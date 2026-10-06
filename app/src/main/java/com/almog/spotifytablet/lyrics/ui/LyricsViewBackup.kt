package com.almog.spotifytablet.lyrics.ui

import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.almog.spotifytablet.lyrics.model.LyricLine
import com.almog.spotifytablet.lyrics.model.LyricTrack
import com.almog.spotifytablet.lyrics.model.WordSync
import com.almog.spotifytablet.lyrics.viewmodel.LyricsViewModel

/**
 * Spicy Lyrics High-Precision Stage Engine (Backup).
 *
 * Implements:
 * 1. Native word-synced syllable highlight with progressive GPU fill and dynamic curves.
 * 2. High-precision line-synced continuous gradient sweep fallback when songs do not have syllable-level timings.
 * 3. Non-blocking 3-slot stage with physical spring gliding.
 */
@Composable
fun LyricsViewBackup(
    viewModel: LyricsViewModel,
    modifier: Modifier = Modifier,
    onLineClicked: ((Long) -> Unit)? = null
) {
    val lyricTrack by viewModel.lyricTrack.collectAsStateWithLifecycle()
    val activeLineIndex by viewModel.activeLineIndex.collectAsStateWithLifecycle()
    val currentPositionMs by viewModel.currentPositionMs.collectAsStateWithLifecycle()
    val isAnimationEnabled by viewModel.isAnimationEnabled.collectAsStateWithLifecycle()

    LyricsContentBackup(
        track = lyricTrack,
        activeLineIndex = activeLineIndex,
        currentPositionMs = currentPositionMs,
        isAnimationEnabled = isAnimationEnabled,
        modifier = modifier,
        onLineClicked = onLineClicked
    )
}

