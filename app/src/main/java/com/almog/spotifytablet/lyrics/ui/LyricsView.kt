package com.almog.spotifytablet.lyrics.ui

import android.os.Build
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.almog.spotifytablet.SettingsActivity
import com.almog.spotifytablet.lyrics.model.LyricLine
import com.almog.spotifytablet.lyrics.model.TrackRhythmContext
import com.almog.spotifytablet.lyrics.model.WordSync
import com.almog.spotifytablet.lyrics.model.calculateRhythmWordScale
import com.almog.spotifytablet.lyrics.model.calculateRhythmWordYOffset
import com.almog.spotifytablet.lyrics.viewmodel.LyricsUiState
import com.almog.spotifytablet.lyrics.viewmodel.LyricsViewModel
import com.almog.spotifytablet.lyrics.viewmodel.findActiveLineIndex
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/*
 * ─────────────────────────────────────────────────────────────────────────────
 *  Spicy-Lyrics style renderer — battery-first design
 * ─────────────────────────────────────────────────────────────────────────────
 *  1. ZERO per-frame recomposition. Position is passed down as a `() -> Long`
 *     provider and only read inside graphicsLayer / drawWithContent
 *     lambdas (layout & composition are never invalidated by the 60fps clock).
 *  2. Per-word "phase" (idle / active / done) is a derivedStateOf, so nothing
 *     is invalidated except when a word changes phase (3x per word).
 *  3. The syllable sweep is a GPU gradient mask (DstIn over an offscreen layer)
 *     on ONE static Text — no TextStyle rebuilds, no text re-layout per frame.
 *     Offscreen layers exist only while a segment is actually active.
 *  4. Glow is a STATIC soft white text shadow (no per-frame style changes, no green tint).
 *  5. Line move / alpha / scale / blur animations are read as State inside
 *     graphicsLayer lambdas -> no recomposition while lines glide.
 *  6. The frame loop sleeps (250ms ticks) when nothing is animating
 *     (intro without dots, outro, no lyrics) and stops entirely when paused.
 */

/** Start transitioning to the next lyric before its timestamp so the motion keeps up with the singer. */
private const val LINE_LEAD_MS = 120L

/** Lines kept composed behind / ahead of the centre line (outer ones are invisible, so nothing pops). */
private const val WINDOW_BEHIND = 3
private const val WINDOW_AHEAD = 4

/** Scroll spring (tune here): higher stiffness = faster glide, lower damping = more overshoot. */
private const val LINE_SPRING_STIFFNESS = 650f
private const val LINE_SPRING_DAMPING = 0.9f

private const val PHASE_IDLE = 0
private const val PHASE_ACTIVE = 1
private const val PHASE_DONE = 2

/** Alpha of not-yet-sung text inside the active line. */
private const val DIM_ALPHA = 0.50f

/** Width (fraction of the word) of the soft leading edge of the gradient sweep. */
private const val SWEEP_FEATHER = 0.20f

/** Per-line blur (dp per line of distance from the active line). API 31+ only. */
private const val LINE_BLUR_DP_PER_STEP = 1.5f
private const val LINE_BLUR_MAX_DP = 4.5f

private val LineTransformOrigin = TransformOrigin(0f, 0.5f)

/** Where a line is relative to the currently-sung line. */
enum class LyricLineMode { Past, Active, Upcoming }

private data class PauseInfo(val pauseStartMs: Long, val nextStartMs: Long)

