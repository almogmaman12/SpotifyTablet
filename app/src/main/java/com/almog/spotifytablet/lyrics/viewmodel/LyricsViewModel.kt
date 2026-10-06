package com.almog.spotifytablet.lyrics.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.almog.spotifytablet.DebugLog
import com.almog.spotifytablet.lyrics.model.LyricLine
import com.almog.spotifytablet.lyrics.model.LyricTrack
import com.almog.spotifytablet.lyrics.model.withSynthesizedWordSync
import com.almog.spotifytablet.lyrics.repository.LyricsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Anchor for smooth frame-driven position interpolation in Compose.
 * Set rarely (on song change, play/pause, seek) — NOT on every tick.
 * Compose derives currentPositionMs via SystemClock.elapsedRealtime() inside
 * a withFrameNanos loop, completely decoupled from Handler jitter.
 */
data class PlaybackAnchor(
    val positionMs: Long = 0L,
    val anchorRealtimeMs: Long = android.os.SystemClock.elapsedRealtime(),
    val isPlaying: Boolean = false,
    val speed: Float = 1f
)

/**
 * Consolidated immutable UI state for synchronized lyrics rendering.
 */
data class LyricsUiState(
    val track: LyricTrack? = null,
    val currentPositionMs: Long = 0L,
    val activeLineIndex: Int = -1,
    val anchor: PlaybackAnchor = PlaybackAnchor(),
    val isAnimationEnabled: Boolean = true,
    val isLoading: Boolean = false,
    val isUserScrolling: Boolean = false
)

/**
 * Finds the active lyric line index for a given position.
 * Optimized with an optional [hintIndex] for temporal locality (checks previous line and
 * next line in O(1) before falling back to full search), eliminating per-frame loops.
 */
fun findActiveLineIndex(lines: List<LyricLine>, positionMs: Long, hintIndex: Int = -1): Int {
    if (lines.isEmpty()) return -1

    // Fast-path 1: Check hintIndex (same line as previous frame, covers ~95% of frames)
    if (hintIndex in lines.indices) {
        val line = lines[hintIndex]
        val nextStart = if (hintIndex + 1 < lines.size) {
            lines[hintIndex + 1].startTimeMs
        } else {
            line.endTimeMs.coerceAtLeast(line.startTimeMs + 1000L) + 2500L
        }
        if (positionMs in line.startTimeMs until nextStart && !line.isBackground) {
            return hintIndex
        }
        // Fast-path 2: Check hintIndex + 1 (natural step to next line, covers ~4.9% of frames)
        val nextIndex = hintIndex + 1
        if (nextIndex in lines.indices) {
            val nextLine = lines[nextIndex]
            val afterNextStart = if (nextIndex + 1 < lines.size) {
                lines[nextIndex + 1].startTimeMs
            } else {
                nextLine.endTimeMs.coerceAtLeast(nextLine.startTimeMs + 1000L) + 2500L
            }
            if (positionMs in nextLine.startTimeMs until afterNextStart && !nextLine.isBackground) {
                return nextIndex
            }
        }
    }

    var foundIndex = -1
    for (i in lines.indices) {
        val line = lines[i]
        val nextStart = if (i + 1 < lines.size) {
            lines[i + 1].startTimeMs
        } else {
            line.endTimeMs.coerceAtLeast(line.startTimeMs + 1000L) + 2500L
        }
        if (positionMs in line.startTimeMs until nextStart) {
            if (!line.isBackground || foundIndex == -1) {
                foundIndex = i
                if (!line.isBackground) break
            }
        }
    }
    if (foundIndex == -1) {
        val lastLine = lines.lastOrNull()
        if (lastLine != null && positionMs >= lastLine.startTimeMs) {
            val outroThreshold = lastLine.endTimeMs.coerceAtLeast(lastLine.startTimeMs + 1000L) + 2500L
            if (positionMs < outroThreshold) foundIndex = lines.lastIndex
        }
    }
    return foundIndex
}

/**
 * State engine for real-time synchronized lyrics rendering.
 *
 * Exposes a single unified StateFlow<LyricsUiState>.
 */
