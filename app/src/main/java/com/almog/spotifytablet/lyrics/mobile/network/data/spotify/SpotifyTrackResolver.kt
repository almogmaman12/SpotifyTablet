package com.almog.spotifytablet.lyrics.mobile.network.data.spotify

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Cautious Spotify matching for externally played tracks.
 *
 * Shared by the lyrics lookup and the track extras (release year, artist header, beats), so the
 * last few matches are remembered and a track already being searched is waited for, not searched
 * twice.
 */
class SpotifyTrackResolver(private val catalogSearch: SpotifyCatalogSearch) {
    private val recent = object : LinkedHashMap<LocalTrackMetadata, SpotifyTrackResolution.Matched>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LocalTrackMetadata, SpotifyTrackResolution.Matched>) = size > RECENT
    }
    private val searching = ConcurrentHashMap<LocalTrackMetadata, Mutex>()

    suspend fun warmUp() = catalogSearch.warmUp()

    suspend fun resolve(track: LocalTrackMetadata): SpotifyTrackResolution {
        remembered(track)?.let { return it }
        return searching.getOrPut(track) { Mutex() }.withLock {
            remembered(track)?.let { return@withLock it }
            try {
                search(track)
            } finally {
                searching.remove(track)
            }
        }
    }

    /**
     * The results the automatic match picks from (the same searches), closest to [track] first,
     * for the user to pick from when it picked wrong or nothing.
     */
    suspend fun candidates(track: LocalTrackMetadata): List<SpotifyTrackCandidate> =
        catalogSearch.search(track).sortedByDescending { SpotifyTrackMatcher.score(track, it).score }

    /** Spotify's results for a query the user typed. */
    suspend fun search(query: String): List<SpotifyTrackCandidate> = catalogSearch.search(query)

    /** The match already made for [track], without searching; null if none was made lately. */
    fun remembered(track: LocalTrackMetadata): SpotifyTrackResolution.Matched? = synchronized(recent) { recent[track] }

    private suspend fun search(track: LocalTrackMetadata): SpotifyTrackResolution {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val candidates = catalogSearch.search(track)
        val result = SpotifyTrackMatcher.resolve(track, candidates)
        if (result is SpotifyTrackResolution.Matched) synchronized(recent) { recent[track] = result }
        Log.d("SpotifyMatch", "track=\"${track.title}\" by \"${track.artist}\" lookupMs=${android.os.SystemClock.elapsedRealtime() - startedAt} duration=${track.durationMs} candidates=${candidates.size} result=${result.javaClass.simpleName} top=" +
            candidates.map { SpotifyTrackMatcher.score(track, it) }
                .sortedByDescending { it.score }.take(4)
                .joinToString { "${it.candidate.id}(${it.candidate.title} / ${it.candidate.artists.joinToString()}):${it.score}:${it.durationDeltaMs}:${it.hasVersionConflict}" })
        return result
    }

    private companion object {
        /** The song playing, the few warmed ahead of it, and one to spare. */
        const val RECENT = 6
    }
}
