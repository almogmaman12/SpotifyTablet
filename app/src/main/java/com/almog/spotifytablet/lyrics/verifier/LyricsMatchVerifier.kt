package com.almog.spotifytablet.lyrics.verifier

import com.almog.spotifytablet.DebugLog
import com.almog.spotifytablet.lyrics.model.LyricTrack
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Universal verification and validation engine to prevent wrong lyrics from being accepted,
 * cached, or displayed.
 *
 * Verifies:
 * 1. Title matching with aggressive noise stripping (remasters, feats, versions, live, edits).
 * 2. Artist matching supporting multi-artist collaborations.
 * 3. Track duration matching within strict tolerance.
 * 4. LyricTrack timeline sanity (ensures lyrics do not extend beyond the song or truncate prematurely).
 * 5. Candidate scoring & ranking for multi-result search APIs (e.g. LRCLIB search).
 */
object LyricsMatchVerifier {

    private const val TAG = "LyricsMatchVerifier"
    const val DEFAULT_DURATION_TOLERANCE_SEC = 8.0

    sealed class LyricValidationResult {
        object Valid : LyricValidationResult()
        data class Rejected(val reason: String) : LyricValidationResult()

        val isValid: Boolean get() = this is Valid
    }

    data class LrclibCandidate(
        val id: Long = 0L,
        val trackName: String,
        val artistName: String,
        val albumName: String = "",
        val durationSec: Double = 0.0,
        val instrumental: Boolean = false,
        val syncedLyrics: String = "",
        val plainLyrics: String = ""
    )

    // ──────────────────────────────────────────────────────────────────────
    // 1. Cleaning & Normalization
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Cleans common metadata noise from track titles while preserving core song names
     * in any language (including Hebrew, Arabic, Cyrillic, CJK, etc.).
     */
    fun cleanTrackTitle(title: String): String {
        if (title.isBlank()) return ""
        return title
            // Remove + suffix (e.g. "Song + Something")
            .replace(Regex("(?i)\\s*\\+\\s*.*"), "")
            // Remove parenthetical noise: (feat. ...), [2011 Remaster], (Live at ...), (Radio Edit), (Taylor's Version), (Version Revisited), (From ...), etc.
            .replace(
                Regex("(?i)[\\[(][^\\])]*?(?:remaster|feat\\.?|ft\\.?|with|prod\\.?|deluxe|edition|bonus|live|acoustic|rock|radio edit|edit|mix|original mix|single|mono|stereo|soundtrack|from|ost|score|theme|anniversary|version|revisited)[^\\])]*?[\\])]"),
                ""
            )
            // Remove hyphen/dash/slash noise suffix: - Remastered 2021, - Live, - Radio Edit, - Version Revisited, etc.
            .replace(
                Regex("(?i)\\s*[-/–—]\\s*(?:(?:\\d{4}\\s+)?(?:remaster|live|acoustic|radio edit|deluxe|edition|bonus|mono|stereo|single|version|revisited)|(?:feat\\.?|ft\\.?|with|prod\\.?|edit|mix|original mix|soundtrack|from\\s+[\"']|anniversary|radio|version|revisited)).*"),
                ""
            )
            .trim()
    }