class LyricsViewModel(
    private val repository: LyricsRepository = LyricsRepository()
) : ViewModel() {

    companion object {
        private const val TAG = "LyricsViewModel"
    }

    private val _uiState = MutableStateFlow(LyricsUiState())
    val uiState: StateFlow<LyricsUiState> = _uiState.asStateFlow()

    // Backward compatibility accessors for existing consumers
    val lyricTrack: StateFlow<LyricTrack?> get() = MutableStateFlow(_uiState.value.track)
    val currentPositionMs: StateFlow<Long> get() = MutableStateFlow(_uiState.value.currentPositionMs)
    val activeLineIndex: StateFlow<Int> get() = MutableStateFlow(_uiState.value.activeLineIndex)
    val isAnimationEnabled: StateFlow<Boolean> get() = MutableStateFlow(_uiState.value.isAnimationEnabled)
    val isLoading: StateFlow<Boolean> get() = MutableStateFlow(_uiState.value.isLoading)

    private var currentFetchJob: Job? = null
    private var currentSongKey: String = ""

    fun setAnimationEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(isAnimationEnabled = enabled)
    }

    fun setUserScrolling(isScrolling: Boolean) {
        if (_uiState.value.isUserScrolling != isScrolling) {
            _uiState.value = _uiState.value.copy(isUserScrolling = isScrolling)
        }
    }

    /**
     * Sets a playback anchor. Call on: new song, play/pause, seek — NOT on every tick.
     * Compose derives the per-frame position from this anchor via a withFrameNanos loop,
     * decoupled from Handler jitter. Calling it occasionally (e.g. every 250ms for
     * drift correction) is fine — Compose interpolates smoothly between anchors.
     */
    fun setPlaybackAnchor(positionMs: Long, isPlaying: Boolean, speed: Float = 1f) {
        val currentState = _uiState.value
        val prev = currentState.anchor

        // Skip redundant emissions when paused at the same position
        if (!isPlaying && !prev.isPlaying && prev.positionMs == positionMs && prev.speed == speed) {
            return
        }

        // Skip near-identical re-anchors: if play state is unchanged and the predicted
        // position barely drifted from what the current anchor already implies, don't
        // emit — avoids relaunching the Compose frame-loop for no reason.
        if (prev.isPlaying == isPlaying && isPlaying) {
            val predictedMs = prev.positionMs +
                ((android.os.SystemClock.elapsedRealtime() - prev.anchorRealtimeMs) * prev.speed).toLong()
            if (Math.abs(predictedMs - positionMs) < 80L && prev.speed == speed) {
                return
            }
        }

        val newAnchor = PlaybackAnchor(
            positionMs = positionMs,
            anchorRealtimeMs = android.os.SystemClock.elapsedRealtime(),
            isPlaying = isPlaying,
            speed = speed
        )
        // Also keep activeLineIndex updated so non-Compose consumers stay in sync
        val foundIndex = currentState.track?.let { findActiveLineIndex(it.lines, positionMs, currentState.activeLineIndex) }
            ?: currentState.activeLineIndex
        _uiState.value = currentState.copy(
            anchor = newAnchor,
            currentPositionMs = positionMs,
            activeLineIndex = foundIndex
        )
    }

    /**
     * Updates the playback position in milliseconds.
     * High-frequency call (~60Hz); delegates to setPlaybackAnchor.
     * @deprecated Prefer setPlaybackAnchor for new call sites.
     */
    fun updatePosition(positionMs: Long) {
        val currentState = _uiState.value
        val track = currentState.track

        if (track == null || track.lines.isEmpty()) {
            if (currentState.currentPositionMs != positionMs || currentState.activeLineIndex != -1) {
                _uiState.value = currentState.copy(
                    currentPositionMs = positionMs,
                    activeLineIndex = -1,
                    anchor = PlaybackAnchor(positionMs, android.os.SystemClock.elapsedRealtime(), true, 1f)
                )
            }
            return
        }

        // Throttle negligible sub-8ms jitter unless activeLineIndex changed (ticker is now 16ms)
        val posDiff = Math.abs(positionMs - currentState.currentPositionMs)
        if (findActiveLineIndex(track.lines, positionMs) == currentState.activeLineIndex && posDiff < 8L) {
            return
        }

        setPlaybackAnchor(positionMs, isPlaying = true, speed = 1f)
    }

    /**
     * Loads lyrics asynchronously for the given track.
     */
    fun loadLyrics(
        artist: String,
        title: String,
        album: String = "",
        durationMs: Int = 0,
        mediaSource: String = "auto",
        trackId: String = "",
        isrc: String = "",
        jellyfinUrl: String = "",
        jellyfinApiKey: String = ""
    ) {
        val songKey = "$artist|$title|$trackId"
        if (songKey == currentSongKey && _uiState.value.track != null) {
            return
        }

        currentSongKey = songKey
        currentFetchJob?.cancel()

        if (artist.isBlank() || title.isBlank()) {
            clearLyrics()
            return
        }

        _uiState.value = _uiState.value.copy(isLoading = true)
        currentFetchJob = viewModelScope.launch {
            try {
                DebugLog.d(TAG, "Fetching lyrics for '$title' by '$artist'")
                val track = repository.fetchLyrics(
                    artist = artist,
                    title = title,
                    album = album,
                    durationMs = durationMs,
                    mediaSource = mediaSource,
                    trackId = trackId,
                    isrc = isrc,
                    jellyfinUrl = jellyfinUrl,
                    jellyfinApiKey = jellyfinApiKey
                )
                val displayTrack = track?.withSynthesizedWordSync()
                if (displayTrack != null) {
                    val preview = displayTrack.lines.take(3).joinToString(" / ") { it.rawText }
                    DebugLog.d(TAG, "Lyrics loaded successfully: ${displayTrack.lines.size} lines from ${displayTrack.source}, wordSynced=${displayTrack.isWordSynced}. Preview: '$preview'")
                } else {
                    DebugLog.w(TAG, "Lyrics returned null from all providers for '$title'")
                }
                _uiState.value = _uiState.value.copy(
                    track = displayTrack,
                    activeLineIndex = -1,
                    isLoading = false
                )
                updatePosition(_uiState.value.currentPositionMs)
            } catch (e: Exception) {
                DebugLog.e(TAG, "Error loading lyrics: ${e.message}")
                _uiState.value = _uiState.value.copy(
                    track = null,
                    isLoading = false
                )
            }
        }
    }

    /**
     * Pre-fetches lyrics for an upcoming track in the queue.
     */
    fun prefetchQueueTrack(
        artist: String,
        title: String,
        album: String = "",
        durationMs: Int = 0,
        isrc: String = ""
    ) {
        viewModelScope.launch {
            try {
                repository.preloadUpcomingLyrics(artist, title, album, isrc, durationMs)
            } catch (_: Exception) {}
        }
    }

    fun clearLyrics() {
        currentSongKey = ""
        currentFetchJob?.cancel()
        _uiState.value = _uiState.value.copy(
            track = null,
            activeLineIndex = -1,
            isLoading = false
        )
    }

    fun clearCache() {
        repository.clearCache()
    }

    @androidx.annotation.VisibleForTesting
    fun setLoadedTrackForTesting(track: LyricTrack?) {
        _uiState.value = _uiState.value.copy(
            track = track,
            activeLineIndex = -1,
            isLoading = false
        )
    }
}
