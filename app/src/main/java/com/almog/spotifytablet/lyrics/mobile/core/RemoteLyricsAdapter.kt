package com.almog.spotifytablet.lyrics.mobile.core

import com.almog.spotifytablet.lyrics.mobile.canvas.LyricsLayoutCalculator
import com.almog.spotifytablet.lyrics.mobile.parser.TtmlLyricsParser
import com.almog.spotifytablet.lyrics.mobile.romanization.HumanRomanization
import com.almog.spotifytablet.lyrics.mobile.romanization.RomanizationService
import com.almog.spotifytablet.lyrics.mobile.romanization.Script
import com.almog.spotifytablet.lyrics.mobile.romanization.ScriptDetector
import com.almog.spotifytablet.lyrics.mobile.network.data.RemoteLyricsSelection
import com.almog.spotifytablet.lyrics.mobile.network.data.RemoteLyricsQuality
import com.almog.spotifytablet.lyrics.mobile.models.LineRole
import com.almog.spotifytablet.lyrics.mobile.models.LyricsType

/** Keeps the selected provider's best format and provenance through to the renderer. */
internal object RemoteLyricsAdapter {
    private val lrcStamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")

    fun render(selection: RemoteLyricsSelection, durationMs: Long): LyricsState.Ready {
        val payload = selection.payload
        val attribution = payload.attribution
        val parsedTtml = payload.ttmlLyrics?.takeIf(String::isNotBlank)?.let { ttml ->
            TtmlLyricsParser.parse(ttml.byteInputStream())
        }
        val ttmlLines = parsedTtml?.lines?.let { parsedLines ->
            parsedLines.map { line ->
                TimedLine(
                    startMs = line.startMs,
                    endMs = line.endMs,
                    words = line.words.map { word ->
                        TimedWord(word.text, word.startMs, word.endMs, word.isPartOfWord, word.romanizedText)
                    },
                    role = line.role,
                    groupId = line.groupId,
                    agent = line.agent,
                    oppositeAligned = line.oppositeAligned,
                )
            }.takeIf(List<TimedLine>::isNotEmpty)
        }
        // Plain text becomes untimed lines, rendered by the lyrics view's static mode like the
        // reference, so it gets the same layout, romanization and credits as synced lyrics.
        val parsed = (ttmlLines ?: parseLrc(payload.syncedLyrics, durationMs)).ifEmpty { staticLines(payload.plainLyrics) }
        // Word-synced text is already split per syllable; line-timed text can arrive as one
        // word per line, which the layout (it wraps between words only) can never wrap.
        val unromanized = if (selection.quality == RemoteLyricsQuality.WORD_SYNCED) parsed else parsed.map(::wrappable)
        require(unromanized.isNotEmpty()) { "Selected lyrics contain no displayable text" }
        // On-device romanization fills only the words the source left unromanized.
        val computed = RomanizationService.romanize(unromanized.map { line -> line.words.map(TimedWord::text) })
        val lines = unromanized.mapIndexed { l, line ->
            line.copy(words = line.words.mapIndexed { w, word -> word.copy(romanized = word.romanized ?: computed[l][w]) })
        }
        return LyricsState.Ready(
            lines = lines,
            provider = attribution?.providerName ?: selection.source.displayName,
            source = attribution?.originName ?: selection.source.displayName,
            maker = attribution?.maker,
            uploader = attribution?.uploader,
            songwriters = (attribution?.songwriters.orEmpty() + parsedTtml?.songwriters.orEmpty())
                .map { decodeEntities(it).trim() }.filter(String::isNotBlank).distinct(),
            sourceRomanized = unromanized.any { line -> line.words.any { it.romanized != null } },
            lyricsType = when (selection.quality) {
                RemoteLyricsQuality.WORD_SYNCED -> LyricsType.Syllable
                RemoteLyricsQuality.LINE_SYNCED -> LyricsType.Line
                else -> LyricsType.Static
            },
        )
    }

