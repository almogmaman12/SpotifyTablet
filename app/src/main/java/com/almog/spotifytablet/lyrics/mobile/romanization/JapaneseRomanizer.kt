package com.almog.spotifytablet.lyrics.mobile.romanization

import com.atilika.kuromoji.ipadic.Tokenizer

/**
 * Japanese through kuroshiro 1.2.0's rules (MIT) over kuromoji (IPADIC), converting
 * `{ to: "romaji", mode: "spaced" }`. Each token is read by its *pronunciation* (so the particle
 * は is "wa" and 東京 is "tōkyō"), through [KanaRomanizer]'s Hepburn, one token per word.
 *
 * The tokenizer loads a bundled dictionary lazily on first use (off the main thread).
 */
object JapaneseRomanizer : Romanizer {
    override val script = Script.JAPANESE

    private val tokenizer: Tokenizer? by lazy {
        try { Tokenizer() } catch (t: Throwable) { null }
    }

    override fun isAvailable(): Boolean = tokenizer != null

    override fun romanize(text: String): String {
        val tk = tokenizer ?: return KanaRomanizer.romanize(text)
        return try {
            tokens(tk, text).joinToString(" ") { token -> KanaRomanizer.romanize(token.spoken()) }
        } catch (t: Throwable) {
            KanaRomanizer.romanize(text)
        }
    }

    /**
     * Romanizes one line's [syllables] with the whole line as tokenizer context, returning one
     * entry per syllable. (Romanizing each syllable alone loses readings when a word is
     * split across syllables: 段|々 → "dan" + "々", 堕|ち → "堕" + "chi".) Here 段々 reads だんだん and the
     * reading is shared back out to the syllables it spans.
     */
    fun romanizeLine(syllables: List<String>): List<String> {
        val tk = tokenizer ?: return syllables.map(KanaRomanizer::romanize)
        val owner = syllables.flatMapIndexed { index, text -> List(text.length) { index } }
        val kana = List(syllables.size) { mutableListOf<String>() }
        return try {
            for (token in tokens(tk, syllables.joinToString(""))) {
                val readings = if (hasJapanese(token.surface)) charReadings(token.surface, token.pronunciation ?: token.reading)
                else token.surface.map(Char::toString)
                val bySyllable = readings.indices.groupBy { owner[token.position + it] }.toSortedMap()
                var carry = ""
                for ((syllable, chars) in bySyllable) {
                    var piece = carry + chars.joinToString("") { readings[it] }
                    carry = ""
                    // A っ closing a syllable doubles the next one's consonant (き|っ|と → ki, tto).
                    if (syllable != bySyllable.lastKey()) {
                        while (piece.lastOrNull() == 'ッ') {
                            carry = "ッ$carry"
                            piece = piece.dropLast(1)
                        }
                    }
                    if (piece.isNotBlank()) kana[syllable] += piece
                }
            }
            kana.map { pieces -> pieces.joinToString(" ") { KanaRomanizer.romanize(it).trim() }.trim() }
        } catch (t: Throwable) {
            syllables.map(KanaRomanizer::romanize)
        }
    }

    /** A kuromoji token after kuroshiro's `patchTokens`. */
    private class KToken(
        var surface: String,
        var reading: String,
        var pronunciation: String?,
        val pos: String,
        val position: Int,
    ) {
        /** What kuroshiro romanizes: the pronunciation of Japanese text, anything else as written. */
        fun spoken(): String = if (hasJapanese(surface)) pronunciation ?: reading else surface
    }

