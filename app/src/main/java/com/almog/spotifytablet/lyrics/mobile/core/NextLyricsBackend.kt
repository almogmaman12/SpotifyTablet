package com.almog.spotifytablet.lyrics.mobile.core

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.JsonObject
import com.google.gson.JsonArray

import com.almog.spotifytablet.lyrics.mobile.network.data.*
import com.almog.spotifytablet.lyrics.mobile.network.data.providers.*
import com.almog.spotifytablet.lyrics.mobile.network.data.spotify.AnonymousSpotifyCatalogSearch
import com.almog.spotifytablet.lyrics.mobile.network.data.spotify.SharedSpotify
import com.almog.spotifytablet.lyrics.mobile.network.data.spotify.SpotifyTrackResolver
import com.almog.spotifytablet.lyrics.mobile.network.service.LyricsService
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/** External-playback wiring for the original player's provider boundary. */
internal class NextLyricsBackend(context: Context, clientKey: String) {
    private val diskCache = File(context.cacheDir, "lyrics")
    private val preferences = context.getSharedPreferences("lyrics_sources", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    /** Header values of custom sources: kept out of [preferences], which backups copy. */
    private val customHeaders = context.getSharedPreferences("custom_source_headers", Context.MODE_PRIVATE)
    private val builtIn: Set<RemoteLyricsProvider> =
        createProviders(client, gson, clientKey, SharedSpotify.resolver)
    private val cooldowns = ProviderCooldownTracker()
    // Custom sources come and go, so these are rebuilt with them ([rebuild]).
    @Volatile private var providers: Set<RemoteLyricsProvider> = builtIn
    @Volatile private var source = RemoteLyricsSource(providers, cooldowns)
    @Volatile var descriptors: List<LyricsSourceDescriptor> = emptyList()
        private set

    init {
        rebuild()
        // Left by the Musixmatch source, which is gone.
        File(context.cacheDir, "musixmatch-token.txt").delete()
    }
    /** Blends are switched on and off apart from the ordered sources; their rank follows their donors. */
    val blendDescriptors: List<LyricsSourceDescriptor> = LyricsBlends.ALL.map(LyricsBlendDefinition::descriptor)

    /** Readies every source's tokens; failures are left for the real lookup to report. */
    suspend fun warmUp() = coroutineScope {
        providers.forEach { provider ->
            launch { runCatching { provider.warmUp() }.onFailure { if (it is CancellationException) throw it } }
        }
    }

    suspend fun resolve(
        request: LyricsLookupRequest,
        known: MutableMap<String, ProviderResult>,
        onUpdate: suspend (RemoteLyricsResolution) -> Unit = {},
    ): RemoteLyricsResolution = source.resolveLyrics(request, policy(), known = known, onUpdate = onUpdate)

    /**
     * A stored pick. [settled] is false when a source ranked above it had no real answer (an error,
     * or no Spotify match for a request without a length): the pick shows at once, but those
     * sources are asked again. [answers] are what may be reused without asking.
     */
    class CachedPick(val resolution: RemoteLyricsResolution, val settled: Boolean) {
        val answers: Map<String, ProviderResult> get() = buildMap {
            resolution.attempts.filter { it.outcome == ProviderAttemptOutcome.MISS }.forEach { put(it.sourceId, ProviderResult.Miss) }
            // A blend is rebuilt from its donors, never taken as an answer.
            (resolution as? RemoteLyricsResolution.Found)?.selection
                ?.takeIf { LyricsBlends.byId(it.source.id) == null }
                ?.let { put(it.source.id, ProviderResult.Hit(it.payload)) }
        }
    }

    /**
     * The last final pick for [request]: kept [CACHE_DAYS] days,
     * "no lyrics" included, errors never. Only reused while the enabled source order is the one it
     * was picked under, since another order could pick differently.
     */
    fun cachedResolution(request: LyricsLookupRequest): CachedPick? {
        val stored = runCatching { gson.fromJson(cacheFile(request).readText(), StoredPick::class.java) }.getOrNull()
            ?: return null
        if (stored.version != CACHE_VERSION || stored.expiresAt < System.currentTimeMillis() || stored.order != enabledOrder()) return null
        val attempts = stored.attempts.orEmpty().mapNotNull(StoredAttempt::restore)
        val payload = stored.payload ?: return CachedPick(RemoteLyricsResolution.NotFound(attempts), stored.settled)
        val source = (descriptors + blendDescriptors).firstOrNull { it.id == stored.sourceId } ?: return null
        val quality = payload.measuredQuality()
        return CachedPick(
            RemoteLyricsResolution.Found(
                RemoteLyricsSelection(source, payload, quality),
                attempts.map { if (it.sourceId == source.id) it.copy(message = "cached") else it },
            ),
            stored.settled,
        )
    }

    fun store(request: LyricsLookupRequest, resolution: RemoteLyricsResolution) {
        val found = resolution as? RemoteLyricsResolution.Found
        if (found == null && resolution !is RemoteLyricsResolution.NotFound) return
        val stored = StoredPick(
            version = CACHE_VERSION,
            expiresAt = System.currentTimeMillis() + CACHE_DAYS * 86_400_000L,
            order = enabledOrder(),
            sourceId = found?.selection?.source?.id,
            payload = found?.selection?.payload,
            attempts = resolution.attempts.map(StoredAttempt::of),
            settled = isSettled(request, resolution),
        )
        runCatching { diskCache.mkdirs(); cacheFile(request).writeText(gson.toJson(stored)) }
    }

    fun forget(request: LyricsLookupRequest) {
        cacheFile(request).delete()
    }

    /** Drops every stored pick and Genius lookup; the next lookups ask the sources again. */
    fun clearCache() {
        diskCache.deleteRecursively()
        romanCache.deleteRecursively()
        humanTranslationCache.clear()
    }

    private fun enabledOrder(): List<String> = policy().let { p ->
        val revisions = providers.mapNotNull { (it as? CustomLyricsProvider)?.source }.associate { it.id to it.revision }
        p.sourceOrder.filter { it !in p.disabledSourceIds }.map { id -> revisions[id]?.let { "$id@$it" } ?: id } + p.enabledBlendIds.sorted()
    }

    private fun cacheFile(request: LyricsLookupRequest): File {
        // Title + artist only: a queue entry warmed ahead has no album, length or Spotify ID, and
        // must hit the same entry the real load asks for once the song starts.
        return File(diskCache, cacheKey(request.title, request.artist) + ".json")
    }

    private fun cacheKey(title: String, artist: String): String {
        val identity = listOf(title, artist).joinToString("\u001f") { it.trim().lowercase() }
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private data class StoredPick(
        val version: Int,
        val expiresAt: Long,
        val order: List<String>,
        val sourceId: String?,
        val payload: RemoteLyricsPayload?,
        val attempts: List<StoredAttempt>?,
        val settled: Boolean,
    )

    /** An attempt by names, so a renamed or dropped enum value only loses that one line. */
    private data class StoredAttempt(
        val sourceId: String,
        val outcome: String,
        val quality: String,
        val category: String?,
        val message: String?,
    ) {
        fun restore(): ProviderAttempt? = runCatching {
            ProviderAttempt(
                sourceId,
                ProviderAttemptOutcome.valueOf(outcome),
                RemoteLyricsQuality.valueOf(quality),
                failureCategory = category?.let(ProviderFailureCategory::valueOf),
                message = message,
            )
        }.getOrNull()

        companion object {
            fun of(attempt: ProviderAttempt) = StoredAttempt(
                attempt.sourceId, attempt.outcome.name, attempt.quality.name, attempt.failureCategory?.name, attempt.message,
            )
        }
    }

    companion object {
        /** Whether every source ranked above the pick (every source, for "no lyrics") really answered. */
        internal fun isSettled(request: LyricsLookupRequest, resolution: RemoteLyricsResolution): Boolean {
            val pick = (resolution as? RemoteLyricsResolution.Found)?.selection?.source?.id
            val above = resolution.attempts.takeWhile { it.sourceId != pick }
            return above.none {
                it.outcome in UNANSWERED ||
                    // Matched on title and artist alone (a queue entry, or a player that sends the
                    // length later): with the length, the match may well be found.
                    (it.outcome == ProviderAttemptOutcome.NEEDS_MATCH && request.durationSeconds <= 0)
            }
        }

        /**
         * Sources on by default before [SOURCE_DEFAULTS_VERSION] 2 that now ask first: switched off
         * once for installs that had them on, so they're only asked after their disclosure.
         */
        internal val NOW_OPT_IN = setOf("kugou", "netease", "qq_music", "kuwo", "genius")
        internal const val MUSIXMATCH_ID = "musixmatch"

        /**
         * Brings choices saved by an older version up to date, once. The order and every other
         * choice stay; [NOW_OPT_IN] sources and the Genius romanization switch, which asks Genius,
         * go off. What was on and went off, Musixmatch included, is kept in [SWITCHED_OFF] for the
         * user to be told. Runs on every read, so a restored older backup is brought up too.
         */
        internal fun migrateSourceDefaults(preferences: SharedPreferences) {
            val savedDefaults = preferences.getInt("defaults", 0)
            if (savedDefaults >= SOURCE_DEFAULTS_VERSION) return
            val edit = preferences.edit().putInt("defaults", SOURCE_DEFAULTS_VERSION)
            // Fresh installs start on the new defaults: nothing to switch off or tell.
            if (savedDefaults > 0) {
                val disabled = preferences.getStringSet("disabled", emptySet()).orEmpty()
                val switchedOff = (NOW_OPT_IN + MUSIXMATCH_ID).filterTo(mutableSetOf()) { it !in disabled }
                // On by default before.
                if (preferences.getBoolean("humanRomanizations", true)) switchedOff += SourceDisclosures.GENIUS_ROMANIZATION_ID
                edit.putStringSet("disabled", disabled + NOW_OPT_IN)
                    .remove("humanRomanizations")
                    // The Musixmatch word-sync switch, gone with Musixmatch.
                    .remove("ignoreMusixmatchWordSync")
                    .putStringSet(SWITCHED_OFF, switchedOff)
            }
            edit.apply()
        }

        /** Every source the app asks, built on [client]. Also used by the live source check test. */
        fun createProviders(
            client: OkHttpClient,
            gson: Gson,
            clientKey: String,
            spotifyResolver: SpotifyTrackResolver = SpotifyTrackResolver(AnonymousSpotifyCatalogSearch(client, gson)),
        ): Set<RemoteLyricsProvider> {
            val lrclib = Retrofit.Builder()
                .baseUrl(LyricsService.BASE_URL)
                // LRCLIB asks clients to identify themselves; its Cloudflare front answers OkHttp's
                // default user agent with HTTP 520.
                .client(client.newBuilder().addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().header("User-Agent", APP_USER_AGENT).build())
                }.build())
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build()
                .create(LyricsService::class.java)
            return setOf(
                SpicyLyricsProvider(client, gson, spotifyResolver, clientKey),
                AmllLyricsProvider(client, gson),
                UnisonLyricsProvider(client, gson),
                RelayedOriginSlot.APPLE_MUSIC,
                RmmRevivalLyricsProvider(client, gson),
                LrcRedLyricsProvider(client, gson),
                BiniLyricsProvider(client, gson),
                KugouLyricsProvider(client, gson),
                QqMusicLyricsProvider(client, gson),
                KuwoLyricsProvider(client),
                NetEaseLyricsProvider(client, gson),
                LyricsSource(lrclib),
                RelayedOriginSlot.SPOTIFY,
                LrcMuxLyricsProvider(client, gson),
                GeniusLyricsProvider(client, gson),
                YouTubeTranscriptLyricsProvider(client, gson),
            )
        }

        private val ORIGIN = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://[^/?#]*")

        /** Bump when payload conversion changes, so stale conversions are refetched. */
        private const val CACHE_VERSION = 21
        /** Outcomes that are no answer at all: a later lookup may get one. */
        private val UNANSWERED = setOf(
            ProviderAttemptOutcome.UNAVAILABLE,
            ProviderAttemptOutcome.COOLING_DOWN,
            ProviderAttemptOutcome.QUEUED,
            ProviderAttemptOutcome.PENDING,
        )
        /**
         * Bump when the default source order or on/off set changes, to reset saved choices once.
         * 2: Spicy Lyrics and the lyrics APIs open to any app (LRCLIB, AMLL TTML DB, Unison, LRCMux,
         * lrc.red, BiniLyrics) are on; every other source asks first ([SourceDisclosures]).
         */
        internal const val SOURCE_DEFAULTS_VERSION = 2
        private val APP_USER_AGENT = "Spicy Lyrics Mobile 1.0 (com.almog.spotifytablet)"
        private const val CACHE_DAYS = 3
        /** Custom sources start after every built-in one. */
        private const val CUSTOM_PRIORITY = 1_000
        internal const val SWITCHED_OFF = "switchedOff"
        private const val DISCLOSED = "disclosed"
    }

