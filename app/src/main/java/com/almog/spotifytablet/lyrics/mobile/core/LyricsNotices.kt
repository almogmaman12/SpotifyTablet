package com.almog.spotifytablet.lyrics.mobile.core

import com.almog.spotifytablet.lyrics.mobile.network.data.LyricsBlends
import com.almog.spotifytablet.lyrics.mobile.network.data.ProviderAttempt
import com.almog.spotifytablet.lyrics.mobile.network.data.ProviderAttemptOutcome
import com.almog.spotifytablet.lyrics.mobile.network.data.ProviderFailureCategory

/**
 * The notice shown instead of lyrics, worded from what each source actually said. One source
 * failing while the others simply had nothing is a "no lyrics" answer that names the failure,
 * not "sources are unavailable".
 */
internal object LyricsNotices {
    /** The player didn't give enough to look anything up. */
    val missingMetadata = LyricsState.Error(
        "We could not access the info for this song",
        "The player didn't report a title and artist",
    )

    fun lookupFailed(error: Throwable) = LyricsState.Error(
        "An unknown error happened",
        error.message?.takeIf(String::isNotBlank) ?: error::class.simpleName,
    )

    fun renderFailed(source: String, error: Throwable) = LyricsState.Error(
        "These lyrics couldn't be displayed",
        "$source: ${error.message ?: error::class.simpleName}",
    )

    /** For a finished lookup that picked nothing. [nameOf] turns a source id into its display name. */
    fun noLyrics(attempts: List<ProviderAttempt>, nameOf: (String) -> String): LyricsState.Error {
        // A blend is built from other sources' answers, not asked itself, so it isn't counted.
        val asked = attempts.filter {
            it.outcome != ProviderAttemptOutcome.DISABLED && it.outcome != ProviderAttemptOutcome.SKIPPED &&
                LyricsBlends.byId(it.sourceId) == null
        }
        if (asked.isEmpty()) return LyricsState.Error("No lyrics sources are turned on", "Turn one on in Settings")

        val failed = asked.filter { it.outcome in FAILURES }
        val answered = asked.size - failed.size
        val failures = failed.joinToString { "${nameOf(it.sourceId)} (${reason(it)})" }
        val sources = plural(asked.size, "source")

        return when {
            failed.isEmpty() -> LyricsState.Error("We don't have any lyrics for this song", "Checked $sources, none had it")
            answered > 0 -> LyricsState.Error(
                "We don't have any lyrics for this song",
                "${plural(answered, "source")} had none. Couldn't check $failures",
            )
            failed.all { it.failureCategory == ProviderFailureCategory.NETWORK || it.failureCategory == ProviderFailureCategory.TIMEOUT } ->
                LyricsState.Error("Please go online to enjoy your lyrics experience!", "Couldn't reach $failures")
            else -> LyricsState.Error("Couldn't reach any lyrics source", failures)
        }
    }

    /** A short reason with the HTTP code when there was one, e.g. "HTTP 503" or "timed out". */
    internal fun reason(attempt: ProviderAttempt): String {
        val message = attempt.message.orEmpty()
        HTTP_CODE.find(message)?.let { return "HTTP ${it.groupValues[1]}" }
        // Most providers file every IOException as NETWORK; a timeout says so in its message.
        if (TIMEOUT.containsMatchIn(message)) return "timed out"
        return when (attempt.outcome) {
            ProviderAttemptOutcome.COOLING_DOWN -> "rate limited"
            ProviderAttemptOutcome.QUEUED -> "queued"
            ProviderAttemptOutcome.MALFORMED_HIT -> "unreadable lyrics"
            ProviderAttemptOutcome.PENDING -> "no answer"
            else -> when (attempt.failureCategory) {
                ProviderFailureCategory.NETWORK -> "no connection"
                ProviderFailureCategory.TIMEOUT -> "timed out"
                ProviderFailureCategory.AUTHENTICATION -> "key rejected"
                ProviderFailureCategory.CLIENT_REQUEST -> "bad request"
                ProviderFailureCategory.SERVER -> "server error"
                ProviderFailureCategory.MALFORMED_RESPONSE -> "unreadable response"
                ProviderFailureCategory.UNKNOWN, null -> "error"
            }
        }
    }

    private fun plural(count: Int, noun: String) = if (count == 1) "1 $noun" else "$count ${noun}s"

    private val FAILURES = setOf(
        ProviderAttemptOutcome.UNAVAILABLE,
        ProviderAttemptOutcome.MALFORMED_HIT,
        ProviderAttemptOutcome.COOLING_DOWN,
        ProviderAttemptOutcome.QUEUED,
        ProviderAttemptOutcome.PENDING,
    )
    private val HTTP_CODE = Regex("""HTTP (\d{3})""")
    private val TIMEOUT = Regex("timeout|timed out", RegexOption.IGNORE_CASE)
}
