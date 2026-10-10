package com.almog.spotifytablet.lyrics.mobile.network.data

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** In-memory cooldown gate. A Room-backed implementation can replace it without provider changes. */
@Singleton
class ProviderCooldownTracker @Inject constructor() {
    private data class Rest(val until: Instant, val reason: String?)
    private val deadlines = ConcurrentHashMap<String, Rest>()

    fun record(sourceId: String, retryAt: Instant, reason: String? = null) {
        deadlines.merge(sourceId, Rest(retryAt, reason)) { current, proposed ->
            if (proposed.until > current.until) proposed else current
        }
    }

    fun retryAt(sourceId: String, now: Instant = Instant.now()): Instant? {
        val rest = deadlines[sourceId] ?: return null
        if (!rest.until.isAfter(now)) {
            deadlines.remove(sourceId, rest)
            return null
        }
        return rest.until
    }

    /** Why [sourceId] is resting, when its refusal said. */
    fun reason(sourceId: String): String? = deadlines[sourceId]?.reason

    fun clear(sourceId: String) {
        deadlines.remove(sourceId)
    }

    fun clearAll() {
        deadlines.clear()
    }

    fun activeDeadlines(now: Instant = Instant.now()): Map<String, Instant> =
        deadlines.mapValues { it.value.until }.filterValues { it.isAfter(now) }
}

object RetryAfterParser {
    private val defaultDelay = Duration.ofSeconds(60)
    private val minimumDelay = Duration.ofSeconds(1)
    private val maximumDelay = Duration.ofHours(24)

    /** Supports RFC delta-seconds and RFC 1123 HTTP dates, with bounded fallback. */
    fun deadline(
        value: String?,
        now: Instant = Instant.now(Clock.systemUTC()),
    ): Instant {
        val requestedDelay = value
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { parseDelay(it, now) }
            ?: defaultDelay
        return now.plus(requestedDelay.coerceIn(minimumDelay, maximumDelay))
    }

    private fun parseDelay(value: String, now: Instant): Duration? {
        value.toLongOrNull()?.let { seconds ->
            return if (seconds < 0) minimumDelay else Duration.ofSeconds(seconds)
        }

        return try {
            Duration.between(
                now,
                ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant(),
            )
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun Duration.coerceIn(minimum: Duration, maximum: Duration): Duration = when {
        this < minimum -> minimum
        this > maximum -> maximum
        else -> this
    }
}
