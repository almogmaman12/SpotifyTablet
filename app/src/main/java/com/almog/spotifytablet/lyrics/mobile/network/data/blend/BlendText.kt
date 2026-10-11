package com.almog.spotifytablet.lyrics.mobile.network.data.blend

import kotlin.math.abs

/**
 * Text and timing primitives shared by the blend: how a line is spelled, compared, and re-cut along somebody else's syllables.
 */
internal object BlendText {
    const val RELAY_LIKE = 0.75
    /** Looser for a line the re-stream handed over, which arrives with less evidence behind it. */
    const val RECUT_LIKE = 0.65
    const val WORDED_SHARE = 0.5
    private const val CONTRACTED = "'’"

    val CRIES = setOf(
        "yeah", "yea", "yah", "yuh", "ye", "oh", "ooh", "ohh", "oo", "ah",
        "ahh", "aah", "uh", "uhh", "huh", "hey", "ay", "ayy", "aye", "woo",
        "whoo", "hoo", "wow", "damn", "god", "lord", "what", "nah", "na",
        "la", "mm", "mmm", "hmm", "hm", "ha", "haha", "hahaha", "go", "come",
        "on", "let's", "lets", "yo", "ey", "eh", "okay", "ok", "mhm", "brr",
        "skrrt", "uh-huh", "woah", "whoa", "baby", "now", "yes", "no", "one",
        "two", "three", "four",
    )
    const val CRY_WORDS = 4
    private val wordSplit = Regex("[^\\p{L}\\p{N}_'’-]+")
    private val lumpParts = Regex("[^\\s​]+[\\s​]*")

    /** A line reduced to its letters and digits, lower-cased: how two lines are compared by text. */
    fun key(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        val out = StringBuilder(s.length)
        for (c in s) if (Character.isLetterOrDigit(c)) out.append(Character.toLowerCase(c))
        return out.toString()
    }

    fun isAlnum(c: Char) = Character.isLetterOrDigit(c)

    /** The words a run of syllables spells; the space between words comes from the flags. */
    fun syllablesText(syllables: List<BlendSyllable>?): String {
        val out = StringBuilder()
        for (s in syllables.orEmpty()) {
            if (s.partOfWord) out.append(s.text) else out.append(s.text.trim()).append(' ')
        }
        return out.toString().trim()
    }

    fun lineText(line: BlendLine?): String =
        line?.text?.trim() ?: syllablesText(line?.lead?.syllables)

    fun lineStart(line: BlendLine?): Double? = line?.start ?: line?.lead?.start

    fun lineEnd(line: BlendLine?): Double? = line?.end ?: line?.lead?.end

    /** Whether a line is nothing but shouting: yeah, ooh, come on, oh God. */
    fun aCry(text: String?): Boolean {
        val words = wordSplit.split(text.orEmpty().lowercase()).filter(String::isNotEmpty).map { it.trim('\'', '’', '-') }
        return words.isNotEmpty() && words.all { it in CRIES }
    }

    fun wordCount(text: String?): Int = wordSplit.split(text.orEmpty()).count(String::isNotEmpty)

    /** Whether two shouts are the same shout however often it is written: "yes" and "yesyes". */
    fun kin(one: String, two: String): Boolean {
        if (one.isEmpty() || two.isEmpty()) return false
        val (small, big) = if (one.length <= two.length) one to two else two to one
        return big.contains(small) && big.replace(small, "").length <= 0.2 * big.length
    }

    fun slide(s: BlendSyllable, by: Double) = s.copy(start = s.start + by, end = s.end + by)

    fun slide(g: BlendGroup, by: Double) =
        BlendGroup(g.syllables.map { slide(it, by) }, g.start?.plus(by), g.end?.plus(by))

    /** What a document actually has, judged by its lines rather than a declared type. */
    fun quality(doc: BlendDoc?): BlendQuality {
        val items = doc?.lines.orEmpty()
        if (items.isEmpty()) return BlendQuality.NONE
        val worded = items.count { !it.lead?.syllables.isNullOrEmpty() }
        return when {
            worded >= WORDED_SHARE * items.size -> BlendQuality.SYLLABLE
            items.any { lineStart(it) != null } -> BlendQuality.LINE
            items.any { lineText(it).isNotBlank() } -> BlendQuality.STATIC
            else -> BlendQuality.NONE
        }
    }

