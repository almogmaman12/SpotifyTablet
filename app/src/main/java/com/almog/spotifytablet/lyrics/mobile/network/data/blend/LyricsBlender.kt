package com.almog.spotifytablet.lyrics.mobile.network.data.blend

import com.almog.spotifytablet.lyrics.mobile.network.data.blend.BlendText.RECUT_LIKE
import com.almog.spotifytablet.lyrics.mobile.network.data.blend.BlendText.RELAY_LIKE
import com.almog.spotifytablet.lyrics.mobile.network.data.blend.BlendText.aCry
import com.almog.spotifytablet.lyrics.mobile.network.data.blend.BlendText.key
import com.almog.spotifytablet.lyrics.mobile.network.data.blend.BlendText.kin
import com.almog.spotifytablet.lyrics.mobile.network.data.blend.BlendText.lineEnd
import com.almog.spotifytablet.lyrics.mobile.network.data.blend.BlendText.lineStart
import com.almog.spotifytablet.lyrics.mobile.network.data.blend.BlendText.lineText
import com.almog.spotifytablet.lyrics.mobile.network.data.blend.BlendText.relay
import com.almog.spotifytablet.lyrics.mobile.network.data.blend.BlendText.slide
import com.almog.spotifytablet.lyrics.mobile.network.data.blend.BlendText.syllablesText
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.abs

/** One donor handed to a blend: its document, what to call it, and which source it is. */
internal data class BlendDonor(val doc: BlendDoc?, val name: String, val sourceId: String)

/** What a blend came to. [via] names the parts that were used ("Apple Music + QQ Music"). */
internal data class BlendOutcome(
    val doc: BlendDoc,
    val quality: BlendQuality,
    /** Timings stitched from more than one source; null where one source's document came through. */
    val via: String?,
    /** Where the blend stood down or added nothing: the one source whose document this is. */
    val alone: String?,
)

/**
 * Blends: one document's lines with somebody else's word timing laid under them.
 *
 * The base is the best document ranked above the blend, usually the Spicy Lyrics API's Apple Music copy
 * that came back line-synced. The timing donor (QQ Music, Kugou or NetEase) is paired with it line
 * by line, then as one syllable stream re-cut at the base's line ends, and its syllables are used
 * only as cut points for the base's own words. A second donor, where there is one, answers for
 * the lines the first cannot place or placed badly. Where the blend comes out worse than the donor
 * alone, the donor's document is handed back instead, in the base's wording.
 */
internal object LyricsBlender {
    private const val ASIDE_REACH = 2.0
    private const val BLEND_TAIL = 0.35
    private const val BLEND_HOLD = 0.15
    private const val BLEND_NEAR = 0.35
    private const val BLEND_FAR = 1.5
    private const val BLEND_SAME = 0.55
    private const val BLEND_JUMP = 0.75
    private const val BLEND_LONG = 1.6
    private const val BLEND_STEADY = 0.5
    private const val BLEND_PATCHY = 0.34
    private const val BLEND_BETTER = 0.2
    private const val BLEND_LEAD = 0.05
    private const val BLEND_WOBBLE = 0.05
    private const val BLEND_SHORT = 0.85
    private const val BLEND_SAME_WORDS = 0.85
    private const val BLEND_THIN = 0.35
    private const val LIKE_LEN = 0.65
    private const val FRAGMENT = 0.95
    private const val NEAR_PAIRS = 0.75
    private const val RESTREAM_FLOOR = 0.80
    private const val STRAY_REACH = 0.6
    private const val DOUBLE_SLACK = 0.15
    private const val ASIDE_TRIM = " \t,;:.-\u2014\u2013~(\uff08[\u3010"
    private const val OPENERS = "(\uff08[\u3010"
    private const val CLOSERS = ")\uff09]\u3011"
    private val BRACKETED = Regex("\\s*[(\uff08\\[\u3010]([^)\uff09\\]\u3011]{1,60})[)\uff09\\]\u3011]")
    private val ASIDES = Regex("[(\\[\uff08\u3010]([^)\\]\uff09\u3011]*)[)\\]\uff09\u3011]")
    private val SPACES = Regex("\\s{2,}")

    // --- The entry point (_blended's reconciling, once its documents are in hand) ----------

    /**
     * [base] is the document whose lines are kept, called [words]; [timing] and [spare] are the
     * two donors, in the order the caller ranked them. Returns null where there is nothing to
     * blend at all.
     */
    fun blended(base: BlendDoc, words: String, origin: String, timing: BlendDonor, spare: BlendDonor?): BlendOutcome? {
        val (lead, fill) = inOrder(base, timing, spare ?: BlendDonor(null, "", ""))
        val out = blend(base, words, origin, lead.doc, lead.name, fill.doc, fill.name)
        return standDown(out, lead.doc, base, lead.sourceId)
    }

    /** The blend, or the donor's own document where the blend was not worth it (`stand_down`). */
    private fun standDown(out: BlendOutcome?, donor: BlendDoc?, base: BlendDoc, alone: String): BlendOutcome? {
        if (donor == null) return out
        val outRank = out?.quality?.rank ?: 0
        if (BlendText.quality(donor).rank > outRank || thinner(out?.doc, donor) || shorter(out?.doc, donor)) {
            val reworded = reworded(donor, base)
            return BlendOutcome(reworded, BlendText.quality(reworded), via = null, alone = alone)
        }
        return out
    }

    // --- Pairing two documents' lines ------------------------------------------------------

