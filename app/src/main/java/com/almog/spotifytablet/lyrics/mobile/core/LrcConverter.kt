package com.almog.spotifytablet.lyrics.mobile.core

import java.util.Locale

/**
 * LRC to TTML, so local LRC files go the same way as everything else. Line-timed LRC becomes
 * line-timed TTML; enhanced LRC (`<mm:ss.xx>` stamps before words or syllables) becomes
 * word-timed TTML, never flattened. Pieces with no space between them are one word, as in TTML.
 */
object LrcConverter {
    data class Tags(val title: String?, val artist: String?)

    private val tag = Regex("""^\[([a-zA-Z]+):(.*)]\s*$""")
    private val lineStamp = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val wordStamp = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")

    /** What the file says it is, from its `[ti:]` and `[ar:]` tags. */
    fun tags(lrc: String): Tags {
        val tags = lrc.lineSequence().mapNotNull { tag.find(it.trim()) }
            .associate { it.groupValues[1].lowercase(Locale.ROOT) to it.groupValues[2].trim() }
        return Tags(tags["ti"]?.ifBlank { null }, tags["ar"]?.ifBlank { null })
    }

    /** Whether [text] reads as LRC: at least one line stamp. */
    fun isLrc(text: String): Boolean = text.lineSequence().any { lineStamp.matchesAt(it.trimStart(), 0) }

    /** [lrc] as TTML, or null when it has no timed lines. */
    fun toTtml(lrc: String): String? {
        val offset = lrc.lineSequence().mapNotNull { tag.find(it.trim()) }
            .firstOrNull { it.groupValues[1].equals("offset", ignoreCase = true) }
            ?.groupValues?.get(2)?.trim()?.toLongOrNull() ?: 0L
        // A positive offset shows the lyrics earlier.
        fun time(match: MatchResult) = (stampMs(match) - offset).coerceAtLeast(0L)

        val raws = lrc.lineSequence().flatMap { line ->
            val trimmed = line.trim()
            var rest = trimmed
            val starts = mutableListOf<Long>()
            while (true) {
                val match = lineStamp.matchAt(rest, 0) ?: break
                starts += time(match)
                rest = rest.substring(match.range.last + 1)
            }
            starts.asSequence().map { Raw(it, rest) }
        }.sortedBy { it.start }.toList()
        if (raws.none { it.body.isNotBlank() }) return null

        val wordTimed = raws.any { wordStamp.containsMatchIn(it.body) }
        val paragraphs = StringBuilder()
        raws.forEachIndexed { index, raw ->
            if (raw.body.replace(wordStamp, "").isBlank()) return@forEachIndexed
            // A line ends where the next one (or a blank stamp) starts.
            val next = raws.getOrNull(index + 1)?.start
            if (wordTimed) {
                val words = words(raw, next, ::time)
                if (words.isEmpty()) return@forEachIndexed
                val end = words.last().end!!
                paragraphs.append("""<p begin="${seconds(raw.start)}" end="${seconds(end)}" ttm:agent="v1">""")
                words.forEachIndexed { i, word ->
                    if (i > 0 && word.spaceBefore) paragraphs.append(' ')
                    paragraphs.append("""<span begin="${seconds(word.start)}" end="${seconds(word.end!!)}">${escape(word.text)}</span>""")
                }
                paragraphs.append("</p>")
            } else {
                val end = (next ?: (raw.start + LAST_LINE_MS)).coerceAtLeast(raw.start + 1)
                paragraphs.append("""<p begin="${seconds(raw.start)}" end="${seconds(end)}" ttm:agent="v1">${escape(raw.body.trim())}</p>""")
            }
        }
        if (paragraphs.isEmpty()) return null
        return """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" """ +
            """xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="${if (wordTimed) "Word" else "Line"}">""" +
            """<head><metadata><ttm:agent type="person" xml:id="v1"/></metadata></head><body><div>$paragraphs</div></body></tt>"""
    }

    private class Piece(val text: String, val start: Long, val end: Long?, val spaceBefore: Boolean)

    /**
     * The line's pieces: each runs from its stamp to the next stamp. A last piece with no closing
     * stamp runs to the next line, for at most [LAST_WORD_MS].
     */
    private fun words(raw: Raw, next: Long?, time: (MatchResult) -> Long): List<Piece> {
        // (text before the stamp, the stamp) pairs, then the text after the last stamp.
        val stamps = wordStamp.findAll(raw.body).toList()
        val texts = buildList {
            var cursor = 0
            stamps.forEach { add(raw.body.substring(cursor, it.range.first)); cursor = it.range.last + 1 }
            add(raw.body.substring(cursor))
        }
        val pieces = mutableListOf<Piece>()
        var spaceBefore = false
        texts.forEachIndexed { i, text ->
            val start = if (i == 0) raw.start else time(stamps[i - 1])
            if (text.isBlank()) {
                if (text.isNotEmpty()) spaceBefore = true
                return@forEachIndexed
            }
            val end = stamps.getOrNull(i)?.let(time)
            pieces += Piece(text.trim(), start, end, spaceBefore || text.first().isWhitespace())
            spaceBefore = text.last().isWhitespace()
        }
        return pieces.map { piece ->
            val end = piece.end ?: minOf(next ?: Long.MAX_VALUE, piece.start + LAST_WORD_MS)
            Piece(piece.text, piece.start, end.coerceAtLeast(piece.start + 1), piece.spaceBefore)
        }
    }

    private fun stampMs(match: MatchResult): Long {
        val minutes = match.groupValues[1].toLong()
        val seconds = match.groupValues[2].toLong()
        val fraction = match.groupValues[3].let { if (it.isBlank()) 0L else it.padEnd(3, '0').take(3).toLong() }
        return (minutes * 60 + seconds) * 1_000 + fraction
    }

    private fun seconds(ms: Long) = String.format(Locale.ROOT, "%d.%03d", ms / 1_000, ms % 1_000)

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private class Raw(val start: Long, val body: String)

    private const val LAST_LINE_MS = 5_000L
    private const val LAST_WORD_MS = 3_000L
}
