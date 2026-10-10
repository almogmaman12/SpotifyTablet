package com.almog.spotifytablet.lyrics.mobile.romanization

/**
 * Detects the dominant non-Latin script of a block of text, in a cascade: kana implies Japanese, otherwise Han
 * implies Chinese; then Hangul, Cyrillic, Greek. Character-class scanning over the whole
 * lyric is sufficient offline (no language-guessing library needed).
 */
object ScriptDetector {

    internal fun hasKana(c: Char): Boolean {
        val b = Character.UnicodeBlock.of(c)
        return b == Character.UnicodeBlock.HIRAGANA || b == Character.UnicodeBlock.KATAKANA
    }

    internal fun hasHan(c: Char): Boolean {
        val b = Character.UnicodeBlock.of(c)
        return b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            b == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
            b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
    }

    private fun isHangul(c: Char): Boolean {
        val b = Character.UnicodeBlock.of(c)
        return b == Character.UnicodeBlock.HANGUL_SYLLABLES ||
            b == Character.UnicodeBlock.HANGUL_JAMO ||
            b == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO
    }

    private fun isCyrillic(c: Char): Boolean =
        Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CYRILLIC

    private fun isGreek(c: Char): Boolean =
        Character.UnicodeBlock.of(c) == Character.UnicodeBlock.GREEK

    /** Per-item test once [script] is known to be in the song; Japanese covers kana and kanji. */
    fun contains(script: Script, text: String): Boolean = text.any { c ->
        when (script) {
            Script.JAPANESE -> hasKana(c) || hasHan(c)
            Script.CHINESE -> hasHan(c)
            Script.KOREAN -> isHangul(c)
            Script.CYRILLIC -> isCyrillic(c)
            Script.GREEK -> isGreek(c)
            Script.LATIN -> false
        }
    }

    /**
     * Returns the set of scripts present in [text], most-specific first. Japanese is chosen over
     * Chinese when any kana is present; if only Han is present the text is treated as Chinese.
     */
    fun detect(text: String): Set<Script> {
        var kana = false
        var han = false
        var hangul = false
        var cyr = false
        var greek = false
        for (c in text) {
            when {
                hasKana(c) -> kana = true
                hasHan(c) -> han = true
                isHangul(c) -> hangul = true
                isCyrillic(c) -> cyr = true
                isGreek(c) -> greek = true
            }
        }
        val result = linkedSetOf<Script>()
        if (kana) result.add(Script.JAPANESE)
        else if (han) result.add(Script.CHINESE)
        if (hangul) result.add(Script.KOREAN)
        if (cyr) result.add(Script.CYRILLIC)
        if (greek) result.add(Script.GREEK)
        return result
    }
}