    /** Whether two line sequences are the same song, measured against the shorter (`_shared`). */
    private fun shared(a: List<String>, b: List<String>, pairs: Map<Int, Int>, floor: Double = BLEND_SAME): Boolean {
        val real = minOf(a.count(String::isNotEmpty), b.count(String::isNotEmpty))
        return real > 0 && pairs.size >= floor * real
    }

    /** base line index -> other's, by text, or null if these are not one song (`_pair`). */
    private fun pair(base: List<BlendLine>, other: List<BlendLine>): MutableMap<Int, Int>? {
        val a = base.map { key(lineText(it)) }
        val b = other.map { key(lineText(it)) }
        val pairs = exactPairs(a, b)
        if (!shared(a, b, pairs)) return null
        pairs.putAll(nearPairs(a, b, pairs))
        return pairs
    }

    private fun exactPairs(a: List<String>, b: List<String>): MutableMap<Int, Int> {
        val pairs = linkedMapOf<Int, Int>()
        for ((i, j, n) in SequenceMatcher.of(a, b).matchingBlocks()) {
            for (k in 0 until n) if (a[i + k].isNotEmpty()) pairs[i + k] = j + k
        }
        return pairs
    }

    /** Whether the shorter of two lines is a piece cut out of the longer, whole (`_fragment`). */
    private fun fragment(one: String, other: String): Boolean {
        val (short, long) = if (one.length <= other.length) one to other else other to one
        if (short.isEmpty()) return false
        return SequenceMatcher.of(short, long).findLongestMatch(0, short.length, 0, long.length).size >= FRAGMENT * short.length
    }

    /** Pairings the exact match missed, taken on similarity inside their neighbours' window. */
    private fun nearPairs(a: List<String>, b: List<String>, mate: Map<Int, Int>, floor: Double = NEAR_PAIRS): Map<Int, Int> {
        val taken = mate.values.toHashSet()
        val anchors = mate.keys.sorted()
        val out = linkedMapOf<Int, Int>()
        for ((i, k) in a.withIndex()) {
            if (i in mate || k.isEmpty()) continue
            val lo = anchors.filter { it < i }.maxOfOrNull { mate.getValue(it) } ?: -1
            val hi = anchors.filter { it > i }.minOfOrNull { mate.getValue(it) } ?: b.size
            var best: Int? = null
            var score = floor
            for (j in lo + 1 until hi) {
                if (j in taken || b[j].isEmpty()) continue
                if (minOf(k.length, b[j].length) < LIKE_LEN * maxOf(k.length, b[j].length)) continue
                if (k.length != b[j].length && fragment(k, b[j])) continue
                val r = SequenceMatcher.of(k, b[j]).ratio()
                if (r > score) { best = j; score = r }
            }
            if (best != null) { out[i] = best; taken += best }
        }
        return out
    }

    /** The ad-libs a donor files beside its lines rather than inside them, by their words. */
    private fun filedApart(lines: List<BlendLine>?): Set<String> =
        lines.orEmpty().flatMap { it.background }.map { key(syllablesText(it.syllables)) }.filter(String::isNotEmpty).toSet()

    /** A line without the ad-libs the donor files apart from its own words (`_unaside`). */
    private fun unaside(text: String, apart: Set<String>): String {
        if (apart.isEmpty()) return text
        return BRACKETED.replace(text) { m ->
            val k = key(m.groupValues[1])
            if (k.isNotEmpty() && apart.any { kin(k, it) }) "" else m.value
        }
    }

    private fun alike(line: BlendLine?) = if (line?.recut == true) RECUT_LIKE else RELAY_LIKE

    /** The donor's syllables re-cut where the BASE breaks its lines (`_restream`). */
    private fun restream(base: List<BlendLine>, donor: List<BlendLine>, floor: Double = RESTREAM_FLOOR): List<BlendLine?>? {
        val syls = donor.flatMap { it.lead?.syllables.orEmpty() }
        if (syls.isEmpty() || base.isEmpty()) return null
        val apart = filedApart(donor)
        val theirs = StringBuilder()
        val owner = mutableListOf<Int>()
        syls.forEachIndexed { i, y -> val k = key(y.text); theirs.append(k); repeat(k.length) { owner += i } }
        val ours = StringBuilder()
        val lineOf = mutableListOf<Int>()
        base.forEachIndexed { i, it -> val k = key(unaside(lineText(it), apart)); ours.append(k); repeat(k.length) { lineOf += i } }
        if (ours.isEmpty() || theirs.isEmpty()) return null
        val sm = SequenceMatcher.of(ours, theirs)
        if (sm.quickRatio() < floor || sm.ratio() < floor) return null
        val span = HashMap<Int, IntArray>()
        for ((i, j, n) in sm.matchingBlocks()) {
            for (k in 0 until n) {
                val li = lineOf[i + k]
                val si = owner[j + k]
                val got = span[li]
                span[li] = if (got == null) intArrayOf(si, si) else intArrayOf(minOf(got[0], si), maxOf(got[1], si))
            }
        }
        var seen = -1
        val out = base.indices.map { i ->
            val got = span[i]
            if (got == null || got[1] <= seen) return@map null
            val take = syls.subList(maxOf(got[0], seen + 1), got[1] + 1)
            if (take.isEmpty()) return@map null
            seen = got[1]
            val end = take.maxOf(BlendSyllable::end)
            BlendLine(syllablesText(take), take[0].start, end, BlendGroup(take, take[0].start, end), recut = true)
        }
        return out.takeIf { lines -> lines.any { it != null } }
    }

