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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

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

    class SourceInfo(val id: String, val name: String, val enabled: Boolean, val note: String?)

    /** The lyric sources the app can ask, in rank order, with whether each is on. Empty before [init]. */
    @JvmStatic
    fun sources(): List<SourceInfo> {
        val b = backend ?: return emptyList()
        val policy = b.policy()
        val byId = b.descriptors.associateBy { it.id }
        return policy.sourceOrder.mapNotNull { id -> byId[id] }
            .filterNot { it.rankOnly }
            .map { d ->
                SourceInfo(
                    id = d.id,
                    name = d.displayName,
                    enabled = d.id !in policy.disabledSourceIds,
                    note = com.almog.spotifytablet.lyrics.mobile.network.data.SourceDisclosures.forSource(d)?.description
                )
            }
    }

    /** Switches one source on or off. Picks cached under another set of sources are ignored automatically. */
    @JvmStatic
    fun setSourceEnabled(id: String, enabled: Boolean) {
        val b = backend ?: return
        val policy = b.policy()
        val disabled = if (enabled) policy.disabledSourceIds - id else policy.disabledSourceIds + id
        b.setPolicy(policy.sourceOrder, disabled)
    }

    /**
     * [track] is what was found (null for nothing). [complete] is false when the lookup stopped at its
     * time budget with a not-yet-final pick: show it, but do not cache it as the answer. The lookup
     * then keeps going in the background and reports its final answer through `onSettled`.
     * [definitive] is true when every source answered and none had lyrics, so asking the app's older
     * fallback sources the same questions again would only waste time.
     */
    class Outcome(val track: LyricTrack?, val complete: Boolean, val definitive: Boolean)

    /** Shows the best answer so far once this long has passed, if any source has found lyrics. */
    private const val SOFT_BUDGET_MS = 3_500L
    /** With nothing found yet, waits this long in all before showing "no lyrics". */
    private const val HARD_BUDGET_MS = 9_000L
    /** A lookup that outlives the screen's wait (slow word-synced source) is given this long to finish. */
    private const val BACKGROUND_CAP_MS = 20_000L

    /** Lookups run here, not in the caller, so a slow better source can finish after the first answer is shown. */
    private val lookupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun fetch(
        artist: String,
        title: String,
        album: String,
        durationMs: Int,
        spotifyTrackId: String?
    ): LyricTrack? = fetchOutcome(artist, title, album, durationMs, spotifyTrackId).track

    /**
     * @param onSettled called later, from a background thread, with the lookup's final lyrics when the
     * outcome returned was not final (`complete == false`). `isUpgrade` says whether they differ from
     * what was returned, e.g. word-synced lyrics arriving after line-synced ones were shown.
     */
    suspend fun fetchOutcome(
        artist: String,
        title: String,
        album: String,
        durationMs: Int,
        spotifyTrackId: String?,
        onSettled: ((track: LyricTrack, isUpgrade: Boolean) -> Unit)? = null
    ): Outcome {
        val backend = backend ?: return Outcome(null, complete = true, definitive = false)
        val request = LyricsLookupRequest(
            artist = artist,
            title = title,
            album = album,
            durationSeconds = if (durationMs > 0) durationMs / 1000 else 0,
            spotifyTrackId = spotifyTrackId?.takeIf { it.isNotBlank() }
        )
        return try {
            val cached = backend.cachedResolution(request)
            var complete = true
            val resolution: RemoteLyricsResolution = if (cached != null && cached.settled) {
                cached.resolution
            } else {
                // Answers that land stay in [known]; [best] is the pick so far, readable at any moment.
                val known = cached?.answers?.toMutableMap() ?: mutableMapOf()
                val best = AtomicReference<RemoteLyricsResolution?>(cached?.resolution)
                val job = lookupScope.async {
                    val r = withTimeoutOrNull(BACKGROUND_CAP_MS) { backend.resolve(request, known) { best.set(it) } }
                    if (r != null) {
                        backend.store(request, r)
                        com.almog.spotifytablet.DebugLog.i("LyricSources",
                            "${request.title}: " + r.attempts.joinToString(", ") { "${it.sourceId}=${it.outcome}" })
                    }
                    r
                }
                var answered = withTimeoutOrNull(SOFT_BUDGET_MS) { job.await() }
                if (answered == null && best.get() !is RemoteLyricsResolution.Found) {
                    answered = withTimeoutOrNull(HARD_BUDGET_MS - SOFT_BUDGET_MS) { job.await() }
                }
                if (answered != null) {
                    answered
                } else {
                    // Show what there is; a better source (word timing) may still answer.
                    complete = false
                    val shown = best.get()
                    val shownSource = (shown as? RemoteLyricsResolution.Found)?.selection?.source?.id
                    if (onSettled != null) {
                        lookupScope.launch {
                            val done = job.await() as? RemoteLyricsResolution.Found ?: return@launch
                            val track: LyricTrack? = try {
                                toTrack(RemoteLyricsAdapter.render(done.selection, durationMs.toLong()))
                            } catch (c: CancellationException) {
                                throw c
                            } catch (t: Throwable) {
                                null
                            }
                            if (track != null) onSettled(track, done.selection.source.id != shownSource)
                        }
                    }
                    shown ?: return Outcome(null, complete = false, definitive = false)
                }
            }
            val found = resolution as? RemoteLyricsResolution.Found
                ?: return Outcome(null, complete = complete, definitive = complete && resolution is RemoteLyricsResolution.NotFound)
            val ready = RemoteLyricsAdapter.render(found.selection, durationMs.toLong())
            Outcome(toTrack(ready), complete, definitive = false)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            android.util.Log.w("MobileLyricsSources", "lookup failed", t)
            Outcome(null, complete = false, definitive = false)
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
