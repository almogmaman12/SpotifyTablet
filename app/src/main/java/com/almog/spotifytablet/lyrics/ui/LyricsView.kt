package com.almog.spotifytablet.lyrics.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.almog.spotifytablet.Constants
import com.almog.spotifytablet.SettingsActivity
import com.almog.spotifytablet.lyrics.model.LyricLine
import com.almog.spotifytablet.lyrics.model.LyricTrack
import com.almog.spotifytablet.lyrics.model.TrackRhythmContext
import com.almog.spotifytablet.lyrics.model.WordSync
import com.almog.spotifytablet.lyrics.model.calculateRhythmSpringSpec
import com.almog.spotifytablet.lyrics.model.calculateRhythmWordScale
import com.almog.spotifytablet.lyrics.model.calculateRhythmWordYOffset
import com.almog.spotifytablet.lyrics.model.calculateWordProgressEasing
import com.almog.spotifytablet.lyrics.viewmodel.LyricsUiState
import com.almog.spotifytablet.lyrics.viewmodel.LyricsViewModel
import com.almog.spotifytablet.lyrics.viewmodel.findActiveLineIndex
import kotlinx.coroutines.delay

/**
 * Predictive vocal anticipation offset in milliseconds.
 * Starts syllable scaling/transient animation slightly ahead for responsive feel.
 */
private const val PRE_ROLL_OFFSET_MS = 45L
private const val SPICY_SWEEP_FEATHER = 0.20f

/**
 * Spicy's gradient is a moving 20%-wide band, not a growing left-to-right fill.
 * Its direction is vertical for LTR lyrics (CSS 180deg) and right-to-left for RTL
 * lyrics (CSS -90deg). The shader uses the actual text bounds, including off-canvas
 * stop positions at the beginning and end of a word.
 */
private class SpicySweepBrush(
    private val progress: Float,
    private val isRtl: Boolean,
    private val litColor: Color,
    private val dimColor: Color
) : ShaderBrush() {
    override fun createShader(size: Size): Shader {
        val rawStart = -0.20f + 1.20f * progress.coerceIn(0f, 1f)
        val rawEnd = rawStart + SPICY_SWEEP_FEATHER
        val start = if (isRtl) {
            Offset(size.width * (1f - rawStart), size.height * 0.5f)
        } else {
            Offset(size.width * 0.5f, size.height * rawStart)
        }
        val end = if (isRtl) {
            Offset(size.width * (1f - rawEnd), size.height * 0.5f)
        } else {
            Offset(size.width * 0.5f, size.height * rawEnd)
        }
        return LinearGradientShader(
            from = start,
            to = end,
            colors = listOf(litColor, dimColor),
            tileMode = TileMode.Clamp
        )
    }
}


/**
 * Small port of Spicy's damped spring. It advances only when the active lyric line
 * receives a new playback position, so it adds no per-word frame loop or coroutine.
 */
private class SpicySpring(
    startPosition: Float,
    private val frequency: Float,
    private val damping: Float
) {
    private var position = startPosition
    private var velocity = 0f
    private var goal = startPosition
    private var lastPositionMs: Long? = null

    fun update(nextGoal: Float, positionMs: Long): Float {
        goal = nextGoal
        val previous = lastPositionMs
        lastPositionMs = positionMs
        if (previous == null) return position
        val deltaMs = positionMs - previous
        if (deltaMs <= 0L) return position
        if (deltaMs > 100L) {
            position = goal
            velocity = 0f
            return position
        }
        return step((deltaMs / 1000f).coerceAtMost(0.05f))
    }

    private fun step(dt: Float): Float {
        val f = frequency * (2f * Math.PI.toFloat())
        val q = kotlin.math.exp(-damping * f * dt)
        val c = kotlin.math.sqrt(1f - damping * damping)
        val i = kotlin.math.cos(dt * f * c)
        val j = kotlin.math.sin(dt * f * c)
        val z = j / c
        val y = j / (f * c)
        val offset = position - goal

        position = (offset * (i + z * damping) + velocity * y) * q + goal
        velocity = (velocity * (i - z * damping) - offset * (z * f)) * q
        return position
    }
}

