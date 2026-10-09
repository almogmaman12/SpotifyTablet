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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.Shadow
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
private const val SPICY_BLUR_MULTIPLIER = 1.25f

/**
 * Draw-phase karaoke gradient. The brush is intentionally not a ShaderBrush: ShaderBrush caches
 * its shader by size, but this shader also changes with playback time. Creating the shader in
 * applyTo lets Compose redraw a single Text without a second offscreen text layer.
 */
private class SpicyPlaybackBrush(
    private val positionProvider: () -> Long,
    private val startTimeMs: Long,
    private val endTimeMs: Long,
    private val isRtl: Boolean,
    private val litAlpha: Float,
    private val dimAlpha: Float
) : androidx.compose.ui.graphics.Brush() {
    override fun applyTo(size: Size, p: Paint, alpha: Float) {
        val position = positionProvider() + PRE_ROLL_OFFSET_MS
        val duration = (endTimeMs - startTimeMs).coerceAtLeast(1L)
        val baseColor = Color.White.copy(alpha = if (position >= endTimeMs) litAlpha else dimAlpha)

        if (position < startTimeMs || position >= endTimeMs || size.width <= 0f || size.height <= 0f) {
            p.shader = null
            p.color = baseColor
            p.alpha = alpha
            return
        }

        val progress = ((position - startTimeMs).toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        val rawStart = -0.20f + 1.20f * progress
        val rawEnd = rawStart + SPICY_SWEEP_FEATHER

        if (rawEnd <= 0f) {
            p.shader = null
            p.color = Color.White.copy(alpha = dimAlpha)
            p.alpha = alpha
            return
        }
        if (rawStart >= 1f) {
            p.shader = null
            p.color = Color.White.copy(alpha = litAlpha)
            p.alpha = alpha
            return
        }

        fun alphaAt(position: Float): Float = when {
            position <= rawStart -> litAlpha
            position >= rawEnd -> dimAlpha
            else -> litAlpha + (dimAlpha - litAlpha) *
                ((position - rawStart) / (rawEnd - rawStart)).coerceIn(0f, 1f)
        }

        val stopPositions = buildList {
            add(0f)
            if (rawStart > 0f && rawStart < 1f) add(rawStart)
            if (rawEnd > 0f && rawEnd < 1f) add(rawEnd)
            add(1f)
        }.distinct().sorted()
        val colors = stopPositions.map { stop ->
            Color.White.copy(alpha = alphaAt(stop))
        }

        val from = if (isRtl) Offset(size.width, 0f) else Offset(0f, 0f)
        val to = if (isRtl) Offset(0f, 0f) else Offset(0f, size.height)
        p.shader = LinearGradientShader(
            colors = colors,
            from = from,
            to = to,
            colorStops = stopPositions,
            tileMode = TileMode.Clamp
        )
        p.alpha = alpha
    }
}

/** Edge fade matching Spicy's transparent 16px edge and full-opacity 64px content. */
private fun lyricEdgeFade(centerY: Float, height: Float, edgeStartPx: Float, edgeEndPx: Float): Float {
    if (height <= 0f || edgeEndPx <= edgeStartPx) return 1f
    val top = ((centerY - edgeStartPx) / (edgeEndPx - edgeStartPx)).coerceIn(0f, 1f)
    val bottom = ((height - centerY - edgeStartPx) / (edgeEndPx - edgeStartPx)).coerceIn(0f, 1f)
    return minOf(top, bottom)
}

/** An eased, staggered pulse: the three music dots keep moving for the full interlude. */
private fun pauseDotPulse(elapsedMs: Long, index: Int): Float {
    val cycleMs = 1080L
    val phaseMs = ((elapsedMs.coerceAtLeast(0L) + index * 235L) % cycleMs).toFloat()
    val phase = phaseMs / cycleMs.toFloat()
    val wave = (0.5 + 0.5 * kotlin.math.cos(phase * (2.0 * Math.PI))).toFloat()
    return wave * wave * wave
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

private val LineTransformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
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

        // Spicy's active line sits above the exact vertical center. Use the measured
        // viewport height so the focal point stays consistent across screen sizes.
        val focalShiftPx = with(LocalDensity.current) {
            (this@BoxWithConstraints.maxHeight * 0.12f).toPx()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = stageAlpha
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .drawWithContent {
                    drawContent()
                    // Spicy fades lyric rows into the surrounding background at both edges.
                    val edge = 0.08f
                    val innerEdge = 0.02f
                    val mask = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            innerEdge to Color.Transparent,
                            edge to Color.White,
                            (1f - edge) to Color.White,
                            (1f - innerEdge) to Color.Transparent,
                            1f to Color.Transparent
                        ),
                        startY = 0f,
                        endY = size.height
                    )
                    drawRect(brush = mask, blendMode = BlendMode.DstIn)
                },
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
                            translationY = -48f * density.density - focalShiftPx
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

                    val targetYPx = (targetYOffsetsPx[offset] ?: (offset * (fallbackLineHeightPx + interLineGapPx))) - focalShiftPx

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

                    // Active: live position (smoothed via displayPositionMs to prevent sweep jumps on drift correction).
                    // Departing (renderAsActive): completed state (stable).
                    // Upcoming inactive: not-started state (stable, no per-frame recomposition).
                    val linePositionMs = when {
                        isActive -> displayPositionMs
                        renderAsActive -> line.endTimeMs + 1L
                        targetIndex < activeLineIndex -> line.endTimeMs + 1L
                        else -> line.startTimeMs - 1L
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
                                positionProvider = positionProvider,
                                isAnimationEnabled = isAnimationEnabled,
                                isActiveLine = isActive,
                                forceFlowRow = renderAsActive,
                                isSubduedBackground = line.isBackground,
                                rhythm = rhythmContext,
                                activeFontSizeSp = lyricsFontSizeSp,
                                lineDistance = kotlin.math.abs(targetIndex - activeLineIndex)
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
    val totalTime = (nextStartMs - pauseStartMs).coerceAtLeast(1000L)
    val baseDotTime = totalTime / 3

    val dot1End = pauseStartMs + baseDotTime
    val dot2End = pauseStartMs + (baseDotTime * 2)

    // These derived states depend on playback time, but only invalidate composition when
    // a dot crosses its threshold, rather than on every playback-clock update.
    val dot1Active by remember(pauseStartMs, nextStartMs) {
        derivedStateOf { positionProvider() >= pauseStartMs }
    }
    val dot2Active by remember(pauseStartMs, nextStartMs) {
        derivedStateOf { positionProvider() >= dot1End }
    }
    val dot3Active by remember(pauseStartMs, nextStartMs) {
        derivedStateOf { positionProvider() >= dot2End }
    }

    val dotSpring = rhythm.calculateRhythmSpringSpec<Float>(baseStiffness = 380f, baseDamping = 0.65f)

    val d1Scale by animateFloatAsState(
        targetValue = if (dot1Active) 1.35f else 0.85f,
        animationSpec = dotSpring,
        label = "dot1Scale"
    )
    val d2Scale by animateFloatAsState(
        targetValue = if (dot2Active) 1.35f else 0.85f,
        animationSpec = dotSpring,
        label = "dot2Scale"
    )
    val d3Scale by animateFloatAsState(
        targetValue = if (dot3Active) 1.35f else 0.85f,
        animationSpec = dotSpring,
        label = "dot3Scale"
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(vertical = 14.dp)
    ) {
        PauseDot(isActive = dot1Active, scale = d1Scale)
        PauseDot(isActive = dot2Active, scale = d2Scale)
        PauseDot(isActive = dot3Active, scale = d3Scale)
    }
}

@Composable
private fun PauseDot(
    isActive: Boolean,
    scale: Float
) {
    Box(
        modifier = Modifier
            .size(16.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .background(
                color = if (isActive) Color.White else Color(0x55FFFFFF),
                shape = CircleShape
            )
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SingleLyricLineRow(
    line: LyricLine,
    positionProvider: () -> Long,
    isAnimationEnabled: Boolean = true,
    isActiveLine: Boolean = true,
    forceFlowRow: Boolean = false,
    isSubduedBackground: Boolean = false,
    rhythm: TrackRhythmContext = TrackRhythmContext.Default,
    activeFontSizeSp: Float = 32f,
    lineDistance: Int = 0,
    modifier: Modifier = Modifier
) {
    val fontSize = if (isSubduedBackground) (activeFontSizeSp * 0.69f).sp else activeFontSizeSp.sp
    val lineHeight = if (isSubduedBackground) (activeFontSizeSp * 0.875f).sp else (activeFontSizeSp * 1.1818f).sp
    val fontStyle = if (isSubduedBackground) FontStyle.Italic else FontStyle.Normal
    val lineFillAlpha by remember(line.startTimeMs, line.endTimeMs, isActiveLine, isSubduedBackground) {
        derivedStateOf {
            val lit = if (isSubduedBackground) 0.60f else 0.85f
            val dim = if (isSubduedBackground) 0.30f else 0.35f
            when {
                isActiveLine -> if (isSubduedBackground) 0.60f else 1.0f
                positionProvider() >= line.endTimeMs -> lit
                else -> dim
            }
        }
    }
    val rtl = remember(line.rawText) { isRtlText(line.rawText) }
    val lineBlurRadiusPx = with(LocalDensity.current) {
        (lineDistance.coerceIn(0, 5) * SPICY_BLUR_MULTIPLIER).dp.toPx()
    }
    val lineShadow = if (!isActiveLine && lineDistance > 0) {
        Shadow(
            color = Color.White.copy(alpha = lineFillAlpha),
            offset = Offset.Zero,
            blurRadius = lineBlurRadiusPx
        )
    } else null

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
                        color = Color.White.copy(alpha = lineFillAlpha),
                        letterSpacing = 0.sp,
                        shadow = lineShadow
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
                                        activeFontSizeSp = activeFontSizeSp,
                                        lineDistance = lineDistance
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
                            color = Color.White.copy(alpha = lineFillAlpha)
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
    lineDistance: Int = 0,
    modifier: Modifier = Modifier
) {
    val duration = (word.endTimeMs - word.startTimeMs).coerceAtLeast(1L)
    val isLetterCapable = isAnimationEnabled && isActiveLine &&
        duration >= 1400L &&
        word.graphemes.size in 2..12 &&
        canSplitIntoLetters(word.text)

    if (isLetterCapable) {
        val letterDuration = (duration / word.graphemes.size.coerceAtLeast(1)).coerceAtLeast(1L)
        Row(verticalAlignment = Alignment.Bottom, modifier = modifier) {
            word.graphemes.forEachIndexed { index, grapheme ->
                val letterStart = word.startTimeMs + index * letterDuration
                val letterEnd = if (index == word.graphemes.lastIndex) {
                    word.endTimeMs
                } else {
                    (letterStart + letterDuration).coerceAtMost(word.endTimeMs)
                }
                SpicyAnimatedTextUnit(
                    text = grapheme,
                    startTimeMs = letterStart,
                    endTimeMs = letterEnd,
                    positionProvider = positionProvider,
                    isAnimationEnabled = true,
                    isActiveLine = true,
                    isSubduedBackground = isSubduedBackground,
                    glowColor = glowColor,
                    activeFontSizeSp = activeFontSizeSp,
                    isLetter = true,
                    lineDistance = lineDistance
                )
            }
            if (word.trailingSpace) {
                Text(
                    text = " ",
                    style = TextStyle(
                        fontSize = if (isSubduedBackground) (activeFontSizeSp * 0.69f).sp else activeFontSizeSp.sp,
                        lineHeight = if (isSubduedBackground) (activeFontSizeSp * 0.875f).sp else (activeFontSizeSp * 1.1818f).sp
                    )
                )
            }
        }
    } else {
        SpicyAnimatedTextUnit(
            text = if (word.trailingSpace) "${word.text} " else word.text,
            startTimeMs = word.startTimeMs,
            endTimeMs = word.endTimeMs,
            positionProvider = positionProvider,
            isAnimationEnabled = isAnimationEnabled,
            isActiveLine = isActiveLine,
            isSubduedBackground = isSubduedBackground,
            glowColor = glowColor,
            activeFontSizeSp = activeFontSizeSp,
            isLetter = false,
            lineDistance = lineDistance,
            modifier = modifier
        )
    }
}

@Composable
private fun SpicyAnimatedTextUnit(
    text: String,
    startTimeMs: Long,
    endTimeMs: Long,
    positionProvider: () -> Long,
    isAnimationEnabled: Boolean,
    isActiveLine: Boolean,
    isSubduedBackground: Boolean,
    glowColor: Color,
    activeFontSizeSp: Float,
    isLetter: Boolean,
    lineDistance: Int = 0,
    modifier: Modifier = Modifier
) {
    val duration = (endTimeMs - startTimeMs).coerceAtLeast(1L)
    val playbackState by remember(startTimeMs, endTimeMs, isActiveLine) {
        derivedStateOf {
            val position = positionProvider() + PRE_ROLL_OFFSET_MS
            when {
                position < startTimeMs -> SpicyWordPlaybackState.Upcoming
                position >= endTimeMs -> SpicyWordPlaybackState.Completed
                !isActiveLine -> SpicyWordPlaybackState.Upcoming
                else -> SpicyWordPlaybackState.Active
            }
        }
    }

    // Opacity mirrors Mixed.css: normal text uses 0.85 sung / 0.35 unsung.
    // Background vocals use the dedicated 0.60 / 0.30 levels.
    val litAlpha = if (isSubduedBackground) 0.60f else 0.85f
    val dimAlpha = if (isSubduedBackground) 0.30f else 0.35f
    // The animated foreground is composited over the dim base, so use the inverse
    // alpha needed to land on Spicy's target final opacity.
    val overlayAlpha = ((litAlpha - dimAlpha) / (1f - dimAlpha)).coerceIn(0f, 1f)
    val baseAlpha = if (isAnimationEnabled && isActiveLine) {
        dimAlpha
    } else if (playbackState == SpicyWordPlaybackState.Upcoming) {
        dimAlpha
    } else {
        litAlpha
    }
    val fontSize = if (isSubduedBackground) (activeFontSizeSp * 0.69f).sp else activeFontSizeSp.sp
    val lineHeight = if (isSubduedBackground) (activeFontSizeSp * 0.875f).sp else (activeFontSizeSp * 1.1818f).sp
    val lineBlurRadiusPx = with(LocalDensity.current) {
        (lineDistance.coerceIn(0, 5) * SPICY_BLUR_MULTIPLIER).dp.toPx()
    }
    val lineShadowAlpha = if (playbackState == SpicyWordPlaybackState.Upcoming) dimAlpha else litAlpha
    val lineShadow = if (!isActiveLine && lineDistance > 0) {
        Shadow(
            color = Color.White.copy(alpha = lineShadowAlpha),
            offset = Offset.Zero,
            blurRadius = lineBlurRadiusPx
        )
    } else null

    val baseStyle = remember(fontSize, lineHeight, baseAlpha, isSubduedBackground, isLetter, lineDistance, playbackState, lineShadow) {
        TextStyle(
            color = Color.White.copy(alpha = baseAlpha),
            fontSize = fontSize,
            lineHeight = lineHeight,
            fontWeight = FontWeight.Bold,
            fontStyle = if (isSubduedBackground) FontStyle.Italic else FontStyle.Normal,
            fontFamily = FontFamily.SansSerif,
            letterSpacing = 0.sp,
            shadow = lineShadow
        )
    }

    // Glow intensity changes in a few perceptual stages rather than recomposing every frame.
    // The spring itself remains continuous for scale and vertical movement in the layer lambda.
    val glowStage by remember(startTimeMs, endTimeMs, isActiveLine, isAnimationEnabled) {
        derivedStateOf {
            if (!isActiveLine || !isAnimationEnabled) {
                0
            } else {
                val p = ((positionProvider() + PRE_ROLL_OFFSET_MS - startTimeMs).toFloat() /
                    duration.toFloat()).coerceIn(0f, 1f)
                when {
                    p < 0.05f -> 0
                    p < 0.15f -> 1
                    p < 0.60f -> 2
                    p < 0.80f -> 3
                    p < 0.96f -> 4
                    else -> 5
                }
            }
        }
    }
    val glowAlpha = when (glowStage) {
        1 -> 0.45f
        2 -> 0.72f
        3 -> 0.52f
        4 -> 0.22f
        else -> 0f
    }
    val animator = remember(startTimeMs, endTimeMs, isLetter) {
        SpicyLyricsAnimator(isLetter = isLetter)
    }

    Box(
        modifier = modifier.graphicsLayer {
            if (isAnimationEnabled && isActiveLine) {
                val frame = animator.sample(
                    positionMs = positionProvider(),
                    startTimeMs = startTimeMs,
                    endTimeMs = endTimeMs,
                    frameTimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                )
                scaleX = frame.scale
                scaleY = frame.scale
                translationY = frame.yOffsetEm * activeFontSizeSp * density *
                    if (isLetter) 2f else 1f
            } else {
                val scale = if (playbackState == SpicyWordPlaybackState.Completed) 1f else 0.95f
                scaleX = scale
                scaleY = scale
                translationY = 0f
            }
        },
        contentAlignment = Alignment.CenterStart
    ) {
        Text(text = text, style = baseStyle)

        // The overlay's moving mask recreates Spicy's -20% to 100% gradient sweep.
        // Position is sampled in draw, not composition, so the moving edge doesn't rebuild Text.
        if (isAnimationEnabled && isActiveLine) {
            val overlayStyle = remember(fontSize, lineHeight, isSubduedBackground, isLetter, glowAlpha) {
                TextStyle(
                    color = Color.White.copy(alpha = overlayAlpha),
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    fontWeight = FontWeight.Bold,
                    fontStyle = if (isSubduedBackground) FontStyle.Italic else FontStyle.Normal,
                    fontFamily = FontFamily.SansSerif,
                    letterSpacing = 0.sp,
                    shadow = Shadow(
                        color = glowColor.copy(alpha = glowAlpha * if (isLetter) 0.9f else 0.45f),
                        offset = Offset.Zero,
                        blurRadius = if (isLetter) 16f else 6f
                    )
                )
            }

            Text(
                text = text,
                style = overlayStyle,
                modifier = Modifier
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        val position = positionProvider() + PRE_ROLL_OFFSET_MS
                        val progress = ((position - startTimeMs).toFloat() / duration.toFloat())
                            .coerceIn(0f, 1f)
                        val rawSweepPosition = -0.20f + 1.20f * progress
                        val feather = SPICY_SWEEP_FEATHER
                        val transitionEnd = rawSweepPosition + feather
                        val rtl = isRtlText(text)
                        val mask = if (rtl) {
                            when {
                                transitionEnd <= 0f -> Brush.horizontalGradient(
                                    colors = listOf(Color.Transparent, Color.Transparent),
                                    startX = 0f,
                                    endX = size.width
                                )
                                rawSweepPosition >= 1f -> Brush.horizontalGradient(
                                    colors = listOf(Color.White, Color.White),
                                    startX = 0f,
                                    endX = size.width
                                )
                                rawSweepPosition < 0f -> {
                                    // Stop positions begin off the right edge; preserve the
                                    // partially revealed edge instead of snapping at 0%.
                                    val edgeAlpha = (transitionEnd / feather).coerceIn(0f, 1f)
                                    val fadeStart = (1f - transitionEnd).coerceIn(0f, 1f)
                                    Brush.horizontalGradient(
                                        colorStops = arrayOf(
                                            0f to Color.Transparent,
                                            fadeStart to Color.Transparent,
                                            1f to Color.White.copy(alpha = edgeAlpha)
                                        ),
                                        startX = 0f,
                                        endX = size.width
                                    )
                                }
                                else -> {
                                    val fadeStart = (1f - transitionEnd).coerceIn(0f, 1f)
                                    val litStart = (1f - rawSweepPosition).coerceIn(0f, 1f)
                                    Brush.horizontalGradient(
                                        colorStops = arrayOf(
                                            0f to Color.Transparent,
                                            fadeStart to Color.Transparent,
                                            litStart to Color.White,
                                            1f to Color.White
                                        ),
                                        startX = 0f,
                                        endX = size.width
                                    )
                                }
                            }
                        } else {
                            when {
                                transitionEnd <= 0f -> Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, Color.Transparent),
                                    startY = 0f,
                                    endY = size.height
                                )
                                rawSweepPosition >= 1f -> Brush.verticalGradient(
                                    colors = listOf(Color.White, Color.White),
                                    startY = 0f,
                                    endY = size.height
                                )
                                rawSweepPosition < 0f -> {
                                    // The CSS gradient starts at -20%, so the top edge begins
                                    // partially dim and becomes progressively lit before 0%.
                                    val edgeAlpha = (transitionEnd / feather).coerceIn(0f, 1f)
                                    val fadeEnd = transitionEnd.coerceIn(0f, 1f)
                                    Brush.verticalGradient(
                                        colorStops = arrayOf(
                                            0f to Color.White.copy(alpha = edgeAlpha),
                                            fadeEnd to Color.Transparent,
                                            1f to Color.Transparent
                                        ),
                                        startY = 0f,
                                        endY = size.height
                                    )
                                }
                                else -> {
                                    val fadeEnd = transitionEnd.coerceIn(0f, 1f)
                                    Brush.verticalGradient(
                                        colorStops = arrayOf(
                                            0f to Color.White,
                                            rawSweepPosition to Color.White,
                                            fadeEnd to Color.Transparent,
                                            1f to Color.Transparent
                                        ),
                                        startY = 0f,
                                        endY = size.height
                                    )
                                }
                            }
                        }
                        drawRect(brush = mask, blendMode = BlendMode.DstIn)
                    }
            )
        }
    }
}

