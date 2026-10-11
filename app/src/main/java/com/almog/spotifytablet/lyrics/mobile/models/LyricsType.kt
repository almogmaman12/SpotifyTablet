package com.almog.spotifytablet.lyrics.mobile.models

/**
 * The synchronization granularity of a parsed lyrics document (`Syllable` / `Line` / `Static`).
 *
 * - [Syllable]: word/syllable-timed karaoke (full per-word gradient wipe + springs).
 * - [Line]: line-timed; the whole line fills as one gradient sweep.
 * - [Static]: unsynced text; rendered as a plain, non-interactive list.
 */
enum class LyricsType {
    Syllable,
    Line,
    Static,
}
