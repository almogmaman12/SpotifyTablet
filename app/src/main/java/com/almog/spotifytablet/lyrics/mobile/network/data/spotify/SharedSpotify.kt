package com.almog.spotifytablet.lyrics.mobile.network.data.spotify

import com.google.gson.Gson
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * The app's one anonymous Spotify session and match memory, shared by the lyrics lookup and the
 * track extras so a song is matched once and the token fetched once.
 */
object SharedSpotify {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val catalog = AnonymousSpotifyCatalogSearch(client, gson)
    val resolver = SpotifyTrackResolver(catalog)

    @Volatile private var extras: SpotifyTrackExtras? = null

    fun extras(cacheDir: File): SpotifyTrackExtras = extras ?: synchronized(this) {
        extras ?: SpotifyTrackExtras(client, gson, catalog, resolver, cacheDir).also { extras = it }
    }
}
