package com.almog.spotifytablet.lyrics.mobile.romanization

/**
 * Chinese (Han → pinyin) as the `pinyin` 4.0.0 package (MIT) gives it, called as
 * `pinyin(text, { segment: false, group: true }).join("-")`. Each Han character takes its first
 * (most common) reading with tone marks; runs of anything else stay as one piece; the pieces
 * join with "-" (你好 world → nǐ-hǎo- world).
 *
 * `romanization/pinyin.txt` is that package's character dictionary, reduced to the first reading
 * per character: one line each, the character then its reading.
 */
object PinyinRomanizer : Romanizer {
    override val script = Script.CHINESE

    private val readings: Map<Int, String>? by lazy {
        runCatching {
            val stream = PinyinRomanizer::class.java.classLoader!!.getResourceAsStream("romanization/pinyin.txt")!!
            buildMap {
                stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    for (line in lines) {
                        if (line.isEmpty()) continue
                        val code = line.codePointAt(0)
                        put(code, line.substring(Character.charCount(code)))
                    }
                }
            }
        }.getOrNull()
    }

    override fun isAvailable(): Boolean = readings != null

    override fun romanize(text: String): String {
        val dict = readings ?: return text
        val pieces = mutableListOf<String>()
        val other = StringBuilder()
        // The package walks UTF-16 units (charCodeAt), so astral characters never match.
        for (c in text) {
            val reading = dict[c.code]
            if (reading != null) {
                if (other.isNotEmpty()) {
                    pieces += other.toString()
                    other.clear()
                }
                pieces += reading
            } else {
                other.append(c)
            }
        }
        if (other.isNotEmpty()) pieces += other.toString()
        return pieces.joinToString("-")
    }
}
