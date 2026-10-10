package com.almog.spotifytablet.lyrics.mobile.network.data

import com.almog.spotifytablet.lyrics.mobile.models.LyricsType
import com.almog.spotifytablet.lyrics.mobile.parser.TtmlLyricsParser
import com.almog.spotifytablet.lyrics.mobile.network.data.blend.BlendDonors
import java.time.Instant

/** Metadata available when looking up lyrics for a local track. */
data class LyricsLookupRequest(
    val artist: String,
    val title: String,
    val album: String,
    val durationSeconds: Int,
    /** Manual/cache override; bypasses metadata matching when present. */
    val spotifyTrackId: String? = null,
)

enum class LyricsCapability {
    WORD_SYNC,
    LINE_SYNC,
    PLAIN_TEXT,
    TRANSLATION,
    TRANSLITERATION,
    CONTRIBUTOR_CREDITS,
}

enum class SourceReleaseChannel {
    RECOMMENDED,
    EXTENDED,
    EXPERIMENTAL,
}

enum class TransportRequirement {
    DIRECT_OK,
    DIRECT_OR_BROKER,
    BROKER_ONLY,
}

/** Stable metadata used for ordering, settings, diagnostics, and future migrations. */
data class LyricsSourceDescriptor(
    val id: String,
    val displayName: String,
    val defaultPriority: Int,
    val capabilities: Set<LyricsCapability>,
    val upstreamFamily: String = id,
    val transportRequirement: TransportRequirement = TransportRequirement.DIRECT_OK,
    val releaseChannel: SourceReleaseChannel = SourceReleaseChannel.RECOMMENDED,
    val defaultEnabled: Boolean = true,
    /**
     * Never asked: only a place in the order, where lyrics relayed from this origin rank
     * ([RemoteLyricsSource.rankByOrigin]). Switched off, they rank after every source.
     */
    val rankOnly: Boolean = false,
)

/**
 * Provider-neutral payload returned by an online lyrics service.
 *
 * TTML is first-class so word timing is never flattened to LRC. Attribution is
 * intentionally added only after the released Spicy API contract is mapped.
 */
data class RemoteLyricsPayload(
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
    val ttmlLyrics: String? = null,
    val sourceId: String? = null,
    val attribution: LyricsAttribution? = null,
) {
    fun isEmpty(): Boolean =
        plainLyrics.isNullOrBlank() && syncedLyrics.isNullOrBlank() && ttmlLyrics.isNullOrBlank()
}

data class LyricsContributor(
    val username: String,
    val profileUrl: String? = null,
    val avatarUrl: String? = null,
)

data class LyricsAttribution(
    val providerName: String,
    /** Original catalogue/community source when the provider syndicates several origins. */
    val originName: String? = null,
    val songwriters: List<String> = emptyList(),
    val maker: LyricsContributor? = null,
    val uploader: LyricsContributor? = null,
)

enum class ProviderFailureCategory {
    NETWORK,
    TIMEOUT,
    AUTHENTICATION,
    CLIENT_REQUEST,
    SERVER,
    MALFORMED_RESPONSE,
    UNKNOWN,
}

/** A provider miss is deliberately distinct from an unreachable provider. */
sealed interface ProviderResult {
    data class Hit(val payload: RemoteLyricsPayload) : ProviderResult
    data object Miss : ProviderResult
    data object NeedsMatch : ProviderResult
    /** Resting until [retryAt]; [reason] is the refusal that started it, when known. */
    data class CoolingDown(val retryAt: Instant, val reason: String? = null) : ProviderResult
    data class Queued(val retryAt: Instant? = null) : ProviderResult
    data class Unavailable(
        val category: ProviderFailureCategory,
        val message: String? = null,
        val retryable: Boolean = false,
    ) : ProviderResult
}

/** A single online source. Ordering is policy, not part of fetch behavior. */
interface RemoteLyricsProvider {
    val descriptor: LyricsSourceDescriptor

    suspend fun fetch(request: LyricsLookupRequest): ProviderResult

    /** Gets whatever every lookup needs first (a token, say) ready before the first song asks. */
    suspend fun warmUp() {}
}

/** Snapshot of source preferences for one lookup. */
data class RemoteLyricsPolicy(
    val sourceOrder: List<String> = emptyList(),
    val disabledSourceIds: Set<String> = emptySet(),
    /** Blends are off unless switched on; see [LyricsBlends]. */
    val enabledBlendIds: Set<String> = emptySet(),
)

