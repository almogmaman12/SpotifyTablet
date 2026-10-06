# Word-for-Word Synchronized Lyrics Module Walkthrough

## Summary of Changes
We completely replaced the legacy lyrics system with a modern, high-performance native Android module in **Kotlin & Jetpack Compose** featuring word-for-word (syllable-level) synchronized lyrics with Apple Music / Spotify aesthetics.

---

## What Was Implemented

### 1. Data Layer (`LyricModels.kt`)
- Created [LyricModels.kt](file:///c:/Users/almog/AndroidStudioProjects/SpotifyTablet2/app/src/main/java/com/almog/spotifytablet/lyrics/model/LyricModels.kt) containing:
  - `WordSync(text, startTimeMs, endTimeMs, trailingSpace)`
  - `LyricLine(startTimeMs, endTimeMs, words, rawText)`
  - `LyricTrack(isWordSynced, lines, source)`

### 2. Parsing Layer (`TTMLParser.kt` & `EnhancedLrcParser.kt`)
- [TTMLParser.kt](file:///c:/Users/almog/AndroidStudioProjects/SpotifyTablet2/app/src/main/java/com/almog/spotifytablet/lyrics/parser/TTMLParser.kt):
  - Parses Apple Music TTML XML structures with line (`<p begin="..." end="...">`) and word/syllable (`<span begin="..." end="...">`) timing.
  - Preserves intra-tag and inter-tag whitespace to avoid text merging.
- [EnhancedLrcParser.kt](file:///c:/Users/almog/AndroidStudioProjects/SpotifyTablet2/app/src/main/java/com/almog/spotifytablet/lyrics/parser/EnhancedLrcParser.kt):
  - Handles enhanced LRC format (`[mm:ss.xx]<mm:ss.xx> Word <mm:ss.xx> NextWord`).
  - Handles Musixmatch RichSync JSON and LyricsPlus/Lrcmux payloads.
  - Gracefully falls back to standard line-synced LRC when syllable timestamps are unavailable.

### 3. Repository & State Layer (`LyricsRepository.kt` & `LyricsViewModel.kt`)
- [LyricsRepository.kt](file:///c:/Users/almog/AndroidStudioProjects/SpotifyTablet2/app/src/main/java/com/almog/spotifytablet/lyrics/repository/LyricsRepository.kt):
  - Multi-tier prioritized provider search (LyricsPlus -> Lrcmux -> LRCLIB -> Musixmatch -> Jellyfin).
  - In-memory LRU caching.
- [LyricsViewModel.kt](file:///c:/Users/almog/AndroidStudioProjects/SpotifyTablet2/app/src/main/java/com/almog/spotifytablet/lyrics/viewmodel/LyricsViewModel.kt):
  - Real-time `StateFlow` updates for track and active line index.
  - High-frequency position ticks without garbage collection pressure.

### 4. UI Layer (`LyricsView.kt` with Jetpack Compose)
- [LyricsView.kt](file:///c:/Users/almog/AndroidStudioProjects/SpotifyTablet2/app/src/main/java/com/almog/spotifytablet/lyrics/ui/LyricsView.kt):
  - `LazyColumn` auto-scrolling with smooth spring physics to center active lines.
  - `FlowRow` word wrapping to prevent overflow on long lines.
  - `drawWithCache` + `clipRect` progressive text fill shaders that execute purely in the draw phase, eliminating recomposition stutters on high-frequency playback position updates.
  - Inactive/past lines fading (alpha transitions) and active line focus scaling.
  - Click-to-seek support.

### 5. Java Interop & Legacy Removal
- [LyricsComposeBridge.kt](file:///c:/Users/almog/AndroidStudioProjects/SpotifyTablet2/app/src/main/java/com/almog/spotifytablet/lyrics/bridge/LyricsComposeBridge.kt): Clean `@JvmStatic` bridge for initializing the `ComposeView` from `MainActivity.java`.
- Removed old legacy files: `GlowingWordSpan.java`, `LyricLine.java`, and `LyricsManager.java`.
- Updated [activity_main.xml](file:///c:/Users/almog/AndroidStudioProjects/SpotifyTablet2/app/src/main/res/layout/activity_main.xml) with `ComposeView`.
- Enabled Kotlin Android & Compose compiler in [app/build.gradle](file:///c:/Users/almog/AndroidStudioProjects/SpotifyTablet2/app/build.gradle).

---

## Verification Results
- `./gradlew compileDebugKotlin` & `./gradlew assembleDebug` passed with **BUILD SUCCESSFUL**.
