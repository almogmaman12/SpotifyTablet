package com.almog.spotifytablet.lyrics.mobile.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import com.almog.spotifytablet.lyrics.mobile.RenderConfig
import com.almog.spotifytablet.lyrics.mobile.animation.AppleMusicMotion
import com.almog.spotifytablet.lyrics.mobile.animation.LineAnimState
import com.almog.spotifytablet.lyrics.mobile.animation.WordAnimState

/**
 * Draws a text fragment with a moving left-to-right gradient "wipe" (the karaoke fill), in a
 * single pass. This mirrors the original's exact CSS model:
 * `linear-gradient(bright stop1%, dim stop2%)` where `stop1 = gradientPositionPercent` and
 * `stop2 = stop1 + 20`, both expressed as percentages of [fullWidth] — NOT renormalized to a
 * plain 0..1 range. That distinction matters: the position sweeps from -20% to
 * 100% (a 120-point range), so for roughly the first ~17% and last ~17% of a syllable's own
 * timing window the transition band sits entirely off the visible text (uniformly dim, then
 * uniformly bright) before/after actually crossing it — which is what gives the wipe its feel
 * instead of stretching the wipe evenly across the whole syllable.
 *
 * @param gradientPositionPercent the raw position value (e.g. -20 at NotSung, 100 at Sung),
 *   in percent of [fullWidth].
 * @param fullWidth total width the gradient's percentages are relative to (whole word across
 *   rows, whole line, or — for a standalone letter — that letter's own width).
 * @param startXOffset this fragment's offset within [fullWidth].
 */
internal fun DrawScope.drawWipeText(
    layoutResult: TextLayoutResult,
    xPos: Float,
    yPos: Float,
    fragmentWidth: Float,
    fullWidth: Float,
    startXOffset: Float,
    gradientPositionPercent: Float,
    brightAlpha: Float,
    dimAlpha: Float,
    shadow: Shadow?,
    rtl: Boolean = false,
    gradientOffsetPercent: Float = 0f,
) {
    val bright = Color.White.copy(alpha = brightAlpha.coerceIn(0f, 1f))
    val dim = Color.White.copy(alpha = dimAlpha.coerceIn(0f, 1f))
    val topLeft = Offset(xPos, yPos)

    val fw = fullWidth.coerceAtLeast(1f)
    val w = fragmentWidth.coerceAtLeast(1f)
    // Reference (Mixed.css): stop2 = stop1 + 20% + --gradient-offset. The offset is 0 in Full
    // mode but 30% for Simple-mode words/letters, which widens the fade band from 20% to 50% so
    // the sweep reaches the word's trailing edge smoothly instead of snapping the last ~30% from
    // dim straight to bright at the Active→Sung flip.
    val stop1Frac = gradientPositionPercent / 100f
    val stop2Frac = (gradientPositionPercent + 20f + gradientOffsetPercent) / 100f
    // RTL mirrors the whole sweep: bright grows from the right instead of the left, so both the
    // band's position and its colour order flip.
    val loFrac = if (rtl) 1f - stop2Frac else stop1Frac
    val hiFrac = if (rtl) 1f - stop1Frac else stop2Frac
    val leftColor = if (rtl) dim else bright
    val rightColor = if (rtl) bright else dim

    // NO solid-color fast path for off-band fragments: Skia renders the same nominal alpha
    // slightly darker through a gradient shader than through solid color paint, so a NotSung
    // word drawn solid next to an active word's shader-drawn dim tail visibly mismatches even
    // at identical alpha values (the artifact the old dimBoostFor workaround papered over).
    // Every word goes through the same brush path; the clamped stops below degenerate to a
    // uniform color when the band lies entirely off this fragment.
    // Map the (possibly off-fragment) stop positions into the fragment's own 0..1 local space.
    val localLo = ((loFrac * fw) - startXOffset) / w
    val localHi = ((hiFrac * fw) - startXOffset) / w

    // The colour at any local x is the band's linear interpolation, clamped at the ends —
    // exactly what the CSS gradient shows when part of the band lies outside the box. When a
    // word has just started (band mostly off the left edge) its left edge must show the
    // mid-fade value and build up gradually, NOT snap to full bright: keeping the endpoint
    // colour at a clamped stop makes the word's tip flash and the rest look darkened.
    val bandW = (localHi - localLo).coerceAtLeast(1e-4f)
    fun colorAt(x: Float): Color =
        androidx.compose.ui.graphics.lerp(leftColor, rightColor, ((x - localLo) / bandW).coerceIn(0f, 1f))

    val s0 = localLo.coerceIn(0.0001f, 0.9997f)
    val s1 = localHi.coerceIn(s0 + 0.0001f, 0.9998f)
    // IMPORTANT: drawText(textLayoutResult, brush) evaluates the brush in TEXT-LOCAL
    // coordinates (the canvas is translated to topLeft before the shader is applied), so the
    // gradient must span [0, w] — NOT absolute canvas x. Using absolute coords shifts the
    // band off the glyphs entirely and the clamped shader floods the word with one color.
    val brush = Brush.horizontalGradient(
        0f to colorAt(0f),
        s0 to colorAt(s0),
        s1 to colorAt(s1),
        1f to colorAt(1f),
        startX = 0f,
        endX = w,
    )
    drawGlowThenText(layoutResult, topLeft, shadow) { drawGradientText(layoutResult, brush, topLeft) }
}