    /** Drop pairings whose timing disagrees with their neighbours' (`_timely`). */
    private fun timely(pairs: Map<Int, Int>, bit: List<BlendLine>, dit: List<BlendLine>, tol: Double = BLEND_JUMP): MutableMap<Int, Int> {
        val out = LinkedHashMap(pairs)
        if (pairs.isEmpty()) return out
        val delta = offsetsByLine(bit, dit, pairs)
        val keys = delta.keys.sorted()
        if (keys.size < 4) return out
        for ((pos, i) in keys.withIndex()) {
            val near = keys.subList(maxOf(0, pos - 3), minOf(keys.size, pos + 4)).filter { it != i }.map { delta.getValue(it) }.sorted()
            if (near.isNotEmpty() && abs(delta.getValue(i) - near[near.size / 2]) > tol) out.remove(i)
        }
        return out
    }

    private fun offsetsByLine(bit: List<BlendLine>, dit: List<BlendLine>, map: Map<Int, Int>): Map<Int, Double> {
        val out = HashMap<Int, Double>()
        for ((i, j) in map) {
            if (j !in dit.indices) continue
            val s = lineStart(bit[i]) ?: continue
            val d = lineStart(dit[j]) ?: continue
            out[i] = d - s
        }
        return out
    }

    /** Every paired line's distance from where the base puts it, sorted (`_offsets`). */
    private fun offsets(bit: List<BlendLine>, dit: List<BlendLine>, map: Map<Int, Int>): List<Double> =
        offsetsByLine(bit, dit, map).values.sorted()

    /** (shift, wander) of a donor's whole clock against the base's line sync (`_clock`). */
    private fun clock(bit: List<BlendLine>, doc: BlendDoc?): Pair<Double, Double>? {
        val it = doc?.lines?.toMutableList() ?: return null
        if (bit.isEmpty() || it.isEmpty()) return null
        val map = timely(pair(bit, it) ?: emptyMap(), bit, it)
        if (map.size < bit.size) {
            restream(bit, it)?.forEachIndexed { i, got ->
                if (got != null && i !in map) { it += got; map[i] = it.lastIndex }
            }
        }
        val off = offsets(bit, it, map)
        if (off.size < 6) return null
        val mid = off[off.size / 2]
        val apart = off.map { v -> abs(v - mid) }.sorted()
        return mid to apart[apart.size / 2]
    }

    /** The blend's two donors, the one holding THIS recording first (`in_order`). */
    private fun inOrder(base: BlendDoc, first: BlendDonor, second: BlendDonor): Pair<BlendDonor, BlendDonor> {
        if (second.doc == null || first.doc == null) return first to second
        val bit = base.lines
        val mine = clock(bit, first.doc)
        if (mine == null || abs(mine.first) <= BLEND_LEAD) return first to second
        val theirs = clock(bit, second.doc)
        if (theirs == null || abs(theirs.first) >= abs(mine.first) - BLEND_LEAD) return first to second
        if (theirs.second > mine.second + BLEND_WOBBLE) return first to second
        return second to first
    }

    /** How far each line sits from where the lines around it put this donor (`_drift`). */
    private fun drift(bit: List<BlendLine>, dit: List<BlendLine>, map: Map<Int, Int>): Map<Int, Double> {
        val off = offsetsByLine(bit, dit, map)
        val keys = off.keys.sorted()
        val out = HashMap<Int, Double>()
        for ((pos, i) in keys.withIndex()) {
            val near = keys.subList(maxOf(0, pos - 3), minOf(keys.size, pos + 4)).filter { it != i }.map { off.getValue(it) }.sorted()
            if (near.isNotEmpty()) out[i] = off.getValue(i) - near[near.size / 2]
        }
        return out
    }

    /** The share of a line's words that would fill on an onset nobody measured (`_guessed`). */
    private fun guessed(text: String, group: BlendGroup?): Double? {
        val syls = group?.syllables.orEmpty()
        val got = (if (syls.isNotEmpty()) relay(text, syls) else null) ?: return null
        return got.count(BlendSyllable::guess).toDouble() / got.size
    }

    /** The lines the first donor timed, and timed wrong: i -> (drift, guessed) (`_astray`). */
    private fun astray(bit: List<BlendLine>, qit: List<BlendLine>, qmap: Map<Int, Int>?, apart: Set<String>): Map<Int, Pair<Double?, Double>> {
        if (qmap.isNullOrEmpty()) return emptyMap()
        val drift = drift(bit, qit, qmap)
        val out = LinkedHashMap<Int, Pair<Double?, Double>>()
        for ((i, j) in qmap) {
            if (j !in qit.indices) continue
            val got = guessed(unaside(lineText(bit[i]), apart), qit[j].lead) ?: continue
            val was = drift[i]
            if ((was != null && abs(was) > BLEND_JUMP) || got > BLEND_PATCHY) out[i] = was to got
        }
        return out
    }

    /** Whether the second donor's answer for one line beats the first's (`_steadier`). */
    private fun steadier(mine: Pair<Double?, Double>, theirs: Pair<Double?, Double?>): Boolean {
        val (drift, guessed) = mine
        val (other, patch) = theirs
        if (other == null || abs(other) > BLEND_STEADY) return false
        if (drift != null && abs(drift) > BLEND_JUMP) return true
        return patch != null && guessed > BLEND_PATCHY && patch <= guessed - BLEND_BETTER
    }

    /** Whether a borrowed rhythm is the right length for the room it is going into (`_fits`). */
    private fun fits(syls: List<BlendSyllable>, at: Double?, next: Double?): Boolean {
        if (syls.isEmpty() || at == null || next == null || next <= at) return true
        return syls.last().end - syls.first().start <= (next - at) * BLEND_LONG
    }

    // --- Voting on a line's start and end --------------------------------------------------

