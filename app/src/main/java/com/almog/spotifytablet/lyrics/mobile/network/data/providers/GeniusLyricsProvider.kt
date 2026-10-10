package com.almog.spotifytablet.lyrics.mobile.network.data.providers

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.almog.spotifytablet.lyrics.mobile.network.data.*
import com.almog.spotifytablet.lyrics.mobile.network.data.spotify.SpotifyTrackMatcher
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

@Singleton class GeniusLyricsProvider @Inject constructor(private val client:OkHttpClient,private val gson:Gson):RemoteLyricsProvider{
 override val descriptor=LyricsSourceDescriptor("genius","Genius",140,setOf(LyricsCapability.PLAIN_TEXT, LyricsCapability.CONTRIBUTOR_CREDITS),releaseChannel=SourceReleaseChannel.EXPERIMENTAL,defaultEnabled=false)
 override suspend fun fetch(r:LyricsLookupRequest):ProviderResult { return try{val url="https://genius.com/api/search/multi".toHttpUrl().newBuilder().addQueryParameter("q","${r.artist} ${r.title}").build();val root=get(url);val sections=root.getAsJsonObject("response")?.getAsJsonArray("sections")?:return ProviderResult.Miss;var page:String?=null
  sections.flatMap{it.asJsonObject.getAsJsonArray("hits")?.toList().orEmpty()}.map{it.asJsonObject.getAsJsonObject("result")}.firstOrNull{res->SpotifyTrackMatcher.normalize(res.get("title")?.asString.orEmpty())==SpotifyTrackMatcher.normalize(r.title)&&SpotifyTrackMatcher.normalize(res.getAsJsonObject("primary_artist")?.get("name")?.asString.orEmpty()).contains(SpotifyTrackMatcher.normalize(r.artist))}?.let{page=it.get("url")?.asString}
  val target=page?:return ProviderResult.Miss;val html=client.newCall(Request.Builder().url(target).header("User-Agent",BROWSER_UA).get().build()).awaitResponse().use{if(!it.isSuccessful)throw ProviderHttpException("Genius",it.code);it.body?.string().orEmpty()};val text=geniusLyricsText(html);if(text.isBlank())ProviderResult.Miss else ProviderResult.Hit(RemoteLyricsPayload(plainLyrics=text))
 }catch(c:CancellationException){throw c}catch(e:ProviderHttpException){e.unavailable()}catch(e:IOException){ProviderResult.Unavailable(ProviderFailureCategory.NETWORK,e.message,true)}catch(e:Exception){ProviderResult.Unavailable(ProviderFailureCategory.MALFORMED_RESPONSE,e.message)} }
 // Genius' front answers OkHttp's own user agent with HTTP 401.
 private suspend fun get(url:okhttp3.HttpUrl)=client.newCall(Request.Builder().url(url).header("Referer","https://genius.com/").header("User-Agent",BROWSER_UA).get().build()).awaitResponse().use{r->if(!r.isSuccessful)throw ProviderHttpException("Genius",r.code);gson.fromJson(r.body?.string(),JsonObject::class.java)}
 private companion object { const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36" }
}

/**
 * The lyrics on a Genius song page: the text of every `data-lyrics-container` div, which nest
 * divs of their own (a header with a translations menu, annotations). Parts marked
 * `data-exclude-from-selection` are page furniture, not lyrics, and are skipped.
 */
internal fun geniusLyricsText(html: String): String {
    val tag = Regex("<(/?)([a-zA-Z0-9]+)([^>]*)>")
    val containers = mutableListOf<String>()
    var from = 0
    while (true) {
        val open = Regex("<div[^>]*data-lyrics-container=[\"']true[\"'][^>]*>", RegexOption.IGNORE_CASE).find(html, from) ?: break
        val out = StringBuilder()
        var depth = 1
        var excludedAt = -1
        var at = open.range.last + 1
        while (depth > 0) {
            val next = tag.find(html, at) ?: break
            if (excludedAt < 0) out.append(html, at, next.range.first)
            at = next.range.last + 1
            val name = next.groupValues[2].lowercase()
            val closing = next.groupValues[1] == "/"
            if (name == "br") {
                if (excludedAt < 0) out.append('\n')
                continue
            }
            if (name !in NESTING_TAGS || next.groupValues[3].trimEnd().endsWith("/")) continue
            if (closing) {
                depth--
                if (depth == excludedAt) excludedAt = -1
            } else {
                if (excludedAt < 0 && "data-exclude-from-selection=\"true\"" in next.groupValues[3]) excludedAt = depth
                depth++
            }
        }
        containers += out.toString()
        from = at
    }
    return containers.joinToString("\n")
        .replace("&amp;", "&").replace("&#x27;", "'").replace("&#39;", "'").replace("&quot;", "\"")
        .lines().joinToString("\n") { it.trim() }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

/** Tags that open and close around content; void and inline tags don't change the nesting that matters. */
private val NESTING_TAGS = setOf("div", "span", "a", "i", "b", "em", "strong", "p", "button", "ul", "li", "svg", "path", "label", "section")
