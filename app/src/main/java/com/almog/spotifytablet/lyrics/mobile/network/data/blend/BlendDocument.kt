package com.almog.spotifytablet.lyrics.mobile.network.data.blend

import com.almog.spotifytablet.lyrics.mobile.models.LineRole
import com.almog.spotifytablet.lyrics.mobile.models.LyricsType
import com.almog.spotifytablet.lyrics.mobile.parser.TtmlLyricsParser
import com.almog.spotifytablet.lyrics.mobile.network.data.RemoteLyricsPayload
import java.util.Locale

/*
 * The shape blends work in: the Spicy Lyrics API's JSON document (Content -> Lead/Background ->
 * Syllables), in seconds. Lines and groups are compared by identity, so they are plain classes
 * rather than data classes.
 */

/** One timed syllable. [partOfWord]: it runs on into the next one. */
internal data class BlendSyllable(
    val text: String,
    val start: Double,
    val end: Double,
    val partOfWord: Boolean,
    /** An onset nobody measured: shared out of a lump by character count. */
    val guess: Boolean = false,
)

/** A lead or background vocal group. */
internal class BlendGroup(val syllables: List<BlendSyllable>, val start: Double?, val end: Double?)

internal class BlendLine(
    /** The line's own text, or null for one spelled by its lead syllables. */
    var text: String?,
    var start: Double?,
    var end: Double?,
    var lead: BlendGroup? = null,
    var background: List<BlendGroup> = emptyList(),
    val agent: String? = null,
    val oppositeAligned: Boolean = false,
    /** Cut out of a donor's syllable stream rather than paired with one of its lines. */
    val recut: Boolean = false,
) {
    /** A copy with the vocal parts and times taken off, keeping what identifies the line. */
    fun bare(text: String? = this.text) = BlendLine(text, null, null, agent = agent, oppositeAligned = oppositeAligned)
}

internal class BlendDoc(val lines: List<BlendLine>, val songwriters: List<String> = emptyList())

internal enum class BlendQuality(val rank: Int) { NONE(0), STATIC(1), LINE(2), SYLLABLE(3) }

internal object BlendDocuments {
    private val lrcStamp = Regex("\\[(\\d+):(\\d+(?:[.:]\\d+)?)]")

    /** A payload as a document, from its best representation (the order measuredQuality reads). */
    fun from(payload: RemoteLyricsPayload): BlendDoc? {
        payload.ttmlLyrics?.takeIf(String::isNotBlank)?.let(::fromTtml)?.let { return it }
        payload.syncedLyrics?.takeIf(String::isNotBlank)?.let { lrc -> fromLrc(lrc, payload.plainLyrics)?.let { return it } }
        return payload.plainLyrics?.let(::fromPlain)
    }

    fun fromTtml(ttml: String): BlendDoc? {
        val parsed = TtmlLyricsParser.parse(ttml.byteInputStream())
        if (parsed.lines.isEmpty()) return null
        val lines = mutableListOf<BlendLine>()
        when (parsed.type) {
            LyricsType.Static -> parsed.lines.forEach { line ->
                lines += BlendLine(line.words.joinToString(" ") { it.text }, null, null)
            }
            LyricsType.Line -> parsed.lines.forEach { line ->
                lines += BlendLine(line.words.joinToString(" ") { it.text }, sec(line.startMs), sec(line.endMs),
                    agent = line.agent, oppositeAligned = line.oppositeAligned)
            }
            LyricsType.Syllable -> {
                var current: BlendLine? = null
                for (line in parsed.lines) {
                    val syllables = line.words.mapIndexed { i, word ->
                        // Our words mark the one glued to the previous; Spicy Lyrics marks the one that runs on.
                        BlendSyllable(word.text, sec(word.startMs), sec(word.endMs),
                            partOfWord = line.words.getOrNull(i + 1)?.isPartOfWord == true)
                    }.filter { it.text.isNotEmpty() }
                    val host = current
                    if (line.role == LineRole.BACKGROUND && host != null) {
                        if (syllables.isEmpty()) continue
                        host.background = host.background + BlendGroup(syllables, sec(line.startMs), sec(line.endMs))
                        // The parser widens a lead's window over its backing vocals; the lead's own
                        // words are where the line really starts and ends.
                        host.lead?.let { lead ->
                            host.start = lead.start
                            host.end = lead.end
                        }
                        continue
                    }
                    val start = sec(line.startMs)
                    val end = sec(line.endMs)
                    val lead = syllables.takeIf { it.isNotEmpty() }?.let {
                        BlendGroup(it, it.first().start, it.maxOf(BlendSyllable::end))
                    }
                    current = BlendLine(null, start, end, lead, agent = line.agent, oppositeAligned = line.oppositeAligned)
                    lines += current
                }
            }
        }
        return BlendDoc(lines, parsed.songwriters).takeIf { lines.isNotEmpty() }
    }