    private fun outliers(votes: Map<String, Double>): Set<String> =
        votes.filter { (k, v) -> votes.size > 1 && votes.all { (j, u) -> j == k || abs(v - u) > BLEND_FAR } }.keys

    /** Where a line begins, given the opinions about it (`_agree`). */
    private fun agree(starts: Map<String, Double>): Double {
        val out = outliers(starts)
        var keep = starts.filterKeys { it !in out }
        if (keep.isEmpty()) keep = starts["base"]?.let { mapOf("base" to it) } ?: starts
        val values = keep.values.sorted()
        if (values.size >= 3 && values.last() - values.first() > BLEND_NEAR) return values[values.size / 2]
        return values.first()
    }

    /** The line's end: the soonest anyone measured (`_last_end`). */
    private fun lastEnd(ends: Collection<Double>): Double? = ends.minOrNull()

    // --- Ad-libs -----------------------------------------------------------------------------

    /** The line without a bracket the lifted piece left hanging open (`_unclosed`). */
    private fun unclosed(text: String): String {
        var depth = 0
        var at: Int? = null
        text.forEachIndexed { i, c ->
            if (c in OPENERS) { if (depth == 0) at = i; depth++ }
            else if (c in CLOSERS && depth > 0) depth--
        }
        val open = at
        return if (depth > 0 && open != null) text.substring(0, open).trimEnd { it in ASIDE_TRIM } else text
    }

    /** Lift a trailing ad-lib out of a line the donor times without it (`_peel_aside`). */
    private fun peelAside(line: BlendLine, q: BlendLine, qit: List<BlendLine>, start: Double?, end: Double?): Pair<BlendGroup, Int>? {
        val text = line.text.orEmpty()
        val ours = key(text)
        val theirs = key(lineText(q))
        if (theirs.isEmpty() || !ours.startsWith(theirs) || ours.length <= theirs.length) return null
        val want = ours.substring(theirs.length)
        val idx = text.indices.filter { BlendText.isAlnum(text[it]) }
        if (idx.size != ours.length || theirs.length >= idx.size) return null
        val core = unclosed(text.substring(0, idx[theirs.length]).trimEnd { it in ASIDE_TRIM })
        if (core.isEmpty()) return null
        val hi = end ?: start
        for ((j, other) in qit.withIndex()) {
            if (other === q || key(lineText(other)) != want) continue
            val syls = other.lead?.syllables.orEmpty()
            val s2 = lineStart(other)
            if (syls.isEmpty() || s2 == null) continue
            if (start != null && !(start - ASIDE_REACH <= s2 && s2 <= (hi ?: start) + ASIDE_REACH)) continue
            line.text = core
            return BlendGroup(syls, s2, lineEnd(other) ?: s2) to j
        }
        return null
    }

    private fun asidesIn(text: String): List<String> =
        ASIDES.findAll(text).map { it.groupValues[1] }.filter { key(it).isNotEmpty() }.toList()

    /** Whether the line already writes this ad-lib into its own text (`_spoken_for`). */
    private fun spokenFor(text: String, line: BlendLine): Boolean {
        val k = key(text)
        return asidesIn(line.text.orEmpty()).any { kin(k, key(it)) || (aCry(it) && aCry(text)) }
    }

    private data class Said(val index: Int, val start: Double, val end: Double, val key: String)

    /** Whether the donor has these words elsewhere over the same seconds (`_echoes`). */
    private fun echoes(k: String, j: Int, line: BlendLine, said: List<Said>): Boolean {
        val lo = line.start ?: return false
        val hi = line.end ?: lo
        return said.any { it.key.isNotEmpty() && k in it.key && it.index != j && it.start <= hi && it.end >= lo }
    }

    private data class Span(val key: String, val start: Double, val end: Double)

    /** Every voice one finished line draws (`_spans_of`). */
    private fun spansOf(line: BlendLine): List<Span> {
        val out = mutableListOf<Span>()
        val at = lineStart(line)
        if (!line.lead?.syllables.isNullOrEmpty() && at != null) out += Span(key(lineText(line)), at, lineEnd(line) ?: at)
        for (g in line.background) {
            val (s, e) = groupSpan(g)
            if (g.syllables.isNotEmpty() && s != null) out += Span(key(syllablesText(g.syllables)), s, e ?: s)
        }
        return out
    }

    /** Whether the document already sings these words across these seconds (`_doubled`). */
    private fun doubled(out: List<BlendLine>, k: String, at: Double, until: Double): Boolean =
        out.any { line ->
            spansOf(line).any { s ->
                s.key.isNotEmpty() && (kin(k, s.key) || k in s.key) && minOf(until, s.end) - maxOf(at, s.start) > -DOUBLE_SLACK
            }
        }

    private fun groupSpan(g: BlendGroup): Pair<Double?, Double?> {
        val at = g.start ?: g.syllables.firstOrNull()?.start
        val done = g.end ?: g.syllables.lastOrNull()?.end
        return at to (done ?: at)
    }

    /**
     * A line nobody could match by its letters, given the stamp of the one donor line left
     * standing in the hole where it belongs (`_place_dark`).
     */
    private fun placeDark(out: List<BlendLine>, qit: List<BlendLine>, spoken: MutableSet<Any>) {
        val lit = out.indices.filter { out[it].start != null }
        if (lit.size < 2) return
        val free = qit.mapNotNull { q ->
            val at = lineStart(q) ?: return@mapNotNull null
            val text = lineText(q)
            if (key(text).isEmpty() || aCry(text)) null else Triple(q, at, lineEnd(q) ?: at)
        }
        if (free.isEmpty()) return
        for ((a, b) in lit.zipWithNext()) {
            if (b - a != 2) continue
            val lo = lineEnd(out[a]) ?: continue
            val hi = out[b].start ?: continue
            if (lo >= hi) continue
            val said = free.filter { (q, at, _) -> q !in spoken && lo - STRAY_REACH <= at && at < hi }
            if (said.size != 1) continue
            val (q, at, done) = said.single()
            val line = out[a + 1]
            if (spokenFor(lineText(q), line)) continue
            val start = maxOf(at, lo)
            line.start = start
            line.end = maxOf(minOf(done, hi), start + 0.05)
            spoken += q
        }
    }

