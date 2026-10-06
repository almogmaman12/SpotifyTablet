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

        @Volatile
        @JvmStatic
        var defaultDiskCacheDir: File? = null
    }

    private val trackCache = LruCache<String, LyricTrack>(60)
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
            val files = subDir.listFiles { f -> f.isFile && f.extension == "json" } ?: return
            if (files.size <= maxFiles) {
                val totalSize = files.sumOf { it.length() }
                if (totalSize <= maxSizeBytes) return
            }

            // Prune oldest accessed/modified files first
            val sortedFiles = files.sortedBy { it.lastModified() }
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
                    wordsArray.put(wObj)
                }
                lObj.put("words", wordsArray)
                linesArray.put(lObj)
            }
            root.put("lines", linesArray)
            file.writeText(root.toString())

            // Trigger LRU pruning check in background
            file.parentFile?.let { pruneDiskCacheIfNeeded(it) }
        } catch (e: Exception) {
            DebugLog.e(TAG, "Failed saving track to disk cache: ${e.message}")
        }
    }

    private fun loadTrackFromDisk(cacheKey: String, expectedDurationMs: Int = 0): LyricTrack? {
        return try {
            val file = getDiskCacheFile(cacheKey) ?: return null
            if (!file.exists()) return null
            file.setLastModified(System.currentTimeMillis())
            val content = file.readText()
            val root = JSONObject(content)
            val isWordSynced = root.optBoolean("isWordSynced", false)
            val source = root.optString("source", "Disk Cache")
            val bpm = if (root.has("bpm")) root.getDouble("bpm").toFloat() else null

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
                            trailingSpace = wObj.optBoolean("trailingSpace", true)
                        )
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
                        translation = if (lObj.has("translation")) lObj.optString("translation") else null
                    )
                )
            }
            val loadedTrack = LyricTrack(isWordSynced = isWordSynced, lines = lines, source = source, bpm = bpm)
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

        // 1. Lrcmux Word-Sync Mirror (Native Word Timestamps)
        if (!isCircuitOpen("lrcmux")) {
            DebugLog.d(TAG, "[1/3] Trying Lrcmux Word-Sync Mirror")
            fetchFromLrcmux(artist, title, album, isrc, durationMs, requireWordSync = true)?.let { track ->
                if (track.isWordSynced) {
                    DebugLog.d(TAG, "✓ LRCMUX (WordSync): ${track.lines.size} lines, source=${track.source}")
                    trackCache.put(cacheKey, track)
                    saveTrackToDisk(cacheKey, track)
                    return@withContext track
                }
            }
        }

        // ── FALLBACK TIERS (Line-level synced lyrics + single synthesized pass) ──

        // 2. LRCLIB (Accurate metadata and synced community lyrics)
        DebugLog.d(TAG, "[2/3] Trying LRCLIB Fallback (https://lrclib.net/api/get?artist_name=${Uri.encode(artist)}&track_name=${Uri.encode(cleanTitle)})")
        fetchFromLrclib(artist, title, album, durationMs)?.let { track ->
            val synthesized = track.synthesizeWordsIfMissing()
            DebugLog.d(TAG, "✓ LRCLIB: ${synthesized.lines.size} lines, wordSynced=${synthesized.isWordSynced}")
            trackCache.put(cacheKey, synthesized)
            saveTrackToDisk(cacheKey, synthesized)
            return@withContext synthesized
        }

        // 3. Jellyfin Local Storage
        if (mediaSource == "jellyfin" && trackId.isNotEmpty() && jellyfinUrl.isNotEmpty()) {
            DebugLog.d(TAG, "[3/3] Trying Jellyfin Local")
            fetchJellyfinLyrics(trackId, jellyfinUrl, jellyfinApiKey)?.let { track ->
                val synthesized = track.synthesizeWordsIfMissing()
                DebugLog.d(TAG, "✓ JELLYFIN: ${synthesized.lines.size} lines, wordSynced=${synthesized.isWordSynced}")
                trackCache.put(cacheKey, synthesized)
                saveTrackToDisk(cacheKey, synthesized)
                return@withContext synthesized
            }
        }

        // 4. Lrcmux Line-Level Fallback
        if (!isCircuitOpen("lrcmux")) {
            DebugLog.d(TAG, "Trying Lrcmux Line-Level Fallback")
            fetchFromLrcmux(artist, title, album, isrc, durationMs, requireWordSync = false)?.let { lineTrack ->
                val synthesized = lineTrack.synthesizeWordsIfMissing()
                DebugLog.d(TAG, "✓ LRCMUX LINE FALLBACK (${lineTrack.source}): ${synthesized.lines.size} lines, wordSynced=${synthesized.isWordSynced}")
                trackCache.put(cacheKey, synthesized)
                saveTrackToDisk(cacheKey, synthesized)
                return@withContext synthesized
            }
        }

        DebugLog.w(TAG, "=== ALL PROVIDERS FAILED for '$cleanTitle' by '$artist' ===")
        null
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
