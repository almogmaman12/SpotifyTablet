package com.almog.spotifytablet.lyrics.mobile.translation

import com.almog.spotifytablet.lyrics.mobile.romanization.ScriptDetector
import com.almog.spotifytablet.lyrics.mobile.models.Line
import com.almog.spotifytablet.lyrics.mobile.models.Word
import com.almog.spotifytablet.lyrics.mobile.romanization.RomanizationMode

/** A note piece retains its source word and its range in the complete, spaced note. */
internal data class RomanizationPiece(val word: Word, val sourceWordIndex: Int, val text: String, val textOffset: Int)

internal data class RomanizationNote(val text: String, val pieces: List<RomanizationPiece>)

internal fun romanizationNote(original: Line): RomanizationNote? {
    if (original.isInterlude || original.words.none { it.romanizedText != null }) return null
    // Chinese pinyin separates syllables; Japanese keeps syllables of the same word glued.
    val chinese = original.words.any { word -> word.text.any(ScriptDetector::hasHan) } &&
        original.words.none { word -> word.text.any(ScriptDetector::hasKana) }
    val text = StringBuilder()
    val pieces = original.words.mapIndexed { index, word ->
        if (text.isNotEmpty() && (!word.isPartOfWord || chinese)) text.append(' ')
        val piece = word.romanizedText ?: word.text
        RomanizationPiece(word, index, piece, text.length).also { text.append(piece) }
    }
    return RomanizationNote(text.toString(), pieces).takeIf { it.text.isNotBlank() }
}

/** Supplemental rows are independent: romanization first, then translation. */
internal fun presentationSupplements(original: Line, romanize: Boolean, romanizationMode: RomanizationMode,
    translation: String?, translationMode: TranslationMode?): List<String> {
    if (original.isInterlude) return emptyList()
    val romanized = if (romanize && romanizationMode == RomanizationMode.UnderLine) romanizationNote(original)?.text else null
    return listOfNotNull(romanized, translation?.takeIf { translationMode == TranslationMode.UnderLine && it.isNotBlank() })
}

data class TranslationPresentation(val texts: List<String?>, val mode: TranslationMode) {
    companion object {
        fun forTimeline(originals: List<Line>, timeline: List<Line>, result: TranslationResult, mode: TranslationMode): TranslationPresentation? {
            if (result.texts.size != originals.size) return null
            // Timeline copies retain their words; synthetic interludes have none.
            val indices = java.util.IdentityHashMap<List<Word>, Int>()
            originals.forEachIndexed { index, line -> indices[line.words] = index }
            return TranslationPresentation(timeline.map { line ->
                if (line.isInterlude) null else indices[line.words]?.let(result.texts::get)
                    ?.let { plainTranslation(line.words.joinToString("") { word -> word.text }, it) }
            }, mode)
        }
    }

    fun displayLines(lines: List<Line>): List<Line> {
        if (texts.size != lines.size || mode != TranslationMode.Replace) return lines
        return lines.mapIndexed { index, line ->
            texts[index]?.takeIf(String::isNotBlank)?.let { text ->
                line.copy(words = listOf(Word(text, line.startMs, line.endMs)), translationReplaces = true)
            } ?: line
        }
    }
}


/** Translators mark titles and stress as *emphasis*; shown as is, the asterisks are just noise. */
internal fun plainTranslation(original: String, translation: String): String =
    if ('*' in original) translation else translation.replace(EMPHASIS, "$1").replace("*", "").trim()

private val EMPHASIS = Regex("""\*+([^*]+)\*+""")

enum class TranslationMode(val label: String) { UnderLine("Under each line"), Replace("Replace") }

/** The lines' translated texts, index-aligned with the originals. Translation itself is not wired up in this app. */
data class TranslationResult(val texts: List<String?>)