    /**
     * [lyrics] with Genius's human romanization ([genius], its lyric lines) laid over the lines it
     * lines up with. Only lines that were romanized get it; the rest, and lines Genius doesn't
     * match confidently, keep what they had.
     */
    fun withHumanRomanization(lyrics: LyricsState.Ready, genius: List<String>): LyricsState.Ready {
        val cuts = HumanRomanization.apply(
            ours = lyrics.lines.map { line -> line.words.map { it.romanized ?: it.text } },
            genius = genius,
            background = lyrics.lines.map { it.role == LineRole.BACKGROUND },
            groups = lyrics.lines.map { it.groupId },
        )
        return lyrics.copy(lines = lyrics.lines.mapIndexed { i, line ->
            val cut = cuts[i]
            if (cut == null || line.words.none { it.romanized != null }) line
            else line.copy(words = line.words.mapIndexed { w, word -> word.copy(romanized = cut[w]) })
        })
    }

    /** Whether [lyrics] are in a script Genius writes romanizations for (Japanese, Korean, Chinese). */
    fun wantsHumanRomanization(lyrics: LyricsState.Ready): Boolean {
        if (lyrics.sourceRomanized || lyrics.lines.none { line -> line.words.any { it.romanized != null } }) return false
        val scripts = ScriptDetector.detect(lyrics.lines.joinToString("\n") { line -> line.words.joinToString("") { it.text } })
        return scripts.any { it == Script.JAPANESE || it == Script.KOREAN || it == Script.CHINESE }
    }

    /** Some sources escape twice ("Tom &amp;amp; Jerry"), so one XML decode still leaves "&amp;". */
    internal fun decodeEntities(value: String): String = value
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&")

    /**
     * Splits at spaces, and CJK per character (glued), so long line-timed lines can wrap.
     *
     * A source romanization doesn't line up with the original's pieces, so it becomes pieces of
     * its own after them: blank in the original view, while the original's are blank when
     * romanized. The layout gives blank pieces no room.
     */
    internal fun wrappable(line: TimedLine): TimedLine = line.copy(words = line.words.flatMap { word ->
        val pieces = wrapPieces(word.text, word.attached)
        val roman = word.romanized?.let { wrapPieces(it, word.attached) }
        when {
            roman != null && (pieces.size > 1 || roman.size > 1) ->
                pieces.map { (text, attached) -> TimedWord(text, word.startMs, word.endMs, attached, romanized = "") } +
                    roman.map { (text, attached) -> TimedWord("", word.startMs, word.endMs, attached, romanized = text) }
            roman != null || pieces.size <= 1 -> listOf(word)
            else -> pieces.map { (text, attached) -> TimedWord(text, word.startMs, word.endMs, attached) }
        }
    })

    private fun wrapPieces(text: String, attached: Boolean): List<Pair<String, Boolean>> =
        text.split(Regex("\\s+")).filter(String::isNotEmpty).flatMapIndexed { tokenIdx, token ->
            // Cut by index: growing a String a character at a time is quadratic on a long token.
            val runs = mutableListOf<String>()
            var start = 0
            for (i in 1..token.length) {
                if (i == token.length || LyricsLayoutCalculator.isCjk(token[i]) || LyricsLayoutCalculator.isCjk(token[i - 1])) {
                    runs += token.substring(start, i)
                    start = i
                }
            }
            runs.mapIndexed { runIdx, run -> run to if (runIdx > 0) true else tokenIdx == 0 && attached }
        }

    private fun staticLines(plain: String?): List<TimedLine> = plain.orEmpty().lines()
        .map(String::trim).filter(String::isNotEmpty)
        .map { text -> TimedLine(0L, 0L, listOf(TimedWord(text, 0L, 0L, false))) }

    private fun parseLrc(raw: String?, durationMs: Long): List<TimedLine> {
        val entries = raw.orEmpty().lineSequence().flatMap { line ->
            val text = line.replace(lrcStamp, "").trim()
            if (text.isBlank()) emptySequence() else lrcStamp.findAll(line).map { match ->
                val minutes = match.groupValues[1].toLong()
                val seconds = match.groupValues[2].toLong()
                val fraction = match.groupValues[3].let { if (it.isBlank()) 0L else it.padEnd(3, '0').take(3).toLong() }
                ((minutes * 60 + seconds) * 1_000 + fraction) to text
            }
        }.distinct().sortedBy(Pair<Long, String>::first).toList()
        return entries.mapIndexed { index, (start, text) ->
            val end = entries.getOrNull(index + 1)?.first
                ?: durationMs.takeIf { it > start }
                ?: start + 4_000
            TimedLine(start, end.coerceAtLeast(start + 1), listOf(TimedWord(text, start, end, false)))
        }
    }
}
