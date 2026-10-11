package com.almog.spotifytablet.lyrics.mobile.network.data.providers

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.almog.spotifytablet.lyrics.mobile.core.LrcConverter
import com.almog.spotifytablet.lyrics.mobile.network.data.*
import com.almog.spotifytablet.lyrics.mobile.network.data.spotify.LocalTrackMetadata
import com.almog.spotifytablet.lyrics.mobile.network.data.spotify.SpotifyTrackResolution
import com.almog.spotifytablet.lyrics.mobile.network.data.spotify.SpotifyTrackResolver
import java.io.IOException
import java.net.URLEncoder
import java.util.UUID
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * A source the user describes: one GET to [url] with the song filled in (`{title}`, `{artist}`,
 * `{album}`, `{duration}` in seconds, `{durationMs}`, `{spotifyId}`), answered with TTML, LRC or
 * plain text, as is or in JSON. [path] says where in the JSON (`data.lyrics[0].ttml`); without it
 * the usual field names are tried. [headers] (API keys, mostly) are kept apart from the rest so
 * they never travel in a backup or a shared copy.
 */
data class CustomLyricsSource(
    val id: String,
    val name: String,
    val url: String,
    val path: String = "",
    val headers: List<Pair<String, String>> = emptyList(),
    /** Set anew each time its header values are saved; no key material, just "these changed". */
    val keyRevision: String = "",
) {
    /** Changes whenever what it asks for does, so lyrics cached from an older version aren't reused. */
    val revision: String get() = Integer.toHexString(listOf(url, path, headers.joinToString { it.first }, keyRevision).hashCode())

    companion object {
        const val ID_PREFIX = "custom_"
        const val SHARE_FORMAT = "spicy-lyrics-mobile-source"
        /** Shared while the app was called Spicy Player. */
        private const val OLD_SHARE_FORMAT = "spicy-player-source"
        val PLACEHOLDERS = listOf("title", "artist", "album", "duration", "durationMs", "spotifyId")
        // Both braces escaped: Android's regex engine (ICU) refuses a bare "}", unlike the JVM's.
        private val PLACEHOLDER = Regex("""\{(\w+)\}""")

        fun newId() = ID_PREFIX + UUID.randomUUID().toString().replace("-", "").take(12)

        fun isCustom(id: String) = id.startsWith(ID_PREFIX)

        /** What's wrong with [url] as a template, or null when it's fine. */
        fun problem(url: String): String? {
            val unknown = PLACEHOLDER.findAll(url).map { it.groupValues[1] }.firstOrNull { it !in PLACEHOLDERS }
            return when {
                !url.startsWith("https://") -> "The address has to start with https://."
                unknown != null -> "There's no {$unknown}. Use ${PLACEHOLDERS.joinToString { "{$it}" }}."
                PLACEHOLDER.replace(url, "x").toHttpUrlOrNull() == null -> "That isn't a valid web address."
                else -> null
            }
        }

        /** "Name: value" lines, as typed. */
        fun parseHeaders(text: String): List<Pair<String, String>> = text.lines().mapNotNull { line ->
            val name = line.substringBefore(':', "").trim()
            val value = line.substringAfter(':').trim()
            if (line.indexOf(':') < 0 || !isHeaderName(name) || !isHeaderValue(value)) null else name to value
        }

        /** What HTTP (and OkHttp, which throws otherwise) takes as a header name: ASCII letters, digits, `-` and `_`. */
        fun isHeaderName(name: String) = name.isNotEmpty() && name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "-_" }

        private fun isHeaderValue(value: String) = value.all { it == '\t' || it in ' '..'~' }

        fun headersText(headers: List<Pair<String, String>>) = headers.joinToString("\n") { "${it.first}: ${it.second}" }

        /**
         * A copy to paste somewhere: name, address and path, plus the header names with their
         * values left blank, so whoever pastes it knows a key goes there.
         */
        fun share(source: CustomLyricsSource): String = JsonObject().apply {
            addProperty("format", SHARE_FORMAT)
            addProperty("name", source.name)
            addProperty("url", source.url)
            if (source.path.isNotBlank()) addProperty("path", source.path)
            if (source.headers.isNotEmpty()) add("headers", JsonArray().apply { source.headers.forEach { add(it.first) } })
        }.toString()

        /** A pasted [share], as a new source; null when [text] isn't one. */
        fun fromShare(text: String): CustomLyricsSource? = runCatching {
            val o = JsonParser.parseString(text.trim()).asJsonObject
            if (o.get("format")?.asString !in setOf(SHARE_FORMAT, OLD_SHARE_FORMAT)) return null
            val url = o.get("url").asString.trim()
            if (problem(url) != null) return null
            CustomLyricsSource(
                id = newId(),
                name = o.get("name")?.asString?.trim()?.take(40)?.ifBlank { null } ?: "Custom source",
                url = url,
                path = o.get("path")?.asString?.trim().orEmpty(),
                headers = o.getAsJsonArray("headers")?.mapNotNull { h -> h.asString.trim().takeIf(::isHeaderName)?.let { it to "" } }.orEmpty(),
            )
        }.getOrNull()

        /** The URL with [request] filled in, or null when it needs a Spotify ID it hasn't got. */
        internal fun expand(template: String, request: LyricsLookupRequest, spotifyId: String?): String? {
            var missing = false
            val url = PLACEHOLDER.replace(template) { match ->
                val value = when (match.groupValues[1]) {
                    "title" -> request.title
                    "artist" -> request.artist
                    "album" -> request.album
                    "duration" -> request.durationSeconds.toString()
                    "durationMs" -> (request.durationSeconds * 1_000L).toString()
                    "spotifyId" -> spotifyId ?: "".also { missing = true }
                    else -> match.value
                }
                URLEncoder.encode(value, "UTF-8").replace("+", "%20")
            }
            return url.takeUnless { missing }
        }

        /** The lyrics in a response body: TTML, LRC or plain text, as is or in JSON at [path]. */
        internal fun payloadOf(body: String, path: String): RemoteLyricsPayload? {
            val text = body.trim().removePrefix("\uFEFF")
            if (text.isEmpty()) return null
            val json = if (text.startsWith("{") || text.startsWith("[")) runCatching { JsonParser.parseString(text) }.getOrNull() else null
            val lyrics = when {
                json == null -> text
                path.isNotBlank() -> at(json, path)?.let(::asText)
                else -> guess(json)
            }?.trim()?.takeIf(String::isNotEmpty) ?: return null
            return when {
                lyrics.contains("<tt", ignoreCase = true) -> RemoteLyricsPayload(ttmlLyrics = lyrics)
                // Word stamps would show up as text in line-timed LRC: those go through as TTML.
                LrcConverter.isLrc(lyrics) && WORD_STAMP.containsMatchIn(lyrics) -> LrcConverter.toTtml(lyrics)?.let { RemoteLyricsPayload(ttmlLyrics = it) }
                LrcConverter.isLrc(lyrics) -> RemoteLyricsPayload(syncedLyrics = lyrics)
                else -> RemoteLyricsPayload(plainLyrics = lyrics)
            }
        }

        private val WORD_STAMP = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")
        private val PATH_PART = Regex("""([^.\[\]]+)|\[(\d+)]""")

        /** `data.lyrics[0].ttml` (a leading `$.` is fine too). */
        internal fun at(root: JsonElement, path: String): JsonElement? {
            var node: JsonElement? = root
            for (part in PATH_PART.findAll(path.trim().removePrefix("$").removePrefix("."))) {
                val key = part.groupValues[1]
                node = when {
                    key.isNotEmpty() -> node?.takeIf { it.isJsonObject }?.asJsonObject?.get(key)
                    else -> node?.takeIf { it.isJsonArray }?.asJsonArray?.let { array ->
                        part.groupValues[2].toInt().takeIf { it < array.size() }?.let(array::get)
                    }
                }
            }
            return node?.takeUnless { it.isJsonNull }
        }

        private fun asText(element: JsonElement): String? = when {
            element.isJsonPrimitive -> element.asString
            // A list of lines.
            element.isJsonArray && element.asJsonArray.all { it.isJsonPrimitive } -> element.asJsonArray.joinToString("\n") { it.asString }
            else -> null
        }

        /** The best of the usual fields, at the top or under a usual wrapper. */
        private fun guess(json: JsonElement): String? {
            val roots = buildList {
                val top = json.takeIf { it.isJsonObject }?.asJsonObject
                    ?: json.takeIf { it.isJsonArray }?.asJsonArray?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
                    ?: return null
                add(top)
                listOf("data", "result", "results", "body", "lyrics").forEach { key ->
                    top.get(key)?.let { child ->
                        (child.takeIf { it.isJsonObject } ?: child.takeIf { it.isJsonArray }?.asJsonArray?.firstOrNull())
                            ?.takeIf { it.isJsonObject }?.asJsonObject?.let(::add)
                    }
                }
            }
            for (field in GUESSED_FIELDS) for (root in roots) {
                val value = root.entrySet().firstOrNull { it.key.equals(field, ignoreCase = true) }?.value ?: continue
                asText(value)?.takeIf(String::isNotBlank)?.let { return it }
            }
            return null
        }

        /** Best format first. */
        private val GUESSED_FIELDS = listOf("ttml", "syncedLyrics", "synced", "lrc", "lyrics", "content", "text", "plainLyrics", "plain")
    }
}