/**
 * Fills the text with [brush] (in text-local coordinates). Colour emoji need a mask: Skia draws
 * a colour glyph's own pixels and ignores the shader, so a lit word would flip from the white
 * silhouette an inactive line shows to the real emoji. The reference paints lyrics with
 * `background-clip: text`, where an emoji is only a shape the gradient shows through, so words
 * with emoji draw their glyphs into a layer and keep only the brush where they cover (SrcIn).
 */
private fun DrawScope.drawGradientText(layoutResult: TextLayoutResult, brush: Brush, topLeft: Offset) {
    if (!layoutResult.layoutInput.text.text.hasEmoji()) {
        drawText(layoutResult, brush = brush, shadow = Shadow.None, topLeft = topLeft)
        return
    }
    val w = layoutResult.size.width.toFloat()
    val h = layoutResult.size.height.toFloat()
    // Glyphs can reach past the layout box; the layer and fill cover that overhang.
    val pad = h / 2f
    translate(topLeft.x, topLeft.y) {
        drawIntoCanvas { it.saveLayer(Rect(-pad, -pad, w + pad, h + pad), Paint()) }
        drawText(layoutResult, color = Color.White, shadow = Shadow.None)
        drawRect(brush, topLeft = Offset(-pad, -pad), size = Size(w + 2 * pad, h + 2 * pad), blendMode = BlendMode.SrcIn)
        drawIntoCanvas { it.restore() }
    }
}

/** True if [this] has a pictographic (colour-emoji) character. */
internal fun String.hasEmoji(): Boolean {
    var i = 0
    while (i < length) {
        val cp = codePointAt(i)
        if (cp >= 0x1F000 || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF || cp == 0xFE0F || cp in 0x2190..0x21FF || cp in 0x2300..0x23FF) return true
        i += Character.charCount(cp)
    }
    return false
}

/**
 * Draws [shadow] as its own pass (invisible text casting a white glow, like a CSS
 * text-shadow), then the text on top without a shadow, instead of giving the gradient-filled
 * text the shadow in the same draw, which renders the glyphs visibly darker.
 *
 * Every draw must pass a shadow: a TextLayoutResult keeps its paint between draws and a null
 * shadow means "unchanged", so the glow would otherwise stick to the text pass and to later
 * frames. [Shadow.None] clears it.
 */
private inline fun DrawScope.drawGlowThenText(
    layoutResult: TextLayoutResult,
    topLeft: Offset,
    shadow: Shadow?,
    drawBody: () -> Unit,
) {
    if (shadow != null) drawText(layoutResult, color = Color.Transparent, shadow = shadow, topLeft = topLeft)
    drawBody()
}

/**
 * Vertical (top→bottom) variant of [drawWipeText] for Line-mode lines: the `.line` element
 * takes the `--gradient-degrees: 180deg !important` rule (unlike `.word`/`.letter`, whose own
 * 90deg declarations win), so line-synced lyrics fill downward across the LINE's full height.
 * The position percent maps over [fullHeight]; each wrapped row fragment maps the band into its
 * own local vertical space via [startYOffset].
 */
