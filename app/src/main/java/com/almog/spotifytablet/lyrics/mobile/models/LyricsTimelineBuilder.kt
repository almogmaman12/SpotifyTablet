package com.almog.spotifytablet.lyrics.mobile.models

private const val NORMAL_INTERLUDE_THRESHOLD_MS = 3_000L
private const val MINIMAL_INTERLUDE_THRESHOLD_MS = 5_000L

/**
 * Builds the presentation timeline from normalized vocal lines, which arrive in document order:
 * each lead line followed by its background lines. That order is kept: a background vocal leading into its line starts before that line, but it still
 * belongs under it, never above it.
 *
 * Between lines the gap is measured on the
 * lead lines' times (which its parser stretches over their background vocals). The intro runs
 * from 0 to the song's StartTime, which the Spicy Lyrics API sets to the first lead syllable,
 * not the first line: in Bologna 2 the first line starts at 3.1s for a background "Pluh" but
 * the song's StartTime is 12.4s, when the lead vocal comes in. TTML carries no song StartTime,
 * so it is taken from the first lead line's own words.
 *
 * [holdThroughShortGaps] stretches each line's end over short gaps; it's on in
 * Minimal Lyrics Mode for word-synced lyrics and in Simple Lyrics Mode for line-synced ones.
 */
fun buildDisplayTimeline(lines: List<Line>, minimalMode: Boolean, holdThroughShortGaps: Boolean = false): List<Line> {
    if (lines.isEmpty()) return emptyList()

    val threshold = if (minimalMode) MINIMAL_INTERLUDE_THRESHOLD_MS else NORMAL_INTERLUDE_THRESHOLD_MS
    if (lines.none { it.role == LineRole.LEAD }) return lines

    val leads = lines.filter { it.role == LineRole.LEAD }
    val timeline = ArrayList<Line>(lines.size + 8)
    var previousLead: Line? = null
    var leadIndex = 0
    for (line in lines) {
        var shown = line
        if (line.role == LineRole.LEAD) {
            val gapStart = previousLead?.endMs ?: 0L
            val gapEnd = if (previousLead == null) line.words.minOfOrNull { it.startMs } ?: line.startMs else line.startMs
            if (gapEnd - gapStart >= threshold) {
                timeline += Line(words = emptyList(), startMs = gapStart, endMs = gapEnd, role = LineRole.INTERLUDE)
            }
            previousLead = line
            // The applyers' lineEndTime: a lead stays Active up to the next lead when the gap to it
            // is shorter than an interlude, so no line sits between Sung and Active.
            val next = leads.getOrNull(++leadIndex)
            if (holdThroughShortGaps && next != null && next.startMs > line.endMs && next.startMs - line.endMs < threshold) {
                shown = line.copy(endMs = next.startMs)
            }
        }
        timeline += shown
    }
    return timeline
}

/** How much earlier interludes end: (preHiddenDotLineMs + 50) * -1. */
private const val INTERLUDE_TIME_PADDING_MS = -550.0

/**
 * The three interlude dots' [start, end) times: each fills a third of the gap, shifted earlier so the last dot finishes 550ms
 * before the next line (the dot line itself hides 500ms before it).
 */
fun interludeDotTimes(startMs: Long, endMs: Long): List<Pair<Long, Long>> {
    val total = (endMs - startMs).toDouble()
    val base = total / 3.0
    val padding = INTERLUDE_TIME_PADDING_MS / 3.0
    val dot1 = maxOf(startMs.toDouble(), startMs + base + padding)
    val dot2 = maxOf(dot1, startMs + base * 2 + padding * 2)
    val dot3 = maxOf(dot2, startMs + total + INTERLUDE_TIME_PADDING_MS)
    val ends = listOf(dot1, dot2, dot3).map { Math.round(it) }
    return listOf(startMs to ends[0], ends[0] to ends[1], ends[1] to ends[2])
}
