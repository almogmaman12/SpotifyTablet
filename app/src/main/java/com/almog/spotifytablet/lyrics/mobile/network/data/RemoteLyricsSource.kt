package com.almog.spotifytablet.lyrics.mobile.network.data

import com.almog.spotifytablet.lyrics.mobile.network.model.NetworkErrorException
import com.almog.spotifytablet.lyrics.mobile.network.model.NotFoundException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

enum class ProviderAttemptOutcome {
    HIT,
    MISS,
    NEEDS_MATCH,
    DISABLED,
    COOLING_DOWN,
    QUEUED,
    UNAVAILABLE,
    MALFORMED_HIT,
    /** Still being asked. */
    PENDING,
    /** Never asked or cancelled: a source ranked above it already answered word-synced. */
    SKIPPED,
}

data class ProviderAttempt(
    val sourceId: String,
    val outcome: ProviderAttemptOutcome,
    val quality: RemoteLyricsQuality = RemoteLyricsQuality.NONE,
    val retryAt: Instant? = null,
    val failureCategory: ProviderFailureCategory? = null,
    val message: String? = null,
)

data class RemoteLyricsSelection(
    val source: LyricsSourceDescriptor,
    val payload: RemoteLyricsPayload,
    val quality: RemoteLyricsQuality,
)

sealed interface RemoteLyricsResolution {
    val attempts: List<ProviderAttempt>

    data class Found(
        val selection: RemoteLyricsSelection,
        override val attempts: List<ProviderAttempt>,
    ) : RemoteLyricsResolution

    data class NotFound(
        override val attempts: List<ProviderAttempt>,
    ) : RemoteLyricsResolution

    data class Unavailable(
        override val attempts: List<ProviderAttempt>,
        val earliestRetryAt: Instant? = null,
    ) : RemoteLyricsResolution
}

/**
 * Applies source policy and selects the best usable payload: better timing wins, and between
 * equal timing the user's order wins, never whichever server answered first. TTML is parsed
 * before it can win, so malformed or empty XML never terminates the chain.
 *
 * Fetching walks the ranking: the top-ranked source leads alone, since one request
 * usually settles the song; the rest fan out in parallel if it misses or is slow. The walk stops
 * once a word-synced answer has nothing ranked above it still out.
 */
