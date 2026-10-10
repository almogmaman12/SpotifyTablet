package com.almog.spotifytablet.lyrics.mobile.translation

import com.almog.spotifytablet.lyrics.mobile.romanization.HumanRomanization
import com.almog.spotifytablet.lyrics.mobile.network.data.blend.SequenceMatcher
import java.text.Normalizer
import java.util.Locale

data class GeniusTranslationPair(val originals: List<String>, val translations: List<String>, val sourceLanguage: String?) {
    companion object {
        /**
         * The lines of [original] and [translation] that pair up. Translators often merge two
         * sung lines into one, so the pages rarely match as a whole, but they keep the same
         * sections: each section whose line counts match pairs line for line, and the others are
         * left out. Pages without the same sections pair only when they match as a whole.
         */
        fun fromText(original: String, translation: String, sourceLanguage: String? = null): GeniusTranslationPair? {
            val ours = sections(original)
            val theirs = sections(translation)
            val pairs = if (ours.size == theirs.size) {
                ours.zip(theirs).filter { (a, b) -> a.size == b.size }.flatMap { (a, b) -> a.zip(b) }
            } else {
                val a = HumanRomanization.cleanLines(original)
                val b = HumanRomanization.cleanLines(translation)
                if (a.size == b.size) a.zip(b) else emptyList()
            }
            return if (pairs.isEmpty()) null
            else GeniusTranslationPair(pairs.map { it.first }, pairs.map { it.second }, languageCode(sourceLanguage))
        }

        /** A page's lyric lines, cut at its section headers ("[Chorus]"); sections with no lines are dropped. */
        private fun sections(text: String): List<List<String>> {
            val out = mutableListOf<MutableList<String>>(mutableListOf())
            for (line in text.lines()) {
                if (line.trim().let { it.startsWith("[") && it.endsWith("]") }) out += mutableListOf<String>()
                else out.last() += line
            }
            return out.map { HumanRomanization.cleanLines(it.joinToString("\n")) }.filter { it.isNotEmpty() }
        }
    }
}

/** Matches original script in order; weak or ambiguous matches leave the machine line alone. */
object HumanTranslation {
    fun align(document: TranslationDocument, pair: GeniusTranslationPair): List<String?> {
        val out = MutableList<String?>(document.lines.size) { null }
        if (pair.originals.isEmpty() || pair.originals.size != pair.translations.size) return out
        val leadsWithBackground = document.lines.filter { it.address.backgroundIndex != null }.map { it.address.lineIndex }.toSet()
        val ours = document.lines.filter { !it.boundary && it.address.backgroundIndex == null }
        val mine = ours.map { key(it.text) }
        val theirs = pair.originals.map { key(it) }
        val withoutBackground = pair.originals.map { key(it.replace(BRACKETED, " ")) }
        val score = Array(ours.size + 1) { DoubleArray(theirs.size + 1) }
        val move = Array(ours.size + 1) { IntArray(theirs.size + 1) }
        fun candidate(i: Int, j: Int) = if (ours[i].address.lineIndex in leadsWithBackground) withoutBackground[j] else theirs[j]
        for (i in 1..ours.size) {
            for (j in 1..theirs.size) {
                val dropOurs = score[i - 1][j]
                val dropTheirs = score[i][j - 1]
                var best = maxOf(dropOurs, dropTheirs)
                var step = if (dropOurs >= dropTheirs) 2 else 3
                val a = mine[i - 1]
                val b = candidate(i - 1, j - 1)
                val similarity = if (a.isEmpty() || b.isEmpty()) 0.0 else SequenceMatcher.of(a, b).ratio()
                if (similarity >= MIN_SCORE) {
                    val take = score[i - 1][j - 1] + similarity - MIN_SCORE + 0.01
                    if (take > best) { best = take; step = 1 }
                }
                score[i][j] = best
                move[i][j] = step
            }
        }
        fun translated(i: Int, j: Int): String? = pair.translations[j].let {
            if (ours[i].address.lineIndex in leadsWithBackground) it.replace(BRACKETED, " ").replace(Regex("\\s+"), " ").trim() else it
        }.takeIf(String::isNotBlank)
        var i = ours.size
        var j = theirs.size
        while (i > 0 && j > 0) {
            when (move[i][j]) {
                1 -> { out[ours[i - 1].index] = translated(i - 1, j - 1); i--; j-- }
                2 -> i--
                else -> j--
            }
        }
        // A collapsed repeated chorus may reuse an exact original only when every occurrence
        // on the page has the same translation. Different human readings stay ambiguous.
        ours.forEachIndexed { index, line ->
            val exact = theirs.indices.filter { mine[index].isNotEmpty() && mine[index] == candidate(index, it) }
            val readings = exact.mapNotNull { translated(index, it) }.distinct()
            if (exact.size > 1 && readings.size > 1) out[line.index] = null
            else if (out[line.index] == null && readings.size == 1) out[line.index] = readings.single()
        }
        return out
    }

    private fun key(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

    private val BRACKETED = Regex("\\([^()]*\\)|\\[[^\\[\\]]*]")
    private const val MIN_SCORE = 0.85
}

fun combineTranslations(key: TranslationKey, human: List<String?>, machine: List<String?>, language: String?): TranslationResult {
    require(human.size == machine.size)
    val texts = human.indices.map { human[it]?.takeIf(String::isNotBlank) ?: machine[it]?.takeIf(String::isNotBlank) }
    val origins = texts.indices.map { index ->
        when {
            texts[index] == null -> null
            !human[index].isNullOrBlank() -> TranslationOrigin.Genius
            else -> TranslationOrigin.valueOf(key.provider.name)
        }
    }
    return TranslationResult(key, texts, language, origins)
}
