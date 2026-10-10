package com.almog.spotifytablet.lyrics.mobile.romanization

import com.almog.spotifytablet.lyrics.mobile.network.data.blend.SequenceMatcher
import java.text.Normalizer

/**
 * Lays a human-written romanization (Genius's "Romanized" pages) over timed lyrics: clean the
 * lines, align them, split merged ones, rebalance, and retime.
 *
 * People carry the readings no romanizer can infer (運命 sung "sadame"). The hard part is lining
 * them up: Genius has section headers, repeats or skips choruses, and breaks lines for the page,
 * not the vocal. So the two are aligned in order, through our own machine romanization as the
 * bridge, and only confident matches are kept; a wrong match puts the wrong words on a line,
 * which is worse than leaving the machine's reading there.
 */
object HumanRomanization {

    /**
     * Per line of [ours] (each a list of syllables' machine romanizations), the human
     * romanization cut to its syllables, or null where no Genius line matched well enough.
     */
    fun apply(ours: List<List<String>>, genius: List<String>, background: List<Boolean> = ours.map { false }, groups: List<Int?> = ours.map { null }): List<List<String>?> {
        // Genius writes background vocals in brackets inside their lead line, so a lead line and
        // its background lines are matched as one: "lead (background)".
        val units = mutableListOf<Pair<Int, List<Int>>>()
        val claimed = mutableSetOf<Int>()
        ours.indices.forEach { i ->
            if (background[i]) return@forEach
            val bg = groups[i]?.let { g -> ours.indices.filter { it != i && background[it] && groups[it] == g } }.orEmpty()
            claimed += bg
            units += i to bg
        }
        ours.indices.filter { background[it] && it !in claimed }.forEach { units += it to emptyList() }
        units.sortBy { it.first }
        val texts = units.map { (lead, bg) ->
            (listOf(ours[lead].joinToString(" ")) + bg.map { "(" + ours[it].joinToString(" ") + ")" }).joinToString(" ")
        }
        val mapping = align(texts, genius)
        val out = MutableList<List<String>?>(ours.size) { null }
        units.forEachIndexed { u, (lead, bg) ->
            val text = mapping[u] ?: return@forEachIndexed
            if (bg.isEmpty()) {
                out[lead] = retime(ours[lead], text)
                return@forEachIndexed
            }
            val bracketed = BRACKETED.findAll(text).map { it.groupValues[1].trim() }.filter(String::isNotEmpty).toList()
            val main = text.replace(BRACKETED, " ").replace(Regex("\\s+"), " ").trim()
            if (main.isNotEmpty()) out[lead] = retime(ours[lead], main)
            // The bracketed words go to the background lines, one run each while they last,
            // then the rest together on the last; only where they read like that line.
            bg.forEachIndexed { k, line ->
                val part = if (k < bg.lastIndex) bracketed.getOrNull(k) else bracketed.drop(k).joinToString(" ").ifEmpty { null }
                if (part != null && similar(ours[line].joinToString(" "), part) >= BACKGROUND_MIN) out[line] = retime(ours[line], part)
            }
        }
        return out
    }

