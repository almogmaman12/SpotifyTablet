package com.almog.spotifytablet.lyrics.mobile.translation

import com.google.gson.Gson
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Found page pairs last a week; a missing translation lasts a day. */
class DiskGeniusTranslationCache(private val directory: File, private val now: () -> Long = System::currentTimeMillis) {
    private val gson = Gson()
    private data class Entry(val version: Int, val expiresAt: Long, val pair: GeniusTranslationPair?)

    suspend fun find(title: String, artist: String, target: String, lookup: suspend () -> GeniusTranslationPair?): GeniusTranslationPair? {
        val file = file(title, artist, target)
        val entry = runCatching {
            if (!file.isFile || file.length() > 2 * 1024 * 1024) return@runCatching null
            gson.fromJson(file.readText(), Entry::class.java)
        }.getOrNull()
        if (entry != null && entry.version == TRANSLATION_CACHE_VERSION && entry.expiresAt > now()) return entry.pair
        val pair = lookup()
        runCatching {
            directory.mkdirs()
            val temporary = File.createTempFile("genius-", ".tmp", directory)
            try {
                temporary.writeText(gson.toJson(Entry(TRANSLATION_CACHE_VERSION, now() + TimeUnit.DAYS.toMillis(if (pair == null) 1 else 7), pair)))
                if (!temporary.renameTo(file)) { file.delete(); temporary.renameTo(file) }
            } finally { temporary.delete() }
            directory.listFiles()?.filter { it.extension == "json" }?.sortedByDescending(File::lastModified)
                ?.drop(100)?.forEach(File::delete)
        }
        return pair
    }

    fun clear() {
        directory.deleteRecursively()
    }

    fun forget(title: String, artist: String) {
        directory.listFiles()?.filter { it.name.startsWith(songKey(title, artist) + "-") }?.forEach(File::delete)
    }

    private fun file(title: String, artist: String, target: String): File =
        File(directory, songKey(title, artist) + "-" + translationHash(listOf(languageCode(target).orEmpty())) + ".json")

    private fun songKey(title: String, artist: String) = translationHash(listOf(title, artist).map { it.trim().lowercase(Locale.ROOT) })
}
