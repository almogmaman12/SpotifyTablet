package com.almog.spotifytablet.lyrics.mobile.translation

/** Builds the translation result from the machine (Google) translated lines. */
fun combineTranslations(key: TranslationKey, machine: List<String?>, language: String?): TranslationResult {
    val texts = machine.map { it?.takeIf(String::isNotBlank) }
    val origins = texts.map { text -> if (text == null) null else TranslationOrigin.Google }
    return TranslationResult(key, texts, language, origins)
}
