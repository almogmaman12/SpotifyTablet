package com.almog.spotifytablet.lyrics.mobile.network.data.providers

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.almog.spotifytablet.lyrics.mobile.network.data.*
import com.almog.spotifytablet.lyrics.mobile.network.data.spotify.SpotifyTrackMatcher
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.Base64
import java.util.zip.InflaterInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

@Singleton
class QqMusicLyricsProvider @Inject constructor(private val client:OkHttpClient,private val gson:Gson):RemoteLyricsProvider{
 override val descriptor=LyricsSourceDescriptor("qq_music","QQ Music",80,setOf(LyricsCapability.WORD_SYNC,LyricsCapability.LINE_SYNC),releaseChannel=SourceReleaseChannel.EXTENDED,defaultEnabled=false)
 override suspend fun fetch(request:LyricsLookupRequest):ProviderResult { return try{
  val headers=mapOf("Referer" to "https://y.qq.com/")
  val search=gson.toJson(mapOf("req_1" to mapOf("module" to "music.search.SearchCgiService", "method" to "DoSearchForQQMusicDesktop", "param" to mapOf("num_per_page" to 8, "page_num" to 1, "query" to "${request.artist} ${request.title}", "search_type" to 0))))
  val root=client.newCall(Request.Builder().url("https://u.y.qq.com/cgi-bin/musicu.fcg").header("Referer","https://y.qq.com/").post(search.toRequestBody("application/json".toMediaType())).build()).awaitResponse().use { response ->
   if(!response.isSuccessful)throw ProviderHttpException("QQ",response.code)
   gson.fromJson(response.body?.string(),JsonObject::class.java)
  }
  val songs=root.getAsJsonObject("req_1")?.getAsJsonObject("data")?.getAsJsonObject("body")?.getAsJsonObject("song")?.getAsJsonArray("list")?:return ProviderResult.Miss
  for(song in songs.map{it.asJsonObject}){val title=song.get("name")?.asString.orEmpty();val artists=song.getAsJsonArray("singer")?.joinToString(" "){it.asJsonObject.get("name").asString}.orEmpty();val dur=song.get("interval")?.asInt?:0
   if(SpotifyTrackMatcher.normalize(title)!=SpotifyTrackMatcher.normalize(request.title)||!SpotifyTrackMatcher.normalize(artists).contains(SpotifyTrackMatcher.normalize(request.artist))||(dur>0&&kotlin.math.abs(dur-request.durationSeconds)>8))continue
   val mid=song.get("mid")?.asString?:continue
   fetchQrc(mid)?.let { return ProviderResult.Hit(RemoteLyricsPayload(ttmlLyrics=it)) }
   val lyric="https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg".toHttpUrl().newBuilder().addQueryParameter("songmid",mid).addQueryParameter("format","json").addQueryParameter("nobase64","1").build()
   val data=getJson(lyric,headers);var text=data.get("lyric")?.asString.orEmpty();if(text.isBlank())continue
   if(!text.trim().startsWith("["))text=runCatching{String(Base64.getDecoder().decode(text),Charsets.UTF_8)}.getOrDefault(text)
   if(text.isNotBlank())return ProviderResult.Hit(RemoteLyricsPayload(syncedLyrics=text))
  };ProviderResult.Miss
 }catch(c:CancellationException){throw c}catch(e:ProviderHttpException){e.unavailable()}catch(e:IOException){ProviderResult.Unavailable(ProviderFailureCategory.NETWORK,e.message,true)}catch(e:Exception){ProviderResult.Unavailable(ProviderFailureCategory.MALFORMED_RESPONSE,e.message)} }
 private suspend fun getJson(url:okhttp3.HttpUrl,headers:Map<String,String>):JsonObject{val b=Request.Builder().url(url);headers.forEach{(k,v)->b.header(k,v)};return client.newCall(b.get().build()).awaitResponse().use{r->if(!r.isSuccessful)throw ProviderHttpException("QQ",r.code);gson.fromJson(r.body?.string(),JsonObject::class.java)}}
 private suspend fun fetchQrc(songMid:String):String? {
  val payload="""{"comm":{"ct":"19","cv":"1873","uin":"0"},"req":{"module":"music.musichallSong.PlayLyricInfo","method":"GetPlayLyricInfo","param":{"songMID":"$songMid","qrc":1,"qrc_t":0,"trans":1,"roma":1,"crypt":1}}}"""
  return client.newCall(Request.Builder().url("https://u.y.qq.com/cgi-bin/musicu.fcg").header("Referer","https://y.qq.com/").post(payload.toRequestBody("application/json".toMediaType())).build()).awaitResponse().use { response ->
   if(!response.isSuccessful)return@use null
   val root=gson.fromJson(response.body?.string(),JsonObject::class.java);val value=root.getAsJsonObject("req")?.getAsJsonObject("data")?.get("lyric")?.asString?:return@use null
   val decoded=when { value.trim().startsWith("[") -> value; value.length>64&&value.all{it.isWhitespace()||it in "0123456789abcdefABCDEF"}->QrcCodec.decrypt(value); else->null }
   decoded?.let(QrcCodec::toTtml)
  }
 }
}

