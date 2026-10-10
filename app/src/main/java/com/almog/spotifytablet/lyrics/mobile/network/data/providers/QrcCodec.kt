package com.almog.spotifytablet.lyrics.mobile.network.data.providers

import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.zip.InflaterInputStream

/** Tencent's QRC cipher is 3DES-ECB with two deliberately non-standard S-box entries. */
internal object QrcCodec {
    private val key = "!@#)(*$%123ZXC!@!@#)(NHL".toByteArray()
    private val line = Regex("^\\[(\\d+),(\\d+)](.*)$")
    private val token = Regex("(.*?)\\((\\d+),(\\d+)\\)")

    fun decrypt(hex: String): String? = runCatching {
        val data = decryptBytes(hex)
        InflaterInputStream(ByteArrayInputStream(data)).bufferedReader().readText()
    }.getOrNull()

    internal fun decryptBytes(hex: String): ByteArray {
        var data = hex.filterNot(Char::isWhitespace).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        require(data.size % 8 == 0)
        data = crypt(data, key.copyOfRange(16, 24), false)
        data = crypt(data, key.copyOfRange(8, 16), true)
        return crypt(data, key.copyOfRange(0, 8), false)
    }

    fun toTtml(raw: String): String? {
        val content = Regex("LyricContent=\"(.*?)\"\\s*/>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(raw)?.groupValues?.get(1)?.let(::htmlDecode) ?: raw
        var offset = 0L
        val paragraphs = content.lineSequence().mapNotNull { source ->
            val row = source.trim()
            Regex("^\\[offset:(-?\\d+)]", RegexOption.IGNORE_CASE).find(row)?.let { offset = it.groupValues[1].toLong(); return@mapNotNull null }
            val match = line.matchEntire(row) ?: return@mapNotNull null
            val start = (match.groupValues[1].toLong() + offset).coerceAtLeast(0)
            val duration = match.groupValues[2].toLong()
            val spans = token.findAll(match.groupValues[3]).mapNotNull {
                val text = it.groupValues[1].takeIf(String::isNotEmpty) ?: return@mapNotNull null
                val wordStart = (it.groupValues[2].toLong() + offset).coerceAtLeast(0)
                val wordDuration = it.groupValues[3].toLong()
                "<span begin=\"${sec(wordStart)}\" end=\"${sec(wordStart + wordDuration)}\">${xml(text)}</span>"
            }.toList()
            if (spans.isEmpty()) null else "<p begin=\"${sec(start)}\" end=\"${sec(start + duration)}\">${spans.joinToString("")}</p>"
        }.toList()
        if (paragraphs.isEmpty()) return null
        return """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="word"><body><div>${paragraphs.joinToString("")}</div></body></tt>"""
    }

    private fun crypt(input: ByteArray, key: ByteArray, encrypt: Boolean): ByteArray {
        val keys = donorSchedule(key, encrypt)
        return ByteArray(input.size).also { output ->
            for (offset in input.indices step 8) {
                val block = input.copyOfRange(offset, offset + 8)
                var left = 0; var right = 0
                repeat(32) { left = left or bitNum(block, IPL[it], 31 - it); right = right or bitNum(block, IPR[it], 31 - it) }
                repeat(15) { round -> val previous = right; right = donorF(right, keys[round]) xor left; left = previous }
                left = donorF(right, keys[15]) xor left
                donorJoin(left, right).copyInto(output, offset)
            }
        }
    }

    private fun donorSchedule(key: ByteArray, encrypt: Boolean): Array<ByteArray> {
        val schedule = Array(16) { ByteArray(6) }; var c = 0; var d = 0
        repeat(28) { c = c or bitNum(key, KEY_C[it], 31 - it); d = d or bitNum(key, KEY_D[it], 31 - it) }
        repeat(16) { round ->
            val shift = SHIFTS[round]; c = ((c shl shift) or (c ushr (28 - shift))) and 0xfffffff0.toInt(); d = ((d shl shift) or (d ushr (28 - shift))) and 0xfffffff0.toInt()
            val target = if (encrypt) round else 15 - round
            repeat(24) { j -> schedule[target][j ushr 3] = (schedule[target][j ushr 3].toInt() or bitR(c, KEY_COMP[j], 7 - (j and 7))).toByte() }
            for (j in 24 until 48) schedule[target][j ushr 3] = (schedule[target][j ushr 3].toInt() or bitR(d, KEY_COMP[j] - 27, 7 - (j and 7))).toByte()
        }
        return schedule
    }

    private fun donorF(state: Int, key: ByteArray): Int {
        var t1 = bitL(state,31,0) or ((state and 0xf0000000.toInt()) ushr 1) or bitL(state,4,5) or bitL(state,3,6) or ((state and 0x0f000000) ushr 3) or bitL(state,8,11) or bitL(state,7,12) or ((state and 0x00f00000) ushr 5) or bitL(state,12,17) or bitL(state,11,18) or ((state and 0x000f0000) ushr 7) or bitL(state,16,23)
        var t2 = bitL(state,15,0) or ((state and 0x0000f000) shl 15) or bitL(state,20,5) or bitL(state,19,6) or ((state and 0x00000f00) shl 13) or bitL(state,24,11) or bitL(state,23,12) or ((state and 0x000000f0) shl 11) or bitL(state,28,17) or bitL(state,27,18) or ((state and 0x0000000f) shl 9) or bitL(state,0,23)
        val b0=(t1 ushr 24 xor key[0].toInt()) and 0xff; val b1=(t1 ushr 16 xor key[1].toInt()) and 0xff; val b2=(t1 ushr 8 xor key[2].toInt()) and 0xff
        val b3=(t2 ushr 24 xor key[3].toInt()) and 0xff; val b4=(t2 ushr 16 xor key[4].toInt()) and 0xff; val b5=(t2 ushr 8 xor key[5].toInt()) and 0xff
        val boxed=(SBOX[0][sboxBit(b0 ushr 2)] shl 28) or (SBOX[1][sboxBit(((b0 and 3) shl 4) or (b1 ushr 4))] shl 24) or (SBOX[2][sboxBit(((b1 and 15) shl 2) or (b2 ushr 6))] shl 20) or (SBOX[3][sboxBit(b2 and 63)] shl 16) or (SBOX[4][sboxBit(b3 ushr 2)] shl 12) or (SBOX[5][sboxBit(((b3 and 3) shl 4) or (b4 ushr 4))] shl 8) or (SBOX[6][sboxBit(((b4 and 15) shl 2) or (b5 ushr 6))] shl 4) or SBOX[7][sboxBit(b5 and 63)]
        var out=0; PPAIRS.forEach { (from,to) -> out = out or bitL(boxed,from,to) }; return out
    }

    private fun donorJoin(left:Int,right:Int):ByteArray { val out=ByteArray(8); repeat(8){k->val base=7-k;var v=0;repeat(4){t->v=v or bitR(right,base+8*t,7-2*t) or bitR(left,base+8*t,6-2*t)};out[INV_ORDER[k]]=v.toByte()};return out }
    private fun bitNum(bytes:ByteArray,b:Int,c:Int):Int { val index=(b/32)*4+3-(b%32)/8;return (((bytes[index].toInt() and 0xff) ushr (7-b%8)) and 1) shl c }
    private fun bitR(value:Int,b:Int,c:Int)=((value ushr (31-b)) and 1) shl c
    private fun bitL(value:Int,b:Int,c:Int)=((value shl b) and Int.MIN_VALUE) ushr c
    private fun sboxBit(value:Int)=(value and 0x20) or ((value and 0x1f) ushr 1) or ((value and 1) shl 4)

    private fun subkeys(key: ByteArray): LongArray {
        var raw = 0L; key.forEach { raw = (raw shl 8) or (it.toLong() and 0xff) }
        val pc1 = permute(raw, 64, PC1); var c = ((pc1 ushr 28) and 0xfffffff).toInt(); var d = (pc1 and 0xfffffff).toInt()
        return LongArray(16) { round ->
            val shift = SHIFTS[round]; c = ((c shl shift) or (c ushr (28 - shift))) and 0xfffffff; d = ((d shl shift) or (d ushr (28 - shift))) and 0xfffffff
            permute((c.toLong() shl 28) or d.toLong(), 56, PC2)
        }
    }

    private fun feistel(right: Int, key: Long): Int {
        val expanded = permute(right.toLong() and 0xffffffffL, 32, E) xor key
        var substituted = 0
        repeat(8) { box ->
            val six = ((expanded ushr (42 - box * 6)) and 0x3f).toInt()
            val row = ((six and 0x20) ushr 4) or (six and 1); val col = (six ushr 1) and 0xf
            substituted = (substituted shl 4) or SBOX[box][row * 16 + col]
        }
        return permute(substituted.toLong() and 0xffffffffL, 32, P).toInt()
    }

    private fun permute(value: Long, width: Int, table: IntArray): Long {
        var out = 0L; table.forEach { position -> out = (out shl 1) or ((value ushr (width - position)) and 1L) }; return out
    }

    private fun sec(ms: Long) = "${"%.3f".format(Locale.ROOT, ms / 1000.0)}s"
    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun htmlDecode(s: String) = s.replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    private val SHIFTS = intArrayOf(1,1,2,2,2,2,2,2,1,2,2,2,2,2,2,1)
    private val KEY_C=intArrayOf(56,48,40,32,24,16,8,0,57,49,41,33,25,17,9,1,58,50,42,34,26,18,10,2,59,51,43,35)
    private val KEY_D=intArrayOf(62,54,46,38,30,22,14,6,61,53,45,37,29,21,13,5,60,52,44,36,28,20,12,4,27,19,11,3)
    private val KEY_COMP=intArrayOf(13,16,10,23,0,4,2,27,14,5,20,9,22,18,11,3,25,7,15,6,26,19,12,1,40,51,30,36,46,54,29,39,50,44,32,47,43,48,38,55,33,52,45,41,49,35,28,31)
    private val IPL=intArrayOf(57,49,41,33,25,17,9,1,59,51,43,35,27,19,11,3,61,53,45,37,29,21,13,5,63,55,47,39,31,23,15,7)
    private val IPR=intArrayOf(56,48,40,32,24,16,8,0,58,50,42,34,26,18,10,2,60,52,44,36,28,20,12,4,62,54,46,38,30,22,14,6)
    private val PPAIRS=arrayOf(15 to 0,6 to 1,19 to 2,20 to 3,28 to 4,11 to 5,27 to 6,16 to 7,0 to 8,14 to 9,22 to 10,25 to 11,4 to 12,17 to 13,30 to 14,9 to 15,1 to 16,7 to 17,23 to 18,13 to 19,31 to 20,26 to 21,2 to 22,8 to 23,18 to 24,12 to 25,29 to 26,5 to 27,21 to 28,10 to 29,3 to 30,24 to 31)
    private val INV_ORDER=intArrayOf(3,2,1,0,7,6,5,4)
    private val IP = intArrayOf(58,50,42,34,26,18,10,2,60,52,44,36,28,20,12,4,62,54,46,38,30,22,14,6,64,56,48,40,32,24,16,8,57,49,41,33,25,17,9,1,59,51,43,35,27,19,11,3,61,53,45,37,29,21,13,5,63,55,47,39,31,23,15,7)
    private val FP = intArrayOf(40,8,48,16,56,24,64,32,39,7,47,15,55,23,63,31,38,6,46,14,54,22,62,30,37,5,45,13,53,21,61,29,36,4,44,12,52,20,60,28,35,3,43,11,51,19,59,27,34,2,42,10,50,18,58,26,33,1,41,9,49,17,57,25)
    private val E = intArrayOf(32,1,2,3,4,5,4,5,6,7,8,9,8,9,10,11,12,13,12,13,14,15,16,17,16,17,18,19,20,21,20,21,22,23,24,25,24,25,26,27,28,29,28,29,30,31,32,1)
    private val P = intArrayOf(16,7,20,21,29,12,28,17,1,15,23,26,5,18,31,10,2,8,24,14,32,27,3,9,19,13,30,6,22,11,4,25)
    private val PC1 = intArrayOf(57,49,41,33,25,17,9,1,58,50,42,34,26,18,10,2,59,51,43,35,27,19,11,3,60,52,44,36,63,55,47,39,31,23,15,7,62,54,46,38,30,22,14,6,61,53,45,37,29,21,13,5,28,20,12,4)
    private val PC2 = intArrayOf(14,17,11,24,1,5,3,28,15,6,21,10,23,19,12,4,26,8,16,7,27,20,13,2,41,52,31,37,47,55,30,40,51,45,33,48,44,49,39,56,34,53,46,42,50,36,29,32)
    private val SBOX = arrayOf(
        intArrayOf(14,4,13,1,2,15,11,8,3,10,6,12,5,9,0,7,0,15,7,4,14,2,13,1,10,6,12,11,9,5,3,8,4,1,14,8,13,6,2,11,15,12,9,7,3,10,5,0,15,12,8,2,4,9,1,7,5,11,3,14,10,0,6,13),
        intArrayOf(15,1,8,14,6,11,3,4,9,7,2,13,12,0,5,10,3,13,4,7,15,2,8,15,12,0,1,10,6,9,11,5,0,14,7,11,10,4,13,1,5,8,12,6,9,3,2,15,13,8,10,1,3,15,4,2,11,6,7,12,0,5,14,9),
        intArrayOf(10,0,9,14,6,3,15,5,1,13,12,7,11,4,2,8,13,7,0,9,3,4,6,10,2,8,5,14,12,11,15,1,13,6,4,9,8,15,3,0,11,1,2,12,5,10,14,7,1,10,13,0,6,9,8,7,4,15,14,3,11,5,2,12),
        intArrayOf(7,13,14,3,0,6,9,10,1,2,8,5,11,12,4,15,13,8,11,5,6,15,0,3,4,7,2,12,1,10,14,9,10,6,9,0,12,11,7,13,15,1,3,14,5,2,8,4,3,15,0,6,10,10,13,8,9,4,5,11,12,7,2,14),
        intArrayOf(2,12,4,1,7,10,11,6,8,5,3,15,13,0,14,9,14,11,2,12,4,7,13,1,5,0,15,10,3,9,8,6,4,2,1,11,10,13,7,8,15,9,12,5,6,3,0,14,11,8,12,7,1,14,2,13,6,15,0,9,10,4,5,3),
        intArrayOf(12,1,10,15,9,2,6,8,0,13,3,4,14,7,5,11,10,15,4,2,7,12,9,5,6,1,13,14,0,11,3,8,9,14,15,5,2,8,12,3,7,0,4,10,1,13,11,6,4,3,2,12,9,5,15,10,11,14,1,7,6,0,8,13),
        intArrayOf(4,11,2,14,15,0,8,13,3,12,9,7,5,10,6,1,13,0,11,7,4,9,1,10,14,3,5,12,2,15,8,6,1,4,11,13,12,3,7,14,10,15,6,8,0,5,9,2,6,11,13,8,1,4,10,7,9,5,0,15,14,2,3,12),
        intArrayOf(13,2,8,4,6,15,11,1,10,9,3,14,5,0,12,7,1,15,13,8,10,3,7,4,12,5,6,11,0,14,9,2,7,11,4,1,9,12,14,2,0,6,10,13,15,3,5,8,2,1,14,7,4,10,8,13,15,12,9,0,3,5,6,11),
    )
}
