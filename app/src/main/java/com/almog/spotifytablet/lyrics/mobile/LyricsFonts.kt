package com.almog.spotifytablet.lyrics.mobile

import android.content.Context
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.almog.spotifytablet.Constants
import com.almog.spotifytablet.R
import com.almog.spotifytablet.lyrics.mobile.canvas.LyricsLayoutCalculator

/**
 * The fonts offered for Latin-script lyrics (Hebrew, Arabic, Georgian, CJK keep their own).
 * All are open-licensed (SIL OFL) and bundled. `recommendedSp` is the "Lyrics size" that looks
 * balanced for each, as fonts differ in x-height and width.
 */
object LyricsFonts {
    const val PREF_LYRICS_FONT = "lyrics_font"
    const val DEFAULT_KEY = "system"
    /** Translation and romanization line size, percent of the default (50–150). */
    const val PREF_NOTE_SIZE = "lyrics_note_size_pct"

    class Option(val key: String, val label: String, val resId: Int?, val recommendedSp: Int, val note: String)

    @JvmField
    val OPTIONS: List<Option> = listOf(
        Option("system", "System default (Roboto)", null, 32, "what the tablet uses"),
        Option("inter", "Inter", R.font.inter_variable, 32, "closest to Apple's SF"),
        Option("dm_sans", "DM Sans", R.font.dm_sans_variable, 34, "rounder, runs a bit small"),
        Option("jakarta", "Plus Jakarta Sans", R.font.plus_jakarta_sans_variable, 30, "wide and modern, runs large"),
        Option("figtree", "Figtree", R.font.figtree_variable, 33, "friendly and compact"),
    )

    @JvmStatic
    fun find(key: String?): Option = OPTIONS.firstOrNull { it.key == key } ?: OPTIONS[0]

    @JvmStatic
    fun names(): Array<String> = OPTIONS.map { "${it.label} — ${it.recommendedSp} sp, ${it.note}" }.toTypedArray()

    @JvmStatic
    fun keys(): Array<String> = OPTIONS.map { it.key }.toTypedArray()

    /** Reads the saved choice and hands it to the layout code. Cheap; safe to call repeatedly. */
    @JvmStatic
    fun apply(context: Context) {
        val prefs = context.getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE)
        val note = prefs.getInt(PREF_NOTE_SIZE, 100)
        if (LyricsLayoutCalculator.noteScalePct != note) LyricsLayoutCalculator.setNoteScale(note)
        val key = prefs.getString(PREF_LYRICS_FONT, DEFAULT_KEY)
        val option = find(key)
        if (LyricsLayoutCalculator.fontKey == option.key) return
        LyricsLayoutCalculator.setFont(option.resId?.let { family(it) }, option.key)
    }

    @OptIn(ExperimentalTextApi::class)
    private fun family(resId: Int) = FontFamily(
        listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { weight ->
            Font(resId, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))
        },
    )
}