    /** The donor's ad-libs that our lines have no place for, lifted onto them (`_lift_strays`). */
    private fun liftStrays(out: List<BlendLine>, qit: List<BlendLine>, spoken: Set<Any>, slid: Map<Int, Double>) {
        val starts = out.indices.mapNotNull { i -> out[i].start?.let { i to it } }
        if (starts.isEmpty()) return
        val said = qit.mapIndexedNotNull { j, q ->
            val s = lineStart(q) ?: return@mapIndexedNotNull null
            Said(j, s, lineEnd(q) ?: s, key(lineText(q)))
        }
        for ((j, q) in qit.withIndex()) {
            if (q in spoken) continue
            val text = lineText(q)
            val k = key(text)
            val syls = q.lead?.syllables.orEmpty()
            val begin = lineStart(q)
            if (k.isEmpty() || syls.isEmpty() || begin == null) continue
            if (BlendText.wordCount(text) > BlendText.CRY_WORDS) continue
            var host: Int? = null
            for ((i, at) in starts) { if (at <= begin) host = i else break }
            if (host == null) continue
            val line = out[host]
            val stop = line.end
            if (stop != null && begin > stop + STRAY_REACH) continue
            if (kin(k, key(line.text))) continue
            if (!(aCry(text) || echoes(k, j, line, said))) continue
            if (spokenFor(text, line) || (host + 1 < out.size && spokenFor(text, out[host + 1]))) continue
            if (line.background.any { kin(k, key(syllablesText(it.syllables))) }) continue
            val group = slide(BlendGroup(syls, begin, lineEnd(q) ?: begin), slid[host] ?: 0.0)
            if (doubled(out, k, group.start!!, group.end!!)) continue
            line.background = (line.background + group).sortedBy { it.start ?: 0.0 }
            line.end?.let { line.end = maxOf(it, group.end) }
        }
    }

    /** The base's own words for an ad-lib, on the donor's clock (`_our_words`). */
    private fun ourWords(inner: String, syls: List<BlendSyllable>): List<BlendSyllable> {
        if (syls.isEmpty()) return emptyList()
        relay(inner, syls)?.let { return it }
        val k = key(inner)
        val keys = syls.map { key(it.text) }
        for (i in syls.indices) {
            val run = StringBuilder()
            for (j in i until syls.size) {
                run.append(keys[j])
                if (run.toString() == k) relay(inner, syls.subList(i, j + 1))?.let { return it }
                if (run.length >= k.length) break
            }
        }
        return listOf(BlendSyllable(inner.trim(), syls.first().start, syls.last().end, partOfWord = false))
    }

    private class Pooled(val group: BlendGroup, val start: Double, val key: String, val syllables: List<BlendSyllable>)

    /** Ad-libs the base writes into the line, given the timing a donor has (`_peel_bracket`). */
    private fun peelBracket(line: BlendLine, pool: List<Pooled>, start: Double?, end: Double?, spoken: MutableSet<Any>): List<BlendGroup> {
        val text = line.text.orEmpty()
        if (text.isEmpty() || text.none { it == '(' || it == '\uff08' || it == '[' || it == '\u3010' }) return emptyList()
        val lo = start ?: return emptyList()
        val hi = end ?: lo
        val got = mutableListOf<BlendGroup>()
        val left = BRACKETED.replace(text) { m ->
            val inner = m.groupValues[1]
            val k = key(inner)
            if (k.isEmpty()) return@replace m.value
            val cry = aCry(inner)
            var best: Triple<Triple<Int, Int, Double>, Pooled, Unit>? = null
            for (item in pool) {
                if (item.group in spoken) continue
                val said = syllablesText(item.syllables)
                if (!(kin(k, item.key) || (cry && aCry(said)))) continue
                if (!(lo - ASIDE_REACH <= item.start && item.start <= hi + ASIDE_REACH)) continue
                val near = Triple(if (kin(k, item.key)) 0 else 1, abs(item.key.length - k.length), abs(item.start - lo))
                if (best == null || compareNear(near, best.first) < 0) best = Triple(near, item, Unit)
            }
            val chosen = best?.second ?: return@replace m.value
            val said = ourWords(inner, chosen.syllables)
            got += BlendGroup(said, said.first().start, said.last().end)
            spoken += chosen.group
            ""
        }.replace(SPACES, " ").trim()
        if (got.isEmpty() || key(left).isEmpty()) return emptyList()
        line.text = left
        return got
    }

    private fun compareNear(a: Triple<Int, Int, Double>, b: Triple<Int, Int, Double>): Int =
        compareValuesBy(a, b, { it.first }, { it.second }, { it.third })

    // --- Keeping lines in order ------------------------------------------------------------