    private fun tokens(tk: Tokenizer, text: String): List<KToken> {
        val tokens = tk.tokenize(text).mapTo(mutableListOf()) { t ->
            val reading = t.reading?.takeIf { it != "*" }
            val pronunciation = t.pronunciation?.takeIf { it != "*" }
            KToken(
                surface = t.surface,
                reading = when {
                    !hasJapanese(t.surface) -> t.surface
                    reading == null -> if (t.surface.all(::isKanaChar)) toKatakana(t.surface) else t.surface
                    else -> toKatakana(reading)
                },
                pronunciation = pronunciation,
                pos = t.partOfSpeechLevel1,
                position = t.position,
            )
        }
        // 助動詞 "う" after a 動詞 joins it as a long vowel (行こう → ikō).
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (t.pos == "助動詞" && (t.surface == "う" || t.surface == "ウ") && i > 0 && tokens[i - 1].pos == "動詞") {
                val prev = tokens[i - 1]
                prev.surface += "う"
                prev.pronunciation = (prev.pronunciation ?: prev.reading) + "ー"
                prev.reading += "ウ"
                tokens.removeAt(i)
                continue
            }
            i++
        }
        // A 動詞 or 形容詞 ending in っ takes the next token in (言っ|て → itte).
        var j = 0
        while (j < tokens.size) {
            val t = tokens[j]
            if ((t.pos == "動詞" || t.pos == "形容詞") && t.surface.length > 1 &&
                (t.surface.endsWith('っ') || t.surface.endsWith('ッ')) && j + 1 < tokens.size
            ) {
                val next = tokens[j + 1]
                t.surface += next.surface
                t.pronunciation = (t.pronunciation ?: t.reading) + (next.pronunciation ?: next.reading)
                t.reading += next.reading
                tokens.removeAt(j + 1)
                continue
            }
            j++
        }
        return tokens
    }

    /** One kana reading per surface character: kana at the ends read as themselves, the kanji middle shares the rest by mora. */
    internal fun charReadings(surface: String, reading: String?): List<String> {
        if (reading == null) return surface.map(Char::toString)
        val kata = surface.map(::toKatakana)
        var prefix = 0
        while (prefix < surface.length && prefix < reading.length && isKana(kata[prefix]) && kata[prefix] == reading[prefix]) prefix++
        var suffix = 0
        while (suffix < surface.length - prefix && suffix < reading.length - prefix &&
            isKana(kata[surface.length - 1 - suffix]) && kata[surface.length - 1 - suffix] == reading[reading.length - 1 - suffix]
        ) suffix++
        val middleChars = surface.length - prefix - suffix
        if (middleChars == 0) return kata.map(Char::toString)
        val morae = morae(reading.substring(prefix, reading.length - suffix))
        val middle = List(middleChars) { i ->
            // ponytail: even mora split across kanji, per-kanji readings need a dictionary we don't bundle
            morae.subList(i * morae.size / middleChars, (i + 1) * morae.size / middleChars).joinToString("")
        }
            // A long vowel split off from its mora (と|う read トー) reads as the kana it's written with.
            .mapIndexed { i, r -> if (r.startsWith('ー')) kata[prefix + i] + r.drop(1) else r }
        return kata.take(prefix).map(Char::toString) + middle + kata.takeLast(suffix).map(Char::toString)
    }

    private fun morae(reading: String): List<String> = buildList {
        for (c in reading) {
            if (isEmpty() || c !in "ャュョァィゥェォヮー") add(c.toString()) else this[lastIndex] += c
        }
    }

    // kuroshiro's hasJapanese: any kanji or kana.
    private fun hasJapanese(s: String) = s.any { isKanaChar(it) || isKanji(it) }
    private fun isKanaChar(c: Char) = c in '぀'..'ゟ' || c in '゠'..'ヿ'
    private fun isKanji(c: Char) = c in '一'..'鿏' || c in '豈'..'﫿' || c in '㐀'..'䶿'
    private fun isKana(c: Char) = c.code in 0x30A1..0x30FC
    private fun toKatakana(c: Char) = if (c.code in 0x3041..0x3096) (c.code + 0x60).toChar() else c
    private fun toKatakana(s: String) = String(CharArray(s.length) { toKatakana(s[it]) })
}
