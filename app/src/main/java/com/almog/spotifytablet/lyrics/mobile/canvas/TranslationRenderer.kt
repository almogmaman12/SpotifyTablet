package com.almog.spotifytablet.lyrics.mobile.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import com.almog.spotifytablet.lyrics.mobile.RenderConfig
import com.almog.spotifytablet.lyrics.mobile.animation.LineAnimState
import com.almog.spotifytablet.lyrics.mobile.animation.AppleMusicMotion

/** Whole-line text follows its parent's state, without inventing translated word timings. */
internal fun DrawScope.drawTranslationText(
    layout: LineLayout, animation: LineAnimState, startX: Float, scroll: Float, y: Float,
    config: RenderConfig, static: Boolean, replacement: Boolean = false,
) {
    val plan = lyricPaintPlan(animation.state, animation.isBackground, animation.opacity, animation.blur, config, lit = animation.lit)
    val alpha = when {
        static -> animation.opacity
        plan is LyricPaintPlan.InactiveShadow -> plan.alpha
        config.isAppleMusic -> (if (animation.isBackground) AppleMusicMotion.BACKGROUND_BRIGHT_ALPHA else AppleMusicMotion.BRIGHT_ALPHA) * animation.opacity
        else -> (if (animation.isBackground) 0.6f else config.gradientAlphaBright) * animation.opacity
    }
    val blur = if (static || plan !is LyricPaintPlan.InactiveShadow) 0f else plan.blurRadius
    fun draw(result: TextLayoutResult, offset: Offset, dimmer: Float) {
        val opacity = (alpha * dimmer).coerceIn(0f, 1f)
        val size = with(result.layoutInput.density) { result.layoutInput.style.fontSize.toPx() }
        // Blur is a separate pass, scaled by the actual lyric font size rather than density.
        if (blur > 0f && config.distanceBlurEnabled) {
            drawText(result, color = Color.Transparent, topLeft = offset,
                shadow = Shadow(Color.White.copy(alpha = opacity), blurRadius = blur * size / 56f))
            drawText(result, color = Color.Transparent, shadow = Shadow.None, topLeft = offset)
        } else drawText(result, color = Color.White.copy(alpha = opacity), shadow = Shadow.None, topLeft = offset)
    }
    withTransform({
        scale(animation.scale, animation.scale, Offset(
            if (layout.isRightAligned) startX + layout.totalWidth else startX, y + scroll + layout.height / 2f,
        ))
    }) {
        if (replacement) layout.words.forEach { draw(it.textLayoutResult, Offset(startX, y + scroll) + it.relativeOffset, 1f) }
        layout.supplements.forEach { supplement ->
            val offset = Offset(startX, y + scroll) + supplement.offset
            if (!static && animation.isActive && supplement.words.isNotEmpty()) {
                supplement.words.forEach words@ { word ->
                    val state = animation.wordStates.getOrNull(word.sourceWordIndex) ?: return@words
                    val width = word.textLayoutResult.size.width.toFloat()
                    val size = with(word.textLayoutResult.layoutInput.density) { word.textLayoutResult.layoutInput.style.fontSize.toPx() }
                    val (position, band) = if (config.isAppleMusic)
                        appleWipe(state.gradientPosition, word.fullWordWidth, size, word.word.text)
                    else state.gradientPosition to if (config.isSimple) 30f else 0f
                    val dim = when {
                        config.isAppleMusic && animation.isBackground -> AppleMusicMotion.BACKGROUND_DIM_ALPHA
                        config.isAppleMusic -> AppleMusicMotion.DIM_ALPHA
                        animation.isBackground -> 0.3f
                        else -> config.gradientAlphaDim
                    } * animation.opacity * 0.65f
                    drawWipeText(word.textLayoutResult, offset.x + word.relativeOffset.x, offset.y + word.relativeOffset.y,
                        width, word.fullWordWidth, word.startXOffset, position, alpha * 0.65f, dim,
                        shadow = null, rtl = supplement.isRtl, gradientOffsetPercent = band)
                }
            } else draw(supplement.text, offset, 0.65f)
        }
    }
}
