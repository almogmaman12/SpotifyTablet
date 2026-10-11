package com.almog.spotifytablet.lyrics.mobile

enum class SimpleAnimationStyle { CALCULATE, ANIMATE }

/** Whether a word's syllables are drawn as one word, filling evenly from its first syllable to its last. */
enum class SyllableMerge(val label: String) {
    Off("Off"),
    /** Every split word. */
    Full("Full"),
    /** Only words long enough, merged, for the held-word letter emphasis. */
    Held("Held words only"),
}

/** How the lyrics follow the song and answer a tap. None of it changes how lines are measured. */
data class ScrollConfig(
    /** Early Scroll: the view picks its line as if the song were this far ahead. 0 is off. */
    val leadMs: Long = 0L,
    /** Smooth Scrolling: a spring moves the view, carrying its speed into the next line. */
    val smooth: Boolean = false,
    /** Seek Fade-in Compensation: tapping a line seeks [SEEK_FADE_COMPENSATION_MS] before it. */
    val seekFadeCompensation: Boolean = false,
) {
    companion object {
        /** Players fade audio in for about this long after a seek, swallowing a line's first syllable. */
        const val SEEK_FADE_COMPENSATION_MS = 300L
    }
}

data class RenderConfig(
    val simpleLyricsMode: Boolean,
    /** Already scoped to a fullscreen, non-compact surface by the caller. */
    val minimalLyricsMode: Boolean,
    /**
     * The Apple Music style (see `animation/AppleMusicMotion`): its own wipe, word rise, held-word
     * letters, line fades and scroll. Simple and Minimal don't apply with it.
     */
    val appleMusic: Boolean = false,
    val simpleAnimationStyle: SimpleAnimationStyle = SimpleAnimationStyle.CALCULATE,
    /** Mixed.css `--gradient-alpha`: 1 under `.SimpleLyricsMode`. Background vocals keep 0.6. */
    val gradientAlphaBright: Float = if (simpleLyricsMode) 1f else 0.85f,
    /** `--gradient-alpha-end` on words and letters: 0.3 under `.SimpleLyricsMode`. */
    val gradientAlphaDim: Float = if (simpleLyricsMode) 0.3f else 0.5f,
    /** A whole line's `--gradient-alpha-end` (line-synced lyrics), `!important` in every mode. */
    val lineGradientAlphaDim: Float = 0.35f,
    val opacityActive: Float = 1f,
    /** `--Vocal-NotSung-opacity` / `--Vocal-Sung-opacity`; Minimal's own values live in the animator. */
    val opacityNotSung: Float = if (simpleLyricsMode) 0.45f else 0.51f,
    val opacitySung: Float = if (simpleLyricsMode) 0.35f else 0.497f,
    /** Simple mode deliberately keeps the distance blur. */
    val distanceBlurEnabled: Boolean = true,
    /** False drops every glow halo (the Glow setting, low performance mode). */
    val glowEnabled: Boolean = true,
    /** Minimal is a line-visibility layer and does not disable word/letter motion. */
    val lettersEnabled: Boolean = true,
    val letterDurationThresholdMs: Long = if (simpleLyricsMode) 1050L else 1000L,
    val letterMaxLength: Int = when {
        // The Apple Music style picks its held words itself (AppleMusicMotion.emphasizes).
        appleMusic -> Int.MAX_VALUE
        simpleLyricsMode -> 12
        else -> Int.MAX_VALUE
    },
    val syllableMerge: SyllableMerge = SyllableMerge.Off,
    /**
     * Multiplies how far sung words and letters grow (their scale away from 1) and lift
     * (their y offset). 1 is the desktop amount.
     */
    val wordMotionBoost: Float = 1f,
    /** Duet Line Padding: duet lines inset 15cqw on the side they lean away from, not 5cqw. */
    val wideDuetPadding: Boolean = true,
    val scroll: ScrollConfig = ScrollConfig(),
) {
    val isSimple: Boolean get() = simpleLyricsMode
    val isMinimal: Boolean get() = minimalLyricsMode
    val isAppleMusic: Boolean get() = appleMusic

    companion object {
        val FULL = RenderConfig(false, false)
        val SIMPLE = RenderConfig(true, false)
        val MINIMAL = RenderConfig(false, true)

        fun create(
            simpleLyricsMode: Boolean,
            minimalLyricsMode: Boolean,
            simpleAnimationStyle: SimpleAnimationStyle = SimpleAnimationStyle.CALCULATE,
            fullscreen: Boolean = true,
            compact: Boolean = false,
        ) = RenderConfig(
            simpleLyricsMode = simpleLyricsMode,
            minimalLyricsMode = minimalLyricsMode && fullscreen && !compact,
            simpleAnimationStyle = simpleAnimationStyle,
        )
    }
}