private fun DrawScope.drawVerticalWipeText(
    layoutResult: TextLayoutResult,
    xPos: Float,
    yPos: Float,
    fragmentHeight: Float,
    fullHeight: Float,
    startYOffset: Float,
    gradientPositionPercent: Float,
    brightAlpha: Float,
    dimAlpha: Float,
    shadow: Shadow?,
) {
    val bright = Color.White.copy(alpha = brightAlpha.coerceIn(0f, 1f))
    val dim = Color.White.copy(alpha = dimAlpha.coerceIn(0f, 1f))
    val topLeft = Offset(xPos, yPos)

    val fh = fullHeight.coerceAtLeast(1f)
    val h = fragmentHeight.coerceAtLeast(1f)
    val loFrac = gradientPositionPercent / 100f
    val hiFrac = (gradientPositionPercent + 20f) / 100f

    // Always the brush path — see drawWipeText: solid-color vs shader paint renders the same
    // alpha differently, so all fragments must share one paint path.
    val localLo = ((loFrac * fh) - startYOffset) / h
    val localHi = ((hiFrac * fh) - startYOffset) / h
    val bandW = (localHi - localLo).coerceAtLeast(1e-4f)
    fun colorAt(y: Float): Color =
        androidx.compose.ui.graphics.lerp(bright, dim, ((y - localLo) / bandW).coerceIn(0f, 1f))

    val s0 = localLo.coerceIn(0.0001f, 0.9997f)
    val s1 = localHi.coerceIn(s0 + 0.0001f, 0.9998f)
    val brush = Brush.verticalGradient(
        0f to colorAt(0f),
        s0 to colorAt(s0),
        s1 to colorAt(s1),
        1f to colorAt(1f),
        startY = 0f,
        endY = h,
    )
    drawGlowThenText(layoutResult, topLeft, shadow) { drawGradientText(layoutResult, brush, topLeft) }
}

/**
 * Shadow used for an inactive line's distance blur, or null if not blurred. The reference
 * paints inactive text as its own text-shadow (NotSung at the dim alpha, Sung at the bright
 * alpha) whose blur radius is the distance-based --BlurAmount.
 */
private fun inactiveShadow(plan: LyricPaintPlan.InactiveShadow, suppressBlur: Boolean, cssPx: Float): Shadow = Shadow(
    color = Color.White.copy(alpha = plan.alpha),
    blurRadius = if (suppressBlur) 0f else plan.blurRadius * cssPx,
)

private fun DrawScope.drawInactiveText(
    layoutResult: TextLayoutResult,
    xPos: Float,
    yPos: Float,
    plan: LyricPaintPlan.InactiveShadow,
    suppressBlur: Boolean,
    cssPx: Float,
) {
    drawText(
        textLayoutResult = layoutResult,
        color = Color.Transparent,
        shadow = inactiveShadow(plan, suppressBlur, cssPx),
        topLeft = Offset(xPos, yPos),
    )
}

internal fun DrawScope.drawInterludeGroup(
    layout: LineLayout,
    lineAnim: LineAnimState,
    lineStartX: Float,
    scrollOffset: Float,
    dynamicY: Float,
) {
    // Widened past 1 (but not below 0 — a negative scale would mirror-flip the dots) so the
    // overshoot collapse curve (DOT_GROUP_COLLAPSE_EASING) stays visible.
    val groupScale = (lineAnim.scale * lineAnim.groupScale).coerceIn(0f, 1.3f)
    if (groupScale < 0.01f) return

    val firstDot = layout.words.firstOrNull() ?: return
    val lastDot = layout.words.lastOrNull() ?: return
    val firstTextW = firstDot.textLayoutResult.size.width.toFloat()
    val lastTextW = lastDot.textLayoutResult.size.width.toFloat()

    val dotGroupCentreX = lineStartX + firstDot.relativeOffset.x + firstTextW / 2f +
        (lastDot.relativeOffset.x + lastTextW / 2f - firstDot.relativeOffset.x - firstTextW / 2f) / 2f
    val dotGroupCentreY = dynamicY + scrollOffset

    layout.words.forEachIndexed { dotIdx, wLayout ->
        val dotAnim = lineAnim.wordStates.getOrNull(dotIdx) ?: return@forEachIndexed
        val dotOpacity = dotAnim.glow.coerceIn(0f, 1f)

        val xPos = lineStartX + wLayout.relativeOffset.x
        val textW = wLayout.textLayoutResult.size.width.toFloat()
        val textH = wLayout.textLayoutResult.size.height.toFloat()
        val baseYPos = dotGroupCentreY - textH / 2f

        val dotPivotX = xPos + textW / 2f
        val dotPivotY = baseYPos + textH / 2f
        // The offset is in lyric font sizes; a dot is 1.3 of that.
        val lyricSizePx = with(wLayout.textLayoutResult.layoutInput) { with(density) { style.fontSize.toPx() } } / 1.3f
        val dotYShift = dotAnim.yOffset * lyricSizePx

        // Dot halo driven by its own glow spring: blur 4 + 6·glow, opacity glow·0.9.
        val dotGlow = dotAnim.dotGlow.coerceIn(0f, 1f)
        val dotGlowAlpha = (dotGlow * 0.9f).coerceIn(0f, 1f)
        val dotShadow = if (!lineAnim.suppressShadows && dotGlowAlpha > 0.02f) {
            Shadow(color = Color.White.copy(alpha = dotGlowAlpha * lineAnim.opacity), blurRadius = (4f + 6f * dotGlow) * lyricSizePx / REFERENCE_LYRIC_SIZE_CSS_PX)
        } else null

        withTransform({
            scale(groupScale, groupScale, Offset(dotGroupCentreX, dotGroupCentreY))
            scale(dotAnim.scale.coerceIn(0f, 1.5f), dotAnim.scale.coerceIn(0f, 1.5f), Offset(dotPivotX, dotPivotY))
            translate(top = dotYShift)
        }) {
            drawText(
                textLayoutResult = wLayout.textLayoutResult,
                color = Color.White,
                alpha = dotOpacity * lineAnim.opacity,
                shadow = dotShadow ?: Shadow.None,
                topLeft = Offset(xPos, baseYPos),
            )
        }
    }
}