@Singleton
class KuwoLyricsProvider @Inject constructor(private val client:OkHttpClient):RemoteLyricsProvider{
 override val descriptor=LyricsSourceDescriptor("kuwo","Kuwo",130,setOf(LyricsCapability.LINE_SYNC),releaseChannel=SourceReleaseChannel.EXTENDED,defaultEnabled=false)
 override suspend fun fetch(request:LyricsLookupRequest):ProviderResult { return try{
  val url="https://search.kuwo.cn/r.s".toHttpUrl().newBuilder().addQueryParameter("all","${request.artist} ${request.title}").addQueryParameter("ft","music").addQueryParameter("client","kt").addQueryParameter("cluster","0").addQueryParameter("pn","0").addQueryParameter("rn","8").addQueryParameter("rformat","json").addQueryParameter("encoding","utf8")
   // Without these the search leaves out licensed originals and returns only covers and remixes.
   .addQueryParameter("vipver","1").addQueryParameter("strategy","2012").addQueryParameter("mobi","1").addQueryParameter("issubtitle","1").addQueryParameter("show_copyright_off","1").build()
  for(song in kuwoSearchResults(getBytes(url).toString(Charsets.UTF_8))){
   if(SpotifyTrackMatcher.normalize(song.title)!=SpotifyTrackMatcher.normalize(request.title)||!SpotifyTrackMatcher.normalize(song.artist).contains(SpotifyTrackMatcher.normalize(request.artist)))continue
   if(song.durationSeconds>0&&request.durationSeconds>0&&kotlin.math.abs(song.durationSeconds-request.durationSeconds)>8)continue
   val query=Base64.getEncoder().encodeToString("user=12345,web,web,web&requester=localhost&req=1&rid=${song.rid}".toByteArray().mapIndexed{i,b->(b.toInt() xor "yeelion".encodeToByteArray()[i%7].toInt()).toByte()}.toByteArray())
   val bytes=getBytes("https://newlyric.kuwo.cn/newlyric.lrc?$query".toHttpUrl());val split=bytes.indexOfSequence("\r\n\r\n".toByteArray());if(split<0)continue
   val lrc=decodeKuwoLrc(InflaterInputStream(ByteArrayInputStream(bytes.copyOfRange(split+4,bytes.size))).readBytes())
   if(lrc.isNotBlank())return ProviderResult.Hit(RemoteLyricsPayload(syncedLyrics=lrc))
  };ProviderResult.Miss
 }catch(c:CancellationException){throw c}catch(e:ProviderHttpException){e.unavailable()}catch(e:IOException){ProviderResult.Unavailable(ProviderFailureCategory.NETWORK,e.message,true)}catch(e:Exception){ProviderResult.Unavailable(ProviderFailureCategory.MALFORMED_RESPONSE,e.message)} }
 private suspend fun getBytes(url:okhttp3.HttpUrl):ByteArray=client.newCall(Request.Builder().url(url).header("Referer","https://www.kuwo.cn/").get().build()).awaitResponse().use{r->if(!r.isSuccessful)throw ProviderHttpException("Kuwo",r.code);r.body?.bytes() ?: byteArrayOf()}
 private fun ByteArray.indexOfSequence(needle:ByteArray):Int{outer@for(i in 0..size-needle.size){for(j in needle.indices)if(this[i+j]!=needle[j])continue@outer;return i};return -1}
}

internal data class KuwoSong(val title: String, val artist: String, val durationSeconds: Int, val rid: String)

/**
 * Kuwo's search answers in single-quoted JavaScript-style JSON, with `&nbsp;` for spaces in
 * names. Records nest objects of their own, so this parses it leniently rather than by pattern.
 */
internal fun kuwoSearchResults(raw: String): List<KuwoSong> {
    val reader = com.google.gson.stream.JsonReader(java.io.StringReader(raw)).apply { isLenient = true }
    val root = com.google.gson.JsonParser.parseReader(reader).takeIf { it.isJsonObject }?.asJsonObject ?: return emptyList()
    fun JsonObject.text(name: String) = get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        .replace("&nbsp;", " ").replace("&amp;", "&").trim()
    return root.getAsJsonArray("abslist")?.mapNotNull { element ->
        val record = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        val rid = record.text("MUSICRID").ifBlank { record.text("DC_TARGETID") }.ifBlank { return@mapNotNull null }
        KuwoSong(
            title = record.text("SONGNAME").ifBlank { record.text("NAME") },
            artist = record.text("ARTIST"),
            durationSeconds = record.text("DURATION").toIntOrNull() ?: 0,
            rid = if (rid.startsWith("MUSIC_")) rid else "MUSIC_$rid",
        )
    }.orEmpty()
}

/** Kuwo's LRC is GB18030 (with its byte-order mark), not UTF-8; its own tag lines are dropped. */
internal fun decodeKuwoLrc(bytes: ByteArray): String = String(bytes, charset("GB18030"))
    .removePrefix("\uFEFF")
    .lineSequence()
    .map { it.trimEnd('\r') }
    .filterNot { it.startsWith("[kuwo:") || it.startsWith("[ml:") }
    .joinToString("\n")
