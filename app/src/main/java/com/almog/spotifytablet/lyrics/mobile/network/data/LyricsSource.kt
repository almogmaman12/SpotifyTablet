package com.almog.spotifytablet.lyrics.mobile.network.data

import com.almog.spotifytablet.lyrics.mobile.network.service.LyricsService
import com.almog.spotifytablet.lyrics.mobile.network.data.spotify.SpotifyTrackMatcher
import com.almog.spotifytablet.lyrics.mobile.network.model.LrclibSearchEntry
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class LyricsSource @Inject constructor(
    private val lyricsService: LyricsService
) : RemoteLyricsProvider {

    override val descriptor = LyricsSourceDescriptor(
        id = "lrclib",
        displayName = "LRCLIB",
        defaultPriority = 100,
        capabilities = setOf(
            LyricsCapability.LINE_SYNC,
            LyricsCapability.PLAIN_TEXT,
        ),
        upstreamFamily = "lrclib",
        releaseChannel = SourceReleaseChannel.RECOMMENDED,
    )

    override suspend fun fetch(request: LyricsLookupRequest): ProviderResult {
        return try {
            val response = lyricsService.getSongLyrics(
                request.artist,
                request.title,
                // A fetch-ahead queue entry has no album or length. LRCLIB rejects duration=0,
                // so leave unknown fields out instead.
                request.album.takeIf { it.isNotBlank() },
                request.durationSeconds.takeIf { it > 0 },
            )
            // An instrumental is a record with neither field filled in: no lyrics, not a broken answer.
            if (response.plainLyrics.isNullOrBlank() && response.syncedLyrics.isNullOrBlank()) {
                ProviderResult.Miss
            } else ProviderResult.Hit(
                RemoteLyricsPayload(
                    plainLyrics = response.plainLyrics,
                    syncedLyrics = response.syncedLyrics,
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: HttpException) {
            when (e.code()) {
                404 -> runCatching { searchFallback(request) }.getOrElse { error ->
                    if (error is CancellationException) throw error
                    ProviderResult.Unavailable(ProviderFailureCategory.NETWORK, error.message, retryable = true)
                }
                429 -> ProviderResult.CoolingDown(
                    RetryAfterParser.deadline(e.response()?.headers()?.get("Retry-After"))
                )
                503 -> ProviderResult.Queued(
                    e.response()?.headers()?.get("Retry-After")?.let {
                        RetryAfterParser.deadline(it, Instant.now())
                    }
                )
                401, 403 -> ProviderResult.Unavailable(
                    category = ProviderFailureCategory.AUTHENTICATION,
                    message = "LRCLIB rejected the request (HTTP ${e.code()})",
                )
                in 400..499 -> ProviderResult.Unavailable(
                    category = ProviderFailureCategory.CLIENT_REQUEST,
                    message = "LRCLIB request was invalid (HTTP ${e.code()})",
                )
                else -> {
                    Timber.w("lrclib request failed with HTTP %d", e.code())
                    val fallback = runCatching { searchFallback(request) }.getOrNull()
                    if (fallback is ProviderResult.Hit) fallback else ProviderResult.Unavailable(
                        category = ProviderFailureCategory.SERVER,
                        message = "LRCLIB server error (HTTP ${e.code()})",
                        retryable = e.code() >= 500,
                    )
                }
            }
        } catch (e: SocketTimeoutException) {
            ProviderResult.Unavailable(
                category = ProviderFailureCategory.TIMEOUT,
                message = e.message,
                retryable = true,
            )
        } catch (e: IOException) {
            ProviderResult.Unavailable(
                category = ProviderFailureCategory.NETWORK,
                message = e.message,
                retryable = true,
            )
        } catch (e: Exception) {
            ProviderResult.Unavailable(
                category = ProviderFailureCategory.UNKNOWN,
                message = e.message,
            )
        }
    }

    private suspend fun searchFallback(request: LyricsLookupRequest): ProviderResult {
        val candidates = lyricsService.searchSongLyrics(request.artist, request.title)
        val match = chooseSearchResult(request, candidates) ?: return ProviderResult.Miss
        return ProviderResult.Hit(RemoteLyricsPayload(match.plainLyrics, match.syncedLyrics))
    }

    internal fun chooseSearchResult(request: LyricsLookupRequest, candidates: List<LrclibSearchEntry>): LrclibSearchEntry? {
        val title = SpotifyTrackMatcher.normalize(request.title)
        val artist = SpotifyTrackMatcher.normalize(request.artist)
        return candidates.asSequence()
            .filter { SpotifyTrackMatcher.normalize(it.trackName.orEmpty()) == title }
            .filter { SpotifyTrackMatcher.normalize(it.artistName.orEmpty()) == artist }
            .filter { it.duration != null && request.durationSeconds > 0 && kotlin.math.abs(it.duration - request.durationSeconds) <= 8 }
            .filter { !it.syncedLyrics.isNullOrBlank() || !it.plainLyrics.isNullOrBlank() }
            .sortedWith(compareByDescending<LrclibSearchEntry> {
                SpotifyTrackMatcher.normalize(it.albumName.orEmpty()) == SpotifyTrackMatcher.normalize(request.album)
            }.thenBy { kotlin.math.abs(requireNotNull(it.duration) - request.durationSeconds) })
            .firstOrNull()
    }
}
