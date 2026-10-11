package com.almog.spotifytablet.lyrics.mobile

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.almog.spotifytablet.Constants
import com.almog.spotifytablet.lyrics.mobile.romanization.RomanizationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.almog.spotifytablet.lyrics.mobile.translation.TranslationMode
import com.almog.spotifytablet.lyrics.mobile.translation.TranslationPresentation
import com.almog.spotifytablet.lyrics.mobile.translation.TranslationResult
import com.almog.spotifytablet.lyrics.mobile.canvas.LyricsView
import com.almog.spotifytablet.lyrics.mobile.models.Line
import com.almog.spotifytablet.lyrics.mobile.models.LineRole
import com.almog.spotifytablet.lyrics.mobile.models.LyricsType
import com.almog.spotifytablet.lyrics.mobile.models.Word
import com.almog.spotifytablet.lyrics.mobile.models.buildDisplayTimeline
import com.almog.spotifytablet.lyrics.model.LyricLine
import com.almog.spotifytablet.lyrics.model.LyricTrack
import com.almog.spotifytablet.lyrics.model.WordSync
import com.almog.spotifytablet.lyrics.ui.LyricsAttributionBadge
import com.almog.spotifytablet.lyrics.viewmodel.LyricsUiState
import com.almog.spotifytablet.lyrics.web.SpicyLyricsJson

/**
 * Converts this app's [LyricTrack] into the line model of the ported Spicy Lyrics Mobile renderer
 * (https://github.com/spicylyrics/mobile, AGPL-3.0): lead lines followed by their background vocals,
 * with a lead's window stretched over its backgrounds, then interlude lines added for long gaps.
 */
object MobileLyricsAdapter {

    /** [original] are the lines before interludes (index-aligned with translations); [lines] the display timeline. */
    class Result(val lines: List<Line>, val type: LyricsType, val original: List<Line>)

    fun convert(track: LyricTrack, romanize: Boolean = false): Result {
        val syllable = SpicyLyricsJson.isSyllableSynced(track)
        val groups = if (syllable) {
            SpicyLyricsJson.groupBackground(track.lines)
        } else {
            track.lines.filterNot { it.isBackground }.sortedBy { it.startTimeMs }.map { it to emptyList<LyricLine>() }
        }
        val lines = ArrayList<Line>(track.lines.size + 8)
        groups.forEachIndexed { groupId, (lead, backgrounds) ->
            val opposite = lead.agentId == "v2"
            val start = minOf(lead.startTimeMs, backgrounds.minOfOrNull { it.startTimeMs } ?: lead.startTimeMs)
            val end = maxOf(
                lead.endTimeMs, backgrounds.maxOfOrNull { it.endTimeMs } ?: lead.endTimeMs, start + 1
            )
            lines += Line(
                words = wordsOf(lead, syllable),
                startMs = start,
                endMs = end,
                agent = lead.agentId,
                role = LineRole.LEAD,
                groupId = groupId,
                oppositeAligned = opposite
            )
            for (bg in backgrounds) {
                lines += Line(
                    words = wordsOf(bg, syllable),
                    startMs = bg.startTimeMs,
                    endMs = maxOf(bg.endTimeMs, bg.startTimeMs + 1),
                    agent = lead.agentId,
                    role = LineRole.BACKGROUND,
                    groupId = groupId,
                    oppositeAligned = opposite
                )
            }
        }
        val original = if (romanize) withRomanization(lines) else lines
        val timeline = buildDisplayTimeline(original, minimalMode = false)
        return Result(timeline, if (syllable) LyricsType.Syllable else LyricsType.Line, original)
    }

    /** Fills in romanization for words the source did not romanize (Japanese, Chinese, Korean, Cyrillic, Greek). */
    private fun withRomanization(lines: List<Line>): List<Line> {
        val computed = try {
            RomanizationService.romanize(lines.map { line -> line.words.map { it.text } })
        } catch (t: Throwable) {
            android.util.Log.w("MobileLyrics", "romanization failed", t)
            return lines
        }
        return lines.mapIndexed { l, line ->
            line.copy(words = line.words.mapIndexed { w, word ->
                word.copy(romanizedText = word.romanizedText ?: computed.getOrNull(l)?.getOrNull(w))
            })
        }
    }