    /** Lines that borrowed the wrong repeat of themselves, given back (`_in_step`). */
    private fun inStep(out: MutableList<BlendLine>, based: List<Pair<Double?, Double?>>) {
        val at = out.map { lineStart(it) }
        if (out.size != based.size || at.count { it != null } < 4) return
        val was = based.map { it.first }
        if (was.zipWithNext().any { (x, y) -> x != null && y != null && x > y }) return
        // The longest run of starts that ascend: the most lines that can all be right together.
        val tails = mutableListOf<Int>()
        val back = IntArray(out.size) { out.size }
        for ((i, v) in at.withIndex()) {
            if (v == null) continue
            var lo = 0
            var hi = tails.size
            while (lo < hi) {
                val mid = (lo + hi) / 2
                if (at[tails[mid]]!! <= v) lo = mid + 1 else hi = mid
            }
            back[i] = if (lo > 0) tails[lo - 1] else out.size
            if (lo == tails.size) tails += i else tails[lo] = i
        }
        val kept = HashSet<Int>()
        var i = tails.lastOrNull() ?: out.size
        while (i < out.size) { kept += i; i = back[i] }
        val said = HashMap<String, MutableList<Double>>()
        for ((n, line) in out.withIndex()) {
            val v = at[n]
            if (n in kept && v != null) said.getOrPut(key(lineText(line))) { mutableListOf() } += v
        }
        for ((n, pair) in based.withIndex()) {
            val (start, end) = pair
            val v = at[n]
            if (n in kept || v == null || start == null) continue
            val twin = said[key(lineText(out[n])).ifEmpty { "\u0000" }].orEmpty()
            if (twin.none { abs(it - v) <= BLEND_FAR }) continue
            val given = out[n].bare(lineText(out[n]))
            given.start = start
            if (end != null) given.end = end
            out[n] = given
        }
    }

    // --- The blend itself (`_blend`) -------------------------------------------------------