    /** Run on every read, so a restored old backup can't switch the old sources back on either. */
    private fun migrateDefaults() = migrateSourceDefaults(preferences)

    fun policy(): RemoteLyricsPolicy {
        migrateDefaults()
        val order = preferences.getString("order", null)?.split(',')?.filter(String::isNotBlank)
        val disabled = preferences.getStringSet("disabled", emptySet()).orEmpty()
        val normalized = LyricsSourcePreferenceNormalizer.normalize(order, disabled, descriptors)
        val blends = preferences.getStringSet("blends", emptySet()).orEmpty()
            .filterTo(mutableSetOf()) { LyricsBlends.byId(it) != null }
        return RemoteLyricsPolicy(normalized.order, normalized.disabledSourceIds, blends)
    }

    fun setBlendEnabled(id: String, enabled: Boolean) {
        val blends = policy().enabledBlendIds.toMutableSet()
        if (enabled) blends += id else blends -= id
        preferences.edit().putStringSet("blends", blends).apply()
    }

    /** Whether what [id] sends ([SourceDisclosures]) has been shown and agreed to. */
    fun isDisclosed(id: String): Boolean = id in preferences.getStringSet(DISCLOSED, emptySet()).orEmpty()

    fun markDisclosed(id: String) {
        preferences.edit().putStringSet(DISCLOSED, preferences.getStringSet(DISCLOSED, emptySet()).orEmpty() + id).apply()
    }

