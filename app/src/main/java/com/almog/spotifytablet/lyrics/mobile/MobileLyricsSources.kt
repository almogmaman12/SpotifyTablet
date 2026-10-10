package com.almog.spotifytablet.lyrics.mobile

import android.content.Context
import com.almog.spotifytablet.Constants
import com.almog.spotifytablet.lyrics.mobile.core.LyricsState
import com.almog.spotifytablet.lyrics.mobile.core.NextLyricsBackend
import com.almog.spotifytablet.lyrics.mobile.core.RemoteLyricsAdapter
import com.almog.spotifytablet.lyrics.mobile.models.LineRole
import com.almog.spotifytablet.lyrics.mobile.models.LyricsType
import com.almog.spotifytablet.lyrics.mobile.network.data.LyricsLookupRequest
import com.almog.spotifytablet.lyrics.mobile.network.data.RemoteLyricsResolution
import com.almog.spotifytablet.lyrics.model.LyricAttribution
import com.almog.spotifytablet.lyrics.model.LyricContributor
import com.almog.spotifytablet.lyrics.model.LyricLine
import com.almog.spotifytablet.lyrics.model.LyricTrack
import com.almog.spotifytablet.lyrics.model.WordSync
import kotlin.coroutines.cancellation.CancellationException

/**
 * Lyrics sources of Spicy Lyrics Mobile (Spicy Lyrics, LRCLIB, AMLL TTML DB, Unison, Apple, NetEase,
 * lrc.red, ...) behind one call that returns this app's [LyricTrack]. Returns null when nothing is
 * found (or the sources are not initialised), so the caller can fall back to its own sources.
 */
object MobileLyricsSources {
    @Volatile private var backend: NextLyricsBackend? = null

    @JvmStatic
    fun init(context: Context) {
        if (backend != null) return
        synchronized(this) {
            if (backend == null) {
                backend = NextLyricsBackend(context.applicationContext, Constants.SPICY_LYRICS_API_KEY)
            }
        }
    }

    suspend fun fetch(
        artist: String,
        title: String,
        album: String,
        durationMs: Int,
        spotifyTrackId: String?
    ): LyricTrack? {
        val backend = backend ?: return null
        val request = LyricsLookupRequest(
            artist = artist,
            title = title,
            album = album,
            durationSeconds = if (durationMs > 0) durationMs / 1000 else 0,
            spotifyTrackId = spotifyTrackId?.takeIf { it.isNotBlank() }
        )
        return try {
            val cached = backend.cachedResolution(request)
            val resolution = if (cached != null && cached.settled) {
                cached.resolution
            } else {
                backend.resolve(request, cached?.answers?.toMutableMap() ?: mutableMapOf()).also { backend.store(request, it) }
            }
            val found = resolution as? RemoteLyricsResolution.Found ?: return null
            val ready = RemoteLyricsAdapter.render(found.selection, durationMs.toLong())
            toTrack(ready)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            android.util.Log.w("MobileLyricsSources", "lookup failed", t)
            null
        }
    }

    private fun toTrack(ready: LyricsState.Ready): LyricTrack? {
        if (ready.lyricsType == LyricsType.Static) return null
        val syllable = ready.lyricsType == LyricsType.Syllable
        val lines = ready.lines.map { line ->
            val words = line.words
            val text = words.joinToString("") { (if (it.attached) "" else " ") + it.text }.trim()
            LyricLine(
                startTimeMs = line.startMs,
                endTimeMs = line.endMs,
                words = if (syllable) {
                    words.mapIndexed { i, w ->
                        val next = words.getOrNull(i + 1)
                        WordSync(
                            text = w.text,
                            startTimeMs = w.startMs,
                            endTimeMs = w.endMs,
                            trailingSpace = next != null && !next.attached,
                            romanized = w.romanized
                        )
                    }
                } else emptyList(),
                rawText = text,
                isBackground = line.role == LineRole.BACKGROUND,
                agentId = if (line.oppositeAligned) "v2" else "v1"
            )
        }
        if (lines.isEmpty()) return null
        return LyricTrack(
            isWordSynced = syllable,
            lines = lines,
            source = "Spicy Lyrics Mobile: ${ready.source ?: ready.provider}",
            attribution = LyricAttribution(
                provider = ready.provider,
                uploader = ready.uploader?.let { LyricContributor(it.username, it.profileUrl) },
                maker = ready.maker?.let { LyricContributor(it.username, it.profileUrl) }
            )
        )
    }
}
