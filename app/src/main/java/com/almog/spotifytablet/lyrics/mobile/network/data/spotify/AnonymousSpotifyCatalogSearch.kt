package com.almog.spotifytablet.lyrics.mobile.network.data.spotify

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.almog.spotifytablet.lyrics.mobile.network.data.awaitResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class SpotifyCatalogException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Minimal Android port of the anonymous search path used by SpotMatch's
 * SpotifyScraper dependency.
 *
 * This uses Spotify's undocumented web-player contract. Keep all volatile
 * operation details in this file and fail soft when Spotify changes it.
 */
class AnonymousSpotifyCatalogSearch constructor(
    private val client: OkHttpClient,
    private val gson: Gson,
) : SpotifyCatalogSearch {
    private val tokenMutex = Mutex()
    @Volatile private var cachedSession: SpotifyAnonymousSession? = null

    override suspend fun search(track: LocalTrackMetadata): List<SpotifyTrackCandidate> =
        withContext(Dispatchers.IO) {
            val queries = linkedSetOf(
                "${track.title} ${track.artist}".trim(),
                "${track.title} ${track.album}".trim(),
            ).filter(String::isNotBlank)

            if (queries.size == 1) return@withContext searchOnceWithTokenRetry(queries.first())
            // Both at once: the album query is only needed when the first misses, but waiting to
            // find that out costs a whole round trip on exactly the songs that are already slow.
            coroutineScope {
                // Its failure mustn't sink a first query that matched on its own.
                val byAlbum = async {
                    try { searchOnceWithTokenRetry(queries.last()) } catch (error: IOException) { emptyList() }
                }
                val first = searchOnceWithTokenRetry(queries.first())
                if (SpotifyTrackMatcher.resolve(track, first) is SpotifyTrackResolution.Matched) {
                    byAlbum.cancel()
                    first
                } else {
                    (first + byAlbum.await()).distinctBy(SpotifyTrackCandidate::id)
                }
            }
        }

    override suspend fun search(query: String): List<SpotifyTrackCandidate> =
        withContext(Dispatchers.IO) { searchOnceWithTokenRetry(query) }

    override suspend fun warmUp() {
        withContext(Dispatchers.IO) { session() }
    }

    /** The anonymous web-player token, for other calls on the same contract. */
    suspend fun accessToken(): String = withContext(Dispatchers.IO) { session().accessToken }

    /** Drops [token] after Spotify turned it down, so the next [accessToken] fetches a fresh one. */
    fun rejectToken(token: String) = invalidate(token)

    private suspend fun searchOnceWithTokenRetry(query: String): List<SpotifyTrackCandidate> {
        val firstSession = session()
        val firstResponse = executeSearch(query, firstSession.accessToken)
        if (firstResponse.statusCode != 401) return firstResponse.candidates

        invalidate(firstSession.accessToken)
        val retryResponse = executeSearch(query, session().accessToken)
        if (retryResponse.statusCode == 401) {
            throw SpotifyCatalogException("Spotify rejected the refreshed anonymous token")
        }
        return retryResponse.candidates
    }

    private suspend fun session(): SpotifyAnonymousSession {
        val now = System.currentTimeMillis()
        cachedSession?.takeIf { it.expiresAtMs - EXPIRY_SKEW_MS > now }?.let { return it }

        return tokenMutex.withLock {
            val lockedNow = System.currentTimeMillis()
            cachedSession
                ?.takeIf { it.expiresAtMs - EXPIRY_SKEW_MS > lockedNow }
                ?: bootstrapSession().also { cachedSession = it }
        }
    }

    private fun invalidate(rejectedToken: String) {
        if (cachedSession?.accessToken == rejectedToken) cachedSession = null
    }

    private suspend fun bootstrapSession(): SpotifyAnonymousSession {
        val request = Request.Builder().url(BOOTSTRAP_URL).get().build()
        return client.newCall(request).awaitResponse().use { response ->
            if (!response.isSuccessful) {
                throw SpotifyCatalogException("Spotify token bootstrap failed with HTTP ${response.code}")
            }
            val html = response.body?.string()
                ?: throw SpotifyCatalogException("Spotify token bootstrap returned an empty body")
            SpotifyWebParser.parseAnonymousSession(html, gson)
        }
    }

    private suspend fun executeSearch(query: String, token: String): SearchResponse {
        val variables = gson.toJson(
            mapOf(
                "searchTerm" to query,
                "offset" to 0,
                "limit" to SEARCH_LIMIT,
                "numberOfTopResults" to 5,
                "includeAudiobooks" to false,
                "includePreReleases" to true,
                "includeAlbumPreReleases" to false,
                "includeAuthors" to false,
                "includeEpisodeContentRatingsV2" to false,
            )
        )
        val extensions = gson.toJson(
            mapOf(
                "persistedQuery" to mapOf(
                    "version" to 1,
                    "sha256Hash" to SEARCH_SHA256,
                )
            )
        )
        val url = PATHFINDER_URL.toHttpUrl().newBuilder()
            .addQueryParameter("operationName", SEARCH_OPERATION)
            .addQueryParameter("variables", variables)
            .addQueryParameter("extensions", extensions)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("app-platform", "WebPlayer")
            .get()
            .build()

        return client.newCall(request).awaitResponse().use { response ->
            if (response.code == 401) return@use SearchResponse(response.code, emptyList())
            if (!response.isSuccessful) {
                throw SpotifyCatalogException("Spotify search failed with HTTP ${response.code}")
            }
            val body = response.body?.string()
                ?: throw SpotifyCatalogException("Spotify search returned an empty body")
            SearchResponse(response.code, SpotifyWebParser.parseSearch(body, gson))
        }
    }

    private data class SearchResponse(
        val statusCode: Int,
        val candidates: List<SpotifyTrackCandidate>,
    )

    private companion object {
        const val BOOTSTRAP_TRACK_ID = "4uLU6hMCjMI75M1A2tKUQC"
        const val BOOTSTRAP_URL = "https://open.spotify.com/embed/track/$BOOTSTRAP_TRACK_ID"
        const val PATHFINDER_URL = "https://api-partner.spotify.com/pathfinder/v1/query"
        const val SEARCH_OPERATION = "searchDesktop"

        // Spotify persisted-query hashes are not stable API. A
        // PersistedQueryNotFound response means this value must be refreshed.
        const val SEARCH_SHA256 = "eff59fa0a3d026b88b56fddbcf4bdfa16a186b8175a5c1a358c072e053c2e5b0"
        const val SEARCH_LIMIT = 20
        const val EXPIRY_SKEW_MS = 60_000L
    }
}

