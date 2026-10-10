package com.almog.spotifytablet.lyrics.mobile.parser

import com.almog.spotifytablet.lyrics.mobile.RenderConfig
import com.almog.spotifytablet.lyrics.mobile.SyllableMerge
import com.almog.spotifytablet.lyrics.mobile.models.Letter
import com.almog.spotifytablet.lyrics.mobile.models.Line
import com.almog.spotifytablet.lyrics.mobile.models.Word

/**
 * Merges a word's syllables when [RenderConfig.syllableMerge] asks for it, then splits
 * sufficiently long syllables into per-letter timings for the "held word"
 * letter-by-letter emphasis.
 *
 * This runs at render time (not parse time) because letter capability depends on the
 * active [RenderConfig] (thresholds differ per quality mode) and on whether romanized
 * text is currently displayed (letters are split over the *displayed* string).
 */
object LetterSynthesizer {

    /**
     * @param romanized when true, letters are split over each word's romanized text (if present).
     */
    fun apply(lines: List<Line>, config: RenderConfig, romanized: Boolean): List<Line> {
        return lines.map { line ->
            if (line.isInterlude || line.isSongwriter || line.translationReplaces) {
                line
            } else {
                line.copy(words = mergeSyllables(line.words, config, romanized).map { synthesize(it, config, romanized) })
            }
        }
    }

    /**
     * Joins each run of syllables (a word, then the parts glued to it by [Word.isPartOfWord]) into
     * one word spanning the first syllable's start to the last one's end, so it fills evenly like
     * a word that was never split. [SyllableMerge.Held] merges a run only when the whole word
     * would get the held-word letter emphasis. See [startsNewWord] for glued syllables that stay apart.
     */
    internal fun mergeSyllables(words: List<Word>, config: RenderConfig, romanized: Boolean): List<Word> {
        if (config.syllableMerge == SyllableMerge.Off) return words
        val runs = mutableListOf<MutableList<Word>>()
        for (word in words) {
            if (word.isPartOfWord && runs.isNotEmpty() && !startsNewWord(runs.last().last(), word)) {
                runs.last() += word
            } else {
                runs += mutableListOf(word)
            }
        }
        return runs.flatMap { run ->
            if (run.size == 1) return@flatMap run
            val first = run.first()
            val merged = first.copy(
                text = run.joinToString("") { it.text },
                endMs = run.maxOf { it.endMs },
                isLetterGroup = false,
                letters = emptyList(),
                romanizedText = if (run.any { it.romanizedText != null }) run.joinToString("") { it.romanizedText ?: it.text } else null,
            )
            if (config.syllableMerge == SyllableMerge.Held && !isLetterCapable(merged, config, romanized)) run else listOf(merged)
        }
    }

    /**
     * Whether [next], glued to [prev] with no space, is still a word of its own: across a hyphen,
     * dash or slash ("well-known" is two words), or next to a script written without spaces
     * between words (Chinese, Japanese, Thai...), where glued only means the same line.
     * Korean spaces its words, so its glued syllables do merge.
     */
    private fun startsNewWord(prev: Word, next: Word): Boolean {
        val before = prev.text.trimEnd()
        val after = next.text.trimStart()
        if (before.isEmpty() || after.isEmpty()) return true
        val last = before.codePointBefore(before.length)
        val first = after.codePointAt(0)
        return last in WORD_JOINERS || first in WORD_JOINERS || isUnspaced(last) || isUnspaced(first)
    }

    private val WORD_JOINERS = "-‐‑‒–—―/".map(Char::code).toSet()

    // Chinese and Japanese (Han, kana, "ー"), Thai, Lao, Myanmar and Khmer, by code point
    // (Character.UnicodeScript needs Android 7).
    private val UNSPACED_RANGES = listOf(
        0x0E00..0x0EFF, 0x1000..0x109F, 0x1780..0x17FF,
        0x2E80..0x2FDF, 0x3005..0x3007, 0x3021..0x3029, 0x3038..0x303B,
        0x3040..0x30FF, 0x31F0..0x31FF, 0x3400..0x4DBF, 0x4E00..0x9FFF,
        0xF900..0xFAFF, 0xFF66..0xFF9F, 0x20000..0x323AF,
    )

    private fun isUnspaced(codePoint: Int) = UNSPACED_RANGES.any { codePoint in it }

    private fun display(word: Word, romanized: Boolean) = if (romanized) (word.romanizedText ?: word.text) else word.text

    // Reference (IsLetterCapable.ts) has no lower length bound — even a single-character
    // held word (e.g. "I", "oh") gets the letter treatment if held long enough.
    private fun isLetterCapable(word: Word, config: RenderConfig, romanized: Boolean): Boolean {
        val display = display(word, romanized)
        if (config.isAppleMusic) {
            return config.lettersEnabled && com.almog.spotifytablet.lyrics.mobile.animation.AppleMusicMotion.emphasizes(
                display, GraphemeSegmenter.segment(display).size, word.duration,
            )
        }
        return config.lettersEnabled &&
            word.duration >= config.letterDurationThresholdMs &&
            GraphemeSegmenter.segment(display).size in 1..config.letterMaxLength &&
            !RtlDetector.isRtl(display)
    }

    private fun synthesize(word: Word, config: RenderConfig, romanized: Boolean): Word {
        val display = display(word, romanized)
        val graphemes = GraphemeSegmenter.segment(display)
        val len = graphemes.size

        if (!isLetterCapable(word, config, romanized)) {
            return if (word.isLetterGroup) word.copy(isLetterGroup = false, letters = emptyList()) else word
        }

        // Reference (Emphasize.ts) Subtractions: the emphasized WORD's own window is shrunk too,
        // not just the letters — normal mode trims 250ms off the tail (the "breather"); simple
        // mode nudges the window by {Start:-21, End:-40} (i.e. +21ms/+40ms).
        // The Apple Music style times its letters off the word's own window.
        val subStartMs = if (config.isSimple) -21L else 0L
        val subEndMs = when {
            config.isAppleMusic -> 0L
            config.isSimple -> -40L
            else -> 250L
        }
        val windowStart = word.startMs - subStartMs
        val windowEnd = (word.endMs - subEndMs).coerceAtLeast(windowStart + 1L)
        val span = (windowEnd - windowStart).toFloat() / len

        // Every character — including punctuation — is split into its own timed letter and
        // animated identically; punctuation isn't excluded.
        val letters = graphemes.mapIndexed { i, grapheme ->
            Letter(
                char = grapheme,
                startMs = windowStart + (i * span).toLong(),
                endMs = windowStart + ((i + 1) * span).toLong(),
            )
        }
        return word.copy(startMs = windowStart, endMs = windowEnd, isLetterGroup = true, letters = letters)
    }
}
