package com.almog.spotifytablet.lyrics.mobile.network.data.providers

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.almog.spotifytablet.lyrics.mobile.network.data.*
import com.almog.spotifytablet.lyrics.mobile.network.data.blend.BlendDonors
import com.almog.spotifytablet.lyrics.mobile.network.data.spotify.SpotifyTrackMatcher
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

@Singleton
class NetEaseLyricsProvider @Inject constructor(private val client: OkHttpClient, private val gson: Gson) : RemoteLyricsProvider {
    override val descriptor = LyricsSourceDescriptor(
        "netease", "NetEase", 90,
        setOf(LyricsCapability.WORD_SYNC, LyricsCapability.LINE_SYNC, LyricsCapability.TRANSLITERATION),
        releaseChannel = SourceReleaseChannel.EXTENDED,
        defaultEnabled = false,
    )

    override suspend fun fetch(request: LyricsLookupRequest): ProviderResult = try {
        lookup(request)
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (error: IOException) { ProviderResult.Unavailable(ProviderFailureCategory.NETWORK, error.message, true) }
      catch (error: Exception) { ProviderResult.Unavailable(ProviderFailureCategory.MALFORMED_RESPONSE, error.message) }

    private suspend fun lookup(request: LyricsLookupRequest): ProviderResult {
        val searchUrl = "$BASE/cloudsearch/pc".toHttpUrl().newBuilder()
            .addQueryParameter("s", "${request.artist} ${request.title}").addQueryParameter("type", "1")
            .addQueryParameter("offset", "0").addQueryParameter("limit", "8").build()
        val search = getJson(searchUrl)
        val candidates = search.getAsJsonObject("result")?.getAsJsonArray("songs") ?: return ProviderResult.Miss
        val songs = candidates.map { it.asJsonObject }.filter { item ->
            val name = item.get("name")?.asString.orEmpty()
            val artists = (item.getAsJsonArray("ar") ?: item.getAsJsonArray("artists"))?.joinToString(" ") { it.asJsonObject.get("name").asString }.orEmpty()
            val duration = (item.get("dt") ?: item.get("duration"))?.asLong?.div(1000) ?: 0
            SpotifyTrackMatcher.normalize(name) == SpotifyTrackMatcher.normalize(request.title) &&
                SpotifyTrackMatcher.normalize(artists).contains(SpotifyTrackMatcher.normalize(request.artist)) &&
                (duration == 0L || kotlin.math.abs(duration - request.durationSeconds) <= 8)
        }.take(TRIES)
        if (songs.isEmpty()) return ProviderResult.Miss
        // Word timing belongs to a release, not a song, and the search's favourite is often an
        // untimed or credits-only copy: take the first word-timed candidate, else the first with
        // timed lines.
        val lyrics = coroutineScope { songs.map { song -> async { lyrics(song.get("id").asString) } }.awaitAll() }
        // A copy whose lyric is only "纯音乐，请欣赏" (instrumental) says nothing about the others.
        lyrics.firstNotNullOfOrNull { data ->
            data.getAsJsonObject("yrc")?.get("lyric")?.asString
                ?.takeIf { it.isNotBlank() && !BlendDonors.isNoWordsNote(it.lines().map { line -> line.replace(ANY_STAMP, "").replace(YRC_STAMP, "") }) }
                ?.let(YrcToTtml::convert)
        }?.let { return ProviderResult.Hit(RemoteLyricsPayload(ttmlLyrics = it)) }
        val lrcs = lyrics.mapNotNull { data -> data.getAsJsonObject("lrc")?.get("lyric")?.asString?.takeIf(String::isNotBlank) }
            .filterNot { lrc -> BlendDonors.isNoWordsNote(lrc.lines().map { it.replace(ANY_STAMP, "") }) }
        lrcs.firstOrNull(::hasTimedLyric)?.let { return ProviderResult.Hit(RemoteLyricsPayload(syncedLyrics = it)) }
        // No stamps worth the name: the words alone, if there are any besides the credits.
        val plain = lrcs.firstNotNullOfOrNull { lrc ->
            lrc.lines().map { it.replace(ANY_STAMP, "").trim() }
                .filter { it.isNotEmpty() && !BlendDonors.isCredit(it) }
                .takeIf { it.isNotEmpty() }?.joinToString("\n")
        }
        return if (plain != null) ProviderResult.Hit(RemoteLyricsPayload(plainLyrics = plain)) else ProviderResult.Miss
    }

    private suspend fun lyrics(id: String): JsonObject = getJson("$BASE/song/lyric".toHttpUrl().newBuilder()
        .addQueryParameter("os", "pc").addQueryParameter("id", id)
        .addQueryParameter("lv", "-1").addQueryParameter("kv", "-1")
        .addQueryParameter("tv", "-1").addQueryParameter("yv", "-1").addQueryParameter("rv", "-1").build())

    /** An LRC with a stamped line that is a lyric, not a credit ("作词 : ..."). */
    private fun hasTimedLyric(lrc: String): Boolean = lrc.lineSequence().any { line ->
        val m = TIMED_LINE.find(line) ?: return@any false
        val body = line.substring(m.range.last + 1).trim()
        body.isNotEmpty() && !BlendDonors.isCredit(body)
    }

    private suspend fun getJson(url: okhttp3.HttpUrl): JsonObject {
        val request = Request.Builder().url(url).header("Referer", "https://music.163.com").get().build()
        return client.newCall(request).awaitResponse().use { response ->
            if (response.code == 404) return@use JsonObject()
            if (!response.isSuccessful) throw IOException("NetEase HTTP ${response.code}")
            gson.fromJson(response.body?.string(), JsonObject::class.java)
        }
    }

    private companion object {
        const val BASE = "https://music.163.com/api"
        /** How many equally good candidates are worth a lyric request. */
        const val TRIES = 3
        val TIMED_LINE = Regex("""^\[\d+:\d+(?:[.:]\d+)?]""")
        val ANY_STAMP = Regex("""\[[^\]]*]""")
        val YRC_STAMP = Regex("""\(\d+,\d+,[^)]*\)""")
    }
}

/**
 * NetEase's word-timed lyrics: `[lineStart,lineLength](wordStart,wordLength,0)word…`, in ms.
 * Word starts are absolute (the first word of a line starting at 28480 says 28480).
 */
internal object YrcToTtml {
    /** Rights notices ("词版权管理方：…", "录音作品及MV版权：…"), which the shared credit check lets through. */
    private val rights = Regex("^[^:：]{0,20}版权[^:：]{0,8}[:：]")
    private const val RELATIVE_SLACK_MS = 1_000L
    private val line = Regex("^\\[(\\d+),(\\d+)](.*)$")
    private val word = Regex("\\((\\d+),(\\d+),[^)]*\\)([^()]*)")
    fun convert(yrc: String): String? {
        val paragraphs = yrc.lineSequence().mapNotNull { raw ->
            val match = line.matchEntire(raw.trim()) ?: return@mapNotNull null
            val start = match.groupValues[1].toLong(); val duration = match.groupValues[2].toLong()
            val words = word.findAll(match.groupValues[3]).mapNotNull { token ->
                val at = token.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                val length = token.groupValues[2].toLongOrNull() ?: return@mapNotNull null
                val text = token.groupValues[3]; if (text.isEmpty()) return@mapNotNull null
                Triple(at, length, text)
            }.toList()
            if (words.isEmpty()) return@mapNotNull null
            val body = words.joinToString("") { it.third }.trim()
            if (BlendDonors.isCredit(body) || rights.containsMatchIn(body)) return@mapNotNull null
            // Should a line ever come with its words timed from the line's start, they'd sit
            // well before it: add the start back then.
            val base = if (words.first().first + RELATIVE_SLACK_MS < start) start else 0L
            val spans = words.joinToString("") { (at, length, text) ->
                "<span begin=\"${sec(base + at)}\" end=\"${sec(base + at + length)}\">${xml(text)}</span>"
            }
            "<p begin=\"${sec(start)}\" end=\"${sec(start + duration)}\">$spans</p>"
        }.toList()
        if (paragraphs.isEmpty()) return null
        return """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="word"><body><div>${paragraphs.joinToString("")}</div></body></tt>"""
    }
    private fun sec(ms: Long) = "${"%.3f".format(Locale.ROOT, ms / 1000.0)}s"
    private fun xml(s: String) = s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
}
