package com.almog.spotifytablet.lyrics.mobile.romanization

/**
 * Resolves the best available [Romanizer] for a script. Japanese prefers the dictionary-backed
 * [JapaneseRomanizer] (Kuromoji) when present, falling back to kana-only conversion.
 */
object Romanizers {
    fun forScript(script: Script): Romanizer? = when (script) {
        Script.JAPANESE -> JapaneseRomanizerProvider.get()
        Script.CHINESE -> PinyinRomanizer
        Script.KOREAN -> KoreanRomanizer
        Script.CYRILLIC -> CyrillicRomanizer
        Script.GREEK -> GreekRomanizer
        Script.LATIN -> null
    }
}

/**
 * On-device romanization: the
 * scripts present are detected across the whole song first (so a kanji-only syllable in a
 * Japanese song is read as Japanese, never pinyin), then each text is run through every
 * present-script romanizer whose characters it contains, in priority order. Japanese is read
 * with its whole line as context, since syllable splits cut words apart.
 */
object RomanizationService {

    /** [lines] of syllable texts in, the same shape out: each romanization, or null when nothing changed. */
    fun romanize(lines: List<List<String>>): List<List<String?>> {
        val songScripts = ScriptDetector.detect(lines.joinToString("\n") { it.joinToString("") })
        if (songScripts.isEmpty()) return lines.map { line -> line.map { null } }
        val japanese = Romanizers.forScript(Script.JAPANESE) as? JapaneseRomanizer
        return lines.map { line ->
            val staged = if (japanese != null && Script.JAPANESE in songScripts &&
                line.any { ScriptDetector.contains(Script.JAPANESE, it) }
            ) japanese.romanizeLine(line) else line
            line.indices.map { i ->
                var romanized = staged[i]
                for (script in songScripts) {
                    if (!ScriptDetector.contains(script, romanized)) continue
                    val romanizer = Romanizers.forScript(script) ?: continue
                    if (romanizer.isAvailable()) romanized = romanizer.romanize(romanized)
                }
                romanized.takeIf { it != line[i] && it.isNotBlank() }
            }
        }
    }
}
