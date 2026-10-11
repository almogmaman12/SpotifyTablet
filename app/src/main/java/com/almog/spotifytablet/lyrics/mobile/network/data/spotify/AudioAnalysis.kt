package com.almog.spotifytablet.lyrics.mobile.network.data.spotify

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * The parts of Spotify's audio analysis the background and the music haptics follow: the track's
 * tempo and loudness, its sections, beats and bars, the loudness of its segments (Spotify's short
 * sound events, a note or a hit each), and its [Rhythm]: where sounds start, per frequency band.
 * Times are in seconds, loudness in dB.
 */
class AudioAnalysis(
    val tempo: Float,
    val loudness: Float,
    val sections: List<Section>,
    val beats: List<Beat>,
    /** When each bar starts: its first beat, the downbeat. */
    val bars: List<Float> = emptyList(),
    val segments: List<Segment> = emptyList(),
    val rhythm: Rhythm? = null,
) {
    class Section(val start: Float, val duration: Float, val loudness: Float, val tempo: Float)
    class Beat(val start: Float, val duration: Float, val confidence: Float)

    /**
     * A segment's loudness as it starts and at its peak, when the peak comes, and three of its
     * timbre coefficients (unscaled, only comparable within one track): [brightness] (the second,
     * high against low frequencies), and the fifth and tenth, which with it tell a snare from a
     * kick (a snare sits higher on the first two and lower on the last).
     */
    class Segment(
        val start: Float,
        val loudnessStart: Float,
        val loudnessMax: Float,
        val peakAt: Float,
        val brightness: Float = 0f,
        val timbre4: Float = 0f,
        val timbre9: Float = 0f,
    )

    /**
     * A compact copy for the disk cache: the full analysis runs to ~300 KB, mostly segment
     * timbre and pitches and tatums nobody here reads. Built by hand rather than through Gson's
     * reflection, which R8's renaming would break.
     */
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("tempo", tempo)
        addProperty("loudness", loudness)
        add("sections", JsonArray().apply {
            sections.forEach { s -> add(numbers(s.start, s.duration, s.loudness, s.tempo)) }
        })
        add("beats", JsonArray().apply {
            beats.forEach { b -> add(numbers(b.start, b.duration, b.confidence)) }
        })
        add("bars", numbers(*bars.toFloatArray()))
        add("segments", JsonArray().apply {
            segments.forEach { g ->
                add(numbers(g.start, g.loudnessStart, g.loudnessMax, g.peakAt, g.brightness, g.timbre4, g.timbre9))
            }
        })
        addProperty(RHYTHM_KEY, rhythm?.raw ?: "")
    }

    companion object {
        /** Spotify's own analysis document, or null when it lacks the track, sections or beats. */
        fun fromSpotify(root: JsonObject): AudioAnalysis? = runCatching {
            val track = root.getAsJsonObject("track") ?: return null
            val sections = root.getAsJsonArray("sections") ?: return null
            val beats = root.getAsJsonArray("beats") ?: return null
            AudioAnalysis(
                tempo = track.float("tempo"),
                loudness = track.float("loudness"),
                sections = sections.map { e ->
                    val o = e.asJsonObject
                    Section(o.float("start"), o.float("duration"), o.float("loudness"), o.float("tempo"))
                },
                beats = beats.map { e ->
                    val o = e.asJsonObject
                    Beat(o.float("start"), o.float("duration"), o.float("confidence"))
                },
                bars = root.getAsJsonArray("bars")?.map { it.asJsonObject.float("start") }.orEmpty(),
                segments = root.getAsJsonArray("segments")?.map { e ->
                    val o = e.asJsonObject
                    val start = o.float("start")
                    val timbre = o.getAsJsonArray("timbre")
                    fun timbre(i: Int) = timbre?.takeIf { it.size() > i }?.get(i)?.asFloat ?: 0f
                    Segment(
                        start, o.float("loudness_start"), o.float("loudness_max"), start + o.float("loudness_max_time"),
                        brightness = timbre(1), timbre4 = timbre(4), timbre9 = timbre(9),
                    )
                }.orEmpty(),
                rhythm = track.get("rhythmstring")?.takeUnless(JsonElement::isJsonNull)?.asString?.let(Rhythm::parse),
            )
        }.getOrNull()

        /** Reads what [toJson] wrote; null for a copy saved before it kept the rhythm, so it's fetched again. */
        fun fromJson(root: JsonObject): AudioAnalysis? = runCatching {
            val rhythm = root.get(RHYTHM_KEY)?.asString ?: return null
            AudioAnalysis(
                tempo = root.get("tempo").asFloat,
                loudness = root.get("loudness").asFloat,
                sections = root.getAsJsonArray("sections").map { e ->
                    val a = e.asJsonArray
                    Section(a[0].asFloat, a[1].asFloat, a[2].asFloat, a[3].asFloat)
                },
                beats = root.getAsJsonArray("beats").map { e ->
                    val a = e.asJsonArray
                    Beat(a[0].asFloat, a[1].asFloat, a[2].asFloat)
                },
                bars = root.getAsJsonArray("bars").map { it.asFloat },
                segments = root.getAsJsonArray("segments").map { e ->
                    val a = e.asJsonArray
                    Segment(a[0].asFloat, a[1].asFloat, a[2].asFloat, a[3].asFloat, a[4].asFloat, a[5].asFloat, a[6].asFloat)
                },
                rhythm = rhythm.takeIf { it.isNotEmpty() }?.let(Rhythm::parse),
            )
        }.getOrNull()

        /** Older copies lack it (and kept other timbre), so they're read as missing. */
        private const val RHYTHM_KEY = "rhythm"

        private fun JsonObject.float(key: String): Float = get(key)?.takeUnless(JsonElement::isJsonNull)?.asFloat ?: 0f

        /** Rounded to the millisecond (or thousandth of a dB), which is all anything here needs. */
        private fun numbers(vararg values: Float) = JsonArray().apply {
            values.forEach { add(Math.round(it * 1000.0) / 1000.0) }
        }
    }
}
