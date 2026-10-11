package com.almog.spotifytablet.lyrics.mobile.canvas

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.almog.spotifytablet.lyrics.mobile.models.FooterLine
import com.almog.spotifytablet.lyrics.mobile.models.LyricsFooter
import com.almog.spotifytablet.lyrics.mobile.models.LyricsType
import com.almog.spotifytablet.lyrics.mobile.keepsControlsHidden
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt

/** Which credits stay on screen, pinned under the lyrics, instead of scrolling after them. */
enum class PinnedFooterMode(val label: String) {
    Off("Off"),
    /** The source and community credits; the writers still scroll. */
    NoWriters("No Writers"),
    Full("Full");

    fun pins(kind: FooterLine.Kind): Boolean = when (this) {
        Off -> false
        NoWriters -> kind != FooterLine.Kind.WRITERS
        Full -> true
    }
}

/**
 * What the lyrics screen and [LyricsView] share: which way the active line lies when it has been
 * scrolled out of view, a way to go back to it, the credits on screen, and a credit's profile
 * waiting to be opened.
 */
class LyricsViewState {
    /** Set while the user has scrolled the active line out of view; null otherwise. */
    var activeLineDirection by mutableStateOf<ActiveLineDirection?>(null)
        internal set

    /** The credits of the lyrics on screen, null while none are shown. */
    var shownFooter by mutableStateOf<LyricsFooter?>(null)
        internal set

    /** A tapped credit whose profile the screen asks about before opening; null otherwise. */
    var profileRequest by mutableStateOf<FooterLine?>(null)
        private set

    internal var scrollToActiveRequests by mutableIntStateOf(0)
        private set

    internal fun requestProfile(line: FooterLine) {
        profileRequest = line
    }

    fun dismissProfile() {
        profileRequest = null
    }

    fun scrollToActive() {
        scrollToActiveRequests++
    }
}

enum class ActiveLineDirection { Above, Below }

/** One credit row, measured. */
internal class FooterRow(
    val line: FooterLine,
    val text: TextLayoutResult,
    val alpha: Float,
    val marginTop: Float,
    val height: Float,
    val avatarSize: Float,
    val avatarGap: Float,
)

/** The synced lyric size L the credits are sized from, whatever the lyrics type. */
internal fun creditBaseSp(canvasWidth: Float, density: Float, fontSizeScale: Float): Float =
    LyricsLayoutMetrics(canvasWidth, density, LyricsType.Syllable, fontSizeScale).baseFontSizeSp

/**
 * Matched to a Spicy Lyrics screenshot, relative to the lyric size L: "Written by" 0.47L,
 * the rest ~0.34L (its Mixed.css), all in the lyrics font; gaps ~0.45L above the block
 * and 0.2-0.3L between rows; the avatar ~1.4x the credit text, right after the name.
 * [CREDIT_SCALE] enlarges it all a little for a phone screen.
 */
internal fun measureFooterRows(
    lines: List<FooterLine>,
    textMeasurer: TextMeasurer,
    creditBaseSp: Float,
    density: Float,
    maxWidthPx: Float,
    firstMarginTop: Boolean = true,
): List<FooterRow> {
    val lyricPx = creditBaseSp * density
    return lines.mapIndexed { index, line ->
        val (size, alpha, margin) = when (line.kind) {
            FooterLine.Kind.WRITERS -> Triple(0.47f, 0.7f, 0.25f)
            FooterLine.Kind.PROVIDER -> Triple(0.34f, 0.55f, 0.25f)
            FooterLine.Kind.NOTE -> Triple(0.35f, 0.65f, 0.3f)
            FooterLine.Kind.CONTRIBUTOR -> Triple(0.34f, 1f, 0.2f)
        }
        val fontSp = creditBaseSp * size * CREDIT_SCALE
        val text = if (line.kind == FooterLine.Kind.CONTRIBUTOR && line.label != null && line.name != null) {
            // "Made by " dimmer, then the bold, underlined "@name" (.song-info-profile-section).
            buildAnnotatedString {
                withStyle(SpanStyle(color = Color.White.copy(alpha = 0.6f))) { append("${line.label} ") }
                withStyle(SpanStyle(
                    color = Color.White.copy(alpha = 0.75f),
                    fontWeight = FontWeight.Bold,
                    textDecoration = TextDecoration.Underline,
                )) { append("@${line.name}") }
            }
        } else AnnotatedString(line.text)
        val avatar = if (line.avatarUrl != null) fontSp * density * 1.4f else 0f
        val layout = textMeasurer.measure(
            text,
            TextStyle(
                fontFamily = LyricsLayoutCalculator.spicyFontFamily,
                fontSize = fontSp.sp,
                fontWeight = if (line.kind == FooterLine.Kind.NOTE) FontWeight.Bold else FontWeight.SemiBold,
            ),
            constraints = Constraints(maxWidth = (maxWidthPx - avatar).roundToInt().coerceAtLeast(1)),
        )
        FooterRow(
            line, layout, alpha,
            marginTop = when {
                index > 0 -> lyricPx * margin
                firstMarginTop -> lyricPx * 0.45f
                else -> 0f
            },
            height = maxOf(layout.size.height.toFloat(), avatar),
            avatarSize = avatar,
            avatarGap = 2f * density,
        )
    }
}

