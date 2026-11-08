package com.almog.spotifytablet.lyrics.mobile.canvas

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * The cover's accent color for the lyrics: the sung part of the active line and its glow lean towards it
 * (0 means plain white, exactly as before). It is Compose state, so the canvas redraws when it changes.
 * Stored as a plain ARGB Int so Java can set it.
 */
object LyricsTheme {
    var accentArgb: Int by mutableStateOf(0)

    /** The sung text: white with a clear tint of the accent, so it stays easy to read on the dark background. */
    fun text(strength: Float = 1f): Color = accentArgb.let {
        if (it == 0 || strength <= 0f) Color.White else lerp(Color.White, Color(it), 0.55f * strength.coerceAtMost(1f))
    }

    /** The glow around the active words. */
    fun glow(): Color = accentArgb.let { if (it == 0) Color.White else lerp(Color.White, Color(it), 0.8f) }
}
