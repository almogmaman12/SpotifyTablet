package com.almog.spotifytablet.lyrics.mobile.network.data.spotify

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.almog.spotifytablet.lyrics.mobile.network.data.awaitResponse
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * What Spotify knows about a matched track beyond its lyrics: its release date and artists (from
 * the track's embed page), the artist's header image, and the audio analysis the background moves
 * with. All of it through the same anonymous web-player token the matching uses.
 *
 * Every call is best effort: Spotify's web contract is undocumented, so any failure is null.
 */
class SpotifyTrackExtras(
    private val client: OkHttpClient,
    private val gson: Gson,
    private val catalog: AnonymousSpotifyCatalogSearch,
    private val resolver: SpotifyTrackResolver,
    private val cacheDir: File?,
) {
    /** The embed page's details for the last few tracks. */
    private val details = object : LinkedHashMap<String, TrackDetails>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TrackDetails>) = size > 8
    }

    /**
     * The song's Spotify IDs, best first: [knownId] alone when the player gave one, else the
     * matched track and the other releases of the same recording (single, album, compilation).
     */
    suspend fun trackIds(track: LocalTrackMetadata, knownId: String?): List<String> {
        if (knownId != null) return listOf(knownId)
        val match = guard { resolver.resolve(track) as? SpotifyTrackResolution.Matched } ?: return emptyList()
        return (listOf(match.track) + match.alternates).map { it.candidate.id }
    }

    /**
     * The year the recording first came out: the earliest of its releases among [trackIds] (the
     * first few), so a later compilation or re-release that won the match doesn't date it.
     */
    suspend fun releaseYear(trackIds: List<String>): String? = coroutineScope {
        trackIds.take(RELEASES_CHECKED)
            .map { id -> async { details(id)?.releaseYear } }
            .awaitAll()
            .filterNotNull()
            .minOrNull()
    }

    /** The track's release year and artists, from its embed page. */
    suspend fun details(trackId: String): TrackDetails? {
        synchronized(details) { details[trackId] }?.let { return it }
        val html = guard { get("https://open.spotify.com/embed/track/$trackId") } ?: return null
        val entity = runCatching {
            val json = NEXT_DATA.find(html)?.groupValues?.get(1) ?: return null
            gson.fromJson(json, JsonObject::class.java)
                .getAsJsonObject("props").getAsJsonObject("pageProps").getAsJsonObject("state")
                .getAsJsonObject("data").getAsJsonObject("entity")
        }.getOrNull() ?: return null
        val year = runCatching {
            entity.getAsJsonObject("releaseDate").get("isoString").asString.take(4)
        }.getOrNull()?.takeIf { YEAR.matches(it) }
        val artistIds = runCatching {
            entity.getAsJsonArray("artists").mapNotNull { a ->
                a.asJsonObject.get("uri")?.asString?.removePrefix("spotify:artist:")?.takeIf(String::isNotBlank)
            }
        }.getOrDefault(emptyList())
        return TrackDetails(year, artistIds).also { synchronized(details) { details[trackId] = it } }
    }

    /**
     * The artist's header image (the wide photo over their Spotify page), or null when they have
     * none. Remembered a week per artist, misses included.
     */
    suspend fun artistHeaderUrl(artistId: String, trackId: String): String? {
        val file = cacheDir?.let { File(it, "spotify/headers/$artistId.txt") }
        file?.takeIf { it.exists() && System.currentTimeMillis() - it.lastModified() < HEADER_TTL_MS }
            ?.let { return runCatching { it.readText() }.getOrNull()?.ifBlank { null } }
        val variables = gson.toJson(
            mapOf(
                "artistUri" to "spotify:artist:$artistId",
                "trackUri" to "spotify:track:$trackId",
                "enableRelatedVideos" to false,
                "enableRelatedAudioTracks" to false,
            )
        )
        val body = pathfinder("queryNpvArtist", variables, NPV_ARTIST_SHA256) ?: return null
        val url = runCatching {
            val sources = JsonParser.parseString(body).asJsonObject
                .getAsJsonObject("data").getAsJsonObject("artistUnion")
                .get("headerImage")?.takeIf { it.isJsonObject }?.asJsonObject
                ?.getAsJsonObject("data")?.getAsJsonArray("sources")
            // The first source's image ID, served from the plain image CDN.
            sources?.firstOrNull()?.asJsonObject?.get("url")?.asString
                ?.substringAfterLast('/')?.takeIf(String::isNotBlank)
                ?.let { "https://i.scdn.co/image/$it" }
        }.getOrElse { return null }
        file?.let { runCatching { it.parentFile?.mkdirs(); it.writeText(url.orEmpty()) } }
        return url
    }

    /**
     * The track's audio analysis, or null when Spotify has none. Kept a month on disk (in its
     * compact form), "none" included.
     */
    suspend fun audioAnalysis(trackId: String): AudioAnalysis? {
        val file = cacheDir?.let { File(it, "spotify/analysis/$trackId.json") }
        file?.takeIf { it.exists() && System.currentTimeMillis() - it.lastModified() < ANALYSIS_TTL_MS }?.let { cached ->
            val text = runCatching { cached.readText() }.getOrNull()
            if (text == NONE) return null
            text?.let { runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull() }
                ?.let(AudioAnalysis::fromJson)?.let { return it }
        }
        val result = guard {
            withToken { token ->
                val request = Request.Builder()
                    .url("https://spclient.wg.spotify.com/audio-attributes/v1/audio-analysis/$trackId?format=json")
                    .header("Authorization", "Bearer $token")
                    .header("app-platform", "WebPlayer")
                    .build()
                client.newCall(request).awaitResponse().use { response ->
                    when {
                        response.code == 401 -> Fetched.Rejected
                        response.code == 404 -> Fetched.Missing
                        !response.isSuccessful -> null
                        else -> withContext(Dispatchers.IO) { response.body.string() }.let { Fetched.Body(it) }
                    }
                }
            }
        } ?: return null
        val analysis = (result as? Fetched.Body)?.let { body ->
            runCatching { JsonParser.parseString(body.text).asJsonObject }.getOrNull()?.let(AudioAnalysis::fromSpotify)
        }
        if (result is Fetched.Missing || analysis != null) {
            file?.let { runCatching { it.parentFile?.mkdirs(); it.writeText(analysis?.toJson()?.toString() ?: NONE) } }
        }
        return analysis
    }

    private suspend fun pathfinder(operation: String, variables: String, sha256: String): String? = guard {
        val extensions = gson.toJson(mapOf("persistedQuery" to mapOf("version" to 1, "sha256Hash" to sha256)))
        val url = PATHFINDER_URL.toHttpUrl().newBuilder()
            .addQueryParameter("operationName", operation)
            .addQueryParameter("variables", variables)
            .addQueryParameter("extensions", extensions)
            .build()
        val result = withToken { token ->
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("app-platform", "WebPlayer")
                .build()
            client.newCall(request).awaitResponse().use { response ->
                when {
                    response.code == 401 -> Fetched.Rejected
                    !response.isSuccessful -> null
                    else -> Fetched.Body(withContext(Dispatchers.IO) { response.body.string() })
                }
            }
        }
        (result as? Fetched.Body)?.text
    }

    /** Runs [call] with the anonymous token, once more with a fresh one if it was turned down. */
    private suspend fun withToken(call: suspend (String) -> Fetched?): Fetched? {
        val token = catalog.accessToken()
        val first = call(token)
        if (first != Fetched.Rejected) return first
        catalog.rejectToken(token)
        return call(catalog.accessToken()).takeUnless { it == Fetched.Rejected }
    }

    private suspend fun get(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", BROWSER_UA).build()
        client.newCall(request).awaitResponse().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
            return withContext(Dispatchers.IO) { response.body.string() }
        }
    }

    private inline fun <T> guard(block: () -> T?): T? = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private sealed interface Fetched {
        class Body(val text: String) : Fetched
        data object Missing : Fetched
        data object Rejected : Fetched
    }

    /** [releaseYear]: four digits, or null when the page has no usable date. */
    data class TrackDetails(val releaseYear: String?, val artistIds: List<String>)

    private companion object {
        const val PATHFINDER_URL = "https://api-partner.spotify.com/pathfinder/v1/query"

        // Persisted-query hashes are not stable API; a rotated one just means no header image.
        const val NPV_ARTIST_SHA256 = "e1ae46a21911a3075c1aa29bf09a6c60f9a45b6a2b1132429f10ad06c299b5d7"

        const val NONE = "none"
        const val RELEASES_CHECKED = 4
        val HEADER_TTL_MS = TimeUnit.DAYS.toMillis(7)
        val ANALYSIS_TTL_MS = TimeUnit.DAYS.toMillis(30)
        val YEAR = Regex("\\d{4}")
        val NEXT_DATA = Regex(
            """<script[^>]*id=["']__NEXT_DATA__["'][^>]*>(.*?)</script>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        const val BROWSER_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }
}
