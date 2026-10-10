package com.almog.spotifytablet.lyrics.mobile.translation

import com.almog.spotifytablet.lyrics.mobile.core.TimedLine
import com.almog.spotifytablet.lyrics.mobile.models.LineRole
import java.security.MessageDigest
import java.util.Locale

enum class TranslationMode(val label: String) { UnderLine("Under each line"), Replace("Replace") }
enum class TranslationProvider(val label: String) {
    Google("Google Translate"), DeepL("DeepL");

    companion object {
        fun stored(value: String?): TranslationProvider = entries.firstOrNull { it.name == value } ?: Google
    }
}

enum class TranslationOrigin { Genius, Google, DeepL }

object TranslationConsent {
    fun id(provider: TranslationProvider, human: Boolean): String =
        "translation_${provider.name.lowercase(Locale.ROOT)}" + if (human) "_genius" else ""

    fun covered(disclosed: Set<String>, provider: TranslationProvider, human: Boolean): Boolean =
        id(provider, human) in disclosed || (!human && (id(provider, true) in disclosed || provider == TranslationProvider.DeepL && "DeepL" in disclosed))
}

data class TranslationPreferences(
    val targetLanguage: String = Locale.getDefault().language,
    val automatic: Boolean = false,
    val provider: TranslationProvider = TranslationProvider.Google,
    val excludedLanguages: Set<String> = emptySet(),
    val humanTranslations: Boolean = true,
)

/** A main line and each of its background voices have distinct, stable addresses. */
data class LyricAddress(val lineIndex: Int, val backgroundIndex: Int? = null)
data class OriginalLine(val index: Int, val address: LyricAddress, val text: String, val boundary: Boolean, val verseStart: Boolean = false)

data class TranslationDocument(val song: String, val source: String, val lines: List<OriginalLine>) {
    val context: String get() = lines.joinToString("\n") { if (it.boundary) "" else it.text }
    val hash: String = translationHash(listOf(song, source) + lines.flatMap {
        listOf(it.address.toString(), it.text, it.boundary.toString(), it.verseStart.toString())
    })

    companion object {
        fun from(song: String, source: String, lines: List<TimedLine>): TranslationDocument {
            val leads = lines.mapIndexedNotNull { index, line ->
                line.groupId?.takeIf { line.role == LineRole.LEAD }?.let { it to index }
            }.toMap()
            val backgrounds = mutableMapOf<Int, Int>()
            var previousLead: TimedLine? = null
            return TranslationDocument(song, source, lines.mapIndexed { index, line ->
                val parent = line.groupId?.let(leads::get) ?: index
                val address = if (line.role == LineRole.BACKGROUND) {
                    val background = backgrounds.getOrDefault(parent, 0)
                    backgrounds[parent] = background + 1
                    LyricAddress(parent, background)
                } else LyricAddress(index)
                val text = buildString {
                    line.words.forEach { word ->
                        if (isNotEmpty() && !word.attached) append(' ')
                        append(word.text)
                    }
                }
                // A timed instrumental gap is a verse boundary even when TTML has no blank rows.
                val verseStart = line.role == LineRole.LEAD && previousLead?.let { line.startMs - it.endMs >= 3_000L } == true
                if (line.role == LineRole.LEAD) previousLead = line
                OriginalLine(index, address, text, line.role == LineRole.INTERLUDE || !isTranslationContent(text), verseStart)
            })
        }
    }
}

fun isTranslationContent(text: String): Boolean = text.any(Char::isLetterOrDigit) && !INSTRUMENTAL_MARKER.matches(text.trim())
private val INSTRUMENTAL_MARKER = Regex("(?:\\[(?:instrumental|interlude|music)]|\\((?:instrumental|interlude|music)\\))", RegexOption.IGNORE_CASE)

fun languageCode(code: String?): String? = code?.trim()?.lowercase(Locale.ROOT)
    ?.replace('_', '-')?.substringBefore('-')?.let {
        when (it) { "iw" -> "he"; "in" -> "id"; "nb", "nn" -> "no"; "und", "auto", "" -> null; else -> it }
    }

fun skipTranslation(source: String?, target: String, excluded: Set<String>): Boolean {
    val language = languageCode(source) ?: return false
    return language == languageCode(target) || excluded.any { languageCode(it) == language }
}

