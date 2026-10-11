package com.almog.spotifytablet.lyrics.mobile.network.data

import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LyricsLookupDiagnostic(
    val title: String,
    val recordedAt: Instant,
    val durationMs: Long,
    val outcome: String,
    val attempts: List<ProviderAttempt>,
)

@Singleton
class LyricsDiagnostics @Inject constructor() {
    private val _recent = MutableStateFlow<List<LyricsLookupDiagnostic>>(emptyList())
    val recent = _recent.asStateFlow()

    fun record(entry: LyricsLookupDiagnostic) {
        _recent.value = (listOf(entry) + _recent.value).take(30)
    }

    /** A shareable report deliberately omits song metadata, lyric text, URLs, and credentials. */
    fun safeReport(): String = buildString {
        appendLine("Spicy Lyrics Mobile lyrics diagnostics")
        _recent.value.forEach { entry ->
            appendLine("${entry.recordedAt} ${entry.outcome} ${entry.durationMs}ms")
            entry.attempts.forEach { attempt ->
                appendLine("  ${attempt.sourceId}: ${attempt.outcome} ${attempt.quality} ${attempt.failureCategory ?: ""}")
            }
        }
    }
}
