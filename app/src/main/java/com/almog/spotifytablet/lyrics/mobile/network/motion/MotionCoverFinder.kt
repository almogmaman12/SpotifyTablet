package com.almog.spotifytablet.lyrics.mobile.network.motion

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.almog.spotifytablet.lyrics.mobile.network.data.awaitResponse
import java.io.File
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Finds a record's animated cover: the HLS stream Apple Music loops on its album page.
 *
 * It takes two steps because the answer is split across two places. A search says which album
 * this is but knows nothing of its video; the music.apple.com album page carries the video but
 * can only be reached by the album's id.
 *
 * The animation belongs to the record, not the track, so the album name is what gets searched.
 * A single is the one case where they coincide, and there the song title stands in.
 *
 * All of this is best effort. The album may have no animation, and the page format is Apple's
 * and undocumented. Every failure ends the same way: no URL, and the still cover stays.
 *
 * Answers are memoised in [memoFile], misses included, since a miss is what saves a search next
 * time. A miss is retried after [MISS_TTL_MS], in case the album has gained an animation.
 */
class MotionCoverFinder(
    private val client: OkHttpClient,
    private val memoFile: File?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private var memo: MutableMap<String, MemoEntry>? = null

    /** The stream's master playlist URL, or null when this record has no animated cover. */
    suspend fun find(artist: String, album: String, title: String): String? {
        val key = key(artist, album, title) ?: return null
        lock.withLock { memo()[key] }?.let { known ->
            if (known.url.isNotEmpty()) return known.url
            if (now() - known.at < MISS_TTL_MS) return null
        }
        val url = try {
            search(artist, album, title)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // A network failure is not a miss: don't remember it.
            return null
        }
        lock.withLock {
            memo()[key] = MemoEntry(url.orEmpty(), now())
            save()
        }
        return url
    }

    /** Drops a remembered stream that failed to play, so the next ask searches again. */
    suspend fun forget(artist: String, album: String, title: String) {
        val key = key(artist, album, title) ?: return
        lock.withLock {
            if (memo().remove(key) != null) save()
        }
    }

    private suspend fun search(artist: String, album: String, title: String): String? {
        val name = album.ifBlank { title }
        val single = album.isBlank() || loose(plain(album), plain(title))
        val terms = listOfNotNull(
            "$artist $name",
            "$artist ${plain(name)}",
            "$artist $title".takeIf { single && title.isNotBlank() },
        ).map { it.split(Regex("\\s+")).filter(String::isNotEmpty).joinToString(" ") }.distinct()

        // The Search API first, as it is quick. Its index runs years behind for some records
        // ("Linkin Park From Zero" never returns the 2024 album), so the web search follows.
        for (finder in listOf(::itunesAlbums, ::webAlbums)) {
            val hits = terms.flatMap { finder(it) }
            val ranked = rankAlbums(hits, artist, name)
            if (ranked.isEmpty()) continue
            for (url in ranked) {
                val page = runCatching { get(url) }.getOrNull() ?: continue
                ambientVideo(page)?.let { return it }
            }
            return null
        }
        return null
    }

    private suspend fun itunesAlbums(term: String): List<AlbumHit> {
        val body = runCatching {
            get("https://itunes.apple.com/search?term=${encode(term)}&entity=album&limit=10")
        }.getOrNull() ?: return emptyList()
        return parseAlbums(body)
    }

    /** The music.apple.com search page. It has no usable metadata, so its ids go through lookup. */
    private suspend fun webAlbums(term: String): List<AlbumHit> {
        val html = runCatching { get("https://music.apple.com/us/search?term=${encode(term)}") }
            .getOrNull() ?: return emptyList()
        val ids = albumIds(html)
        if (ids.isEmpty()) return emptyList()
        val body = runCatching { get("https://itunes.apple.com/lookup?id=${ids.joinToString(",")}") }
            .getOrNull() ?: return emptyList()
        return parseAlbums(body)
    }

    private suspend fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_UA)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        client.newCall(request).awaitResponse().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
            return withContext(Dispatchers.IO) { response.body.string() }
        }
    }

    // Built by hand rather than through Gson's reflection, which R8's renaming would break.
    private fun memo(): MutableMap<String, MemoEntry> = memo ?: run {
        val loaded = mutableMapOf<String, MemoEntry>()
        runCatching {
            JsonParser.parseString(memoFile!!.readText()).asJsonObject.entrySet().forEach { (key, value) ->
                val o = value.asJsonObject
                loaded[key] = MemoEntry(o.get("url").asString, o.get("at").asLong)
            }
        }
        loaded.also { memo = it }
    }

    private fun save() {
        val file = memoFile ?: return
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            val json = JsonObject()
            memo?.forEach { (key, entry) ->
                json.add(key, JsonObject().apply { addProperty("url", entry.url); addProperty("at", entry.at) })
            }
            tmp.writeText(json.toString())
            tmp.renameTo(file) || run { file.delete(); tmp.renameTo(file) }
        }
    }

    private class MemoEntry(val url: String, val at: Long)

    internal data class AlbumHit(val url: String, val name: String, val artist: String)

    companion object {
        /** How long a miss is trusted before the album is searched again. */
        const val MISS_TTL_MS = 30L * 24 * 60 * 60 * 1000

        private const val BROWSER_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        private val AMBIENT_VIDEO = Regex("<amp-ambient-video[^>]+src=\"([^\"]+\\.m3u8)\"")
        private val ALBUM_LINK = Regex("music\\.apple\\.com/[a-z]{2}/album/[^/\"?]+/(\\d+)")
        private val EDITION = Regex(
            "\\s*(?:[(\\[][^()\\[\\]]*(?:deluxe|edition|version|remaster|expanded|" +
                "anniversary|special|bonus|explicit|reissue)[^()\\[\\]]*[)\\]]" +
                "|-\\s*(?:single|ep|deluxe\\b.*|.*\\bedition\\b.*|.*\\bversion\\b.*))\\s*$",
            RegexOption.IGNORE_CASE,
        )

        /** The memo key: one per record, by its lead artist. Null when there's nothing to search. */
        internal fun key(artist: String, album: String, title: String): String? {
            val name = album.ifBlank { title }
            if (artist.isBlank() || name.isBlank()) return null
            return "${akey(artist)}|${akey(name)}"
        }

        internal fun akey(s: String) = s.lowercase().filter(Char::isLetterOrDigit)

        /** The same name allowing for editions and suffixes, either way round. */
        internal fun loose(a: String, b: String): Boolean {
            val ka = akey(a)
            val kb = akey(b)
            if (ka.isEmpty() || kb.isEmpty()) return false
            return ka == kb || (ka.length >= 4 && ka in kb) || (kb.length >= 4 && kb in ka)
        }

        /** The record's name without its edition suffix ("(Deluxe)", "- Single"). */
        internal fun plain(name: String): String {
            var out = name
            repeat(2) { out = EDITION.replace(out, "") }
            return out.trim().ifEmpty { name }
        }

        /**
         * Album pages worth opening, best first. The artist is checked as hard as the name:
         * album names are far from unique ("NF NO NAME" also returns Jack White's "No Name"), and
         * since a page without an animation is skipped, a loose match would hang another
         * artist's video on this record.
         */
        internal fun rankAlbums(hits: List<AlbumHit>, artist: String, name: String): List<String> =
            hits.asSequence()
                .filter { it.url.isNotEmpty() && loose(name, it.name) && loose(artist, it.artist) }
                .distinctBy { it.url }
                .sortedBy {
                    when {
                        akey(it.name) == akey(name) -> 0
                        akey(plain(it.name)) == akey(plain(name)) -> 1
                        else -> 2
                    }
                }
                .map { it.url }
                .toList()

        internal fun parseAlbums(json: String): List<AlbumHit> = runCatching {
            JsonParser.parseString(json).asJsonObject.getAsJsonArray("results").mapNotNull { element ->
                val o = element.asJsonObject
                if (o.get("wrapperType")?.asString != "collection") return@mapNotNull null
                AlbumHit(
                    url = o.get("collectionViewUrl")?.asString.orEmpty().substringBefore('?'),
                    name = o.get("collectionName")?.asString.orEmpty(),
                    artist = o.get("artistName")?.asString.orEmpty(),
                )
            }
        }.getOrDefault(emptyList())

        internal fun albumIds(html: String): List<String> =
            ALBUM_LINK.findAll(html).map { it.groupValues[1] }.distinct().take(12).toList()

        internal fun ambientVideo(html: String): String? = AMBIENT_VIDEO.find(html)?.groupValues?.get(1)

        private fun encode(s: String) = URLEncoder.encode(s, "UTF-8")
    }
}
