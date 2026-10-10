package com.almog.spotifytablet.lyrics.mobile.canvas

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import com.almog.spotifytablet.lyrics.mobile.RenderConfig
import com.almog.spotifytablet.lyrics.mobile.ScrollConfig
import com.almog.spotifytablet.lyrics.mobile.animation.LineAnimState
import com.almog.spotifytablet.lyrics.mobile.animation.LyricsAnimator
import com.almog.spotifytablet.lyrics.mobile.animation.AppleMusicMotion
import com.almog.spotifytablet.lyrics.mobile.models.Line
import com.almog.spotifytablet.lyrics.mobile.models.LyricsType
import com.almog.spotifytablet.lyrics.mobile.models.FooterLine
import com.almog.spotifytablet.lyrics.mobile.models.LyricsFooter
import com.almog.spotifytablet.lyrics.mobile.parser.LetterSynthesizer
import com.almog.spotifytablet.lyrics.mobile.keepsControlsHidden
import com.almog.spotifytablet.lyrics.mobile.translation.TranslationPresentation
import com.almog.spotifytablet.lyrics.mobile.translation.TranslationMode
import com.almog.spotifytablet.lyrics.mobile.romanization.RomanizationMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * The main lyrics display component representing the split architecture.
 * Delegates text measurement to LyricsLayoutCalculator,
 * scroll physics to ScrollManager,
 * and drawing to LyricsRenderer extensions.
 *
 * @param lines The list of lyric lines to display.
 * @param currentTimeMs The current playback time in milliseconds.
 * @param onSeekWord Callback triggered when a user taps a line to seek to its start time.
 */