/**
 * Where a syllable scales from, as a fraction of its width (reference Mixed.css): the first
 * syllable of a split word grows from its right edge, middle ones from their centre, the last
 * from its left edge, so a split word spreads outward from inside. Whole words use the centre.
 * Mirrored for RTL.
 */
private fun scaleAnchor(gluedBefore: Boolean, gluedAfter: Boolean, rtl: Boolean): Float {
    val ltr = when {
        gluedAfter && !gluedBefore -> 1f
        gluedBefore && !gluedAfter -> 0f
        else -> 0.5f
    }
    return if (rtl) 1f - ltr else ltr
}

/**
 * The lyric size on a desktop window, where the effect sizes (blur, glow radii in CSS px) were
 * tuned: clamp(1.85rem, 7cqw, 3.5rem), so 3.5rem = 56px.
 */
private const val REFERENCE_LYRIC_SIZE_CSS_PX = 56f

/**
 * One desktop CSS px in our px, relative to the text: effects keep the same size next to the
 * letters as on a desktop page, whatever the screen density.
 */
private fun cssPx(layout: TextLayoutResult, isBackground: Boolean): Float =
    lyricSizePx(layout, isBackground) / REFERENCE_LYRIC_SIZE_CSS_PX

/** The lyric size in px: a background line's text is 0.75 of it (an approximation in the Apple Music style's 0.7). */
private fun lyricSizePx(layout: TextLayoutResult, isBackground: Boolean): Float =
    with(layout.layoutInput) { with(density) { style.fontSize.toPx() } } / (if (isBackground) 0.75f else 1f)

/**
 * [scale] with its growth past 1 multiplied by [boost] (RenderConfig.wordMotionBoost). Below 1 it
 * passes through: boosting the resting 0.95 too would shrink unsung and settling words further.
 */
internal fun boostedScale(scale: Float, boost: Float): Float =
    if (scale > 1f) 1f + (scale - 1f) * boost else scale

/** [yOffset] with its lift (negative, upward) multiplied by [boost]; the resting dip passes through. */
internal fun boostedLift(yOffset: Float, boost: Float): Float =
    if (yOffset < 0f) yOffset * boost else yOffset

/** Word/syllable-synced karaoke line. */
internal fun DrawScope.drawStandardLine(
    layout: LineLayout,
    lineAnim: LineAnimState,
    lineStartX: Float,
    scrollOffset: Float,
    dynamicY: Float,
    config: RenderConfig,
) {
    val rtl = layout.isRtl
    val sourceWords = layout.line.words
    layout.words.forEach { wLayout ->
        val wordAnim = lineAnim.wordStates.getOrNull(wLayout.sourceWordIndex) ?: return@forEach
        val anchor = scaleAnchor(
            gluedBefore = wLayout.sourceWordIndex > 0 && sourceWords.getOrNull(wLayout.sourceWordIndex)?.isPartOfWord == true,
            gluedAfter = sourceWords.getOrNull(wLayout.sourceWordIndex + 1)?.isPartOfWord == true,
            rtl = rtl,
        )
        val xPos = lineStartX + wLayout.relativeOffset.x
        val yPos = dynamicY + wLayout.relativeOffset.y
        val textWidth = wLayout.textLayoutResult.size.width.toFloat()
        val textHeight = wLayout.textLayoutResult.size.height.toFloat()

        val paintPlan = lyricPaintPlan(lineAnim.state, lineAnim.isBackground, lineAnim.opacity, lineAnim.blur, config, lit = lineAnim.lit)

        val wordText = sourceWords.getOrNull(wLayout.sourceWordIndex)?.text.orEmpty()
        when {
            config.isAppleMusic && wordAnim.isLetterGroup ->
                drawAppleMusicLetter(wLayout, wordAnim, lineAnim, xPos, yPos, textWidth, textHeight, scrollOffset, paintPlan, rtl, wordText)
            config.isAppleMusic ->
                drawAppleMusicWord(wLayout, wordAnim, lineAnim, xPos, yPos, scrollOffset, paintPlan, rtl, wordText)
            wordAnim.isLetterGroup ->
                drawSyllabicLetterFragment(wLayout, wordAnim, lineAnim, xPos, yPos, textWidth, textHeight, scrollOffset, config, paintPlan, rtl, anchor)
            else ->
                drawStandardWord(wLayout, wordAnim, lineAnim, xPos, yPos, textWidth, textHeight, scrollOffset, config, paintPlan, rtl, anchor)
        }
    }
}

