package com.almog.spotifytablet.lyrics.mobile.network.data.providers

import com.google.gson.Gson
import okhttp3.OkHttpClient

/** Finds a human romanization directly or through the original song's translation links. */
internal class GeniusRomanizationSource(client: OkHttpClient, gson: Gson) {
    private val pages = GeniusSongPages(client, gson)

    suspend fun find(title: String, artist: String): List<String>? {
        val hits = pages.search(title, artist)
        for (hit in hits) {
            if (!pages.isRomanization(hit) || !pages.byUs(hit, artist)) continue
            pages.linesAt(hit.get("url")?.asString.orEmpty())?.let { return it }
        }
        for (hit in hits) {
            if (pages.isRomanization(hit) || !pages.ours(hit, title, artist)) continue
            val linked = pages.linkedRomanization(hit.get("id")?.asLong ?: continue) ?: continue
            pages.linesAt(linked.get("url")?.asString.orEmpty())?.let { return it }
        }
        return null
    }
}