/** Asks one [CustomLyricsSource]. */
class CustomLyricsProvider(
    val source: CustomLyricsSource,
    private val client: OkHttpClient,
    private val spotifyResolver: SpotifyTrackResolver,
    /** Where it starts in the order: after the built-in sources. */
    priority: Int,
    /** Sent unless the source sets its own: some APIs refuse OkHttp's default one. */
    private val userAgent: String,
) : RemoteLyricsProvider {
    override val descriptor = LyricsSourceDescriptor(
        source.id, source.name, priority,
        setOf(LyricsCapability.WORD_SYNC, LyricsCapability.LINE_SYNC, LyricsCapability.PLAIN_TEXT),
        upstreamFamily = "custom", releaseChannel = SourceReleaseChannel.EXPERIMENTAL,
    )

    private val needsSpotifyId = "{spotifyId}" in source.url

    override suspend fun warmUp() {
        if (needsSpotifyId) spotifyResolver.warmUp()
    }

    override suspend fun fetch(request: LyricsLookupRequest): ProviderResult {
        val spotifyId = if (!needsSpotifyId) null else request.spotifyTrackId ?: try {
            val match = spotifyResolver.resolve(LocalTrackMetadata(request.title, request.artist, request.album, request.durationSeconds * 1_000L))
            (match as? SpotifyTrackResolution.Matched)?.track?.candidate?.id ?: return ProviderResult.NeedsMatch
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return ProviderResult.Unavailable(ProviderFailureCategory.NETWORK, "Spotify match: ${error.message}", retryable = true)
        }
        val url = CustomLyricsSource.expand(source.url, request, spotifyId) ?: return ProviderResult.NeedsMatch
        return try {
            follow(url).use { response ->
                when {
                    response.code == 404 || response.code == 204 -> ProviderResult.Miss
                    response.code == 429 -> ProviderResult.CoolingDown(
                        RetryAfterParser.deadline(response.header("Retry-After")),
                        "Rate limited (HTTP 429)",
                    )
                    response.code == 401 || response.code == 403 ->
                        ProviderResult.Unavailable(ProviderFailureCategory.AUTHENTICATION, "HTTP ${response.code}: check the source's headers")
                    !response.isSuccessful -> ProviderResult.Unavailable(
                        if (response.code >= 500) ProviderFailureCategory.SERVER else ProviderFailureCategory.CLIENT_REQUEST,
                        "HTTP ${response.code}",
                        retryable = response.code >= 500,
                    )
                    else -> {
                        val body = response.body?.source()?.let { it.request(MAX_BODY_BYTES); it.buffer.readUtf8(minOf(it.buffer.size, MAX_BODY_BYTES)) }.orEmpty()
                        CustomLyricsSource.payloadOf(body, source.path)
                            ?.let { ProviderResult.Hit(it.copy(sourceId = source.id, attribution = LyricsAttribution(source.name))) }
                            ?: ProviderResult.Miss
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            ProviderResult.Unavailable(ProviderFailureCategory.NETWORK, error.message, retryable = true)
        } catch (error: Exception) {
            ProviderResult.Unavailable(ProviderFailureCategory.MALFORMED_RESPONSE, error.message)
        }
    }

    /**
     * GETs [url], following redirects by hand: the source's own headers (its keys) go along only
     * while the address stays on the same scheme, host and port. OkHttp would forward all but
     * `Authorization` to wherever it's sent.
     */
    private suspend fun follow(url: String): Response {
        val origin = url.toHttpUrl()
        var target = origin
        repeat(MAX_REDIRECTS + 1) {
            val sameOrigin = target.scheme == origin.scheme && target.host == origin.host && target.port == origin.port
            val call = Request.Builder().url(target).get().header("User-Agent", userAgent).apply {
                if (sameOrigin) source.headers.filter { it.second.isNotEmpty() }.forEach { (name, value) -> header(name, value) }
            }.build()
            val response = noRedirects.newCall(call).awaitResponse()
            val next = response.takeIf { it.isRedirect }?.header("Location")?.let(target::resolve)
            if (next == null || next.scheme != "https") return response
            response.close()
            target = next
        }
        throw IOException("Too many redirects")
    }

    private val noRedirects = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    private companion object {
        const val MAX_REDIRECTS = 5
        /** Lyrics are kilobytes; a source answering with more is sending something else. */
        const val MAX_BODY_BYTES = 2L * 1024 * 1024
    }
}