private fun DrawScope.drawSyllabicLetterFragment(
    wLayout: WordLayout,
    wordAnim: WordAnimState,
    lineAnim: LineAnimState,
    xPos: Float,
    yPos: Float,
    textWidth: Float,
    textHeight: Float,
    scrollOffset: Float,
    config: RenderConfig,
    paintPlan: LyricPaintPlan,
    rtl: Boolean,
    anchor: Float,
) {
    val lState = wordAnim.letterStates.getOrNull(wLayout.charIndex) ?: return

    val sLYPos = yPos + scrollOffset
    val sPivotX = xPos + textWidth / 2f
    val sPivotY = sLYPos + textHeight / 2f
    // Word-container pivot: letters sit inside their word, so the
    // word's own scale() pivots at the WORD's center (spreading letters outward), not each
    // letter's own center. Recover the word's left edge from this fragment's offset within it.
    // ponytail: single-row word-center pivot; wrapped held words approximate.
    val wordLeftX = xPos - wLayout.startXOffset
    val wordPivotX = wordLeftX + wLayout.fullWordWidth * anchor
    // Offsets are in lyric font sizes (--DefaultLyricsSize); a letter's is applied ×2.
    val lyricSize = lyricSizePx(wLayout.textLayoutResult, lineAnim.isBackground)
    val boost = config.wordMotionBoost
    val lYShift = boostedLift(lState.yOffset, boost) * lyricSize * 2f
    val containerYShift = boostedLift(wordAnim.yOffset, boost) * lyricSize
    val wordScale = boostedScale(wordAnim.scale, boost)
    val letterScale = boostedScale(lState.scale, boost)

    // Glow shadow tracks the spring in every state (not gated to Active): the animator keeps
    // stepping scale/glow/yOffset toward their Sung targets after EndTime (checkNextLine), so a
    // held word's settle tail must stay visible instead of being amputated the instant it's Sung.
    val lGlowBlur = 4f + 12f * lState.glow
    val lGlowOpacity = (lState.glow * 1.85f).coerceIn(0f, 1f)  // LetterGlowMultiplier_Opacity = 185%
    val lShadow = when {
        !lineAnim.suppressShadows && lGlowOpacity > 0.02f ->
            Shadow(color = Color.White.copy(alpha = lGlowOpacity * lineAnim.opacity), blurRadius = lGlowBlur * cssPx(wLayout.textLayoutResult, lineAnim.isBackground))
        else -> null
    }

    // Reference gradient stops are fixed for every state (bright 0.85 / dim 0.35; bg-line
    // 0.6/0.3 — letters inherit the .bg-line override too); only --gradient-position moves.
    // Multiplying by line opacity is the only "fade" layer — a NotSung word (position -20) and
    // an active word's unsung tail land on the identical dim stop, and a Sung line's bright
    // side (0.85×0.497≈0.42) can never render darker than an inactive line's dim stop
    // (0.35×0.51≈0.18).
    val isBg = lineAnim.isBackground
    val dim = (if (isBg) 0.3f else config.gradientAlphaDim) * lineAnim.opacity
    val bright = (if (isBg) 0.6f else config.gradientAlphaBright) * lineAnim.opacity

    withTransform({
        // The reference nests letter spans inside the word element: the word's own
        // scale/translate wraps every letter's individual scale/translate.
        scale(wordScale, wordScale, Offset(wordPivotX, sPivotY))
        translate(top = containerYShift)
        scale(letterScale, letterScale, Offset(sPivotX, sPivotY))
        translate(top = lYShift)
    }) {
        if (paintPlan is LyricPaintPlan.InactiveShadow) drawInactiveText(
            wLayout.textLayoutResult, xPos, yPos + scrollOffset, paintPlan, lineAnim.suppressShadows,
            cssPx(wLayout.textLayoutResult, lineAnim.isBackground),
        ) else drawWipeText(
            layoutResult = wLayout.textLayoutResult,
            xPos = xPos,
            yPos = yPos + scrollOffset,
            fragmentWidth = textWidth,
            fullWidth = wLayout.fullWordWidth,
            startXOffset = wLayout.startXOffset,
            gradientPositionPercent = lState.gradientPosition,
            brightAlpha = bright,
            dimAlpha = dim,
            shadow = lShadow,
            rtl = rtl,
            gradientOffsetPercent = if (config.isSimple) 30f else 0f,
        )
    }
}