    /**
     * The documents reconciled into one.
     */
    fun blend(
        base: BlendDoc,
        words: String,
        origin: String,
        qq: BlendDoc?,
        whose: String,
        spare: BlendDoc?,
        spareName: String,
    ): BlendOutcome? {
        val bit = base.lines
        if (bit.isEmpty()) return null
        var qit: List<BlendLine> = qq?.lines.orEmpty()
        val qorig = qit.size
        val apart = filedApart(qq?.lines) + filedApart(spare?.lines)

        /** Whether line [i] would actually come out with words on it. */
        fun worded(m: Map<Int, Int>?, items: List<BlendLine>, i: Int): Boolean {
            val j = m?.get(i)
            val got = if (j != null && j < items.size) items[j] else null
            val syls = got?.lead?.syllables.orEmpty()
            return syls.isNotEmpty() && relay(unaside(lineText(bit[i]), apart), syls, alike(got)) != null
        }

        val qpairs: Map<Int, Int> = if (qit.isNotEmpty()) pair(bit, qit).orEmpty() else emptyMap()
        var qmap: MutableMap<Int, Int>? = if (qit.isNotEmpty()) timely(qpairs, bit, qit) else null
        val astrayQ = qpairs.filterKeys { it !in qmap.orEmpty() }
        if (qit.isNotEmpty() && (qmap?.size ?: 0) < bit.size) {
            val recut = restream(bit, qit)
            if (recut != null) {
                val map = LinkedHashMap(qmap.orEmpty())
                val extra = mutableListOf<BlendLine>()
                recut.forEachIndexed { i, got ->
                    if (got == null || worded(map, qit, i)) return@forEachIndexed
                    extra += got
                    map[i] = qit.size + extra.size - 1
                }
                qmap = map
                if (extra.isNotEmpty()) qit = qit + extra
            }
        }

        val borrowed = HashSet<Int>()
        val rhythm = HashSet<Int>()
        val dropped = identitySet()
        val spareLines = spare?.lines.orEmpty()
        val holes = bit.indices.filter { !worded(qmap, qit, it) }
        val astray = if (spare != null) astray(bit, qit, qmap, apart) else emptyMap()
        val want = holes + astray.keys.sorted()
        if (spare != null && want.isNotEmpty()) {
            val sit = spareLines.toMutableList()
            val smap = if (sit.isNotEmpty()) timely(pair(bit, sit).orEmpty(), bit, sit) else mutableMapOf()
            if (sit.isNotEmpty() && smap.size < bit.size) {
                restream(bit, sit)?.forEachIndexed { i, got ->
                    if (i !in smap && got != null) { sit += got; smap[i] = sit.lastIndex }
                }
            }
            val sdrift = drift(bit, sit, smap)
            val map = LinkedHashMap(qmap.orEmpty())
            val extra = mutableListOf<BlendLine>()
            for (i in want) {
                if (!worded(smap, sit, i)) continue
                val theirsLine = sit[smap.getValue(i)]
                val mine = astray[i]
                if (mine != null) {
                    val theirs = sdrift[i] to guessed(unaside(lineText(bit[i]), apart), theirsLine.lead)
                    if (!steadier(mine, theirs)) continue
                    dropped += qit[map.getValue(i)]
                } else if (abs(sdrift[i] ?: 0.0) > BLEND_STEADY) {
                    rhythm += i
                }
                extra += theirsLine
                map[i] = qit.size + extra.size - 1
                borrowed += i
            }
            qmap = map
            if (extra.isNotEmpty()) qit = qit + extra
        }

        val used = HashSet<String>()
        val spoken = identitySet().apply {
            qmap.orEmpty().values.forEach { k -> if (k in qit.indices) add(qit[k]) }
            addAll(dropped)
        }
        val slid = HashMap<Int, Double>()
        val over = HashMap<Int, Double>()
        // Only backing groups are pooled: whole donor lines never match here.
        val pool = (qit.take(qorig) + spareLines).flatMap { it.background }.mapNotNull { g ->
            val (at, _) = groupSpan(g)
            if (g.syllables.isEmpty() || at == null) null else Pooled(g, at, key(syllablesText(g.syllables)), g.syllables)
        }

        val out = mutableListOf<BlendLine>()
        val based = mutableListOf<Pair<Double?, Double?>>()
        for ((i, it) in bit.withIndex()) {
            val bS = lineStart(it)
            val bE = lineEnd(it)
            val bNext = bit.getOrNull(i + 1)?.let(::lineStart)
            val q = qmap?.get(i)?.let { qit[it] }
            val qS = lineStart(q)
            val qE = lineEnd(q)

            val starts = LinkedHashMap<String, Double>().apply {
                bS?.let { put("base", it) }
                qS?.let { put("qq", it) }
            }
            val line = it.bare(lineText(it))
            if (starts.isEmpty()) {
                out += line
                based += bS to bE
                continue
            }
            var start = agree(starts)
            val lent = q?.lead?.syllables.orEmpty()
            if (i in rhythm) {
                if (bS != null) start = bS
            } else if (qS != null && relay(lineText(it), lent) != null) {
                start = qS
            }
            if ("qq" in starts) {
                val rest = starts.filterKeys { k -> k != "qq" }
                if (rest.isEmpty() || abs(agree(rest) - start) > 1e-6) used += "qq"
            }

            val asides = peelBracket(line, pool, start, lineEnd(it), spoken).toMutableList()
            if (q != null) {
                peelAside(line, q, qit, start, lineEnd(it))?.let { (aside, lifted) ->
                    asides += aside
                    spoken += qit[lifted]
                }
            }
            val qby = if (qS != null) start - qS else 0.0
            var syls: List<BlendSyllable> = relay(line.text.orEmpty(), q?.lead?.syllables.orEmpty(), alike(q)).orEmpty()
            if (syls.isNotEmpty()) {
                syls = syls.map { y -> slide(y, qby) }
                if (i in rhythm && !fits(syls, start, bNext)) syls = emptyList()
                else used += if (i in borrowed) "spare" else "qq"
            } else {
                val own = it.lead?.syllables.orEmpty()
                if (own.isNotEmpty() && bS != null) syls = own.map { y -> slide(y, start - bS) }
                val looseAt = astrayQ[i]
                if (syls.isEmpty() && looseAt != null) {
                    val loose = qit[looseAt]
                    val dS = lineStart(loose)
                    val borrowedSyls = relay(line.text.orEmpty(), loose.lead?.syllables.orEmpty())
                    if (borrowedSyls != null && dS != null) {
                        val moved = borrowedSyls.map { y -> slide(y, start - dS) }
                        if (fits(moved, start, bNext)) {
                            syls = moved
                            spoken += loose
                            used += "qq"
                        }
                    }
                }
            }

            val ends = LinkedHashMap<String, Double>()
            if (bE != null && bS != null) ends["base"] = bE + (start - bS)
            if (qE != null && qS != null) ends["qq"] = qE + (start - qS)
            var end = lastEnd(ends.values)
            if ("qq" in ends) {
                val rest = ends.filterKeys { k -> k != "qq" }.values
                val restEnd = lastEnd(rest)?.takeIf { v -> v != 0.0 } ?: end!!
                if (rest.isEmpty() || abs(restEnd - end!!) > 1e-6) used += "qq"
            }
            if (syls.isNotEmpty()) end = if (end != null) maxOf(end, syls.last().end) else syls.last().end
            if (end == null || end <= start) end = if (syls.isNotEmpty()) syls.last().end else start + 4.0
            end = maxOf(end, start + 0.05)

            val own = ends["base"]
            val sung = if (syls.isNotEmpty()) (syls.dropLast(1).map(BlendSyllable::end).maxOrNull() ?: start) else start
            if (own != null && bE != null && bNext != null && bNext - bE >= BLEND_TAIL && end - own > BLEND_HOLD &&
                (syls.isEmpty() || own >= maxOf(sung, syls.last().start + 0.05))
            ) {
                end = maxOf(own, start + 0.05)
                if (syls.isNotEmpty() && syls.last().end > end) syls = syls.dropLast(1) + syls.last().copy(end = end)
            } else if (own != null && bE != null && bNext != null && bE > bNext + BLEND_HOLD && own > end + BLEND_HOLD) {
                end = own
                if (syls.isNotEmpty()) syls = syls.dropLast(1) + syls.last().copy(end = maxOf(syls.last().end, end))
            }

            line.start = start
            line.end = end
            over[out.size] = if (bE != null && bNext != null) maxOf(0.0, bE - bNext) else 0.0
            slid[out.size] = qby
            if (syls.isNotEmpty()) line.lead = BlendGroup(syls, start, end)
            val bg = it.background + asides
            if (bg.isNotEmpty()) line.background = if (bS != null) bg.map { g -> slide(g, start - bS) } else bg
            out += line
            based += bS to bE
        }

        inStep(out, based)

        // A line held past the start of the next gives way, down to what it is still singing.
        for (i in 0 until out.size - 1) {
            val at = lineStart(out[i + 1]) ?: continue
            val mine = lineStart(out[i]) ?: continue
            val done = lineEnd(out[i]) ?: continue
            val room = at + (over[i] ?: 0.0)
            if (at <= mine || done <= room) continue
            var keep = maxOf(room, mine + 0.05)
            val lead = out[i].lead
            val syls = lead?.syllables.orEmpty()
            if (lead != null && syls.isNotEmpty()) {
                keep = maxOf(keep, syls.last().start + 0.05)
                if (syls.last().end > keep) out[i].lead = BlendGroup(syls.dropLast(1) + syls.last().copy(end = keep), lead.start, keep)
            }
            out[i].end = keep
        }

        if (out.isNotEmpty()) {
            for (lines in listOf(qit.take(qorig), spareLines)) if (lines.isNotEmpty()) placeDark(out, lines, spoken)
            for (lines in listOf(qit.take(qorig), spareLines)) if (lines.isNotEmpty()) liftStrays(out, lines, spoken, slid)
        }
        if (out.isEmpty()) return null

        val doc = BlendText.unlump(BlendDoc(out, base.songwriters))
        val quality = BlendText.quality(doc)
        val parts = listOfNotNull(whose.takeIf { "qq" in used }, spareName.takeIf { "spare" in used && it.isNotEmpty() })
        return if (parts.isNotEmpty()) {
            BlendOutcome(noOverlap(doc), quality, via = (listOf(words) + parts).joinToString(" + "), alone = null)
        } else {
            BlendOutcome(doc, quality, via = null, alone = origin)
        }
    }

