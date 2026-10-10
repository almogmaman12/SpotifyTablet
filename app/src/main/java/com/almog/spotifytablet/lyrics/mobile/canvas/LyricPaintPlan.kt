package com.almog.spotifytablet.lyrics.mobile.canvas

import com.almog.spotifytablet.lyrics.mobile.RenderConfig
import com.almog.spotifytablet.lyrics.mobile.animation.AppleMusicMotion
import com.almog.spotifytablet.lyrics.mobile.animation.ElementState

internal sealed interface LyricPaintPlan {
    data object ActiveGradient : LyricPaintPlan
    data class InactiveShadow(val alpha: Float, val blurRadius: Float) : LyricPaintPlan
}

internal fun lyricPaintPlan(
    state: ElementState,
    isBackground: Boolean,
    opacity: Float,
    blurRadius: Float,
    config: RenderConfig,
    /** The NotSung colour: a word's `--gradient-alpha-end`, or a whole line's in line-synced lyrics. */
    dimAlpha: Float = config.gradientAlphaDim,
    /** The Apple Music style: how lit a just-ended line still is (LineAnimState.lit). */
    lit: Float = 0f,
): LyricPaintPlan {
    if (state == ElementState.Active) return LyricPaintPlan.ActiveGradient
    val stateAlpha = when {
        // The Apple Music style dims every line but the active one alike, sung or not.
        config.isAppleMusic -> {
            val dim = if (isBackground) AppleMusicMotion.BACKGROUND_DIM_ALPHA else AppleMusicMotion.DIM_ALPHA
            val bright = if (isBackground) AppleMusicMotion.BACKGROUND_BRIGHT_ALPHA else AppleMusicMotion.BRIGHT_ALPHA
            dim + (bright - dim) * lit
        }
        isBackground && state == ElementState.NotSung -> 0.3f
        isBackground -> 0.6f
        state == ElementState.NotSung -> dimAlpha
        else -> config.gradientAlphaBright
    }
    return LyricPaintPlan.InactiveShadow(
        alpha = (stateAlpha * opacity).coerceIn(0f, 1f),
        blurRadius = blurRadius.coerceAtLeast(0f),
    )
}