/** Immutable bundle of per-line visual params shared by every word/letter of a line. */
private class WordVisuals(
    val textStyle: TextStyle,
    val baseAlpha: Float,
    val dimAlpha: Float,
    val amplitude: Float,
    val rtl: Boolean,
    val spaceWidth: Dp
) {
    /** Keep a restrained bloom while the segment is active without rebuilding TextStyle every frame. */
    val litTextStyle: TextStyle = textStyle.copy(
        shadow = Shadow(
            color = Color.White.copy(alpha = 0.12f * amplitude),
            offset = Offset.Zero,
            blurRadius = 6f
        )
    )
}

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

    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences(com.almog.spotifytablet.Constants.PREF_NAME, android.content.Context.MODE_PRIVATE)
    }
    val isPauseDotsPrefEnabled = prefs.getBoolean(SettingsActivity.PREF_PAUSE_DOTS, true)
    val isDynamicSpacingPrefEnabled = prefs.getBoolean(SettingsActivity.PREF_DYNAMIC_SPACING, true)
    val lyricsFontSizeSp = prefs.getInt(com.almog.spotifytablet.Constants.PREF_KEY_LYRICS_FONT_SIZE, 32).toFloat()

    // ── Position state (long states, read lazily) ────────────────────────────
    val currentPositionMs = remember { mutableLongStateOf(anchor.positionMs) }
    val displayPositionMs = remember { mutableLongStateOf(anchor.positionMs) }
    // Stable provider: children read it ONLY in draw / layer lambdas.
    val positionProvider = remember { { displayPositionMs.longValue } }

    LaunchedEffect(track) {
        currentPositionMs.longValue = anchor.positionMs
        displayPositionMs.longValue = anchor.positionMs
    }

    val displayLines = remember(track?.lines) {
        track?.lines?.filter { !it.isBackground } ?: emptyList()
    }

    // Active line is a derived state: composition is only invalidated when the INDEX changes.
    val lastActiveRef = remember(track) { intArrayOf(-1) }
    val activeIndexState = remember(displayLines) {
        derivedStateOf {
            val idx = if (displayLines.isNotEmpty()) {
                findActiveLineIndex(displayLines, currentPositionMs.longValue + LINE_LEAD_MS, lastActiveRef[0])
            } else -1
            lastActiveRef[0] = idx
            idx
        }
    }
    val activeLineIndex = activeIndexState.value

    val pauseInfoState = remember(displayLines, isPauseDotsPrefEnabled) {
        derivedStateOf {
            if (isPauseDotsPrefEnabled) {
                getPauseInfo(displayLines, activeIndexState.value, currentPositionMs.longValue)
            } else null
        }
    }
    val pauseInfo = pauseInfoState.value

    // Idle = nothing on screen is animating (intro w/o dots, outro, no lyrics).
    val isIdleState = remember(displayLines) {
        derivedStateOf { activeIndexState.value == -1 && pauseInfoState.value == null }
    }

    // ── Single frame-driven clock ────────────────────────────────────────────
    LaunchedEffect(anchor.isPlaying, anchor.positionMs, anchor.anchorRealtimeMs, anchor.speed, displayLines) {
        if (!anchor.isPlaying) {
            currentPositionMs.longValue = anchor.positionMs
            displayPositionMs.longValue = anchor.positionMs
            return@LaunchedEffect
        }

        if (abs(anchor.positionMs - displayPositionMs.longValue) > 1500L) {
            displayPositionMs.longValue = anchor.positionMs
        }
        val firstStart = displayLines.firstOrNull()?.startTimeMs ?: Long.MAX_VALUE
        val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
        var minFrameNanos = 10_000_000L   // caps 90/120Hz panels to ~60 updates/s
        var lastFrameNanos = 0L
        var frameCounter = 0

        while (true) {
            if (frameCounter++ % 120 == 0) {
                minFrameNanos = if (powerManager?.isPowerSaveMode == true) 30_000_000L else 10_000_000L
            }
            if (isIdleState.value) {
                // Nothing animates: tick slowly instead of waking the GPU every vsync.
                val pos = anchor.positionMs +
                        ((android.os.SystemClock.elapsedRealtime() - anchor.anchorRealtimeMs) * anchor.speed).toLong()
                currentPositionMs.longValue = pos
                displayPositionMs.longValue = pos
                val sleepMs = if (pos < firstStart) ((firstStart - pos) / 2).coerceIn(16L, 250L) else 250L
                delay(sleepMs)
                continue
            }
            withFrameNanos { frameNanos ->
                if (frameNanos - lastFrameNanos < minFrameNanos) return@withFrameNanos
                lastFrameNanos = frameNanos
                val elapsedMs = android.os.SystemClock.elapsedRealtime() - anchor.anchorRealtimeMs
                val newPos = anchor.positionMs + (elapsedMs * anchor.speed).toLong()
                currentPositionMs.longValue = newPos

                // Use the playback clock directly. Low-pass smoothing made timed words
                // trail the singer, especially when Spotify refreshed the anchor.
                displayPositionMs.longValue = newPos
            }
        }
    }

    // ── Manual scroll override ───────────────────────────────────────────────
    var manualScrollOffsetLines by remember { mutableIntStateOf(0) }
    var isUserInteracting by remember { mutableStateOf(false) }
    var lastInteractionTime by remember { mutableLongStateOf(0L) }

    LaunchedEffect(isUserInteracting, lastInteractionTime) {
        if (!isUserInteracting && manualScrollOffsetLines != 0) {
            delay(3000L)
            manualScrollOffsetLines = 0
            onUserScrollStateChanged?.invoke(false)
        }
    }

    val effectiveCenterIndex = (activeLineIndex + manualScrollOffsetLines).coerceIn(-1, displayLines.lastIndex)
    val isManualScrolling = isUserInteracting || manualScrollOffsetLines != 0

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 0.dp)
            .pointerInput(displayLines.size) {
                detectVerticalDragGestures(
                    onDragStart = {
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
                        if (dragAmount < -30f) {
                            manualScrollOffsetLines = (manualScrollOffsetLines + 1).coerceAtMost(3)
                            lastInteractionTime = System.currentTimeMillis()
                        } else if (dragAmount > 30f) {
                            manualScrollOffsetLines = (manualScrollOffsetLines - 1).coerceAtLeast(-3)
                            lastInteractionTime = System.currentTimeMillis()
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        if (track == null || track.lines.isEmpty()) {
            return@Box
        }

        val rhythmContext = remember(track.bpm) {
            if (track.bpm != null && track.bpm > 0f) {
                TrackRhythmContext(bpm = track.bpm, hasExplicitBpm = true)
            } else {
                TrackRhythmContext.Default
            }
        }

        val lastLineEndTime = track.lines.lastOrNull()?.endTimeMs ?: Long.MAX_VALUE
        val isOutro by remember(displayLines, lastLineEndTime) {
            derivedStateOf { activeIndexState.value == -1 && currentPositionMs.longValue >= lastLineEndTime }
        }
        val stageAlphaState = animateFloatAsState(
            targetValue = if (isOutro) 0f else 1f,
            animationSpec = tween(durationMillis = 500, easing = LinearOutSlowInEasing),
            label = "stageAlpha"
        )

        // Seek discontinuity → snap instead of animating through
        var prevAnchorPositionMs by remember { mutableLongStateOf(anchor.positionMs) }
        val isDiscontinuousSeek = abs(anchor.positionMs - prevAnchorPositionMs) > 1500L
        LaunchedEffect(anchor.positionMs) { prevAnchorPositionMs = anchor.positionMs }

        val propAnimSpec: AnimationSpec<Float> = if (isDiscontinuousSeek) {
            snap()
        } else {
            tween(durationMillis = 150, easing = FastOutSlowInEasing)
        }

        // RenderEffect blur re-runs every frame on every blurred line -> opt-in (off by default).
        val blurSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isAnimationEnabled &&
                prefs.getBoolean("lyrics_line_blur", false)

        val activeLine = displayLines.getOrNull(activeLineIndex)
        val companionBgLine = remember(activeLine, track.lines) {
            if (activeLine != null) {
                track.lines.find { other ->
                    other !== activeLine &&
                            other.isBackground && (
                            (other.startTimeMs in activeLine.startTimeMs..activeLine.endTimeMs) ||
                                    (activeLine.startTimeMs in other.startTimeMs..other.endTimeMs) ||
                                    (abs(other.startTimeMs - activeLine.startTimeMs) < 2500L)
                            )
                }
            } else null
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = stageAlphaState.value },
            contentAlignment = Alignment.CenterStart
        ) {
            val lineHeightsPx = remember { mutableStateMapOf<Long, Int>() }
            val density = LocalDensity.current
            val interLineGapPx = with(density) { 36.dp.toPx() }
            val fallbackLineHeightPx = with(density) { (lyricsFontSizeSp * 1.1875f).sp.toPx() }

            val targetYOffsetsPx by remember(effectiveCenterIndex, displayLines, isDynamicSpacingPrefEnabled, fallbackLineHeightPx) {
                derivedStateOf {
                    val map = HashMap<Int, Float>()
                    map[0] = 0f
                    if (!isDynamicSpacingPrefEnabled) {
                        val slot = with(density) { 88.dp.toPx() }
                        for (off in -WINDOW_BEHIND..WINDOW_AHEAD) map[off] = off * slot
                    } else {
                        fun h(off: Int): Float {
                            val l = displayLines.getOrNull(effectiveCenterIndex + off) ?: return fallbackLineHeightPx
                            return lineHeightsPx[l.startTimeMs]?.toFloat() ?: fallbackLineHeightPx
                        }
                        // Every line is centred in the stage, so spacing = centre-to-centre distance.
                        var y = 0f
                        for (off in 1..WINDOW_AHEAD) {
                            y += (h(off - 1) + h(off)) / 2f + interLineGapPx
                            map[off] = y
                        }
                        y = 0f
                        for (off in -1 downTo -WINDOW_BEHIND) {
                            y -= (h(off + 1) + h(off)) / 2f + interLineGapPx
                            map[off] = y
                        }
                    }
                    map
                }
            }

            // Interlude: the dots get their own slot. Lines below the active one slide down to make room
            // (spring), the dots pop in inside that slot, then everything slides back as the next line starts.
            val dotsSlotPx = with(density) { 56.dp.toPx() }
            val roomAnchor = remember { intArrayOf(-1) }
            if (pauseInfo != null && activeLineIndex >= 0) roomAnchor[0] = activeLineIndex
            val roomState = animateFloatAsState(
                targetValue = if (pauseInfo != null && activeLineIndex >= 0 && !isManualScrolling) dotsSlotPx else 0f,
                animationSpec = spring(dampingRatio = 0.9f, stiffness = 260f),
                label = "dotsRoom"
            )

            if (pauseInfo != null && !isManualScrolling) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            translationY = if (activeLineIndex >= 0) {
                                val hActive = displayLines.getOrNull(activeLineIndex)
                                    ?.let { lineHeightsPx[it.startTimeMs]?.toFloat() } ?: fallbackLineHeightPx
                                hActive / 2f + (interLineGapPx + roomState.value) / 2f
                            } else 0f
                        },
                    contentAlignment = Alignment.CenterStart
                ) {
                    SpicyPauseDots(
                        positionProvider = positionProvider,
                        pauseStartMs = pauseInfo.pauseStartMs,
                        nextStartMs = pauseInfo.nextStartMs,
                        rhythm = rhythmContext,
                        dotDiameter = (lyricsFontSizeSp * 0.5f).dp,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }

            for (offset in -WINDOW_BEHIND..WINDOW_AHEAD) {
                val targetIndex = effectiveCenterIndex + offset
                val line = displayLines.getOrNull(targetIndex) ?: continue

                key(line.startTimeMs) {
                    val isActive = targetIndex == activeLineIndex
                    // ±1 neighbours keep the FlowRow structure so there is never a layout mode switch.
                    val renderAsActive = isActive || offset in -2..3

                    val mode = when {
                        isActive -> LyricLineMode.Active
                        targetIndex < activeLineIndex -> LyricLineMode.Past
                        else -> LyricLineMode.Upcoming
                    }

                    val targetYPx = targetYOffsetsPx[offset] ?: (offset * (fallbackLineHeightPx + interLineGapPx))

                    // Keep line movement immediate on a new lyric timestamp. The spring smooths
                    // the movement without delaying the next line behind a stagger timer.
                    val ySpec: AnimationSpec<Float> = if (isDiscontinuousSeek) {
                        snap()
                    } else {
                        spring(dampingRatio = LINE_SPRING_DAMPING, stiffness = LINE_SPRING_STIFFNESS)
                    }
                    val yState = animateFloatAsState(targetYPx, ySpec, label = "lineY")
                    val alphaState = animateFloatAsState(
                        targetValue = when {
                            isActive -> 1.0f
                            else -> when (abs(offset)) {
                                0 -> 0.9f
                                1 -> 0.5f
                                2 -> 0.3f
                                3 -> 0.14f
                                else -> 0f
                            }
                        },
                        animationSpec = propAnimSpec,
                        label = "lineAlpha"
                    )
                    val scaleState = animateFloatAsState(
                        targetValue = if (isActive) 1.0f else 0.95f,
                        animationSpec = propAnimSpec,
                        label = "lineScale"
                    )
                    val blurDp = if (!blurSupported || isActive || isManualScrolling || activeLineIndex < 0) {
                        0f
                    } else {
                        min(abs(targetIndex - activeLineIndex) * LINE_BLUR_DP_PER_STEP, LINE_BLUR_MAX_DP)
                    }
                    val blurState = animateFloatAsState(blurDp, propAnimSpec, label = "lineBlur")

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onSizeChanged { size ->
                                if (size.height > 0 && lineHeightsPx[line.startTimeMs] != size.height) {
                                    lineHeightsPx[line.startTimeMs] = size.height
                                }
                            }
                            .graphicsLayer {
                                translationY = yState.value + (if (targetIndex > roomAnchor[0]) roomState.value else 0f)
                                alpha = alphaState.value
                                val s = scaleState.value
                                scaleX = s
                                scaleY = s
                                transformOrigin = LineTransformOrigin
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    val b = blurState.value * this.density
                                    renderEffect = if (b > 0.5f) BlurEffect(b, b, TileMode.Decal) else null
                                }
                            }
                            .then(
                                if (onLineClicked != null) {
                                    Modifier.clickable { onLineClicked.invoke(line.startTimeMs) }
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
                                mode = mode,
                                forceFlowRow = renderAsActive,
                                isSubduedBackground = line.isBackground,
                                rhythm = rhythmContext,
                                activeFontSizeSp = lyricsFontSizeSp
                            )

                            val unattachedCompanionBg =
                                if (line.backgroundLine == null && isActive && companionBgLine != null && line != companionBgLine) companionBgLine else null
                            if (unattachedCompanionBg != null) {
                                SingleLyricLineRow(
                                    line = unattachedCompanionBg,
                                    positionProvider = positionProvider,
                                    isAnimationEnabled = isAnimationEnabled,
                                    mode = LyricLineMode.Active,
                                    isSubduedBackground = true,
                                    rhythm = rhythmContext,
                                    activeFontSizeSp = lyricsFontSizeSp,
                                    modifier = Modifier.padding(top = 4.dp, start = 16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Attribution badge (required by provider terms)
        if (track.attribution != null) {
            val attr = track.attribution
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
        } else if (!track.source.isNullOrBlank()) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(bottom = 12.dp, start = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Lyrics from ${track.source}",
                    color = Color.White.copy(alpha = 0.40f),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.SansSerif
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Interlude dots
// ─────────────────────────────────────────────────────────────────────────────

/** Pause detection runs on the SAME list the index refers to (display lines, no bg vocals). */
private fun getPauseInfo(lines: List<LyricLine>, activeLineIndex: Int, currentPositionMs: Long): PauseInfo? {
    if (lines.isEmpty()) return null

    if (activeLineIndex == -1) {
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
            val gapStart = currentLine.endTimeMs + 300L
            if (currentPositionMs in gapStart until nextLine.startTimeMs) {
                return PauseInfo(pauseStartMs = gapStart, nextStartMs = nextLine.startTimeMs)
            }
        }
    }
    return null
}

/**
 * Spicy-style interlude dots, a pure function of playback time (no state, no recomposition):
 *  - group pops in with an overshoot, then shrinks + floats up just before the next line
 *  - each dot fills (scale + brightness + soft glow) over its third of the gap
 *  - gentle staggered bob locked to the song tempo
 */
@Composable
fun SpicyPauseDots(
    positionProvider: () -> Long,
    pauseStartMs: Long,
    nextStartMs: Long,
    rhythm: TrackRhythmContext = TrackRhythmContext.Default,
    dotDiameter: Dp = 16.dp,
    modifier: Modifier = Modifier
) {
    val totalTime = (nextStartMs - pauseStartMs).coerceAtLeast(1000L)
    val dotTime = totalTime / 3f
    val twoPi = 2f * PI.toFloat()

    Canvas(
        modifier = modifier.size(width = dotDiameter * 5.6f + 16.dp, height = dotDiameter * 2.2f)
    ) {
        val pos = positionProvider()
        val d = dotDiameter.toPx()
        val r = d / 2f
        val gap = d * 1.1f
        val startX = 8.dp.toPx() + r
        val centerY = size.height / 2f

        val enterT = ((pos - pauseStartMs) / 450f).coerceIn(0f, 1f)
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val u = enterT - 1f
        val enter = 1f + c3 * u * u * u + c1 * u * u            // easeOutBack

        val exitT = ((nextStartMs - LINE_LEAD_MS - pos) / 420f).coerceIn(0f, 1f)
        val exit = exitT * exitT * (3f - 2f * exitT)

        val beat = rhythm.beatIntervalMs
        val phase = twoPi * (pos / (beat * 2f))                  // one bob cycle per two beats
        val breathe = 1f + 0.06f * sin(phase)
        val bobAmp = d * 0.22f * enterT
        val lift = (1f - exit) * -d * 0.5f

        for (i in 0..2) {
            val p = ((pos - (pauseStartMs + i * dotTime)) / dotTime).coerceIn(0f, 1f)
            val e = p * p * (3f - 2f * p)
            val bob = sin(phase - i * 0.9f) * bobAmp
            val scale = ((0.7f + 0.5f * e) * breathe * enter * exit).coerceAtLeast(0f)
            val a = ((0.3f + 0.7f * e) * exit * enterT).coerceIn(0f, 1f)
            val c = Offset(startX + i * (d + gap), centerY + bob + lift)
            if (e > 0.05f && scale > 0f) {
                val gr = r * 2.4f * scale
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to Color.White.copy(alpha = 0.22f * e * exit),
                        1f to Color.Transparent,
                        center = c,
                        radius = gr
                    ),
                    radius = gr,
                    center = c
                )
            }
            drawCircle(color = Color.White.copy(alpha = a), radius = r * scale, center = c)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Line / word rendering
// ─────────────────────────────────────────────────────────────────────────────

private fun isRtlText(s: String): Boolean {
    for (ch in s) {
        when (Character.getDirectionality(ch)) {
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
            else -> {}
        }
    }
    return false
}

/** Arabic / Syriac / Indic scripts join or reorder glyphs → never split into per-letter nodes. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SingleLyricLineRow(
    line: LyricLine,
    positionProvider: () -> Long,
    isAnimationEnabled: Boolean = true,
    mode: LyricLineMode = LyricLineMode.Active,
    forceFlowRow: Boolean = false,
    isSubduedBackground: Boolean = false,
    rhythm: TrackRhythmContext = TrackRhythmContext.Default,
    activeFontSizeSp: Float = 32f,
    modifier: Modifier = Modifier
) {
    val rtl = remember(line.rawText) { isRtlText(line.rawText) }
    val density = LocalDensity.current

    val visuals = remember(activeFontSizeSp, isSubduedBackground, rtl, line.agentId, density) {
        val fontSizeSp = if (isSubduedBackground) activeFontSizeSp * 0.69f else activeFontSizeSp
        val lineHeightSp = if (isSubduedBackground) activeFontSizeSp * 0.875f else activeFontSizeSp * 1.1875f
        WordVisuals(
            textStyle = TextStyle(
                color = Color.White,
                fontSize = fontSizeSp.sp,
                lineHeight = lineHeightSp.sp,
                fontWeight = FontWeight.Bold,
                fontStyle = if (isSubduedBackground) FontStyle.Italic else FontStyle.Normal,
                fontFamily = FontFamily.SansSerif,
                letterSpacing = 0.sp
            ),
            baseAlpha = if (isSubduedBackground) 0.6f else 0.85f,
            dimAlpha = if (isSubduedBackground) 0.3f else DIM_ALPHA,
            amplitude = if (isSubduedBackground) 0.6f else 1.0f,
            rtl = rtl,
            spaceWidth = with(density) { (fontSizeSp * 0.18f).sp.toDp() }
        )
    }

    CompositionLocalProvider(
        LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    ) {
        Column(
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.Center,
            modifier = modifier.fillMaxWidth()
        ) {
            if (mode != LyricLineMode.Active && !forceFlowRow) {
                // Far lines: a single cheap Text, zero per-word nodes.
                Text(
                    text = line.rawText,
                    style = visuals.textStyle,
                    color = Color.White.copy(alpha = visuals.baseAlpha)
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
                            tokens.mapIndexed { idx, tok ->
                                WordSync(
                                    text = tok,
                                    startTimeMs = line.startTimeMs + (idx * tokenDur),
                                    endTimeMs = line.startTimeMs + ((idx + 1) * tokenDur),
                                    trailingSpace = idx < tokens.size - 1
                                )
                            }
                        }
                    }
                } else emptyList()

                if (effectiveWords.isNotEmpty()) {
                    val wordGroups = remember(effectiveWords) {
                        val groups = mutableListOf<List<WordSync>>()
                        var currentGroup = mutableListOf<WordSync>()
                        for (syl in effectiveWords) {
                            currentGroup.add(syl)
                            if (syl.trailingSpace) {
                                groups.add(currentGroup)
                                currentGroup = mutableListOf()
                            }
                        }
                        if (currentGroup.isNotEmpty()) groups.add(currentGroup)
                        groups
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.Start,
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        wordGroups.forEach { syllables ->
                            // Keep all syllables of a word together so FlowRow never splits a word.
                            Row(verticalAlignment = Alignment.Bottom) {
                                syllables.forEach { syl ->
                                    SpicyWord(
                                        word = syl,
                                        mode = mode,
                                        positionProvider = positionProvider,
                                        rhythm = rhythm,
                                        visuals = visuals
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Text(
                        text = line.rawText,
                        style = visuals.textStyle,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                }
            }

            // Attached background vocals
            val bgLine = line.backgroundLine
            if (bgLine != null) {
                val bgMode = if (mode == LyricLineMode.Active) {
                    val st = remember(bgLine, positionProvider) {
                        derivedStateOf {
                            val p = positionProvider()
                            when {
                                p < bgLine.startTimeMs - 200L -> LyricLineMode.Upcoming
                                p > bgLine.endTimeMs + 500L -> LyricLineMode.Past
                                else -> LyricLineMode.Active
                            }
                        }
                    }
                    st.value
                } else mode
                SingleLyricLineRow(
                    line = bgLine,
                    positionProvider = positionProvider,
                    isAnimationEnabled = isAnimationEnabled,
                    mode = bgMode,
                    forceFlowRow = mode == LyricLineMode.Active || forceFlowRow,
                    isSubduedBackground = true,
                    rhythm = rhythm,
                    activeFontSizeSp = activeFontSizeSp,
                    modifier = Modifier.padding(top = 4.dp, start = 16.dp)
                )
            }

            // Translation / transliteration
            if (!line.translation.isNullOrBlank()) {
                val base = visuals.textStyle
                Text(
                    text = line.translation,
                    style = TextStyle(
                        fontSize = (base.fontSize.value * 0.55f).sp,
                        lineHeight = (base.lineHeight.value * 0.6f).sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.SansSerif,
                        textAlign = TextAlign.Start,
                        color = if (mode == LyricLineMode.Active) Color(0xCCFFFFFF) else Color(0x88FFFFFF)
                    ),
                    modifier = Modifier.padding(top = 2.dp, bottom = 1.dp)
                )
            }
        }
    }
}

/**
 * One syllable/word. Long sustained words are split into per-grapheme segments
 * (Spicy's "letter" animation), keeping Nikkud / emoji sequences intact via graphemes.
 */
@Composable
private fun SpicyWord(
    word: WordSync,
    mode: LyricLineMode,
    positionProvider: () -> Long,
    rhythm: TrackRhythmContext,
    visuals: WordVisuals
) {
    // Keep each synchronized segment together. Splitting every long word into
    // independently timed graphemes creates a stuttery, letter-by-letter effect
    // for ordinary lyrics. Spicy only does this when the lyric source explicitly
    // marks a letter group, which WordSync does not currently expose.
    SweepSegment(word.text, word.startTimeMs, word.endTimeMs, mode, positionProvider, rhythm, visuals)
    if (word.trailingSpace) {
        Spacer(Modifier.width(visuals.spaceWidth))
    }
}

/**
 * The core primitive: one static Text + draw-phase-only animation.
 *
 *  - graphicsLayer #1: rhythm scale + Y lift (Spicy ScaleRange / YOffsetRange)
 *  - graphicsLayer #2: Offscreen ONLY while active (needed for the DstIn mask)
 *  - drawWithContent:  gradient alpha mask → leading-edge sweep, RTL-aware
 */
@Composable
private fun SweepSegment(
    text: String,
    startMs: Long,
    endMs: Long,
    mode: LyricLineMode,
    positionProvider: () -> Long,
    rhythm: TrackRhythmContext,
    visuals: WordVisuals
) {
    val duration = (endMs - startMs).coerceAtLeast(1L)

    // Line scrolling can move to the next line before this word's timestamp window ends.
    // Word animation must follow the word's own timestamps, not the line's visual mode, or
    // the last word freezes mid-sweep when the previous line becomes Past.
    val phaseState = remember(startMs, endMs, positionProvider) {
        derivedStateOf {
            val p = positionProvider()
            if (p < startMs) PHASE_IDLE else if (p >= endMs) PHASE_DONE else PHASE_ACTIVE
        }
    }

    val baseAlpha = visuals.baseAlpha
    val dimAlpha = visuals.dimAlpha
    val amplitude = visuals.amplitude
    val currentPhase by phaseState
    val rtl = visuals.rtl

    Box(
        modifier = Modifier
            .graphicsLayer {
                val phase = phaseState.value
                if (phase == PHASE_ACTIVE) {
                    val pos = positionProvider()
                    val raw = ((pos - startMs).toFloat() / duration).coerceIn(0f, 1f)
                    val s = 1f + (calculateRhythmWordScale(raw, duration, pos, rhythm) - 1f) * amplitude * 0.35f
                    scaleX = s
                    scaleY = s
                    translationY = calculateRhythmWordYOffset(raw, duration, rhythm) * amplitude * 0.35f * this.density
                } else if (phase == PHASE_IDLE && mode == LyricLineMode.Active) {
                    scaleX = 0.95f
                    scaleY = 0.95f
                }
            }
            .graphicsLayer {
                val phase = phaseState.value
                if (phase == PHASE_ACTIVE) {
                    compositingStrategy = CompositingStrategy.Offscreen
                    // The gradient supplies the exact Spicy opacity range. Do not multiply it again.
                    alpha = 1f
                } else {
                    alpha = if (phase == PHASE_IDLE) dimAlpha else baseAlpha
                }
            }
            .drawWithContent {
                drawContent()
                if (phaseState.value == PHASE_ACTIVE) {
                    val raw = ((positionProvider() - startMs).toFloat() / duration).coerceIn(0f, 1f)
                    // Spicy animates the gradient linearly: -20% + 120% * progress.
                    // These stops are the clipped equivalent of its CSS gradient.
                    val edge = raw * (1f + SWEEP_FEATHER)
                    val litEnd = (edge - SWEEP_FEATHER).coerceIn(0f, 1f)
                    val dimStart = edge.coerceIn(0f, 1f)
                    val lit = Color.White.copy(alpha = baseAlpha)
                    val dim = Color.White.copy(alpha = dimAlpha)
                    val brush = Brush.horizontalGradient(
                        0f to lit,
                        litEnd to lit,
                        dimStart to dim,
                        1f to dim,
                        startX = if (rtl) size.width else 0f,
                        endX = if (rtl) 0f else size.width
                    )
                    drawRect(brush = brush, blendMode = BlendMode.DstIn)
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = text,
            style = if (currentPhase == PHASE_ACTIVE) visuals.litTextStyle else visuals.textStyle,
            softWrap = false,
            maxLines = 1
        )
    }
}