    /**
     * Re-cut [text] along [syllables]' boundaries, keeping our own characters. Only the timing is borrowed: every character on screen still comes from [text].
     * Where the two spell the line differently the letters are aligned and the cuts come across
     * with them; below [floor] similarity nothing is taken.
     */
    fun relay(text: String, syllables: List<BlendSyllable>, floor: Double = RELAY_LIKE): List<BlendSyllable>? {
        val idx = text.indices.filter { isAlnum(text[it]) }
        val spans = syllables.map { it to key(it.text) }.filter { it.second.isNotEmpty() }
        val ours = key(text)
        if (spans.isEmpty() || idx.isEmpty() || ours.length != idx.size) return null
        val theirs = StringBuilder()
        val bounds = spans.map { (_, k) -> theirs.append(k); theirs.length }
        val cuts = if (theirs.toString() == ours) bounds else {
            val breaks = HashSet<Int>().apply {
                add(0); add(ours.length)
                for (p in 1 until ours.length) if (idx[p] - idx[p - 1] > 1) add(p)
            }
            recut(theirs.toString(), ours, bounds, breaks, floor) ?: return null
        }
        val out = mutableListOf<BlendSyllable>()
        var cut = 0
        var held: Double? = null
        for ((span, at) in spans.zip(cuts)) {
            val (s, _) = span
            val stop = if (at < idx.size) idx[at] else text.length
            val piece = if (stop > cut) text.substring(cut, stop) else ""
            if (key(piece).isEmpty()) {
                if (out.isNotEmpty()) out[out.lastIndex] = out.last().copy(end = maxOf(out.last().end, s.end))
                else held = minOf(held ?: s.start, s.start)
                continue
            }
            val body = piece.trimEnd()
            out += BlendSyllable(body, held ?: s.start, s.end, partOfWord = piece == body)
            held = null
            cut = stop
        }
        if (out.isEmpty()) return null
        if (cut < text.length) out[out.lastIndex] = out.last().copy(text = out.last().text + text.substring(cut).trimEnd())
        out[out.lastIndex] = out.last().copy(partOfWord = false)
        return unlump(unsplit(out))
    }

    /** Cut positions in the donor's letters, moved onto ours (`_recut`). */
    private fun recut(theirs: String, ours: String, bounds: List<Int>, breaks: Set<Int>, floor: Double): List<Int>? {
        val sm = SequenceMatcher.of(theirs, ours)
        if (sm.ratio() < floor) return null
        val at = IntArray(theirs.length + 1)
        val lo = IntArray(theirs.length + 1)
        val hi = IntArray(theirs.length + 1)
        var loose: Int? = null
        for (op in sm.opcodes()) {
            val (tag, i1, i2, j1, j2) = op
            if (tag == SequenceMatcher.Tag.EQUAL) {
                for (k in 0 until i2 - i1) { at[i1 + k] = j1 + k; lo[i1 + k] = j1 + k; hi[i1 + k] = j1 + k }
                // Python's `if loose:` — an insertion at position 0 counts as none.
                if (loose != null && loose != 0) lo[i1] = loose
            } else if (i2 > i1) {
                for (k in 0 until i2 - i1) {
                    at[i1 + k] = j1 + (k.toDouble() * (j2 - j1) / (i2 - i1)).roundHalfEven()
                    lo[i1 + k] = j1
                    hi[i1 + k] = j2
                }
            }
            loose = if (tag == SequenceMatcher.Tag.EQUAL) null else j1
        }
        at[theirs.length] = ours.length; lo[theirs.length] = ours.length; hi[theirs.length] = ours.length
        if (loose != null) lo[theirs.length] = loose
        return bounds.map { b ->
            val free = (lo[b]..hi[b]).filter { it in breaks }
            if (free.isEmpty()) at[b]
            else free.minWith(compareBy<Int> { abs(it - at[b]) }.thenByDescending { it })
        }
    }

    /** A word the letter alignment cut at its apostrophe ("It'" + "s"), put back together. */
    private fun unsplit(syllables: List<BlendSyllable>): List<BlendSyllable> {
        val out = mutableListOf<BlendSyllable>()
        for (y in syllables) {
            val was = out.lastOrNull()
            val tail = was?.text?.trimEnd()
            if (was != null && was.partOfWord && (tail.isNullOrEmpty() || tail.last() in CONTRACTED)) {
                out[out.lastIndex] = was.copy(text = was.text + y.text, end = maxOf(was.end, y.end), partOfWord = y.partOfWord)
                continue
            }
            out += y
        }
        return out
    }

    /** One timing covering several words, shared out among them by character count (`_unlump`). */
    fun unlump(syllables: List<BlendSyllable>): List<BlendSyllable> {
        val out = mutableListOf<BlendSyllable>()
        for (y in syllables) {
            val found = lumpParts.findAll(y.text).map { it.value }.toList()
            if (found.size < 2) { out += y; continue }
            val parts = mutableListOf<String>()
            for (piece in found) {
                if (parts.isNotEmpty() && piece.none(::isAlnum)) parts[parts.lastIndex] += piece else parts += piece
            }
            if (parts.size < 2) { out += y; continue }
            val span = maxOf(0.0, y.end - y.start)
            val total = parts.sumOf { it.trim().length }.takeIf { it > 0 } ?: 1
            var at = y.start
            parts.forEachIndexed { k, piece ->
                val body = piece.trimEnd()
                val last = k == parts.lastIndex
                val end = if (last) y.end else at + span * body.length / total
                out += y.copy(
                    text = if (last) body else piece,
                    start = at,
                    end = maxOf(end, at),
                    partOfWord = if (last) y.partOfWord else piece == body,
                    guess = y.guess || k > 0,
                )
                at = end
            }
        }
        return out
    }

    fun unlump(doc: BlendDoc): BlendDoc {
        for (line in doc.lines) {
            line.lead?.let { lead -> line.lead = BlendGroup(unlump(lead.syllables), lead.start, lead.end) }
            line.background = line.background.map { BlendGroup(unlump(it.syllables), it.start, it.end) }
        }
        return doc
    }
}

/** Python's round(): halves go to the even neighbour. */
private fun Double.roundHalfEven(): Int = Math.rint(this).toInt()
