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
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
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
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.almog.spotifytablet.Constants
import com.almog.spotifytablet.SettingsActivity
import com.almog.spotifytablet.lyrics.model.LyricLine
import com.almog.spotifytablet.lyrics.model.LyricTrack
import com.almog.spotifytablet.lyrics.model.TrackRhythmContext
import com.almog.spotifytablet.lyrics.model.SpicyMotion
import com.almog.spotifytablet.lyrics.model.WordSync
import com.almog.spotifytablet.lyrics.model.calculateRhythmSpringSpec
import com.almog.spotifytablet.lyrics.model.calculateRhythmWordYOffset
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

private const val MAX_LETTERS_PER_WORD = 20

private fun easeSinOut(value: Float): Float =
    kotlin.math.sin(value.coerceIn(0f, 1f) * (Math.PI.toFloat() / 2f))

/**
 * Spicy's sweep: a linear gradient that is [lit] up to [position] and fades to [dim] over the next
 * [SPICY_SWEEP_FEATHER]. CSS lets the stops run past the box (position -20%..100%), so the colours
 * at the box edges are interpolated rather than clamped.
 */
private fun spicySweepBrush(position: Float, lit: Color, dim: Color): Brush {
    val end = position + SPICY_SWEEP_FEATHER
    fun colorAt(x: Float): Color = when {
        x <= position -> lit
        x >= end -> dim
        else -> lerp(lit, dim, (x - position) / SPICY_SWEEP_FEATHER)
    }
    val stops = ArrayList<Pair<Float, Color>>(4)
    stops += 0f to colorAt(0f)
    if (position > 0f && position < 1f) stops += position to lit
    if (end > 0f && end < 1f) stops += end to dim
    stops += 1f to colorAt(1f)
    return Brush.horizontalGradient(*stops.toTypedArray())
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
    durationMs >= SpicyMotion.LetterMinDurationMs &&
            word.graphemes.size in 1..MAX_LETTERS_PER_WORD &&
            canSplitIntoLetters(word.text)

/** Spicy aligns the second singer of a duet to the opposite side of the screen. */
private fun isOppositeAligned(line: LyricLine): Boolean = line.agentId == "v2"

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

/** Preference that switches between the bundled Spicy Lyrics web renderer (default) and the native one. */
const val PREF_SPICY_WEB_RENDERER = "lyrics_spicy_web_renderer"

@Composable
fun LyricsContent(
    uiState: LyricsUiState,
    modifier: Modifier = Modifier,
    onLineClicked: ((Long) -> Unit)? = null,
    onUserScrollStateChanged: ((Boolean) -> Unit)? = null
) {
    val context = LocalContext.current
    val wantsWeb = remember(context) {
        context.getSharedPreferences(Constants.PREF_NAME, android.content.Context.MODE_PRIVATE)
            .getBoolean(PREF_SPICY_WEB_RENDERER, true)
    }
    // If the device has no usable WebView, fall back to the native renderer instead of showing nothing.
    var webUnavailable by remember { mutableStateOf(false) }
    if (wantsWeb && !webUnavailable) {
        com.almog.spotifytablet.lyrics.web.SpicyWebLyricsContent(
            uiState = uiState,
            modifier = modifier,
            onLineClicked = onLineClicked,
            onUserScrollStateChanged = onUserScrollStateChanged,
            onUnavailable = { webUnavailable = true }
        )
    } else {
        NativeLyricsContent(uiState, modifier, onLineClicked, onUserScrollStateChanged)
    }
}

@Composable
fun NativeLyricsContent(
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

        // Y: Spicy's scroll glide (critically damped, ~0.8s, no overshoot).
        // alpha & scale: short tweens, as in Spicy's line transitions.
        val lineAnimSpec: androidx.compose.animation.core.AnimationSpec<Float> = if (isDiscontinuousSeek) {
            snap()
        } else {
            SpicyMotion.LineScrollSpring
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
                .graphicsLayer {
                    alpha = stageAlpha
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .drawWithContent {
                    drawContent()
                    val fadePx = 64.dp.toPx().coerceAtMost(size.height * 0.25f)
                    val startFraction = if (size.height > 0f) fadePx / size.height else 0f
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Transparent,
                            startFraction to Color.Black,
                            (1f - startFraction) to Color.Black,
                            1f to Color.Transparent
                        ),
                        blendMode = BlendMode.DstIn
                    )
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
                        for (off in -3..4) {
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
                        for (off in 1..4) {
                            y += (lineHeightAt(effectiveCenterIndex + off - 1) +
                                    lineHeightAt(effectiveCenterIndex + off)) / 2f + interLineGapPx
                            map[off] = y
                        }

                        y = 0f
                        for (off in -1 downTo -3) {
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
                        fontSizeSp = lyricsFontSizeSp,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }

            // Render a -3..4 window: one spare line each side so a line gliding out of view (Spicy's
            // scroll takes ~0.8s) never pops out while it is still on screen.
            for (offset in -3..4) {
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

                    // Spicy blurs each line by its distance from the active line (1.25px per line, capped),
                    // so the lyrics you are not at yet melt into the background. The CSS value is a
                    // text-shadow blur radius, i.e. twice the Gaussian sigma that a render effect takes.
                    val isBrowsing = isUserInteracting || manualScrollOffsetLines != 0
                    val targetBlurSigma = if (isActive || isBrowsing || activeLineIndex < 0) {
                        0f
                    } else {
                        val distance = Math.abs(targetIndex - activeLineIndex)
                        minOf(SpicyMotion.BlurPerLinePx * distance, SpicyMotion.BlurMaxPx) / 2f
                    }
                    val blurSigmaState = animateFloatAsState(
                        targetValue = targetBlurSigma,
                        animationSpec = propAnimSpec,
                        label = "lineBlur_${line.startTimeMs}"
                    )
                    val isOpposite = isOppositeAligned(line)

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
                                // `density` here is the Density captured above, hence `.density` for the scale.
                                val blurPx = blurSigmaState.value * density.density
                                renderEffect = if (blurPx > 0.05f) {
                                    BlurEffect(blurPx, blurPx, TileMode.Decal)
                                } else {
                                    null
                                }
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
                            horizontalAlignment = if (isOpposite) Alignment.End else Alignment.Start,
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
                                isSung = targetIndex < activeLineIndex,
                                isOppositeAligned = isOpposite
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
                                    isOppositeAligned = isOpposite,
                                    modifier = Modifier.padding(
                                        top = 4.dp,
                                        start = if (isOpposite) 0.dp else 16.dp,
                                        end = if (isOpposite) 16.dp else 0.dp
                                    )
                                )
                            }

                            // Music interlude dots: integrated cleanly between lines (directly under active line, above next line)
                            if (isActive && pauseInfo != null && pauseAlpha > 0.01f) {
                                SpicyPauseDots(
                                    positionProvider = positionProvider,
                                    pauseStartMs = pauseInfo.pauseStartMs,
                                    nextStartMs = pauseInfo.nextStartMs,
                                    rhythm = rhythmContext,
                                    fontSizeSp = lyricsFontSizeSp,
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
            LyricsAttributionBadge(
                attr = attr,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(bottom = 12.dp, start = 4.dp)
            )
        }
    }
}

@Composable
internal fun LyricsAttributionBadge(
    attr: com.almog.spotifytablet.lyrics.model.LyricAttribution,
    modifier: Modifier = Modifier
) {
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
        modifier = modifier,
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
            Text(text = "· uploaded by", color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp)
            Text(
                text = attr.uploader.username,
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 11.sp,
                textDecoration = if (attr.uploader.url != null) TextDecoration.Underline else TextDecoration.None,
                modifier = Modifier.clickable(enabled = attr.uploader.url != null) { openUrl(attr.uploader.url) }
            )
        }
        if (attr.maker != null) {
            Text(text = "· made by", color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp)
            Text(
                text = attr.maker.username,
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 11.sp,
                textDecoration = if (attr.maker.url != null) TextDecoration.Underline else TextDecoration.None,
                modifier = Modifier.clickable(enabled = attr.maker.url != null) { openUrl(attr.maker.url) }
            )
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
    fontSizeSp: Float = 32f,
    modifier: Modifier = Modifier
) {
    val currentPositionMs = positionProvider()
    val totalTime = (nextStartMs - pauseStartMs).coerceAtLeast(1000L)
    val dotTime = (totalTime / 3f).coerceAtLeast(1f)

    // Each dot runs its own 0..1 progress across a third of the interlude, exactly like a word.
    fun dotProgress(index: Int): Float =
        ((currentPositionMs - (pauseStartMs + index * dotTime)) / dotTime).coerceIn(0f, 1f)

    Row(
        horizontalArrangement = Arrangement.spacedBy((fontSizeSp * 0.5625f).dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(vertical = 14.dp)
    ) {
        for (index in 0..2) {
            SpicyPauseDot(progress = dotProgress(index), fontSizeSp = fontSizeSp)
        }
    }
}

/**
 * One interlude dot. Spicy drives these with the same spline + spring pipeline as words, but with
 * its own curves: they grow from 0.75x to 1.05x, lift by 12% of the font size, and fade from 35%
 * to full opacity as the dot is "sung".
 */
@Composable
private fun SpicyPauseDot(
    progress: Float,
    fontSizeSp: Float
) {
    val scale = animateFloatAsState(
        targetValue = SpicyMotion.DotScale.at(progress),
        animationSpec = SpicyMotion.DotScaleSpring,
        label = "dotScale"
    )
    val lift = animateFloatAsState(
        targetValue = SpicyMotion.DotLift.at(progress),
        animationSpec = SpicyMotion.DotLiftSpring,
        label = "dotLift"
    )
    val opacity = animateFloatAsState(
        targetValue = SpicyMotion.DotOpacity.at(progress),
        animationSpec = SpicyMotion.DotFadeSpring,
        label = "dotOpacity"
    )
    val glow = animateFloatAsState(
        targetValue = SpicyMotion.DotGlow.at(progress),
        animationSpec = SpicyMotion.DotFadeSpring,
        label = "dotGlow"
    )

    Box(
        modifier = Modifier
            .size((fontSizeSp * 0.5f).dp)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                translationY = lift.value * fontSizeSp.sp.toPx()
                alpha = opacity.value.coerceIn(0f, 1f)
            }
            .drawBehind {
                val glowAmount = glow.value.coerceIn(0f, 1f)
                if (glowAmount > 0.01f) {
                    val glowRadius = size.minDimension * 1.1f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.35f * glowAmount),
                                Color.Transparent
                            ),
                            center = center,
                            radius = glowRadius
                        ),
                        radius = glowRadius,
                        center = center
                    )
                }
                drawCircle(color = Color.White)
            }
    )
}

