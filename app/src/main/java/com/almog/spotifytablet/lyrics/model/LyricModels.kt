package com.almog.spotifytablet.lyrics.model

import java.text.BreakIterator
import java.util.Locale

/**
 * Extracts true user-perceived grapheme clusters (preserving Hebrew Nikkud, Arabic Harakat, Emoji sequences).
 */
fun extractGraphemeClusters(text: String, locale: Locale = Locale.getDefault()): List<String> {
    if (text.isEmpty()) return emptyList()
    val iterator = BreakIterator.getCharacterInstance(locale)
    iterator.setText(text)
    val clusters = ArrayList<String>(text.length.coerceAtMost(16))
    var start = iterator.first()
    var end = iterator.next()
    while (end != BreakIterator.DONE) {
        clusters.add(text.substring(start, end))
        start = end
        end = iterator.next()
    }
    return clusters
}

/**
 * Represents a single syllable or word within a synchronized line.
 *
 * @property text The exact text snippet (word or syllable) to render.
 * @property startTimeMs Word start timestamp in playback milliseconds.
 * @property endTimeMs Word end timestamp in playback milliseconds.
 * @property trailingSpace Whether a whitespace followed this word in the source lyric.
 * @property graphemes Pre-split grapheme cluster strings (supporting complex scripts, diacritics, and emoji).
 * @property characters Backward compatibility Char list.
 */
data class WordSync(
    val text: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val trailingSpace: Boolean = true,
    val graphemes: List<String> = extractGraphemeClusters(text),
    val characters: List<Char> = text.toList()
)

/**
 * Extension function to clamp word end times so sequential syllables do not overlap,
 * preventing delayed word highlight animation loops, with a strict duration floor guard (>= 10ms).
 */
fun List<WordSync>.clampWordOverlaps(lineEndMs: Long): List<WordSync> {
    if (isEmpty()) return this
    return mapIndexed { idx, current ->
        val nextStart = if (idx + 1 < size) this[idx + 1].startTimeMs else lineEndMs
        val rawEnd = if (current.endTimeMs > nextStart && nextStart > current.startTimeMs) {
            nextStart
        } else {
            current.endTimeMs
        }
        // Strict duration floor guard (T_end >= T_start + 10ms) to avoid division by zero in easing math
        val safeEnd = maxOf(current.startTimeMs + 10L, rawEnd)
        current.copy(
            startTimeMs = current.startTimeMs,
            endTimeMs = safeEnd
        )
    }
}

/**
 * Represents a single line of lyrics, which may contain word-by-word timestamps or be line-synced.
 *
 * @property startTimeMs Line start timestamp in milliseconds.
 * @property endTimeMs Line end timestamp in milliseconds.
 * @property words Word-level timing tokens, or empty list if only line-synced.
 * @property rawText The full line text for display/fallback.
 * @property isSynthesized True if word tokens were proportionally generated rather than native word-synced.
 * @property isBackground True if marked as background vocal (e.g. ttm:role="x-bg").
 * @property agentId Optional singer identifier for duets (e.g., "v1", "v2").
 * @property translation Optional translation or transliteration string to render underneath original lyric.
 */
data class LyricLine(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val words: List<WordSync> = emptyList(),
    val rawText: String = "",
    val isSynthesized: Boolean = false,
    val isBackground: Boolean = false,
    val agentId: String? = null,
    val translation: String? = null
) {
    val isWordSynced: Boolean
        get() = words.isNotEmpty() && !isSynthesized
}

/**
 * Represents a complete lyric track.
 *
 * @property isWordSynced True if at least one line has genuine word-level sync.
 * @property lines The sorted sequence of lyric lines.
 * @property source Optional descriptor of the provider source (e.g., "Apple TTML", "LRCLIB", "Musixmatch").
 * @property bpm Optional tempo in beats per minute if detected/supplied.
 */
data class LyricTrack(
    val isWordSynced: Boolean,
    val lines: List<LyricLine>,
    val source: String = "",
    val bpm: Float? = null
)

/**
 * Estimates word-level timing for plain LRC lines based on character length.
 */
fun LyricTrack.withSynthesizedWordSync(): LyricTrack {
    if (this.isWordSynced || lines.isEmpty()) return this

    val updatedLines = lines.mapIndexed { index, line ->
        if (line.words.isNotEmpty() && !line.isSynthesized) return@mapIndexed line

        val wordsList = line.rawText.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (wordsList.isEmpty()) return@mapIndexed line

        val lineDuration = (line.endTimeMs - line.startTimeMs).coerceIn(500L, 12000L)
        val totalChars = wordsList.sumOf { it.length }.coerceAtLeast(1)

        val words = mutableListOf<WordSync>()
        var cursor = line.startTimeMs

        for ((wIdx, word) in wordsList.withIndex()) {
            val wordDur = (lineDuration * word.length / totalChars).coerceAtLeast(100L)
            words.add(
                WordSync(
                    text = word,
                    startTimeMs = cursor,
                    endTimeMs = cursor + wordDur,
                    trailingSpace = wIdx < wordsList.lastIndex
                )
            )
            cursor += wordDur
        }

        val clamped = words.clampWordOverlaps(line.endTimeMs)
        line.copy(words = clamped, isSynthesized = true)
    }

    return LyricTrack(
        isWordSynced = true,
        lines = updatedLines,
        source = "${this.source} (Synthesized Words)",
        bpm = this.bpm
    )
}
