package com.almog.spotifytablet.lyrics.mobile

import android.content.Context
import com.almog.spotifytablet.lyrics.mobile.core.TimedLine
import com.almog.spotifytablet.lyrics.mobile.core.TimedWord
import com.almog.spotifytablet.lyrics.mobile.models.Line
import com.almog.spotifytablet.lyrics.mobile.translation.DiskTranslationCache
import com.almog.spotifytablet.lyrics.mobile.translation.GoogleTranslator
import com.almog.spotifytablet.lyrics.mobile.translation.SongLanguageDetector
import com.almog.spotifytablet.lyrics.mobile.translation.TranslationDocument
import com.almog.spotifytablet.lyrics.mobile.translation.TranslationEngine
import com.almog.spotifytablet.lyrics.mobile.translation.TranslationOutcome
import com.almog.spotifytablet.lyrics.mobile.translation.TranslationPreferences
import com.almog.spotifytablet.lyrics.mobile.translation.TranslationProvider
import com.almog.spotifytablet.lyrics.mobile.translation.TranslationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Translates the lyrics on screen with the Spicy Lyrics Mobile translation engine (Google Translate,
 * cached on disk). Only runs when the user turned translation on (see [PREF_TRANSLATE_TARGET]), since it
 * sends the lyrics text to Google.
 */
object MobileTranslation {
    /** Pref (in Constants.PREF_NAME): target language code such as "en" or "he"; empty/missing = off. */
    const val PREF_TRANSLATE_TARGET = "lyrics_translate_target"
    /** Pref: "replace" shows the translation instead of the line; anything else shows it under the line. */
    const val PREF_TRANSLATION_MODE = "lyrics_translation_mode"
    /** Pref (boolean): show romanization (Latin transliteration) of non-Latin lyrics. */
    const val PREF_ROMANIZE = "lyrics_romanize"

    @Volatile private var engine: TranslationEngine? = null
    private val client: OkHttpClient by lazy { OkHttpClient.Builder().callTimeout(45, TimeUnit.SECONDS).build() }

    private fun engine(context: Context): TranslationEngine = engine ?: synchronized(this) {
        engine ?: TranslationEngine(
            SongLanguageDetector { null },
            DiskTranslationCache(File(context.applicationContext.cacheDir, "translations"))
        ).also { engine = it }
    }

    /** [original] are the track's lines before interludes were added (the translation is index-aligned to them). */
    suspend fun translate(context: Context, songKey: String, original: List<Line>, target: String): TranslationResult? =
        withContext(Dispatchers.IO) {
            val timed = original.map { line ->
                TimedLine(
                    startMs = line.startMs,
                    endMs = line.endMs,
                    words = line.words.map { TimedWord(it.text, it.startMs, it.endMs, it.isPartOfWord, it.romanizedText) },
                    role = line.role,
                    groupId = line.groupId,
                    agent = line.agent,
                    oppositeAligned = line.oppositeAligned
                )
            }
            val document = TranslationDocument.from(songKey, "app", timed)
            val preferences = TranslationPreferences(
                targetLanguage = target,
                automatic = true,
                provider = TranslationProvider.Google,
                humanTranslations = false
            )
            val outcome = engine(context).translate(document, preferences, null, GoogleTranslator(client))
            (outcome as? TranslationOutcome.Translated)?.result
        }
}