    /** What the update to [SOURCE_DEFAULTS_VERSION] switched off ([migrateSourceDefaults]), until the user's been told. */
    val switchedOffSources: Set<String>
        get() {
            migrateDefaults()
            return preferences.getStringSet(SWITCHED_OFF, emptySet()).orEmpty()
        }

    fun dismissSwitchedOff() {
        preferences.edit().remove(SWITCHED_OFF).apply()
    }

    /** Human-written romanizations from Genius over the on-device ones (off until asked for: it asks Genius). */
    var humanRomanizations: Boolean
        get() = preferences.getBoolean("humanRomanizations", false)
        set(value) = preferences.edit().putBoolean("humanRomanizations", value).apply()

    private val geniusRomanization = GeniusRomanizationSource(client, gson)
    private val romanCache = File(context.cacheDir, "genius-roman")
    private val geniusTranslation = GeniusTranslationSource(client, gson)
    private val humanTranslationCache = com.almog.spotifytablet.lyrics.mobile.translation.DiskGeniusTranslationCache(File(context.cacheDir, "genius-translations"))

    suspend fun humanTranslation(title: String, artist: String, target: String): com.almog.spotifytablet.lyrics.mobile.translation.GeniusTranslationPair? =
        humanTranslationCache.find(title, artist, target) { geniusTranslation.find(title, artist, target) }