private fun DrawScope.drawStandardWord(
    wLayout: WordLayout,
    wordAnim: WordAnimState,
    lineAnim: LineAnimState,
    xPos: Float,
    yPos: Float,
    textWidth: Float,
    textHeight: Float,
    scrollOffset: Float,
    config: RenderConfig,
    paintPlan: LyricPaintPlan,
    rtl: Boolean,
    anchor: Float,
) {
    // Glow shadow tracks the spring in every state (not gated to Active): the animator keeps
    // stepping scale/glow/yOffset toward their Sung targets after EndTime (checkNextLine), so a
    // held word's settle tail must stay visible instead of being amputated the instant it's Sung.
    val glowBlur = 4f + 2f * wordAnim.glow
    val glowOpacity = (wordAnim.glow * 0.35f).coerceIn(0f, 1f)
    val shadow = when {
        !lineAnim.suppressShadows && glowOpacity > 0.02f ->
            Shadow(color = Color.White.copy(alpha = glowOpacity * lineAnim.opacity), blurRadius = glowBlur * cssPx(wLayout.textLayoutResult, lineAnim.isBackground))
        else -> null
    }

    val wordScale = boostedScale(wordAnim.scale, config.wordMotionBoost)
    val wordYShift = boostedLift(wordAnim.yOffset, config.wordMotionBoost) * lyricSizePx(wLayout.textLayoutResult, lineAnim.isBackground)
    val pivotX = xPos + textWidth * anchor
    val pivotY = yPos + textHeight / 2f

    // Reference gradient stops are fixed for every state (bright 0.85/0.6bg, dim 0.35/0.3bg);
    // only --gradient-position moves. Multiplying by line opacity is the only "fade" layer.
    val isBg = lineAnim.isBackground
    val dim = (if (isBg) 0.3f else config.gradientAlphaDim) * lineAnim.opacity
    val bright = (if (isBg) 0.6f else config.gradientAlphaBright) * lineAnim.opacity

    withTransform({
        translate(top = scrollOffset)
        scale(scaleX = wordScale, scaleY = wordScale, pivot = Offset(pivotX, pivotY))
        translate(top = wordYShift)
    }) {
        if (paintPlan is LyricPaintPlan.InactiveShadow) drawInactiveText(
            wLayout.textLayoutResult, xPos, yPos, paintPlan, lineAnim.suppressShadows,
            cssPx(wLayout.textLayoutResult, lineAnim.isBackground),
        ) else drawWipeText(
            layoutResult = wLayout.textLayoutResult,
            xPos = xPos,
            yPos = yPos,
            fragmentWidth = textWidth,
            fullWidth = wLayout.fullWordWidth,
            startXOffset = wLayout.startXOffset,
            gradientPositionPercent = wordAnim.gradientPosition,
            brightAlpha = bright,
            dimAlpha = dim,
            shadow = shadow,
            rtl = rtl,
            gradientOffsetPercent = if (config.isSimple) 30f else 0f,
        )
    }
}

/**
 * The Apple Music style's wipe as [drawWipeText] takes it: from the word's [progress] (0..100), a
 * fully lit part that ends at the edge, then a soft edge of [AppleMusicMotion.featherEm] into the
 * unsung colour. The edge starts a feather before the word and ends at its far side, so the word
 * is wholly dim at 0 and wholly lit at 100. Returns the position and the extra band width.
 */
internal fun appleWipe(progress: Float, fullWidth: Float, lyricSize: Float, text: String): Pair<Float, Float> {
    val width = fullWidth.coerceAtLeast(1f)
    val feather = minOf(AppleMusicMotion.featherEm(text.length, AppleMusicMotion.isCjk(text)) * lyricSize, width / 2f)
    val edge = progress / 100f * (width + feather) - feather
    return edge / width * 100f to feather / width * 100f - 20f
}

