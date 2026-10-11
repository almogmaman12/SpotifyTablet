package com.almog.spotifytablet.lyrics.mobile.models

/** Non-timed attribution displayed after the vocal timeline. */
data class LyricsFooter(
    val songwriters: List<String> = emptyList(),
    val provenance: LyricsProvenance? = null,
    /** Sync credits: who made the timing and who uploaded/submitted it. */
    val maker: LyricsCredit? = null,
    val uploader: LyricsCredit? = null,
    val translationCredit: String? = null,
) {
    /**
     * The lines shown after the lyrics: writers, then the Spicy Lyrics community block, or for other sources
     * the lyric source followed by its own credits.
     */
    fun lines(): List<FooterLine> = buildList {
        // Reference order (Syllable/Line/Static applyers): Credits, LyricsProvider, SongInfo.
        if (songwriters.isNotEmpty()) add(FooterLine("Written by: ${songwriters.joinToString(", ")}", FooterLine.Kind.WRITERS))
        provenance?.let { p ->
            // The catalogue that answered, as in "Provided by: Apple Music"; a
            // Spicy Lyrics community sync is "Spicy Lyrics" there.
            val origin = p.contributor?.takeIf(String::isNotBlank)
                ?.let { if (it == "Spicy Lyrics Community") "Spicy Lyrics" else it } ?: p.provider
            add(FooterLine("Provided by: $origin", FooterLine.Kind.PROVIDER))
        }
        translationCredit?.let { add(FooterLine(it, FooterLine.Kind.PROVIDER)) }
        val spicyCommunity = provenance?.provider == "Spicy Lyrics" && (maker != null || uploader != null)
        if (spicyCommunity) add(FooterLine("These lyrics have been provided by our community", FooterLine.Kind.NOTE))
        maker?.let { add(it.line("Made by")) }
        uploader?.let { add(it.line(if (maker != null) "Uploaded by" else if (spicyCommunity) "Made by" else "Submitted by")) }
    }
}

/** A person credited for a sync, with the links the source gave for them. */
data class LyricsCredit(val name: String, val profileUrl: String? = null, val avatarUrl: String? = null) {
    internal fun line(role: String) = FooterLine(
        "$role @$name",
        FooterLine.Kind.CONTRIBUTOR,
        label = role,
        name = name,
        profileUrl = profileUrl?.takeIf(::isTrustedProfile),
        avatarUrl = avatarUrl?.takeIf { it.startsWith("https://") },
    )

    private companion object {
        val TRUSTED_HOSTS = setOf("spicylyrics.org", "www.spicylyrics.org", "github.com", "unison.boidu.dev")

        /** Only https profiles on hosts we know are opened from a tap. */
        fun isTrustedProfile(url: String): Boolean =
            url.startsWith("https://") && url.removePrefix("https://").substringBefore('/').lowercase() in TRUSTED_HOSTS
    }
}

data class FooterLine(
    val text: String,
    val kind: Kind,
    val profileUrl: String? = null,
    val avatarUrl: String? = null,
    /** For [Kind.CONTRIBUTOR]: drawn as "label " then a bold, underlined "@name". */
    val label: String? = null,
    val name: String? = null,
) {
    enum class Kind { WRITERS, PROVIDER, NOTE, CONTRIBUTOR }
}

data class LyricsProvenance(
    val provider: String,
    val contributor: String? = null,
)

/** Normalized lyric input shared by static, line-synced, and syllable-synced renderers. */
data class LyricsDocument(
    val lines: List<Line>,
    val footer: LyricsFooter = LyricsFooter(),
    /** Synchronization granularity; drives which render path is used. */
    val type: LyricsType = LyricsType.Syllable,
    /** Song URI + source + SHA-256 of the raw lyric payload. */
    val documentId: String = "",
) {
    val songwriters: List<String> get() = footer.songwriters

    /** True if any word carries romanized text (from TTML/API metadata or on-device romanization). */
    val hasTransliteration: Boolean
        get() = lines.any { line -> line.words.any { it.romanizedText != null } }
}

typealias ParsedLyrics = LyricsDocument