    private fun wordsOf(line: LyricLine, syllable: Boolean): List<Word> {
        val end = maxOf(line.endTimeMs, line.startTimeMs + 1)
        if (!syllable || line.words.isEmpty()) {
            return listOf(Word(line.rawText.trim(), line.startTimeMs, end))
        }
        val source: List<WordSync> = line.words
        val out = ArrayList<Word>(source.size)
        source.forEachIndexed { i, w ->
            val text = w.text.trim()
            if (text.isEmpty()) return@forEachIndexed
            val glued = i > 0 && !source[i - 1].trailingSpace
            out += Word(
                text, w.startTimeMs, maxOf(w.endTimeMs, w.startTimeMs + 1),
                isPartOfWord = glued && out.isNotEmpty(), romanizedText = w.romanized
            )
        }
        return out.ifEmpty { listOf(Word(line.rawText.trim(), line.startTimeMs, end)) }
    }
}

/**
 * Lyrics drawn by the ported Spicy Lyrics Mobile canvas renderer (pure Compose, no WebView).
 * Playback time is extrapolated from the playback anchor on every frame.
 */
@Composable
fun MobileLyricsContent(
    uiState: LyricsUiState,
    modifier: Modifier = Modifier,
    onLineClicked: ((Long) -> Unit)? = null
) {
    val track = uiState.track
    val anchor = uiState.anchor
    val anchorState by rememberUpdatedState(anchor)
    val onClick by rememberUpdatedState(onLineClicked)

    // Settings (SharedPreferences): romanization on/off.
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE) }
    val romanize = prefs.getBoolean(MobileTranslation.PREF_ROMANIZE, false)
    // Converting (and romanizing, which loads a Japanese dictionary) happens off the main thread.
    val converted by produceState<MobileLyricsAdapter.Result?>(null, track, romanize) {
        value = withContext(Dispatchers.Default) {
            track?.takeIf { it.lines.isNotEmpty() }?.let { MobileLyricsAdapter.convert(it, romanize) }
        }
    }
    // The app's lyrics font size setting (sp, default 32) scales Spicy's own size.
    val fontScale = if (prefs.contains(Constants.PREF_KEY_LYRICS_FONT_SIZE)) {
        prefs.getInt(Constants.PREF_KEY_LYRICS_FONT_SIZE, 32) / 32f
    } else {
        1f
    }
    val documentId = remember(track) { (track?.lines?.hashCode() ?: 0).toString() }

    // Translation target ("" = off) and mode.
    val target = prefs.getString(MobileTranslation.PREF_TRANSLATE_TARGET, "").orEmpty()
    val mode = if (prefs.getString(MobileTranslation.PREF_TRANSLATION_MODE, "under") == "replace") {
        TranslationMode.Replace
    } else {
        TranslationMode.UnderLine
    }
    var translation by remember(converted, target) { mutableStateOf<TranslationResult?>(null) }
    LaunchedEffect(converted, target) {
        val c = converted
        if (c != null && target.isNotBlank()) {
            translation = try {
                MobileTranslation.translate(context, documentId, c.original, target)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                android.util.Log.w("MobileTranslation", "translation failed", t)
                null
            }
        }
    }
    val presentation = remember(converted, translation, mode) {
        val c = converted
        val r = translation
        if (c != null && r != null) TranslationPresentation.forTimeline(c.original, c.lines, r, mode) else null
    }

    Box(modifier = modifier.fillMaxSize()) {
        val shown = converted
        if (shown != null) {
            LyricsView(
                lines = shown.lines,
                documentId = documentId,
                currentTimeMs = {
                    val a = anchorState
                    if (a.isPlaying) {
                        a.positionMs + ((SystemClock.elapsedRealtime() - a.anchorRealtimeMs) * a.speed).toLong()
                    } else {
                        a.positionMs
                    }
                },
                onSeekWord = { ms -> onClick?.invoke(ms) },
                modifier = Modifier.fillMaxSize(),
                lyricsType = shown.type,
                isPlaying = anchor.isPlaying,
                focusAnchorFraction = 0.4f,
                fontSizeScale = fontScale,
                romanize = romanize,
                translation = presentation
            )
        }
        track?.attribution?.let { attr ->
            LyricsAttributionBadge(
                attr = attr,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 28.dp, bottom = 12.dp)
            )
        }
    }
}