private fun smoothStep(value: Float): Float {
    val x = value.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

/** Spicy Lyrics' word scale profile: 0.95 at attack, a small peak at 70%, then settles to 1. */
private fun spicyScale(progress: Float, peakScale: Float): Float {
    val p = progress.coerceIn(0f, 1f)
    return if (p <= 0.7f) {
        0.95f + (peakScale - 0.95f) * smoothStep(p / 0.7f)
    } else {
        peakScale + (1f - peakScale) * smoothStep((p - 0.7f) / 0.3f)
    }
}

/** The subtle lift used by Spicy Lyrics, expressed as a fraction of the lyric font size. */
private fun spicyYOffset(progress: Float): Float {
    val p = progress.coerceIn(0f, 1f)
    return if (p <= 0.9f) {
        0.01f + ((-1f / 60f) - 0.01f) * smoothStep(p / 0.9f)
    } else {
        (-1f / 60f) * (1f - smoothStep((p - 0.9f) / 0.1f))
    }
}

/** Per-letter lift range from Spicy's LetterYOffsetRange. */
private fun spicyLetterYOffset(progress: Float): Float {
    val p = progress.coerceIn(0f, 1f)
    return if (p <= 0.9f) {
        0.01f + ((-1f / 56f) - 0.01f) * smoothStep(p / 0.9f)
    } else {
        (-1f / 56f) * (1f - smoothStep((p - 0.9f) / 0.1f))
    }
}

/** Brief attack glow: rise by 15%, hold to 60%, then fade away. */
private fun spicyGlow(progress: Float): Float {
    val p = progress.coerceIn(0f, 1f)
    return when {
        p < 0.15f -> smoothStep(p / 0.15f)
        p <= 0.6f -> 1f
        else -> 1f - smoothStep((p - 0.6f) / 0.4f)
    }
}

private fun isRtlText(text: String): Boolean {
    for (character in text) {
        when (Character.getDirectionality(character)) {
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
            else -> Unit
        }
    }
    return false
}

/** Avoid splitting Hebrew, Arabic, and other joining scripts into per-character composables. */
private fun canSplitIntoLetters(text: String): Boolean =
    !isRtlText(text) && text.none { it.code in 0x0590..0x0DFF }

private fun isLetterCapableDuration(durationMs: Long, word: WordSync): Boolean =
    durationMs >= 1400L && word.graphemes.size in 2..12 && canSplitIntoLetters(word.text)

private val LineTransformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
private val ShadowOffsetGlow = Offset(0f, 0f)

@Composable
fun LyricsView(
    viewModel: LyricsViewModel,
    modifier: Modifier = Modifier,
    onLineClicked: ((Long) -> Unit)? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LyricsContent(
        uiState = uiState,
        modifier = modifier,
        onLineClicked = onLineClicked,
        onUserScrollStateChanged = { isScrolling ->
            viewModel.setUserScrolling(isScrolling)
        }
    )
}

@Composable
fun LyricsContent(
    uiState: LyricsUiState,
    modifier: Modifier = Modifier,
    onLineClicked: ((Long) -> Unit)? = null,
    onUserScrollStateChanged: ((Boolean) -> Unit)? = null
) {
    val track = uiState.track
    val isAnimationEnabled = uiState.isAnimationEnabled
    val anchor = uiState.anchor

    // Frame-driven position: smooth 60fps position computed from SystemClock.elapsedRealtime()
    // inside Compose’s own vsync-aligned callback. Completely decoupled from Handler scheduling
    // jitter on the main thread — long Glide/OkHttp/Blurry frames can no longer bleed in.
    // Frame-driven position: smooth vsync position computed from SystemClock.elapsedRealtime()
    // inside Compose's own vsync-aligned callback.
    var currentPositionMs by remember { mutableLongStateOf(anchor.positionMs) }
    var displayPositionMs by remember { mutableLongStateOf(anchor.positionMs) }
    // Stable provider: draw/layer lambdas can read playback time without rebuilding Text composables.
    val positionProvider = remember { { displayPositionMs } }
    val displayLines = remember(track?.lines) {
        track?.lines?.filterNot { it.isBackground } ?: emptyList()
    }

    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences(Constants.PREF_NAME, android.content.Context.MODE_PRIVATE)
    }
    val isPauseDotsPrefEnabled = prefs.getBoolean(SettingsActivity.PREF_PAUSE_DOTS, true)

    // Reset positions immediately when the track changes
    LaunchedEffect(track, anchor.positionMs, anchor.anchorRealtimeMs, anchor.isPlaying, anchor.speed) {
        currentPositionMs = anchor.positionMs
        displayPositionMs = anchor.positionMs
    }

    // Temporal locality cache: stores last active line index to enable O(1) checks
    val lastActiveRef = remember(displayLines) { intArrayOf(-1) }
    // Playback position updates each frame; the composition changes only at line boundaries.
    val activeIndexState = remember(displayLines) {
        derivedStateOf {
            val idx = if (displayLines.isNotEmpty()) {
                findActiveLineIndex(displayLines, currentPositionMs, lastActiveRef[0])
            } else -1
            lastActiveRef[0] = idx
            idx
        }
    }

    val pauseInfoState = remember(displayLines, isPauseDotsPrefEnabled) {
        derivedStateOf {
            if (isPauseDotsPrefEnabled) {
                getPauseInfo(displayLines, activeIndexState.value, currentPositionMs)
            } else null
        }
    }
    val isIdleState = remember(displayLines, isPauseDotsPrefEnabled) {
        derivedStateOf { activeIndexState.value == -1 && pauseInfoState.value == null }
    }
    val activeLineIndex = activeIndexState.value

    // Unified frame-driven position loop:
    // Derives smooth vsync-aligned position and drift-smoothing in a SINGLE withFrameNanos pass.
    // When paused, sets exact anchor positions and terminates immediately — ZERO Choreographer
    // callbacks, waking neither CPU nor GPU while paused or idle.
    LaunchedEffect(anchor.isPlaying, anchor.positionMs, anchor.anchorRealtimeMs, anchor.speed) {
        if (!anchor.isPlaying) {
            currentPositionMs = anchor.positionMs
            displayPositionMs = anchor.positionMs
            return@LaunchedEffect
        }

        // On seek or resuming after long pause, snap displayPositionMs to prevent long catch-up drift
        if (Math.abs(anchor.positionMs - displayPositionMs) > 1500L) {
            displayPositionMs = anchor.positionMs
        }

        val firstStart = displayLines.firstOrNull()?.startTimeMs ?: Long.MAX_VALUE
        val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
        var minFrameNanos = 16_666_666L
        var lastFrameNanos = 0L
        var frameCounter = 0

        while (true) {
            if (frameCounter++ % 120 == 0) {
                minFrameNanos = if (powerManager?.isPowerSaveMode == true) 33_333_333L else 16_666_666L
            }

            if (isIdleState.value) {
                // No lyrics or dots are animating: update slowly instead of requesting every vsync.
                val elapsedMs = android.os.SystemClock.elapsedRealtime() - anchor.anchorRealtimeMs
                val pos = anchor.positionMs + (elapsedMs * anchor.speed).toLong()
                currentPositionMs = pos
                displayPositionMs = pos
                val sleepMs = if (pos < firstStart) ((firstStart - pos) / 2).coerceIn(16L, 200L) else 200L
                delay(sleepMs)
                continue
            }

            withFrameNanos { frameNanos ->
                if (frameNanos - lastFrameNanos < minFrameNanos) return@withFrameNanos
                lastFrameNanos = frameNanos
                val elapsedMs = android.os.SystemClock.elapsedRealtime() - anchor.anchorRealtimeMs
                val newPos = anchor.positionMs + (elapsedMs * anchor.speed).toLong()
                currentPositionMs = newPos

                val diff = newPos - displayPositionMs
                displayPositionMs += when {
                    diff <= 0L -> diff
                    diff > 1500L -> diff
                    else -> (diff * 0.3).toLong().coerceAtLeast(1L)
                }
            }
        }
    }


    var manualScrollOffsetLines by remember { mutableIntStateOf(0) }
    var isUserInteracting by remember { mutableStateOf(false) }
    var lastInteractionTime by remember { mutableLongStateOf(0L) }

    // Auto-snap timer: automatically return to active line after 3 seconds of inactivity
    LaunchedEffect(isUserInteracting, lastInteractionTime) {
        if (!isUserInteracting && manualScrollOffsetLines != 0) {
            delay(3000L)
            manualScrollOffsetLines = 0
            onUserScrollStateChanged?.invoke(false)
        }
    }

    val effectiveCenterIndex = (activeLineIndex + manualScrollOffsetLines).coerceIn(
        -1,
        displayLines.lastIndex
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 0.dp)
            .pointerInput(track?.lines?.size) {
                var accumulatedDragPx = 0f
                detectVerticalDragGestures(
                    onDragStart = {
                        accumulatedDragPx = 0f
                        isUserInteracting = true
                        onUserScrollStateChanged?.invoke(true)
                    },
                    onDragEnd = {
                        isUserInteracting = false
                        lastInteractionTime = System.currentTimeMillis()
                    },
                    onDragCancel = {
                        isUserInteracting = false
                        lastInteractionTime = System.currentTimeMillis()
                    },
                    onVerticalDrag = { _, dragAmount ->
                        accumulatedDragPx += dragAmount
                        val thresholdPx = 30f
                        while (accumulatedDragPx <= -thresholdPx) {
                            manualScrollOffsetLines = (manualScrollOffsetLines + 1).coerceAtMost(3)
                            accumulatedDragPx += thresholdPx
                            lastInteractionTime = System.currentTimeMillis()
                        }
                        while (accumulatedDragPx >= thresholdPx) {
                            manualScrollOffsetLines = (manualScrollOffsetLines - 1).coerceAtLeast(-3)
                            accumulatedDragPx -= thresholdPx
                            lastInteractionTime = System.currentTimeMillis()
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        if (track == null || track.lines.isEmpty()) {
            return@BoxWithConstraints
        }

        val rhythmContext = remember(track.bpm) {
            if (track.bpm != null && track.bpm > 0f) {
                TrackRhythmContext(bpm = track.bpm, hasExplicitBpm = true)
            } else {
                TrackRhythmContext.Default
            }
        }

        val isDynamicSpacingPrefEnabled = prefs.getBoolean(SettingsActivity.PREF_DYNAMIC_SPACING, true)
        val lyricsFontSizeSp = prefs.getInt(com.almog.spotifytablet.Constants.PREF_KEY_LYRICS_FONT_SIZE, 32).toFloat()

        val pauseInfo = pauseInfoState.value
        val isPauseActive = pauseInfo != null

        val pauseAlpha by animateFloatAsState(
            targetValue = if (isPauseActive) 1f else 0f,
            animationSpec = tween(durationMillis = 350, easing = LinearOutSlowInEasing),
            label = "pauseAlpha"
        )

        val lastLineEndTime = track.lines.asSequence()
            .filterNot { it.isBackground }
            .maxOfOrNull { it.endTimeMs } ?: Long.MAX_VALUE
        val isOutro by remember(displayLines, lastLineEndTime) {
            derivedStateOf { activeIndexState.value == -1 && currentPositionMs >= lastLineEndTime }
        }

        val stageAlpha by animateFloatAsState(
            targetValue = if (isOutro) 0f else 1f,
            animationSpec = tween(durationMillis = 500, easing = LinearOutSlowInEasing),
            label = "stageAlpha"
        )

        // Detect seeking discontinuity (>1500ms jump) to snap all animations instead of animating through.
        // Tracked via anchor.positionMs rather than frame-by-frame currentPositionMs:
        // — Seeks appear as large jumps in the anchor when Spotify returns a new progress_ms
        // — This fires at anchor-update rate (~2s) instead of 60fps, eliminating a
        //   SideEffect lambda allocation on every recomposition (was ~60 allocs/sec).
        var prevAnchorPositionMs by remember { mutableLongStateOf(anchor.positionMs) }
        val isDiscontinuousSeek = Math.abs(anchor.positionMs - prevAnchorPositionMs) > 1500L
        LaunchedEffect(anchor.positionMs) {
            prevAnchorPositionMs = anchor.positionMs
        }

        // Y: spring feels natural for "lines sliding" (Apple Music / Spotify style).
        // alpha & scale: use the exact same spec so all three stay in sync — no mismatch on seeks.
        val lineAnimSpec: androidx.compose.animation.core.AnimationSpec<Float> = if (isDiscontinuousSeek) {
            snap()
        } else {
            spring(dampingRatio = 0.85f, stiffness = 520f)
        }
        val propAnimSpec: androidx.compose.animation.core.AnimationSpec<Float> = if (isDiscontinuousSeek) {
            snap()
        } else {
            tween(durationMillis = 200, easing = androidx.compose.animation.core.CubicBezierEasing(0.61f, 1f, 0.88f, 1f))
        }

        // Find companion background line happening during active line if any
        val activeLine = displayLines.getOrNull(activeLineIndex)
        val companionBgLine = remember(activeLine?.startTimeMs, track.lines) {
            if (activeLine != null && !activeLine.isBackground) {
                track.lines.asSequence()
                    .filter { it.isBackground }
                    .filter { other ->
                        other.startTimeMs <= activeLine.endTimeMs &&
                                other.endTimeMs >= activeLine.startTimeMs
                    }
                    .minByOrNull { other ->
                        kotlin.math.abs(other.startTimeMs - activeLine.startTimeMs)
                    }
            } else {
                null
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = stageAlpha },
            contentAlignment = Alignment.CenterStart
        ) {
            // Map of line heights measured in pixels (stable per line)
            val lineHeightsPx = remember { mutableStateMapOf<Long, Int>() }
            val density = LocalDensity.current

            // The visual gap between the bottom of one line and the top of the next line (constant 36.dp)
            // Spicy Lyrics uses a width-relative line gap (1cqw), not a fixed 36dp gap.
            val containerWidth = this@BoxWithConstraints.maxWidth
            val interLineGapPx = with(density) { (containerWidth * 0.01f).toPx() }

            // Fallback height derived from actual font metrics used in SingleLyricLineRow:
            // activeFontSizeSp × lineHeight factor (1.1875) converted to px.
            // This matches the real rendered height far better than the old 44.dp constant,
            // so the initial layout position is correct before onSizeChanged fires.
            val fallbackLineHeightPx = with(density) { (lyricsFontSizeSp * 1.1875f).sp.toPx() }

            // Dynamic cumulative Y calculation:
            // Center line (offset 0) is at Y = 0.
            // Downward lines (offset > 0): targetY is sum of previous lines' heights + constant gap.
            // Upward lines (offset < 0): targetY is negative sum of heights + constant gap.
            //
            // Use derivedStateOf so any individual height update in lineHeightsPx (not just .size
            // changes) instantly invalidates this snapshot — eliminates the stale-size bug that
            // caused spacing jumps when a line's real height first arrived from onSizeChanged.
            val targetYOffsetsPx by remember(effectiveCenterIndex, displayLines, isDynamicSpacingPrefEnabled, fallbackLineHeightPx, interLineGapPx) {
                derivedStateOf {
                    val map = mutableMapOf<Int, Float>()
                    map[0] = 0f

                    if (!isDynamicSpacingPrefEnabled) {
                        val fixedSlotPx = with(density) { 88.dp.toPx() }
                        for (off in -2..3) {
                            map[off] = off * fixedSlotPx
                        }
                    } else {
                        fun lineHeightAt(index: Int): Float {
                            val line = displayLines.getOrNull(index) ?: return fallbackLineHeightPx
                            return lineHeightsPx[line.startTimeMs]?.toFloat() ?: fallbackLineHeightPx
                        }

                        // Place line centers using half-heights, so wrapped lyrics do not
                        // create oversized gaps or overlap when line measurements arrive.
                        var y = 0f
                        for (off in 1..3) {
                            y += (lineHeightAt(effectiveCenterIndex + off - 1) +
                                    lineHeightAt(effectiveCenterIndex + off)) / 2f + interLineGapPx
                            map[off] = y
                        }

                        y = 0f
                        for (off in -1 downTo -2) {
                            y -= (lineHeightAt(effectiveCenterIndex + off + 1) +
                                    lineHeightAt(effectiveCenterIndex + off)) / 2f + interLineGapPx
                            map[off] = y
                        }
                    }

                    map
                }
            }

            // If activeLineIndex == -1 and there is an intro pause, show SpicyPauseDots centered above first line
            if (activeLineIndex == -1 && pauseInfo != null && pauseAlpha > 0.01f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            translationY = -48f * density.density
                            alpha = pauseAlpha
                        },
                    contentAlignment = Alignment.CenterStart
                ) {
                    SpicyPauseDots(
                        positionProvider = positionProvider,
                        pauseStartMs = pauseInfo.pauseStartMs,
                        nextStartMs = pauseInfo.nextStartMs,
                        rhythm = rhythmContext,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }

            // Render -2..3 window (6 slots) — fewer composables = fewer recompositions per frame.
            for (offset in -2..3) {
                val targetIndex = effectiveCenterIndex + offset
                val line = displayLines.getOrNull(targetIndex) ?: continue

                // Skip companion background line — rendered inline below the active row
                if (offset != 0 && line == companionBgLine) continue

                // key by startTimeMs: Compose reuses the composable as it scrolls, only
                // updating translationY on the GPU layer — no recomposition needed.
                key(line.startTimeMs) {
                    val isActive = targetIndex == activeLineIndex

                    // Lines at offset 0 and ±1 always use FlowRow so there is never a
                    // mode switch at the transition moment. Only barely-visible offset ±2
                    // uses the cheap single-Text path. Pinned positions for non-active
                    // FlowRows mean they never recompose per frame.
                    val renderAsActive = isActive || Math.abs(offset) <= 1

                    val targetYPx = targetYOffsetsPx[offset] ?: (offset * (fallbackLineHeightPx + interLineGapPx))

                    val animatedYOffsetPx by animateFloatAsState(
                        targetValue = targetYPx,
                        animationSpec = lineAnimSpec,
                        label = "lineY_${line.startTimeMs}"
                    )

                    // Spicy Lyrics' default vocal opacity is ~0.50 for sung and unsung lines.
                    val targetAlpha = when {
                        isActive -> 1.0f
                        targetIndex < activeLineIndex -> 0.497f
                        else -> 0.51f
                    }
                    val animatedAlpha by animateFloatAsState(
                        targetValue = targetAlpha,
                        animationSpec = propAnimSpec,
                        label = "lineAlpha_${line.startTimeMs}"
                    )

                    // Spicy keeps whole lines at 1x; the scale pulse belongs to individual words/letters.
                    val targetScale = 1.0f
                    val animatedScale by animateFloatAsState(
                        targetValue = targetScale,
                        animationSpec = propAnimSpec,
                        label = "lineScale_${line.startTimeMs}"
                    )

                    // Only the active line observes the frame clock. Neighbouring lines get
                    // a stable completed/not-started position, so their word composables do not
                    // recompose at playback frame rate.
                    val isPastLine = targetIndex < activeLineIndex
                    val linePositionProvider = remember(
                        positionProvider, line.startTimeMs, line.endTimeMs, isActive, isPastLine
                    ) {
                        if (isActive) {
                            positionProvider
                        } else {
                            val fixedPosition = if (isPastLine) line.endTimeMs + 1L else line.startTimeMs - 1L
                            { fixedPosition }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onSizeChanged { size ->
                                if (size.height > 0 && lineHeightsPx[line.startTimeMs] != size.height) {
                                    lineHeightsPx[line.startTimeMs] = size.height
                                }
                            }
                            .graphicsLayer {
                                translationY = animatedYOffsetPx
                                alpha = animatedAlpha
                                scaleX = animatedScale
                                scaleY = animatedScale
                                transformOrigin = LineTransformOrigin
                            }
                            .then(
                                if (onLineClicked != null) {
                                    Modifier.clickable {
                                        onLineClicked.invoke(line.startTimeMs)
                                    }
                                } else Modifier
                            ),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Column(
                            horizontalAlignment = Alignment.Start,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            SingleLyricLineRow(
                                line = line,
                                positionProvider = linePositionProvider,
                                isAnimationEnabled = isAnimationEnabled,
                                isActiveLine = isActive,
                                isPastLine = isPastLine,
                                forceFlowRow = renderAsActive,
                                isSubduedBackground = line.isBackground,
                                rhythm = rhythmContext,
                                activeFontSizeSp = lyricsFontSizeSp
                            )

                            if (isActive && companionBgLine != null && line != companionBgLine) {
                                SingleLyricLineRow(
                                    line = companionBgLine,
                                    positionProvider = positionProvider,
                                    isAnimationEnabled = isAnimationEnabled,
                                    isActiveLine = true,
                                    isSubduedBackground = true,
                                    rhythm = rhythmContext,
                                    activeFontSizeSp = lyricsFontSizeSp,
                                    modifier = Modifier.padding(top = 4.dp, start = 16.dp)
                                )
                            }

                            // Music interlude dots: integrated cleanly between lines (directly under active line, above next line)
                            if (isActive && pauseInfo != null && pauseAlpha > 0.01f) {
                                SpicyPauseDots(
                                    positionProvider = positionProvider,
                                    pauseStartMs = pauseInfo.pauseStartMs,
                                    nextStartMs = pauseInfo.nextStartMs,
                                    rhythm = rhythmContext,
                                    modifier = Modifier
                                        .graphicsLayer { alpha = pauseAlpha }
                                        .padding(top = 8.dp, start = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Attribution Badge (Required by Spicy Lyrics & Provider Terms of Service)
        track.attribution?.let { attr ->
            val context = LocalContext.current
            val openUrl = { url: String? ->
                if (!url.isNullOrBlank()) {
                    try {
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    } catch (_: Exception) {}
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(bottom = 12.dp, start = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Lyrics from ${attr.provider}",
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.SansSerif
                )
                if (attr.uploader != null) {
                    Text(
                        text = "· uploaded by",
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 11.sp
                    )
                    Text(
                        text = attr.uploader.username,
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 11.sp,
                        textDecoration = if (attr.uploader.url != null) TextDecoration.Underline else TextDecoration.None,
                        modifier = Modifier.clickable(enabled = attr.uploader.url != null) {
                            openUrl(attr.uploader.url)
                        }
                    )
                }
                if (attr.maker != null) {
                    Text(
                        text = "· made by",
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 11.sp
                    )
                    Text(
                        text = attr.maker.username,
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 11.sp,
                        textDecoration = if (attr.maker.url != null) TextDecoration.Underline else TextDecoration.None,
                        modifier = Modifier.clickable(enabled = attr.maker.url != null) {
                            openUrl(attr.maker.url)
                        }
                    )
                }
            }
        }
    }
}

private data class PauseInfo(val pauseStartMs: Long, val nextStartMs: Long)

private fun getPauseInfo(lines: List<LyricLine>, activeLineIndex: Int, currentPositionMs: Long): PauseInfo? {
    if (lines.isEmpty()) return null

    if (activeLineIndex == -1 && lines.isNotEmpty()) {
        val firstStart = lines[0].startTimeMs
        if (firstStart >= 3500L && currentPositionMs < firstStart) {
            val pauseStart = maxOf(0L, firstStart - 6000L)
            if (currentPositionMs in pauseStart until firstStart) {
                return PauseInfo(pauseStartMs = pauseStart, nextStartMs = firstStart)
            }
        }
        return null
    }

    if (activeLineIndex in 0 until lines.size - 1) {
        val currentLine = lines[activeLineIndex]
        val nextLine = lines[activeLineIndex + 1]
        val gap = nextLine.startTimeMs - currentLine.endTimeMs
        if (gap >= 4000L) {
            val gapStart = currentLine.endTimeMs + 600L
            if (currentPositionMs in gapStart until (nextLine.startTimeMs - 200L)) {
                return PauseInfo(pauseStartMs = gapStart, nextStartMs = nextLine.startTimeMs)
            }
        }
    }

    return null
}

@Composable
fun SpicyPauseDots(
    positionProvider: () -> Long,
    pauseStartMs: Long,
    nextStartMs: Long,
    rhythm: TrackRhythmContext = TrackRhythmContext.Default,
    modifier: Modifier = Modifier
) {
    // Spicy's interlude dots keep pulsing instead of becoming permanently "completed".
    // This loop is only alive while the pause row is composed.
    var pulseStep by remember(pauseStartMs, nextStartMs) { mutableIntStateOf(0) }
    LaunchedEffect(pauseStartMs, nextStartMs) {
        pulseStep = 0
        while (true) {
            delay(170L)
            pulseStep = (pulseStep + 1) % 3
        }
    }

    val dotSpec = tween<Float>(durationMillis = 160, easing = FastOutSlowInEasing)
    val d1Scale by animateFloatAsState(
        targetValue = if (pulseStep == 0) 1.05f else 0.75f,
        animationSpec = dotSpec,
        label = "dot1Scale"
    )
    val d2Scale by animateFloatAsState(
        targetValue = if (pulseStep == 1) 1.05f else 0.75f,
        animationSpec = dotSpec,
        label = "dot2Scale"
    )
    val d3Scale by animateFloatAsState(
        targetValue = if (pulseStep == 2) 1.05f else 0.75f,
        animationSpec = dotSpec,
        label = "dot3Scale"
    )
    val d1Alpha by animateFloatAsState(
        targetValue = if (pulseStep == 0) 1f else 0.35f,
        animationSpec = dotSpec,
        label = "dot1Alpha"
    )
    val d2Alpha by animateFloatAsState(
        targetValue = if (pulseStep == 1) 1f else 0.35f,
        animationSpec = dotSpec,
        label = "dot2Alpha"
    )
    val d3Alpha by animateFloatAsState(
        targetValue = if (pulseStep == 2) 1f else 0.35f,
        animationSpec = dotSpec,
        label = "dot3Alpha"
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(vertical = 4.dp)
    ) {
        PauseDot(scale = d1Scale, alpha = d1Alpha)
        PauseDot(scale = d2Scale, alpha = d2Alpha)
        PauseDot(scale = d3Scale, alpha = d3Alpha)
    }
}

@Composable
private fun PauseDot(
    scale: Float,
    alpha: Float
) {
    Box(
        modifier = Modifier
            .size(7.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .background(color = Color.White, shape = CircleShape)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SingleLyricLineRow(
    line: LyricLine,
    positionProvider: () -> Long,
    isAnimationEnabled: Boolean = true,
    isActiveLine: Boolean = true,
    isPastLine: Boolean = false,
    forceFlowRow: Boolean = false,
    isSubduedBackground: Boolean = false,
    rhythm: TrackRhythmContext = TrackRhythmContext.Default,
    activeFontSizeSp: Float = 32f,
    modifier: Modifier = Modifier
) {
    val fontSize = if (isSubduedBackground) (activeFontSizeSp * 0.69f).sp else activeFontSizeSp.sp
    val lineHeight = if (isSubduedBackground) (activeFontSizeSp * 0.875f).sp else (activeFontSizeSp * 1.1818f).sp
    val fontStyle = if (isSubduedBackground) FontStyle.Italic else FontStyle.Normal
    val baseAlpha = when {
        isSubduedBackground && (isActiveLine || isPastLine) -> 0.60f
        isSubduedBackground -> 0.30f
        isActiveLine || isPastLine -> 0.85f
        else -> 0.35f
    }
    val rtl = remember(line.rawText) { isRtlText(line.rawText) }

    // Spicy uses white lyric fills; singer IDs must not tint the whole renderer green/cyan/orange.
    CompositionLocalProvider(
        LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    ) {
        Column(
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.Center,
            modifier = modifier.fillMaxWidth()
        ) {
            if (!isActiveLine && !forceFlowRow) {
                // Spicy keeps the adjacent lines simple: the line itself controls its opacity.
                Text(
                    text = line.rawText,
                    style = TextStyle(
                        fontSize = fontSize,
                        lineHeight = lineHeight,
                        fontStyle = fontStyle,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif,
                        color = Color.White.copy(alpha = baseAlpha),
                        letterSpacing = 0.sp
                    )
                )
            } else {
                val effectiveWords = if (line.words.isNotEmpty()) {
                    line.words
                } else if (line.rawText.isNotBlank()) {
                    remember(line.rawText, line.startTimeMs, line.endTimeMs) {
                        val tokens = line.rawText.split("\\s+".toRegex()).filter { it.isNotEmpty() }
                        if (tokens.isEmpty()) emptyList()
                        else {
                            val lineDur = (line.endTimeMs - line.startTimeMs).coerceAtLeast(tokens.size * 50L)
                            val tokenDur = lineDur / tokens.size
                            tokens.mapIndexed { idx, token ->
                                WordSync(
                                    text = token,
                                    startTimeMs = line.startTimeMs + idx * tokenDur,
                                    endTimeMs = line.startTimeMs + (idx + 1) * tokenDur,
                                    trailingSpace = idx < tokens.size - 1
                                )
                            }
                        }
                    }
                } else emptyList()

                if (effectiveWords.isNotEmpty()) {
                    // Keep split syllables of a word together so a line wrap never breaks a word in half.
                    val wordGroups = remember(effectiveWords) {
                        val groups = mutableListOf<List<WordSync>>()
                        var currentGroup = mutableListOf<WordSync>()
                        for (word in effectiveWords) {
                            currentGroup.add(word)
                            if (word.trailingSpace) {
                                groups.add(currentGroup.toList())
                                currentGroup = mutableListOf()
                            }
                        }
                        if (currentGroup.isNotEmpty()) groups.add(currentGroup.toList())
                        groups
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(0.dp),
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        wordGroups.forEach { syllables ->
                            Row(verticalAlignment = Alignment.Bottom) {
                                syllables.forEach { word ->
                                    RhythmWordHighlightText(
                                        word = word,
                                        positionProvider = positionProvider,
                                        isAnimationEnabled = isAnimationEnabled,
                                        isActiveLine = isActiveLine,
                                        isSubduedBackground = isSubduedBackground,
                                        glowColor = Color.White,
                                        rhythm = rhythm,
                                        activeFontSizeSp = activeFontSizeSp
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Text(
                        text = line.rawText,
                        style = TextStyle(
                            fontSize = fontSize,
                            lineHeight = lineHeight,
                            fontStyle = fontStyle,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = baseAlpha)
                        )
                    )
                }
            }

            if (!line.translation.isNullOrBlank()) {
                Text(
                    text = line.translation,
                    style = TextStyle(
                        fontSize = (fontSize.value * 0.55f).sp,
                        lineHeight = (lineHeight.value * 0.6f).sp,
                        fontStyle = FontStyle.Normal,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.SansSerif,
                        textAlign = TextAlign.Start,
                        color = if (isActiveLine) Color.White.copy(alpha = 0.80f) else Color.White.copy(alpha = 0.50f)
                    ),
                    modifier = Modifier.padding(top = 2.dp, bottom = 1.dp)
                )
            }
        }
    }
}

@Composable
fun RhythmWordHighlightText(
    word: WordSync,
    positionProvider: () -> Long,
    isAnimationEnabled: Boolean = true,
    isActiveLine: Boolean = true,
    isSubduedBackground: Boolean = false,
    glowColor: Color = Color.White,
    rhythm: TrackRhythmContext = TrackRhythmContext.Default,
    activeFontSizeSp: Float = 32f,
    modifier: Modifier = Modifier
) {
    val currentPositionMs = positionProvider()
    val duration = (word.endTimeMs - word.startTimeMs).coerceAtLeast(1L)

    // Predictive Pre-Roll (starts anticipation 45ms before timestamp)
    val rawWordProgress = if (isActiveLine) {
        val elapsed = currentPositionMs + PRE_ROLL_OFFSET_MS - word.startTimeMs
        when {
            elapsed < 0L -> 0f
            elapsed >= duration -> 1f
            else -> (elapsed.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        }
    } else {
        if (currentPositionMs >= word.endTimeMs) 1f else 0f
    }

    val isWordActive = rawWordProgress > 0f && rawWordProgress < 1f
    val isWordCompleted = rawWordProgress >= 1f

    val wordPeakScale = if (isLetterCapableDuration(duration, word)) 1.175f else 1.0505f
    val targetWordScale = when {
        isWordActive -> spicyScale(rawWordProgress, wordPeakScale)
        isWordCompleted -> 1f
        else -> 0.95f
    }
    val initialWordScale = targetWordScale
    val wordScaleSpring = remember(word.startTimeMs, isActiveLine) {
        SpicySpring(initialWordScale, frequency = 0.88f, damping = 0.64f)
    }
    val springWordScale = if (isActiveLine && isAnimationEnabled) {
        wordScaleSpring.update(targetWordScale, currentPositionMs)
    } else {
        targetWordScale
    }
    val wordScale = if (isSubduedBackground) {
        1f + (springWordScale - 1f) * 0.55f
    } else {
        springWordScale
    }

    val targetYOffset = if (isActiveLine) spicyYOffset(rawWordProgress) else 0f
    val wordOffsetSpring = remember(word.startTimeMs, isActiveLine) {
        SpicySpring(targetYOffset, frequency = 1.45f, damping = 0.4f)
    }
    val springYOffset = if (isActiveLine && isAnimationEnabled) {
        wordOffsetSpring.update(targetYOffset, currentPositionMs)
    } else {
        targetYOffset
    }
    val wordOffsetY = springYOffset * activeFontSizeSp * if (isSubduedBackground) 0.5f else 1f

    val isLetterCapable = isAnimationEnabled &&
            duration >= 1400L &&
            word.graphemes.size in 2..12 &&
            canSplitIntoLetters(word.text)

    Box(
        modifier = modifier
            .padding(horizontal = 0.dp, vertical = 0.dp)
            .graphicsLayer {
                scaleX = wordScale
                scaleY = wordScale
                translationY = wordOffsetY * density * fontScale
            },
        contentAlignment = Alignment.CenterStart
    ) {
        if (isLetterCapable && isActiveLine) {
            RhythmLetterGroupSweepText(
                word = word,
                currentPositionMs = currentPositionMs,
                isWordActive = isWordActive,
                isWordCompleted = isWordCompleted,
                isSubduedBackground = isSubduedBackground,
                glowColor = glowColor,
                rhythm = rhythm,
                activeFontSizeSp = activeFontSizeSp
            )
        } else {
            RhythmSingleSyllableSweepText(
                word = word,
                rawProgress = rawWordProgress,
                currentPositionMs = currentPositionMs,
                isActiveLine = isActiveLine,
                isWordActive = isWordActive,
                isWordCompleted = isWordCompleted,
                isSubduedBackground = isSubduedBackground,
                glowColor = glowColor,
                activeFontSizeSp = activeFontSizeSp
            )
        }
    }
}

@Composable
private fun RhythmSingleSyllableSweepText(
    word: WordSync,
    rawProgress: Float,
    currentPositionMs: Long,
    isActiveLine: Boolean,
    isWordActive: Boolean,
    isWordCompleted: Boolean,
    isSubduedBackground: Boolean,
    glowColor: Color,
    activeFontSizeSp: Float
) {
    val displayString = remember(word.text, word.trailingSpace) {
        if (word.trailingSpace) "${word.text} " else word.text
    }
    val fontSize = if (isSubduedBackground) (activeFontSizeSp * 0.69f).sp else activeFontSizeSp.sp
    val lineHeight = if (isSubduedBackground) (activeFontSizeSp * 0.875f).sp else (activeFontSizeSp * 1.1818f).sp
    val fontStyle = if (isSubduedBackground) FontStyle.Italic else FontStyle.Normal
    val litAlpha = if (isSubduedBackground) 0.60f else 0.85f
    val dimAlpha = if (isSubduedBackground) 0.30f else 0.35f
    val glowSpring = remember(word.startTimeMs, isActiveLine) {
        SpicySpring(spicyGlow(rawProgress), frequency = 1.18f, damping = 0.56f)
    }
    val glowStrength = if (isActiveLine) {
        glowSpring.update(spicyGlow(rawProgress), currentPositionMs)
    } else {
        0f
    }

    val litColor = Color.White.copy(alpha = litAlpha)
    val dimColor = Color.White.copy(alpha = dimAlpha)

    val completedStyle = remember(litColor, fontSize, lineHeight, fontStyle) {
        TextStyle(
            color = litColor,
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            fontStyle = fontStyle,
            fontFamily = FontFamily.SansSerif,
            letterSpacing = 0.sp,
            lineHeight = lineHeight,
        )
    }

    val unstartedStyle = remember(dimColor, fontSize, lineHeight, fontStyle) {
        TextStyle(
            color = dimColor,
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            fontStyle = fontStyle,
            fontFamily = FontFamily.SansSerif,
            letterSpacing = 0.sp,
            lineHeight = lineHeight,
        )
    }

    // Gradient brush only needed when the word is actively sweeping across (0f < rawProgress < 1f).
    // Pre-computed solid styles eliminate shader construction & Skia Paint shader pipeline for inactive words.
    val textStyle = when {
        isWordCompleted -> completedStyle
        !isWordActive -> unstartedStyle
        else -> {
            val p = rawProgress.coerceIn(0f, 1f)
            val textBrush = SpicySweepBrush(
                progress = p,
                isRtl = isRtlText(displayString),
                litColor = litColor,
                dimColor = dimColor
            )
            TextStyle(
                brush = textBrush,
                fontSize = fontSize,
                fontWeight = FontWeight.Bold,
                fontStyle = fontStyle,
                fontFamily = FontFamily.SansSerif,
                letterSpacing = 0.sp,
                lineHeight = lineHeight,
                shadow = Shadow(
                    color = glowColor.copy(alpha = glowStrength * if (isSubduedBackground) 0.45f else 0.72f),
                    offset = ShadowOffsetGlow,
                    blurRadius = 4f
                )
            )
        }
    }

    Text(
        text = displayString,
        style = textStyle
    )
}

@Composable
private fun RhythmLetterGroupSweepText(
    word: WordSync,
    currentPositionMs: Long,
    isWordActive: Boolean,
    isWordCompleted: Boolean,
    isSubduedBackground: Boolean,
    glowColor: Color,
    rhythm: TrackRhythmContext,
    activeFontSizeSp: Float
) {
    val totalDuration = (word.endTimeMs - word.startTimeMs).coerceAtLeast(1L)
    val fontSize = if (isSubduedBackground) (activeFontSizeSp * 0.69f).sp else activeFontSizeSp.sp
    val lineHeight = if (isSubduedBackground) (activeFontSizeSp * 0.875f).sp else (activeFontSizeSp * 1.1818f).sp
    val fontStyle = if (isSubduedBackground) FontStyle.Italic else FontStyle.Normal
    val litAlpha = if (isSubduedBackground) 0.60f else 0.85f
    val dimAlpha = if (isSubduedBackground) 0.30f else 0.35f

    val litColor = Color.White.copy(alpha = litAlpha)
    val dimColor = Color.White.copy(alpha = dimAlpha)

    val completedLetterStyle = remember(litColor, fontSize, lineHeight, fontStyle) {
        TextStyle(
            color = litColor,
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            fontStyle = fontStyle,
            fontFamily = FontFamily.SansSerif,
            letterSpacing = 0.sp,
            lineHeight = lineHeight,
        )
    }

    val unstartedLetterStyle = remember(dimColor, fontSize, lineHeight, fontStyle) {
        TextStyle(
            color = dimColor,
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            fontStyle = fontStyle,
            fontFamily = FontFamily.SansSerif,
            letterSpacing = 0.sp,
            lineHeight = lineHeight,
        )
    }

    val spaceStyle = remember(fontSize, lineHeight) {
        TextStyle(fontSize = fontSize, lineHeight = lineHeight)
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        val graphemes = word.graphemes
        val count = graphemes.size.coerceAtLeast(1)
        val letterDuration = (totalDuration / count).coerceAtLeast(1L)

        graphemes.forEachIndexed { index, graphemeCluster ->
            val letterStart = word.startTimeMs + (index * letterDuration)
            val rawLetterProgress = ((currentPositionMs + PRE_ROLL_OFFSET_MS - letterStart).toFloat() / letterDuration.toFloat()).coerceIn(0f, 1f)

            val isLetterActive = rawLetterProgress > 0f && rawLetterProgress < 1f
            val isLetterDone = rawLetterProgress >= 1f
            val targetLetterScale = when {
                isLetterActive -> spicyScale(rawLetterProgress, 1.175f)
                isLetterDone || isWordCompleted -> 1f
                else -> 0.95f
            }
            val letterScaleSpring = remember(word.startTimeMs, index) {
                SpicySpring(targetLetterScale, frequency = 0.88f, damping = 0.64f)
            }
            val letterScale = letterScaleSpring.update(targetLetterScale, currentPositionMs)
            val targetLetterYOffset = spicyLetterYOffset(rawLetterProgress)
            val letterYOffsetSpring = remember(word.startTimeMs, index) {
                SpicySpring(targetLetterYOffset, frequency = 1.45f, damping = 0.4f)
            }
            val letterYOffset = letterYOffsetSpring.update(targetLetterYOffset, currentPositionMs) *
                activeFontSizeSp * if (isSubduedBackground) 0.5f else 1f
            val letterGlowSpring = remember(word.startTimeMs, index) {
                SpicySpring(spicyGlow(rawLetterProgress), frequency = 1.18f, damping = 0.56f)
            }
            val letterGlow = letterGlowSpring.update(spicyGlow(rawLetterProgress), currentPositionMs)

            val letterStyle = when {
                isLetterDone || isWordCompleted -> completedLetterStyle
                !isLetterActive -> unstartedLetterStyle
                else -> {
                    val p = rawLetterProgress.coerceIn(0f, 1f)
                    val textBrush = SpicySweepBrush(
                        progress = p,
                        isRtl = isRtlText(graphemeCluster),
                        litColor = litColor,
                        dimColor = dimColor
                    )
                    TextStyle(
                        brush = textBrush,
                        fontSize = fontSize,
                        fontWeight = FontWeight.Bold,
                        fontStyle = fontStyle,
                        fontFamily = FontFamily.SansSerif,
                        letterSpacing = 0.sp,
                        lineHeight = lineHeight,
                        shadow = Shadow(
                            color = glowColor.copy(alpha = letterGlow * if (isSubduedBackground) 0.45f else 0.72f),
                            offset = ShadowOffsetGlow,
                            blurRadius = if (isSubduedBackground) 7f else 12f
                        )
                    )
                }
            }

            Box(
                modifier = Modifier.graphicsLayer {
                    scaleX = letterScale
                    scaleY = letterScale
                    translationY = letterYOffset * density * fontScale
                },
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = graphemeCluster,
                    style = letterStyle
                )
            }
        }

        if (word.trailingSpace) {
            Text(
                text = " ",
                style = spaceStyle
            )
        }
    }
}