package com.almog.spotifytablet.lyrics.mobile.network.data.providers

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.almog.spotifytablet.lyrics.mobile.network.data.LyricsCapability
import com.almog.spotifytablet.lyrics.mobile.network.data.LyricsAttribution
import com.almog.spotifytablet.lyrics.mobile.network.data.LyricsContributor
import com.almog.spotifytablet.lyrics.mobile.network.data.LyricsLookupRequest
import com.almog.spotifytablet.lyrics.mobile.network.data.LyricsSourceDescriptor
import com.almog.spotifytablet.lyrics.mobile.network.data.ProviderFailureCategory
import com.almog.spotifytablet.lyrics.mobile.network.data.ProviderResult
import com.almog.spotifytablet.lyrics.mobile.network.data.RemoteLyricsPayload
import com.almog.spotifytablet.lyrics.mobile.network.data.RemoteLyricsProvider
import com.almog.spotifytablet.lyrics.mobile.network.data.RemoteLyricsQuality
import com.almog.spotifytablet.lyrics.mobile.network.data.measuredQuality
import com.almog.spotifytablet.lyrics.mobile.network.data.RetryAfterParser
import com.almog.spotifytablet.lyrics.mobile.network.data.SourceReleaseChannel
import com.almog.spotifytablet.lyrics.mobile.network.data.awaitResponse
import com.almog.spotifytablet.lyrics.mobile.network.data.spotify.LocalTrackMetadata
import com.almog.spotifytablet.lyrics.mobile.network.data.spotify.SpotifyTrackResolution
import com.almog.spotifytablet.lyrics.mobile.network.data.spotify.SpotifyTrackResolver
import com.almog.spotifytablet.lyrics.mobile.network.data.BackoffLadder
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SpicyLyricsClientKey