@Composable
fun LyricsView(
    lines: List<Line>,
    documentId: String,
    footer: LyricsFooter = LyricsFooter(),
    currentTimeMs: () -> Long,
    onSeekWord: (Long) -> Unit,
    modifier: Modifier = Modifier,
    fontSizeScale: Float = 1.0f,
    config: RenderConfig = RenderConfig.FULL,
    lyricsType: LyricsType = LyricsType.Syllable,
    romanize: Boolean = false,
    focusAnchorFraction: Float = 0.25f,
    // When set, the active line's top (not its centre) is kept this far below the view's top,
    // ("Top" scrolling); focusAnchorFraction is then unused.
    activeLineTopPx: Float? = null,
    // Without activeLineTopPx, the active line's centre sits this far above the focus anchor.
    focusLiftPx: Float = 0f,
    // Invoked with the raw frame-nanos at the top of this view's own animation frame, before
    // currentTimeMs() is read. Lets a caller (e.g. the playback clock smoothing in
    // SpicyLyricsPlayer) piggyback on this view's single withFrameNanos loop instead of running
    // a second, independent one — halving the Choreographer callbacks registered per lyrics
    // screen. Optional so other/future callers aren't forced to supply one.
    onFrameTick: ((Long) -> Unit)? = null,
    // Paused, the frame loop rests once nothing moves, looking in a few times a second for a seek.
    isPlaying: Boolean = true,
    /** Shared with the screen: the scroll-to-active button and the pinned credits. */
    viewState: LyricsViewState? = null,
    /** Credits drawn pinned by the screen instead of after the lyrics. */
    pinnedFooter: PinnedFooterMode = PinnedFooterMode.Off,
    /** How far up from the bottom the lyrics are covered (pinned credits); they fade out above it. */
    maskBottomPx: () -> Float = { 0f },
    /** Scales the top and bottom edge fade; under 1 for a window too small for the full one. */
    maskScale: Float = 1f,
    translation: TranslationPresentation? = null,
    romanizationMode: RomanizationMode = RomanizationMode.Replace,
) {
    val textMeasurer = rememberTextMeasurer()
    // What is on screen: lines and their layouts, swapped together once new layouts are measured.
    // Until then the previous lyrics stay up, so a source switch mid-song (a better answer
    // arriving) replaces them in one frame instead of blanking the view while it measures.
    var measuredShown by remember { mutableStateOf<ShownLyrics?>(null) }
    val letterConfig = config.copy(wordMotionBoost = 1f, scroll = ScrollConfig())
    val measuredCache = remember(lines) { HashMap<MeasureKey, MeasuredLyrics>() }
    var measuredWidth by remember { mutableFloatStateOf(0f) }
    val fontKey = LyricsLayoutCalculator.fontKey
    val desiredKey = MeasureKey(letterConfig, measuredWidth, fontSizeScale, romanize, lyricsType, fontKey, translation, romanizationMode)
    val desired = measuredCache[desiredKey]
    val original = measuredCache[desiredKey.copy(translation = null)]
    // Translation variants never keep a previous song or an off-toggle's translated layout up.
    // While a new translation measures, what's up for this song stays up (the machine translation
    // a human one is replacing, or the plain lyrics), so the view never blanks in between.
    val shown = if (translation != null || measuredShown?.translation != null) {
        val current = measuredShown?.takeIf { translation != null && it.documentId == documentId }
        desired?.let { ShownLyrics(documentId, it.lines, it.layouts, lyricsType, footer, translation, it.romanizationLines) }
            ?: current
            ?: original?.let { ShownLyrics(documentId, it.lines, it.layouts, lyricsType, footer, null, it.romanizationLines) }
    } else measuredShown
    val shownId = shown?.documentId
    val lineLayouts = shown?.layouts.orEmpty()
    // The type and credits of what is shown, not of what is still being measured.
    val incomingType = lyricsType
    val incomingFooter = footer
    val lyricsType = shown?.lyricsType ?: incomingType
    val footer = shown?.footer ?: incomingFooter
    val coroutineScope = rememberCoroutineScope()

    val animator = remember(shownId) { LyricsAnimator(coroutineScope, config) }
    LaunchedEffect(config) { animator.config = config }
    val isStatic = lyricsType == LyricsType.Static

    // Keep the latest time provider without recomposing on every position tick: the frame loop
    // invokes it off-composition (inside withFrameNanos), so the changing clock never re-runs this
    // composable's body — only the Canvas redraws when the derived anim state actually changes.
    val currentTimeProvider by rememberUpdatedState(currentTimeMs)
    val linesUpdated by rememberUpdatedState(shown?.lines.orEmpty())
    val romanizationLinesUpdated by rememberUpdatedState(shown?.romanizationLines)
    val lineLayoutsUpdated by rememberUpdatedState(lineLayouts)
    val onFrameTickUpdated by rememberUpdatedState(onFrameTick)
    val isPlayingUpdated by rememberUpdatedState(isPlaying)
    val scrollConfigUpdated by rememberUpdatedState(config.scroll)

    val scrollPolicy = remember(shownId) { ScrollPolicyController() }
    // Read by the drag handler, written by the frame loop.
    val contentHeightForDrag = remember(shownId) { FloatArray(1) }
    val density = LocalDensity.current
    val scrollManager = remember(shownId) { ScrollManager().also { it.reset() } }
    scrollManager.smoothScrolling = config.scroll.smooth
    scrollManager.appleMusic = config.isAppleMusic
    val appleMusicUpdated by rememberUpdatedState(config.isAppleMusic)
    // The Apple Music style's staggered scroll: lines past the active one follow the view late.
    val scrollTrail = remember(shownId) { ScrollTrail() }
    // The frame time the late lines are drawn at while one is still behind; 0 once all have caught up.
    var trailNow by remember(shownId) { mutableLongStateOf(0L) }
    // Wakes a resting frame loop at once (a drag or tap), rather than at its next look.
    val wake = remember(shownId) { Channel<Unit>(Channel.CONFLATED) }
    val context = LocalContext.current
    val haptics by rememberUpdatedState(LocalHapticFeedback.current)

    BoxWithConstraints(modifier = modifier.fillMaxSize().clipToBounds()) {
        val canvasWidth = constraints.maxWidth.toFloat()
        SideEffect { measuredWidth = canvasWidth }
        val canvasHeight = constraints.maxHeight.toFloat()
        // Compact fullscreen keeps the active lyric in the upper portion of the viewport.
        val centerY = activeLineTopPx ?: (ScrollPolicyController.anchorY(canvasHeight, focusAnchorFraction) - focusLiftPx)
        val alignTop = activeLineTopPx != null
        // The frame loop and tap handler outlive a change of anchor (the header shown or hidden
        // mid-song), so they read the latest one; a change also wakes a resting loop to re-anchor.
        val centerYUpdated by rememberUpdatedState(centerY)
        val alignTopUpdated by rememberUpdatedState(alignTop)
        val canvasHeightUpdated by rememberUpdatedState(canvasHeight)
        LaunchedEffect(centerY, alignTop) { wake.trySend(Unit) }
        val footerMetrics = remember(canvasWidth, density.density, fontSizeScale, lyricsType) {
            LyricsLayoutMetrics(canvasWidth, density.density, lyricsType, fontSizeScale)
        }
        val footerSlot = footerMetrics.contentSlot(false, false, false)
        val lineGapUpdated by rememberUpdatedState(footerMetrics.lineGapPx)
        val rowHeightUpdated by rememberUpdatedState(footerMetrics.lineHeightPx(footerMetrics.baseFontSizeSp))
        // Credits are sized from the synced lyric size whatever the lyrics type, so static lyrics
        // (drawn smaller) get the same credits.
        val creditBaseSp = remember(canvasWidth, density.density, fontSizeScale) {
            creditBaseSp(canvasWidth, density.density, fontSizeScale)
        }
        scrollManager.pxPerReferencePx = creditBaseSp * density.density / REFERENCE_LYRIC_SIZE_PX
        // The rows the screen pins are drawn there instead.
        val footerLines = remember(footer, pinnedFooter) { footer.lines().filterNot { pinnedFooter.pins(it.kind) } }
        val footerLayouts = remember(footerLines, creditBaseSp, footerSlot.widthPx, canvasWidth, fontKey) {
            measureFooterRows(footerLines, textMeasurer, creditBaseSp, density.density, footerSlot.widthPx)
        }
        val footerHeight = footerLayouts.sumOf { (it.marginTop + it.height).toDouble() }.toFloat()
        val footerHeightUpdated by rememberUpdatedState(footerHeight)
        val avatars = rememberFooterAvatars(footerLines)
        if (viewState != null) {
            SideEffect { viewState.shownFooter = footer }
            DisposableEffect(viewState) {
                onDispose {
                    viewState.shownFooter = null
                    viewState.activeLineDirection = null
                }
            }
            LaunchedEffect(viewState.scrollToActiveRequests) {
                if (viewState.scrollToActiveRequests == 0) return@LaunchedEffect
                scrollManager.returnToActive()
                wake.trySend(Unit)
            }
        }
        val viewStateUpdated by rememberUpdatedState(viewState)
        val maskBottomUpdated by rememberUpdatedState(maskBottomPx)
        // Recalculate layouts whenever the lyrics, dimensions, or font size change.
        // A newer key cancels a measurement still running, so only the latest one lands.
        LaunchedEffect(lines, letterConfig, canvasWidth, fontSizeScale, romanize, documentId, incomingType, incomingFooter, fontKey, translation, romanizationMode) {
            suspend fun measure(romanized: Boolean, presentation: TranslationPresentation? = translation): MeasuredLyrics {
                val key = MeasureKey(letterConfig, canvasWidth, fontSizeScale, romanized, incomingType, fontKey, presentation, romanizationMode)
                measuredCache[key]?.let { return it }
                return withContext(Dispatchers.Default) {
                    // Per-letter emphasis for held words (mode-dependent thresholds, romanized
                    // display). Syllable mode only; Line/Static never letter-split.
                    val translated = presentation?.displayLines(lines) ?: lines
                    val useRomanized = romanized && romanizationMode == RomanizationMode.Replace
                    val display = if (incomingType == LyricsType.Syllable) LetterSynthesizer.apply(translated, letterConfig, useRomanized) else translated
                    val originals = if (presentation?.mode == TranslationMode.Replace && incomingType == LyricsType.Syllable)
                        LetterSynthesizer.apply(lines, letterConfig, useRomanized) else if (presentation?.mode == TranslationMode.Replace) lines else display
                    MeasuredLyrics(display, if (presentation != null || romanized && romanizationMode == RomanizationMode.UnderLine) LyricsLayoutCalculator.calculatePresentationLayouts(
                        originals, display, presentation,
                        canvasWidth, textMeasurer, density.density, incomingType, fontSizeScale, romanized, letterConfig.isSimple,
                        letterConfig.wideDuetPadding,
                        if (letterConfig.isAppleMusic) AppleMusicMotion.BACKGROUND_SIZE else 0.75f,
                        romanizationMode,
                    ) else LyricsLayoutCalculator.calculateLineLayouts(
                        display, canvasWidth, textMeasurer, density.density, incomingType, fontSizeScale, romanized, letterConfig.isSimple,
                        letterConfig.wideDuetPadding,
                        if (letterConfig.isAppleMusic) AppleMusicMotion.BACKGROUND_SIZE else 0.75f,
                    ), romanizationLines = originals.takeIf {
                        presentation?.mode == TranslationMode.Replace && romanized && romanizationMode == RomanizationMode.UnderLine
                    })
                }.also { measuredCache[key] = it }
            }
            // What's asked for goes up first; the layouts a toggle switches to are measured after,
            // so they're ready without holding this one back.
            val measured = measure(romanize)
            measuredShown = ShownLyrics(documentId, measured.lines, measured.layouts, incomingType, incomingFooter, translation, measured.romanizationLines)
            val romanizable = lines.any { line -> line.words.any { it.romanizedText != null } }
            if (translation != null) measure(romanize, null)
            if (romanizable) measure(!romanize)
            if (translation != null && romanizable) measure(!romanize, null)
        }

        if (lineLayouts.isEmpty()) return@BoxWithConstraints

        var animStates by remember { mutableStateOf<List<LineAnimState>>(emptyList()) }
        var dynamicYOffsets by remember { mutableStateOf(FloatArray(0)) }
        var lastFrameTimeNanos by remember { mutableLongStateOf(0L) }
        
        // The high-frequency animation loop, restarted with each new document's animator and scroll.
        LaunchedEffect(shownId) {
            // Reused per-frame scratch for the dynamic Y offsets: filled every frame but only
            // published to state when its contents actually change, so a paused/idle screen stops
            // invalidating the Canvas (a fresh FloatArray each frame was forcing a redraw via array
            // identity-equality even when nothing moved).
            var dynamicYScratch = FloatArray(0)
            var settledYScratch = FloatArray(0)
            var stillFrames = 0
            var lastLayouts: List<LineLayout>? = null
            while (true) {
                if (stillFrames >= REST_AFTER_STILL_FRAMES) {
                    withTimeoutOrNull(REST_POLL_MS) { wake.receive() }
                }
                withFrameNanos { frameTimeNanos ->
                    val previousStates = animStates
                    val previousOffsets = dynamicYOffsets
                    val previousScroll = scrollManager.animScrollY
                    onFrameTickUpdated?.invoke(frameTimeNanos)

                    val currentLayouts = lineLayoutsUpdated
                    val currentLines = linesUpdated
                    // The same lyrics laid out again (romanized, resized): seen here, on the frame
                    // that first draws the new layouts, so the scroll jumps with them.
                    if (lastLayouts != null && currentLayouts !== lastLayouts) scrollManager.onRelayout()
                    lastLayouts = currentLayouts
                    val currentTime = currentTimeProvider()

                    // Unclamped: springs integrate analytically over any dt.
                    val deltaTime = if (lastFrameTimeNanos == 0L) {
                        0.016f
                    } else {
                        ((frameTimeNanos - lastFrameTimeNanos) / 1_000_000_000f).coerceAtLeast(0f)
                    }
                    lastFrameTimeNanos = frameTimeNanos

                    if (currentLayouts.size == currentLines.size && currentLines.isNotEmpty()) {
                        // 1. Step the animator for visual properties (scale, opacity, glow).
                        animStates = animator.animate(currentLines, currentTime, deltaTime, scrollManager.hideLineBlur, lyricsType, romanizationLinesUpdated)

                        // 1.5 Calculate dynamic Y offsets based on interlude scales.
                        var accumulatedY = 0f
                        if (dynamicYScratch.size != currentLayouts.size) {
                            dynamicYScratch = FloatArray(currentLayouts.size)
                        }
                        val newDynamicYOffsets = dynamicYScratch

                        // Where each line settles once running interlude animations finish; the
                        // scroll aims here so it doesn't chase an opening or closing gap.
                        var settledY = 0f
                        if (settledYScratch.size != currentLayouts.size) {
                            settledYScratch = FloatArray(currentLayouts.size)
                        }
                        // Early Scroll picks its line ahead of the song, so it also has to judge
                        // which interludes are open at that time, or the target moves again when
                        // the dots really open or close.
                        val scrollTime = currentTime + scrollConfigUpdated.leadMs
                        // Where an open interlude's dots sit below its start: the middle of the gap
                        // before it plus its row, i.e. halfway between the lines around it.
                        val interludeCentre = (rowHeightUpdated - lineGapUpdated) / 2f
                        // The room a folded background line gives back: what's left between the line
                        // above and the next one, past the usual gap (background lines sit tight
                        // under their lead, without one of their own).
                        fun slotOf(i: Int): Float {
                            val layout = currentLayouts[i]
                            val next = currentLayouts.getOrNull(i + 1)?.yOffset ?: (layout.yOffset + layout.height + lineGapUpdated)
                            val above = currentLayouts.getOrNull(i - 1)?.let { it.yOffset + it.height + lineGapUpdated } ?: layout.yOffset
                            return (next - above).coerceAtLeast(0f)
                        }
                        for (i in currentLayouts.indices) {
                            val layout = currentLayouts[i]
                            val state = animStates.getOrNull(i)

                            if (layout.isInterlude) {
                                val line = layout.line
                                val open = if (scrollTime >= line.startMs &&
                                    scrollTime <= line.endMs - LyricsAnimator.PRE_HIDDEN_DOT_LINE_MS) 1f else 0f
                                settledYScratch[i] = layout.yOffset + settledY + interludeCentre * open
                                settledY += rowHeightUpdated * open
                            } else {
                                settledYScratch[i] = layout.yOffset + settledY
                                // Folded background vocals (Apple Music style) give up their room.
                                // As far as they've got, not where they're headed: the scroll then
                                // follows the fold as it happens instead of jumping ahead of it.
                                if (layout.isBackground) settledY -= slotOf(i) * (1f - (state?.presence ?: 1f))
                            }

                            if (layout.isInterlude) {
                                // An open interlude is one lyric row; the gap before it is already
                                // there, so it adds none after. The dots sit centred between the two
                                // lines, as far from each as two lines are from each other.
                                val scale = state?.scale?.coerceIn(0f, 1f) ?: 0f
                                newDynamicYOffsets[i] = layout.yOffset + accumulatedY + interludeCentre * scale
                                accumulatedY += rowHeightUpdated * scale
                            } else {
                                val fold = if (layout.isBackground) 1f - (state?.presence ?: 1f) else 0f
                                // Tucked up under their line as they fold.
                                newDynamicYOffsets[i] = layout.yOffset + accumulatedY -
                                    slotOf(i) * fold * AppleMusicMotion.BACKGROUND_FOLDED_SHIFT
                                accumulatedY -= slotOf(i) * fold
                            }
                        }
                        // Publish only when the offsets actually changed (i.e. an interlude is
                        // expanding/collapsing); otherwise the Canvas keeps the last array and
                        // isn't invalidated. copyOf() so the published snapshot is immutable while
                        // the scratch keeps mutating next frame.
                        if (!newDynamicYOffsets.contentEquals(dynamicYOffsets)) {
                            dynamicYOffsets = newDynamicYOffsets.copyOf()
                        }

                        // 2. Resolve the lead/background overlap policy, then anchor
                        // that one line: its centre at the focus point, or its top (compact mode).
                        val decision = scrollPolicy.decide(currentLines, currentTime, leadMs = scrollConfigUpdated.leadMs)
                        // A lead with no words of its own (the line is only background vocals)
                        // has no height; its background vocals stand in for it.
                        val targetIndex = decision.targetIndex?.let { index ->
                            val next = currentLayouts.getOrNull(index + 1)
                            if (currentLayouts[index].line.words.isEmpty() && next != null && next.isBackground &&
                                next.line.groupId == currentLayouts[index].line.groupId) index + 1 else index
                        }
                        var targetY: Float? = targetIndex?.let { index ->
                            // An interlude's offset is already the centre of its dots, in a row
                            // one lyric line tall.
                            val half = when {
                                alignTopUpdated -> if (currentLayouts[index].isInterlude) -interludeCentre else 0f
                                currentLayouts[index].isInterlude -> 0f
                                else -> currentLayouts[index].height / 2f
                            }
                            -(settledYScratch[index] + half)
                        }
                        val targetVisiblePx = targetIndex?.let { index ->
                            val top = centerYUpdated + scrollManager.animScrollY + newDynamicYOffsets[index]
                            val bottom = top + currentLayouts[index].height
                            (minOf(bottom, canvasHeightUpdated) - maxOf(top, 0f)).coerceAtLeast(0f)
                        } ?: Float.POSITIVE_INFINITY

                        // Scrolled away by hand with the line being sung out of sight: which way it lies.
                        viewStateUpdated?.let { shared ->
                            val directionIndex = if (isStatic || !scrollManager.hideLineBlur) null
                                else targetIndex ?: currentLayouts.indices.firstOrNull { i ->
                                    currentLayouts[i].line.startMs > currentTime && !currentLayouts[i].isBackground
                                } ?: currentLayouts.lastIndex
                            val direction = directionIndex?.let { index ->
                                val top = centerYUpdated + scrollManager.animScrollY + newDynamicYOffsets[index]
                                val viewBottom = canvasHeightUpdated - maskBottomUpdated()
                                when {
                                    top + currentLayouts[index].height <= 0f -> ActiveLineDirection.Above
                                    top >= viewBottom -> ActiveLineDirection.Below
                                    else -> null
                                }
                            }
                            if (shared.activeLineDirection != direction) shared.activeLineDirection = direction
                        }

                        // 3. Step the scroll spring and handle user overrides.
                        val lastLayout = currentLayouts.lastOrNull()
                        // Credits scroll into reach too, most visibly when static lyrics are scrolled by hand.
                        val totalContentHeight = (lastLayout?.yOffset ?: 0f) + (lastLayout?.height ?: 0f) + accumulatedY +
                            footerHeightUpdated

                        // Static lyrics have no timing to follow: leave scrolling entirely to the user.
                        if (isStatic) targetY = null
                        contentHeightForDrag[0] = totalContentHeight
                        scrollManager.updateScroll(
                            deltaTime, totalContentHeight, targetIndex.takeIf { targetY != null }, targetY,
                            snap = decision.motion == ScrollMotion.SNAP,
                            targetVisiblePx = targetVisiblePx,
                        )
                        if (appleMusicUpdated) {
                            targetIndex?.let { scrollTrail.reference = it }
                            scrollTrail.record(frameTimeNanos, scrollManager.animScrollY)
                            val behind = !scrollManager.hideLineBlur && scrollTrail.catchingUp(frameTimeNanos)
                            val now = if (behind) frameTimeNanos else 0L
                            if (trailNow != now) trailNow = now
                        } else if (trailNow != 0L) trailNow = 0L
                    }
                    val still = !isPlayingUpdated && !scrollManager.isUserScrolling &&
                        animStates == previousStates && dynamicYOffsets === previousOffsets &&
                        scrollManager.animScrollY == previousScroll && trailNow == 0L
                    stillFrames = if (still) stillFrames + 1 else 0
                }
            }
        }

        // The sole renderer mask: transparent through 16dp, ramping to opaque at 64dp, with a
        // symmetric bottom edge. Pinned credits lift the bottom edge above them: the lyrics fade
        // out over the 48dp just above.
        val maskStops = lyricsMaskStops(canvasHeight, density.density * maskScale)
        val fadeBrush = remember(maskStops) { maskBrush(maskStops) }
        val pinnedFadePx = PINNED_FADE_DP * density.density

        // Where each credit row sits below the last line, in content space (before scrolling).
        // Shared by drawing and tapping so a tap always lands on what was drawn.
        fun footerRowTops(): List<Float> = footerRowTops(
            footerLayouts,
            lineLayouts.lastOrNull()?.let { layout ->
                dynamicYOffsets.getOrElse(lineLayouts.lastIndex) { layout.yOffset } + layout.height
            } ?: 0f,
        )

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    val covered = maskBottomUpdated()
                    val brush = if (covered <= 0f) fadeBrush else maskBrush(
                        maskStops.copy(
                            innerBottom = ((size.height - covered - pinnedFadePx) / size.height).coerceIn(maskStops.innerTop, 1f),
                            outerBottom = ((size.height - covered) / size.height).coerceIn(maskStops.innerTop, 1f),
                        ),
                    )
                    drawRect(brush = brush, blendMode = BlendMode.DstIn)
                }
                .pointerInput(scrollManager) {
                    // Interaction: Dragging.
                    detectDragGestures(
                        onDragStart = { scrollManager.onDragStart(); wake.trySend(Unit) },
                        onDragEnd = { scrollManager.onDragEnd() },
                        onDragCancel = { scrollManager.onDragEnd() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            scrollManager.onDrag(dragAmount.y, contentHeightForDrag[0])
                        }
                    )
                }
                // Tapping a credit leaves the controls as they are.
                .keepsControlsHidden(tapsOnly = true) { position ->
                    footerProfileAt(footerLayouts, footerRowTops(), position.y - (centerYUpdated + scrollManager.animScrollY)) != null
                }
                .pointerInput(isStatic, footerLayouts, shown) {
                    detectTapGestures { tapOffset ->
                        val currentScrollY = scrollManager.animScrollY
                        val adjustedTapY = tapOffset.y - (centerYUpdated + currentScrollY)

                        // A credit with a profile asks the screen to open it.
                        footerProfileAt(footerLayouts, footerRowTops(), adjustedTapY)?.let { line ->
                            viewState?.requestProfile(line)
                            return@detectTapGestures
                        }

                        // Tapping a line seeks to it. Static lyrics are not seekable.
                        if (isStatic) return@detectTapGestures

                        for (i in lineLayouts.indices) {
                            val layout = lineLayouts[i]
                            val layoutDynamicY = dynamicYOffsets.getOrElse(i) { layout.yOffset }
                            if (adjustedTapY >= layoutDynamicY && adjustedTapY <= layoutDynamicY + layout.height) {
                                if (layout.isInterlude || layout.isSongwriter) continue
                                // Folded background vocals sit under the next line: that one takes the tap.
                                if ((animStates.getOrNull(i)?.presence ?: 1f) < 0.5f) continue
                                if (layout.line.words.isNotEmpty()) {
                                    // From the first sung word, which can come after the line's own start.
                                    val start = layout.line.words.first().startMs
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onSeekWord(
                                        if (scrollConfigUpdated.seekFadeCompensation) {
                                            (start - ScrollConfig.SEEK_FADE_COMPENSATION_MS).coerceAtLeast(0L)
                                        } else start
                                    )
                                    scrollManager.onSeek()
                                    wake.trySend(Unit)
                                }
                                return@detectTapGestures
                            }
                        }
                    }
                }
        ) {
            val viewScrollOffset = centerY + scrollManager.animScrollY
            // Where a line is drawn: with the view, or a little behind it while it catches up.
            val trailTime = trailNow
            fun scrollOffsetFor(index: Int) = if (trailTime == 0L) viewScrollOffset
                else centerY + scrollTrail.at(trailTime - scrollTrail.delayNanos(index))

            lineLayouts.forEachIndexed { lineIdx, layout ->
                val scrollOffset = scrollOffsetFor(lineIdx)
                val lineAnim = animStates.getOrNull(lineIdx) ?: return@forEachIndexed
                val dynamicY = dynamicYOffsets.getOrElse(lineIdx) { layout.yOffset }

                // Optimization: Don't draw invisible lines.
                if (lineAnim.opacity <= 0.01f) return@forEachIndexed

                // Optimization: Don't draw lines off-screen.
                val lineScreenY = scrollOffset + dynamicY
                if (lineScreenY < -layout.height * 3 || lineScreenY > canvasHeight + layout.height * 3) {
                    return@forEachIndexed
                }

                val lineStartX = getLineStartX(layout)

                when {
                    layout.isInterlude -> drawInterludeGroup(layout, lineAnim, lineStartX, scrollOffset, dynamicY)
                    layout.line.translationReplaces -> drawTranslationText(layout, lineAnim, lineStartX, scrollOffset, dynamicY, config, lyricsType == LyricsType.Static, replacement = true)
                    lyricsType == LyricsType.Static -> drawStaticLine(layout, lineAnim, lineStartX, scrollOffset, dynamicY)
                    lyricsType == LyricsType.Line -> drawLineModeLine(layout, lineAnim, lineStartX, scrollOffset, dynamicY, config)
                    // Minimal Lyrics Mode shrinks inactive lines (a CSS `scale`: paint only, no reflow)
                    // about their start edge, like line-synced lines.
                    lineAnim.scale != 1f -> withTransform({
                        scale(lineAnim.scale, lineAnim.scale, Offset(
                            if (layout.isRightAligned) lineStartX + layout.totalWidth else lineStartX,
                            dynamicY + scrollOffset + layout.height / 2f,
                        ))
                    }) { drawStandardLine(layout, lineAnim, lineStartX, scrollOffset, dynamicY, config) }
                    else -> drawStandardLine(layout, lineAnim, lineStartX, scrollOffset, dynamicY, config)
                }
                if (!layout.line.translationReplaces && layout.supplements.isNotEmpty()) {
                    drawTranslationText(layout, lineAnim, lineStartX, scrollOffset, dynamicY, config, lyricsType == LyricsType.Static)
                }
            }

            val footerScroll = scrollOffsetFor(lineLayouts.size)
            drawFooterRows(footerLayouts, footerRowTops().map { it + footerScroll }, footerSlot.startPx, avatars)
        }
    }
}

