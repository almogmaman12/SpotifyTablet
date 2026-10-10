package com.almog.spotifytablet.lyrics.mobile.romanization

/**
 * Port of aromanize-js (MIT), using its "RevisedRomanizationTranscription" rules, which spell
 * the sound (사랑해 → saranghae, 좋아 → joha) rather than the letters (salanghae, joh-a), for
 * singing along.
 *
 * Each syllable block splits into jamo; a final consonant (batchim) reads by what follows it,
 * longest rule first: final + next initial + next vowel, then final + next initial, then alone.
 * When the final's rule already covers the next initial, that initial isn't read again.
 */
object KoreanRomanizer : Romanizer {
    override val script = Script.KOREAN

    override fun romanize(text: String): String {
        val out = StringBuilder(text.length * 2)
        var current: String? = null
        var processJaeum = true
        for (index in 0..text.length) {
            val next = if (index < text.length) segment(text[index]) else ""
            val cur = current
            if (cur != null) {
                var piece = ""
                if (processJaeum) {
                    val first = cur.substring(0, 1)
                    piece += CHO[first] ?: first
                } else {
                    processJaeum = true
                }
                if (cur.length > 1) {
                    val vowel = cur.substring(1, 2)
                    piece += JUNG[vowel] ?: vowel
                    if (cur.length == 2) {
                        if (next.isNotEmpty() && isChoseong(next[0])) piece += " "
                    } else {
                        val last = cur.substring(2, 3)
                        val nextFirst = next.take(1)
                        val nextSecond = next.drop(1).take(1)
                        val three = JONG[last + nextFirst + nextSecond]
                        val two = JONG[last + nextFirst]
                        when {
                            three != null -> { piece += three; processJaeum = false }
                            two != null -> { piece += two; processJaeum = false }
                            else -> {
                                piece += JONG[last] ?: last
                                if (nextFirst.isNotEmpty() && isChoseong(nextFirst[0])) piece += " "
                            }
                        }
                    }
                    // No hyphenation asked for: the syllable break (" ") goes.
                    piece = piece.replaceFirst(" ", "")
                }
                out.append(piece)
            }
            current = next
        }
        return out.toString()
    }

    /** A Hangul syllable as its initial, vowel and (if any) final jamo; anything else as itself. */
    private fun segment(c: Char): String {
        val idx = c.code - 0xAC00
        if (idx !in 0..11171) return c.toString()
        val sb = StringBuilder(3)
        sb.append((idx / 588 + 0x1100).toChar())
        sb.append((idx % 588 / 28 + 0x1161).toChar())
        if (idx % 28 != 0) sb.append((idx % 28 + 0x11A7).toChar())
        return sb.toString()
    }

    private fun isChoseong(c: Char) = c.code in 0x1100..0x1112

    private val CHO = mapOf(
        "\u1100" to "g",
        "\u1101" to "kk",
        "\u1102" to "n",
        "\u1103" to "d",
        "\u1104" to "tt",
        "\u1105" to "r",
        "\u1106" to "m",
        "\u1107" to "b",
        "\u1108" to "pp",
        "\u1109" to "s",
        "\u110A" to "ss",
        "\u110B" to "",
        "\u110C" to "j",
        "\u110D" to "jj",
        "\u110E" to "ch",
        "\u110F" to "k",
        "\u1110" to "t",
        "\u1111" to "p",
        "\u1112" to "h",
    )

    private val JUNG = mapOf(
        "\u1161" to "a",
        "\u1162" to "ae",
        "\u1163" to "ya",
        "\u1164" to "yae",
        "\u1165" to "eo",
        "\u1166" to "e",
        "\u1167" to "yeo",
        "\u1168" to "ye",
        "\u1169" to "o",
        "\u116A" to "wa",
        "\u116B" to "wae",
        "\u116C" to "oe",
        "\u116D" to "yo",
        "\u116E" to "u",
        "\u116F" to "wo",
        "\u1170" to "we",
        "\u1171" to "wi",
        "\u1172" to "yu",
        "\u1173" to "eu",
        "\u1174" to "eui",
        "\u1175" to "i",
    )

