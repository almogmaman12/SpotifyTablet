package com.almog.spotifytablet.lyrics.mobile.network.data.spotify

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

data class LocalTrackMetadata(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
)

data class SpotifyTrackCandidate(
    val id: String,
    val title: String,
    val artists: List<String>,
    val album: String,
    val durationMs: Long,
    val coverUrl: String? = null,
)

data class ScoredSpotifyTrack(
    val candidate: SpotifyTrackCandidate,
    val score: Int,
    val titleSimilarity: Double,
    val artistSimilarity: Double,
    val durationDeltaMs: Long,
    val hasVersionConflict: Boolean,
)

sealed interface SpotifyTrackResolution {
    /**
     * [alternates]: other IDs for the same recording (single, album, re-release), best first.
     * Lyrics are uploaded per ID, so a lookup that misses on [track] should try these.
     */
    data class Matched(
        val track: ScoredSpotifyTrack,
        val alternates: List<ScoredSpotifyTrack> = emptyList(),
    ) : SpotifyTrackResolution
    data class Ambiguous(val candidates: List<ScoredSpotifyTrack>) : SpotifyTrackResolution
    data object NotFound : SpotifyTrackResolution
}

/**
 * Ranks Spotify search results against local audio metadata.
 *
 * The 55/30/15 title/artist/duration weighting comes from SpotMatch, while the
 * version-conflict and winner-margin checks are intentionally stricter: showing
 * no lyrics is better than attaching lyrics for a live, remix, or instrumental
 * recording to the studio track.
 */
object SpotifyTrackMatcher {
    private const val MINIMUM_SCORE = 82
    private const val MINIMUM_TITLE_SIMILARITY = 0.78
    private const val MINIMUM_ARTIST_SIMILARITY = 0.55
    private const val MAXIMUM_DURATION_DELTA_MS = 8_000L
    private const val MINIMUM_WINNER_MARGIN = 4
    private const val DURATION_SCORE_WINDOW_MS = 15_000.0
    private const val SAME_RECORDING_DURATION_MS = 2_000L
    private const val MAXIMUM_ALTERNATES = 3

    private val versionTerms = setOf(
        "acoustic",
        "cover",
        "demo",
        // Language versions are different recordings (e.g. Mesmerizer's "Official English Version").
        "english",
        "inst",
        "japanese",
        "extended",
        "instrumental",
        "karaoke",
        "live",
        "mix",
        "remaster",
        "remastered",
        "remix",
        "slowed",
        "sped",
    )

    fun resolve(
        source: LocalTrackMetadata,
        candidates: Collection<SpotifyTrackCandidate>,
    ): SpotifyTrackResolution {
        val ranked = candidates
            .asSequence()
            .distinctBy(SpotifyTrackCandidate::id)
            .map { score(source, it) }
            .filter(::isPlausible)
            .sortedWith(
                compareByDescending<ScoredSpotifyTrack> { it.score }
                    .thenBy { it.durationDeltaMs }
                    .thenBy { it.candidate.id }
            )
            .toList()

        var winner = ranked.firstOrNull() ?: return SpotifyTrackResolution.NotFound
        val runnerUp = ranked.getOrNull(1)
        if (runnerUp != null && winner.score - runnerUp.score < MINIMUM_WINNER_MARGIN) {
            // The same recording is often listed as a single, an album track, and a
            // compilation. A unique exact album match picks which to ask first.
            val tied = ranked.takeWhile { winner.score - it.score < MINIMUM_WINNER_MARGIN }
            val sourceAlbum = normalize(source.album)
            val albumMatch = tied.filter { sourceAlbum.isNotBlank() && normalize(it.candidate.album) == sourceAlbum }
                .singleOrNull()
            if (albumMatch != null) winner = albumMatch
            // A tie between different songs (same title, other artist) stays unresolved.
            else if (!tied.all { sameRecording(winner.candidate, it.candidate) }) {
                return SpotifyTrackResolution.Ambiguous(ranked.take(5))
            }
        }

        // Only a copy that ties on title, byline and
        // length is another pressing of this song; anything less is a different song.
        val alternates = ranked
            .filter { it !== winner && sameRecording(winner.candidate, it.candidate) }
            .take(MAXIMUM_ALTERNATES)
        return SpotifyTrackResolution.Matched(winner, alternates)
    }

    // Loose on purpose: only near-tied, plausible candidates are compared, and remixes, language
    // versions and extra-release titles have already been scored out. Spotify lists one recording
    // as "ヤラララ(YARARARA)" by one artist and "YARARARA" with a featured artist added.
    private fun sameRecording(a: SpotifyTrackCandidate, b: SpotifyTrackCandidate): Boolean =
        titleParts(a.title).map(::normalize).intersect(titleParts(b.title).map(::normalize).toSet()).isNotEmpty() &&
            a.artists.map(::normalize).intersect(b.artists.map(::normalize).toSet()).isNotEmpty() &&
            abs(a.durationMs - b.durationMs) <= SAME_RECORDING_DURATION_MS

