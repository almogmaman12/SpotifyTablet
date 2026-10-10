package com.almog.spotifytablet.lyrics.mobile.network.data.providers

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.almog.spotifytablet.lyrics.mobile.translation.GeniusTranslationPair
import com.almog.spotifytablet.lyrics.mobile.translation.TranslationLanguages
import com.almog.spotifytablet.lyrics.mobile.translation.languageCode
import kotlin.coroutines.cancellation.CancellationException
import okhttp3.OkHttpClient

internal class GeniusTranslationSource(client: OkHttpClient, gson: Gson) {
    private val pages = GeniusSongPages(client, gson)

    suspend fun find(title: String, artist: String, target: String): GeniusTranslationPair? = try {
        val hits = pages.search(title, artist, romanizations = false)
        var found: GeniusTranslationPair? = null
        for (hit in hits) {
            if (pages.isRomanization(hit) || !pages.ours(hit, title, artist)) continue
            val song = pages.record(hit.get("id")?.asLong ?: continue) ?: continue
            val linked = linkedTranslations(song, target).takeIf { it.isNotEmpty() } ?: continue
            val original = pages.textAt(song.get("url")?.asString.orEmpty()) ?: continue
            // The first page that pairs with the original; a sung cover in the same language has
            // its own lines and pairs with little or nothing.
            for (page in linked) {
                val translated = pages.textAt(page.get("url")?.asString.orEmpty()) ?: continue
                found = GeniusTranslationPair.fromText(original, translated, song.get("language")?.asString)
                if (found != null) break
            }
            if (found != null) break
        }
        found
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

/**
 * The pages linked to [song] in the [target] language. Their language field decides which are in
 * it; ones titled as a translation go first, and a sung "official English version" or cover, which
 * has lyrics of its own rather than a translation, is left out.
 */
internal fun linkedTranslations(song: JsonObject, target: String): List<JsonObject> =
    song.getAsJsonArray("translation_songs")?.mapNotNull { it.takeIf { value -> value.isJsonObject }?.asJsonObject }.orEmpty()
        .filterNot { linked -> linked.get("title")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty().contains(COVER) }
        .filter { linked ->
            val language = linked.get("language")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            val code = TranslationLanguages.common.entries.firstOrNull { it.value.equals(language, ignoreCase = true) }?.key ?: languageCode(language)
            code != null && code == languageCode(target)
        }
        .sortedByDescending { linked -> linked.get("title")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty().contains("translat", ignoreCase = true) }

private val COVER = Regex("""\b(version|cover)\b""", RegexOption.IGNORE_CASE)