private fun maskBrush(stops: LyricsMaskStops) = Brush.verticalGradient(
    0f to Color.Transparent,
    stops.outerTop to Color.Transparent,
    stops.innerTop to Color.Black,
    stops.innerBottom to Color.Black,
    stops.outerBottom to Color.Transparent,
    1f to Color.Transparent,
)

/** Pinned credits: the lyrics fade out over this much just above them. */
private const val PINNED_FADE_DP = 48f

private data class MeasureKey(
    val config: RenderConfig,
    val widthPx: Float,
    val fontSizeScale: Float,
    val romanize: Boolean,
    val type: LyricsType,
    val fontKey: String,
    val translation: TranslationPresentation? = null,
    val romanizationMode: RomanizationMode = RomanizationMode.Replace,
)

private class MeasuredLyrics(val lines: List<Line>, val layouts: List<LineLayout>, val romanizationLines: List<Line>? = null)

private class ShownLyrics(
    val documentId: String,
    val lines: List<Line>,
    val layouts: List<LineLayout>,
    val lyricsType: LyricsType,
    val footer: LyricsFooter,
    val translation: TranslationPresentation? = null,
    val romanizationLines: List<Line>? = null,
)

/** Paused and this many frames without a change, the frame loop rests. */
private const val REST_AFTER_STILL_FRAMES = 30
/** How often a resting frame loop looks for a change (a seek while paused). */
private const val REST_POLL_MS = 150L

/** The desktop lyric size (its 3.5rem cap), in CSS px. */
private const val REFERENCE_LYRIC_SIZE_PX = 56f

/**
 * Calculates the horizontal starting position of a line based on its alignment and the presence of duets.
 */
private fun getLineStartX(
    layout: LineLayout,
): Float {
    return if (layout.isRightAligned) {
        layout.contentStartX + layout.contentWidth - layout.totalWidth
    } else {
        layout.contentStartX
    }
}
