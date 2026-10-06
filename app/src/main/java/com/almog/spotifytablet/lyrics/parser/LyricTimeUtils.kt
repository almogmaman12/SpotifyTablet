package com.almog.spotifytablet.lyrics.parser

import java.util.regex.Pattern

/**
 * Unified timestamp parsing utility across all lyric formats:
 * - TTML/SMPTE: `00:01:23.456`, `01:23.45`, `83.456s`, `12345ms`
 * - LRC: `[01:23.45]`, `<01:23.450>`
 * - Raw decimal seconds: `12.345`
 */
object LyricTimeUtils {

    private val TIME_COLON_REGEX = Pattern.compile("^(?:(\\d{1,2}):)?(\\d{1,2}):(\\d{2})(?:\\.(\\d{1,3}))?$")

    fun parseTime(timeStr: String): Long? {
        val clean = timeStr.trim()
        if (clean.isEmpty()) return null

        try {
            // Milliseconds format: "12345ms"
            if (clean.endsWith("ms", ignoreCase = true)) {
                return clean.substring(0, clean.length - 2).trim().toDoubleOrNull()?.toLong()
            }

            // Seconds format: "83.456s" or "83s"
            if (clean.endsWith("s", ignoreCase = true)) {
                val seconds = clean.substring(0, clean.length - 1).trim().toDoubleOrNull() ?: return null
                return (seconds * 1000.0).toLong()
            }

            // Frame format e.g. "120f" (assuming 30fps baseline)
            if (clean.endsWith("f", ignoreCase = true)) {
                val frames = clean.substring(0, clean.length - 1).trim().toDoubleOrNull() ?: return null
                return (frames * (1000.0 / 30.0)).toLong()
            }

            // Clock ticks format e.g. "1000t" (assuming 1000 ticks/sec or SMPTE tick standard)
            if (clean.endsWith("t", ignoreCase = true)) {
                val ticks = clean.substring(0, clean.length - 1).trim().toDoubleOrNull() ?: return null
                return ticks.toLong()
            }

            // Colon timestamp: "00:01:23.456", "01:23.45", or SMPTE "00:01:23:15"
            if (clean.contains(":")) {
                val matcher = TIME_COLON_REGEX.matcher(clean)
                if (matcher.matches()) {
                    val hoursStr = matcher.group(1)
                    val minutesStr = matcher.group(2) ?: "0"
                    val secondsStr = matcher.group(3) ?: "0"
                    val fracStr = matcher.group(4)

                    val hours = hoursStr?.toLongOrNull() ?: 0L
                    val minutes = minutesStr.toLongOrNull() ?: 0L
                    val seconds = secondsStr.toLongOrNull() ?: 0L

                    val fracMs = if (fracStr != null) {
                        val digits = fracStr.length
                        val fracVal = fracStr.toLongOrNull() ?: 0L
                        (fracVal * (1000.0 / Math.pow(10.0, digits.toDouble()))).toLong()
                    } else 0L

                    return (hours * 3_600_000L) + (minutes * 60_000L) + (seconds * 1000L) + fracMs
                }

                // Fallback manual split supporting SMPTE HH:mm:ss:ff or standard min:sec:frac
                val parts = clean.split(":")
                return when (parts.size) {
                    2 -> {
                        val min = parts[0].toLongOrNull() ?: 0L
                        val sec = parts[1].toDoubleOrNull() ?: 0.0
                        (min * 60_000L) + (sec * 1000.0).toLong()
                    }
                    3 -> {
                        val hr = parts[0].toLongOrNull() ?: 0L
                        val min = parts[1].toLongOrNull() ?: 0L
                        val sec = parts[2].toDoubleOrNull() ?: 0.0
                        (hr * 3_600_000L) + (min * 60_000L) + (sec * 1000.0).toLong()
                    }
                    4 -> {
                        // SMPTE HH:mm:ss:ff (frames at 30fps)
                        val hr = parts[0].toLongOrNull() ?: 0L
                        val min = parts[1].toLongOrNull() ?: 0L
                        val sec = parts[2].toLongOrNull() ?: 0L
                        val frames = parts[3].toDoubleOrNull() ?: 0.0
                        (hr * 3_600_000L) + (min * 60_000L) + (sec * 1000L) + (frames * (1000.0 / 30.0)).toLong()
                    }
                    else -> null
                }
            }

            return null
        } catch (_: Exception) {
            return null
        }
    }
}
