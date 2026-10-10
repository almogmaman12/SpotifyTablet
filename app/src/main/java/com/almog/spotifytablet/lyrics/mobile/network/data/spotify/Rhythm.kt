package com.almog.spotifytablet.lyrics.mobile.network.data.spotify

import okio.ByteString.Companion.decodeBase64
import java.util.zip.Inflater

/**
 * Where sounds start in a track, per frequency band: the analysis's `rhythmstring`. Eight bands,
 * low to high, each a sorted list of onset times in seconds, to about 3 ms.
 *
 * A snare, a clap or an acoustic kick starts a sound in nearly every band at once; a bass note, a
 * hi-hat, a sung word or a produced kick (an 808) starts one in one or two. So [hits] (onsets
 * grouped across the bands) and which bands each reaches say what was struck and how hard, which
 * the segments' loudness alone can't.
 *
 * [raw] is kept as it came, for the disk cache.
 */
class Rhythm private constructor(val raw: String, val bands: List<FloatArray>) {
    /** Onsets in the same moment across the bands: [bands] is a bit per band reached, low band first. */
    class Hit(val at: Float, val bands: Int) {
        val bandCount get() = Integer.bitCount(bands)
    }

    /** Every onset grouped into hits, in time order. */
    val hits: List<Hit> by lazy {
        val onsets = bands.flatMapIndexed { band, times -> times.map { it to band } }.sortedBy { it.first }
        val out = ArrayList<Hit>()
        var at = Float.NEGATIVE_INFINITY
        var mask = 0
        for ((t, band) in onsets) {
            if (t - at < SAME_HIT) {
                mask = mask or (1 shl band)
            } else {
                if (mask != 0) out += Hit(at, mask)
                at = t
                mask = 1 shl band
            }
        }
        if (mask != 0) out += Hit(at, mask)
        out
    }

    companion object {
        /** Onsets this close across bands are one hit, in seconds. */
        private const val SAME_HIT = 0.025f

        /**
         * Decodes a `rhythmstring`: base64 (either alphabet) of zlib-packed text, "sample rate,
         * step, band count", then per band its onset count and the onsets as steps after the last.
         * Null when it doesn't read.
         */
        fun parse(rhythmString: String): Rhythm? = runCatching {
            val packed = rhythmString.decodeBase64()!!.toByteArray()
            val text = Inflater().run {
                setInput(packed)
                val out = java.io.ByteArrayOutputStream(packed.size * 4)
                val buffer = ByteArray(16 * 1024)
                while (!finished()) {
                    val n = inflate(buffer)
                    if (n == 0 && (needsInput() || needsDictionary())) break
                    out.write(buffer, 0, n)
                }
                end()
                out.toString(Charsets.US_ASCII.name())
            }
            val numbers = text.trim().split(' ').map { it.toLong() }
            val sampleRate = numbers[0]
            val stepSeconds = numbers[1].toDouble() / sampleRate
            val bandCount = numbers[2].toInt()
            var i = 3
            val bands = List(bandCount) {
                val count = numbers[i++].toInt()
                var steps = 0L
                FloatArray(count) { steps += numbers[i++]; (steps * stepSeconds).toFloat() }
            }
            Rhythm(rhythmString, bands)
        }.getOrNull()
    }
}
