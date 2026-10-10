package com.almog.spotifytablet.lyrics.mobile.network.data.providers

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.almog.spotifytablet.lyrics.mobile.network.data.LyricsAttribution
import com.almog.spotifytablet.lyrics.mobile.network.data.LyricsCapability
import com.almog.spotifytablet.lyrics.mobile.network.data.LyricsContributor
import com.almog.spotifytablet.lyrics.mobile.network.data.LyricsLookupRequest
import com.almog.spotifytablet.lyrics.mobile.network.data.LyricsSourceDescriptor
import com.almog.spotifytablet.lyrics.mobile.network.data.ProviderFailureCategory
import com.almog.spotifytablet.lyrics.mobile.network.data.ProviderResult
import com.almog.spotifytablet.lyrics.mobile.network.data.RemoteLyricsPayload
import com.almog.spotifytablet.lyrics.mobile.network.data.RemoteLyricsProvider
import com.almog.spotifytablet.lyrics.mobile.network.data.SourceReleaseChannel
import com.almog.spotifytablet.lyrics.mobile.network.data.awaitResponse
import com.almog.spotifytablet.lyrics.mobile.network.data.spotify.SpotifyTrackMatcher
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

@Singleton
class UnisonLyricsProvider @Inject constructor(
    private val client: OkHttpClient,
    private val gson: Gson,
) : RemoteLyricsProvider {
    override val descriptor = LyricsSourceDescriptor(
        id = "unison",
        displayName = "Unison",
        defaultPriority = 60,
        capabilities = setOf(
            LyricsCapability.WORD_SYNC,
            LyricsCapability.LINE_SYNC,
            LyricsCapability.PLAIN_TEXT,
        ),
        releaseChannel = SourceReleaseChannel.EXTENDED,
    )

    override suspend fun fetch(request: LyricsLookupRequest): ProviderResult = try {
        direct(request) ?: search(request) ?: ProviderResult.Miss
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: IOException) {
        ProviderResult.Unavailable(ProviderFailureCategory.NETWORK, error.message, retryable = true)
    } catch (error: Exception) {
        ProviderResult.Unavailable(ProviderFailureCategory.MALFORMED_RESPONSE, error.message)
    }

    private suspend fun direct(request: LyricsLookupRequest): ProviderResult? {
        val url = "$BASE/lyrics".toHttpUrl().newBuilder()
            .addQueryParameter("song", request.title)
            .addQueryParameter("artist", request.artist)
            .build()
        return client.newCall(Request.Builder().url(url).get().build()).awaitResponse().use { response ->
            if (response.code == 404) return@use null
            if (!response.isSuccessful) return@use httpFailure(response.code)
            val root = gson.fromJson(response.body?.string(), JsonObject::class.java)
            val data = root.getAsJsonObject("data") ?: return@use null
            if (!data.matches(request)) return@use null
            payload(data)
        }
    }

    private suspend fun search(request: LyricsLookupRequest): ProviderResult? {
        val url = "$BASE/lyrics/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", "${request.artist} ${request.title}")
            .build()
        val searchResult = client.newCall(Request.Builder().url(url).get().build()).awaitResponse().use { response ->
            if (!response.isSuccessful) {
                return@use null to if (response.code == 404) null else httpFailure(response.code)
            }
            val root = gson.fromJson(response.body?.string(), JsonObject::class.java)
            val id = root.getAsJsonArray("data")
                ?.mapNotNull { it.takeIf { value -> value.isJsonObject }?.asJsonObject }
                ?.firstOrNull { it.matches(request) }
                ?.get("id")?.asString
            id to null
        }
        searchResult.second?.let { return it }
        val id = searchResult.first ?: return null
        val detailUrl = "$BASE/lyrics".toHttpUrl().newBuilder().addPathSegment(id).build()
        return client.newCall(Request.Builder().url(detailUrl).get().build()).awaitResponse().use { response ->
            if (response.code == 404) return@use null
            if (!response.isSuccessful) return@use httpFailure(response.code)
            val root = gson.fromJson(response.body?.string(), JsonObject::class.java)
            root.getAsJsonObject("data")?.let(::payload)
        }
    }

    private fun payload(data: JsonObject): ProviderResult? {
        val lyrics = data.get("lyrics")?.asString?.takeIf(String::isNotBlank) ?: return null
        val format = data.get("format")?.asString?.lowercase()
        val payload = when {
            format == "ttml" || lyrics.contains("<tt", ignoreCase = true) ->
                RemoteLyricsPayload(ttmlLyrics = lyrics)
            format == "lrc" || LRC_TIME.containsMatchIn(lyrics) ->
                RemoteLyricsPayload(syncedLyrics = lyrics)
            format == "plain" || format == "txt" || format.isNullOrBlank() ->
                RemoteLyricsPayload(plainLyrics = lyrics)
            else -> return null // YAML needs a dedicated lossless converter.
        }
        return ProviderResult.Hit(payload.copy(attribution = LyricsAttribution(
            providerName = descriptor.displayName,
            uploader = data.getAsJsonObject("submitter")?.submitter(),
        )))
    }

    /** Whoever submitted the sync, with their curator page on Unison and their avatar. */
    private fun JsonObject.submitter(): LyricsContributor? {
        fun string(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf(String::isNotBlank)
        val name = string("displayName") ?: return null
        return LyricsContributor(
            username = name,
            profileUrl = string("keyId")?.takeIf { it.matches(KEY_ID) }?.let { "$BASE/curator/$it" },
            avatarUrl = string("avatarUrl")?.takeIf { it.startsWith("https://") },
        )
    }

    private fun JsonObject.matches(request: LyricsLookupRequest): Boolean {
        val title = get("song")?.asString ?: get("title")?.asString ?: return false
        val artist = get("artist")?.asString ?: return false
        return SpotifyTrackMatcher.normalize(title) == SpotifyTrackMatcher.normalize(request.title) &&
            SpotifyTrackMatcher.normalize(artist).let { candidate ->
                val wanted = SpotifyTrackMatcher.normalize(request.artist)
                candidate == wanted || candidate in wanted || wanted in candidate
            }
    }

    private fun httpFailure(code: Int) = ProviderResult.Unavailable(
        if (code in 400..499) ProviderFailureCategory.CLIENT_REQUEST else ProviderFailureCategory.SERVER,
        "Unison returned HTTP $code",
        retryable = code >= 500,
    )

    companion object {
        const val BASE = "https://unison.boidu.dev"
        val KEY_ID = Regex("[0-9a-f]{16,128}")
        val LRC_TIME = Regex("\\[\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?]")
    }
}