/** Where each row starts, the first one [startY] down. */
internal fun footerRowTops(rows: List<FooterRow>, startY: Float): List<Float> {
    var y = startY
    return rows.map { row -> (y + row.marginTop).also { y = it + row.height } }
}

internal fun DrawScope.drawFooterRows(rows: List<FooterRow>, tops: List<Float>, x: Float, avatars: Map<String, ImageBitmap>) {
    rows.zip(tops).forEach { (row, y) ->
        val textHeight = row.text.size.height.toFloat()
        drawText(
            textLayoutResult = row.text,
            color = Color.White,
            alpha = row.alpha,
            topLeft = Offset(x, y + (row.height - textHeight) / 2f),
        )
        // The avatar follows the name, a 24px circle.
        row.line.avatarUrl?.let { avatars[it] }?.let { avatar ->
            val ax = x + row.text.size.width + row.avatarGap
            val ay = y + (row.height - row.avatarSize) / 2f
            clipPath(Path().apply { addOval(Rect(ax, ay, ax + row.avatarSize, ay + row.avatarSize)) }) {
                drawImage(
                    avatar,
                    dstOffset = IntOffset(ax.roundToInt(), ay.roundToInt()),
                    dstSize = IntSize(row.avatarSize.roundToInt(), row.avatarSize.roundToInt()),
                )
            }
        }
    }
}

/** The row at [y] with a profile to open, if any. */
internal fun footerProfileAt(rows: List<FooterRow>, tops: List<Float>, y: Float): FooterLine? =
    rows.zip(tops).firstOrNull { (row, top) -> row.line.profileUrl != null && y in top..(top + row.height) }
        ?.first?.line

@Composable
internal fun rememberFooterAvatars(lines: List<FooterLine>): Map<String, ImageBitmap> {
    val context = LocalContext.current
    val urls = remember(lines) { lines.mapNotNull(FooterLine::avatarUrl).distinct() }
    val avatars by produceState(emptyMap<String, ImageBitmap>(), urls) {
        value = urls.mapNotNull { url -> loadAvatar(context, url)?.let { url to it } }.toMap()
    }
    return avatars
}

/**
 * The pinned credits: the rows [mode] pins, from the lyrics on screen, drawn as they are after
 * the lyrics but held in place. [onHeight] reports how tall they are (0 with nothing to show).
 * Their links only take taps while [tappable].
 */
@Composable
fun PinnedLyricsFooter(
    state: LyricsViewState,
    mode: PinnedFooterMode,
    fontSizeScale: Float,
    tappable: Boolean,
    onHeight: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val footer = state.shownFooter
    val lines = remember(footer, mode) { footer?.lines().orEmpty().filter { mode.pins(it.kind) } }
    val textMeasurer = rememberTextMeasurer()
    val avatars = rememberFooterAvatars(lines)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val density = LocalDensity.current.density
        val width = constraints.maxWidth.toFloat()
        val slot = LyricsLayoutMetrics(width, density, LyricsType.Syllable, fontSizeScale).contentSlot(false, false, false)
        val baseSp = creditBaseSp(width, density, fontSizeScale)
        val rows = remember(lines, baseSp, slot.widthPx, LyricsLayoutCalculator.fontKey) {
            measureFooterRows(lines, textMeasurer, baseSp, density, slot.widthPx, firstMarginTop = false)
        }
        val tops = remember(rows) { footerRowTops(rows, 0f) }
        val height = rows.sumOf { (it.marginTop + it.height).toDouble() }.toFloat()
        SideEffect { onHeight(height.roundToInt()) }
        if (rows.isEmpty()) return@BoxWithConstraints
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(with(LocalDensity.current) { height.toDp() })
                .then(
                    if (!tappable) Modifier else Modifier
                        .keepsControlsHidden(tapsOnly = true) { footerProfileAt(rows, tops, it.y) != null }
                        .pointerInput(rows) {
                            detectTapGestures { tap -> footerProfileAt(rows, tops, tap.y)?.let(state::requestProfile) }
                        },
                ),
        ) {
            drawFooterRows(rows, tops, slot.startPx, avatars)
        }
    }
}

/** Scales the credits up from their desktop proportions, for a phone screen. */
private const val CREDIT_SCALE = 1.15f

private suspend fun loadAvatar(context: Context, url: String): ImageBitmap? = try {
    // Avatars are not loaded in this app (the original uses Coil); credits draw without them.
    null
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}
