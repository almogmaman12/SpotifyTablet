package com.almog.spotifytablet.lyrics.mobile.core

data class TimedWord(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val attached: Boolean,
    /** Source-supplied romanization (TTML); on-device romanization fills gaps later. */
    val romanized: String? = null,
)

data class TimedLine(
    val startMs: Long,
    val endMs: Long,
    val words: List<TimedWord>,
    val role: com.almog.spotifytablet.lyrics.mobile.models.LineRole = com.almog.spotifytablet.lyrics.mobile.models.LineRole.LEAD,
    val groupId: Int? = null,
    val agent: String? = null,
    val oppositeAligned: Boolean = false,
)

sealed interface LyricsState {
    data object Idle : LyricsState
    data object Loading : LyricsState
    data class Ready(
        val lines: List<TimedLine>,
        val source: String?,
        val maker: com.almog.spotifytablet.lyrics.mobile.network.data.LyricsContributor?,
        val uploader: com.almog.spotifytablet.lyrics.mobile.network.data.LyricsContributor?,
        val songwriters: List<String>,
        val provider: String = "Spicy Lyrics",
        val lyricsType: com.almog.spotifytablet.lyrics.mobile.models.LyricsType = com.almog.spotifytablet.lyrics.mobile.models.LyricsType.Syllable,
        /** The source brought its own romanization (TTML), which a human one from Genius doesn't replace. */
        val sourceRomanized: Boolean = false,
    ) : LyricsState
    /** [message] is the headline, [detail] the smaller line under it (which sources, which codes). */
    data class Error(val message: String, val detail: String? = null) : LyricsState
}