    fun score(source: LocalTrackMetadata, candidate: SpotifyTrackCandidate): ScoredSpotifyTrack {
        // Players often show "イガク - Medicine" or "Artist - Title"; compare the parts too.
        val localParts = titleParts(source.title)
        val remoteParts = titleParts(candidate.title)
        val titleSimilarity = localParts.maxOf { local -> remoteParts.maxOf { remote -> similarity(local, remote) } }
        // Extra words only the Spotify title has ("（テトライブ2025-実演盤-）") mark another release;
        // extra words only the player has are normal (bilingual titles), so they cost nothing.
        val unexplainedParts = remoteParts.drop(1).count { remote ->
            localParts.none { local -> similarity(local, remote) >= 0.5 }
        }
        val artistSimilarity = artistSimilarity(source.artist, candidate.artists)
        // Unknown length (a queue entry often has none) is not held against anyone.
        val durationDelta = if (source.durationMs <= 0) 0L else abs(source.durationMs - candidate.durationMs)
        val durationScore = max(0.0, 1.0 - durationDelta / DURATION_SCORE_WINDOW_MS)
        val versionConflict = versionTerms(source.title) != versionTerms(candidate.title)
        val weightedScore = 100 * (
            titleSimilarity * 0.55 +
                artistSimilarity * 0.30 +
                durationScore * 0.15
            )
        val score = (weightedScore - (if (versionConflict) 18 else 0) - 10 * unexplainedParts).roundToInt()

        return ScoredSpotifyTrack(
            candidate = candidate,
            score = score.coerceIn(0, 100),
            titleSimilarity = titleSimilarity,
            artistSimilarity = artistSimilarity,
            durationDeltaMs = durationDelta,
            hasVersionConflict = versionConflict,
        )
    }

    internal fun normalize(value: String): String = Normalizer
        .normalize(value, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace("&", " and ")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun similarity(left: String, right: String): Double {
        val normalizedLeft = normalize(left)
        val normalizedRight = normalize(right)
        if (normalizedLeft == normalizedRight) return 1.0
        if (normalizedLeft.isEmpty() || normalizedRight.isEmpty()) return 0.0

        // Sørensen-Dice over character bigrams is compact, deterministic, and
        // cheap enough to rank a normal Spotify search result page on-device.
        val leftBigrams = normalizedLeft.bigrams()
        val rightBigrams = normalizedRight.bigrams().toMutableList()
        var intersection = 0
        for (bigram in leftBigrams) {
            val index = rightBigrams.indexOf(bigram)
            if (index >= 0) {
                intersection += 1
                rightBigrams.removeAt(index)
            }
        }
        return 2.0 * intersection / (leftBigrams.size + normalizedRight.bigrams().size)
    }

    /** The whole title plus its pieces around " - ", "/", "|" and brackets. */
    private fun titleParts(title: String): List<String> =
        (listOf(title) + title.split(Regex("\\s+[-–—|/]\\s+|[／｜()\\[\\]（）【】「」]")))
            .map(String::trim)
            // A credit like "(feat. X)" is not a title: two songs sharing it are not the same song.
            .filter { normalize(it).isNotEmpty() && !normalize(it).matches(Regex("(feat|featuring|ft|with|prod)\\b.*")) }
            .distinct()

    private fun artistSimilarity(localArtist: String, spotifyArtists: List<String>): Double {
        val localParts = splitArtists(localArtist)
        val candidateParts = spotifyArtists.flatMap(::splitArtists)
        if (localParts.isEmpty() || candidateParts.isEmpty()) return 0.0

        return localParts.maxOf { local ->
            candidateParts.maxOf { candidate -> similarity(local, candidate) }
        }
    }

    // The whole name stays in too: "&" is both a join (YouTube Music's "Beyoncé & JAY-Z", which
    // Spotify lists as two artists) and part of a name ("Simon & Garfunkel").
    private fun splitArtists(value: String): List<String> = (listOf(value) + value
        .split(Regex("(?i)\\s+(?:feat(?:uring)?|ft|with|x)\\.?\\s+|\\s+&\\s+|[,;/]")))
        .map(::normalize)
        .filter(String::isNotEmpty)
        .distinct()

    private fun versionTerms(value: String): Set<String> {
        val tokens = normalize(value).split(' ').toSet()
        return tokens.intersect(versionTerms)
    }

    private fun isPlausible(track: ScoredSpotifyTrack): Boolean =
        track.score >= MINIMUM_SCORE &&
            track.titleSimilarity >= MINIMUM_TITLE_SIMILARITY &&
            track.artistSimilarity >= MINIMUM_ARTIST_SIMILARITY &&
            track.durationDeltaMs <= MAXIMUM_DURATION_DELTA_MS &&
            !track.hasVersionConflict

    private fun String.bigrams(): List<String> = when (length) {
        0 -> emptyList()
        1 -> listOf(this)
        else -> windowed(size = 2, step = 1)
    }
}