@Composable
fun LyricsContentBackup(
    track: LyricTrack?,
    activeLineIndex: Int,
    currentPositionMs: Long,
    isAnimationEnabled: Boolean,
    modifier: Modifier = Modifier,
    onLineClicked: ((Long) -> Unit)? = null
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 0.dp),
        contentAlignment = Alignment.Center
    ) {
        if (track == null || track.lines.isEmpty()) {
            return@Box
        }

        // 1. Interlude countdown dots
        val pauseInfo = getPauseInfoBackup(track.lines, activeLineIndex, currentPositionMs)
        val isPauseActive = pauseInfo != null

        val pauseAlpha by animateFloatAsState(
            targetValue = if (isPauseActive) 1f else 0f,
            animationSpec = tween(durationMillis = 350, easing = LinearOutSlowInEasing),
            label = "pauseAlpha"
        )

        if (pauseAlpha > 0.01f && pauseInfo != null) {
            Box(
                modifier = Modifier.graphicsLayer { alpha = pauseAlpha },
                contentAlignment = Alignment.Center
            ) {
                SpicyPauseDotsBackup(
                    currentPositionMs = currentPositionMs,
                    pauseStartMs = pauseInfo.pauseStartMs,
                    nextStartMs = pauseInfo.nextStartMs
                )
            }
        }

        // 2. Ultra-Fluid 3-Slot Kinetic Stage Layout with Organic Physics
        val activeLine = track.lines.getOrNull(activeLineIndex)
        val prevLine = track.lines.getOrNull(activeLineIndex - 1)
        val nextLine = track.lines.getOrNull(activeLineIndex + 1)

        val stageAlpha by animateFloatAsState(
            targetValue = if (isPauseActive) 0.15f else 1f,
            animationSpec = tween(durationMillis = 400, easing = LinearOutSlowInEasing),
            label = "stageAlpha"
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = stageAlpha },
            contentAlignment = Alignment.Center
        ) {
            // A. Previous Line Slot (Gliding Upward with Soft Translucency & Scale)
            if (prevLine != null && activeLineIndex > 0) {
                androidx.compose.animation.AnimatedContent(
                    targetState = activeLineIndex - 1,
                    transitionSpec = {
                        (androidx.compose.animation.fadeIn(animationSpec = tween(400, easing = LinearOutSlowInEasing)) +
                         androidx.compose.animation.slideInVertically(animationSpec = spring(stiffness = 280f, dampingRatio = 0.82f)) { it / 3 })
                        .togetherWith(
                            androidx.compose.animation.fadeOut(animationSpec = tween(300, easing = FastOutSlowInEasing)) +
                            androidx.compose.animation.slideOutVertically(animationSpec = tween(300, easing = FastOutSlowInEasing)) { -it / 3 }
                        )
                    },
                    label = "prevSlotAnimated"
                ) { prevIdx ->
                    val line = track.lines.getOrNull(prevIdx)
                    if (line != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    translationY = -92f * density
                                    alpha = 0.32f
                                    scaleX = 0.88f
                                    scaleY = 0.88f
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            SingleLyricLineRowBackup(
                                line = line,
                                currentPositionMs = currentPositionMs,
                                isAnimationEnabled = false,
                                isActiveLine = false
                            )
                        }
                    }
                }
            }

            // B. Active Line Slot (Hero Centerpiece with Fluid Dynamic Rise)
            if (activeLine != null) {
                androidx.compose.animation.AnimatedContent(
                    targetState = activeLineIndex,
                    transitionSpec = {
                        (androidx.compose.animation.fadeIn(animationSpec = tween(420, easing = LinearOutSlowInEasing)) +
                         androidx.compose.animation.slideInVertically(animationSpec = spring(stiffness = 260f, dampingRatio = 0.80f)) { it / 3 })
                        .togetherWith(
                            androidx.compose.animation.fadeOut(animationSpec = tween(280, easing = FastOutSlowInEasing)) +
                            androidx.compose.animation.slideOutVertically(animationSpec = tween(280, easing = FastOutSlowInEasing)) { -it / 3 }
                        )
                    },
                    label = "heroActiveLine"
                ) { targetIndex ->
                    val lineToRender = track.lines.getOrNull(targetIndex)
                    if (lineToRender != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    scaleX = 1.0f
                                    scaleY = 1.0f
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            SingleLyricLineRowBackup(
                                line = lineToRender,
                                currentPositionMs = currentPositionMs,
                                isAnimationEnabled = isAnimationEnabled,
                                isActiveLine = true
                            )
                        }
                    }
                }
            }

            // C. Next Line Slot (Anticipation Below with Natural Spring Inflow)
            if (nextLine != null) {
                androidx.compose.animation.AnimatedContent(
                    targetState = activeLineIndex + 1,
                    transitionSpec = {
                        (androidx.compose.animation.fadeIn(animationSpec = tween(400, easing = LinearOutSlowInEasing)) +
                         androidx.compose.animation.slideInVertically(animationSpec = spring(stiffness = 280f, dampingRatio = 0.82f)) { it / 3 })
                        .togetherWith(
                            androidx.compose.animation.fadeOut(animationSpec = tween(300, easing = FastOutSlowInEasing)) +
                            androidx.compose.animation.slideOutVertically(animationSpec = tween(300, easing = FastOutSlowInEasing)) { -it / 3 }
                        )
                    },
                    label = "nextSlotAnimated"
                ) { nextIdx ->
                    val line = track.lines.getOrNull(nextIdx)
                    if (line != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    translationY = 92f * density
                                    alpha = 0.32f
                                    scaleX = 0.88f
                                    scaleY = 0.88f
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            SingleLyricLineRowBackup(
                                line = line,
                                currentPositionMs = currentPositionMs,
                                isAnimationEnabled = false,
                                isActiveLine = false
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class PauseInfoBackup(val pauseStartMs: Long, val nextStartMs: Long)

private fun getPauseInfoBackup(lines: List<LyricLine>, activeLineIndex: Int, currentPositionMs: Long): PauseInfoBackup? {
    if (lines.isEmpty()) return null

    // 1. Long intro pause before first line (>= 3.5s)
    if (activeLineIndex == -1 && lines.isNotEmpty()) {
        val firstStart = lines[0].startTimeMs
        if (firstStart >= 3500L && currentPositionMs < firstStart) {
            val pauseStart = maxOf(0L, firstStart - 6000L)
            if (currentPositionMs in pauseStart until firstStart) {
                return PauseInfoBackup(pauseStartMs = pauseStart, nextStartMs = firstStart)
            }
        }
        return null
    }

    // 2. Long interlude gap between activeLine and next line (>= 4.0s gap)
    if (activeLineIndex in 0 until lines.size - 1) {
        val currentLine = lines[activeLineIndex]
        val nextLine = lines[activeLineIndex + 1]
        val gap = nextLine.startTimeMs - currentLine.endTimeMs
        if (gap >= 4000L) {
            val gapStart = currentLine.endTimeMs + 600L
            if (currentPositionMs in gapStart until (nextLine.startTimeMs - 200L)) {
                return PauseInfoBackup(pauseStartMs = gapStart, nextStartMs = nextLine.startTimeMs)
            }
        }
    }

    return null
}

@Composable
fun SpicyPauseDotsBackup(
    currentPositionMs: Long,
    pauseStartMs: Long,
    nextStartMs: Long,
    modifier: Modifier = Modifier
) {
    val totalTime = (nextStartMs - pauseStartMs).coerceAtLeast(1000L)
    val baseDotTime = totalTime / 3

    val dot1End = pauseStartMs + baseDotTime
    val dot2End = pauseStartMs + (baseDotTime * 2)

    val dot1Active = currentPositionMs >= pauseStartMs
    val dot2Active = currentPositionMs >= dot1End
    val dot3Active = currentPositionMs >= dot2End

    val d1Scale by animateFloatAsState(
        targetValue = if (dot1Active) 1.35f else 0.85f,
        animationSpec = spring(stiffness = 380f, dampingRatio = 0.65f),
        label = "dot1Scale"
    )
    val d2Scale by animateFloatAsState(
        targetValue = if (dot2Active) 1.35f else 0.85f,
        animationSpec = spring(stiffness = 380f, dampingRatio = 0.65f),
        label = "dot2Scale"
    )
    val d3Scale by animateFloatAsState(
        targetValue = if (dot3Active) 1.35f else 0.85f,
        animationSpec = spring(stiffness = 380f, dampingRatio = 0.65f),
        label = "dot3Scale"
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(vertical = 14.dp)
    ) {
        PauseDotBackup(isActive = dot1Active, scale = d1Scale)
        PauseDotBackup(isActive = dot2Active, scale = d2Scale)
        PauseDotBackup(isActive = dot3Active, scale = d3Scale)
    }
}

@Composable
private fun PauseDotBackup(
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
fun SingleLyricLineRowBackup(
    line: LyricLine,
    currentPositionMs: Long,
    isAnimationEnabled: Boolean = true,
    isActiveLine: Boolean = true,
    modifier: Modifier = Modifier
) {
    if (isAnimationEnabled && line.words.isNotEmpty()) {
        FlowRow(
            horizontalArrangement = Arrangement.Center,
            verticalArrangement = Arrangement.Center,
            modifier = modifier.fillMaxWidth()
        ) {
            line.words.forEach { word ->
                SpicyWordHighlightTextBackup(
                    word = word,
                    currentPositionMs = currentPositionMs,
                    isActiveLine = isActiveLine
                )
            }
        }
    } else {
        val lineDur = (line.endTimeMs - line.startTimeMs).coerceAtLeast(1L)
        val lineProgress = if (isActiveLine) {
            ((currentPositionMs - line.startTimeMs).toFloat() / lineDur.toFloat()).coerceIn(0f, 1f)
        } else 0f

        val isLineDone = currentPositionMs >= line.endTimeMs

        Box(
            modifier = modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = line.rawText,
                style = TextStyle(
                    fontSize = 34.sp,
                    lineHeight = 46.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif,
                    textAlign = TextAlign.Center,
                    color = if (isLineDone) Color(0xFFF2F2F2) else if (isActiveLine) Color(0x99FFFFFF) else Color(0x44FFFFFF),
                    shadow = Shadow(
                        color = Color(0x99000000),
                        offset = Offset(0f, 2f),
                        blurRadius = 6f
                    ),
                    letterSpacing = (-0.3).sp
                )
            )

            if (isActiveLine && lineProgress > 0f) {
                Box(
                    modifier = Modifier.drawWithCache {
                        onDrawWithContent {
                            clipRect(left = 0f, top = 0f, right = size.width * lineProgress, bottom = size.height) {
                                this@onDrawWithContent.drawContent()
                            }
                        }
                    }
                ) {
                    Text(
                        text = line.rawText,
                        style = TextStyle(
                            fontSize = 34.sp,
                            lineHeight = 46.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.SansSerif,
                            textAlign = TextAlign.Center,
                            color = Color.White,
                            shadow = Shadow(
                                color = Color(0xFF1DB954),
                                offset = Offset(0f, 0f),
                                blurRadius = 22f
                            ),
                            letterSpacing = (-0.3).sp
                        )
                    )
                }
            }
        }
    }
}

private fun calculateDynamicScaleBackup(progress: Float, durationMs: Long): Float {
    val peakScale = if (durationMs < 400L) 1.13f else 1.09f
    return when {
        progress <= 0f -> 0.97f
        progress < 0.55f -> {
            val t = progress / 0.55f
            val ease = 1f - (1f - t) * (1f - t) * (1f - t)
            0.97f + (peakScale - 0.97f) * ease
        }
        progress <= 1.0f -> {
            val t = (progress - 0.55f) / 0.45f
            val ease = t * t * (3f - 2f * t)
            peakScale - (peakScale - 1.0f) * ease
        }
        else -> 1.0f
    }
}

private fun calculateDynamicYOffsetBackup(progress: Float, durationMs: Long): Float {
    val maxLift = if (durationMs < 400L) -3.2f else -2.0f
    return when {
        progress <= 0f -> 0f
        progress < 0.55f -> {
            val t = progress / 0.55f
            val ease = 1f - (1f - t) * (1f - t) * (1f - t)
            maxLift * ease
        }
        progress <= 1.0f -> {
            val t = (progress - 0.55f) / 0.45f
            val ease = t * t * (3f - 2f * t)
            maxLift * (1f - ease)
        }
        else -> 0f
    }
}

@Composable
fun SpicyWordHighlightTextBackup(
    word: WordSync,
    currentPositionMs: Long,
    isActiveLine: Boolean = true,
    modifier: Modifier = Modifier
) {
    val duration = (word.endTimeMs - word.startTimeMs).coerceAtLeast(1L)
    val wordProgress = if (isActiveLine) {
        ((currentPositionMs - word.startTimeMs).toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else 0f

    val isWordActive = wordProgress > 0f && wordProgress < 1f
    val isWordCompleted = wordProgress >= 1f

    val wordScale = if (isWordActive) calculateDynamicScaleBackup(wordProgress, duration) else if (isWordCompleted) 1.0f else 0.97f
    val wordOffsetY = if (isWordActive) calculateDynamicYOffsetBackup(wordProgress, duration) else 0.0f

    val isLetterCapable = duration >= 1000L && word.text.length > 1

    Box(
        modifier = modifier
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .graphicsLayer {
                scaleX = wordScale
                scaleY = wordScale
                translationY = wordOffsetY * density
            },
        contentAlignment = Alignment.CenterStart
    ) {
        if (isLetterCapable && isActiveLine) {
            LetterGroupSweepTextBackup(
                word = word,
                currentPositionMs = currentPositionMs,
                isWordActive = isWordActive,
                isWordCompleted = isWordCompleted
            )
        } else {
            SingleSyllableSweepTextBackup(
                word = word,
                progress = wordProgress,
                isWordActive = isWordActive,
                isWordCompleted = isWordCompleted
            )
        }
    }
}

@Composable
private fun SingleSyllableSweepTextBackup(
    word: WordSync,
    progress: Float,
    isWordActive: Boolean,
    isWordCompleted: Boolean
) {
    val displayString = if (word.trailingSpace) "${word.text} " else word.text

    Text(
        text = displayString,
        style = TextStyle(
            fontSize = 34.sp,
            lineHeight = 46.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.SansSerif,
            color = if (isWordCompleted) Color(0xFFF2F2F2) else Color(0x66FFFFFF),
            shadow = Shadow(
                color = Color(0x99000000),
                offset = Offset(0f, 2f),
                blurRadius = 6f
            ),
            letterSpacing = (-0.3).sp
        )
    )

    if (progress > 0f) {
        val sweepProgress = progress * progress * (3f - 2f * progress)
        Box(
            modifier = Modifier
                .drawWithCache {
                    onDrawWithContent {
                        clipRect(left = 0f, top = 0f, right = size.width * sweepProgress, bottom = size.height) {
                            this@onDrawWithContent.drawContent()
                        }
                    }
                }
        ) {
            Text(
                text = displayString,
                style = TextStyle(
                    fontSize = 34.sp,
                    lineHeight = 46.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.SansSerif,
                    color = Color.White,
                    shadow = Shadow(
                        color = if (isWordActive) Color(0xFF1DB954) else Color(0x99000000),
                        offset = Offset(0f, 0f),
                        blurRadius = if (isWordActive) 24f else 6f
                    ),
                    letterSpacing = (-0.3).sp
                )
            )
        }
    }
}

@Composable
private fun LetterGroupSweepTextBackup(
    word: WordSync,
    currentPositionMs: Long,
    isWordActive: Boolean,
    isWordCompleted: Boolean
) {
    val text = word.text
    val totalDuration = (word.endTimeMs - word.startTimeMs).coerceAtLeast(1L)
    val letterDuration = (totalDuration / text.length).coerceAtLeast(1L)

    Row(verticalAlignment = Alignment.CenterVertically) {
        text.forEachIndexed { index, char ->
            val letterStart = word.startTimeMs + (index * letterDuration)
            val letterEnd = letterStart + letterDuration
            val rawLetterProgress = ((currentPositionMs - letterStart).toFloat() / letterDuration.toFloat()).coerceIn(0f, 1f)
            val letterProgress = rawLetterProgress * rawLetterProgress * (3f - 2f * rawLetterProgress)
            val isLetterActive = rawLetterProgress > 0f && rawLetterProgress < 1f
            val isLetterDone = rawLetterProgress >= 1f

            val letterScale = if (isLetterActive) 1.12f else if (isLetterDone) 1.0f else 0.97f

            Box(
                modifier = Modifier.graphicsLayer {
                    scaleX = letterScale
                    scaleY = letterScale
                },
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = char.toString(),
                    style = TextStyle(
                        fontSize = 34.sp,
                        lineHeight = 46.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif,
                        color = if (isLetterDone || isWordCompleted) Color(0xFFF2F2F2) else Color(0x66FFFFFF),
                        shadow = Shadow(
                            color = Color(0x99000000),
                            offset = Offset(0f, 2f),
                            blurRadius = 6f
                        ),
                        letterSpacing = (-0.2).sp
                    )
                )

                if (rawLetterProgress > 0f) {
                    Box(
                        modifier = Modifier.drawWithCache {
                            onDrawWithContent {
                                clipRect(left = 0f, top = 0f, right = size.width * letterProgress, bottom = size.height) {
                                    this@onDrawWithContent.drawContent()
                                }
                            }
                        }
                    ) {
                        Text(
                            text = char.toString(),
                            style = TextStyle(
                                fontSize = 34.sp,
                                lineHeight = 46.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.SansSerif,
                                color = Color.White,
                                shadow = Shadow(
                                    color = if (isLetterActive) Color(0xFF1DB954) else Color(0x99000000),
                                    offset = Offset(0f, 0f),
                                    blurRadius = if (isLetterActive) 24f else 6f
                                ),
                                letterSpacing = (-0.2).sp
                            )
                        )
                    }
                }
            }
        }

        if (word.trailingSpace) {
            Text(
                text = " ",
                style = TextStyle(fontSize = 34.sp, lineHeight = 46.sp)
            )
        }
    }
}