    /**
     * Deeper normalization for strict title and artist comparison.
     * Strips all remaining brackets, replaces punctuation with spaces, and collapses whitespace.
     */
    fun normalizeForComparison(text: String): String {
        if (text.isBlank()) return ""
        return cleanTrackTitle(text)
            .replace(Regex("[\\[(].*?[\\])]"), " ")
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .lowercase()
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Splits normalized text into non-empty tokens, filtering out trivial single-character
     * noise or stop-words if the phrase contains multiple tokens.
     */
    fun tokenize(text: String): List<String> {
        val rawTokens = normalizeForComparison(text)
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        if (rawTokens.size <= 1) return rawTokens

        val stopWords = setOf("the", "a", "an", "and", "of", "in", "to", "for", "with", "feat", "ft")
        val filtered = rawTokens.filter { it !in stopWords }
        return if (filtered.isNotEmpty()) filtered else rawTokens
    }

    // ──────────────────────────────────────────────────────────────────────
    // 2. String Similarity Metrics
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Computes the Dice coefficient between token sets (0.0 to 1.0).
     */
    fun tokenDiceCoefficient(tokensA: Collection<String>, tokensB: Collection<String>): Double {
        val setA = tokensA.toSet()
        val setB = tokensB.toSet()
        if (setA.isEmpty() && setB.isEmpty()) return 1.0
        if (setA.isEmpty() || setB.isEmpty()) return 0.0

        val intersectionSize = setA.intersect(setB).size
        return (2.0 * intersectionSize) / (setA.size + setB.size)
    }

    /**
     * Standard Levenshtein distance normalized to 0.0 .. 1.0 similarity.
     */
    fun levenshteinSimilarity(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0

        val len1 = s1.length
        val len2 = s2.length
        var prev = IntArray(len2 + 1) { it }
        var curr = IntArray(len2 + 1)

        for (i in 0 until len1) {
            curr[0] = i + 1
            for (j in 0 until len2) {
                val cost = if (s1[i] == s2[j]) 0 else 1
                curr[j + 1] = min(
                    min(curr[j] + 1, prev[j + 1] + 1),
                    prev[j] + cost
                )
            }
            val temp = prev
            prev = curr
            curr = temp
        }

        val distance = prev[len2]
        val maxLen = max(len1, len2)
        return (1.0 - (distance.toDouble() / maxLen)).coerceIn(0.0, 1.0)
    }

    // ──────────────────────────────────────────────────────────────────────
    // 3. Match Verification Methods
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Verifies if candidateTitle matches expectedTitle.
     * Prevents false positives where one title is a short subset of another completely different song
     * (e.g. "Stay" vs "Stay With Me").
     */
    fun isTitleMatch(expectedTitle: String, candidateTitle: String): Boolean {
        val cleanExpected = normalizeForComparison(expectedTitle)
        val cleanCandidate = normalizeForComparison(candidateTitle)

        if (cleanExpected.isBlank() || cleanCandidate.isBlank()) return false
        if (cleanExpected == cleanCandidate) return true

        val tokensExpected = tokenize(cleanExpected)
        val tokensCandidate = tokenize(cleanCandidate)

        // Exact token sets match regardless of word ordering
        if (tokensExpected.toSet() == tokensCandidate.toSet()) return true

        val dice = tokenDiceCoefficient(tokensExpected, tokensCandidate)
        val lev = levenshteinSimilarity(cleanExpected, cleanCandidate)

        // If one title is a single word (e.g. "Stay", "Ghost", "Intro", "Home"),
        // reject if the other has distinct words (prevent "Stay" matching "Stay With Me")
        if (tokensExpected.size == 1 || tokensCandidate.size == 1) {
            if (tokensExpected.size != tokensCandidate.size) {
                return lev >= 0.85
            }
            return tokensExpected.first() == tokensCandidate.first() || lev >= 0.85
        }

        return dice >= 0.70 || lev >= 0.80
    }

    /**
     * Verifies if candidateArtist matches expectedArtist, correctly handling
     * multi-artist collaborations (e.g. "Post Malone, Swae Lee" matching "Post Malone").
     */
    fun isArtistMatch(expectedArtist: String, candidateArtist: String): Boolean {
        val normExpected = normalizeForComparison(expectedArtist)
        val normCandidate = normalizeForComparison(candidateArtist)

        if (normExpected.isBlank() || normCandidate.isBlank()) return false
        if (normExpected == normCandidate) return true

        // Split collaboration lists: e.g. "Ed Sheeran, Justin Bieber" or "Artist A feat. Artist B"
        val splitRegex = Regex("(?i)[,/&|]|\\b(?:feat\\.?|ft\\.?|with|featuring)\\b")
        val expArtists = expectedArtist.split(splitRegex).map { normalizeForComparison(it) }.filter { it.isNotBlank() }
        val candArtists = candidateArtist.split(splitRegex).map { normalizeForComparison(it) }.filter { it.isNotBlank() }

        // Check cross-match between any primary artist
        for (ea in expArtists) {
            for (ca in candArtists) {
                if (ea == ca) return true
                if (levenshteinSimilarity(ea, ca) >= 0.80) return true
                if (tokenDiceCoefficient(tokenize(ea), tokenize(ca)) >= 0.75) return true
            }
        }

        // Full string fallback
        val fullDice = tokenDiceCoefficient(tokenize(normExpected), tokenize(normCandidate))
        val fullLev = levenshteinSimilarity(normExpected, normCandidate)
        return fullDice >= 0.70 || fullLev >= 0.78
    }

    /**
     * Verifies track duration tolerance (default +/- 8 seconds).
     * If duration is unknown (<= 0), returns true.
     */
    fun isDurationMatch(
        expectedDurationMs: Int,
        candidateDurationSec: Double,
        toleranceSec: Double = DEFAULT_DURATION_TOLERANCE_SEC
    ): Boolean {
        if (expectedDurationMs <= 0 || candidateDurationSec <= 0.0) return true
        val expectedSec = expectedDurationMs / 1000.0
        val diff = abs(expectedSec - candidateDurationSec)
        return diff <= toleranceSec
    }

    /**
     * Validates the timeline of an actual [LyricTrack] against the song's known duration.
     * Prevents accepting lyrics from extended versions, wrong remixes, or truncated files.
     */
    fun validateLyricTrackTimeline(
        track: LyricTrack?,
        expectedDurationMs: Int
    ): LyricValidationResult {
        if (track == null) return LyricValidationResult.Rejected("Track is null")
        if (track.lines.isEmpty()) return LyricValidationResult.Rejected("Track has 0 lines")

        // Check for all-zero timestamps (corrupt sync)
        if (track.lines.size > 2 && track.lines.all { it.startTimeMs == 0L }) {
            return LyricValidationResult.Rejected("Corrupt sync: all lyric lines start at 0ms")
        }

        if (expectedDurationMs > 20_000) {
            val lastLine = track.lines.last()
            val lastStartMs = lastLine.startTimeMs
            val lastEndMs = max(lastLine.endTimeMs, lastStartMs)

            // Lyrics continue singing more than 8 seconds past track end
            if (lastStartMs > expectedDurationMs + 8_000L) {
                return LyricValidationResult.Rejected(
                    "Lyrics exceed track duration (lastLineStart=${lastStartMs}ms > duration=${expectedDurationMs}ms + 8s)"
                )
            }

            // Song is >= 60 seconds, but lyrics have < 4 lines and end before 25% of the song
            if (expectedDurationMs >= 60_000 && track.lines.size < 4 && lastEndMs < expectedDurationMs * 0.25) {
                return LyricValidationResult.Rejected(
                    "Premature lyrics termination (lines=${track.lines.size}, lastEnd=${lastEndMs}ms vs duration=${expectedDurationMs}ms)"
                )
            }
        }

        return LyricValidationResult.Valid
    }

    // ──────────────────────────────────────────────────────────────────────
    // 4. Candidate Scoring & Selection for Search APIs (e.g. LRCLIB)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Evaluates a list of LRCLIB search candidates and returns the best matching candidate,
     * or null if no candidate passes the verification test.
     */
    fun findBestLrclibMatch(
        candidates: List<LrclibCandidate>,
        expectedArtist: String,
        expectedTitle: String,
        expectedDurationMs: Int,
        requireSynced: Boolean = false
    ): LrclibCandidate? {
        if (candidates.isEmpty()) return null

        var bestCandidate: LrclibCandidate? = null
        var bestScore = -1.0

        for (cand in candidates) {
            // Discard instrumental tracks if we are looking for lyrics
            if (cand.instrumental) continue

            // Discard if empty lyrics
            val hasSynced = cand.syncedLyrics.isNotBlank()
            val hasPlain = cand.plainLyrics.isNotBlank()
            if (!hasSynced && !hasPlain) continue
            if (requireSynced && !hasSynced) continue

            // Must match artist and title
            if (!isArtistMatch(expectedArtist, cand.artistName)) continue
            if (!isTitleMatch(expectedTitle, cand.trackName)) continue

            // Duration check
            if (expectedDurationMs > 0 && cand.durationSec > 0.0) {
                if (!isDurationMatch(expectedDurationMs, cand.durationSec, DEFAULT_DURATION_TOLERANCE_SEC)) {
                    continue
                }
            }

            // Compute match score:
            val normExpTitle = normalizeForComparison(expectedTitle)
            val normCandTitle = normalizeForComparison(cand.trackName)
            val titleDice = tokenDiceCoefficient(tokenize(normExpTitle), tokenize(normCandTitle))
            val titleLev = levenshteinSimilarity(normExpTitle, normCandTitle)
            val titleScore = max(titleDice, titleLev) * 45.0 // max 45

            val normExpArtist = normalizeForComparison(expectedArtist)
            val normCandArtist = normalizeForComparison(cand.artistName)
            val artistDice = tokenDiceCoefficient(tokenize(normExpArtist), tokenize(normCandArtist))
            val artistLev = levenshteinSimilarity(normExpArtist, normCandArtist)
            val artistScore = max(artistDice, artistLev) * 35.0 // max 35

            val durationScore = if (expectedDurationMs > 0 && cand.durationSec > 0.0) {
                val diff = abs((expectedDurationMs / 1000.0) - cand.durationSec)
                val ratio = (1.0 - (diff / DEFAULT_DURATION_TOLERANCE_SEC)).coerceIn(0.0, 1.0)
                ratio * 15.0 // max 15
            } else 10.0

            val syncBonus = if (hasSynced) 5.0 else 0.0 // +5 for synced

            val totalScore = titleScore + artistScore + durationScore + syncBonus
            if (totalScore > bestScore && totalScore >= 60.0) {
                bestScore = totalScore
                bestCandidate = cand
            }
        }

        if (bestCandidate != null) {
            DebugLog.d(TAG, "Selected best LRCLIB match '${bestCandidate.trackName}' by '${bestCandidate.artistName}' (score=$bestScore)")
        } else {
            DebugLog.w(TAG, "No LRCLIB search candidates passed verification test for '$expectedTitle' by '$expectedArtist'")
        }

        return bestCandidate
    }
}
