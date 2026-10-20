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
import com.almog.spotifytablet.lyrics.mobile.network.data.LyricsBlends
import com.almog.spotifytablet.lyrics.mobile.network.data.LyricsCapability
import com.almog.spotifytablet.lyrics.mobile.network.data.SpicyLyricsKey
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
    @Volatile private var appContext: Context? = null

    @JvmStatic
    fun init(context: Context) {
        appContext = context.applicationContext
        if (backend != null) return
        synchronized(this) {
            if (backend == null) {
                backend = NextLyricsBackend(context.applicationContext, Constants.SPICY_LYRICS_API_KEY)
            }
        }
    }

    class SourceInfo(
        val id: String, val name: String, val enabled: Boolean, val note: String?,
        /** Needs the person's agreement before it is first switched on. */
        val needsConsent: Boolean = false,
        /** What it can return: "Word sync", "Line sync", "Plain text". */
        val types: List<String> = emptyList(),
        /** Sources that only add to another's results (e.g. Apple Music relays); null for a top-level source. */
        val group: String? = null,
    )

    class BlendInfo(val id: String, val name: String, val enabled: Boolean, val usable: Boolean, val needs: String)

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
                    note = com.almog.spotifytablet.lyrics.mobile.network.data.SourceDisclosures.forSource(d)?.description,
                    needsConsent = com.almog.spotifytablet.lyrics.mobile.network.data.SourceDisclosures.forSource(d) != null && !b.isDisclosed(d.id),
                    types = buildList {
                        if (LyricsCapability.WORD_SYNC in d.capabilities) add("Word sync")
                        if (LyricsCapability.LINE_SYNC in d.capabilities) add("Line sync")
                        if (LyricsCapability.PLAIN_TEXT in d.capabilities) add("Plain text")
                    },
                    group = d.upstreamFamily.takeIf { it != d.id && it != "custom" && it != "blend" }
                )
            }
    }

    /** Saves a new rank order (ids of [sources], top first). Ids left out keep their place after the others. */
    @JvmStatic
    fun setOrder(ids: List<String>) {
        val b = backend ?: return
        val policy = b.policy()
        b.setPolicy(ids + policy.sourceOrder.filterNot { it in ids }, policy.disabledSourceIds)
    }

    @JvmStatic
    fun markDisclosed(id: String) { backend?.markDisclosed(id) }

    /** The blends, with whether each is on and whether its donor sources are on too (a blend needs both). */
    @JvmStatic
    fun blends(): List<BlendInfo> {
        val b = backend ?: return emptyList()
        val policy = b.policy()
        val names = b.descriptors.associate { it.id to it.displayName }
        return LyricsBlends.ALL.map { d ->
            BlendInfo(d.id, d.displayName, d.id in policy.enabledBlendIds,
                d.donorIds.all { it !in policy.disabledSourceIds },
                d.donorIds.joinToString(" + ") { names[it] ?: it })
        }
    }

    @JvmStatic
    fun setBlendEnabled(id: String, enabled: Boolean) { backend?.setBlendEnabled(id, enabled) }

    /** Hint of the saved personal key (e.g. "sl_pk_…x7Qa"), or null while the built-in key is used. */
    @JvmStatic
    fun personalKeyHint(): String? = backend?.personalKey?.let(SpicyLyricsKey::hint)

    /** Saves [input] as the personal Spicy Lyrics key; blank goes back to the built-in one. Returns an error text, or null when saved. */
    @JvmStatic
    fun setPersonalKey(input: String): String? {
        val b = backend ?: return "Sources are not ready yet"
        return when (val c = SpicyLyricsKey.check(input)) {
            is SpicyLyricsKey.Check.Ok -> { b.setPersonalKey(c.key); null }
            SpicyLyricsKey.Check.Empty -> { b.setPersonalKey(null); null }
            SpicyLyricsKey.Check.Secret -> "That is a secret key (sl_sk_…). Use the client key that starts with sl_pk_."
            SpicyLyricsKey.Check.Invalid -> "That doesn't look like a client key. It starts with sl_pk_."
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

    /**
     * Settings → Lyrics → Lookup patience: how long the screen waits before showing the best answer so far
     * (soft), how long it waits with nothing found yet (hard), and how long a lookup may run in all (cap).
     */
    private fun patience(): Triple<Long, Long, Long> {
        val mode = appContext?.getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE)
            ?.getString("lyrics_patience", "quick")
        return when (mode) {
            "balanced" -> Triple(20_000L, 30_000L, 45_000L)
            "best" -> Triple(40_000L, 45_000L, 60_000L)
            else -> Triple(8_000L, 20_000L, 45_000L)
        }
    }

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
        val (softBudgetMs, hardBudgetMs, backgroundCapMs) = patience()
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
                com.almog.spotifytablet.DebugLog.i("LyricSources",
                    "${request.title}: using stored pick " +
                        ((cached.resolution as? RemoteLyricsResolution.Found)?.selection?.let { "${it.source.id} (${it.quality})" } ?: "no lyrics") +
                        "; " + cached.resolution.attempts.joinToString(", ") { "${it.sourceId}=${it.outcome}" })
                cached.resolution
            } else {
                // Answers that land stay in [known]; [best] is the pick so far, readable at any moment.
                val known = cached?.answers?.toMutableMap() ?: mutableMapOf()
                val best = AtomicReference<RemoteLyricsResolution?>(cached?.resolution)
                val job = lookupScope.async {
                    val r = withTimeoutOrNull(backgroundCapMs) { backend.resolve(request, known) { best.set(it) } }
                    if (r != null) {
                        backend.store(request, r)
                        com.almog.spotifytablet.DebugLog.i("LyricSources",
                            "${request.title}: " + r.attempts.joinToString(", ") { "${it.sourceId}=${it.outcome}" })
                    }
                    r
                }
                var answered = withTimeoutOrNull(softBudgetMs) { job.await() }
                if (answered == null && best.get() !is RemoteLyricsResolution.Found) {
                    answered = withTimeoutOrNull(hardBudgetMs - softBudgetMs) { job.await() }
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
