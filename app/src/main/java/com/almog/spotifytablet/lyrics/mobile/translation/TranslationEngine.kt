package com.almog.spotifytablet.lyrics.mobile.translation

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/** Keeps request positions separate from display positions, including split long lines. */
data class TranslationPiece(val index: Int, val part: Int, val text: String, val verse: Int = 0)

object TranslationBatching {
    const val GOOGLE_TEXT_LIMIT = 4_999

    fun batches(document: TranslationDocument, provider: TranslationProvider): List<List<TranslationPiece>> {
        val verses = mutableListOf<MutableList<TranslationPiece>>()
        var verse = mutableListOf<TranslationPiece>()
        document.lines.forEach { line ->
            if (line.boundary || line.verseStart) {
                if (verse.isNotEmpty()) verses.add(verse)
                verse = mutableListOf()
            }
            if (!line.boundary) {
                val texts = if (provider == TranslationProvider.Google) splitLongLine(line.text) else listOf(line.text)
                texts.forEachIndexed { part, text -> verse.add(TranslationPiece(line.index, part, text, verses.size)) }
            }
        }
        if (verse.isNotEmpty()) verses.add(verse)
        // One document, so DeepL reads the whole song together; its size limit is checked on the request.
        if (provider == TranslationProvider.DeepL) return listOf(verses.flatten()).filter { it.isNotEmpty() }
        val batches = mutableListOf<List<TranslationPiece>>()
        var batch = mutableListOf<TranslationPiece>()
        verses.forEach { block ->
            if (size(batch + block) > GOOGLE_TEXT_LIMIT && batch.isNotEmpty()) {
                batches.add(batch)
                batch = mutableListOf()
            }
            // An oversized verse has to split between lines; each piece still keeps its address.
            block.forEach { piece ->
                if (size(batch + piece) > GOOGLE_TEXT_LIMIT && batch.isNotEmpty()) {
                    batches.add(batch)
                    batch = mutableListOf()
                }
                batch.add(piece)
            }
        }
        if (batch.isNotEmpty()) batches.add(batch)
        return batches
    }

    private fun splitLongLine(text: String): List<String> {
        val parts = mutableListOf<String>()
        var remaining = text
        while (remaining.length > GOOGLE_TEXT_LIMIT) {
            var end = remaining.lastIndexOf(' ', GOOGLE_TEXT_LIMIT).takeIf { it > 0 } ?: GOOGLE_TEXT_LIMIT
            if (remaining[end - 1].isHighSurrogate()) end--
            parts.add(remaining.substring(0, end))
            remaining = remaining.substring(end).trimStart()
        }
        if (remaining.isNotBlank()) parts.add(remaining)
        return parts
    }

    private fun size(pieces: List<TranslationPiece>): Int = pieces.sumOf { it.text.length } + (pieces.size - 1).coerceAtLeast(0)

    /**
     * A batch whose line count came back wrong, split into its verses to ask again. A single verse
     * isn't split further: asking line by line is a burst of requests that gets the connection
     * blocked, so its lines stay untranslated instead.
     */
    fun fallback(batch: List<TranslationPiece>): List<List<TranslationPiece>> {
        val verses = batch.groupBy { it.verse }.values.toList()
        return if (verses.size > 1) verses else emptyList()
    }

    /** A positional response of the wrong size cannot reveal where a missing item was. */
    fun align(batch: List<TranslationPiece>, response: TranslatorResponse): Map<Pair<Int, Int>, String?> {
        if (response.lines.size != batch.size) return batch.associate { (it.index to it.part) to null }
        return batch.mapIndexed { position, piece ->
            val result = response.lines[position]
            (piece.index to piece.part) to result?.text?.takeIf { result.needsTranslation && it.isNotBlank() }
        }.toMap()
    }
}

interface TranslationCache {
    fun read(key: TranslationKey, lineCount: Int): TranslationResult?
    fun write(result: TranslationResult)
}

sealed interface TranslationOutcome {
    data class Translated(val result: TranslationResult) : TranslationOutcome
    data class Skipped(val language: String?) : TranslationOutcome
}

