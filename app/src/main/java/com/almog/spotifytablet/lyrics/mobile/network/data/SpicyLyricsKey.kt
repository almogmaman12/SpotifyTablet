package com.almog.spotifytablet.lyrics.mobile.network.data

/**
 * The person's own Spicy Lyrics key. Spicy Lyrics Mobile is an app template on the developer site:
 * adding it there gives each person their own application and a client key to paste here.
 * Without one the app uses the built-in key. Rate limits are per IP address, not per key.
 */
object SpicyLyricsKey {
    /** Spicy Lyrics Mobile's page in the developer site's catalog, where Add hands out the key. */
    const val CATALOG_URL = "https://developers.spicylyrics.org/catalog/spicy-player"

    sealed interface Check {
        data class Ok(val key: String) : Check
        /** Blank: back to the built-in key. */
        data object Empty : Check
        /** A secret key, which must never ship in an app; the template's client key is the one. */
        data object Secret : Check
        data object Invalid : Check
    }

    fun check(input: String): Check {
        val key = input.trim()
        return when {
            key.isEmpty() -> Check.Empty
            key.startsWith("sl_sk_") -> Check.Secret
            CLIENT_KEY.matches(key) -> Check.Ok(key)
            else -> Check.Invalid
        }
    }

    /** Enough of the key to recognise it, e.g. "sl_pk_…x7Qa". */
    fun hint(key: String): String = if (key.length <= 10) key else "sl_pk_…${key.takeLast(4)}"

    private val CLIENT_KEY = Regex("sl_pk_[A-Za-z0-9_-]{8,}")
}
