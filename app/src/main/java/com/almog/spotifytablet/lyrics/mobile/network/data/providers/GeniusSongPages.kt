package com.almog.spotifytablet.lyrics.mobile.network.data.providers

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.almog.spotifytablet.lyrics.mobile.romanization.HumanRomanization
import com.almog.spotifytablet.lyrics.mobile.network.data.awaitResponse
import com.almog.spotifytablet.lyrics.mobile.network.data.spotify.SpotifyTrackMatcher
import kotlin.coroutines.cancellation.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Shared public song search, record lookup and lyric page parsing. */
internal class GeniusSongPages(private val client: OkHttpClient, private val gson: Gson) {

    /** Search the song, with extra reading-specific queries when looking for romanizations. */
    suspend fun search(title: String, artist: String, romanizations: Boolean = true): List<JsonObject> {
        val cleanTitle = title.replace(BRACKETED, "").trim()
        val cleanArtist = artist.replace(BRACKETED, "").trim()
        val queries = if (romanizations) listOf(
            "$title $artist",
            "$cleanTitle $cleanArtist Romanized",
            "$cleanTitle Romanized",
            "$title Genius Romanizations",
        ) else listOf("$title $artist", "$cleanTitle $cleanArtist")
        val hits = mutableListOf<JsonObject>()
        val seen = mutableSetOf<Long>()
        for (query in queries) {
            if (query.isBlank()) continue
            try {
                val url = "https://genius.com/api/search/multi".toHttpUrl().newBuilder()
                    .addQueryParameter("q", query.trim()).build()
                val sections = getJson(url.toString())?.getAsJsonObject("response")?.getAsJsonArray("sections")
                sections?.forEach { section ->
                    val s = section.asJsonObject
                    if (s.str("type") !in setOf("top_hit", "song")) return@forEach
                    s.getAsJsonArray("hits")?.forEach { h ->
                        val res = h.asJsonObject.getAsJsonObject("result") ?: return@forEach
                        val id = res.get("id")?.takeIf { !it.isJsonNull }?.asLong ?: return@forEach
                        if (isSong(res) && seen.add(id)) hits += res
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (_: Exception) {
            }
            if (romanizations && hits.any(::isRomanization)) break
        }
        return hits.sortedBy { !isRomanization(it) }
    }

    /** A song, not an album: an album's id is a different id space and would fetch another song. */
    private fun isSong(hit: JsonObject): Boolean {
        val type = hit.str("_type").ifEmpty { hit.str("type") }
        return type.isEmpty() || type == "song"
    }

    /** Only entries that claim to be romanizations: the original-script page is no use. */
    fun isRomanization(hit: JsonObject): Boolean {
        if (hit.str("language").lowercase() in ROMAN_LANGUAGES) return true
        val fields = listOf(hit.str("title"), hit.str("full_title"), hit.str("title_with_featured"), artistOf(hit))
        return fields.any { ROMAN_HINT.containsMatchIn(it) }
    }

    /**
     * Whether a romanization page is for [artist]. They're filed under a Genius account, so the
     * page title is where the musician is named: "Ado - 踊 (Odo) (Romanized)". Unknown counts as yes.
     */
    fun byUs(hit: JsonObject, artist: String): Boolean {
        if (artist.isBlank()) return true
        val bare = hit.str("title").replace(ROMAN_HINT, "").trim(' ', '-', '(', ')', '[', ']')
        if (" - " !in bare) return true
        val ours = norm(artist)
        val theirs = norm(bare.substringBefore(" - "))
        if (ours.isEmpty() || theirs.isEmpty()) return true
        return ours in theirs || theirs in ours
    }

    /**
     * Whether a search hit is the song that was asked for. One title holds the other, whole or
     * with their bracketed parts (featured credits, alternate titles) set aside. Any one of our
     * credited artists in Genius's credits, featured ones included, is enough: a producer can be
     * filed under another name (the player's "32ki" is Genius's "サツキ (Satsuki)").
     */
    fun ours(hit: JsonObject, title: String, artist: String): Boolean {
        fun holds(a: String, b: String) = a.isNotEmpty() && b.isNotEmpty() && (a == b || a in b || b in a)
        val theirTitle = hit.str("title")
        if (!holds(norm(theirTitle), norm(title)) && !holds(norm(theirTitle.replace(BRACKETED, "")), norm(title.replace(BRACKETED, "")))) return false
        val who = norm(artistOf(hit))
        val asked = norm(artist)
        if (asked.isEmpty() || who.isEmpty() || asked in who || who in asked) return true
        val credits = norm(hit.str("artist_names").ifEmpty { artistOf(hit) })
        return artist.split(ARTIST_SEPARATOR).map(::norm).any { it.length >= 2 && it in credits }
    }

    /** The romanization Genius files against a song (`translation_songs`), if any. */
    suspend fun linkedRomanization(songId: Long): JsonObject? = try {
        record(songId)
            ?.getAsJsonArray("translation_songs")
            ?.map { it.asJsonObject }
            ?.firstOrNull { it.str("language").lowercase() in ROMAN_LANGUAGES }
    } catch (c: CancellationException) {
        throw c
    } catch (_: Exception) {
        null
    }

    /** A Genius song page's lyric lines, cleaned; short or unavailable pages have no result. */
    suspend fun linesAt(url: String, minimum: Int = 4): List<String>? =
        textAt(url)?.let(HumanRomanization::cleanLines)?.takeIf { it.size >= minimum }

    /** A page's lyrics as text, section headers ("[Chorus]") kept. */
    suspend fun textAt(url: String): String? {
        if (url.isBlank()) return null
        return try {
            val html = client.newCall(Request.Builder().url(url).header("User-Agent", BROWSER_UA).get().build())
                .awaitResponse().use { if (it.isSuccessful) it.body?.string().orEmpty() else "" }
            geniusLyricsText(html).takeIf(String::isNotBlank)
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            null
        }
    }

    suspend fun record(songId: Long): JsonObject? =
        getJson("https://genius.com/api/songs/$songId")?.getAsJsonObject("response")?.getAsJsonObject("song")

    // Genius' front answers OkHttp's own user agent with HTTP 401.
    private suspend fun getJson(url: String): JsonObject? =
        client.newCall(Request.Builder().url(url).header("Referer", "https://genius.com/").header("User-Agent", BROWSER_UA).get().build())
            .awaitResponse().use { r -> if (r.isSuccessful) gson.fromJson(r.body?.string(), JsonObject::class.java) else null }

    private fun artistOf(hit: JsonObject) = hit.getAsJsonObject("primary_artist")?.str("name").orEmpty()
    private fun norm(s: String) = SpotifyTrackMatcher.normalize(s).replace(" ", "")
    private fun JsonObject.str(name: String): String = get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    private companion object {
        val ROMAN_HINT = Regex("romani[sz]ed|romani[sz]ation|\\bromaji\\b", RegexOption.IGNORE_CASE)
        val ROMAN_LANGUAGES = setOf("romanization", "romanized")
        val BRACKETED = Regex("[(\\[{].*?[)\\]}]")
        /** Between the names in a player's artist credit: "32ki, Hatsune Miku & 重音テト". */
        val ARTIST_SEPARATOR = Regex("""\s*(?:,|&|、|/|×|\bfeat\.?|\bft\.?|\bx\b)\s*""", RegexOption.IGNORE_CASE)
        const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36"
    }
}
