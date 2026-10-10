package com.almog.spotifytablet.lyrics.mobile.animation

import com.almog.spotifytablet.lyrics.mobile.canvas.LyricsLayoutCalculator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import com.almog.spotifytablet.lyrics.mobile.RenderConfig
import com.almog.spotifytablet.lyrics.mobile.SimpleAnimationStyle
import com.almog.spotifytablet.lyrics.mobile.models.Line
import com.almog.spotifytablet.lyrics.mobile.models.LyricsType
import com.almog.spotifytablet.lyrics.mobile.models.Word
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The lyrics animator, stepped once per frame with the playback position:
 * - Only **Active** lines have their word/letter/dot springs retargeted and stepped.
 * - **NotSung** lines are never touched — their words freeze at whatever state they last had
 *   (initially the resting state). There is no snap-to-resting reset pass.
 * - A **Sung** line keeps stepping toward its final targets (the `checkNextLine` pass) only
 *   while the following line is itself not yet Sung (or when it's the last line); afterwards
 *   it freezes. This is what makes a just-finished line settle out gracefully instead of
 *   stopping dead the moment its time window ends.
 * - Line-level opacity is the Compose equivalent of the CSS class transition
 *   (200ms cubic-bezier(0.61,1,0.88,1)); distance blur is recomputed only when the active
 *   line index changes.
 *
 * Springs are [SpringSimulation] (exact spr.lua port); dt is in **seconds**, positions in ms.
 */
class LyricsAnimator(
    private val coroutineScope: CoroutineScope,
    config: RenderConfig = RenderConfig.FULL,
) {

    var config: RenderConfig = config
        set(value) {
            // The motion boost is applied at draw time; only real mode changes restart the springs.
            val modeChanged = value.copy(wordMotionBoost = 1f) != field.copy(wordMotionBoost = 1f)
            field = value
            if (modeChanged) {
                rebuildModeSplines()
                reset()
            }
        }

    // ── Splines ──────────────────────────────────────────────────────────────────────────
    private val scaleSpline = spline(0f to 0.95f, 0.7f to 1.0505f, 1f to 1f)
    private val glowSpline = spline(0f to 0f, 0.15f to 1f, 0.6f to 1f, 1f to 0f)
    private val dotScaleSpline = spline(0f to 0.75f, 0.7f to 1.05f, 1f to 1f)
    private val dotYOffsetSpline = spline(0f to 0f, 0.9f to -0.12f, 1f to 0f)
    private val dotGlowSpline = spline(0f to 0f, 0.6f to 1f, 1f to 1f)
    private val lineGlowSpline = spline(0f to 0f, 0.5f to 1f, 1f to 0f)

    // Mode-dependent splines (rebuilt when the quality mode changes).
    private lateinit var yOffsetSpline: CubicSplineInterpolator
    private lateinit var letterScaleSpline: CubicSplineInterpolator
    private lateinit var letterYOffsetSpline: CubicSplineInterpolator
    private lateinit var dotOpacitySpline: CubicSplineInterpolator

    init { rebuildModeSplines() }

    private fun rebuildModeSplines() {
        val simple = config.isSimple
        yOffsetSpline = if (simple) spline(0f to 0.01f, 1f to -0.033f)
            else spline(0f to 0.01f, 0.9f to -1f / 60f, 1f to 0f)
        letterScaleSpline = if (simple) spline(0f to 0.95f, 0.7f to 1.07f, 1f to 1f)
            else spline(0f to 0.95f, 0.7f to 1.175f, 1f to 1f)
        letterYOffsetSpline = if (simple) spline(0f to 0.01f, 0.9f to -1f / 62f, 1f to 0f)
            else spline(0f to 0.01f, 0.9f to -1f / 56f, 1f to 0f)
        dotOpacitySpline = spline(0f to (if (simple) 0.27f else 0.35f), 0.6f to 1f, 1f to 1f)
    }

    internal companion object {
        // Word/letter spring tuning (reference "active" values).
        const val SCALE_FREQUENCY = 0.88f
        const val SCALE_DAMPING = 0.64f
        const val Y_OFFSET_FREQUENCY = 1.45f
        const val Y_OFFSET_DAMPING = 0.4f
        const val GLOW_FREQUENCY = 1.18f
        const val GLOW_DAMPING = 0.56f

        // Dot spring tuning.
        const val DOT_SCALE_FREQUENCY = 0.7f
        const val DOT_SCALE_DAMPING = 0.6f
        const val DOT_Y_OFFSET_FREQUENCY = 1.25f
        const val DOT_Y_OFFSET_DAMPING = 0.4f
        const val DOT_GLOW_FREQUENCY = 1f
        const val DOT_GLOW_DAMPING = 0.5f
        const val DOT_OPACITY_FREQUENCY = 1f
        const val DOT_OPACITY_DAMPING = 0.5f

        // Line-mode whole-line glow spring.
        const val LINE_GLOW_FREQUENCY = 1f
        const val LINE_GLOW_DAMPING = 0.5f

        /** GlowSpline input for a finished letter while its word still has an active letter path. */
        const val SUNG_LETTER_GLOW = 0.2f

        // Distance blur (Shared.ts).
        const val BLUR_MULTIPLIER = 1.25f
        val MAX_BLUR = BLUR_MULTIPLIER * 5f + BLUR_MULTIPLIER * 0.465f  // 6.83125

        /** Dots collapse this long before their line ends (lyrics.ts preHiddenDotLineMs). */
        const val PRE_HIDDEN_DOT_LINE_MS = 500L
        /** Position shift applied in simple mode (Animate: `- 33.5`). */
        const val SIMPLE_MODE_POSITION_SHIFT_MS = 33.5f

        // Simple-mode letter effect strength (lyrics.ts SimpleLyricsMode_LetterEffectsStrengthConfig).
        const val SIMPLE_LETTER_LONGER_THAN_MS = 1500L
        const val SIMPLE_LONGER_SCALE = 1.103f
        const val SIMPLE_LONGER_Y_OFFSET = 0.45f
        const val SIMPLE_LONGER_GLOW = 0.4f
        const val SIMPLE_SHORTER_SCALE = 1.09f
        const val SIMPLE_SHORTER_Y_OFFSET = 0.1f
        const val SIMPLE_SHORTER_GLOW = 0.285f

        val OPACITY_EASING = CubicBezierEasing(0.61f, 1f, 0.88f, 1f)
        val SCALE_EASING = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)
        /** Reference dot-group collapse (Mixed.css .pre-hidden .dotGroup): 0.4s w/ dip-then-overshoot. */
        val DOT_COLLAPSE_STOPS = floatArrayOf(
            0f, 0f, 0.094f, -0.006f, 0.18f, -0.029f, 0.433f, -0.157f,
            0.514f, -0.185f, 0.559f, -0.189f, 0.6f, -0.182f, 0.639f, -0.163f,
            0.676f, -0.133f, 0.723f, -0.074f, 0.767f, 0.006f, 0.85f, 0.238f,
            0.927f, 0.566f, 1f, 1f,
        )
        val DOT_GROUP_COLLAPSE_EASING = Easing { fraction ->
            val stops = DOT_COLLAPSE_STOPS
            var index = 0
            while (index < stops.size - 4 && fraction > stops[index + 2]) index += 2
            val x0 = stops[index]
            val y0 = stops[index + 1]
            val x1 = stops[index + 2]
            val y1 = stops[index + 3]
            y0 + (y1 - y0) * ((fraction - x0) / (x1 - x0).coerceAtLeast(0.0001f))
        }
        const val MUSICAL_LINE_TRANSITION_MS = 140
        /** Where Simple mode's Animate sweep (SLM_Animation) starts, in percent. */
        const val SLM_FROM = -27.5f
        /** `.line` opacity (and line-synced scale) transition: 0.2s. */
        const val LINE_TRANSITION_MS = 200
        /** Minimal Lyrics Mode (Mixed.css): scale 0.4s ease-in-out, opacity 0.4s linear. */
        const val MINIMAL_TRANSITION_MS = 400
        val EASE_IN_OUT = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
        const val MINIMAL_SUNG_SCALE = 0.95f
        const val MINIMAL_NOT_SUNG_SCALE = 0.965f
        const val MINIMAL_NOT_SUNG_OPACITY = 0.5f
        const val MINIMAL_AFTER_ACTIVE_OPACITY = 0.45f
        /** CSS `ease`. */
        val CSS_EASE = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
        /** The Apple Music style keeps a sung line's words moving this long after it ends (held-word letters settling). */
        const val APPLE_SETTLE_MS = 15_000L
        /**
         * An em of CSS `blur()` deviation as the shadow radius the renderer takes, in desktop px:
         * 56px to the em, and a shadow's radius is about √3 of its deviation.
         */
        const val APPLE_BLUR_CSS_PX_PER_EM = 56f * 1.73f
        const val DOT_GROUP_EXPANSION_MS = 300
        const val DOT_GROUP_COLLAPSE_MS = 400

        fun spline(vararg points: Pair<Float, Float>) =
            CubicSplineInterpolator(points.map { AnimationPoint(it.first, it.second) })
    }

    /** Word/letter-group container springs, snapped to resting on creation (reference lazy-init). */
    private inner class WordSprings {
        val scale = SpringSimulation(scaleSpline.at(0f), SCALE_FREQUENCY, SCALE_DAMPING)
        val yOffset = SpringSimulation(yOffsetSpline.at(0f), Y_OFFSET_FREQUENCY, Y_OFFSET_DAMPING)
        val glow = SpringSimulation(glowSpline.at(0f), GLOW_FREQUENCY, GLOW_DAMPING)
    }

    private inner class LetterSprings {
        val scale = SpringSimulation(letterScaleSpline.at(0f), SCALE_FREQUENCY, SCALE_DAMPING)
        val yOffset = SpringSimulation(letterYOffsetSpline.at(0f), Y_OFFSET_FREQUENCY, Y_OFFSET_DAMPING)
        val glow = SpringSimulation(glowSpline.at(0f), GLOW_FREQUENCY, GLOW_DAMPING)
    }

    private inner class DotSprings {
        val scale = SpringSimulation(dotScaleSpline.at(0f), DOT_SCALE_FREQUENCY, DOT_SCALE_DAMPING)
        val yOffset = SpringSimulation(dotYOffsetSpline.at(0f), DOT_Y_OFFSET_FREQUENCY, DOT_Y_OFFSET_DAMPING)
        val glow = SpringSimulation(dotGlowSpline.at(0f), DOT_GLOW_FREQUENCY, DOT_GLOW_DAMPING)
        val opacity = SpringSimulation(dotOpacitySpline.at(0f), DOT_OPACITY_FREQUENCY, DOT_OPACITY_DAMPING)
    }

    // ── Per-element animation state ─────────────────────────────────────────────────
    private val wordSpringsMap = mutableMapOf<Long, WordSprings>()
    private val letterSpringsMap = mutableMapOf<Long, LetterSprings>()
    private val dotSpringsMap = mutableMapOf<Long, DotSprings>()
    private val lineGlowSprings = mutableMapOf<Int, SpringSimulation>()

    private val lineOpacityAnims = mutableMapOf<Int, Animatable<Float, AnimationVector1D>>()
    private val lineScaleAnims = mutableMapOf<Int, Animatable<Float, AnimationVector1D>>()

    /** Frozen word states per line — reused verbatim on frames where the line isn't touched. */
    private val cachedWordStates = mutableMapOf<Int, List<WordAnimState>>()
    private val cachedLineGradient = mutableMapOf<Int, Float>()
    private val cachedLineGlow = mutableMapOf<Int, Float>()

    /**
     * The lines the word caches were built from. Lines get rebuilt (romanization switched on,
     * letters re-synthesized) with different words and letter counts, so the per-word caches and
     * springs are dropped then. Otherwise an upcoming line kept its old letter states and drew
     * only the first letter or two of a held word until the line became active.
     */
    private var cachedFor: List<Line>? = null
    private var cachedRomanizationFor: List<Line>? = null

    /** Blur is recomputed only when the active line index changes (reference Blurring_LastLine). */
    private var blurringLastLine: Int = -1
    private var blurAmounts = FloatArray(0)

    fun reset() {
        wordSpringsMap.clear()
        letterSpringsMap.clear()
        dotSpringsMap.clear()
        lineGlowSprings.clear()
        lineOpacityAnims.clear()
        lineScaleAnims.clear()
        cachedWordStates.clear()
        cachedLineGradient.clear()
        cachedLineGlow.clear()
        appleFinalStates.clear()
        appleOrdinalsFor = null
        blurringLastLine = -1
        blurAmounts = FloatArray(0)
    }

    /**
     * Computes the animation state for all lines at [currentTimeMs].
     *
     * @param deltaTime seconds since the last frame (unclamped).
     * @param suppressBlur true from the user's first touch until auto-scroll resumes.
     */
    fun animate(
        lines: List<Line>,
        currentTimeMs: Long,
        deltaTime: Float,
        suppressBlur: Boolean = false,
        lyricsType: LyricsType = LyricsType.Syllable,
        /** Original word timings for romanization below a whole-line translation replacement. */
        romanizationLines: List<Line>? = null,
    ): List<LineAnimState> {
        if (lines.isEmpty()) return emptyList()

        // Static lyrics have no timing: full-white, no animation (reference Static mode).
        if (lyricsType == LyricsType.Static) {
            return lines.map {
                LineAnimState(
                    opacity = 1f, blur = 0f, scale = 1f, isActive = false,
                    wordStates = emptyList(), isBackground = false, isSongwriter = false,
                    state = ElementState.NotSung,
                )
            }
        }

        if (lines !== cachedFor || romanizationLines !== cachedRomanizationFor) {
            cachedFor = lines
            cachedRomanizationFor = romanizationLines
            cachedWordStates.clear()
            appleFinalStates.clear()
            wordSpringsMap.clear()
            letterSpringsMap.clear()
            dotSpringsMap.clear()
        }

        val processedPosition = currentTimeMs.toDouble() -
            if (config.isSimple) SIMPLE_MODE_POSITION_SHIFT_MS.toDouble() else 0.0

        if (blurAmounts.size != lines.size) blurAmounts = FloatArray(lines.size)

        val states = lines.map { elementState(processedPosition, it.startMs, it.endMs) }
        if (config.isAppleMusic) return animateAppleMusic(lines, states, processedPosition, deltaTime, suppressBlur, lyricsType, romanizationLines)
        // Minimal's `.LyricsContent:not(.HideLineBlur)` rules drop out while the user scrolls.
        val minimalLook = config.isMinimal && !suppressBlur
        // `.LyricsContent:not(:has(.line.Active)):not(:has(.line.NotSung))`: the song is over.
        val allSung = states.none { it != ElementState.Sung }

        return lines.mapIndexed { lineIdx, line ->
            val romanization = if (line.translationReplaces && lyricsType == LyricsType.Syllable) romanizationLines?.getOrNull(lineIdx) else null
            val lyricsType = if (line.translationReplaces) LyricsType.Line else lyricsType
            val lineState = states[lineIdx]
            val isActive = !line.isSongwriter && lineState == ElementState.Active

            // Distance blur: recompute only when the active line changes.
            if (isActive && !line.isBackground && blurringLastLine != lineIdx) {
                applyBlur(lines, lineIdx, processedPosition)
                blurringLastLine = lineIdx
            }

            // A line whose own words just finished can still be mid-settle (checkNextLine) while
            // a concurrent bg line — or the array-adjacency check — keeps it from freezing yet;
            // this only governs how long the scale/glow springs keep relaxing smoothly instead of
            // freezing abruptly. It must NOT keep the line's fill brighter than any other inactive
            // line — the renderer treats every non-active line's words identically regardless of
            // Sung/NotSung, so line opacity can go back to tracking lineState alone.
            val stillFinalizing = lineState == ElementState.Sung &&
                (shouldFinalize(lines, lineIdx, processedPosition) || !isSettled(cachedWordStates[lineIdx]))
            val afterActive = states.getOrNull(lineIdx - 1) == ElementState.Active
            val opacity = animateLineOpacity(lineIdx, line, lineState, isActive, lyricsType, minimalLook, allSung, afterActive)
            val lineScale = when {
                line.isInterlude -> animateInterludeScale(lineIdx, line, processedPosition, isActive)
                // Mixed.css `.Fullscreen.MinimalLyricsMode .line:not(.musical-line)`.
                config.isMinimal && !line.isSongwriter -> animateTweenScale(lineIdx, when {
                    lineState == ElementState.Active || allSung || !minimalLook -> 1f
                    lineState == ElementState.Sung -> MINIMAL_SUNG_SCALE
                    else -> MINIMAL_NOT_SUNG_SCALE
                })
                // Line-mode active line scales to 1.05 (CSS: data-lyrics-type="Line" .line.Active).
                lyricsType == LyricsType.Line && !line.isSongwriter ->
                    animateTweenScale(lineIdx, if (isActive) LyricsLayoutCalculator.ACTIVE_LINE_SCALE else 1f)
                else -> 1f
            }

            val wordStates: List<WordAnimState>
            var lineGradient = cachedLineGradient[lineIdx] ?: -20f
            var lineGlow = cachedLineGlow[lineIdx] ?: 0f

            if (lyricsType == LyricsType.Line && !line.isInterlude) {
                // ── Line mode: whole-line gradient + glow spring; no word processing. ──
                when (lineState) {
                    ElementState.Active -> {
                        val pct = progress(processedPosition, line.startMs, line.endMs)
                        lineGradient = if (config.isSimple) 100f else pct * 100f
                        if (config.isSimple) {
                            lineGlow = 0f
                        } else {
                            val spring = lineGlowSprings.getOrPut(lineIdx) {
                                SpringSimulation(lineGlowSpline.at(0f), LINE_GLOW_FREQUENCY, LINE_GLOW_DAMPING)
                            }
                            spring.setGoal(lineGlowSpline.at(pct))
                            lineGlow = spring.step(deltaTime)
                        }
                    }
                    ElementState.NotSung -> lineGradient = if (config.isSimple) 100f else -20f
                    ElementState.Sung -> lineGradient = 100f
                }
                cachedLineGradient[lineIdx] = lineGradient
                cachedLineGlow[lineIdx] = lineGlow
                wordStates = if (isActive && romanization != null) romanization.words.mapIndexed { wordIdx, word ->
                    animateWord(word, romanization.words.getOrNull(wordIdx - 1), processedPosition, deltaTime, lineIdx, wordIdx)
                } else emptyList()
            } else {
                wordStates = when {
                    line.isSongwriter -> cachedWordStates.getOrPut(lineIdx) {
                        line.words.map { songwriterWordState(it) }
                    }
                    lineState == ElementState.Active -> {
                        val states = if (line.isInterlude) {
                            animateInterludeDots(line, processedPosition, deltaTime, lineIdx, finalize = false)
                        } else {
                            line.words.mapIndexed { wordIdx, word ->
                                animateWord(word, line.words.getOrNull(wordIdx - 1), processedPosition, deltaTime, lineIdx, wordIdx)
                            }
                        }
                        cachedWordStates[lineIdx] = states
                        states
                    }
                    stillFinalizing -> {
                        // checkNextLine: keep settling toward final targets until the next line is Sung.
                        val states = if (line.isInterlude) {
                            animateInterludeDots(line, processedPosition, deltaTime, lineIdx, finalize = true)
                        } else {
                            line.words.mapIndexed { wordIdx, word ->
                                finalizeWord(word, deltaTime, lineIdx, wordIdx)
                            }
                        }
                        cachedWordStates[lineIdx] = states
                        states
                    }
                    else -> {
                        // Frozen: NotSung lines and long-finished Sung lines are never touched. A seek
                        // back leaves lines that were sung ahead of the song again, so they start over.
                        if (lineState == ElementState.NotSung && cachedWordStates[lineIdx]?.any { it.state != ElementState.NotSung } == true) {
                            forgetLine(lineIdx)
                        }
                        cachedWordStates.getOrPut(lineIdx) { line.words.map { restingWordState(it) } }
                    }
                }
            }

            val blur = if (!config.distanceBlurEnabled || suppressBlur || isActive) 0f
                else blurAmounts.getOrElse(lineIdx) { 0f }

            LineAnimState(
                opacity = opacity,
                blur = blur,
                scale = lineScale,
                isActive = isActive,
                wordStates = wordStates,
                isBackground = line.isBackground,
                isSongwriter = line.isSongwriter,
                lineGradientPercent = lineGradient,
                lineGlow = lineGlow,
                suppressShadows = !config.glowEnabled,
                state = lineState,
            )
        }
    }

    // ── The Apple Music style ───────────────────────────────────────────────────────

    /** The line the Apple Music style measures distances from: the last one sung, kept through gaps. */
    private var appleActiveLine = -1
    /** Each line's place among the lead lines (background vocals share their lead's). */
    private var appleOrdinals = IntArray(0)
    private var appleOrdinalsFor: List<Line>? = null
    /** Each background line's lead line (same group), or -1. */
    private var appleLeads = IntArray(0)
    private val appleFinalStates = mutableMapOf<Int, List<WordAnimState>>()
    private val presenceAnims = mutableMapOf<Int, Animatable<Float, AnimationVector1D>>()
    private val blurAnims = mutableMapOf<Int, Animatable<Float, AnimationVector1D>>()
    private val litAnims = mutableMapOf<Int, Animatable<Float, AnimationVector1D>>()

    /**
     * Every line in the Apple Music style. Its words are worked out from the time alone
     * ([AppleMusicMotion]), so only the line fades and size changes are tweened.
     */
    private fun animateAppleMusic(
        lines: List<Line>,
        states: List<ElementState>,
        t: Double,
        deltaTime: Float,
        userScrolling: Boolean,
        lyricsType: LyricsType,
        romanizationLines: List<Line>?,
    ): List<LineAnimState> {
        if (appleOrdinalsFor !== lines) {
            appleOrdinalsFor = lines
            var ordinal = -1
            appleOrdinals = IntArray(lines.size) { i ->
                val line = lines[i]
                if (!line.isBackground && !line.isInterlude) ordinal++
                ordinal.coerceAtLeast(0)
            }
            appleLeads = IntArray(lines.size) { i ->
                val line = lines[i]
                if (!line.isBackground || line.groupId == null) -1
                else lines.indices.firstOrNull { j ->
                    lines[j].groupId == line.groupId && !lines[j].isBackground && !lines[j].isInterlude
                } ?: -1
            }
            appleActiveLine = -1
            presenceAnims.clear()
            blurAnims.clear()
            litAnims.clear()
        }
        // The last lead line being sung; through a gap, the one sung last.
        lines.indices.lastOrNull { i ->
            states[i] == ElementState.Active && !lines[i].isBackground && !lines[i].isInterlude && !lines[i].isSongwriter
        }?.let { appleActiveLine = it }
        val activeOrdinal = appleOrdinals.getOrElse(appleActiveLine) { -1 }

        return lines.mapIndexed { lineIdx, line ->
            val romanization = if (line.translationReplaces && lyricsType == LyricsType.Syllable) romanizationLines?.getOrNull(lineIdx) else null
            val lineState = states[lineIdx]
            val isActive = !line.isSongwriter && lineState == ElementState.Active
            // Before any line has been sung, distances count from just above the first.
            val lyricsType = if (line.translationReplaces) LyricsType.Line else lyricsType
            val signed = appleOrdinals[lineIdx] - activeOrdinal
            val away = if (isActive) 0 else abs(signed).coerceAtLeast(1)

            val opacityTarget = when {
                line.isSongwriter -> 0.6f
                line.isInterlude -> if (isActive) 1f else 0f
                else -> AppleMusicMotion.lineOpacity(away, userScrolling)
            }
            val opacityAnim = lineOpacityAnims.getOrPut(lineIdx) { Animatable(opacityTarget) }
            if (opacityAnim.targetValue != opacityTarget) {
                val spec = if (line.isInterlude) tween<Float>(MUSICAL_LINE_TRANSITION_MS, easing = OPACITY_EASING)
                    else tween(AppleMusicMotion.OPACITY_MS, easing = CSS_EASE)
                coroutineScope.launch { opacityAnim.animateTo(opacityTarget, spec) }
            }

            val lineScale = when {
                line.isInterlude -> animateInterludeScale(lineIdx, line, t, isActive)
                line.isSongwriter -> 1f
                else -> {
                    val target = if (isActive) 1f else AppleMusicMotion.INACTIVE_SCALE
                    val anim = lineScaleAnims.getOrPut(lineIdx) { Animatable(target) }
                    if (anim.targetValue != target) {
                        val ms = if (target == 1f) AppleMusicMotion.SCALE_IN_MS else AppleMusicMotion.SCALE_OUT_MS
                        coroutineScope.launch { anim.animateTo(target, tween(ms, easing = CSS_EASE)) }
                    }
                    anim.value
                }
            }

            // Background vocals open while they or their lead are sung.
            val presenceTarget = if (!line.isBackground) 1f else {
                val lead = appleLeads[lineIdx]
                if (lineState == ElementState.Active || (lead >= 0 && states[lead] == ElementState.Active)) 1f else 0f
            }
            val presence = if (!line.isBackground) 1f else {
                val anim = presenceAnims.getOrPut(lineIdx) { Animatable(presenceTarget) }
                if (anim.targetValue != presenceTarget) {
                    // Turning back mid-way keeps the spring's speed (Animatable carries it over).
                    coroutineScope.launch {
                        anim.animateTo(presenceTarget, spring(dampingRatio = 1f, stiffness = AppleMusicMotion.BACKGROUND_SPRING_STIFFNESS))
                    }
                }
                anim.value
            }

            val wordStates = when {
                isActive && romanization != null -> romanization.words.mapIndexed { i, word -> appleWordState(word, i, romanization, t) }
                lyricsType == LyricsType.Line && !line.isInterlude -> emptyList()
                line.isSongwriter -> cachedWordStates.getOrPut(lineIdx) { line.words.map { songwriterWordState(it) } }
                line.isInterlude -> appleDots(line, t)
                // Not reached yet: at rest. Long done: risen and still.
                t < line.startMs -> cachedWordStates.getOrPut(lineIdx) {
                    line.words.mapIndexed { i, word -> appleWordState(word, i, line, Double.NEGATIVE_INFINITY) }
                }
                t > line.endMs + APPLE_SETTLE_MS -> appleFinalStates.getOrPut(lineIdx) {
                    line.words.mapIndexed { i, word -> appleWordState(word, i, line, Double.POSITIVE_INFINITY) }
                }
                else -> line.words.mapIndexed { i, word -> appleWordState(word, i, line, t) }
            }

            // Background vocals stay sharp while their line is sung.
            val blurTarget = when {
                !config.distanceBlurEnabled || userScrolling || isActive || line.isInterlude -> 0f
                line.isBackground && presenceTarget == 1f -> 0f
                else -> AppleMusicMotion.blurEm(if (signed == 0) -1 else signed) * APPLE_BLUR_CSS_PX_PER_EM *
                    (if (line.isBackground) AppleMusicMotion.BACKGROUND_BLUR_SHARE else 1f)
            }
            val blurAnim = blurAnims.getOrPut(lineIdx) { Animatable(blurTarget) }
            if (blurAnim.targetValue != blurTarget) {
                // A touch scroll clears it at once; otherwise lines soften and sharpen smoothly.
                if (userScrolling) coroutineScope.launch { blurAnim.snapTo(blurTarget) }
                else coroutineScope.launch { blurAnim.animateTo(blurTarget, tween(AppleMusicMotion.BLUR_FADE_MS, easing = CSS_EASE)) }
            }
            val blur = blurAnim.value

            // Lit while sung; after, the words dim back over LINE_SETTLE_MS instead of at once.
            val litTarget = if (lineState == ElementState.Active) 1f else 0f
            val litAnim = litAnims.getOrPut(lineIdx) { Animatable(0f) }
            if (litAnim.targetValue != litTarget) {
                if (litTarget == 1f) coroutineScope.launch { litAnim.snapTo(1f) }
                else coroutineScope.launch { litAnim.animateTo(0f, tween(AppleMusicMotion.LINE_SETTLE_MS, easing = CSS_EASE)) }
            }

            val folded = AppleMusicMotion.BACKGROUND_FOLDED_SCALE
            LineAnimState(
                opacity = opacityAnim.value * (if (line.isBackground) AppleMusicMotion.backgroundAlpha(presence) else 1f),
                blur = blur,
                scale = if (line.isBackground) lineScale * (folded + (1f - folded) * presence) else lineScale,
                isActive = isActive,
                wordStates = wordStates,
                isBackground = line.isBackground,
                isSongwriter = line.isSongwriter,
                // Line-synced lyrics light up whole.
                lineGradientPercent = if (lineState == ElementState.NotSung) -20f else 100f,
                lineGlow = 0f,
                suppressShadows = !config.glowEnabled,
                state = lineState,
                presence = presence,
                lit = if (lineState == ElementState.Sung) litAnim.value else 0f,
                groupScale = if (line.isInterlude) AppleMusicMotion.Dots(line.duration.toFloat()).scale((t - line.startMs).toFloat()) else 1f,
            )
        }
    }

    /**
     * A word in the Apple Music style at [t]: its wipe as plain progress (0..100, the renderer adds
     * the soft edge), its rise, and for a held word its letters' emphasis. Infinite [t] gives the
     * resting and the finished pose.
     */
    private fun appleWordState(word: Word, index: Int, line: Line, t: Double): WordAnimState {
        val state = elementState(t, word.startMs, word.endMs)
        val progress = progress(t, word.startMs, word.endMs)
        val lineEnd = if (t >= line.endMs) line.endMs else null
        val lift = AppleMusicMotion.WORD_LIFT_EM * (if (line.isBackground) 2f else 1f) *
            AppleMusicMotion.wordRise(t, word.startMs, word.endMs, lineEnd)
        // The letter synthesizer only splits the words this style emphasizes.
        if (word.isLetterGroup && word.letters.isNotEmpty()) {
            val count = word.letters.size
            val last = index == line.words.lastIndex
            return WordAnimState(
                scale = 1f, yOffset = -lift, glow = 0f,
                gradientPosition = progress * 100f,
                state = state,
                isLetterGroup = true,
                letterStates = List(count) { i ->
                    val pose = AppleMusicMotion.emphasisLetter(t, word.startMs, word.endMs, i, count, last, line.isBackground)
                    LetterAnimState(
                        gradientPosition = progress * 100f,
                        scale = pose.scale,
                        yOffset = pose.yOffset - lift,
                        glow = pose.glow,
                        xOffset = pose.xOffset,
                        glowRadius = pose.glowRadiusEm,
                    )
                },
            )
        }
        return WordAnimState(
            scale = 1f,
            yOffset = -lift,
            glow = 0f,
            gradientPosition = progress * 100f,
            state = state,
            isLetterGroup = false,
        )
    }

    /**
     * An instrumental break's three dots in the Apple Music style: they light in turn, while the
     * group breathes as one ([LineAnimState.groupScale]); the line's own scale opens its room.
     */
    private fun appleDots(line: Line, t: Double): List<WordAnimState> {
        val dots = AppleMusicMotion.Dots(line.duration.toFloat())
        val at = (t - line.startMs).toFloat()
        val alpha = dots.alpha(at)
        val state = elementState(t, line.startMs, line.endMs)
        return List(3) { i ->
            WordAnimState(
                scale = 1f,
                yOffset = 0f,
                // 'glow' carries a dot's opacity for the interlude draw.
                glow = alpha * dots.dotAlpha(i, at),
                dotGlow = 0f,
                gradientPosition = if (state == ElementState.Sung) 100f else 0f,
                state = state,
            )
        }
    }

    // ── Line-level pieces ───────────────────────────────────────────────────────────

    private fun animateLineOpacity(
        lineIdx: Int,
        line: Line,
        lineState: ElementState,
        isActive: Boolean,
        lyricsType: LyricsType,
        minimalLook: Boolean,
        allSung: Boolean,
        afterActive: Boolean,
    ): Float {
        val target = when {
            line.isSongwriter -> 0.6f
            line.isInterlude -> if (isActive) 1f else 0f
            // Reference: bg-lines have NO opacity override — they use the standard .line state
            // opacities; only their gradient alphas differ (0.6/0.3, applied in the renderer).
            minimalLook && allSung -> config.opacitySung
            minimalLook -> when (lineState) {
                ElementState.Active -> config.opacityActive
                ElementState.NotSung -> MINIMAL_NOT_SUNG_OPACITY
                // `.line.Active + .line.Sung`: a finished line right under the active one stays faint.
                ElementState.Sung -> if (afterActive) MINIMAL_AFTER_ACTIVE_OPACITY else 0f
            }
            else -> when (lineState) {
                ElementState.Active -> config.opacityActive
                ElementState.NotSung -> config.opacityNotSung
                ElementState.Sung -> config.opacitySung
            }
        }
        val animatable = lineOpacityAnims.getOrPut(lineIdx) { Animatable(target) }
        if (animatable.targetValue != target) {
            val spec = when {
                line.isInterlude -> tween<Float>(MUSICAL_LINE_TRANSITION_MS, easing = OPACITY_EASING)
                config.isMinimal && !line.isSongwriter -> tween<Float>(MINIMAL_TRANSITION_MS, easing = LinearEasing)
                // The line-synced `.line` rule swaps the easing for its scale curve.
                lyricsType == LyricsType.Line -> tween<Float>(LINE_TRANSITION_MS, easing = SCALE_EASING)
                else -> tween<Float>(LINE_TRANSITION_MS, easing = OPACITY_EASING)
            }
            coroutineScope.launch { animatable.animateTo(target, spec) }
        }
        return animatable.value
    }

    /**
     * Interlude dot-group visibility (the line's height collapse and its pre-hidden state): 1 while active until 500ms before the line ends,
     * 0 otherwise. Non-interlude lines have no line-level scale (reference Syllable mode).
     */
    private fun animateInterludeScale(
        lineIdx: Int,
        line: Line,
        processedPosition: Double,
        isActive: Boolean,
    ): Float {
        if (!line.isInterlude) return 1f
        val preHidden = processedPosition > line.endMs - PRE_HIDDEN_DOT_LINE_MS
        val target = if (isActive && !preHidden) 1f else 0f
        val animatable = lineScaleAnims.getOrPut(lineIdx) { Animatable(target) }
        if (animatable.targetValue != target) {
            // The collapse (1→0, at pre-hidden) uses a slower 0.4s dip-then-overshoot
            // curve; expansion (0→1, on activation) keeps the default line-transition tween.
            val duration = if (target == 0f) DOT_GROUP_COLLAPSE_MS else DOT_GROUP_EXPANSION_MS
            // The Apple Music style's dots already swell before they go (AppleMusicMotion.Dots).
            val easing = when {
                config.isAppleMusic -> CSS_EASE
                target == 0f -> DOT_GROUP_COLLAPSE_EASING
                else -> SCALE_EASING
            }
            coroutineScope.launch {
                animatable.animateTo(target, tween(duration, easing = easing))
            }
        }
        return animatable.value
    }

    /** Generic line-scale tween (Line-mode active-line 1.05, Minimal's shrink; CSS transitions). */
    private fun animateTweenScale(lineIdx: Int, target: Float): Float {
        val animatable = lineScaleAnims.getOrPut(lineIdx) { Animatable(target) }
        if (animatable.targetValue != target) {
            val spec = if (config.isMinimal) tween<Float>(MINIMAL_TRANSITION_MS, easing = EASE_IN_OUT)
                else tween(LINE_TRANSITION_MS, easing = SCALE_EASING)
            coroutineScope.launch { animatable.animateTo(target, spec) }
        }
        return animatable.value
    }

    private fun applyBlur(lines: List<Line>, activeIndex: Int, processedPosition: Double) {
        for (i in lines.indices) {
            val state = elementState(processedPosition, lines[i].startMs, lines[i].endMs)
            val distance = abs(i - activeIndex)
            blurAmounts[i] = if (state == ElementState.Active || distance == 0) 0f
                else min(BLUR_MULTIPLIER * distance, MAX_BLUR)
        }
    }

    /** A Sung line keeps stepping while the next line is NotSung/Active, or when it's the last line. */
    private fun shouldFinalize(lines: List<Line>, lineIdx: Int, processedPosition: Double): Boolean {
        val next = lines.getOrNull(lineIdx + 1) ?: return true
        return elementState(processedPosition, next.startMs, next.endMs) != ElementState.Sung
    }

    /**
     * True once a settled line's springs have actually reached their final (fully-sung) values.
     * The array-adjacency check in [shouldFinalize] can flip false before that happens — e.g. a
     * background line's "next line" in list order may already be Sung from a much earlier point
     * in the song — which would otherwise freeze the line mid-transition, never fully lighting up.
     */
    private fun isSettled(states: List<WordAnimState>?): Boolean {
        if (states == null) return false
        return states.all { w ->
            w.gradientPosition >= 99.5f && w.glow < 0.02f &&
                (!w.isLetterGroup || w.letterStates.all { it.gradientPosition >= 99.5f && it.glow < 0.02f })
        }
    }

    // ── Word processing (Active line) ───────────────────────────────────────────────

    private fun animateWord(
        word: Word,
        previous: Word?,
        processedPosition: Double,
        deltaTime: Float,
        lineIndex: Int,
        wordIndex: Int,
    ): WordAnimState {
        val springs = wordSprings(lineIndex, wordIndex)
        val wordState = elementState(processedPosition, word.startMs, word.endMs)
        val percentage = progress(processedPosition, word.startMs, word.endMs)
        val simple = config.isSimple
        val animateStyle = simple && config.simpleAnimationStyle == SimpleAnimationStyle.ANIMATE
        val gradientBase = if (simple) -50f else -20f

        val targetScale: Float
        val targetYOffset: Float
        val targetGlow: Float
        val targetGradientPos: Float
        when (wordState) {
            ElementState.Active -> {
                targetScale = scaleSpline.at(percentage)
                targetYOffset = yOffsetSpline.at(percentage)
                targetGlow = glowSpline.at(percentage)
                // Animate: the SLM_Animation keyframes, -27.5% to 100% linearly over the word.
                targetGradientPos = if (animateStyle) SLM_FROM + (100f - SLM_FROM) * percentage
                    else gradientBase + 120f * percentage
            }
            ElementState.NotSung -> {
                targetScale = scaleSpline.at(0f)
                targetYOffset = yOffsetSpline.at(0f)
                targetGlow = glowSpline.at(0f)
                targetGradientPos = if (animateStyle && !word.isLetterGroup && previous != null) {
                    preSweep(previous, processedPosition)
                } else gradientBase
            }
            ElementState.Sung -> {
                targetScale = scaleSpline.at(1f)
                targetYOffset = yOffsetSpline.at(1f)
                targetGlow = glowSpline.at(1f)
                targetGradientPos = 100f
            }
        }

        springs.scale.setGoal(targetScale)
        springs.yOffset.setGoal(targetYOffset)
        springs.glow.setGoal(targetGlow)
        val currentScale = springs.scale.step(deltaTime)
        val currentYOffset = springs.yOffset.step(deltaTime)
        val currentGlow = springs.glow.step(deltaTime)

        val isLetterGroup = word.isLetterGroup && word.letters.isNotEmpty()
        val letterStates = if (isLetterGroup) {
            animateLetters(word, wordState, processedPosition, deltaTime, lineIndex, wordIndex)
        } else emptyList()

        return WordAnimState(
            // Simple mode: word scale and glow springs do nothing.
            scale = if (simple) 1f else currentScale,
            yOffset = currentYOffset,
            glow = if (simple) 0f else currentGlow,
            gradientPosition = targetGradientPos,
            state = wordState,
            isLetterGroup = isLetterGroup,
            letterStates = letterStates,
        )
    }

    private fun animateLetters(
        word: Word,
        wordState: ElementState,
        processedPosition: Double,
        deltaTime: Float,
        lineIndex: Int,
        wordIndex: Int,
    ): List<LetterAnimState> {
        val simple = config.isSimple
        val gradientBase = if (simple) -50f else -20f

        // 4b/4c: NotSung/Sung word inside an Active line — letters chase resting/final targets.
        if (wordState != ElementState.Active) {
            val t = if (wordState == ElementState.NotSung) 0f else 1f
            val gradient = if (wordState == ElementState.NotSung) gradientBase else 100f
            return word.letters.mapIndexed { li, _ ->
                val s = letterSprings(lineIndex, wordIndex, li)
                s.scale.setGoal(letterScaleSpline.at(t))
                s.yOffset.setGoal(letterYOffsetSpline.at(t))
                s.glow.setGoal(glowSpline.at(t))
                LetterAnimState(
                    gradientPosition = gradient,
                    scale = s.scale.step(deltaTime),
                    yOffset = s.yOffset.step(deltaTime),
                    glow = s.glow.step(deltaTime),
                )
            }
        }

        // 4a: Active word — two-phase per-letter targets.
        var activeLetterIndex = -1
        var activeLetterPercentage = 0f
        for (i in word.letters.indices) {
            val l = word.letters[i]
            if (elementState(processedPosition, l.startMs, l.endMs) == ElementState.Active) {
                activeLetterIndex = i
                activeLetterPercentage = progress(processedPosition, l.startMs, l.endMs)
                break
            }
        }

        val wordDuration = word.endMs - word.startMs

        return word.letters.mapIndexed { k, letter ->
            val letterState = elementState(processedPosition, letter.startMs, letter.endMs)

            // Defaults: resting.
            var targetScale = letterScaleSpline.at(0f)
            var targetYOffset = letterYOffsetSpline.at(0f)
            var targetGlow = glowSpline.at(0f)

            if (activeLetterIndex != -1) {
                // Simple mode blends by whole-word progress with strength multipliers; full mode
                // by the active letter's own progress.
                val percentageCount = if (simple)
                    progress(processedPosition, word.startMs, word.endMs) else activeLetterPercentage
                val longer = wordDuration > SIMPLE_LETTER_LONGER_THAN_MS
                val scaleMult = if (simple) (if (longer) SIMPLE_LONGER_SCALE else SIMPLE_SHORTER_SCALE) else 1f
                val yOffMult = if (simple) (if (longer) SIMPLE_LONGER_Y_OFFSET else SIMPLE_SHORTER_Y_OFFSET) else 1f
                val glowMult = if (simple) (if (longer) SIMPLE_LONGER_GLOW else SIMPLE_SHORTER_GLOW) else 1f

                val baseScale = letterScaleSpline.at(percentageCount) * scaleMult
                val baseYOffset = letterYOffsetSpline.at(percentageCount) * yOffMult
                val baseGlow = glowSpline.at(percentageCount) * glowMult

                val restingScale = letterScaleSpline.at(0f)
                val restingYOffset = letterYOffsetSpline.at(0f)
                val restingGlow = glowSpline.at(0f)

                val distance = abs(k - activeLetterIndex).toFloat()
                val falloff = (1f / (1f + distance.pow(2.8f))).coerceAtLeast(0f)
                val glowFalloff = (1f / (1f + distance * 0.9f)).coerceAtLeast(0f)

                targetScale = restingScale + (baseScale - restingScale) * falloff
                targetYOffset = restingYOffset + (baseYOffset - restingYOffset) * falloff
                targetGlow = restingGlow + (baseGlow - restingGlow) * glowFalloff
            }

            // Overrides (reference order): NotSung letters reset to resting (full mode only);
            // Sung letters in a word with no active letter hold GlowSpline.at(SungLetterGlow).
            if (letterState == ElementState.NotSung && !simple) {
                targetScale = letterScaleSpline.at(0f)
                targetYOffset = letterYOffsetSpline.at(0f)
                targetGlow = glowSpline.at(0f)
            } else if (letterState == ElementState.Sung && activeLetterIndex == -1) {
                targetGlow = glowSpline.at(SUNG_LETTER_GLOW)
            }

            // Gradient: only the actual active letter sweeps (eased; Animate: SLM_Animation's
            // linear -27.5% to 100%); rest hold base/final.
            val targetGradient = when (letterState) {
                ElementState.NotSung -> gradientBase
                ElementState.Sung -> 100f
                ElementState.Active -> when {
                    simple && config.simpleAnimationStyle == SimpleAnimationStyle.ANIMATE ->
                        SLM_FROM + (100f - SLM_FROM) * progress(processedPosition, letter.startMs, letter.endMs)
                    k == activeLetterIndex -> gradientBase + 120f * easeSinOut(activeLetterPercentage)
                    else -> gradientBase
                }
            }

            val s = letterSprings(lineIndex, wordIndex, k)
            s.scale.setGoal(targetScale)
            s.yOffset.setGoal(targetYOffset)
            s.glow.setGoal(targetGlow)

            LetterAnimState(
                gradientPosition = targetGradient,
                scale = s.scale.step(deltaTime),
                yOffset = s.yOffset.step(deltaTime),
                glow = s.glow.step(deltaTime),
            )
        }
    }

    /** The Sung-line settle pass (`checkNextLine`): step everything toward its final state. */
    private fun finalizeWord(
        word: Word,
        deltaTime: Float,
        lineIndex: Int,
        wordIndex: Int,
    ): WordAnimState {
        val springs = wordSprings(lineIndex, wordIndex)
        springs.scale.setGoal(scaleSpline.at(1f))
        springs.yOffset.setGoal(yOffsetSpline.at(1f))
        springs.glow.setGoal(glowSpline.at(1f))
        val currentScale = springs.scale.step(deltaTime)
        val currentYOffset = springs.yOffset.step(deltaTime)
        val currentGlow = springs.glow.step(deltaTime)

        val isLetterGroup = word.isLetterGroup && word.letters.isNotEmpty()
        val letterStates = if (isLetterGroup) {
            word.letters.mapIndexed { li, _ ->
                val s = letterSprings(lineIndex, wordIndex, li)
                s.scale.setGoal(letterScaleSpline.at(1f))
                s.yOffset.setGoal(letterYOffsetSpline.at(1f))
                s.glow.setGoal(glowSpline.at(1f))
                LetterAnimState(
                    gradientPosition = 100f,
                    scale = s.scale.step(deltaTime),
                    yOffset = s.yOffset.step(deltaTime),
                    glow = s.glow.step(deltaTime),
                )
            }
        } else emptyList()

        val simple = config.isSimple
        return WordAnimState(
            scale = if (simple) 1f else currentScale,
            yOffset = currentYOffset,
            glow = if (simple) 0f else currentGlow,
            gradientPosition = 100f,
            state = ElementState.Sung,
            isLetterGroup = isLetterGroup,
            letterStates = letterStates,
        )
    }

    // ── Interlude dots ──────────────────────────────────────────────────────────────

    private fun animateInterludeDots(
        line: Line,
        processedPosition: Double,
        deltaTime: Float,
        lineIndex: Int,
        finalize: Boolean,
    ): List<WordAnimState> {
        // Dot timing: each dot ends slightly early (applier: dotPadding = -550/3 per dot).
        val span = ((line.duration - 550L).coerceAtLeast(90L)).toFloat() / 3f
        return (0 until 3).map { dotIdx ->
            val dotStart = line.startMs + (dotIdx * span).toLong()
            val dotEnd = line.startMs + ((dotIdx + 1) * span).toLong()
            val state = if (finalize) ElementState.Sung
                else elementState(processedPosition, dotStart, dotEnd)
            val pct = progress(processedPosition, dotStart, dotEnd)

            fun sample(sp: CubicSplineInterpolator) = when (state) {
                ElementState.Active -> sp.at(pct)
                ElementState.NotSung -> sp.at(0f)
                ElementState.Sung -> sp.at(1f)
            }

            val key = lineIndex.toLong() * 3L + dotIdx.toLong()
            val springs = dotSpringsMap.getOrPut(key) { DotSprings() }
            springs.scale.setGoal(sample(dotScaleSpline))
            springs.yOffset.setGoal(sample(dotYOffsetSpline))
            springs.glow.setGoal(sample(dotGlowSpline))
            springs.opacity.setGoal(sample(dotOpacitySpline))

            WordAnimState(
                scale = if (config.isSimple) 1f else springs.scale.step(deltaTime),
                yOffset = if (config.isSimple) 0f else springs.yOffset.step(deltaTime),
                glow = springs.opacity.step(deltaTime),   // 'glow' carries dot opacity for the base draw
                dotGlow = if (config.isSimple) 0f else springs.glow.step(deltaTime),
                gradientPosition = if (state == ElementState.Sung) 100f else 0f,
                state = state,
            )
        }
    }

    // ── Frozen-state synthesis ──────────────────────────────────────────────────────

    /** Initial resting state for a word that has never been animated (reference build-time styles). */
    private fun restingWordState(word: Word): WordAnimState {
        val simple = config.isSimple
        val gradientBase = if (simple) -50f else -20f
        val isLetterGroup = word.isLetterGroup && word.letters.isNotEmpty()
        return WordAnimState(
            // Simple mode's applyer leaves a word's scale and lift unset until the animator reaches it.
            scale = if (simple) 1f else scaleSpline.at(0f),
            yOffset = if (simple) 0f else yOffsetSpline.at(0f),
            glow = 0f,
            gradientPosition = gradientBase,
            state = ElementState.NotSung,
            isLetterGroup = isLetterGroup,
            letterStates = if (isLetterGroup) word.letters.map {
                LetterAnimState(
                    gradientPosition = gradientBase,
                    scale = letterScaleSpline.at(0f),
                    yOffset = letterYOffsetSpline.at(0f),
                    glow = 0f,
                )
            } else emptyList(),
        )
    }

    /** Songwriter credits render as plain, fully-swept text (they aren't lyrics). */
    private fun songwriterWordState(word: Word): WordAnimState = WordAnimState(
        scale = 1f,
        yOffset = 0f,
        glow = 0f,
        gradientPosition = 100f,
        state = ElementState.Sung,
        isLetterGroup = false,
        letterStates = emptyList(),
    )

    // ── Spring lookup ───────────────────────────────────────────────────────────────

    /** Drops what a line remembers of being sung, so it animates from the start the next time. */
    private fun forgetLine(lineIdx: Int) {
        cachedWordStates.remove(lineIdx)
        cachedLineGradient.remove(lineIdx)
        cachedLineGlow.remove(lineIdx)
        lineGlowSprings.remove(lineIdx)
        wordSpringsMap.keys.removeAll { it / 100000L == lineIdx.toLong() }
        letterSpringsMap.keys.removeAll { it / 1000000L == lineIdx.toLong() }
        dotSpringsMap.keys.removeAll { it / 3L == lineIdx.toLong() }
    }

    private fun wordSprings(lineIndex: Int, wordIndex: Int): WordSprings =
        wordSpringsMap.getOrPut(lineIndex.toLong() * 100000L + wordIndex.toLong()) { WordSprings() }

    private fun letterSprings(lineIndex: Int, wordIndex: Int, letterIndex: Int): LetterSprings =
        letterSpringsMap.getOrPut(
            lineIndex.toLong() * 1000000L + wordIndex.toLong() * 100L + letterIndex.toLong()
        ) { LetterSprings() }

    // ── Helpers ─────────────────────────────────────────────────────────────────────

    /**
     * Simple mode's Animate style readies a word before it is sung: once the word before it has
     * run 60% of its length less 22ms (a held, letter-split word: 84.5% less 130ms), it eases from
     * -50% to the SLM_Animation start of -27.5% over 125ms (250ms), then holds there
     * (Pre_SLM_GradientAnimation, `forwards`).
     */
    private fun preSweep(previous: Word, t: Double): Float {
        if (t < previous.startMs) return -50f
        val held = previous.isLetterGroup && previous.letters.isNotEmpty()
        val duration = previous.endMs - previous.startMs
        val delay = (if (held) duration * 0.845 - 130 else duration * 0.6 - 22).coerceAtLeast(0.0)
        val length = if (held) 250.0 else 125.0
        val x = ((t - previous.startMs - delay) / length).coerceIn(0.0, 1.0).toFloat()
        return -50f + (SLM_FROM + 50f) * x
    }

    private fun elementState(t: Double, startMs: Long, endMs: Long): ElementState = when {
        t < startMs -> ElementState.NotSung
        t >= endMs -> ElementState.Sung
        else -> ElementState.Active
    }

    private fun progress(t: Double, startMs: Long, endMs: Long): Float {
        if (t <= startMs) return 0f
        if (t >= endMs) return 1f
        return (t - startMs).toFloat() / (endMs - startMs).toFloat()
    }

    private fun easeSinOut(t: Float): Float = sin(t * (PI.toFloat() / 2f))
}