    private val JONG = mapOf(
        "\u11A8" to "k",
        "\u11A8\u110B" to "g",
        "\u11A8\u1102" to "ngn",
        "\u11A8\u1105" to "ngn",
        "\u11A8\u1106" to "ngm",
        "\u11A8\u1112" to "kh",
        "\u11A9" to "kk",
        "\u11A9\u110B" to "kg",
        "\u11A9\u1102" to "ngn",
        "\u11A9\u1105" to "ngn",
        "\u11A9\u1106" to "ngm",
        "\u11A9\u1112" to "kh",
        "\u11AA" to "k",
        "\u11AA\u110B" to "ks",
        "\u11AA\u1102" to "ngn",
        "\u11AA\u1105" to "ngn",
        "\u11AA\u1106" to "ngm",
        "\u11AA\u1112" to "kch",
        "\u11AB" to "n",
        "\u11AB\u1105" to "ll",
        "\u11AC" to "n",
        "\u11AC\u110B" to "nj",
        "\u11AC\u1102" to "nn",
        "\u11AC\u1105" to "nn",
        "\u11AC\u1106" to "nm",
        "\u11AC\u314E" to "nch",
        "\u11AD" to "n",
        "\u11AD\u110B" to "nh",
        "\u11AD\u1105" to "nn",
        "\u11AE" to "t",
        "\u11AE\u110B" to "d",
        "\u11AE\u1102" to "nn",
        "\u11AE\u1105" to "nn",
        "\u11AE\u1106" to "nm",
        "\u11AE\u1112" to "th",
        "\u11AF" to "l",
        "\u11AF\u110B" to "r",
        "\u11AF\u1102" to "ll",
        "\u11B0" to "k",
        "\u11B0\u110B" to "lg",
        "\u11B0\u1102" to "ngn",
        "\u11B0\u1105" to "ngn",
        "\u11B0\u1106" to "ngm",
        "\u11B0\u1112" to "lkh",
        "\u11B1" to "m",
        "\u11B1\u110B" to "lm",
        "\u11B1\u1102" to "mn",
        "\u11B1\u1105" to "mn",
        "\u11B1\u1106" to "mm",
        "\u11B1\u1112" to "lmh",
        "\u11B2" to "p",
        "\u11B2\u110B" to "lb",
        "\u11B2\u1102" to "mn",
        "\u11B2\u1105" to "mn",
        "\u11B2\u1106" to "mm",
        "\u11B2\u1112" to "lph",
        "\u11B3" to "t",
        "\u11B3\u110B" to "ls",
        "\u11B3\u1102" to "nn",
        "\u11B3\u1105" to "nn",
        "\u11B3\u1106" to "nm",
        "\u11B3\u1112" to "lsh",
        "\u11B4" to "t",
        "\u11B4\u110B" to "lt",
        "\u11B4\u1102" to "nn",
        "\u11B4\u1105" to "nn",
        "\u11B4\u1106" to "nm",
        "\u11B4\u1112" to "lth",
        "\u11B5" to "p",
        "\u11B5\u110B" to "lp",
        "\u11B5\u1102" to "mn",
        "\u11B5\u1105" to "mn",
        "\u11B5\u1106" to "mm",
        "\u11B5\u1112" to "lph",
        "\u11B6" to "l",
        "\u11B6\u110B" to "lh",
        "\u11B6\u1102" to "ll",
        "\u11B6\u1105" to "ll",
        "\u11B6\u1106" to "lm",
        "\u11B6\u1112" to "lh",
        "\u11B7" to "m",
        "\u11B7\u1105" to "mn",
        "\u11B8" to "p",
        "\u11B8\u110B" to "b",
        "\u11B8\u1102" to "mn",
        "\u11B8\u1105" to "mn",
        "\u11B8\u1106" to "mm",
        "\u11B8\u1112" to "ph",
        "\u11B9" to "p",
        "\u11B9\u110B" to "ps",
        "\u11B9\u1102" to "mn",
        "\u11B9\u1105" to "mn",
        "\u11B9\u1106" to "mm",
        "\u11B9\u1112" to "psh",
        "\u11BA" to "t",
        "\u11BA\u110B" to "s",
        "\u11BA\u1102" to "nn",
        "\u11BA\u1105" to "nn",
        "\u11BA\u1106" to "nm",
        "\u11BA\u1112" to "sh",
        "\u11BB" to "t",
        "\u11BB\u110B" to "ss",
        "\u11BB\u1102" to "tn",
        "\u11BB\u1105" to "tn",
        "\u11BB\u1106" to "nm",
        "\u11BB\u1112" to "th",
        "\u11BC" to "ng",
        "\u11BD" to "t",
        "\u11BD\u110B" to "j",
        "\u11BD\u1102" to "nn",
        "\u11BD\u1105" to "nn",
        "\u11BD\u1106" to "nm",
        "\u11BD\u1112" to "ch",
        "\u11BE" to "t",
        "\u11BE\u110B" to "ch",
        "\u11BE\u1102" to "nn",
        "\u11BE\u1105" to "nn",
        "\u11BE\u1106" to "nm",
        "\u11BE\u1112" to "ch",
        "\u11BF" to "k",
        "\u11BF\u110B" to "k",
        "\u11BF\u1102" to "ngn",
        "\u11BF\u1105" to "ngn",
        "\u11BF\u1106" to "ngm",
        "\u11BF\u1112" to "kh",
        "\u11C0" to "t",
        "\u11C0\u110B" to "t",
        "\u11C0\u1102" to "nn",
        "\u11C0\u1105" to "nn",
        "\u11C0\u1106" to "nm",
        "\u11C0\u1112" to "th",
        "\u11C1" to "p",
        "\u11C1\u110B" to "p",
        "\u11C1\u1102" to "mn",
        "\u11C1\u1105" to "mn",
        "\u11C1\u1106" to "mm",
        "\u11C1\u1112" to "ph",
        "\u11C2" to "t",
        "\u11C2\u110B" to "h",
        "\u11C2\u1102" to "nn",
        "\u11C2\u1105" to "nn",
        "\u11C2\u1106" to "mm",
        "\u11C2\u1112" to "t",
    )

}