    /** Lyric lines only: no [Verse 1] headers, blanks, "(x2)" notes or page boilerplate. */
    fun cleanLines(text: String): List<String> {
        val out = mutableListOf<String>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (out.isEmpty() && BOILER_FIRST.containsMatchIn(line.replace(TAG, ""))) continue
            if (line.isEmpty() || line.startsWith("[") || annotation(line)) continue
            if (EMBED_LINE.matches(line)) continue
            val kept = line.replace(EMBED_TAIL, "").trim()
            if (kept.isNotEmpty()) out += kept
        }
        return out
    }

    /**
     * Our line indexes onto Genius lines, in order (a monotonic alignment, so line 3 can never
     * pair with line 40). One of ours may take a run of up to [maxJoin] consecutive Genius lines,
     * since Genius often prints one sung line as two. Weak pairings are dropped, not forced.
     */
    internal fun align(ours: List<String>, theirs: List<String>, minScore: Double = 0.55, maxJoin: Int = 4): Map<Int, String> {
        val n = ours.size
        val m = theirs.size
        if (n == 0 || m == 0) return emptyMap()
        val kt = theirs.map(::key)
        val score = Array(n + 1) { DoubleArray(m + 1) }
        // move: 1 take a run, 2 drop ours, 3 drop theirs.
        val move = Array(n + 1) { IntArray(m + 1) { 2 } }
        val run = Array(n + 1) { IntArray(m + 1) }
        for (i in 1..n) {
            val ko = key(ours[i - 1])
            val lb = ko.length
            for (j in 1..m) {
                val dropA = score[i - 1][j]
                val dropB = score[i][j - 1]
                var best: Double
                var mv: Int
                var rn = 0
                if (dropA >= dropB) { best = dropA; mv = 2 } else { best = dropB; mv = 3 }
                var joined = ""
                for (k in 1..minOf(maxJoin, j)) {
                    joined = kt[j - k] + joined
                    if (joined.isEmpty() || lb == 0) continue
                    val la = joined.length
                    if (la > lb && 2 * lb < minScore * (la + lb)) break
                    // real_quick_ratio, then quick_ratio, then ratio, like difflib's cheap-first checks.
                    if (2.0 * minOf(la, lb) / (la + lb) < minScore) continue
                    val sm = SequenceMatcher.of(joined, ko)
                    if (sm.quickRatio() < minScore) continue
                    val s = sm.ratio()
                    if (s < minScore) continue
                    val take = score[i - 1][j - k] + (s - minScore)
                    if (take > best) { best = take; mv = 1; rn = k }
                }
                score[i][j] = best
                move[i][j] = mv
                run[i][j] = rn
            }
        }
        val out = HashMap<Int, String>()
        var i = n
        var j = m
        while (i > 0 && j > 0) {
            when (move[i][j]) {
                1 -> {
                    val r = run[i][j]
                    out[i - 1] = theirs.subList(j - r, j).joinToString(" ")
                    i -= 1
                    j -= r
                }
                2 -> i -= 1
                else -> j -= 1
            }
        }
        return rebalance(unmerge(out, ours, minScore), ours)
    }

    /**
     * Slides the boundary between two neighbouring matched lines to where it reads best: a run
     * taken for one line can end with words that belong to the next, or the mirror.
     */
    private fun rebalance(mapping: MutableMap<Int, String>, ours: List<String>): MutableMap<Int, String> {
        val idx = mapping.keys.sorted()
        for ((a, b) in idx.zipWithNext()) {
            if ((a + 1 until b).any { x -> ours[x].isNotBlank() && x !in mapping }) continue
            val left = words(mapping.getValue(a))
            val right = words(mapping.getValue(b))
            val all = left + right
            if (all.size < 2) continue
            var bestScore = similar(ours[a], mapping.getValue(a)) + similar(ours[b], mapping.getValue(b))
            var bestCut = left.size
            for (cut in 1 until all.size) {
                if (cut == left.size) continue
                val s = similar(ours[a], all.subList(0, cut).joinToString(" ")) +
                    similar(ours[b], all.subList(cut, all.size).joinToString(" "))
                if (s > bestScore) { bestScore = s; bestCut = cut }
            }
            if (bestCut != left.size) {
                mapping[a] = all.subList(0, bestCut).joinToString(" ")
                mapping[b] = all.subList(bestCut, all.size).joinToString(" ")
            }
        }
        return mapping
    }

    /**
     * The opposite mistake: one Genius line covering several of ours ("Kyou nani tabeta? Suki na
     * hon wa?" for two sung lines). Cut back apart on a word boundary, only where every part
     * stands on its own.
     */
    private fun unmerge(mapping: MutableMap<Int, String>, ours: List<String>, minScore: Double, maxSplit: Int = 3): MutableMap<Int, String> {
        val base = HashMap(mapping)
        for (i in base.keys.sorted()) {
            val w = words(base.getValue(i))
            if (w.size < 2) continue
            fun free(x: Int) = x in ours.indices && x !in mapping && ours[x].isNotBlank()
            val spans = mutableListOf<List<Int>>()
            for (lo in (i - maxSplit + 1)..i) {
                for (size in 2..maxSplit) {
                    val rows = (lo until lo + size).toList()
                    if (i in rows && rows.all { r -> r == i || free(r) }) spans += rows
                }
            }
            var best = similar(ours[i], base.getValue(i))
            var cuts: List<String>? = null
            var where: List<Int>? = null
            for (rows in spans) {
                for (split in cutSets(w.size, rows.size)) {
                    val bounds = listOf(0) + split + w.size
                    val segs = bounds.zipWithNext { a, b -> w.subList(a, b).joinToString(" ") }
                    val scores = rows.zip(segs) { r, seg -> similar(ours[r], seg) }
                    if (scores.min() < minScore) continue
                    val total = scores.sum() / scores.size
                    if (total > best) { best = total; cuts = segs; where = rows }
                }
            }
            if (cuts != null && where != null) where.zip(cuts).forEach { (r, seg) -> mapping[r] = seg }
        }
        return mapping
    }

    /** Every way to cut [nWords] into [parts] non-empty runs, in order, as cut positions. */
    private fun cutSets(nWords: Int, parts: Int): Sequence<List<Int>> = sequence {
        if (parts <= 1 || nWords < parts) return@sequence
        if (parts == 2) {
            for (a in 1 until nWords) yield(listOf(a))
            return@sequence
        }
        for (a in 1 until nWords - parts + 2) {
            for (rest in cutSets(nWords - a, parts - 1)) yield(listOf(a) + rest.map { a + it })
        }
    }

    /**
     * One human-romanized line cut across the line's syllables, letter by letter: Genius writes
     * 響いている as one word, "hibiiteiru", where the lyrics time 響い / て / いる apart. Each letter
     * is matched to the machine romanization of a syllable ([base]); a Genius word is cut where
     * its letters change syllable, so every piece fills on its own syllable's clock. Returns one
     * string per syllable ("" for a syllable no letter landed on).
     */
    internal fun retime(base: List<String>, text: String): List<String> {
        if (base.isEmpty()) return emptyList()
        // Nothing to cut by: the whole text goes on the line's first syllable.
        val whole = listOf(text.trim()) + List(base.size - 1) { "" }
        val words = words(text)
        if (words.isEmpty()) return whole
        val theirs = StringBuilder()
        val gWord = mutableListOf<Int>()
        val gChar = mutableListOf<Int>()
        words.forEachIndexed { w, word ->
            val (k, idx) = keyMap(word)
            theirs.append(k)
            repeat(k.length) { gWord += w }
            gChar += idx
        }
        val mine = StringBuilder()
        val syl = mutableListOf<Int>()
        base.forEachIndexed { i, b ->
            val k = key(b)
            mine.append(k)
            repeat(k.length) { syl += i }
        }
        if (theirs.isEmpty() || mine.isEmpty()) return whole

        val at = arrayOfNulls<Int>(theirs.length)
        for (block in SequenceMatcher.of(theirs, mine).matchingBlocks()) {
            for (d in 0 until block.size) at[block.a + d] = block.b + d
        }
        val owner = arrayOfNulls<Int>(theirs.length)
        for (c in owner.indices) owner[c] = at[c]?.let { syl[it] }
        var last: Int? = null
        for (c in owner.indices) { if (owner[c] == null) owner[c] = last else last = owner[c] }
        var next: Int? = null
        for (c in owner.indices.reversed()) { if (owner[c] == null) owner[c] = next else next = owner[c] }

        val out = List(base.size) { StringBuilder() }
        val lastWordIn = IntArray(base.size) { -1 }
        var c = 0
        words.forEachIndexed { w, word ->
            val n = keyMap(word).first.length
            if (n == 0) {
                // Punctuation alone: it rides with the syllable the previous letter went to.
                val target = (if (c > 0) owner[c - 1] else owner.firstOrNull()) ?: 0
                put(out, lastWordIn, target, w, word)
                return@forEachIndexed
            }
            val starts = mutableListOf(0)
            for (d in 1 until n) if (owner[c + d] != owner[c + d - 1]) starts += d
            starts.forEachIndexed { j, d ->
                val lo = if (d != 0) gChar[c + d] else 0
                val hi = if (j + 1 < starts.size) gChar[c + starts[j + 1]] else word.length
                val frag = word.substring(lo, hi)
                if (frag.isNotEmpty()) put(out, lastWordIn, owner[c + d] ?: 0, w, frag)
            }
            c += n
        }
        return out.map { it.toString() }
    }

    /** Adds [frag] (from Genius word [w]) to syllable [target], spaced from another word's piece. */
    private fun put(out: List<StringBuilder>, lastWordIn: IntArray, target: Int, w: Int, frag: String) {
        val sb = out[target]
        if (sb.isNotEmpty() && lastWordIn[target] != w) sb.append(' ')
        sb.append(frag)
        lastWordIn[target] = w
    }

    /** Comparison form: letters only, accents folded, case dropped. */
    internal fun key(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKD).filterNot(::combining).lowercase().filter { it in 'a'..'z' || it in '0'..'9' }

    /** [key], plus where each surviving letter came from in [s]. */
    internal fun keyMap(s: String): Pair<String, List<Int>> {
        val out = StringBuilder()
        val idx = mutableListOf<Int>()
        s.forEachIndexed { i, ch ->
            for (c in Normalizer.normalize(ch.toString(), Normalizer.Form.NFKD)) {
                if (combining(c)) continue
                val l = c.lowercaseChar()
                if (l in 'a'..'z' || l in '0'..'9') {
                    out.append(l)
                    idx += i
                }
            }
        }
        return out.toString() to idx
    }

    internal fun similar(a: String, b: String): Double {
        val ka = key(a)
        val kb = key(b)
        if (ka.isEmpty() || kb.isEmpty()) return 0.0
        return SequenceMatcher.of(ka, kb).ratio()
    }

    private fun combining(c: Char) = Character.getType(c).let {
        it == Character.NON_SPACING_MARK.toInt() || it == Character.ENCLOSING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt()
    }

    private fun words(s: String) = s.trim().split(Regex("\\s+")).filter(String::isNotEmpty)

    /** A bracketed run on its own, short enough to be a note ("(x2)", "(Ooh)") rather than a lyric. */
    private fun annotation(line: String): Boolean = wrapped(line) && line.trim().length <= 12

    /** Is the whole line one bracketed run? */
    private fun wrapped(line: String): Boolean {
        val l = line.trim()
        if (!l.startsWith("(")) return false
        var depth = 0
        l.forEachIndexed { i, ch ->
            if (ch == '(') depth++
            else if (ch == ')') {
                depth--
                if (depth == 0) return i == l.length - 1
            }
        }
        return false
    }

    private val BRACKETED = Regex("\\(([^()]*)\\)")
    private const val BACKGROUND_MIN = 0.5
    private val TAG = Regex("<[^>]+>")
    private val BOILER_FIRST = Regex("^\\s*songtekst\\s+van\\b", RegexOption.IGNORE_CASE)
    private val EMBED_LINE = Regex("\\d+\\s*(embed|contributors?).*", RegexOption.IGNORE_CASE)
    private val EMBED_TAIL = Regex("\\d*embed$", RegexOption.IGNORE_CASE)
}