internal data class SpotifyAnonymousSession(
    val accessToken: String,
    val expiresAtMs: Long,
)

internal object SpotifyWebParser {
    private val nextDataPattern = Regex(
        """<script[^>]*id=[\"']__NEXT_DATA__[\"'][^>]*>(.*?)</script>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    fun parseAnonymousSession(html: String, gson: Gson): SpotifyAnonymousSession {
        val nextData = nextDataPattern.find(html)?.groupValues?.get(1)
            ?: throw SpotifyCatalogException("Spotify embed page no longer contains __NEXT_DATA__")
        val root = runCatching { gson.fromJson(nextData, JsonObject::class.java) }
            .getOrElse { throw SpotifyCatalogException("Spotify embed session JSON is malformed", it) }
        val session = root.objectAt("props", "pageProps", "state", "settings", "session")
            ?: throw SpotifyCatalogException("Spotify embed session shape changed")
        val token = session.string("accessToken")
            ?: throw SpotifyCatalogException("Spotify embed session omitted its access token")
        val expiry = session.get("accessTokenExpirationTimestampMs")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.asLong
            ?: throw SpotifyCatalogException("Spotify embed session omitted token expiry")
        return SpotifyAnonymousSession(token, expiry)
    }

    fun parseSearch(json: String, gson: Gson): List<SpotifyTrackCandidate> {
        val root = runCatching { gson.fromJson(json, JsonObject::class.java) }
            .getOrElse { throw SpotifyCatalogException("Spotify search JSON is malformed", it) }
        val errors = root.getAsJsonArray("errors")
        if (errors?.any { it.asJsonObject.string("message") == "PersistedQueryNotFound" } == true) {
            throw SpotifyCatalogException("Spotify rotated the persisted search query")
        }
        val items = root.objectAt("data", "searchV2", "tracksV2")
            ?.getAsJsonArray("items")
            ?: throw SpotifyCatalogException("Spotify search response shape changed")

        return items.mapNotNull { item ->
            val data = item.asJsonObject.objectAt("item", "data") ?: return@mapNotNull null
            val uri = data.string("uri") ?: return@mapNotNull null
            val id = data.string("id") ?: uri.substringAfterLast(':').takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val title = data.string("name") ?: return@mapNotNull null
            val duration = data.objectAt("duration")
                ?.get("totalMilliseconds")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                ?.asLong
                ?: return@mapNotNull null
            val artists = data.objectAt("artists")
                ?.getAsJsonArray("items")
                ?.mapNotNull { artist -> artist.asJsonObject.objectAt("profile")?.string("name") }
                .orEmpty()
            if (artists.isEmpty()) return@mapNotNull null

            val album = data.objectAt("albumOfTrack")
            SpotifyTrackCandidate(
                id = id,
                title = title,
                artists = artists,
                album = album?.string("name").orEmpty(),
                durationMs = duration,
                // The smallest cover, for the manual search's list.
                coverUrl = album?.objectAt("coverArt")?.getAsJsonArray("sources")
                    ?.mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject }
                    ?.minByOrNull { it.get("width")?.takeIf { w -> w.isJsonPrimitive }?.asInt ?: Int.MAX_VALUE }
                    ?.string("url"),
            )
        }
    }

    private fun JsonObject.objectAt(vararg path: String): JsonObject? {
        var current = this
        for (key in path) {
            val next = current.get(key) ?: return null
            if (!next.isJsonObject) return null
            current = next.asJsonObject
        }
        return current
    }

    private fun JsonObject.string(key: String): String? = get(key)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString
        ?.takeIf(String::isNotBlank)
}