enum class RemoteLyricsQuality(val rank: Int) {
    NONE(0),
    PLAIN(1),
    LINE_SYNCED(2),
    WORD_SYNCED(3),
}

/**
 * Measures the best usable representation in a provider response.
 *
 * TTML is parsed here instead of being trusted by file extension. A malformed or
 * empty TTML body can therefore fall back to a valid LRC/plain representation,
 * and cannot stop the provider chain as a fake word-synced hit.
 */
fun RemoteLyricsPayload.measuredQuality(): RemoteLyricsQuality {
    val ttmlQuality = parsedTtmlQuality()
    return when {
        ttmlQuality != RemoteLyricsQuality.NONE -> ttmlQuality
        !syncedLyrics.isNullOrBlank() -> RemoteLyricsQuality.LINE_SYNCED
        !plainLyrics.isNullOrBlank() -> RemoteLyricsQuality.PLAIN
        else -> RemoteLyricsQuality.NONE
    }
}

/**
 * True when these lyrics are only a note saying the song has no words ("纯音乐，请欣赏"). Taken as
 * lyrics, a note stamped across the song outranks every real answer further down the list, so it
 * counts as a miss. Reads the same representation [measuredQuality] ranks.
 */
fun RemoteLyricsPayload.isNoWordsNote(): Boolean {
    val lines = when {
        !ttmlLyrics.isNullOrBlank() -> TtmlLyricsParser.parse(ttmlLyrics.byteInputStream()).lines
            .map { line -> line.words.joinToString("") { it.text } }
            .ifEmpty { return false }
        !syncedLyrics.isNullOrBlank() -> syncedLyrics.lines().map { it.replace(LRC_STAMP, "") }
        else -> plainLyrics.orEmpty().lines()
    }
    // Nothing but stamps and "♪" (or an empty record) says as much as the note does.
    if (lines.none { line -> line.any(Char::isLetterOrDigit) }) return true
    return BlendDonors.isNoWordsNote(lines)
}

/**
 * These lyrics with synced text that has no real timing read as plain text. Some sources fill the
 * synced field with untimed text (QQ Music) or stamp every line with one time, e.g. the song's
 * end (Kuwo): ranked as line synced, they beat real plain lyrics and then show nothing, or every
 * line at once. Lines with words need two distinct times between them to count as synced (or one,
 * for a single line).
 */
fun RemoteLyricsPayload.withoutFakeTiming(): RemoteLyricsPayload {
    val synced = syncedLyrics?.takeIf(String::isNotBlank) ?: return this
    val lines = synced.lines()
    val textLines = lines.filter { line -> line.replace(LRC_STAMP, "").any(Char::isLetterOrDigit) }
    val times = textLines.flatMap { line -> LRC_TIME.findAll(line).map { it.value } }.toSet()
    if (times.size >= 2 || (times.size == 1 && textLines.size == 1)) return this
    // Tag lines ("[ar:...]") go; a stamp alone is a stanza break.
    val text = lines.filterNot { line -> LRC_TAG.matches(line.trim()) }
        .joinToString("\n") { it.replace(LRC_STAMP, "").trim() }
        .trim()
    return copy(syncedLyrics = null, plainLyrics = plainLyrics?.takeIf(String::isNotBlank) ?: text.ifBlank { null })
}

private val LRC_STAMP = Regex("""\[[^\]]*]""")
private val LRC_TAG = Regex("""\[[a-zA-Z]+:[^\]]*]""")
private val LRC_TIME = Regex("""\[\d+:\d+(?:[.:]\d+)?]""")

private fun RemoteLyricsPayload.parsedTtmlQuality(): RemoteLyricsQuality {
    val ttml = ttmlLyrics?.takeIf(String::isNotBlank) ?: return RemoteLyricsQuality.NONE
    val document = TtmlLyricsParser.parse(ttml.byteInputStream())
    val hasText = document.lines.any { line ->
        // A line with only its romanization still shows it.
        line.words.any { word -> word.text.isNotBlank() || !word.romanizedText.isNullOrBlank() }
    }
    if (!hasText) return RemoteLyricsQuality.NONE

    return when (document.type) {
        LyricsType.Syllable -> RemoteLyricsQuality.WORD_SYNCED
        LyricsType.Line -> RemoteLyricsQuality.LINE_SYNCED
        LyricsType.Static -> RemoteLyricsQuality.PLAIN
    }
}