private fun appleAlphas(lineAnim: LineAnimState): Pair<Float, Float> = if (lineAnim.isBackground) {
    AppleMusicMotion.BACKGROUND_BRIGHT_ALPHA * lineAnim.opacity to AppleMusicMotion.BACKGROUND_DIM_ALPHA * lineAnim.opacity
} else {
    AppleMusicMotion.BRIGHT_ALPHA * lineAnim.opacity to AppleMusicMotion.DIM_ALPHA * lineAnim.opacity
}

/** A word in the Apple Music style: no glow and no growth, only its rise and the soft-edged wipe. */
private fun DrawScope.drawAppleMusicWord(
    wLayout: WordLayout,
    wordAnim: WordAnimState,
    lineAnim: LineAnimState,
    xPos: Float,
    yPos: Float,
    scrollOffset: Float,
    paintPlan: LyricPaintPlan,
    rtl: Boolean,
    text: String,
) {
    val lyricSize = lyricSizePx(wLayout.textLayoutResult, lineAnim.isBackground)
    translate(top = scrollOffset + wordAnim.yOffset * lyricSize) {
        if (paintPlan is LyricPaintPlan.InactiveShadow) {
            drawInactiveText(wLayout.textLayoutResult, xPos, yPos, paintPlan, lineAnim.suppressShadows, cssPx(wLayout.textLayoutResult, lineAnim.isBackground))
        } else {
            val (position, band) = appleWipe(wordAnim.gradientPosition, wLayout.fullWordWidth, lyricSize, text)
            val (bright, dim) = appleAlphas(lineAnim)
            drawWipeText(
                layoutResult = wLayout.textLayoutResult,
                xPos = xPos,
                yPos = yPos,
                fragmentWidth = wLayout.textLayoutResult.size.width.toFloat(),
                fullWidth = wLayout.fullWordWidth,
                startXOffset = wLayout.startXOffset,
                gradientPositionPercent = position,
                brightAlpha = bright,
                dimAlpha = dim,
                shadow = null,
                rtl = rtl,
                gradientOffsetPercent = band,
            )
        }
    }
}

/**
 * A held word's letter in the Apple Music style: it rises, swells and moves apart from its
 * neighbours with a soft glow, while the word's one wipe runs across all its letters.
 */
private fun DrawScope.drawAppleMusicLetter(
    wLayout: WordLayout,
    wordAnim: WordAnimState,
    lineAnim: LineAnimState,
    xPos: Float,
    yPos: Float,
    textWidth: Float,
    textHeight: Float,
    scrollOffset: Float,
    paintPlan: LyricPaintPlan,
    rtl: Boolean,
    text: String,
) {
    val letter = wordAnim.letterStates.getOrNull(wLayout.charIndex) ?: return
    val lyricSize = lyricSizePx(wLayout.textLayoutResult, lineAnim.isBackground)
    val top = yPos + scrollOffset
    val glowAlpha = (letter.glow * lineAnim.opacity).coerceIn(0f, 1f)
    val glow = if (!lineAnim.suppressShadows && glowAlpha > 0.02f && letter.glowRadius > 0f) {
        Shadow(color = Color.White.copy(alpha = glowAlpha), blurRadius = letter.glowRadius * lyricSize)
    } else null
    withTransform({
        translate(left = letter.xOffset * lyricSize, top = letter.yOffset * lyricSize)
        scale(letter.scale, letter.scale, Offset(xPos + textWidth / 2f, top + textHeight / 2f))
    }) {
        if (paintPlan is LyricPaintPlan.InactiveShadow) {
            drawInactiveText(wLayout.textLayoutResult, xPos, top, paintPlan, lineAnim.suppressShadows, cssPx(wLayout.textLayoutResult, lineAnim.isBackground))
        } else {
            val (position, band) = appleWipe(letter.gradientPosition, wLayout.fullWordWidth, lyricSize, text)
            val (bright, dim) = appleAlphas(lineAnim)
            drawWipeText(
                layoutResult = wLayout.textLayoutResult,
                xPos = xPos,
                yPos = top,
                fragmentWidth = textWidth,
                fullWidth = wLayout.fullWordWidth,
                startXOffset = wLayout.startXOffset,
                gradientPositionPercent = position,
                brightAlpha = bright,
                dimAlpha = dim,
                shadow = glow,
                rtl = rtl,
                gradientOffsetPercent = band,
            )
        }
    }
}