    /**
     * Genius's romanization of the song as lyric lines, or null when it has none. Kept on disk a
     * week when found and a day when not, so a replayed song asks once.
     */
    suspend fun humanRomanization(title: String, artist: String): List<String>? {
        val file = File(romanCache, cacheKey(title, artist) + ".json")
        runCatching { gson.fromJson(file.readText(), StoredRoman::class.java) }.getOrNull()
            ?.takeIf { it.expiresAt > System.currentTimeMillis() }
            ?.let { return it.lines }
        val lines = geniusRomanization.find(title, artist)
        val keep = TimeUnit.DAYS.toMillis(if (lines != null) 7 else 1)
        runCatching {
            romanCache.mkdirs()
            file.writeText(gson.toJson(StoredRoman(System.currentTimeMillis() + keep, lines)))
        }
        return lines
    }

    fun forgetRomanization(title: String, artist: String) {
        File(romanCache, cacheKey(title, artist) + ".json").delete()
        humanTranslationCache.forget(title, artist)
    }

    private data class StoredRoman(val expiresAt: Long, val lines: List<String>?)

    /** The user's own sources ([CustomLyricsSource]), in the order they were added. */
    fun customSources(): List<CustomLyricsSource> = runCatching {
        JsonParser.parseString(preferences.getString("custom", null) ?: return emptyList()).asJsonArray.mapNotNull { element ->
            runCatching {
                val o = element.asJsonObject
                val id = o.get("id").asString
                val url = o.get("url").asString
                // Values only go to the host they were typed for: a restored backup that points
                // a source elsewhere leaves them behind. Ones saved before this get bound now.
                val bound = customHeaders.getString(originKey(id), null)
                    ?: origin(url).also { customHeaders.edit().putString(originKey(id), it).apply() }
                val values = if (bound != origin(url)) null
                    else runCatching { JsonParser.parseString(customHeaders.getString(id, "{}")).asJsonObject }.getOrNull()
                CustomLyricsSource(
                    id = id,
                    name = o.get("name").asString,
                    url = url,
                    path = o.get("path")?.asString.orEmpty(),
                    headers = o.getAsJsonArray("headers")?.map { it.asString }.orEmpty()
                        .filter(CustomLyricsSource::isHeaderName)
                        .map { name -> name to (values?.get(name)?.asString.orEmpty()) },
                    keyRevision = customHeaders.getString(revisionKey(id), null).orEmpty(),
                )
            }.getOrNull()
        }
    }.getOrDefault(emptyList())

