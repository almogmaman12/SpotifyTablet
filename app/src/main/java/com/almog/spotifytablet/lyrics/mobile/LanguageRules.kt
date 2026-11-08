package com.almog.spotifytablet.lyrics.mobile

/**
 * Decides from a song's own lyrics what to do with it, so translation and romanization need no switching by hand:
 *  - Hebrew: leave it alone.
 *  - English: leave it alone.
 *  - Japanese, Chinese, Korean, Cyrillic, Greek: romanize and translate.
 *  - Arabic and other Latin-script languages (Spanish, French, ...): translate.
 * It reads the writing system, and for Latin script it checks how many of the words are common English words.
 */
object LanguageRules {
    enum class Kind { UNKNOWN, ENGLISH, HEBREW, ROMANIZABLE, OTHER }

    class Verdict(val kind: Kind) {
        val romanize: Boolean get() = kind == Kind.ROMANIZABLE
        val translate: Boolean get() = kind == Kind.ROMANIZABLE || kind == Kind.OTHER
    }

    private val englishWords = hashSetOf(
        "the", "you", "i", "and", "to", "a", "me", "it", "in", "my", "is", "of", "that", "on", "for", "with", "be",
        "your", "this", "we", "are", "all", "but", "so", "just", "like", "know", "don't", "i'm", "can", "not", "what",
        "when", "no", "oh", "if", "now", "have", "do", "up", "she", "he", "they", "was", "love", "got", "baby"
    )

    fun detect(text: String): Verdict {
        var letters = 0
        var hebrew = 0; var arabic = 0; var asian = 0; var other = 0; var latin = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (!Character.isLetter(cp)) continue
            letters++
            when (Character.UnicodeScript.of(cp)) {
                Character.UnicodeScript.HEBREW -> hebrew++
                Character.UnicodeScript.ARABIC -> arabic++
                Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HAN,
                Character.UnicodeScript.HANGUL, Character.UnicodeScript.CYRILLIC, Character.UnicodeScript.GREEK -> asian++
                Character.UnicodeScript.LATIN -> latin++
                else -> other++
            }
        }
        if (letters < 20) return Verdict(Kind.UNKNOWN)
        fun share(n: Int) = n.toFloat() / letters
        return when {
            share(hebrew) >= 0.4f -> Verdict(Kind.HEBREW)
            share(asian) >= 0.4f -> Verdict(Kind.ROMANIZABLE)
            share(arabic) >= 0.4f -> Verdict(Kind.OTHER)
            share(latin) >= 0.6f -> if (englishShare(text) >= 0.12f) Verdict(Kind.ENGLISH) else Verdict(Kind.OTHER)
            else -> Verdict(Kind.UNKNOWN)
        }
    }

    private fun englishShare(text: String): Float {
        val words = text.lowercase().split(Regex("[^\\p{L}']+")).filter { it.isNotEmpty() }
        if (words.size < 10) return 1f // too little to judge: do not translate
        return words.count { it in englishWords }.toFloat() / words.size
    }
}
