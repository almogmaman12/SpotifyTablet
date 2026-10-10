package com.almog.spotifytablet.lyrics.repository

import android.net.Uri
import android.util.LruCache
import com.almog.spotifytablet.DebugLog
import com.almog.spotifytablet.lyrics.model.LyricLine
import com.almog.spotifytablet.lyrics.model.LyricTrack
import com.almog.spotifytablet.lyrics.model.WordSync
import com.almog.spotifytablet.lyrics.model.clampWordOverlaps
import com.almog.spotifytablet.lyrics.parser.EnhancedLrcParser
import com.almog.spotifytablet.lyrics.verifier.LyricsMatchVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class LyricsRepository(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4000, TimeUnit.MILLISECONDS)
        .readTimeout(4000, TimeUnit.MILLISECONDS)
        .build(),
    private val cacheDir: File? = null
) {
    companion object {
        private const val TAG = "LyricsRepository"
        private const val CIRCUIT_BREAKER_TIMEOUT_MS = 300_000L // 5 minutes
        private const val MAX_CACHE_AGE_MS = 30L * 24L * 60L * 60L * 1000L // 30 days maximum per API Terms
        /** Subdirectory name used for disk cache — must match getDiskCacheFile(). */
        const val DISK_CACHE_SUBDIR = "lyrics_cache"

        @Volatile
        @JvmStatic
        var defaultDiskCacheDir: File? = null

        /**
         * Wipes every file in the lyrics disk-cache directory.
         * Call this from SettingsActivity (or anywhere) to honour a user-initiated clear.
         * Also evicts [globalMemoryCache] so stale in-memory entries don't survive.
         */
        @JvmStatic
        fun clearAllCache() {
            // 1. Disk cache
            val baseDir = defaultDiskCacheDir
            if (baseDir != null) {
                val subDir = File(baseDir, DISK_CACHE_SUBDIR)
                val deleted = mutableListOf<String>()
                val failed  = mutableListOf<String>()
                subDir.listFiles { f -> f.isFile }?.forEach { f ->
                    if (f.delete()) deleted.add(f.name) else failed.add(f.name)
                }
                DebugLog.i(TAG, "clearAllCache: deleted ${deleted.size} disk-cache files" +
                        if (failed.isNotEmpty()) ", ${failed.size} could not be deleted" else "")
            } else {
                DebugLog.w(TAG, "clearAllCache: defaultDiskCacheDir is null — no disk cache to clear")
            }
            // 2. In-memory LRU cache (shared via companion so all ViewModel instances are affected)
            globalMemoryCache.evictAll()
            DebugLog.i(TAG, "clearAllCache: in-memory LRU cache evicted")
        }

        /**
         * Shared LRU cache across all LyricsRepository instances (ViewModel survives
         * config changes, so a per-instance cache is fine too, but sharing means the
         * Settings 'Clear cache' button can evict it without holding a reference).
         */
        private val globalMemoryCache = LruCache<String, LyricTrack>(60)
    }

    // Per-instance alias — reads/writes go to the shared companion cache.
    private val trackCache: LruCache<String, LyricTrack> get() = globalMemoryCache
    // Circuit breaker registry: provider -> bypassUntilMs
    private val circuitBreakers = ConcurrentHashMap<String, Long>()

    private fun isCircuitOpen(provider: String): Boolean {
        val bypassUntil = circuitBreakers[provider] ?: return false
        if (System.currentTimeMillis() < bypassUntil) {
            DebugLog.d(TAG, "Circuit OPEN for $provider, bypassing tier")
            return true
        }
        circuitBreakers.remove(provider)
        return false
    }

    private fun tripCircuit(provider: String) {
        val until = System.currentTimeMillis() + CIRCUIT_BREAKER_TIMEOUT_MS
        circuitBreakers[provider] = until
        DebugLog.w(TAG, "Tripped circuit breaker for $provider until $until")
    }

    private fun getDiskCacheFile(cacheKey: String): File? {
        val baseDir = cacheDir ?: defaultDiskCacheDir ?: return null
        val subDir = File(baseDir, "lyrics_cache")
        if (!subDir.exists()) subDir.mkdirs()
        val hash = try {
            val md = MessageDigest.getInstance("MD5")
            val bytes = md.digest(cacheKey.toByteArray())
            bytes.joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            cacheKey.replace(Regex("[^a-zA-Z0-9_]"), "_")
        }
        return File(subDir, "$hash.json")
    }

    private fun pruneDiskCacheIfNeeded(subDir: File) {
        try {
            val maxSizeBytes = 50L * 1024L * 1024L // 50 MB
            val maxFiles = 1000
            val now = System.currentTimeMillis()
            val files = subDir.listFiles { f -> f.isFile && f.extension == "json" } ?: return

            // 1. Enforce strict 30-day max cache TTL
            for (f in files) {
                if (now - f.lastModified() > MAX_CACHE_AGE_MS) {
                    f.delete()
                }
            }

            val remainingFiles = subDir.listFiles { f -> f.isFile && f.extension == "json" } ?: return
            if (remainingFiles.size <= maxFiles) {
                val totalSize = remainingFiles.sumOf { it.length() }
                if (totalSize <= maxSizeBytes) return
            }

            // Prune oldest accessed/modified files first
            val sortedFiles = remainingFiles.sortedBy { it.lastModified() }
            var currentSize = sortedFiles.sumOf { it.length() }
            var currentCount = sortedFiles.size

            for (f in sortedFiles) {
                if (currentCount <= maxFiles && currentSize <= maxSizeBytes) break
                val len = f.length()
                if (f.delete()) {
                    currentSize -= len
                    currentCount--
                }
            }
        } catch (e: Exception) {
            DebugLog.w(TAG, "Cache pruning error: ${e.message}")
        }
    }

    private fun saveTrackToDisk(cacheKey: String, track: LyricTrack) {
        try {
            val file = getDiskCacheFile(cacheKey) ?: return
            val root = JSONObject()
            root.put("isWordSynced", track.isWordSynced)
            root.put("source", track.source)
            if (track.bpm != null) root.put("bpm", track.bpm.toDouble())

            track.attribution?.let { attr ->
                val attrObj = JSONObject()
                attrObj.put("provider", attr.provider)
                attr.uploader?.let { u ->
                    val uObj = JSONObject()
                    uObj.put("username", u.username)
                    if (u.url != null) uObj.put("url", u.url)
                    attrObj.put("uploader", uObj)
                }
                attr.maker?.let { m ->
                    val mObj = JSONObject()
                    mObj.put("username", m.username)
                    if (m.url != null) mObj.put("url", m.url)
                    attrObj.put("maker", mObj)
                }
                root.put("attribution", attrObj)
            }

            val linesArray = JSONArray()
            for (line in track.lines) {
                val lObj = JSONObject()
                lObj.put("startTimeMs", line.startTimeMs)
                lObj.put("endTimeMs", line.endTimeMs)
                lObj.put("rawText", line.rawText)
                lObj.put("isSynthesized", line.isSynthesized)
                lObj.put("isBackground", line.isBackground)
                if (line.agentId != null) lObj.put("agentId", line.agentId)
                if (line.translation != null) lObj.put("translation", line.translation)

                val wordsArray = JSONArray()
                for (w in line.words) {
                    val wObj = JSONObject()
                    wObj.put("text", w.text)
                    wObj.put("startTimeMs", w.startTimeMs)
                    wObj.put("endTimeMs", w.endTimeMs)
                    wObj.put("trailingSpace", w.trailingSpace)
                    w.romanized?.let { wObj.put("romanized", it) }
                    wordsArray.put(wObj)
                }
                lObj.put("words", wordsArray)

                if (line.backgroundLine != null) {
                    val bgObj = JSONObject()
                    bgObj.put("startTimeMs", line.backgroundLine.startTimeMs)
                    bgObj.put("endTimeMs", line.backgroundLine.endTimeMs)
                    bgObj.put("rawText", line.backgroundLine.rawText)
                    bgObj.put("isSynthesized", line.backgroundLine.isSynthesized)
                    bgObj.put("isBackground", true)
                    val bgWordsArray = JSONArray()
                    for (bw in line.backgroundLine.words) {
                        val bwObj = JSONObject()
                        bwObj.put("text", bw.text)
                        bwObj.put("startTimeMs", bw.startTimeMs)
                        bwObj.put("endTimeMs", bw.endTimeMs)
                        bwObj.put("trailingSpace", bw.trailingSpace)
                        bgWordsArray.put(bwObj)
                    }
                    bgObj.put("words", bgWordsArray)
                    lObj.put("backgroundLine", bgObj)
                }

                linesArray.put(lObj)
            }
            root.put("lines", linesArray)
            file.writeText(root.toString())

            // Trigger LRU pruning & 30-day eviction check in background
            file.parentFile?.let { pruneDiskCacheIfNeeded(it) }
        } catch (e: Exception) {
            DebugLog.e(TAG, "Failed saving track to disk cache: ${e.message}")
        }
    }

    private fun loadTrackFromDisk(cacheKey: String, expectedDurationMs: Int = 0): LyricTrack? {
        return try {
            val file = getDiskCacheFile(cacheKey) ?: return null
            if (!file.exists()) return null
            // Check 30-day expiration limit
            if (System.currentTimeMillis() - file.lastModified() > MAX_CACHE_AGE_MS) {
                file.delete()
                return null
            }
            file.setLastModified(System.currentTimeMillis())
            val content = file.readText()
            val root = JSONObject(content)
            val isWordSynced = root.optBoolean("isWordSynced", false)
            val source = root.optString("source", "Disk Cache")
            val bpm = if (root.has("bpm")) root.getDouble("bpm").toFloat() else null

            val attrObj = root.optJSONObject("attribution")
            val attribution = if (attrObj != null) {
                val provider = attrObj.optString("provider", "")
                val uObj = attrObj.optJSONObject("uploader")
                val uploader = if (uObj != null) com.almog.spotifytablet.lyrics.model.LyricContributor(
                    username = uObj.optString("username", ""),
                    url = if (uObj.has("url")) uObj.optString("url") else null
                ) else null
                val mObj = attrObj.optJSONObject("maker")
                val maker = if (mObj != null) com.almog.spotifytablet.lyrics.model.LyricContributor(
                    username = mObj.optString("username", ""),
                    url = if (mObj.has("url")) mObj.optString("url") else null
                ) else null
                com.almog.spotifytablet.lyrics.model.LyricAttribution(
                    provider = provider,
                    uploader = uploader,
                    maker = maker
                )
            } else null

            val linesArray = root.optJSONArray("lines") ?: return null
            val lines = mutableListOf<LyricLine>()

            for (i in 0 until linesArray.length()) {
                val lObj = linesArray.getJSONObject(i)
                val wordsArray = lObj.optJSONArray("words") ?: JSONArray()
                val words = mutableListOf<WordSync>()
                for (j in 0 until wordsArray.length()) {
                    val wObj = wordsArray.getJSONObject(j)
                    val text = wObj.getString("text")
                    words.add(
                        WordSync(
                            text = text,
                            startTimeMs = wObj.getLong("startTimeMs"),
                            endTimeMs = wObj.getLong("endTimeMs"),
                            trailingSpace = wObj.optBoolean("trailingSpace", true),
                            romanized = wObj.optString("romanized", "").ifEmpty { null }
                        )
                    )
                }

                var cachedBgLine: LyricLine? = null
                if (lObj.has("backgroundLine")) {
                    val bgObj = lObj.getJSONObject("backgroundLine")
                    val bgWordsArray = bgObj.optJSONArray("words") ?: JSONArray()
                    val bgWords = mutableListOf<WordSync>()
                    for (k in 0 until bgWordsArray.length()) {
                        val bwObj = bgWordsArray.getJSONObject(k)
                        bgWords.add(
                            WordSync(
                                text = bwObj.getString("text"),
                                startTimeMs = bwObj.getLong("startTimeMs"),
                                endTimeMs = bwObj.getLong("endTimeMs"),
                                trailingSpace = bwObj.optBoolean("trailingSpace", true)
                            )
                        )
                    }
                    cachedBgLine = LyricLine(
                        startTimeMs = bgObj.getLong("startTimeMs"),
                        endTimeMs = bgObj.getLong("endTimeMs"),
                        words = bgWords,
                        rawText = bgObj.optString("rawText", ""),
                        isSynthesized = bgObj.optBoolean("isSynthesized", false),
                        isBackground = true,
                        agentId = "bg"
                    )
                }

                lines.add(
                    LyricLine(
                        startTimeMs = lObj.getLong("startTimeMs"),
                        endTimeMs = lObj.getLong("endTimeMs"),
                        words = words,
                        rawText = lObj.optString("rawText", ""),
                        isSynthesized = lObj.optBoolean("isSynthesized", false),
                        isBackground = lObj.optBoolean("isBackground", false),
                        agentId = if (lObj.has("agentId")) lObj.optString("agentId") else null,
                        translation = if (lObj.has("translation")) lObj.optString("translation") else null,
                        backgroundLine = cachedBgLine
                    )
                )
            }
            val loadedTrack = LyricTrack(isWordSynced = isWordSynced, lines = lines, source = source, bpm = bpm, attribution = attribution)
            if (expectedDurationMs > 0) {
                val validation = LyricsMatchVerifier.validateLyricTrackTimeline(loadedTrack, expectedDurationMs)
                if (validation is LyricsMatchVerifier.LyricValidationResult.Rejected) {
                    DebugLog.w(TAG, "Cached disk track failed validation: ${validation.reason}. Evicting cache entry.")
                    file.delete()
                    return null
                }
            }
            loadedTrack
        } catch (_: Exception) {
            null
        }
    }

    suspend fun preloadUpcomingLyrics(
        artist: String,
        title: String,
        album: String = "",
        isrc: String = "",
        durationMs: Int = 0
    ) = withContext(Dispatchers.IO) {
        fetchLyrics(
            artist = artist,
            title = title,
            album = album,
            durationMs = durationMs,
            isrc = isrc
        )
    }

    suspend fun fetchLyrics(
        artist: String,
        title: String,
        album: String = "",
        durationMs: Int = 0,
        mediaSource: String = "auto",
        trackId: String = "",
        isrc: String = "",
        jellyfinUrl: String = "",
        jellyfinApiKey: String = ""
    ): LyricTrack? = withContext(Dispatchers.IO) {
        if (artist.isBlank() || title.isBlank()) return@withContext null

        val cleanTitle = cleanTrackTitle(title)
        DebugLog.d(TAG, "=== LYRICS FETCH START === artist='$artist' title='$cleanTitle' trackId='$trackId' isrc='$isrc' durationMs=$durationMs")

        val cacheKey = "$artist|$cleanTitle|${if (durationMs > 0) durationMs / 1000 else ""}|$isrc"
        trackCache.get(cacheKey)?.let { cached ->
            if (durationMs > 0 && !LyricsMatchVerifier.validateLyricTrackTimeline(cached, durationMs).isValid) {
                DebugLog.w(TAG, "Memory cached track failed validation, evicting")
                trackCache.remove(cacheKey)
            } else {
                DebugLog.d(TAG, "CACHE HIT (Memory): '${cached.source}' ${cached.lines.size} lines, wordSynced=${cached.isWordSynced}")
                return@withContext cached
            }
        }

        loadTrackFromDisk(cacheKey, durationMs)?.let { diskTrack ->
            DebugLog.d(TAG, "CACHE HIT (Disk): '${diskTrack.source}' ${diskTrack.lines.size} lines, wordSynced=${diskTrack.isWordSynced}")
            trackCache.put(cacheKey, diskTrack)
            return@withContext diskTrack
        }

        // 0. Spicy Lyrics Mobile sources (Spicy Lyrics, AMLL, Unison, LRCLIB, Apple, ... with its own ranking)
        com.almog.spotifytablet.lyrics.mobile.MobileLyricsSources.fetch(
            artist = artist,
            title = cleanTitle,
            album = album,
            durationMs = durationMs,
            spotifyTrackId = trackId.takeIf { mediaSource != "jellyfin" }
        )?.let { track ->
            DebugLog.i(TAG, "✅ [Tier 0 WIN] SPICY MOBILE sources: ${track.lines.size} lines, wordSynced=${track.isWordSynced} (source=${track.source})")
            val result = if (track.isWordSynced) track else track.synthesizeWordsIfMissing()
            trackCache.put(cacheKey, result)
            saveTrackToDisk(cacheKey, result)
            return@withContext result
        } ?: DebugLog.i(TAG, "⚠️ [Tier 0 MISS] Spicy Lyrics Mobile sources returned nothing")

        // 1. Spicy Lyrics Official API (Top priority — word-sync only)
        var spicyLineFallbackTrack: LyricTrack? = null
        if (trackId.isNotEmpty() && !isCircuitOpen("spicylyrics")) {
            DebugLog.i(TAG, "━━━ [Tier 1] Trying Spicy Lyrics API for trackId='$trackId'")
            fetchFromSpicyLyrics(trackId, durationMs)?.let { track ->
                if (track.isWordSynced) {
                    DebugLog.i(TAG, "✅ [Tier 1 WIN] SPICY LYRICS word-synced: ${track.lines.size} lines (source=${track.source})")
                    trackCache.put(cacheKey, track)
                    saveTrackToDisk(cacheKey, track)
                    return@withContext track
                } else {
                    DebugLog.i(TAG, "⚠️ [Tier 1 SKIP] Spicy Lyrics has NO word-sync (type=${track.source}, ${track.lines.size} lines). Moving to Tier 2 — stashing for last-resort fallback.")
                    spicyLineFallbackTrack = track
                }
            } ?: DebugLog.i(TAG, "⚠️ [Tier 1 MISS] Spicy Lyrics returned nothing for trackId='$trackId'")
        } else {
            if (trackId.isEmpty()) DebugLog.i(TAG, "⏭️ [Tier 1 SKIP] No Spotify trackId — cannot use Spicy Lyrics")
        }

        // 2. Lrcmux Word-Sync Mirror (Native Word Timestamps)
        if (!isCircuitOpen("lrcmux")) {
            DebugLog.i(TAG, "━━━ [Tier 2] Trying Lrcmux Word-Sync Mirror")
            fetchFromLrcmux(artist, title, album, isrc, durationMs, requireWordSync = true)?.let { track ->
                if (track.isWordSynced) {
                    DebugLog.i(TAG, "✅ [Tier 2 WIN] LRCMUX word-synced: ${track.lines.size} lines (source=${track.source})")
                    trackCache.put(cacheKey, track)
                    saveTrackToDisk(cacheKey, track)
                    return@withContext track
                } else {
                    DebugLog.i(TAG, "⚠️ [Tier 2 SKIP] Lrcmux returned non-word-synced data — moving on")
                }
            } ?: DebugLog.i(TAG, "⚠️ [Tier 2 MISS] Lrcmux returned nothing")
        }

        // ── FALLBACK TIERS (Line-level synced lyrics + synthesized word timing) ──

        // 3. LRCLIB
        DebugLog.i(TAG, "━━━ [Tier 3] Trying LRCLIB for '$cleanTitle' by '$artist'")
        fetchFromLrclib(artist, title, album, durationMs)?.let { track ->
            val synthesized = track.synthesizeWordsIfMissing()
            DebugLog.i(TAG, "✅ [Tier 3 WIN] LRCLIB: ${synthesized.lines.size} lines, wordSynced=${synthesized.isWordSynced}")
            trackCache.put(cacheKey, synthesized)
            saveTrackToDisk(cacheKey, synthesized)
            return@withContext synthesized
        } ?: DebugLog.i(TAG, "⚠️ [Tier 3 MISS] LRCLIB returned nothing")

        // 4. Jellyfin Local Storage
        if (mediaSource == "jellyfin" && trackId.isNotEmpty() && jellyfinUrl.isNotEmpty()) {
            DebugLog.i(TAG, "━━━ [Tier 4] Trying Jellyfin Local for trackId='$trackId'")
            fetchJellyfinLyrics(trackId, jellyfinUrl, jellyfinApiKey)?.let { track ->
                val synthesized = track.synthesizeWordsIfMissing()
                DebugLog.i(TAG, "✅ [Tier 4 WIN] JELLYFIN: ${synthesized.lines.size} lines, wordSynced=${synthesized.isWordSynced}")
                trackCache.put(cacheKey, synthesized)
                saveTrackToDisk(cacheKey, synthesized)
                return@withContext synthesized
            } ?: DebugLog.i(TAG, "⚠️ [Tier 4 MISS] Jellyfin returned nothing")
        } else {
            DebugLog.d(TAG, "⏭️ [Tier 4 SKIP] Not a Jellyfin source or no URL configured")
        }

        // 5. Lrcmux Line-Level Fallback
        if (!isCircuitOpen("lrcmux")) {
            DebugLog.i(TAG, "━━━ [Tier 5] Trying Lrcmux Line-Level Fallback")
            fetchFromLrcmux(artist, title, album, isrc, durationMs, requireWordSync = false)?.let { lineTrack ->
                val synthesized = lineTrack.synthesizeWordsIfMissing()
                DebugLog.i(TAG, "✅ [Tier 5 WIN] LRCMUX line-level: ${synthesized.lines.size} lines, wordSynced=${synthesized.isWordSynced} (source=${lineTrack.source})")
                trackCache.put(cacheKey, synthesized)
                saveTrackToDisk(cacheKey, synthesized)
                return@withContext synthesized
            } ?: DebugLog.i(TAG, "⚠️ [Tier 5 MISS] Lrcmux line-level returned nothing")
        }

        // 6. Spicy Lyrics Line-Level Fallback (stashed from Tier 1 if it had no word-sync)
        spicyLineFallbackTrack?.let { lineTrack ->
            val synthesized = lineTrack.synthesizeWordsIfMissing()
            DebugLog.i(TAG, "✅ [Tier 6 WIN] SPICY LYRICS line-level fallback: ${synthesized.lines.size} lines, wordSynced=${synthesized.isWordSynced}")
            trackCache.put(cacheKey, synthesized)
            saveTrackToDisk(cacheKey, synthesized)
            return@withContext synthesized
        }

        DebugLog.w(TAG, "❌ [ALL TIERS FAILED] No lyrics found for '$cleanTitle' by '$artist'")
        null
    }

    // ──────────────────────────────────────────────────────────────────────
    // Spicy Lyrics Official API (https://developers.spicylyrics.org)
    // ──────────────────────────────────────────────────────────────────────
    private fun fetchFromSpicyLyrics(spotifyTrackId: String, durationMs: Int): LyricTrack? {
        val apiKey = com.almog.spotifytablet.Constants.SPICY_LYRICS_API_KEY
        if (apiKey.isBlank()) {
            DebugLog.w(TAG, "SPICY_LYRICS_API_KEY is not set in local.properties. Skipping SpicyLyrics provider.")
            return null
        }

        // Only valid 22-char base62 Spotify track IDs are accepted by the endpoint
        if (!spotifyTrackId.matches(Regex("^[A-Za-z0-9]{22}$"))) {
            DebugLog.d(TAG, "Track ID '$spotifyTrackId' is not a 22-character Spotify track ID. Skipping SpicyLyrics.")
            return null
        }

        val url = "https://api.spicylyrics.org/v1/lyrics/$spotifyTrackId"
        DebugLog.d(TAG, "SpicyLyrics Request URL: $url")

        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .header("User-Agent", "SpotifyTablet/1.0 (Android)")
            .build()

        return try {
            httpClient.newCall(req).execute().use { resp ->
                DebugLog.i(TAG, "SpicyLyrics HTTP Status: ${resp.code} for trackId='$spotifyTrackId'")
                when {
                    resp.code == 401 -> {
                        val body = runCatching { resp.body?.string() }.getOrNull() ?: ""
                        DebugLog.e(TAG, "🔑 SPICY LYRICS 401 UNAUTHORIZED — API key is missing or invalid!\n" +
                                "  ▶ Add SPICY_LYRICS_API_KEY=<your_key> to local.properties\n" +
                                "  ▶ Response body: $body")
                        return@use null
                    }
                    resp.code == 404 -> {
                        DebugLog.i(TAG, "⚠️ SPICY LYRICS 404 — track '$spotifyTrackId' not in Spicy Lyrics catalog")
                        return@use null
                    }
                    resp.code == 429 || resp.code in 500..599 -> {
                        DebugLog.w(TAG, "⚠️ SPICY LYRICS ${resp.code} — server error, tripping circuit breaker for 5 min")
                        tripCircuit("spicylyrics")
                        return@use null
                    }
                    !resp.isSuccessful -> {
                        val body = runCatching { resp.body?.string() }.getOrNull() ?: ""
                        DebugLog.w(TAG, "⚠️ SPICY LYRICS ${resp.code} unexpected error — body: $body")
                        return@use null
                    }
                }

                val bodyStr = resp.body?.string() ?: return@use null
                parseSpicyLyricsResponse(bodyStr, durationMs)
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "SpicyLyrics Exception: ${e.message}")
            null
        }
    }

    private fun parseSpicyLyricsResponse(jsonStr: String, expectedDurationMs: Int): LyricTrack? {
        return try {
            val root = JSONObject(jsonStr)
            val body = root.optJSONObject("Body") ?: return null
            val syncType = body.optString("Type", "Line")
            val source = body.optString("source", "unknown")

            // Parse Attribution (mandatory compliance with section 6 Terms)
            val uploadAttr = body.optJSONObject("UploadAttribution")
            val uploader = uploadAttr?.optJSONObject("Uploader")?.let { u ->
                com.almog.spotifytablet.lyrics.model.LyricContributor(
                    username = u.optString("username", ""),
                    url = if (u.has("url")) u.optString("url") else null
                )
            }
            val maker = uploadAttr?.optJSONObject("Maker")?.let { m ->
                com.almog.spotifytablet.lyrics.model.LyricContributor(
                    username = m.optString("username", ""),
                    url = if (m.has("url")) m.optString("url") else null
                )
            }

            val providerName = when (source) {
                "spicy_lyrics" -> "Spicy Lyrics"
                "apple_music" -> "Apple Music"
                "spotify" -> "Spotify"
                else -> source
            }

            val attribution = com.almog.spotifytablet.lyrics.model.LyricAttribution(
                provider = providerName,
                uploader = uploader,
                maker = maker
            )

            val lines = mutableListOf<LyricLine>()

            if (syncType.equals("Syllable", ignoreCase = true)) {
                // Syllable (Word-level sync)
                val contentArray = body.optJSONArray("Content") ?: return null
                for (i in 0 until contentArray.length()) {
                    val lineObj = contentArray.getJSONObject(i)
                    val leadObj = lineObj.optJSONObject("Lead") ?: continue
                    val syllablesArray = leadObj.optJSONArray("Syllables") ?: continue

                    val lineStartMs = (leadObj.optDouble("StartTime", 0.0) * 1000).toLong()
                    val lineEndMs = (leadObj.optDouble("EndTime", 0.0) * 1000).toLong()
                    val lineTrans = leadObj.optString("TransliteratedText", "")
                    val isOpposite = lineObj.optBoolean("OppositeAligned", false)

                    val words = mutableListOf<WordSync>()
                    val rawTextBuilder = StringBuilder()

                    // Merge syllables that belong to the same word.
                    // Spicy marks IsPartOfWord=true for every syllable that continues the
                    // current word (i.e. it is NOT the last syllable of the word).
                    // IsPartOfWord=false means this syllable IS the last (or only) syllable
                    // of a word, so we flush the accumulated word here.
                    //
                    // Before this fix every syllable became its own FlowRow item, causing
                    // FlowRow to break mid-word (e.g. "beau-" on one line, "-tiful" on the next).
                    for (j in 0 until syllablesArray.length()) {
                        val sylObj = syllablesArray.getJSONObject(j)
                        val text = sylObj.optString("Text", "")
                        val sStart = (sylObj.optDouble("StartTime", 0.0) * 1000).toLong()
                        val sEnd = (sylObj.optDouble("EndTime", 0.0) * 1000).toLong()
                        // IsPartOfWord=true -> this syllable continues the word (no trailing space)
                        // IsPartOfWord=false -> this syllable finishes the word (has trailing space unless line end)
                        val isPartOfWord = sylObj.optBoolean("IsPartOfWord", false)
                        val isLastSyllable = (j == syllablesArray.length() - 1)

                        rawTextBuilder.append(text)
                        if (!isPartOfWord && !isLastSyllable) {
                            rawTextBuilder.append(" ")
                        }

                        words.add(
                            WordSync(
                                text = text,
                                startTimeMs = sStart,
                                endTimeMs = sEnd,
                                trailingSpace = !isPartOfWord && !isLastSyllable
                            )
                        )
                    }

                    // Background vocals if present
                    val bgArray = lineObj.optJSONArray("Background")
                    var bgLine: LyricLine? = null
                    if (bgArray != null && bgArray.length() > 0) {
                        val bgObj = bgArray.getJSONObject(0)
                        val bgSyllables = bgObj.optJSONArray("Syllables")
                        if (bgSyllables != null && bgSyllables.length() > 0) {
                            val bgStartMs = (bgObj.optDouble("StartTime", 0.0) * 1000).toLong()
                            val bgEndMs = (bgObj.optDouble("EndTime", 0.0) * 1000).toLong()
                            val bgWords = mutableListOf<WordSync>()
                            val bgRawBuilder = StringBuilder()

                            for (k in 0 until bgSyllables.length()) {
                                val sObj = bgSyllables.getJSONObject(k)
                                val bText = sObj.optString("Text", "")
                                val bsStart = (sObj.optDouble("StartTime", 0.0) * 1000).toLong()
                                val bsEnd = (sObj.optDouble("EndTime", 0.0) * 1000).toLong()
                                val bPartOfWord = sObj.optBoolean("IsPartOfWord", false)
                                val bLastSyl = (k == bgSyllables.length() - 1)

                                bgRawBuilder.append(bText)
                                if (!bPartOfWord && !bLastSyl) {
                                    bgRawBuilder.append(" ")
                                }

                                bgWords.add(
                                    WordSync(
                                        text = bText,
                                        startTimeMs = bsStart,
                                        endTimeMs = bsEnd,
                                        trailingSpace = !bPartOfWord && !bLastSyl
                                    )
                                )
                            }

                            bgLine = LyricLine(
                                startTimeMs = bgStartMs,
                                endTimeMs = bgEndMs,
                                words = bgWords.clampWordOverlaps(bgEndMs),
                                rawText = bgRawBuilder.toString().trim(),
                                isBackground = true,
                                agentId = "bg"
                            )
                        }
                    }

                    val mainLine = LyricLine(
                        startTimeMs = lineStartMs,
                        endTimeMs = lineEndMs,
                        words = words.clampWordOverlaps(lineEndMs),
                        rawText = rawTextBuilder.toString().trim(),
                        agentId = if (isOpposite) "v2" else null,
                        translation = if (lineTrans.isNotEmpty()) lineTrans else null,
                        backgroundLine = bgLine
                    )
                    lines.add(mainLine)
                    if (bgLine != null) {
                        lines.add(bgLine)
                    }
                }
            } else if (syncType.equals("Line", ignoreCase = true)) {
                // Line-level sync
                val contentArray = body.optJSONArray("Content") ?: return null
                for (i in 0 until contentArray.length()) {
                    val lineObj = contentArray.getJSONObject(i)
                    val lineStartMs = (lineObj.optDouble("StartTime", 0.0) * 1000).toLong()
                    val lineEndMs = (lineObj.optDouble("EndTime", 0.0) * 1000).toLong()
                    val text = lineObj.optString("Text", "")
                    val isOpposite = lineObj.optBoolean("OppositeAligned", false)
                    val trans = lineObj.optString("TransliteratedText", "")

                    lines.add(
                        LyricLine(
                            startTimeMs = lineStartMs,
                            endTimeMs = lineEndMs,
                            rawText = text.trim(),
                            agentId = if (isOpposite) "v2" else null,
                            translation = if (trans.isNotEmpty()) trans else null
                        )
                    )
                }
            } else {
                // Static lyrics
                val linesArray = body.optJSONArray("Lines") ?: return null
                for (i in 0 until linesArray.length()) {
                    val lObj = linesArray.getJSONObject(i)
                    val text = lObj.optString("Text", "")
                    lines.add(
                        LyricLine(
                            startTimeMs = 0L,
                            endTimeMs = 0L,
                            rawText = text.trim()
                        )
                    )
                }
            }

            if (lines.isEmpty()) return null

            val isWordSynced = syncType.equals("Syllable", ignoreCase = true)
            val track = LyricTrack(
                isWordSynced = isWordSynced,
                lines = lines.sortedBy { it.startTimeMs },
                source = "Spicy Lyrics ($providerName)",
                attribution = attribution
            )

            if (expectedDurationMs > 0) {
                val validation = LyricsMatchVerifier.validateLyricTrackTimeline(track, expectedDurationMs)
                if (!validation.isValid) {
                    DebugLog.w(TAG, "SpicyLyrics timeline validation rejected: ${(validation as LyricsMatchVerifier.LyricValidationResult.Rejected).reason}")
                    return null
                }
            }

            track
        } catch (e: Exception) {
            DebugLog.e(TAG, "Failed parsing SpicyLyrics JSON: ${e.message}")
            null
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Lrcmux Multi-Provider Aggregator (https://lrcmux.dev/docs)
    // ──────────────────────────────────────────────────────────────────────
    private fun fetchFromLrcmux(
        artist: String,
        title: String,
        album: String = "",
        isrc: String = "",
        durationMs: Int = 0,
        requireWordSync: Boolean = true
    ): LyricTrack? {
        val cleanTitle = cleanTrackTitle(title)
        val level = if (requireWordSync) "word" else "line"
        val urlBuilder = StringBuilder("https://api.lrcmux.dev/get?")
            .append("artist=").append(Uri.encode(artist))
            .append("&title=").append(Uri.encode(cleanTitle))
            .append("&level=").append(level)
            .append("&strict=true")
            .append("&format=json")

        if (album.isNotEmpty()) urlBuilder.append("&album=").append(Uri.encode(album))
        if (isrc.isNotEmpty()) urlBuilder.append("&isrc=").append(Uri.encode(isrc))
        if (durationMs > 0) urlBuilder.append("&duration=").append(durationMs / 1000)

        val fullUrl = urlBuilder.toString()
        DebugLog.d(TAG, "LRCMUX Request URL: $fullUrl (requireWordSync=$requireWordSync)")

        val req = Request.Builder()
            .url(fullUrl)
            .header("User-Agent", "SpotifyTablet/1.0 (Android)")
            .header("Accept", "application/json")
            .build()

        return try {
            httpClient.newCall(req).execute().use { resp ->
                DebugLog.d(TAG, "LRCMUX HTTP Status: ${resp.code}")
                if (resp.code == 429 || resp.code in 500..599) {
                    tripCircuit("lrcmux")
                    return@use null
                }
                if (!resp.isSuccessful) return@use null
                val bodyStr = resp.body?.string() ?: return@use null
                val sourceHeader = resp.header("X-Source") ?: "lrcmux"
                val syncLevelHeader = resp.header("X-Sync-Level") ?: ""

                // Validate meta.level, track metadata and duration tolerance (+/- 6s) if provided in metadata
                try {
                    val root = JSONObject(bodyStr)
                    val metaObj = root.optJSONObject("meta")
                    val syncLevel = metaObj?.optString("level", syncLevelHeader) ?: syncLevelHeader
                    if (requireWordSync && syncLevel.isNotEmpty() && !syncLevel.equals("word", ignoreCase = true)) {
                        DebugLog.w(TAG, "LRCMUX returned level='$syncLevel' instead of 'word'. Rejecting for word-sync tier.")
                        return@use null
                    }

                    val trackObj = root.optJSONObject("track")
                    if (trackObj != null) {
                        val metaTitle = trackObj.optString("title", trackObj.optString("name", ""))
                        val metaArtist = trackObj.optString("artist", "")
                        if (metaTitle.isNotEmpty() && !LyricsMatchVerifier.isTitleMatch(cleanTitle, metaTitle)) {
                            DebugLog.w(TAG, "LRCMUX Title mismatch rejected! Requested '$cleanTitle', got '$metaTitle'")
                            return@use null
                        }
                        if (metaArtist.isNotEmpty() && !LyricsMatchVerifier.isArtistMatch(artist, metaArtist)) {
                            DebugLog.w(TAG, "LRCMUX Artist mismatch rejected! Requested '$artist', got '$metaArtist'")
                            return@use null
                        }
                        val returnedDurSec = trackObj.optDouble("duration", 0.0)
                        if (durationMs > 0 && returnedDurSec > 0.0) {
                            if (!LyricsMatchVerifier.isDurationMatch(durationMs, returnedDurSec, 6.0)) {
                                DebugLog.w(TAG, "LRCMUX Duration mismatch rejected! expected=${durationMs / 1000.0}s returned=${returnedDurSec}s")
                                return@use null
                            }
                        }
                    }
                } catch (_: Exception) {}

                EnhancedLrcParser.parse(bodyStr)?.let { parsed ->
                    val validation = LyricsMatchVerifier.validateLyricTrackTimeline(parsed, durationMs)
                    if (!validation.isValid) {
                        DebugLog.w(TAG, "LRCMUX parsed lyrics failed timeline validation: ${(validation as LyricsMatchVerifier.LyricValidationResult.Rejected).reason}")
                        return@use null
                    }
                    val isWordLevel = parsed.isWordSynced || syncLevelHeader.equals("word", ignoreCase = true)
                    val preview = parsed.lines.take(3).joinToString(" | ") { it.rawText }
                    DebugLog.d(TAG, "LRCMUX Synced Lyrics Loaded: ${parsed.lines.size} lines from $sourceHeader, wordSynced=$isWordLevel. Preview: '$preview'")
                    parsed.copy(
                        isWordSynced = isWordLevel,
                        source = "lrcmux ($sourceHeader)"
                    )
                }
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "Lrcmux Exception: ${e.message}")
            null
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // LRCLIB & Jellyfin
    // ──────────────────────────────────────────────────────────────────────
    private fun fetchFromLrclib(artist: String, title: String, album: String, durationMs: Int): LyricTrack? {
        val cleanTitle = cleanTrackTitle(title)
        val urlBuilder = StringBuilder("https://lrclib.net/api/get?artist_name=")
            .append(Uri.encode(artist)).append("&track_name=").append(Uri.encode(cleanTitle))
        if (album.isNotEmpty()) urlBuilder.append("&album_name=").append(Uri.encode(album))
        if (durationMs > 0) urlBuilder.append("&duration=").append(durationMs / 1000)

        val directUrl = urlBuilder.toString()
        DebugLog.d(TAG, "LRCLIB Request URL: $directUrl")

        val req = Request.Builder().url(directUrl).header("User-Agent", "SpotifyTablet/1.0").build()
        val body = try {
            httpClient.newCall(req).execute().use { resp ->
                DebugLog.d(TAG, "LRCLIB Direct HTTP Status: ${resp.code}")
                if (resp.isSuccessful && resp.body != null) resp.body!!.string() else null
            }
        } catch (e: Exception) {
            DebugLog.w(TAG, "LRCLIB Direct network error: ${e.message}")
            null
        }

        if (body == null) {
            val searchUrl = "https://lrclib.net/api/search?artist_name=${Uri.encode(artist)}&track_name=${Uri.encode(cleanTitle)}"
            DebugLog.d(TAG, "LRCLIB Search Request URL: $searchUrl")
            val searchReq = Request.Builder().url(searchUrl).header("User-Agent", "SpotifyTablet/1.0").build()
            val searchBody = try {
                httpClient.newCall(searchReq).execute().use { resp ->
                    DebugLog.d(TAG, "LRCLIB Search HTTP Status: ${resp.code}")
                    if (resp.isSuccessful && resp.body != null) resp.body!!.string() else null
                }
            } catch (e: Exception) {
                DebugLog.w(TAG, "LRCLIB Search network error: ${e.message}")
                null
            }

            if (searchBody != null) {
                try {
                    val array = JSONArray(searchBody)
                    val candidates = mutableListOf<LyricsMatchVerifier.LrclibCandidate>()
                    for (i in 0 until array.length()) {
                        val item = array.getJSONObject(i)
                        candidates.add(
                            LyricsMatchVerifier.LrclibCandidate(
                                id = item.optLong("id", 0L),
                                trackName = item.optString("trackName", ""),
                                artistName = item.optString("artistName", ""),
                                albumName = item.optString("albumName", ""),
                                durationSec = item.optDouble("duration", 0.0),
                                instrumental = item.optBoolean("instrumental", false),
                                syncedLyrics = item.optString("syncedLyrics", ""),
                                plainLyrics = item.optString("plainLyrics", "")
                            )
                        )
                    }

                    val bestMatch = LyricsMatchVerifier.findBestLrclibMatch(
                        candidates = candidates,
                        expectedArtist = artist,
                        expectedTitle = cleanTitle,
                        expectedDurationMs = durationMs
                    )

                    if (bestMatch != null) {
                        val lyricText = if (bestMatch.syncedLyrics.isNotEmpty()) bestMatch.syncedLyrics else bestMatch.plainLyrics
                        val parsed = EnhancedLrcParser.parse(lyricText)?.copy(source = "LRCLIB Search")
                        if (parsed != null && LyricsMatchVerifier.validateLyricTrackTimeline(parsed, durationMs).isValid) {
                            return parsed
                        }
                    }
                } catch (_: Exception) {}
            }
            return null
        }

        return try {
            val json = JSONObject(body)
            val isInstrumental = json.optBoolean("instrumental", false)
            if (isInstrumental) {
                DebugLog.d(TAG, "LRCLIB Direct: track is marked instrumental")
                return null
            }

            val retTrack = json.optString("trackName", "")
            val retArtist = json.optString("artistName", "")
            val retDuration = json.optDouble("duration", 0.0)

            if (retTrack.isNotEmpty() && !LyricsMatchVerifier.isTitleMatch(cleanTitle, retTrack)) {
                DebugLog.w(TAG, "LRCLIB Direct Title mismatch rejected! Requested '$cleanTitle', got '$retTrack'")
                return null
            }
            if (retArtist.isNotEmpty() && !LyricsMatchVerifier.isArtistMatch(artist, retArtist)) {
                DebugLog.w(TAG, "LRCLIB Direct Artist mismatch rejected! Requested '$artist', got '$retArtist'")
                return null
            }
            if (durationMs > 0 && retDuration > 0.0 && !LyricsMatchVerifier.isDurationMatch(durationMs, retDuration, 8.0)) {
                DebugLog.w(TAG, "LRCLIB Direct Duration mismatch rejected! Requested ${durationMs / 1000}s, got ${retDuration}s")
                return null
            }

            val synced = json.optString("syncedLyrics", "")
            if (synced.isNotEmpty()) {
                val sampleLines = synced.lines().take(4).joinToString(" | ")
                DebugLog.d(TAG, "LRCLIB Synced Lyrics Preview: $sampleLines")
                EnhancedLrcParser.parse(synced)?.copy(source = "LRCLIB Direct")?.let { track ->
                    if (LyricsMatchVerifier.validateLyricTrackTimeline(track, durationMs).isValid) {
                        return track
                    }
                }
            }
            val plain = json.optString("plainLyrics", "")
            if (plain.isNotEmpty()) {
                EnhancedLrcParser.parse(plain)?.copy(source = "LRCLIB Direct (Plain)")?.let { track ->
                    if (LyricsMatchVerifier.validateLyricTrackTimeline(track, durationMs).isValid) {
                        return track
                    }
                }
            }
            null
        } catch (_: Exception) {
            EnhancedLrcParser.parse(body)?.copy(source = "LRCLIB Direct")?.let { track ->
                if (LyricsMatchVerifier.validateLyricTrackTimeline(track, durationMs).isValid) track else null
            }
        }
    }

    private fun fetchJellyfinLyrics(trackId: String, jellyfinUrl: String, apiKey: String): LyricTrack? {
        val baseUrl = if (jellyfinUrl.endsWith("/")) jellyfinUrl.dropLast(1) else jellyfinUrl
        val url = "$baseUrl/Audio/$trackId/Lyrics?api_key=$apiKey"
        val req = Request.Builder().url(url).header("Authorization", "MediaBrowser Token=\"$apiKey\"").build()


        return try {
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val bodyStr = resp.body?.string() ?: return@use null
                    EnhancedLrcParser.parse(bodyStr)?.copy(source = "Jellyfin Local")
                } else null
            }
        } catch (_: Exception) { null }
    }

    private fun LyricTrack.synthesizeWordsIfMissing(): LyricTrack {
        if (this.isWordSynced || lines.isEmpty()) return this
        val updatedLines = lines.mapIndexed { idx, line ->
            if (line.words.isNotEmpty() && !line.isSynthesized) return@mapIndexed line
            val wordsList = line.rawText.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (wordsList.isEmpty()) return@mapIndexed line

            val nextStart = if (idx + 1 < lines.size) lines[idx + 1].startTimeMs else line.startTimeMs + 4000L
            val effectiveEnd = if (line.endTimeMs > line.startTimeMs) line.endTimeMs else nextStart
            val duration = (effectiveEnd - line.startTimeMs).coerceIn(1000L, 10000L)
            val lineEnd = line.startTimeMs + duration
            val totalChars = wordsList.sumOf { it.length }.coerceAtLeast(1)
            var cursor = line.startTimeMs
            val words = mutableListOf<WordSync>()

            for ((wIdx, w) in wordsList.withIndex()) {
                val dur = (duration * w.length / totalChars).coerceAtLeast(120L)
                words.add(WordSync(text = w, startTimeMs = cursor, endTimeMs = cursor + dur, trailingSpace = wIdx < wordsList.lastIndex))
                cursor += dur
            }
            line.copy(endTimeMs = lineEnd, words = words, isSynthesized = true)
        }
        return LyricTrack(isWordSynced = true, lines = updatedLines, source = "${this.source} (Synthesized)")
    }

    private fun cleanTrackTitle(title: String): String {
        return LyricsMatchVerifier.cleanTrackTitle(title)
    }

    fun clearCache() {
        trackCache.evictAll()
    }
}