class TranslationEngine(
    private val detector: SongLanguageDetector,
    private val cache: TranslationCache,
    /** Waits between requests and before a retry; tests pass one that doesn't. */
    private val pause: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Until when Google is left alone after it started limiting this connection. */
    @Volatile private var googleRestsUntil = 0L
    private val languages = object : LinkedHashMap<String, String?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>): Boolean = size > 32
    }

    fun language(document: TranslationDocument, override: String?): String? = languageCode(override) ?: synchronized(languages) {
        if (!languages.containsKey(document.hash)) languages[document.hash] = languageCode(detector.detect(document.context))
        languages[document.hash]
    }

    fun key(document: TranslationDocument, preferences: TranslationPreferences, source: String?): TranslationKey =
        TranslationKey(document.hash, languageCode(preferences.targetLanguage) ?: "en", preferences.provider, languageCode(source), preferences.humanTranslations)

    suspend fun translate(
        document: TranslationDocument,
        preferences: TranslationPreferences,
        source: String?,
        translator: Translator,
        /** The machine translation, shown while a slow human lookup is still out. */
        onPartial: suspend (TranslationResult) -> Unit = {},
        human: suspend () -> GeniusTranslationPair? = { null },
    ): TranslationOutcome = coroutineScope {
        if (skipTranslation(source, preferences.targetLanguage, preferences.excludedLanguages)) return@coroutineScope TranslationOutcome.Skipped(source)
        require(translator.provider == preferences.provider)
        val key = key(document, preferences, source)
        cache.read(key, document.lines.size)?.let {
            return@coroutineScope if (skipTranslation(it.detectedLanguage, preferences.targetLanguage, preferences.excludedLanguages))
                TranslationOutcome.Skipped(it.detectedLanguage) else TranslationOutcome.Translated(it)
        }
        val none = List<String?>(document.lines.size) { null }
        val lookup = if (preferences.humanTranslations) async { runCatching { human() }.getOrNull() } else null
        // A human translation already on hand (cached) decides before any machine request; a slow
        // lookup doesn't hold the machine translation back.
        val quick = lookup?.let { withTimeoutOrNull(QUICK_HUMAN_MS) { it.await() } }
        var humanTexts = quick?.let { HumanTranslation.align(document, it) } ?: none
        var detected = source ?: languageCode(quick?.sourceLanguage)
        fun skipped(): TranslationOutcome {
            lookup?.cancel()
            // Kept, untranslated, so the song is known to need nothing the next time it plays.
            cache.write(TranslationResult(key, none, detected))
            return TranslationOutcome.Skipped(detected)
        }
        if (skipTranslation(detected, preferences.targetLanguage, preferences.excludedLanguages)) return@coroutineScope skipped()
        if (document.lines.all { it.boundary }) return@coroutineScope TranslationOutcome.Skipped(source)
        if (document.lines.all { it.boundary || humanTexts[it.index] != null }) {
            val result = combineTranslations(key, humanTexts, none, detected)
            cache.write(result)
            return@coroutineScope TranslationOutcome.Translated(result)
        }
        // Keep the whole original song in the machine request, even where human lines win.
        val batches = TranslationBatching.batches(document, translator.provider)
        if (batches.isEmpty()) return@coroutineScope TranslationOutcome.Skipped(source)
        val aligned = mutableMapOf<Pair<Int, Int>, String?>()
        var requests = 0
        var answered = false
        suspend fun send(batch: List<TranslationPiece>) {
            if (requests++ > 0) pause(PACE_MS)
            val response = ask(translator, TranslatorRequest(batch.map { it.text }, key.targetLanguage, detected, document.context))
            detected = detected ?: languageCode(response.detectedLanguage)
            if (skipTranslation(detected, preferences.targetLanguage, preferences.excludedLanguages)) {
                answered = true
                return
            }
            if (response.lines.size != batch.size && translator.provider == TranslationProvider.Google) {
                if (batch.size > 1) TranslationBatching.fallback(batch).forEach { send(it) }
                return
            }
            if (response.lines.size != batch.size) throw TranslationFailure("Translation returned an incomplete response. Try again later.")
            answered = true
            aligned.putAll(TranslationBatching.align(batch, response))
        }
        batches.forEach { if (!skipTranslation(detected, preferences.targetLanguage, preferences.excludedLanguages)) send(it) }
        if (skipTranslation(detected, preferences.targetLanguage, preferences.excludedLanguages)) return@coroutineScope skipped()
        val pieces = batches.flatten().groupBy { it.index }
        val texts = document.lines.map { line ->
            val parts = pieces[line.index].orEmpty()
            // A missing chunk leaves the entire original line intact.
            if (parts.isEmpty() || parts.any { aligned[it.index to it.part] == null }) null
            else parts.joinToString(" ") { aligned.getValue(it.index to it.part).orEmpty() }
        }
        if (lookup != null && quick == null) {
            if (texts.any { it != null }) onPartial(combineTranslations(key, none, texts, detected))
            humanTexts = lookup.await()?.let { HumanTranslation.align(document, it) } ?: none
        }
        if (!answered && humanTexts.all { it == null }) throw TranslationFailure("Couldn't translate these lyrics. Try again later.")
        val result = combineTranslations(key, humanTexts, texts, detected)
        cache.write(result)
        TranslationOutcome.Translated(result)
    }

    /** [Translator.translate], waiting out a busy provider a couple of times before giving up. */
    private suspend fun ask(translator: Translator, request: TranslatorRequest): TranslatorResponse {
        // Google's limit is a block on the connection: asking again soon only makes it last
        // longer, so it's left alone for a while instead of retried.
        if (translator.provider == TranslationProvider.Google) {
            if (now() < googleRestsUntil) throw TranslationFailure(GOOGLE_RESTING)
            return try {
                translator.translate(request)
            } catch (busy: ProviderBusy) {
                googleRestsUntil = now() + GOOGLE_REST_MS
                throw TranslationFailure(GOOGLE_RESTING)
            }
        }
        repeat(BUSY_RETRIES) { attempt ->
            try {
                return translator.translate(request)
            } catch (busy: ProviderBusy) {
                pause(busy.retryAfterMs?.coerceIn(1_000L, 10_000L) ?: (BUSY_WAIT_MS * (attempt + 1)))
            }
        }
        return try {
            translator.translate(request)
        } catch (busy: ProviderBusy) {
            throw TranslationFailure("Translation is busy right now. Try again in a minute.")
        }
    }

    private companion object {
        /** How long a human lookup gets before the machine translation goes ahead without it. */
        const val QUICK_HUMAN_MS = 300L
        const val PACE_MS = 300L
        const val BUSY_RETRIES = 2
        const val BUSY_WAIT_MS = 2_000L
        const val GOOGLE_REST_MS = 10 * 60_000L
        const val GOOGLE_RESTING = "Google is limiting translations on this connection. Try again in a few minutes, or use DeepL."
    }
}
