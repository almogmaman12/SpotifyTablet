package com.almog.spotifytablet.lyrics.mobile.network.data.spotify

/**
 * Transport boundary for resolving local metadata to Spotify candidates.
 *
 * The first implementation may use Spotify's anonymous web search behavior,
 * but callers and matching logic must not depend on that unofficial transport.
 */
interface SpotifyCatalogSearch {
    suspend fun search(track: LocalTrackMetadata): List<SpotifyTrackCandidate>

    /** Spotify's results for a query typed by the user, in Spotify's order. */
    suspend fun search(query: String): List<SpotifyTrackCandidate> = emptyList()

    /** Readies the transport (e.g. its token) so the first search doesn't pay for it. */
    suspend fun warmUp() {}
}
