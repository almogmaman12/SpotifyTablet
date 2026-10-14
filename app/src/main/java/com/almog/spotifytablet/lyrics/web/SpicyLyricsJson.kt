package com.almog.spotifytablet.lyrics.web

import com.almog.spotifytablet.lyrics.model.LyricLine
import com.almog.spotifytablet.lyrics.model.LyricTrack
import com.almog.spotifytablet.lyrics.model.WordSync

/**
 * Serialises a [LyricTrack] into the JSON the bundled Spicy renderer (assets/spicy/spicy.js) reads.
 *
 * Shape: `{ "type": "Syllable" | "Line", "lines": [ { start, end, text, opposite, words: [{ t, s, e, part }], bg: [...] } ] }`
 * with all times in milliseconds. Written by hand (no org.json) so it also works in plain JVM tests.
 */
object SpicyLyricsJson {

    /** Word-synced lyrics are rendered per word/letter; line-synced ones as whole lines. */
    fun isSyllableSynced(track: LyricTrack): Boolean = track.lines.any { it.isWordSynced }

    /**
     * Background vocals belong to the lead line they overlap (Spicy draws them under it). A background
     * line that overlaps nothing is promoted to a normal line so it is never dropped.
     */
    internal fun groupBackground(lines: List<LyricLine>): List<Pair<LyricLine, List<LyricLine>>> {
        // Sources emit each lead followed by its own background lines, so attach by list order
        // (not by time overlap, which pairs echoes with the wrong neighbouring lead).
        val groups = ArrayList<Pair<LyricLine, MutableList<LyricLine>>>()
        for (line in lines) {
            if (!line.isBackground || groups.isEmpty()) {
                groups.add(line to ArrayList())
            } else {
                groups.last().second.add(line)
            }
        }
        return groups.sortedBy { it.first.startTimeMs }.map { it.first to it.second.toList() }
    }

    fun toJson(track: LyricTrack): String {
        val syllable = isSyllableSynced(track)
        val sb = StringBuilder(track.lines.size * 160)
        sb.append("{\"type\":\"").append(if (syllable) "Syllable" else "Line").append("\",\"lines\":[")
        val groups = if (syllable) {
            groupBackground(track.lines)
        } else {
            track.lines.filterNot { it.isBackground }.sortedBy { it.startTimeMs }.map { it to emptyList<LyricLine>() }
        }
        groups.forEachIndexed { index, (line, bg) ->
            if (index > 0) sb.append(',')
            appendLine(sb, line, bg, syllable)
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun appendLine(sb: StringBuilder, line: LyricLine, bg: List<LyricLine>, withWords: Boolean) {
        sb.append("{\"start\":").append(line.startTimeMs)
            .append(",\"end\":").append(maxOf(line.endTimeMs, line.startTimeMs + 1))
            .append(",\"text\":").append(quote(line.rawText))
            .append(",\"opposite\":").append(line.agentId == "v2")
        if (withWords) {
            sb.append(",\"words\":")
            appendWords(sb, line.words)
            if (bg.isNotEmpty()) {
                sb.append(",\"bg\":[")
                bg.forEachIndexed { i, b ->
                    if (i > 0) sb.append(',')
                    sb.append("{\"start\":").append(b.startTimeMs)
                        .append(",\"end\":").append(maxOf(b.endTimeMs, b.startTimeMs + 1))
                        .append(",\"words\":")
                    appendWords(sb, b.words.ifEmpty { listOf(WordSync(b.rawText, b.startTimeMs, b.endTimeMs, false)) })
                    sb.append('}')
                }
                sb.append(']')
            }
        }
        sb.append('}')
    }

    private fun appendWords(sb: StringBuilder, words: List<WordSync>) {
        sb.append('[')
        words.forEachIndexed { i, w ->
            if (i > 0) sb.append(',')
            // A syllable with no space after it (and that is not the last one) continues the next one.
            val part = !w.trailingSpace && i < words.lastIndex
            sb.append("{\"t\":").append(quote(w.text))
                .append(",\"s\":").append(w.startTimeMs)
                .append(",\"e\":").append(maxOf(w.endTimeMs, w.startTimeMs + 1))
                .append(",\"part\":").append(part).append('}')
        }
        sb.append(']')
    }

    /** JSON string literal; also escapes U+2028/2029 so the result is a valid JavaScript literal too. */
    fun quote(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                ' ' -> sb.append("\\u2028")
                ' ' -> sb.append("\\u2029")
                else -> if (ch < ' ') sb.append(String.format("\\u%04x", ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
