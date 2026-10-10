package com.almog.spotifytablet.lyrics.mobile.network.data

/**
 * What switching a source on shares, said once before it's switched on. Out of the box only
 * Spicy Lyrics and the lyrics APIs that say any app may use them are on; every other source asks
 * first.
 */
data class SourceDisclosure(
    /** The source or setting it's for. */
    val id: String,
    val name: String,
    /** Who gets asked, in plain words. */
    val recipient: String,
    /** What they get. */
    val sends: String = TITLE_ARTIST,
    /** Anything else worth knowing first. */
    val note: String? = null,
    val descriptionOverride: String? = null,
) {
    val description: String
        get() = descriptionOverride ?: buildString {
            append("Turning this on sends $sends to $recipient each time it looks for lyrics. ")
            append("No account or device details are sent. ")
            append("This source isn't run by Spicy Lyrics, so its own terms apply.")
            if (note != null) append(" ").append(note)
        }
}

private const val TITLE_ARTIST = "the song's title and artist"
private const val WITH_LENGTH = "the song's title, artist and length"

object SourceDisclosures {
    fun translation(provider: com.almog.spotifytablet.lyrics.mobile.translation.TranslationProvider, human: Boolean = true): SourceDisclosure {
        val deepL = provider == com.almog.spotifytablet.lyrics.mobile.translation.TranslationProvider.DeepL
        val recipient = if (deepL) "DeepL" else "Google Translate"
        return SourceDisclosure(
            id = com.almog.spotifytablet.lyrics.mobile.translation.TranslationConsent.id(provider, human),
            name = "translation", recipient = recipient + if (human) " and Genius" else "",
            descriptionOverride = "Translation sends the song's original lyric lines, including background vocals, to $recipient. " +
                "Your target and lyrics languages are sent too. " +
                (if (deepL) "Your DeepL API key authenticates requests to DeepL. " else "This uses Google's free web service, not a paid API; no API key is needed. ") +
                (if (human) "Human translations are checked first: the song's title and artist are sent to Genius, and its original and translated lyric pages are fetched. Only songs with gaps in the human translation need $recipient. " else "") +
                "Their own terms and privacy policies apply. You can turn translation off at any time.",
        )
    }
    /** The setting that lays human-written romanizations from Genius over the lyrics. */
    const val GENIUS_ROMANIZATION_ID = "genius_romanization"

    private val byId = listOf(
        SourceDisclosure("rmm_revival", "RMM Revival", "Apple's iTunes search and RMM Revival (rmmreviv.al)"),
        SourceDisclosure("kugou", "Kugou", "Kugou's servers in China", WITH_LENGTH),
        SourceDisclosure("qq_music", "QQ Music", "Tencent's QQ Music servers in China"),
        SourceDisclosure("kuwo", "Kuwo", "Kuwo's servers in China"),
        SourceDisclosure("netease", "NetEase", "NetEase Cloud Music's servers in China"),
        SourceDisclosure("genius", "Genius", "Genius (genius.com)"),
        SourceDisclosure("youtube_transcript", "YouTube transcripts", "YouTube's search (youtube.com)"),
        SourceDisclosure(GENIUS_ROMANIZATION_ID, "Human romanizations", "Genius (genius.com)",
            note = "It's only asked for songs in a script that gets romanized."),
    ).associateBy(SourceDisclosure::id)

    /**
     * The disclosure for [descriptor], or null when it needs none: sources on by default, rank-only
     * slots that are never asked, and the user's own sources, whose address they typed.
     */
    fun forSource(descriptor: LyricsSourceDescriptor): SourceDisclosure? = when {
        descriptor.defaultEnabled || descriptor.rankOnly || descriptor.upstreamFamily == "custom" -> null
        else -> byId[descriptor.id] ?: SourceDisclosure(descriptor.id, descriptor.displayName, descriptor.displayName)
    }

    fun forId(id: String): SourceDisclosure? = byId[id]

    /**
     * The title and text telling a user what an update switched off ([ids]: sources, the Genius
     * romanization switch and Musixmatch, which is gone), or null when it switched off nothing.
     */
    fun switchedOffNotice(ids: Set<String>): Pair<String, String>? {
        val names = ids.filter { it != MUSIXMATCH_ID }.map { byId[it]?.name ?: it }.sorted()
        val gone = MUSIXMATCH_ID in ids
        if (names.isEmpty() && !gone) return null
        val text = buildString {
            if (names.isNotEmpty()) {
                append("This update switched off ${names.joinWords()}. ")
                append(if (names.size == 1) "It's" else "They're")
                append(" run by services that haven't said apps may use them, so each now says what it sends ")
                append("before you switch it back on in Settings → Sources. Your other sources and their order are as you left them.")
            }
            if (gone) {
                if (isNotEmpty()) append(" ")
                append("Musixmatch is gone: the app could only reach it by posing as Musixmatch's own app.")
            }
        }
        return (if (names.isEmpty()) "Musixmatch is gone" else "Some lyrics sources were switched off") to text
    }

    private fun List<String>.joinWords() = if (size <= 1) joinToString() else dropLast(1).joinToString() + " and " + last()

    private const val MUSIXMATCH_ID = "musixmatch"
}
