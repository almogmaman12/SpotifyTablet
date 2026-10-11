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