@Singleton
class RemoteLyricsSource @Inject constructor(
    providers: Set<@JvmSuppressWildcards RemoteLyricsProvider>,
    private val cooldowns: ProviderCooldownTracker,
    private val diagnostics: LyricsDiagnostics = LyricsDiagnostics(),
) {
    private val providers = providers.toList()

    /**
     * [known] holds results from earlier lookups of the same request and receives new ones, so a
     * caller that keeps it (per track) never asks a source twice, e.g. after a policy change.
     * [onUpdate] gets the best-so-far resolution each time a source lands.
     */
    suspend fun resolveLyrics(
        request: LyricsLookupRequest,
        policy: RemoteLyricsPolicy = RemoteLyricsPolicy(),
        now: Instant = Instant.now(),
        known: MutableMap<String, ProviderResult> = mutableMapOf(),
        onUpdate: suspend (RemoteLyricsResolution) -> Unit = {},
    ): RemoteLyricsResolution = coroutineScope {
        val ordered = orderedProviders(policy)
        val enabled = ordered.filter { isEnabled(it.descriptor, policy) && !it.descriptor.rankOnly }
        val cooling = enabled.mapNotNull { provider ->
            val id = provider.descriptor.id
            if (id in known) null else cooldowns.retryAt(id, now)?.let { id to it }
        }.toMap()
        val toAsk = enabled.filter { it.descriptor.id !in known && it.descriptor.id !in cooling }
        val qualities = mutableMapOf<String, RemoteLyricsQuality>()
        val pending = mutableSetOf<String>()
        val landed = Channel<Pair<String, Result<ProviderResult>>>(Channel.UNLIMITED)
        val jobs = mutableMapOf<String, Job>()

        // Blends rank among the providers but are built here, from what the providers answered.
        // Their results stay out of [known]: they depend on the order, which the caller may change.
        val blends = LyricsBlends.live(enabled.map { it.descriptor.id }.toSet(), policy.enabledBlendIds)
        val ranked = LyricsBlends.chain(ordered.map { it.descriptor.id }, blends).map { id ->
            ordered.firstOrNull { it.descriptor.id == id }?.descriptor ?: LyricsBlends.byId(id)!!.descriptor
        }
        val blendResults = mutableMapOf<String, ProviderResult>()
        val waiting = blends.map { it.id }.toMutableSet()

        fun ask(provider: RemoteLyricsProvider) {
            val id = provider.descriptor.id
            pending += id
            jobs[id] = launch {
                // Carried as a Result so a provider's own CancellationException reaches the caller.
                val result = try {
                    Result.success(fetch(provider, request))
                } catch (cancelled: CancellationException) {
                    Result.failure(cancelled)
                }
                landed.trySend(id to result)
            }
        }
        fun quality(id: String) = (known[id] as? ProviderResult.Hit)?.let { hit ->
            qualities.getOrPut(id) { hit.payload.measuredQuality() }
        } ?: RemoteLyricsQuality.NONE
        fun answered(id: String) = id in known || id in cooling
        // Relayed lyrics rank where they came from: Spicy Lyrics' Apple Music copy with Apple
        // Music, RMM Revival's copy of a Spicy Lyrics sync with Spicy Lyrics, LRCMux's KuGou copy with Kugou.
        fun byOrigin() = rankByOrigin(ranked, known, policy.disabledSourceIds)
        fun startBlends() {
            for (blend in blends) {
                if (blend.id !in waiting) continue
                val order = byOrigin()
                val above = order.take(order.indexOfFirst { it.id == blend.id })
                    .filter { source -> enabled.any { it.descriptor == source } }
                // Word timing from a source ranked above is what the blend was for (`_outdone`).
                if (above.any { quality(it.id) == RemoteLyricsQuality.WORD_SYNCED }) { waiting -= blend.id; continue }
                if (!above.all { answered(it.id) } || !blend.donorIds.all(::answered)) continue
                val usable = above.filter { quality(it.id) != RemoteLyricsQuality.NONE }
                val fallback = enabled.firstOrNull { it.descriptor.id == LRCLIB_ID && it.descriptor !in above }
                if (usable.isEmpty() && fallback != null && !answered(LRCLIB_ID)) continue
                waiting -= blend.id
                pending += blend.id
                // Read here, on the walk's own thread; the build runs on another.
                val hits = { id: String -> (known[id] as? ProviderResult.Hit)?.payload }
                val bases = usable.map { it to hits(it.id)!! }
                val fallbackBase = fallback?.takeIf { usable.isEmpty() }?.let { lr -> hits(LRCLIB_ID)?.let { lr.descriptor to it } }
                val donors = blend.donorIds.associateWith(hits)
                val names = ranked.associate { it.id to it.displayName }
                jobs[blend.id] = launch(Dispatchers.Default) {
                    val result = try {
                        LyricsBlends.build(blend, request, bases, fallbackBase, donors) { id -> names[id] ?: id }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        ProviderResult.Unavailable(ProviderFailureCategory.UNKNOWN, "Blend failed: ${error.message}")
                    }
                    landed.trySend(blend.id to Result.success(result))
                }
            }
        }
        fun current() = select(byOrigin(), policy, known + blendResults, pending + waiting, cooling, qualities)

        var resolution = current()
        if (known.isNotEmpty()) onUpdate(resolution)
        toAsk.firstOrNull()?.let(::ask)
        var fannedOut = toAsk.size <= 1
        startBlends()
        // Not asked yet counts as still out: a relayed answer can land below sources the lead skipped.
        fun outstanding() = pending + waiting + toAsk.map { it.descriptor.id }.filter { it !in known }
        // A relay still out may yet bring a Spicy Lyrics sync, which ranks in that place, while
        // Spicy Lyrics itself has no real answer (e.g. no Spotify match; RMM Revival asks by Apple ID).
        // lrc.red still out may yet bring Apple Music's lyrics, LRCMux its best switched-on upstream's.
        val lrcMuxBest = LRCMUX_ORIGINS.entries
            .filter { (_, id) -> id !in policy.disabledSourceIds && ranked.any { it.id == id } }
            .minByOrNull { (_, id) -> ranked.indexOfFirst { it.id == id } }
            ?.let { (origin, _) -> ProviderResult.Hit(RemoteLyricsPayload(attribution = LyricsAttribution("", originName = origin))) }
        fun bestCase(): List<LyricsSourceDescriptor> {
            val out = outstanding()
            val standIns = buildMap {
                if (known[SPICY_ID].let { it !is ProviderResult.Hit && it != ProviderResult.Miss }) {
                    (out intersect SPICY_RELAY_IDS).forEach { put(it, SPICY_SYNC) }
                }
                if (LRC_RED_ID in out) put(LRC_RED_ID, APPLE_LYRICS)
                if (LRCMUX_ID in out && lrcMuxBest != null) put(LRCMUX_ID, lrcMuxBest)
            }
            return rankByOrigin(ranked, known + standIns, policy.disabledSourceIds)
        }
        while (!settled(resolution, bestCase(), outstanding())) {
            if (!fannedOut && pending.isEmpty()) {
                // The lead answered without settling the song: ask everyone else.
                toAsk.drop(1).forEach(::ask)
                fannedOut = true
                startBlends()
            }
            if (pending.isEmpty()) break
            val next = if (fannedOut) landed.receive() else landed.receiveWithin(LEAD_HOLD_MS)
            if (next == null) {
                // ponytail: fixed hold for a slow lead; adapt per source if its latency varies a lot
                toAsk.drop(1).forEach(::ask)
                fannedOut = true
                continue
            }
            val (id, outcome) = next
            val result = outcome.getOrThrow()
            pending -= id
            if (blends.any { it.id == id }) {
                blendResults[id] = result
            } else {
                if (result is ProviderResult.CoolingDown) cooldowns.record(id, result.retryAt, result.reason)
                known[id] = result
            }
            startBlends()
            resolution = current()
            onUpdate(resolution)
        }
        pending.toList().forEach { id -> jobs[id]?.cancel(); pending -= id }
        waiting.clear()
        current()
    }

    /** Whether nothing still out could replace the current pick: word timing is the ceiling. */
    private fun settled(
        resolution: RemoteLyricsResolution,
        ranked: List<LyricsSourceDescriptor>,
        pending: Set<String>,
    ): Boolean {
        val best = (resolution as? RemoteLyricsResolution.Found)?.selection ?: return false
        if (best.quality != RemoteLyricsQuality.WORD_SYNCED) return false
        val rank = ranked.indexOfFirst { it.id == best.source.id }
        return ranked.take(rank).none { it.id in pending }
    }

    private fun select(
        ranked: List<LyricsSourceDescriptor>,
        policy: RemoteLyricsPolicy,
        known: Map<String, ProviderResult>,
        pending: Set<String>,
        cooling: Map<String, Instant>,
        qualities: MutableMap<String, RemoteLyricsQuality>,
    ): RemoteLyricsResolution {
        val attempts = mutableListOf<ProviderAttempt>()
        var best: RemoteLyricsSelection? = null
        var hadUnavailableProvider = false
        var earliestRetryAt: Instant? = null

        for (source in ranked) {
            if (source.rankOnly) continue
            // A blend is in the ranking only while it is live.
            if (source.upstreamFamily != BLEND_FAMILY && !isEnabled(source, policy)) {
                attempts += ProviderAttempt(source.id, ProviderAttemptOutcome.DISABLED)
                continue
            }
            val result = known[source.id]
            if (result == null) {
                val retryAt = cooling[source.id]
                attempts += when {
                    source.id in pending -> ProviderAttempt(source.id, ProviderAttemptOutcome.PENDING)
                    retryAt != null -> {
                        hadUnavailableProvider = true
                        earliestRetryAt = earliest(earliestRetryAt, retryAt)
                        ProviderAttempt(source.id, ProviderAttemptOutcome.COOLING_DOWN, retryAt = retryAt, message = cooldowns.reason(source.id))
                    }
                    else -> ProviderAttempt(source.id, ProviderAttemptOutcome.SKIPPED)
                }
                continue
            }

            when (result) {
                is ProviderResult.Hit -> {
                    val quality = qualities.getOrPut(source.id) { result.payload.measuredQuality() }
                    if (quality == RemoteLyricsQuality.NONE) {
                        attempts += ProviderAttempt(
                            source.id,
                            ProviderAttemptOutcome.MALFORMED_HIT,
                            failureCategory = ProviderFailureCategory.MALFORMED_RESPONSE,
                        )
                        hadUnavailableProvider = true
                        continue
                    }
                    attempts += ProviderAttempt(source.id, ProviderAttemptOutcome.HIT, quality)
                    if (best == null || quality.rank > best.quality.rank) {
                        best = RemoteLyricsSelection(source, result.payload, quality)
                    }
                }

                ProviderResult.Miss ->
                    attempts += ProviderAttempt(source.id, ProviderAttemptOutcome.MISS)

                ProviderResult.NeedsMatch ->
                    attempts += ProviderAttempt(source.id, ProviderAttemptOutcome.NEEDS_MATCH)

                is ProviderResult.CoolingDown -> {
                    attempts += ProviderAttempt(
                        source.id,
                        ProviderAttemptOutcome.COOLING_DOWN,
                        retryAt = result.retryAt,
                        message = result.reason,
                    )
                    earliestRetryAt = earliest(earliestRetryAt, result.retryAt)
                    hadUnavailableProvider = true
                }

                is ProviderResult.Queued -> {
                    attempts += ProviderAttempt(
                        source.id,
                        ProviderAttemptOutcome.QUEUED,
                        retryAt = result.retryAt,
                    )
                    result.retryAt?.let { earliestRetryAt = earliest(earliestRetryAt, it) }
                    hadUnavailableProvider = true
                }

                is ProviderResult.Unavailable -> {
                    attempts += ProviderAttempt(
                        source.id,
                        ProviderAttemptOutcome.UNAVAILABLE,
                        failureCategory = result.category,
                        message = result.message,
                    )
                    hadUnavailableProvider = true
                }
            }
        }

        best?.let { return RemoteLyricsResolution.Found(it, attempts) }
        return if (hadUnavailableProvider || pending.isNotEmpty()) {
            RemoteLyricsResolution.Unavailable(attempts, earliestRetryAt)
        } else {
            RemoteLyricsResolution.NotFound(attempts)
        }
    }

    private suspend fun fetch(provider: RemoteLyricsProvider, request: LyricsLookupRequest): ProviderResult = try {
        // A note saying the song has no words ("纯音乐，请欣赏") is no answer: other sources may have them.
        // Synced text without real timing is plain text, and ranks as such.
        provider.fetch(request).let { result ->
            val hit = (result as? ProviderResult.Hit)?.let { it.copy(payload = it.payload.withoutFakeTiming()) } ?: return@let result
            if (hit.payload.isEmpty() || hit.payload.isNoWordsNote()) ProviderResult.Miss else hit
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: NotFoundException) {
        ProviderResult.Miss
    } catch (error: NetworkErrorException) {
        ProviderResult.Unavailable(
            category = ProviderFailureCategory.NETWORK,
            message = error.message,
            retryable = true,
        )
    } catch (error: Exception) {
        ProviderResult.Unavailable(
            category = ProviderFailureCategory.UNKNOWN,
            message = error.message,
        )
    }

    private fun isEnabled(source: LyricsSourceDescriptor, policy: RemoteLyricsPolicy) =
        source.id !in policy.disabledSourceIds && (policy.sourceOrder.isNotEmpty() || source.defaultEnabled)


    /** Compatibility boundary for the existing repository while it migrates to typed outcomes. */
    suspend fun getLyrics(
        request: LyricsLookupRequest,
        policy: RemoteLyricsPolicy = RemoteLyricsPolicy(),
    ): RemoteLyricsPayload {
        val started = System.nanoTime()
        val resolution = resolveLyrics(request, policy)
        diagnostics.record(LyricsLookupDiagnostic(
            title = request.title,
            recordedAt = Instant.now(),
            durationMs = (System.nanoTime() - started) / 1_000_000L,
            outcome = when (resolution) {
                is RemoteLyricsResolution.Found -> "Found: ${resolution.selection.source.id}"
                is RemoteLyricsResolution.NotFound -> "Not found"
                is RemoteLyricsResolution.Unavailable -> "Temporarily unavailable"
            },
            attempts = resolution.attempts,
        ))
        return when (resolution) {
        is RemoteLyricsResolution.Found -> resolution.selection.payload.copy(
            sourceId = resolution.selection.source.id,
            attribution = resolution.selection.payload.attribution ?: LyricsAttribution(
                providerName = resolution.selection.source.displayName,
            ),
        )
        is RemoteLyricsResolution.NotFound -> throw LyricsNotFoundException(resolution.attempts)
        is RemoteLyricsResolution.Unavailable -> throw LyricsSourcesUnavailableException(resolution.attempts, resolution.earliestRetryAt)
        }
    }

    class LyricsNotFoundException(val attempts: List<ProviderAttempt>) : NotFoundException("Lyrics not found")
    class LyricsSourcesUnavailableException(
        val attempts: List<ProviderAttempt>,
        val retryAt: Instant?,
    ) : NetworkErrorException("Lyrics sources are temporarily unavailable")

    private fun orderedProviders(policy: RemoteLyricsPolicy): List<RemoteLyricsProvider> {
        val explicitOrder = policy.sourceOrder
            .withIndex()
            .associate { (index, id) -> id to index }
        return providers.sortedWith(
            compareBy<RemoteLyricsProvider> {
                explicitOrder[it.descriptor.id] ?: Int.MAX_VALUE
            }.thenBy { it.descriptor.defaultPriority }
                .thenBy { it.descriptor.id }
        )
    }

    internal companion object {
        /**
         * [ranked] with the relays' answers ([RELAY_IDS]) moved to where their lyrics came from:
         * Spicy Lyrics syncs rank in Spicy Lyrics' place, Apple Music lyrics in the Apple Music
         * slot's, Spotify's in the Spotify slot's; after every source while that
         * place is in [disabled]. Any other relayed
         * catalogue after every source. LRCMux's answers rank in the place of the source it found
         * them at ([LRCMUX_ORIGINS]), or its own for one it doesn't name. Answers from one place
         * keep the user's order between them. A relay with no answer yet, or no origin, keeps its
         * own place.
         */
        fun rankByOrigin(
            ranked: List<LyricsSourceDescriptor>,
            known: Map<String, ProviderResult>,
            disabled: Set<String> = emptySet(),
        ): List<LyricsSourceDescriptor> {
            val own = ranked.withIndex().associate { (index, source) -> source.id to index }
            fun slot(id: String) = own[id]?.takeIf { id !in disabled } ?: Int.MAX_VALUE
            fun place(id: String): Int {
                val at = own.getValue(id)
                if (id !in RELAY_IDS) return at
                val origin = (known[id] as? ProviderResult.Hit)?.payload?.attribution?.originName ?: return at
                if (id == LRCMUX_ID) return LRCMUX_ORIGINS[origin]?.let(::slot) ?: at
                return when (origin) {
                    in SPICY_OWN_ORIGINS -> own[SPICY_ID] ?: at
                    "Apple Music" -> slot(APPLE_MUSIC_ID)
                    "Spotify" -> slot(SPOTIFY_ID)
                    else -> Int.MAX_VALUE
                }
            }
            return ranked.sortedWith(compareBy({ place(it.id) }, { own.getValue(it.id) }))
        }

        const val SPICY_ID = "spicy_lyrics"
        const val APPLE_MUSIC_ID = "apple_music"
        const val SPOTIFY_ID = "spotify"
        const val LRC_RED_ID = "lrc_red"
        /** Relays of Spicy Lyrics' API (RMM Revival relays it by Apple Music ID). */
        private val SPICY_RELAY_IDS = setOf(SPICY_ID, "rmm_revival")
        const val LRCMUX_ID = "lrcmux"
        /** LRCMux's upstreams, by the name its answers give, and the sources they rank as. */
        val LRCMUX_ORIGINS = mapOf(
            "KuGou" to "kugou",
            "LRCLIB" to LRCLIB_ID,
            "Genius" to "genius",
            "YouTube Music" to "youtube_transcript",
        )
        /** Sources that pass on lyrics from elsewhere and say where from. */
        val RELAY_IDS = SPICY_RELAY_IDS + LRC_RED_ID + LRCMUX_ID
        /** Stand in for a relay's answer that isn't in yet, at the highest place it could take. */
        private val SPICY_SYNC = ProviderResult.Hit(RemoteLyricsPayload(attribution = LyricsAttribution("", originName = "Spicy Lyrics")))
        private val APPLE_LYRICS = ProviderResult.Hit(RemoteLyricsPayload(attribution = LyricsAttribution("", originName = "Apple Music")))
        /** Origin names (SpicyLyricsProvider.spicyOriginName) for Spicy Lyrics' own syncs. */
        val SPICY_OWN_ORIGINS = setOf("Spicy Lyrics", "Spicy Lyrics Community")

        /** How long the lead source is asked alone before everyone else is asked too. */
        const val LEAD_HOLD_MS = 1_000L
        /** Where a blend takes its lines when nothing ranked above it has any. */
        const val LRCLIB_ID = "lrclib"
        const val BLEND_FAMILY = "blend"
    }

    private fun earliest(current: Instant?, candidate: Instant): Instant = when {
        current == null -> candidate
        candidate.isBefore(current) -> candidate
        else -> current
    }
}

/**
 * The next element, or null once [ms] pass. Unlike `withTimeoutOrNull { receive() }`, an element
 * arriving as the time runs out is never taken and then dropped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun <T> ReceiveChannel<T>.receiveWithin(ms: Long): T? = select {
    onReceive { it }
    onTimeout(ms) { null }
}
