package com.almog.spotifytablet.lyrics.mobile.network.data.providers

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.almog.spotifytablet.lyrics.mobile.network.data.*
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** MIT port of Lyrica's lrcmux adapter, requesting word timing first. */
@Singleton
class LrcMuxLyricsProvider @Inject constructor(
    private val client: OkHttpClient,
    private val gson: Gson,
) : RemoteLyricsProvider {
    override val descriptor = LyricsSourceDescriptor(
        id = "lrcmux", displayName = "LRCMux", defaultPriority = 120,
        capabilities = setOf(LyricsCapability.WORD_SYNC, LyricsCapability.LINE_SYNC, LyricsCapability.PLAIN_TEXT),
        releaseChannel = SourceReleaseChannel.EXTENDED,
    )

    override suspend fun fetch(request: LyricsLookupRequest): ProviderResult = try {
        val url = "https://api.lrcmux.dev/get".toHttpUrl().newBuilder()
            .addQueryParameter("artist", request.artist).addQueryParameter("title", request.title)
            .addQueryParameter("duration", request.durationSeconds.toString())
            .addQueryParameter("format", "json")
            .addQueryParameter("level", "word").build()
        client.newCall(Request.Builder().url(url).get().build()).awaitResponse().use { response ->
            if (response.code == 404) return ProviderResult.Miss
            if (!response.isSuccessful) return ProviderResult.Unavailable(
                if (response.code in 400..499) ProviderFailureCategory.CLIENT_REQUEST else ProviderFailureCategory.SERVER,
                "LRCMux returned HTTP ${response.code}", response.code >= 500,
            )
            parse(gson.fromJson(response.body?.string(), JsonObject::class.java))
        }
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (error: IOException) { ProviderResult.Unavailable(ProviderFailureCategory.NETWORK, error.message, true) }
      catch (error: Exception) { ProviderResult.Unavailable(ProviderFailureCategory.MALFORMED_RESPONSE, error.message) }

    internal fun parse(root: JsonObject): ProviderResult {
        val lines = root.getAsJsonArray("lines") ?: return ProviderResult.Miss
        // Where LRCMux found them (KuGou, LRCLIB, Genius, YouTube Music): ranked in that source's place.
        val attribution = LyricsAttribution("LRCMux", originName = root.getAsJsonObject("meta")?.getAsJsonObject("source")
            ?.get("name")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf(String::isNotBlank))
        val ttml = wordTtml(lines)
        if (ttml != null) return ProviderResult.Hit(RemoteLyricsPayload(ttmlLyrics = ttml, attribution = attribution))
        val synced = lines.mapNotNull { element ->
            val line = element.asJsonObject
            val text = line.get("text")?.asString?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val start = line.number("start") ?: return@mapNotNull null
            "[${clock(start)}]$text"
        }.joinToString("\n").takeIf(String::isNotBlank)
        val plain = lines.mapNotNull { it.asJsonObject.get("text")?.asString }.joinToString("\n").takeIf(String::isNotBlank)
        return if (synced != null || plain != null) ProviderResult.Hit(RemoteLyricsPayload(plain, synced, attribution = attribution)) else ProviderResult.Miss
    }

    private fun wordTtml(lines: JsonArray): String? {
        val paragraphs = lines.mapNotNull { element ->
            val line = element.asJsonObject
            val words = line.getAsJsonArray("words") ?: return@mapNotNull null
            val spans = words.mapNotNull { wordElement ->
                val word = wordElement.asJsonObject
                val text = word.get("text")?.asString ?: return@mapNotNull null
                val start = word.number("start") ?: return@mapNotNull null
                val end = word.number("end") ?: return@mapNotNull null
                "<span begin=\"${seconds(start)}\" end=\"${seconds(end)}\"> ${xml(text)}</span>"
            }
            if (spans.isEmpty()) return@mapNotNull null
            val start = line.number("start") ?: words.first().asJsonObject.number("start") ?: return@mapNotNull null
            val end = line.number("end") ?: words.last().asJsonObject.number("end") ?: return@mapNotNull null
            "<p begin=\"${seconds(start)}\" end=\"${seconds(end)}\">${spans.joinToString("")}</p>"
        }
        if (paragraphs.isEmpty()) return null
        return """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="word"><body><div>${paragraphs.joinToString("")}</div></body></tt>"""
    }

    private fun JsonObject.number(key: String): Double? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
    private fun seconds(v: Double) = "${"%.3f".format(Locale.ROOT, if (v > 10_000) v / 1000 else v)}s"
    private fun clock(v: Double): String { val s = if (v > 10_000) v / 1000 else v; return "%02d:%05.2f".format(Locale.ROOT, (s/60).toInt(), s%60) }
    private fun xml(s: String) = s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
}
