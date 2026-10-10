package com.almog.spotifytablet.lyrics.mobile.translation

import com.google.gson.Gson
import java.io.File

class DiskTranslationCache(private val directory: File) : TranslationCache {
    private val gson = Gson()
    private data class Entry(val version: Int, val result: TranslationResult)

    override fun read(key: TranslationKey, lineCount: Int): TranslationResult? = runCatching {
        val file = File(directory, key.fileName())
        if (!file.isFile || file.length() > 2 * 1024 * 1024) return null
        val entry = gson.fromJson(file.readText(), Entry::class.java)
        entry.result.takeIf {
            entry.version == TRANSLATION_CACHE_VERSION && it.key == key && it.texts.size == lineCount && it.origins.size == lineCount
        }
    }.getOrNull()

    /** Drops every translation made from the lyrics with [lyricsHash], in any language or provider. */
    fun forget(lyricsHash: String) {
        directory.listFiles()?.filter { it.extension == "json" }?.forEach { file ->
            val entry = runCatching { gson.fromJson(file.readText(), Entry::class.java) }.getOrNull()
            if (entry == null || entry.result.key.lyricsHash == lyricsHash) file.delete()
        }
    }

    fun clear() {
        directory.deleteRecursively()
    }

    override fun write(result: TranslationResult) {
        runCatching {
            directory.mkdirs()
            val destination = File(directory, result.key.fileName())
            val temporary = File.createTempFile("translation-", ".tmp", directory)
            try {
                temporary.writeText(gson.toJson(Entry(TRANSLATION_CACHE_VERSION, result)))
                if (!temporary.renameTo(destination)) {
                    destination.delete()
                    temporary.renameTo(destination)
                }
            } finally { temporary.delete() }
            // A modest, disposable cache; never a second permanent lyrics library.
            directory.listFiles()?.filter { it.extension == "json" }?.sortedByDescending(File::lastModified)
                ?.drop(100)?.forEach(File::delete)
        }
    }
}
