package com.almog.spotifytablet.lyrics.mobile.network.data.providers

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.almog.spotifytablet.lyrics.mobile.network.data.*
import com.almog.spotifytablet.lyrics.mobile.network.data.spotify.SpotifyTrackMatcher
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.Locale
import java.util.Base64
import java.util.zip.InflaterInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

@Singleton
class KugouLyricsProvider @Inject constructor(private val client: OkHttpClient, private val gson: Gson) : RemoteLyricsProvider {
    override val descriptor = LyricsSourceDescriptor(
        "kugou", "Kugou", 70, setOf(LyricsCapability.WORD_SYNC, LyricsCapability.LINE_SYNC),
        releaseChannel = SourceReleaseChannel.EXTENDED,
        defaultEnabled = false,
    )
    override suspend fun fetch(request: LyricsLookupRequest): ProviderResult {
        return try {
        val keyword = "${request.artist} ${request.title}"
        val search = get("https://mobileservice.kugou.com/api/v3/search/song".toHttpUrl().newBuilder()
            .addQueryParameter("format","json").addQueryParameter("keyword",keyword)
            .addQueryParameter("page","1").addQueryParameter("pagesize","8").build())
        val songs = search.getAsJsonObject("data")?.getAsJsonArray("info") ?: return ProviderResult.Miss
        for (song in songs.map { it.asJsonObject }) {
            val title = song.get("songname")?.asString.orEmpty(); val artist = song.get("singername")?.asString.orEmpty()
            if (SpotifyTrackMatcher.normalize(title) != SpotifyTrackMatcher.normalize(request.title)) continue
            if (!SpotifyTrackMatcher.normalize(artist).contains(SpotifyTrackMatcher.normalize(request.artist))) continue
            val duration = song.get("duration")?.asInt ?: request.durationSeconds
            if (kotlin.math.abs(duration - request.durationSeconds) > 8) continue
            val hash = song.get("hash")?.asString ?: continue
            val candidates = get("https://krcs.kugou.com/search".toHttpUrl().newBuilder()
                .addQueryParameter("ver","1").addQueryParameter("man","yes").addQueryParameter("client","mobi")
                .addQueryParameter("keyword",keyword).addQueryParameter("duration",(duration*1000).toString()).addQueryParameter("hash",hash).build())
                .getAsJsonArray("candidates") ?: continue
            val candidate = candidates.firstOrNull()?.asJsonObject ?: continue
            val id = candidate.get("id")?.asString ?: continue; val access = candidate.get("accesskey")?.asString ?: continue
            for (format in listOf("krc","lrc")) {
                val data = get("https://lyrics.kugou.com/download".toHttpUrl().newBuilder()
                    .addQueryParameter("ver","1").addQueryParameter("client","pc").addQueryParameter("id",id)
                    .addQueryParameter("accesskey",access).addQueryParameter("fmt",format).addQueryParameter("charset","utf8").build())
                val encoded = data.get("content")?.asString ?: continue
                if (format == "krc") {
                    KrcCodec.decode(encoded)?.let(KrcCodec::toTtml)?.let { return ProviderResult.Hit(RemoteLyricsPayload(ttmlLyrics=it)) }
                } else {
                    val lrc = String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
                    if (lrc.isNotBlank()) return ProviderResult.Hit(RemoteLyricsPayload(syncedLyrics=lrc))
                }
            }
        }
        ProviderResult.Miss
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (error: IOException) { ProviderResult.Unavailable(ProviderFailureCategory.NETWORK,error.message,true) }
      catch (error: Exception) { ProviderResult.Unavailable(ProviderFailureCategory.MALFORMED_RESPONSE,error.message) }
    }

    private suspend fun get(url: okhttp3.HttpUrl): JsonObject = client.newCall(Request.Builder().url(url).get().build()).awaitResponse().use { r ->
        if (!r.isSuccessful) throw IOException("Kugou HTTP ${r.code}"); gson.fromJson(r.body?.string(),JsonObject::class.java)
    }
}

internal object KrcCodec {
    private val key = byteArrayOf(0x40,0x47,0x61,0x77,0x5e,0x32,0x74,0x47,0x51,0x36,0x31,0x2d,0xce.toByte(),0xd2.toByte(),0x6e,0x69)
    private val line = Regex("^\\[(\\d+),(\\d+)](.*)$")
    private val word = Regex("<(\\d+),(\\d+)(?:,\\d+)?>([^<]*)")
    fun decode(encoded:String):String? = runCatching {
        val raw=Base64.getDecoder().decode(encoded); val encrypted=raw.copyOfRange(4,raw.size)
        val plain=ByteArray(encrypted.size){i -> (encrypted[i].toInt() xor key[i%key.size].toInt()).toByte()}
        InflaterInputStream(ByteArrayInputStream(plain)).bufferedReader().readText()
    }.getOrNull()
    fun toTtml(text:String):String? {
        val ps=text.lineSequence().mapNotNull { row -> val m=line.matchEntire(row.trim())?:return@mapNotNull null
            val start=m.groupValues[1].toLong(); val dur=m.groupValues[2].toLong()
            val spans=word.findAll(m.groupValues[3]).map { w -> val off=w.groupValues[1].toLong();val len=w.groupValues[2].toLong();"<span begin=\"${sec(start+off)}\" end=\"${sec(start+off+len)}\">${xml(w.groupValues[3])}</span>"}.toList()
            if(spans.isEmpty()) null else "<p begin=\"${sec(start)}\" end=\"${sec(start+dur)}\">${spans.joinToString("")}</p>" }.toList()
        if(ps.isEmpty()) return null
        return """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="word"><body><div>${ps.joinToString("")}</div></body></tt>"""
    }
    private fun sec(ms:Long)="${"%.3f".format(Locale.ROOT,ms/1000.0)}s"
    private fun xml(s:String)=s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
}
