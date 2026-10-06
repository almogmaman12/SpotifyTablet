# Word-for-Word Synchronized Lyrics Module (Jetpack Compose & Kotlin)

Design and implement a native Android module in Kotlin using Jetpack Compose that renders **word-for-word (syllable-level) synchronized lyrics** synced in real-time with audio playback, completely replacing the legacy lyrics implementation.

## User Review & Key Architectural Refinements Included
> [!IMPORTANT]
> 1. **Word Wrapping with FlowRow**: Using `FlowRow` from `androidx.compose.foundation.layout` to ensure long lyrics lines wrap smoothly without overflowing.
> 2. **Compose Draw-Phase Performance**: High-frequency playback position updates (60fps) are isolated using lambda position providers and `drawWithCache` / `clipRect` / `graphicsLayer` text fill shaders, preventing recomposition jank.
> 3. **Java Interop Bridge**: A dedicated `LyricsComposeBridge` object with `@JvmStatic` helpers provides a seamless API for `MainActivity.java`.
> 4. **Whitespace Preservation**: `TTMLParser` and `EnhancedLrcParser` explicitly preserve trailing/leading whitespace and token boundaries so words never concatenate (e.g. avoiding "HelloWorld").

---

## Proposed Architecture & Changes

### 1. Build & Dependencies Configuration
#### [MODIFY] [app/build.gradle](file:///c:/Users/almog/AndroidStudioProjects/SpotifyTablet2/app/build.gradle)
- Enable Kotlin Android (`alias(libs.plugins.kotlin.android)`) and Compose compiler plugin (`alias(libs.plugins.kotlin.compose)`).
- Enable `buildFeatures { compose = true }`.
- Add Compose dependencies:
  - Compose BOM (`libs.androidx.compose.bom`)
  - Compose UI (`libs.androidx.ui`, `libs.androidx.ui.graphics`, `libs.androidx.ui.tooling.preview`)
  - Compose Foundation (`androidx.compose.foundation:foundation`) for `FlowRow`
  - Material 3 (`libs.androidx.material3`)
  - Lifecycle Compose & ViewModel Compose (`androidx.lifecycle:lifecycle-viewmodel-compose`, `androidx.lifecycle:lifecycle-runtime-compose`)
  - Coroutines (`org.jetbrains.kotlinx:kotlinx-coroutines-android`)

### 2. Data Layer
#### [NEW] `com.almog.spotifytablet.lyrics.model.LyricModels.kt`
- `WordSync(val text: String, val startTimeMs: Long, val endTimeMs: Long)`
- `LyricLine(val startTimeMs: Long, val endTimeMs: Long, val words: List<WordSync>, val rawText: String)`
- `LyricTrack(val isWordSynced: Boolean, val lines: List<LyricLine>)`

### 3. Parsing Layer
#### [NEW] `com.almog.spotifytablet.lyrics.parser.TTMLParser.kt`
- Parse TTML XML strings containing `<p begin="..." end="...">` lines and nested `<span begin="..." end="...">` syllables/words.
- Support standard TTML time formats (`00:01:23.456`, `12.34s`, `01:23.45`).
- Preserve explicit spaces and word boundary punctuation.

#### [NEW] `com.almog.spotifytablet.lyrics.parser.EnhancedLrcParser.kt`
- Parse syllable/word-level LRC formats (e.g. `[00:12.34]<00:12.34>Hello <00:12.80>World`).
- Parse RichSync / JSON formats (Musixmatch / LyricsPlus / LRCLIB / Lrcmux).
- Fallback gracefully to line-level synchronized LRC when word timestamps are absent.
- Preserve spacing cleanly during tokenization.

### 4. Repository & State Management
#### [NEW] `com.almog.spotifytablet.lyrics.repository.LyricsRepository.kt`
- Multi-provider fetching (LRCLIB, LyricsPlus, Lrcmux, Musixmatch, TTML, Jellyfin).
- Memory LRU caching.
- Coroutine-based background network dispatching.

#### [NEW] `com.almog.spotifytablet.lyrics.viewmodel.LyricsViewModel.kt`
- Expose `StateFlow<LyricTrack?>` and position provider lambda / `StateFlow<Long>`.
- Active line index tracking and smooth seek events.

### 5. UI Rendering Layer (Jetpack Compose)
#### [NEW] `com.almog.spotifytablet.lyrics.ui.LyricsView.kt`
- `LazyColumn` driven by `LazyListState` with automatic smooth animated scrolling to keep the active line centered with spring/tween physics.
- `FlowRow` for word layout in each line.
- `drawWithCache` / `clipRect` progressive text fill highlighting without triggering re-layouts.
- Line alpha/scale styling:
  - Active line: Full opacity (1.0f), scaled up slightly (e.g. 1.05x).
  - Inactive/past lines: De-emphasized (alpha 0.5f).
  - Upcoming lines: Faded (alpha 0.3f).
- Click-to-seek support on any line.

#### [NEW] `com.almog.spotifytablet.lyrics.bridge.LyricsComposeBridge.kt`
- `@JvmStatic` helper methods for initializing and updating the ComposeView from Java.

### 6. Integration & Legacy Cleanup
#### [MODIFY] [MainActivity.java](file:///c:/Users/almog/AndroidStudioProjects/SpotifyTablet2/app/src/main/java/com/almog/spotifytablet/MainActivity.java)
- Replace legacy `lyricsTextView`, `GlowingWordSpan`, `renderLyricLine`, and old `LyricLine` references with `ComposeView` and `LyricsViewModel` via `LyricsComposeBridge`.
- Feed real-time playback position (`currentPositionMs`) and song changes directly to the lyrics state engine.
#### [MODIFY] [ProjectModeActivity.java](file:///c:/Users/almog/AndroidStudioProjects/SpotifyTablet2/app/src/main/java/com/almog/spotifytablet/ProjectModeActivity.java)
- Remove any remaining legacy lyrics references.
#### [DELETE] Old legacy classes:
- Delete `com.almog.spotifytablet.GlowingWordSpan.java`
- Delete old `com.almog.spotifytablet.LyricLine.java` (superseded by Kotlin `LyricModels.kt`)
- Delete old `com.almog.spotifytablet.LyricsManager.java` (superseded by modern Kotlin `LyricsRepository.kt` & `LyricsViewModel.kt`)
#### [MODIFY] Layout files:
- Replace `TextView id="@+id/lyricsTextView"` in `activity_main.xml` with `androidx.compose.ui.platform.ComposeView id="@+id/lyricsComposeView"`.

---

## Verification Plan

### Automated Build Verification
- `./gradlew compileDebugKotlin`
- `./gradlew assembleDebug`

### Manual / Functional Verification
- Verify word-by-word streaming & highlight animation with simulated/real LRCLIB / TTML / RichSync tracks.
- Verify smooth auto-scroll to active line and tap-to-seek responsiveness.
- Verify fallback to line-level synchronization when word-level timestamps are not present.
