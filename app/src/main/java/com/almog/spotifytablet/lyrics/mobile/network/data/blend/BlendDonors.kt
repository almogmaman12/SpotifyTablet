package com.almog.spotifytablet.lyrics.mobile.network.data.blend

import com.almog.spotifytablet.lyrics.mobile.network.data.blend.BlendText.key
import com.almog.spotifytablet.lyrics.mobile.network.data.blend.BlendText.lineText

/**
 * A QQ Music, Kugou or NetEase document in the shape a blend takes: credit and title-card lines taken out,
 * bracketed backing vocals lifted out of the lead into groups of their own, and lines whose
 * "word timing" is only the line's span divided evenly put back to line level.
 */
internal object BlendDonors {
    private val PAIRS = mapOf('(' to ')', '[' to ']', '（' to '）', '「' to '」', '【' to '】')
    private const val FAKE_EVEN = 0.006
    private const val FAKE_SHOW = 1.2

    private val NE_CREDIT = Regex(
        "^\\s*(?:" +
            "[一-鿿]{0,6}?(?:作词|作曲|编曲|制作|出品|监制|录音|混音|母带|吉他|贝斯" +
            "|鼓|键盘|和声|弦乐|人声|策划|统筹|发行|工程师|演奏|词|曲)[一-鿿]{0,2}?" +
            "|lyric(?:s|ist)?|compos(?:ed|er|ition)|writ(?:ten|er)|music" +
            "|arrang(?:ed|er|ement)|produc(?:ed|er|tion)|mix(?:ed|ing)?|talkbox|rap" +
            "|master(?:ed|ing)?|record(?:ed|ing)?|vocals?|performed|engineer(?:ed)?" +
            "|backing vocals?|guitars?|bass|drums|keyboards?|strings?|piano" +
            ")\\s*(?:by)?\\s*[:：]",
        RegexOption.IGNORE_CASE,
    )
    private val QQ_CREDIT = Regex(
        "^.{0,40}\\bby\\s*[:：]" +
            "|^.{0,30}\\b(?:title|writer|publisher|lyrics?|composer|arranger|producer" +
            "|vocals?|programming|engineer|mix|master)\\s*[:：]",
        RegexOption.IGNORE_CASE,
    )
    private val QRC_TAIL = Regex("^~+\\s*end\\s*~+$", RegexOption.IGNORE_CASE)
    private val NO_WORDS = Regex(
        "纯音乐|純音樂|请欣赏|請欣賞|此歌曲为没有填词|沒有填詞|无歌词|暫無歌詞|暂无歌词|" +
            "^\\W*instrumental\\W*$|^\\W*no lyrics\\W*$",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Whether [lines] are only a note saying the track has no words, like NetEase's and Kugou's
     * "纯音乐，请欣赏" ("instrumental, please enjoy") stamped across the whole song: three lines at
     * most besides credits, one of them saying it.
     */
    fun isNoWordsNote(lines: List<String>): Boolean {
        val words = lines.map(String::trim).filter { it.isNotEmpty() && !isCredit(it) }
        return words.size in 1..3 && NO_WORDS.containsMatchIn(words.joinToString(" "))
    }

    /** A credit line these catalogues write into the lyric: "作词 : ...", "Produced by: ...". */
    fun isCredit(body: String): Boolean =
        NE_CREDIT.containsMatchIn(body) || QQ_CREDIT.containsMatchIn(body) || QRC_TAIL.matches(body)

    fun shape(doc: BlendDoc, title: String, artist: String): BlendDoc? {
        var lines = doc.lines.filterNot { line ->
            val body = lineText(line)
            body.isEmpty() || isCredit(body)
        }
        lines = dropTitleCard(lines, title, artist)
        if (lines.isEmpty() || instrumental(lines)) return null
        return BlendDoc(lines.map(::liftBrackets).map(::unfake), doc.songwriters)
    }

    /** Kugou and QQ open with "artist - title" timed across the intro; nobody sings it. */
    private fun dropTitleCard(lines: List<BlendLine>, title: String, artist: String): List<BlendLine> {
        val t = key(title)
        val a = key(artist)
        var out = lines
        while (out.isNotEmpty()) {
            val text = lineText(out.first())
            val k = key(text)
            val card = k.isNotEmpty() && (k == a + t || k == t + a ||
                (t.isNotEmpty() && " - " in text && k.contains(t) && (a.isEmpty() || k.contains(a.take(4)))))
            if (!card) break
            out = out.drop(1)
        }
        return out
    }

    private fun instrumental(lines: List<BlendLine>): Boolean {
        if (lines.size > 3) return false
        val text = lines.joinToString(" ") { lineText(it) }.trim()
        return text.isNotEmpty() && NO_WORDS.containsMatchIn(text)
    }

    private fun liftBrackets(line: BlendLine): BlendLine {
        val syls = line.lead?.syllables.orEmpty()
        if (syls.isEmpty() || line.background.isNotEmpty()) return line
        var (lead, groups) = splitBackground(syls)
        if (lead.isEmpty()) { lead = syls; groups = emptyList() }
        if (groups.isEmpty() && lead == syls) return line
        lead = lead.dropLast(1) + lead.last().copy(partOfWord = false)
        val end = (listOfNotNull(line.end, lead.last().end) + groups.mapNotNull { it.end }).max()
        return BlendLine(null, line.start, end, BlendGroup(lead, line.lead?.start, line.lead?.end), groups,
            line.agent, line.oppositeAligned)
    }

    /** A line's syllables split into the lead and its bracketed backing vocals (`_ne_bg`). */
    fun splitBackground(syllables: List<BlendSyllable>): Pair<List<BlendSyllable>, List<BlendGroup>> {
        val lead = mutableListOf<BlendSyllable>()
        val groups = mutableListOf<List<BlendSyllable>>()
        var cur: MutableList<BlendSyllable>? = null
        var want: Char? = null
        var opened = ""
        val rest = ArrayDeque(syllables)
        while (rest.isNotEmpty()) {
            val y = rest.removeFirst()
            val text = y.text
            val open = cur
            if (open == null) {
                val at = text.indexOfFirst { it in PAIRS }
                if (at < 0) { lead += y; continue }
                val (before, after) = cut(y, at)
                if (before != null && before.text.isNotBlank()) lead += before
                val start = after!!
                opened = start.text[0].toString()
                want = PAIRS.getValue(start.text[0])
                cur = mutableListOf()
                cut(start, 1).second?.let { rest.addFirst(it) }
                continue
            }
            val at = text.indexOf(want!!)
            if (at < 0) { if (text.isNotEmpty()) open += y; continue }
            val (before, after) = cut(y, at)
            if (before != null && before.text.isNotBlank()) open += before
            if (open.isNotEmpty()) groups += open.toList()
            cur = null
            want = null
            after?.let { cut(it, 1).second }?.let { rest.addFirst(it) }
        }
        cur?.takeIf { it.isNotEmpty() }?.let { left ->
            left[0] = left[0].copy(text = opened + left[0].text)
            lead += left
        }
        val out = groups.mapNotNull { g ->
            val kept = despace(g).filter { it.text.isNotBlank() }.toMutableList()
            if (kept.isEmpty()) return@mapNotNull null
            kept[0] = kept[0].copy(text = capitalised(kept[0].text))
            kept[kept.lastIndex] = kept.last().copy(partOfWord = false)
            BlendGroup(kept, kept.first().start, kept.maxOf(BlendSyllable::end))
        }
        return despace(lead) to out
    }

    /** One syllable split in two at a character, sharing its time by length (`_cut`). */
    private fun cut(y: BlendSyllable, at: Int): Pair<BlendSyllable?, BlendSyllable?> {
        if (at <= 0) return null to y
        if (at >= y.text.length) return y to null
        val mid = y.start + (y.end - y.start) * (at.toDouble() / y.text.length)
        return y.copy(text = y.text.substring(0, at), end = mid, partOfWord = false) to
            y.copy(text = y.text.substring(at), start = mid)
    }

    /** Close the gap a lifted bracket leaves behind (`_despace`). */
    private fun despace(rows: List<BlendSyllable>): List<BlendSyllable> {
        val out = mutableListOf<BlendSyllable>()
        for (y in rows) {
            var text = y.text
            if (out.isNotEmpty() && text.firstOrNull()?.isWhitespace() == true && out.last().text.lastOrNull()?.isWhitespace() == true) {
                text = text.trimStart()
                if (text.isEmpty()) continue
            }
            out += if (text == y.text) y else y.copy(text = text)
        }
        return out
    }

    private fun capitalised(text: String): String {
        val i = text.indexOfFirst(Char::isLetter)
        return if (i < 0) text else text.substring(0, i) + text[i].uppercaseChar() + text.substring(i + 1)
    }

    /** A line whose syllables are its span divided by its word count, put back to line level. */
    private fun unfake(line: BlendLine): BlendLine {
        val syls = line.lead?.syllables.orEmpty()
        if (line.background.isNotEmpty() || syls.size < 2) return line
        val spans = syls.map { it.end - it.start }
        if (spans.max() - spans.min() >= FAKE_EVEN || spans.min() <= FAKE_SHOW) return line
        return BlendLine(lineText(line), line.start ?: line.lead?.start, line.end ?: line.lead?.end,
            agent = line.agent, oppositeAligned = line.oppositeAligned)
    }
}