    /** Reads LRC: each line ends at the next, held no longer than ten seconds. */
    fun fromLrc(text: String, plain: String? = null): BlendDoc? {
        val rows = mutableListOf<Pair<Double, String>>()
        for (raw in text.lines()) {
            val stamps = lrcStamp.findAll(raw).toList()
            if (stamps.isEmpty()) continue
            val body = raw.substring(stamps.last().range.last + 1).trim()
            for (m in stamps) {
                val seconds = m.groupValues[1].toInt() * 60 + (m.groupValues[2].replace(':', '.').toDoubleOrNull() ?: continue)
                rows += seconds to body
            }
        }
        rows.sortBy { it.first }
        if (rows.map { it.first }.toSet().size < 2) {
            return fromPlain(plain?.takeIf(String::isNotBlank) ?: rows.joinToString("\n") { it.second })
        }
        val lines = rows.mapIndexedNotNull { i, (t, body) ->
            if (body.isEmpty()) return@mapIndexedNotNull null
            val next = rows.getOrNull(i + 1)?.first
            BlendLine(body, t, if (next != null) minOf(next, t + 10.0) else t + 6.0)
        }
        return BlendDoc(lines).takeIf { lines.isNotEmpty() } ?: plain?.let(::fromPlain)
    }

    fun fromPlain(text: String): BlendDoc? =
        text.lines().map(String::trim).filter(String::isNotEmpty).map { BlendLine(it, null, null) }
            .takeIf { it.isNotEmpty() }?.let(::BlendDoc)

    /** The document as TTML our parser reads back into the same lines, groups and syllables. */
    fun toTtml(doc: BlendDoc, quality: BlendQuality): String {
        val timing = when (quality) {
            BlendQuality.SYLLABLE -> "word"
            BlendQuality.LINE -> "line"
            else -> "none"
        }
        val times = if (quality == BlendQuality.STATIC || quality == BlendQuality.NONE) null else fillTimes(doc.lines)
        val out = StringBuilder()
        out.append("<tt xmlns=\"http://www.w3.org/ns/ttml\" xmlns:ttm=\"http://www.w3.org/ns/ttml#metadata\" ")
            .append("xmlns:itunes=\"http://music.apple.com/lyric-ttml-internal\" ")
            .append("xmlns:spicy=\"https://spicylyrics.org\" itunes:timing=\"").append(timing).append("\">")
        if (doc.songwriters.isNotEmpty()) {
            out.append("<head><metadata><iTunesMetadata xmlns=\"http://music.apple.com/lyric-ttml-internal\"><songwriters>")
            doc.songwriters.forEach { out.append("<songwriter>").append(xml(it)).append("</songwriter>") }
            out.append("</songwriters></iTunesMetadata></metadata></head>")
        }
        out.append("<body><div>")
        doc.lines.forEachIndexed { i, line ->
            out.append("<p")
            times?.get(i)?.let { (start, end) -> out.append(" begin=\"").append(t(start)).append("\" end=\"").append(t(end)).append('"') }
            line.agent?.let { out.append(" ttm:agent=\"").append(xml(it)).append('"') }
            if (line.oppositeAligned) out.append(" spicy:oppositeAligned=\"true\"")
            out.append('>')
            val lead = line.lead?.syllables.orEmpty()
            // A line with no syllables keeps its text whole, as one line-timed syllable.
            if (quality == BlendQuality.SYLLABLE && lead.isNotEmpty()) appendSyllables(out, lead)
            else out.append(xml(BlendText.lineText(line)))
            if (quality == BlendQuality.SYLLABLE) {
                for (group in line.background) {
                    if (group.syllables.isEmpty()) continue
                    out.append("<span ttm:role=\"x-bg\">")
                    appendSyllables(out, group.syllables)
                    out.append("</span>")
                }
            }
            out.append("</p>")
        }
        out.append("</div></body></tt>")
        return out.toString()
    }

    private fun appendSyllables(out: StringBuilder, syllables: List<BlendSyllable>) {
        syllables.forEachIndexed { i, s ->
            out.append("<span begin=\"").append(t(s.start)).append("\" end=\"").append(t(maxOf(s.end, s.start)))
                .append("\">").append(xml(s.text)).append("</span>")
            if (i < syllables.lastIndex && !s.partOfWord) out.append(' ')
        }
    }

    /**
     * Every line's (start, end), untimed ones placed in the gap their neighbours leave. A blend can
     * leave a line nobody could place; drawn at 0:00 it would sit lit through the whole intro.
     */
    private fun fillTimes(lines: List<BlendLine>): List<Pair<Double, Double>> {
        val starts = lines.map { BlendText.lineStart(it) }
        val ends = lines.map { BlendText.lineEnd(it) }
        return lines.indices.map { i ->
            val s = starts[i]
            if (s != null) return@map s to maxOf(ends[i] ?: s, s)
            val before = (i - 1 downTo 0).firstNotNullOfOrNull { ends[it] ?: starts[it] } ?: 0.0
            val after = (i + 1 until lines.size).firstNotNullOfOrNull { starts[it] } ?: (before + 4.0)
            before to maxOf(after, before)
        }
    }

    private fun sec(ms: Long) = ms / 1000.0
    private fun t(seconds: Double) = "%.3fs".format(Locale.ROOT, seconds.coerceAtLeast(0.0))
    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