    // --- Standing a blend down -------------------------------------------------------------

    /** The donor's document, written the way the base writes it (`_reworded`). */
    private fun reworded(donor: BlendDoc, base: BlendDoc): BlendDoc {
        val dit = donor.lines
        val bit = base.lines
        if (dit.isEmpty() || bit.isEmpty()) return donor
        val a = dit.map { key(lineText(it)) }
        val b = bit.map { key(lineText(it)) }
        val mate = exactPairs(a, b)
        mate.putAll(nearPairs(a, b, mate))
        var said = 0
        val out = dit.mapIndexed { i, it ->
            val lead = it.lead
            val syls = lead?.syllables.orEmpty()
            val text = mate[i]?.let { j -> lineText(bit[j]) }.orEmpty()
            val got = if (text.isNotEmpty() && syls.isNotEmpty()) relay(text, syls) else null
            if (got == null || lead == null) return@mapIndexed it
            said++
            BlendLine(text, it.start, it.end, BlendGroup(got, lead.start, lead.end), it.background, it.agent, it.oppositeAligned)
        }
        return if (said == 0) donor else BlendDoc(out, donor.songwriters.ifEmpty { base.songwriters })
    }

    private fun said(doc: BlendDoc?): String = doc?.lines.orEmpty().joinToString("") { key(lineText(it)) }

    /** Whether the blend's words are missing a real part of the song (`_shorter`). */
    private fun shorter(blend: BlendDoc?, donor: BlendDoc): Boolean {
        val a = said(blend)
        val b = said(donor)
        if (a.isEmpty() || b.isEmpty() || a.length >= b.length) return false
        val ops = SequenceMatcher.of(a, b).opcodes()
        val shared = ops.filter { it.tag == SequenceMatcher.Tag.EQUAL }.sumOf { it.i2 - it.i1 }
        if (shared < BLEND_SAME_WORDS * a.length) return false
        val absent = ops.filter { it.tag != SequenceMatcher.Tag.EQUAL }.maxOfOrNull { it.j2 - it.j1 } ?: 0
        return absent > (1 - BLEND_SHORT) * b.length
    }

    /** Whether the blend leaves a materially larger share of lines untimed (`_thinner`). */
    private fun thinner(blend: BlendDoc?, donor: BlendDoc): Boolean {
        fun share(doc: BlendDoc?): Double {
            val items = doc?.lines.orEmpty()
            if (items.isEmpty()) return 0.0
            return items.count { !it.lead?.syllables.isNullOrEmpty() }.toDouble() / items.size
        }
        return share(donor) - share(blend) >= BLEND_THIN
    }

    // --- Display repairs ---------------------------------------------------------------------

    /**
     * No line drawn past the start of the next where the part past it is slack (`no_overlap`).
     * Run over blends only: a single source's overlaps are its own measurements.
     */
    fun noOverlap(doc: BlendDoc): BlendDoc {
        val items = doc.lines
        if (items.size < 2) return doc
        for ((n, it) in items.withIndex()) {
            val next = items.getOrNull(n + 1)?.let(::lineStart) ?: continue
            val end = lineEnd(it) ?: continue
            if (end <= next) continue
            val start = lineStart(it)
            if (start != null && start >= next) continue
            var floor: Double? = start
            it.lead?.let { lead ->
                val (clipped, sung) = clip(lead, next)
                it.lead = clipped
                floor = later(floor, sung)
            }
            if (it.background.isNotEmpty()) {
                val done = it.background.map { g -> clip(g, next) }
                it.background = done.map { d -> d.first }
                done.forEach { d -> floor = later(floor, d.second) }
            }
            it.end = floor?.let { f -> maxOf(next, f) } ?: next
        }
        return doc
    }

    private fun later(a: Double?, b: Double?) = if (a == null) b else if (b == null) a else maxOf(a, b)

    /** A group with its slack past [next] taken off, and the last moment it is still singing. */
    private fun clip(group: BlendGroup, next: Double): Pair<BlendGroup, Double?> {
        val at = group.start
        if (at != null && at >= next) return group to maxOf(at, group.end ?: at)
        var sung = at
        val syls = group.syllables.mapIndexed { i, y ->
            // Only the last syllable's end is slack; an interior one's end is the next one's onset.
            val clipped = if (i == group.syllables.lastIndex && y.start < next && next < y.end) y.copy(end = next) else y
            sung = sung?.let { s -> maxOf(s, clipped.end) } ?: clipped.end
            clipped
        }
        val end = group.end?.let { e -> if (e > next) sung?.let { maxOf(next, it) } ?: next else e }
        return BlendGroup(syls, group.start, end) to sung
    }

    private fun identitySet(): MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())
}