internal fun translationHash(parts: List<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    parts.forEach {
        val bytes = it.toByteArray(Charsets.UTF_8)
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

data class TranslationKey(
    val lyricsHash: String,
    val targetLanguage: String,
    val provider: TranslationProvider,
    val sourceLanguage: String?,
    val humanTranslations: Boolean = true,
) {
    fun fileName(version: Int = TRANSLATION_CACHE_VERSION): String = translationHash(
        listOf(version.toString(), lyricsHash, targetLanguage, provider.name, sourceLanguage.orEmpty(), humanTranslations.toString()),
    ) + ".json"
}

const val TRANSLATION_CACHE_VERSION = 3

data class TranslationResult(
    val key: TranslationKey,
    val texts: List<String?>,
    val detectedLanguage: String?,
    val origins: List<TranslationOrigin?> = texts.map { if (it == null) null else TranslationOrigin.valueOf(key.provider.name) },
) {
    /** Where the lines came from, for the debug report and a tap's message: "Genius 22, Google 37, none 4". */
    fun lineSources(): String {
        val counts = origins.groupingBy { it?.name ?: "none" }.eachCount()
        val order = TranslationOrigin.entries.map { it.name } + "none"
        return order.mapNotNull { name -> counts[name]?.let { "$name $it" } }.joinToString(", ")
    }

    fun geniusCredit(): String? {
        if (TranslationOrigin.Genius !in origins) return null
        val machine = origins.filterNotNull().firstOrNull { it != TranslationOrigin.Genius }
        return "Translation: Genius" + (machine?.let { " + ${it.name}" } ?: "")
    }
}

/** A generation also rejects an old request after off/on, even when its document is identical. */
class TranslationSession {
    data class Ticket(val generation: Long, val key: TranslationKey)
    private var generation = 0L
    private var current: Ticket? = null
    fun begin(key: TranslationKey): Ticket = Ticket(++generation, key).also { current = it }
    fun invalidate() { generation++; current = null }
    fun accepts(ticket: Ticket): Boolean = current == ticket
}

data class TranslationEntry(val text: String?, val needsTranslation: Boolean = true)
data class TranslatorResponse(val lines: List<TranslationEntry?>, val detectedLanguage: String? = null)
data class TranslatorRequest(val lines: List<String>, val target: String, val source: String?, val context: String)

interface Translator {
    val provider: TranslationProvider
    suspend fun translate(request: TranslatorRequest): TranslatorResponse
}

class TranslationFailure(message: String) : IllegalArgumentException(message)

/** The provider is rate limited for now; [retryAfterMs] is how long it asked to wait, when it said. */
class ProviderBusy(val retryAfterMs: Long? = null) : java.io.IOException("Translation is busy.")

fun interface SongLanguageDetector { fun detect(originalLyrics: String): String? }

object DeepLKey {
    fun valid(input: String): Boolean = KEY.matches(input.trim())
    fun host(key: String): String = if (key.trim().endsWith(":fx")) "https://api-free.deepl.com" else "https://api.deepl.com"
    private val KEY = Regex("[A-Za-z0-9_-]{16,}(?::fx)?")
}

object TranslationLanguages {
    val common = linkedMapOf(
        "en" to "English", "ar" to "Arabic", "bg" to "Bulgarian", "zh" to "Chinese",
        "cs" to "Czech", "da" to "Danish", "nl" to "Dutch", "fi" to "Finnish",
        "fr" to "French", "de" to "German", "el" to "Greek", "he" to "Hebrew",
        "hi" to "Hindi", "hu" to "Hungarian", "id" to "Indonesian", "it" to "Italian",
        "ja" to "Japanese", "ko" to "Korean", "no" to "Norwegian", "pl" to "Polish",
        "pt" to "Portuguese", "ro" to "Romanian", "ru" to "Russian", "sk" to "Slovak",
        "es" to "Spanish", "sv" to "Swedish", "th" to "Thai", "tr" to "Turkish",
        "uk" to "Ukrainian", "vi" to "Vietnamese",
    )
    fun name(code: String): String = common[languageCode(code)] ?: Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault()).ifBlank { code }
}
