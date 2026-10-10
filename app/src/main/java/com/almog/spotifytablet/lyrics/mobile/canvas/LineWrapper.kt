package com.almog.spotifytablet.lyrics.mobile.canvas

/**
 * Where a lyric line wraps, matching how a browser lays out the same line.
 *
 * Word-synced lines are a `flex-wrap: wrap` row of word elements. Syllables written without a
 * space between them sit in a `.word-group` (`white-space: nowrap`), so a group moves to the next
 * row whole. Only a group wider than a whole row breaks inside itself; it then starts on its own
 * row, and whatever follows starts a new row too, since the group takes the full width.
 *
 * Line-synced and static lines are plain text ([textMode]): breaks at spaces and between CJK
 * characters, but not before closing punctuation or after opening brackets.
 */
internal object LineWrapper {

    /** One laid-out piece of a line: a word, syllable, or character. */
    data class Piece(val width: Float, val text: String, val gluedToPrevious: Boolean)

    /** Closing punctuation a line of CJK text never starts with (browser line-breaking rules). */
    private const val NO_BREAK_BEFORE = "、。，．・：；？！゛゜ヽヾゝゞ々）］｝」』】〕〉》〙〗〟’”｠»,.!?)]}…‥"
    /** Opening brackets a line never ends with. */
    private const val NO_BREAK_AFTER = "（［｛「『【〔〈《〘〖〝‘“｟«([{"

    /**
     * @param wordGap space between words on a row.
     * @param trailingGap space a word also needs after itself to fit (a left-aligned word
     *   element's `::after` margin; 0 for column-gap or plain text).
     * @return row start indices, then [pieces].size.
     */
    fun breaks(
        pieces: List<Piece>,
        maxWidth: Float,
        wordGap: Float,
        trailingGap: Float,
        textMode: Boolean,
    ): List<Int> {
        val n = pieces.size
        val lineBreaks = mutableListOf(0)
        if (n == 0) return lineBreaks.apply { add(0) }

        // Whether each piece's glued run (its word group) fits a row by itself.
        val runFits = BooleanArray(n)
        var runStart = 0
        var runWidth = 0f
        for (idx in 0 until n) {
            if (idx > 0 && !pieces[idx].gluedToPrevious) {
                val fits = runWidth + trailingGap <= maxWidth
                for (k in runStart until idx) runFits[k] = fits
                runStart = idx
                runWidth = 0f
            }
            runWidth += pieces[idx].width
        }
        for (k in runStart until n) runFits[k] = runWidth <= maxWidth

        fun endsRun(idx: Int) = idx == n - 1 || !pieces[idx + 1].gluedToPrevious

        fun textBreakBefore(idx: Int): Boolean {
            val piece = pieces[idx]
            if (!piece.gluedToPrevious) return true
            val prev = pieces[idx - 1].text.lastOrNull() ?: return true
            val cur = piece.text.firstOrNull() ?: return true
            if (cur in NO_BREAK_BEFORE || prev in NO_BREAK_AFTER) return false
            // A Latin word longer than a row breaks anywhere rather than running off-screen.
            return LyricsLayoutCalculator.isCjk(prev) || LyricsLayoutCalculator.isCjk(cur) || !runFits[idx]
        }

        var rowWidth = 0f
        var lastBreakCandidate = 0
        var i = 0
        while (i < n) {
            val piece = pieces[i]
            val startsRun = i == 0 || !piece.gluedToPrevious
            val oversizedRunStarts = !textMode && startsRun && !runFits[i]
            val oversizedRunEnded = !textMode && startsRun && i > 0 && !runFits[i - 1]
            if (rowWidth > 0f && (oversizedRunStarts || oversizedRunEnded)) {
                lineBreaks.add(i)
                lastBreakCandidate = i
                rowWidth = 0f
                continue
            }

            val gap = if (rowWidth > 0f && !piece.gluedToPrevious) wordGap else 0f
            // Inside a group that fits a row, no break; inside an oversized one, anywhere.
            val breakable = if (textMode) i == 0 || textBreakBefore(i) else startsRun || !runFits[i]
            if (breakable) lastBreakCandidate = i
            val trailing = if (endsRun(i) && i < n - 1) trailingGap else 0f

            if (rowWidth + gap + piece.width + trailing > maxWidth && rowWidth > 0f) {
                val breakIdx = if (lastBreakCandidate > lineBreaks.last()) lastBreakCandidate else i
                lineBreaks.add(breakIdx)
                i = breakIdx
                rowWidth = 0f
            } else {
                rowWidth += gap + piece.width
                i++
            }
        }
        lineBreaks.add(n)
        return lineBreaks
    }
}
