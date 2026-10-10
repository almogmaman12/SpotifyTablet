package com.almog.spotifytablet.lyrics.mobile.network.data.providers

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.almog.spotifytablet.lyrics.mobile.network.data.LyricsAttribution
import com.almog.spotifytablet.lyrics.mobile.network.data.LyricsContributor
import com.almog.spotifytablet.lyrics.mobile.network.data.LyricsCapability
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
class AmllLyricsProvider @Inject constructor(
    private val client: OkHttpClient,
    private val gson: Gson,
) : RemoteLyricsProvider {
    override val descriptor = LyricsSourceDescriptor(
        id = "amll_ttml_db",
        displayName = "AMLL TTML DB",
        defaultPriority = 20,
        capabilities = setOf(
            LyricsCapability.WORD_SYNC,
            LyricsCapability.LINE_SYNC,
            LyricsCapability.TRANSLATION,
            LyricsCapability.TRANSLITERATION,
            LyricsCapability.CONTRIBUTOR_CREDITS,
        ),
        releaseChannel = SourceReleaseChannel.RECOMMENDED,
    )

    override suspend fun fetch(request: LyricsLookupRequest): ProviderResult = try {
        val searchUrl = "$BASE/v1/lyrics/search".toHttpUrl().newBuilder()
            .addQueryParameter("trackName", request.title)
            .addQueryParameter("artistName", request.artist)
            .build()
        client.newCall(Request.Builder().url(searchUrl).get().build()).awaitResponse().use { response ->
            if (response.code == 404) return ProviderResult.Miss
            if (!response.isSuccessful) return httpFailure(response.code)
            val root = gson.fromJson(response.body?.string(), JsonObject::class.java)
            val items = root.getAsJsonObject("data")?.getAsJsonArray("items") ?: return ProviderResult.Miss
            val candidate = items
                .mapNotNull { it.takeIf { value -> value.isJsonObject }?.asJsonObject }
                .firstOrNull { item -> item.matches(request) }
                ?: return ProviderResult.Miss
            val id = candidate.get("id")?.asString ?: return ProviderResult.Miss
            fetchById(id, candidate.author())
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: IOException) {
        ProviderResult.Unavailable(ProviderFailureCategory.NETWORK, error.message, retryable = true)
    } catch (error: Exception) {
        ProviderResult.Unavailable(ProviderFailureCategory.MALFORMED_RESPONSE, error.message)
    }

    /** The TTML's author: AMLL files them by GitHub account, which gives a profile and an avatar. */
    private fun JsonObject.author(): LyricsContributor? {
        val login = getAsJsonArray("authorUsernames")?.firstOrNull()?.takeIf { it.isJsonPrimitive }?.asString
            ?.takeIf { it.matches(Regex("[A-Za-z0-9-]{1,39}")) } ?: return null
        // ponytail: first author only; files with several authors credit the first
        val userId = getAsJsonArray("authorIds")?.firstOrNull()?.takeIf { it.isJsonPrimitive }?.asString
            ?.takeIf { it.all(Char::isDigit) }
        return LyricsContributor(
            username = login,
            profileUrl = "https://github.com/$login",
            avatarUrl = userId?.let { "https://avatars.githubusercontent.com/u/$it?s=96" },
        )
    }

    private suspend fun fetchById(id: String, author: LyricsContributor?): ProviderResult {
        val url = "$BASE/v1/lyrics/get".toHttpUrl().newBuilder()
            .addQueryParameter("id", id)
            .build()
        return client.newCall(Request.Builder().url(url).get().build()).awaitResponse().use { response ->
            if (response.code == 404) return@use ProviderResult.Miss
            if (!response.isSuccessful) return@use httpFailure(response.code)
            val root = gson.fromJson(response.body?.string(), JsonObject::class.java)
            val data = root.getAsJsonObject("data") ?: root
            val ttml = sequenceOf("lyrics", "ttml", "content")
                .mapNotNull { key -> data.get(key)?.takeIf { it.isJsonPrimitive }?.asString }
                .firstOrNull { it.contains("<tt", ignoreCase = true) }
                ?: return@use ProviderResult.Unavailable(ProviderFailureCategory.MALFORMED_RESPONSE)
            ProviderResult.Hit(RemoteLyricsPayload(
                ttmlLyrics = ttml,
                attribution = LyricsAttribution(providerName = descriptor.displayName, maker = author),
            ))
        }
    }

    private fun JsonObject.matches(request: LyricsLookupRequest): Boolean {
        val titles = getAsJsonArray("musicNames")?.map { it.asString }.orEmpty()
        val artists = getAsJsonArray("artistNames")?.map { it.asString }.orEmpty()
        val title = SpotifyTrackMatcher.normalize(request.title)
        val artist = SpotifyTrackMatcher.normalize(request.artist)
        return titles.any { SpotifyTrackMatcher.normalize(it) == title } &&
            artists.any { candidate ->
                val normalized = SpotifyTrackMatcher.normalize(candidate)
                normalized == artist || normalized in artist || artist in normalized
            }
    }

    private fun httpFailure(code: Int) = ProviderResult.Unavailable(
        if (code in 400..499) ProviderFailureCategory.CLIENT_REQUEST else ProviderFailureCategory.SERVER,
        "AMLL returned HTTP $code",
        retryable = code >= 500,
    )

    private companion object {
        const val BASE = "https://api.amll.dev"
    }
}
