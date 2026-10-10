package com.almog.spotifytablet.lyrics.mobile.network.data

import com.google.gson.JsonParser
import com.almog.spotifytablet.lyrics.mobile.network.motion.MotionCoverFinder
import java.net.URLEncoder
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * A song's release year from the iTunes Search API, for songs Spotify can't place. Best effort:
 * any failure is null.
 */
class ItunesReleaseYear(private val client: OkHttpClient) {
    suspend fun find(title: String, artist: String, album: String, durationMs: Long): String? {
        if (title.isBlank() || artist.isBlank()) return null
        val body = try {
            get("https://itunes.apple.com/search?entity=song&limit=15&term=${encode("$artist $title")}")
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return null
        }
        return pick(parse(body), title, artist, album, durationMs)
    }

    private suspend fun get(url: String): String {
        client.newCall(Request.Builder().url(url).build()).awaitResponse().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
            return withContext(Dispatchers.IO) { response.body.string() }
        }
    }

    internal data class Song(val title: String, val artist: String, val album: String, val durationMs: Long, val year: String)

    internal companion object {
        /**
         * The same song by the same artist: on the same record if there is one, else the
         * earliest release of about the same length (a live cut or re-release comes later).
         */
        fun pick(songs: List<Song>, title: String, artist: String, album: String, durationMs: Long): String? {
            val same = songs.filter {
                MotionCoverFinder.loose(MotionCoverFinder.plain(title), MotionCoverFinder.plain(it.title)) &&
                    MotionCoverFinder.loose(artist, it.artist)
            }
            if (album.isNotBlank()) {
                same.firstOrNull { MotionCoverFinder.loose(MotionCoverFinder.plain(album), MotionCoverFinder.plain(it.album)) }
                    ?.let { return it.year }
            }
            return same
                .filter { durationMs <= 0L || it.durationMs <= 0L || abs(it.durationMs - durationMs) <= LENGTH_SLACK_MS }
                .minByOrNull { it.year }
                ?.year
        }

        fun parse(json: String): List<Song> = runCatching {
            JsonParser.parseString(json).asJsonObject.getAsJsonArray("results").mapNotNull { e ->
                val o = e.asJsonObject
                val year = o.get("releaseDate")?.asString?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }
                    ?: return@mapNotNull null
                Song(
                    title = o.get("trackName")?.asString.orEmpty(),
                    artist = o.get("artistName")?.asString.orEmpty(),
                    album = o.get("collectionName")?.asString.orEmpty(),
                    durationMs = o.get("trackTimeMillis")?.asLong ?: 0L,
                    year = year,
                )
            }
        }.getOrDefault(emptyList())

        private const val LENGTH_SLACK_MS = 5_000L

        private fun encode(s: String) = URLEncoder.encode(s, "UTF-8")
    }
}
