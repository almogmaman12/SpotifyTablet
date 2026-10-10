package com.almog.spotifytablet.lyrics.mobile.translation

import com.google.gson.JsonParser
import com.almog.spotifytablet.lyrics.mobile.network.data.awaitResponse
import java.io.IOException
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class GoogleTranslator(private val client: OkHttpClient) : Translator {
    override val provider = TranslationProvider.Google
    /**
     * Chrome's translate endpoint first, then the web one: each has its own allowance. When both
     * are limiting this connection, it's [ProviderBusy]; asking again soon only lengthens a block.
     */
    override suspend fun translate(request: TranslatorRequest): TranslatorResponse {
        call(googleChromeRequest(request))?.let { return googleChromeResponse(it, request.source) }
        call(googleRequest(request))?.let { return googleResponse(it) }
        throw ProviderBusy()
    }

    /** The body, or null when the endpoint is limiting this connection. */
    private suspend fun call(request: Request): String? = client.newCall(request).awaitResponse().use { response ->
        if (response.code == 429 || response.code == 503) return@use null
        if (!response.isSuccessful) throw IOException("Translation is unavailable (HTTP ${response.code}).")
        response.body?.string().orEmpty()
    }
}

internal fun googleChromeRequest(request: TranslatorRequest): Request {
    val text = request.lines.joinToString("\n")
    if (text.length > TranslationBatching.GOOGLE_TEXT_LIMIT) throw TranslationFailure("This request exceeds Google's text limit.")
    val url = "https://translate.googleapis.com/translate_a/t".toHttpUrl().newBuilder()
        .addQueryParameter("client", "dict-chrome-ex")
        .addQueryParameter("sl", request.source ?: "auto").addQueryParameter("tl", request.target).build()
    return Request.Builder().url(url).post(FormBody.Builder().add("q", text).build()).build()
}

/** `[["translation","ja"]]` when the language was detected, `["translation"]` when it was given. */
internal fun googleChromeResponse(json: String, source: String?): TranslatorResponse {
    val first = JsonParser.parseString(json).asJsonArray.firstOrNull() ?: throw IOException("Google returned an invalid response.")
    val (text, language) = if (first.isJsonArray) {
        val pair = first.asJsonArray
        pair[0].asString to pair.getOrNull(1)?.takeIf { it.isJsonPrimitive }?.asString
    } else first.asString to source
    return TranslatorResponse(text.replace("\r\n", "\n").split('\n').map { TranslationEntry(it.takeIf(String::isNotBlank)) }, language)
}

private fun com.google.gson.JsonArray.getOrNull(index: Int) = if (index < size()) get(index) else null

internal fun googleRequest(request: TranslatorRequest): Request {
    val text = request.lines.joinToString("\n")
    if (text.length > TranslationBatching.GOOGLE_TEXT_LIMIT) throw TranslationFailure("This request exceeds Google's text limit.")
    val url = "https://translate.googleapis.com/translate_a/single".toHttpUrl().newBuilder()
        .addQueryParameter("client", "gtx").addQueryParameter("dt", "t").addQueryParameter("dj", "1")
        .addQueryParameter("sl", request.source ?: "auto").addQueryParameter("tl", request.target).build()
    return Request.Builder().url(url).post(FormBody.Builder().add("q", text).build()).build()
}

internal fun googleResponse(json: String): TranslatorResponse {
    val root = JsonParser.parseString(json).asJsonObject
    val sentences = root.getAsJsonArray("sentences") ?: throw IOException("Google returned an invalid response.")
    val text = sentences.joinToString("") { sentence ->
        sentence.asJsonObject.get("trans")?.takeUnless { it.isJsonNull }?.asString
            ?: throw IOException("Google returned an invalid sentence.")
    }
    return TranslatorResponse(text.replace("\r\n", "\n").split('\n').map { TranslationEntry(it.takeIf(String::isNotBlank)) },
        root.get("src")?.takeUnless { it.isJsonNull }?.asString)
}

class DeepLTranslator(private val client: OkHttpClient, private val key: String) : Translator {
    override val provider = TranslationProvider.DeepL
    override suspend fun translate(request: TranslatorRequest): TranslatorResponse {
        if (!DeepLKey.valid(key)) throw TranslationFailure("Add your DeepL API key in Translation settings.")
        val response = client.newCall(deepLRequest(request, key))
            .awaitResponse().use { response ->
                if (!response.isSuccessful) throw IOException("DeepL couldn't translate these lyrics (HTTP ${response.code}).")
                JsonParser.parseString(response.body?.string().orEmpty()).asJsonObject
            }
        val translation = response.getAsJsonArray("translations")?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw IOException("DeepL returned an invalid response.")
        val language = translation.get("detected_source_language")?.takeUnless { it.isJsonNull }?.asString
        val translated = languageCode(language) != languageCode(request.target)
        val texts = deepLLines(translation.get("text")?.takeUnless { it.isJsonNull }?.asString.orEmpty(), request.lines.size)
        return TranslatorResponse(texts.mapIndexed { index, text ->
            text?.let { TranslationEntry(it, translated && it != request.lines[index]) }
        }, language)
    }
}

/**
 * The whole song as one document, each line in its own numbered tag: DeepL reads the lines with
 * each other for context, and every translated line comes back in its tag, so none can run into
 * another.
 */
internal fun deepLDocument(lines: List<String>): String = lines.withIndex().joinToString("\n") { (index, line) ->
    "<l i=\"$index\">${line.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")}</l>"
}

/** [count] lines read back from [deepLDocument]'s tags; a tag that didn't come back is null. */
internal fun deepLLines(document: String, count: Int): List<String?> {
    val found = Regex("""<l i="(\d+)">(.*?)</l>""", RegexOption.DOT_MATCHES_ALL).findAll(document)
        .associate { it.groupValues[1].toInt() to it.groupValues[2] }
    return List(count) { index ->
        found[index]?.replace("&lt;", "<")?.replace("&gt;", ">")?.replace("&quot;", "\"")?.replace("&apos;", "'")
            ?.replace("&amp;", "&")?.trim()?.takeIf(String::isNotBlank)
    }
}

internal fun deepLRequest(request: TranslatorRequest, key: String): Request {
    fun code(language: String): String = if (languageCode(language) == "no") "NB" else language.uppercase(java.util.Locale.ROOT)
    val body = FormBody.Builder().apply {
        add("text", deepLDocument(request.lines))
        add("target_lang", code(request.target))
        request.source?.let { add("source_lang", code(it)) }
        add("tag_handling", "xml")
        add("split_sentences", "nonewlines")
        add("preserve_formatting", "1")
    }.build()
    if (body.contentLength() > 128 * 1024) throw TranslationFailure("This song exceeds DeepL's request size limit.")
    return Request.Builder().url("${DeepLKey.host(key)}/v2/translate")
        .header("Authorization", "DeepL-Auth-Key ${key.trim()}").post(body).build()
}