/** Whole-line gradient sweep for [com.almog.spotifytablet.lyrics.mobile.models.LyricsType.Line]. */
internal fun DrawScope.drawLineModeLine(
    layout: LineLayout,
    lineAnim: LineAnimState,
    lineStartX: Float,
    scrollOffset: Float,
    dynamicY: Float,
    config: RenderConfig,
) {
    val paintPlan = lyricPaintPlan(lineAnim.state, lineAnim.isBackground, lineAnim.opacity, lineAnim.blur, config, config.lineGradientAlphaDim, lineAnim.lit)
    // Reference gradient stops are fixed for the active state; inactive lines are shadow-only.
    val dim = (if (config.isAppleMusic) AppleMusicMotion.DIM_ALPHA else config.lineGradientAlphaDim) * lineAnim.opacity
    val bright = (if (config.isAppleMusic) AppleMusicMotion.BRIGHT_ALPHA else config.gradientAlphaBright) * lineAnim.opacity

    // Whole-line glow spring (reference Line-mode: shadow blur 4 + 8·glow, alpha glow·0.5),
    // layered with the inactive-line distance blur when present.
    val glowAlpha = (lineAnim.lineGlow * 0.5f).coerceIn(0f, 1f)
    val lineCssPx = layout.words.firstOrNull()?.let { cssPx(it.textLayoutResult, lineAnim.isBackground) } ?: 1f
    val shadow = when {
        config.isAppleMusic -> null
        !lineAnim.suppressShadows && glowAlpha > 0.02f -> Shadow(
            color = Color.White.copy(alpha = glowAlpha * lineAnim.opacity),
            blurRadius = (4f + 8f * lineAnim.lineGlow) * lineCssPx,
        )
        else -> null
    }
    val lineWidth = layout.maxRowWidth.coerceAtLeast(1f)

    // Active Line-mode lines scale to 1.05 with transform-origin left-center
    // (right-center for duet/RTL lines).
    val pivot = Offset(
        if (layout.isRightAligned) lineStartX + layout.totalWidth else lineStartX,
        dynamicY + scrollOffset + layout.height / 2f,
    )
    withTransform({
        scale(lineAnim.scale, lineAnim.scale, pivot)
    }) {
        layout.words.forEach { wLayout ->
            val xPos = lineStartX + wLayout.relativeOffset.x
            val yPos = dynamicY + wLayout.relativeOffset.y + scrollOffset
            val textWidth = wLayout.textLayoutResult.size.width.toFloat()
            val textHeight = wLayout.textLayoutResult.size.height.toFloat()
            if (paintPlan is LyricPaintPlan.InactiveShadow) {
                drawInactiveText(wLayout.textLayoutResult, xPos, yPos, paintPlan, lineAnim.suppressShadows,
                    cssPx(wLayout.textLayoutResult, lineAnim.isBackground))
            } else if (layout.isRtl) {
                // RTL lines keep the horizontal right→left sweep (.line.rtl -90deg !important).
                drawWipeText(
                    layoutResult = wLayout.textLayoutResult,
                    xPos = xPos,
                    yPos = yPos,
                    fragmentWidth = textWidth,
                    fullWidth = lineWidth,
                    startXOffset = wLayout.relativeOffset.x,
                    gradientPositionPercent = lineAnim.lineGradientPercent,
                    brightAlpha = bright,
                    dimAlpha = dim,
                    shadow = shadow,
                    rtl = true,
                )
            } else {
                // Line-mode fills top→bottom across the line's full height (180deg on .line).
                drawVerticalWipeText(
                    layoutResult = wLayout.textLayoutResult,
                    xPos = xPos,
                    yPos = yPos,
                    fragmentHeight = textHeight,
                    fullHeight = (layout.lyricHeight ?: layout.height).coerceAtLeast(1f),
                    startYOffset = wLayout.relativeOffset.y,
                    gradientPositionPercent = lineAnim.lineGradientPercent,
                    brightAlpha = bright,
                    dimAlpha = dim,
                    shadow = shadow,
                )
            }
        }
    }
}

/** Plain, non-interactive text for [com.almog.spotifytablet.lyrics.mobile.models.LyricsType.Static]. */
internal fun DrawScope.drawStaticLine(
    layout: LineLayout,
    lineAnim: LineAnimState,
    lineStartX: Float,
    scrollOffset: Float,
    dynamicY: Float,
) {
    layout.words.forEach { wLayout ->
        val xPos = lineStartX + wLayout.relativeOffset.x
        val yPos = dynamicY + wLayout.relativeOffset.y + scrollOffset
        drawText(
            textLayoutResult = wLayout.textLayoutResult,
            color = Color.White,
            alpha = lineAnim.opacity.coerceIn(0f, 1f),
            shadow = Shadow.None,
            topLeft = Offset(xPos, yPos),
        )
    }
}
