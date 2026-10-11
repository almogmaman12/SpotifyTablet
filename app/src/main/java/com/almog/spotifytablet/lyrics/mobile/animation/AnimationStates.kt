package com.almog.spotifytablet.lyrics.mobile.animation

/**
 * Represents the animation state of a single letter within a word.
 */
data class LetterAnimState(
    val gradientPosition: Float,
    val scale: Float,
    val yOffset: Float,
    val glow: Float,
    /** Sideways offset in lyric font sizes; only the Apple Music style's held words spread apart. */
    val xOffset: Float = 0f,
    /** The Apple Music style's glow radius, in lyric font sizes. */
    val glowRadius: Float = 0f,
)

/**
 * Represents the animation state of a word, including its scale, offset, and potential letter-level states.
 */
data class WordAnimState(
    val scale: Float,
    val yOffset: Float,
    val glow: Float,
    val gradientPosition: Float,
    val state: ElementState,
    /** For interlude dots only: the animated glow level (blur/opacity of the dot's halo). */
    val dotGlow: Float = 0f,
    val isLetterGroup: Boolean = false,
    val letterStates: List<LetterAnimState> = emptyList(),
)

/**
 * Represents the overall animation state of a line, including its words.
 */
data class LineAnimState(
    val opacity: Float,
    val blur: Float,
    val scale: Float,
    val isActive: Boolean,
    val wordStates: List<WordAnimState>,
    val isBackground: Boolean,
    val isSongwriter: Boolean,
    /** Line-mode only: the raw gradient position percent for the whole-line wipe (-20..100). */
    val lineGradientPercent: Float = -20f,
    /** Line-mode only: the whole-line glow spring value (shadow blur 4+8·glow, alpha glow·0.5). */
    val lineGlow: Float = 0f,
    /** Every glow halo is suppressed (low performance mode), not just distance blur. */
    val suppressShadows: Boolean = false,
    val state: ElementState = ElementState.NotSung,
    /** The Apple Music style's background vocals: how far open (0 folded away and taking no room, 1 open). */
    val presence: Float = 1f,
    /** The Apple Music style: how lit an ended line still is, 1 just ended → 0 back to the unsung colour. */
    val lit: Float = 0f,
    /** Interlude dots: the whole group's breathing, about its middle, on top of [scale]. */
    val groupScale: Float = 1f,
)

/**
 * Possible states for a lyric element (word, line, or dot).
 */
enum class ElementState { NotSung, Active, Sung }