    /** Adds [custom], or replaces the one with its ID. */
    fun saveCustomSource(custom: CustomLyricsSource) {
        writeCustomSources(customSources().let { list ->
            if (list.any { it.id == custom.id }) list.map { if (it.id == custom.id) custom else it } else list + custom
        })
        customHeaders.edit()
            .putString(custom.id, JsonObject().apply { custom.headers.forEach { (name, value) -> addProperty(name, value) } }.toString())
            .putString(originKey(custom.id), origin(custom.url))
            .putString(revisionKey(custom.id), java.util.UUID.randomUUID().toString().take(8))
            .apply()
        rebuild()
    }

    fun removeCustomSource(id: String) {
        writeCustomSources(customSources().filter { it.id != id })
        customHeaders.edit().remove(id).remove(originKey(id)).remove(revisionKey(id)).apply()
        rebuild()
    }

    /** Asks [custom] alone for [request], for its editor's test; nothing is cached. */
    suspend fun testCustomSource(custom: CustomLyricsSource, request: LyricsLookupRequest): ProviderResult =
        CustomLyricsProvider(custom, client, SharedSpotify.resolver, 0, APP_USER_AGENT).fetch(request)

    private fun originKey(id: String) = "$id@origin"
    private fun revisionKey(id: String) = "$id@revision"
    /** "https://host:port" of a URL template, placeholders and all. */
    private fun origin(url: String) = ORIGIN.find(url)?.value?.lowercase().orEmpty()

    // Names and addresses only: header values are in [customHeaders].
    private fun writeCustomSources(list: List<CustomLyricsSource>) {
        val array = JsonArray()
        list.forEach { custom ->
            array.add(JsonObject().apply {
                addProperty("id", custom.id)
                addProperty("name", custom.name)
                addProperty("url", custom.url)
                addProperty("path", custom.path)
                add("headers", JsonArray().apply { custom.headers.forEach { add(it.first) } })
            })
        }
        preferences.edit().putString("custom", array.toString()).apply()
    }

    /** Re-reads the custom sources, after a settings restore. */
    fun reloadCustomSources() = rebuild()

    private fun rebuild() {
        val custom = customSources().mapIndexed { index, it -> CustomLyricsProvider(it, client, SharedSpotify.resolver, CUSTOM_PRIORITY + index, APP_USER_AGENT) }
        val all = builtIn + custom
        providers = all
        source = RemoteLyricsSource(all, cooldowns)
        descriptors = all.map(RemoteLyricsProvider::descriptor)
            .sortedWith(compareBy<LyricsSourceDescriptor> { it.defaultPriority }.thenBy { it.id })
    }

    fun setPolicy(order: List<String>, disabledSourceIds: Set<String>) {
        val normalized = LyricsSourcePreferenceNormalizer.normalize(order, disabledSourceIds, descriptors)
        preferences.edit()
            .putString("order", normalized.order.joinToString(","))
            .putStringSet("disabled", normalized.disabledSourceIds)
            .apply()
    }
}
