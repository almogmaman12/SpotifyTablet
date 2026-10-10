package com.almog.spotifytablet.lyrics.mobile.network.data.blend

/**
 * A port of CPython's `difflib.SequenceMatcher` as the blend uses it: always with no junk
 * function and `autojunk=False`, so no element is ever treated as junk or as popular. Its
 * matching blocks, opcodes and ratios are the ones Python returns for the same input, which is
 * what the blend's tuned thresholds were measured against.
 *
 * Sequences are compared as int arrays; [of] turns strings (per UTF-16 unit, like the rest of the
 * blender) and lists of strings into them with one shared symbol table.
 */
internal class SequenceMatcher private constructor(private val a: IntArray, private val b: IntArray) {

    data class Block(val a: Int, val b: Int, val size: Int)
    data class Opcode(val tag: Tag, val i1: Int, val i2: Int, val j1: Int, val j2: Int)
    enum class Tag { REPLACE, DELETE, INSERT, EQUAL }

    /** Every position of each element in `b`, ascending (difflib's `b2j`). */
    private val b2j: Map<Int, IntArray> = buildMap {
        val lists = HashMap<Int, MutableList<Int>>()
        b.forEachIndexed { j, elt -> lists.getOrPut(elt) { mutableListOf() }.add(j) }
        lists.forEach { (k, v) -> put(k, v.toIntArray()) }
    }

    private var blocks: List<Block>? = null
    private var opcodes: List<Opcode>? = null

    fun findLongestMatch(alo: Int, ahi: Int, blo: Int, bhi: Int): Block {
        var besti = alo
        var bestj = blo
        var bestsize = 0
        // j2len[j + 1] is the length of the match ending at a[i - 1] and b[j], for the previous i.
        var j2len = IntArray(b.size + 1)
        var newj2len = IntArray(b.size + 1)
        var touched = IntArray(0)
        for (i in alo until ahi) {
            val js = b2j[a[i]] ?: IntArray(0)
            val nowTouched = IntArray(js.size)
            var n = 0
            for (j in js) {
                if (j < blo) continue
                if (j >= bhi) break
                val k = j2len[j] + 1
                newj2len[j + 1] = k
                nowTouched[n++] = j + 1
                if (k > bestsize) {
                    besti = i - k + 1
                    bestj = j - k + 1
                    bestsize = k
                }
            }
            for (t in touched) j2len[t] = 0
            val swap = j2len; j2len = newj2len; newj2len = swap
            touched = nowTouched.copyOf(n)
        }
        // With nothing junk, difflib's extension loops cannot grow a longest match; they are omitted.
        return Block(besti, bestj, bestsize)
    }

    fun matchingBlocks(): List<Block> {
        blocks?.let { return it }
        val queue = ArrayDeque<IntArray>()
        queue.addLast(intArrayOf(0, a.size, 0, b.size))
        val found = mutableListOf<Block>()
        while (queue.isNotEmpty()) {
            val (alo, ahi, blo, bhi) = queue.removeLast()
            val x = findLongestMatch(alo, ahi, blo, bhi)
            val (i, j, k) = x
            if (k > 0) {
                found += x
                if (alo < i && blo < j) queue.addLast(intArrayOf(alo, i, blo, j))
                if (i + k < ahi && j + k < bhi) queue.addLast(intArrayOf(i + k, ahi, j + k, bhi))
            }
        }
        found.sortWith(compareBy<Block> { it.a }.thenBy { it.b }.thenBy { it.size })
        var i1 = 0; var j1 = 0; var k1 = 0
        val out = mutableListOf<Block>()
        for ((i2, j2, k2) in found) {
            if (i1 + k1 == i2 && j1 + k1 == j2) {
                k1 += k2
            } else {
                if (k1 > 0) out += Block(i1, j1, k1)
                i1 = i2; j1 = j2; k1 = k2
            }
        }
        if (k1 > 0) out += Block(i1, j1, k1)
        out += Block(a.size, b.size, 0)
        return out.also { blocks = it }
    }

    fun opcodes(): List<Opcode> {
        opcodes?.let { return it }
        var i = 0
        var j = 0
        val out = mutableListOf<Opcode>()
        for ((ai, bj, size) in matchingBlocks()) {
            val tag = when {
                i < ai && j < bj -> Tag.REPLACE
                i < ai -> Tag.DELETE
                j < bj -> Tag.INSERT
                else -> null
            }
            if (tag != null) out += Opcode(tag, i, ai, j, bj)
            i = ai + size
            j = bj + size
            if (size > 0) out += Opcode(Tag.EQUAL, ai, i, bj, j)
        }
        return out.also { opcodes = it }
    }

    fun ratio(): Double = ratioOf(matchingBlocks().sumOf { it.size })

    fun quickRatio(): Double {
        val full = HashMap<Int, Int>()
        for (elt in b) full[elt] = (full[elt] ?: 0) + 1
        val avail = HashMap<Int, Int>()
        var matches = 0
        for (elt in a) {
            val numb = avail[elt] ?: full[elt] ?: 0
            avail[elt] = numb - 1
            if (numb > 0) matches++
        }
        return ratioOf(matches)
    }

    private fun ratioOf(matches: Int): Double {
        val length = a.size + b.size
        return if (length > 0) 2.0 * matches / length else 1.0
    }

    companion object {
        fun of(a: CharSequence, b: CharSequence): SequenceMatcher =
            SequenceMatcher(IntArray(a.length) { a[it].code }, IntArray(b.length) { b[it].code })

        fun of(a: List<String>, b: List<String>): SequenceMatcher {
            val ids = HashMap<String, Int>()
            fun encode(seq: List<String>) = IntArray(seq.size) { ids.getOrPut(seq[it]) { ids.size } }
            return SequenceMatcher(encode(a), encode(b))
        }
    }
}