private fun spicyTextStyle(
    color: Color,
    brush: Brush?,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    fontStyle: FontStyle,
    shadow: Shadow?
): TextStyle =
    // Spicy keeps every lyric at weight 700 and zero letter-spacing in all states; changing either
    // while a word is sung would make the line reflow.
    if (brush != null) {
        TextStyle(
            brush = brush,
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            fontStyle = fontStyle,
            fontFamily = FontFamily.SansSerif,
            letterSpacing = 0.sp,
            lineHeight = lineHeight,
            shadow = shadow
        )
    } else {
        TextStyle(
            color = color,
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            fontStyle = fontStyle,
            fontFamily = FontFamily.SansSerif,
            letterSpacing = 0.sp,
            lineHeight = lineHeight,
            shadow = shadow
        )
    }

/** Solid lit/dim fill at the ends of a sweep, the moving gradient in between. */
private fun spicyFill(position: Float, lit: Color, dim: Color): Pair<Color, Brush?> = when {
    position <= -SPICY_SWEEP_FEATHER -> dim to null
    position >= 1f -> lit to null
    else -> lit to spicySweepBrush(position, lit, dim)
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
    isSung: Boolean = false,
    isOppositeAligned: Boolean = false,
    modifier: Modifier = Modifier
) {
    val fontSize = if (isSubduedBackground) (activeFontSizeSp * 0.69f).sp else activeFontSizeSp.sp
    val lineHeight = if (isSubduedBackground) (activeFontSizeSp * 0.875f).sp else (activeFontSizeSp * 1.1818f).sp
    val fontStyle = if (isSubduedBackground) FontStyle.Italic else FontStyle.Normal
    val litColor = Color.White.copy(alpha = if (isSubduedBackground) SpicyMotion.BgLitAlpha else SpicyMotion.LitAlpha)
    val dimColor = Color.White.copy(alpha = if (isSubduedBackground) SpicyMotion.BgDimAlpha else SpicyMotion.DimAlpha)
    val rtl = remember(line.rawText) { isRtlText(line.rawText) }

    // Spicy uses white lyric fills; singer IDs must not tint the whole renderer green/cyan/orange.
    CompositionLocalProvider(
        LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    ) {
        Column(
            horizontalAlignment = if (isOppositeAligned) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.Center,
            modifier = modifier.fillMaxWidth()
        ) {
            if (!isActiveLine && !forceFlowRow) {
                // Spicy keeps the adjacent lines simple: sung lines are fully lit, upcoming ones dim,
                // and the line itself controls its opacity.
                Text(
                    text = line.rawText,
                    textAlign = if (isOppositeAligned) TextAlign.End else TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                    style = spicyTextStyle(
                        color = if (isSung) litColor else dimColor,
                        brush = null,
                        fontSize = fontSize,
                        lineHeight = lineHeight,
                        fontStyle = fontStyle,
                        shadow = null
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
                        horizontalArrangement = if (isOppositeAligned) Arrangement.End else Arrangement.spacedBy(0.dp),
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
                        textAlign = if (isOppositeAligned) TextAlign.End else TextAlign.Start,
                        modifier = Modifier.fillMaxWidth(),
                        style = spicyTextStyle(
                            color = if (isActiveLine || isSung) litColor else dimColor,
                            brush = null,
                            fontSize = fontSize,
                            lineHeight = lineHeight,
                            fontStyle = fontStyle,
                            shadow = null
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
                        textAlign = if (isOppositeAligned) TextAlign.End else TextAlign.Start,
                        color = if (isActiveLine) Color.White.copy(alpha = 0.80f) else Color.White.copy(alpha = 0.50f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp, bottom = 1.dp)
                )
            }
        }
    }
}

/**
 * A word (or syllable) in Spicy's style.
 *
 * Progress through the word is mapped to a scale, a lift and a glow with Spicy's spline curves, but
 * the values the text actually shows are springs chasing those targets. That is what gives Spicy
 * words their slightly loose, bouncy settle after the word is sung, instead of an exact playback
 * of the curve.
 */
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
    val fullDuration = (word.endTimeMs - word.startTimeMs).coerceAtLeast(1L)

    val isLetterCapable = isAnimationEnabled && isLetterCapableDuration(fullDuration, word)
    // Words that are split into letters finish early, so the last letter has time to settle.
    val animEndMs = if (isLetterCapable) {
        maxOf(word.startTimeMs + 1L, word.endTimeMs - SpicyMotion.LetterTailMs)
    } else {
        word.endTimeMs
    }
    val duration = (animEndMs - word.startTimeMs).coerceAtLeast(1L)

    // Predictive Pre-Roll (starts anticipation 45ms before timestamp)
    val rawWordProgress = if (isActiveLine) {
        val elapsed = currentPositionMs + PRE_ROLL_OFFSET_MS - word.startTimeMs
        when {
            elapsed < 0L -> 0f
            elapsed >= duration -> 1f
            else -> (elapsed.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        }
    } else {
        if (currentPositionMs >= animEndMs) 1f else 0f
    }

    val isWordActive = rawWordProgress > 0f && rawWordProgress < 1f
    val isWordCompleted = rawWordProgress >= 1f

    // A word that is rendered whole (line not active) still has to sit where its letters would,
    // otherwise the text jumps in size the moment its line becomes the active one.
    val foldsLetterRest = isLetterCapable && !isActiveLine && !isWordCompleted
    val targetScale = SpicyMotion.WordScale.at(rawWordProgress) *
            (if (foldsLetterRest) SpicyMotion.LetterScale.at(0f) else 1f)
    val targetLift = SpicyMotion.WordLift.at(rawWordProgress) +
            (if (foldsLetterRest) 2f * SpicyMotion.LetterLift.at(0f) else 0f)
    val targetGlow = SpicyMotion.Glow.at(rawWordProgress)

    val scaleState = animateFloatAsState(
        targetValue = targetScale,
        animationSpec = SpicyMotion.WordScaleSpring,
        label = "wordScale"
    )
    val liftState = animateFloatAsState(
        targetValue = targetLift,
        animationSpec = SpicyMotion.WordLiftSpring,
        label = "wordLift"
    )
    val glow = animateFloatAsState(
        targetValue = targetGlow,
        animationSpec = SpicyMotion.WordGlowSpring,
        label = "wordGlow"
    ).value

    Box(
        modifier = modifier.graphicsLayer {
            scaleX = scaleState.value
            scaleY = scaleState.value
            // Lift is a fraction of the (full-size) lyric font, as in Spicy's CSS.
            translationY = liftState.value * activeFontSizeSp.sp.toPx()
        },
        contentAlignment = Alignment.CenterStart
    ) {
        if (isLetterCapable && isActiveLine) {
            SpicyLetterGroupText(
                word = word,
                currentPositionMs = currentPositionMs,
                animEndMs = animEndMs,
                isWordActive = isWordActive,
                isWordCompleted = isWordCompleted,
                isSubduedBackground = isSubduedBackground,
                glowColor = glowColor,
                activeFontSizeSp = activeFontSizeSp
            )
        } else {
            SpicySyllableText(
                word = word,
                rawProgress = rawWordProgress,
                isWordActive = isWordActive,
                isWordCompleted = isWordCompleted,
                glow = glow,
                isSubduedBackground = isSubduedBackground,
                glowColor = glowColor,
                activeFontSizeSp = activeFontSizeSp
            )
        }
    }
}

@Composable
private fun SpicySyllableText(
    word: WordSync,
    rawProgress: Float,
    isWordActive: Boolean,
    isWordCompleted: Boolean,
    glow: Float,
    isSubduedBackground: Boolean,
    glowColor: Color,
    activeFontSizeSp: Float
) {
    val density = LocalDensity.current.density
    val displayString = remember(word.text, word.trailingSpace) {
        if (word.trailingSpace) "${word.text} " else word.text
    }
    val fontSize = if (isSubduedBackground) (activeFontSizeSp * 0.69f).sp else activeFontSizeSp.sp
    val lineHeight = if (isSubduedBackground) (activeFontSizeSp * 0.875f).sp else (activeFontSizeSp * 1.1818f).sp
    val fontStyle = if (isSubduedBackground) FontStyle.Italic else FontStyle.Normal

    val litColor = Color.White.copy(alpha = if (isSubduedBackground) SpicyMotion.BgLitAlpha else SpicyMotion.LitAlpha)
    val dimColor = Color.White.copy(alpha = if (isSubduedBackground) SpicyMotion.BgDimAlpha else SpicyMotion.DimAlpha)

    // Spicy: text-shadow blur 4 + 2 * glow px at (glow * 35)% opacity.
    val shadow = if (glow > 0.01f) {
        Shadow(
            color = glowColor.copy(alpha = (glow * 0.35f).coerceIn(0f, 1f)),
            offset = Offset.Zero,
            blurRadius = (4f + 2f * glow) * density
        )
    } else null

    // Sweep position: -20% at the start of the word to 100% at its end, linear in time.
    val position = when {
        isWordCompleted -> 1f
        isWordActive -> -SPICY_SWEEP_FEATHER + 1.2f * rawProgress
        else -> -SPICY_SWEEP_FEATHER
    }
    val (solid, brush) = spicyFill(position, litColor, dimColor)
    val style = remember(solid, brush, fontSize, lineHeight, fontStyle, shadow) {
        spicyTextStyle(solid, brush, fontSize, lineHeight, fontStyle, shadow)
    }

    Text(
        text = displayString,
        style = style
    )
}

/**
 * Letter-by-letter rendering for long, held words. Spicy spreads the word's (shortened) duration
 * evenly over its letters; the active letter gets the full spline treatment, and its neighbours
 * pick up a share of it that falls off steeply with distance, which produces the travelling swell.
 */
@Composable
private fun SpicyLetterGroupText(
    word: WordSync,
    currentPositionMs: Long,
    animEndMs: Long,
    isWordActive: Boolean,
    isWordCompleted: Boolean,
    isSubduedBackground: Boolean,
    glowColor: Color,
    activeFontSizeSp: Float
) {
    val fontSize = if (isSubduedBackground) (activeFontSizeSp * 0.69f).sp else activeFontSizeSp.sp
    val lineHeight = if (isSubduedBackground) (activeFontSizeSp * 0.875f).sp else (activeFontSizeSp * 1.1818f).sp
    val fontStyle = if (isSubduedBackground) FontStyle.Italic else FontStyle.Normal

    val litColor = Color.White.copy(alpha = if (isSubduedBackground) SpicyMotion.BgLitAlpha else SpicyMotion.LitAlpha)
    val dimColor = Color.White.copy(alpha = if (isSubduedBackground) SpicyMotion.BgDimAlpha else SpicyMotion.DimAlpha)

    val graphemes = word.graphemes
    val count = graphemes.size.coerceAtLeast(1)
    val letterDurationMs = ((animEndMs - word.startTimeMs).coerceAtLeast(1L)).toFloat() / count
    val shiftedPositionMs = currentPositionMs + PRE_ROLL_OFFSET_MS

    val restScale = SpicyMotion.LetterScale.at(0f)
    val restLift = SpicyMotion.LetterLift.at(0f)
    val restGlow = SpicyMotion.Glow.at(0f)

    // Which letter is being sung right now, and how far through it we are.
    var activeLetterIndex = -1
    var activeLetterProgress = 0f
    if (isWordActive) {
        val raw = (shiftedPositionMs - word.startTimeMs) / letterDurationMs
        activeLetterIndex = kotlin.math.floor(raw).toInt().coerceIn(0, count - 1)
        activeLetterProgress = (raw - activeLetterIndex).coerceIn(0f, 1f)
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        graphemes.forEachIndexed { index, graphemeCluster ->
            val letterStartMs = word.startTimeMs + index * letterDurationMs
            val letterEndMs = letterStartMs + letterDurationMs
            val isLetterSung = shiftedPositionMs >= letterEndMs
            val isLetterNotSung = shiftedPositionMs < letterStartMs
            val isLetterActive = !isLetterSung && !isLetterNotSung

            var targetScale = restScale
            var targetLift = restLift
            var targetGlow = restGlow

            if (isWordCompleted) {
                targetScale = SpicyMotion.LetterScale.at(1f)
                targetLift = SpicyMotion.LetterLift.at(1f)
                targetGlow = SpicyMotion.Glow.at(1f)
            } else if (isWordActive && activeLetterIndex != -1 && !isLetterNotSung) {
                val distance = kotlin.math.abs(index - activeLetterIndex).toFloat()
                val falloff = 1f / (1f + Math.pow(distance.toDouble(), 2.8).toFloat())
                val glowFalloff = 1f / (1f + distance * 0.9f)
                val baseScale = SpicyMotion.LetterScale.at(activeLetterProgress)
                val baseLift = SpicyMotion.LetterLift.at(activeLetterProgress)
                val baseGlow = SpicyMotion.Glow.at(activeLetterProgress)
                targetScale = restScale + (baseScale - restScale) * falloff
                targetLift = restLift + (baseLift - restLift) * falloff
                targetGlow = restGlow + (baseGlow - restGlow) * glowFalloff
            }

            val sweepPosition = when {
                isWordCompleted || isLetterSung -> 1f
                isLetterActive && index == activeLetterIndex ->
                    -SPICY_SWEEP_FEATHER + 1.2f * easeSinOut(activeLetterProgress)
                else -> -SPICY_SWEEP_FEATHER
            }

            SpicyLetterText(
                letter = graphemeCluster,
                targetScale = targetScale,
                targetLift = targetLift,
                targetGlow = targetGlow,
                sweepPosition = sweepPosition,
                litColor = litColor,
                dimColor = dimColor,
                glowColor = glowColor,
                fontSize = fontSize,
                lineHeight = lineHeight,
                fontStyle = fontStyle,
                activeFontSizeSp = activeFontSizeSp
            )
        }

        if (word.trailingSpace) {
            Text(
                text = " ",
                style = TextStyle(fontSize = fontSize, lineHeight = lineHeight)
            )
        }
    }
}

@Composable
private fun SpicyLetterText(
    letter: String,
    targetScale: Float,
    targetLift: Float,
    targetGlow: Float,
    sweepPosition: Float,
    litColor: Color,
    dimColor: Color,
    glowColor: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    fontStyle: FontStyle,
    activeFontSizeSp: Float
) {
    val density = LocalDensity.current.density
    val scaleState = animateFloatAsState(
        targetValue = targetScale,
        animationSpec = SpicyMotion.WordScaleSpring,
        label = "letterScale"
    )
    val liftState = animateFloatAsState(
        targetValue = targetLift,
        animationSpec = SpicyMotion.WordLiftSpring,
        label = "letterLift"
    )
    val glow = animateFloatAsState(
        targetValue = targetGlow,
        animationSpec = SpicyMotion.WordGlowSpring,
        label = "letterGlow"
    ).value

    // Spicy letters glow harder than whole words: blur 4 + 12 * glow px at up to 100% opacity.
    val shadow = if (glow > 0.01f) {
        Shadow(
            color = glowColor.copy(alpha = (glow * 1.85f).coerceIn(0f, 1f)),
            offset = Offset.Zero,
            blurRadius = (4f + 12f * glow) * density
        )
    } else null

    val (solid, brush) = spicyFill(sweepPosition, litColor, dimColor)
    val style = remember(solid, brush, fontSize, lineHeight, fontStyle, shadow) {
        spicyTextStyle(solid, brush, fontSize, lineHeight, fontStyle, shadow)
    }

    Box(
        modifier = Modifier.graphicsLayer {
            scaleX = scaleState.value
            scaleY = scaleState.value
            // Letters lift twice as far as the word-level curve, as in Spicy.
            translationY = liftState.value * 2f * activeFontSizeSp.sp.toPx()
        },
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = letter,
            style = style
        )
    }
}