@Singleton
class SpicyLyricsProvider @Inject constructor(
    private val client: OkHttpClient,
    private val gson: Gson,
    private val spotifyResolver: SpotifyTrackResolver,
    @SpicyLyricsClientKey private val apiKey: String,
) : RemoteLyricsProvider {
    override val descriptor = LyricsSourceDescriptor(
        id = "spicy_lyrics",
        displayName = "Spicy Lyrics",
        defaultPriority = 10,
        capabilities = setOf(
            LyricsCapability.WORD_SYNC,
            LyricsCapability.LINE_SYNC,
            LyricsCapability.PLAIN_TEXT,
            LyricsCapability.TRANSLATION,
            LyricsCapability.TRANSLITERATION,
            LyricsCapability.CONTRIBUTOR_CREDITS,
        ),
        releaseChannel = SourceReleaseChannel.RECOMMENDED,
    )

    /** A stalled server would otherwise hold a request open for as long as it trickles bytes. */
    private val deadlineClient = client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
    private val backoff = BackoffLadder()
    /** The last refusal, said while resting from it. */
    @Volatile private var restReason: String? = null

    override suspend fun warmUp() {
        if (apiKey.isNotBlank()) spotifyResolver.warmUp()
    }

    override suspend fun fetch(request: LyricsLookupRequest): ProviderResult {
        if (apiKey.isBlank()) {
            return ProviderResult.Unavailable(
                ProviderFailureCategory.AUTHENTICATION,
                "SPICY_LYRICS_CLIENT_KEY was not supplied at build time",
            )
        }

        val ids = request.spotifyTrackId?.let(::listOf) ?: run {
            // The match is a Spotify search, which fails on its own terms (rate limits, a rotated
            // query hash). Left uncaught, those reached the lookup as an "unknown" error.
            val resolution = try {
                spotifyResolver.resolve(
                    LocalTrackMetadata(
                        title = request.title,
                        artist = request.artist,
                        album = request.album,
                        durationMs = request.durationSeconds * 1_000L,
                    )
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IOException) {
                return ProviderResult.Unavailable(ProviderFailureCategory.NETWORK, "Spotify match: ${error.message}", retryable = true)
            } catch (error: Exception) {
                return ProviderResult.Unavailable(ProviderFailureCategory.MALFORMED_RESPONSE, "Spotify match: ${error.message ?: error::class.simpleName}")
            }
            when (resolution) {
                is SpotifyTrackResolution.Matched ->
                    (listOf(resolution.track) + resolution.alternates).map { it.candidate.id }
                is SpotifyTrackResolution.Ambiguous, SpotifyTrackResolution.NotFound -> return ProviderResult.NeedsMatch
            }
        }
        if (ids.size == 1) return fetchId(ids.single())
        // A community upload lives on one Spotify ID; the song's other IDs (single, album,
        // compilation) often answer with the catalogue copy instead, or nothing. All are asked at
        // once, so a song whose best ID has no upload costs one round trip, not two.
        val results = coroutineScope {
            val asks = ids.map { id -> async { fetchId(id) } }
            val first = asks.first().await()
            if (first.quality() == RemoteLyricsQuality.WORD_SYNCED && first.isUpload()) {
                asks.drop(1).forEach { it.cancel() }
                return@coroutineScope listOf(first)
            }
            listOf(first) + asks.drop(1).awaitAll()
        }
        // Better timing first, then an upload over the catalogue copy, then the best-ranked ID.
        return results.filterIsInstance<ProviderResult.Hit>()
            .maxWithOrNull(compareBy<ProviderResult.Hit>({ it.quality().rank }, { it.isUpload() }).thenByDescending { results.indexOf(it) })
            ?: results.firstOrNull { it !is ProviderResult.Miss }
            ?: ProviderResult.Miss
    }

    private fun ProviderResult.quality() =
        (this as? ProviderResult.Hit)?.payload?.measuredQuality() ?: RemoteLyricsQuality.NONE

    private fun ProviderResult.isUpload() =
        (this as? ProviderResult.Hit)?.payload?.attribution?.let { it.uploader != null || it.maker != null } == true

    private suspend fun fetchId(spotifyId: String): ProviderResult {
        val url = "https://api.spicylyrics.org/v1/lyrics"
            .toHttpUrl()
            .newBuilder()
            .addPathSegment(spotifyId)
            .build()
        val httpRequest = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()

        backoff.openUntil(System.currentTimeMillis())?.let { return ProviderResult.CoolingDown(Instant.ofEpochMilli(it), restReason) }
        return try {
            deadlineClient.newCall(httpRequest).awaitResponse().use { response ->
                if (response.code == 200 || response.code == 404) {
                    backoff.success()
                    return if (response.code == 404) ProviderResult.Miss else parseHit(response.body?.string().orEmpty())
                }
                // The API says what went wrong, e.g. which request window ran out.
                val reason = "Spicy Lyrics returned HTTP ${response.code}" +
                    apiErrorMessage(response.body?.string().orEmpty())?.let { ": $it" }.orEmpty()
                // A spent request window says when it refills (RateLimit-Reset, in seconds), which
                // beats the ladder's guess.
                val retryAfter = (response.header("Retry-After") ?: response.header("RateLimit-Reset")?.takeIf { response.code == 429 })
                    ?.let(RetryAfterParser::deadline)
                if (response.code in REFUSED_STATUSES) {
                    restReason = reason
                    backoff.failure(System.currentTimeMillis(), retryAfter?.toEpochMilli())
                        ?.let { return ProviderResult.CoolingDown(Instant.ofEpochMilli(it), reason) }
                } else backoff.success()
                when (response.code) {
                    429 -> retryAfter?.let { ProviderResult.CoolingDown(it, reason) }
                        ?: ProviderResult.Unavailable(ProviderFailureCategory.SERVER, reason, retryable = true)
                    503 -> ProviderResult.Queued(retryAfter)
                    401, 403 -> ProviderResult.Unavailable(ProviderFailureCategory.AUTHENTICATION, reason)
                    in 400..499 -> ProviderResult.Unavailable(ProviderFailureCategory.CLIENT_REQUEST, reason)
                    else -> ProviderResult.Unavailable(ProviderFailureCategory.SERVER, reason, retryable = response.code >= 500)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            // Timeouts included: an overloaded server usually stalls rather than refuses.
            val reason = error.message ?: error::class.simpleName
            restReason = reason
            backoff.failure(System.currentTimeMillis())?.let { return ProviderResult.CoolingDown(Instant.ofEpochMilli(it), reason) }
            ProviderResult.Unavailable(ProviderFailureCategory.NETWORK, error.message, retryable = true)
        } catch (error: Exception) {
            ProviderResult.Unavailable(ProviderFailureCategory.MALFORMED_RESPONSE, error.message)
        }
    }

    private companion object {
        /** Statuses that mean the server (or the edge in front of it) refused us, not that nothing is there. */
        val REFUSED_STATUSES = setOf(403, 408, 425, 429, 500, 502, 503, 504)
    }

    internal fun parseHit(json: String): ProviderResult {
        val root = gson.fromJson(json, JsonObject::class.java)
        if (root.get("Status")?.asInt != 200) return ProviderResult.Miss
        val body = root.getAsJsonObject("Body") ?: return malformed("Missing Body")
        // Syllable/Line responses carry Content; Static ones carry only Lines[].Text.
        val content = body.getAsJsonArray("Content")
        val ttml = content?.let { SpicyLyricsTtmlConverter.convert(body, it) }
        val plain = body.getAsJsonArray("Lines")
            ?.mapNotNull { it.takeIf { line -> line.isJsonObject }?.asJsonObject?.get("Text")?.takeIf { text -> text.isJsonPrimitive }?.asString }
            ?.joinToString("\n")?.takeIf(String::isNotBlank)
        if (ttml == null && plain == null) return malformed("No usable lyric content")
        val upload = body.getAsJsonObject("UploadAttribution")
        return ProviderResult.Hit(RemoteLyricsPayload(
            ttmlLyrics = ttml,
            plainLyrics = plain,
            attribution = LyricsAttribution(
                providerName = "Spicy Lyrics",
                originName = spicyOriginName(body.get("source")?.takeIf { it.isJsonPrimitive }?.asString),
                songwriters = body.getAsJsonArray("SongWriters")?.mapNotNull { it.takeIf { value -> value.isJsonPrimitive }?.asString }.orEmpty(),
                maker = upload?.contributor("Maker"),
                uploader = upload?.contributor("Uploader"),
            ),
        ))
    }

    private fun malformed(message: String) = ProviderResult.Unavailable(
        ProviderFailureCategory.MALFORMED_RESPONSE,
        message,
    )
}

/** A maker or uploader in a Spicy Lyrics upload attribution (RMM Revival passes them on as is). */
internal fun JsonObject.contributor(name: String): LyricsContributor? {
    val value = get(name) ?: return null
    if (value.isJsonPrimitive) return value.asString.takeIf(String::isNotBlank)?.let(::LyricsContributor)
    if (!value.isJsonObject) return null
    val item = value.asJsonObject
    val username = sequenceOf("username", "Username", "name", "Name")
        .mapNotNull { key -> item.get(key)?.takeIf { it.isJsonPrimitive }?.asString }
        .firstOrNull(String::isNotBlank) ?: return null
    val url = sequenceOf("url", "Url", "profileUrl", "ProfileUrl")
        .mapNotNull { key -> item.get(key)?.takeIf { it.isJsonPrimitive }?.asString }
        .firstOrNull { it.startsWith("https://") }
    val avatar = item.get("avatar")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.startsWith("https://") }
    return LyricsContributor(username, url, avatar)
}

/** The message in a Spicy Lyrics API error (`{"Body":{"error","message"}}`), if the body is one. */
internal fun apiErrorMessage(body: String): String? = runCatching {
    val error = com.google.gson.JsonParser.parseString(body).asJsonObject.getAsJsonObject("Body")
    sequenceOf("message", "error")
        .mapNotNull { error.get(it)?.takeIf { value -> value.isJsonPrimitive }?.asString?.trim() }
        .firstOrNull(String::isNotEmpty)
}.getOrNull()

/** Where Spicy Lyrics' API says a song's lyrics come from, by the names the ranking knows. */
internal fun spicyOriginName(raw: String?): String = when (raw?.trim()?.lowercase()?.replace('-', '_')?.replace(' ', '_')) {
    "apple", "apple_music", "am", "aml" -> "Apple Music"
    "spotify", "spotify_lyrics", "spt" -> "Spotify"
    "spicy", "spicy_lyrics", "community", "spl" -> "Spicy Lyrics Community"
    null, "" -> "Spicy Lyrics"
    else -> raw.trim()
}

internal object SpicyLyricsTtmlConverter {
    fun convert(body: JsonObject, content: JsonArray): String? {
        val defaultAgent = content.mapNotNull { it.takeIf { value -> value.isJsonObject }?.asJsonObject }
            .firstNotNullOfOrNull { it.string("Agent") ?: it.getAsJsonObject("Lead")?.string("Agent") }
        val transliterations = StringBuilder()
        var wordTimed = false
        val paragraphs = buildList {
            content.forEach { element ->
                val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                if (item.string("Type").equals("Interlude", ignoreCase = true)) return@forEach
                val lead = item.getAsJsonObject("Lead")
                val agent = item.string("Agent") ?: lead?.string("Agent")
                val opposite = item.get("OppositeAligned")?.takeIf { it.isJsonPrimitive }?.asBoolean
                    ?: (agent != null && defaultAgent != null && agent != defaultAgent)
                val key = "L${size + 1}"
                if (lead == null) {
                    // Line-type responses carry the text and timing on the item itself.
                    lineParagraph(key, item, agent, opposite)?.let(::add)
                    return@forEach
                }
                wordTimed = true
                paragraph(key, lead, item.getAsJsonArray("Background"), agent, opposite)?.let(::add) ?: return@forEach
                transliteration(lead.getAsJsonArray("Syllables"), item.getAsJsonArray("Background"))?.let {
                    transliterations.append("<text for=\"$key\">$it</text>")
                }
            }
        }
        if (paragraphs.isEmpty()) return null
        val writers = body.getAsJsonArray("SongWriters")
            ?.mapNotNull { it.takeIf { value -> value.isJsonPrimitive }?.asString }
            .orEmpty()
            .joinToString("") { "<songwriter>${xml(it)}</songwriter>" }
        return """<?xml version="1.0" encoding="UTF-8"?><tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" xmlns:spicy="https://spicylyrics.org/ns/ttml" itunes:timing="${if (wordTimed) "Word" else "Line"}"><head><metadata><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal"><songwriters>$writers</songwriters>${if (transliterations.isEmpty()) "" else "<transliterations><transliteration>$transliterations</transliteration></transliterations>"}</iTunesMetadata></metadata></head><body><div>${paragraphs.joinToString("")}</div></body></tt>"""
    }

    /** The API's per-syllable romanization as Apple-style spans; the parser matches them to syllables by timing. */
    private fun transliteration(lead: JsonArray?, backgrounds: JsonArray?): String? {
        fun romanSpans(syllables: JsonArray?) = syllables?.mapNotNull { element ->
            val syllable = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val roman = syllable.string("TransliteratedText")?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
            val start = syllable.number("StartTime") ?: return@mapNotNull null
            val end = syllable.number("EndTime") ?: return@mapNotNull null
            "<span begin=\"${seconds(start)}\" end=\"${seconds(end)}\">${xml(roman)}</span>"
        }.orEmpty()
        val leadSpans = romanSpans(lead).joinToString("")
        val bgSpans = backgrounds?.mapNotNull { it.takeIf { value -> value.isJsonObject }?.asJsonObject }.orEmpty()
            .flatMap { romanSpans(it.getAsJsonArray("Syllables")) }.joinToString("")
        val all = leadSpans + if (bgSpans.isEmpty()) "" else "<span ttm:role=\"x-bg\">$bgSpans</span>"
        return all.ifEmpty { null }
    }

    private fun lineParagraph(key: String, item: JsonObject, agent: String?, opposite: Boolean): String? {
        val text = item.string("Text")?.takeIf(String::isNotBlank) ?: return null
        val start = item.number("StartTime") ?: return null
        val end = item.number("EndTime") ?: return null
        val agentAttribute = agent?.let { " ttm:agent=\"${xml(it)}\"" }.orEmpty()
        return "<p begin=\"${seconds(start)}\" end=\"${seconds(end)}\" itunes:key=\"$key\"$agentAttribute spicy:oppositeAligned=\"$opposite\">${xml(text)}</p>"
    }

    private fun paragraph(key: String, lead: JsonObject, backgrounds: JsonArray?, agent: String?, opposite: Boolean): String? {
        val leadSpans = spans(lead.getAsJsonArray("Syllables"))
        val bgGroups = backgrounds?.mapNotNull { element ->
            val group = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            spans(group.getAsJsonArray("Syllables")).takeIf(List<TimedText>::isNotEmpty)
        }.orEmpty()
        // A line is dropped only when its lead and every background group
        // are empty: a line can be background vocals alone (Industry Baby opens with one).
        if (leadSpans.isEmpty() && bgGroups.isEmpty()) return null
        val start = lead.number("StartTime") ?: (leadSpans + bgGroups.flatten()).minOf { it.start }
        val end = lead.number("EndTime") ?: (leadSpans + bgGroups.flatten()).maxOf { it.end }
        val bg = bgGroups.joinToString("") { group ->
            "<span ttm:role=\"x-bg\">${group.joinToString("") { it.xmlSpan() }}</span>"
        }
        val agentAttribute = agent?.let { " ttm:agent=\"${xml(it)}\"" }.orEmpty()
        return "<p begin=\"${seconds(start)}\" end=\"${seconds(end)}\" itunes:key=\"$key\"$agentAttribute spicy:oppositeAligned=\"$opposite\">${leadSpans.joinToString("") { it.xmlSpan() }}$bg</p>"
    }

    private fun spans(array: JsonArray?): List<TimedText> = array?.mapIndexedNotNull { index, element ->
        val word = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapIndexedNotNull null
        val text = word.string("Text") ?: return@mapIndexedNotNull null
        val start = word.number("StartTime") ?: return@mapIndexedNotNull null
        val end = word.number("EndTime") ?: return@mapIndexedNotNull null
        // Spicy marks whether this syllable joins the NEXT one, while TTML
        // spacing is expressed before the current span.
        val attached = index > 0 && array[index - 1].takeIf { it.isJsonObject }
            ?.asJsonObject?.get("IsPartOfWord")?.asBoolean == true
        TimedText(text, start, end, attached)
    }.orEmpty()

    private data class TimedText(val text: String, val start: Double, val end: Double, val attached: Boolean) {
        fun xmlSpan(): String = "<span begin=\"${seconds(start)}\" end=\"${seconds(end)}\">${if (!attached) " " else ""}${xml(text)}</span>"
    }

    private fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.number(name: String): Double? = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
    private fun seconds(value: Double) = "${"%.3f".format(java.util.Locale.ROOT, value)}s"
    private fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}
