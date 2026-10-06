# 🎵 SpotifyTablet

A customized Android dashboard designed for dedicated wall-mounted or tabletop tablets. Seamlessly combines real-time music control, synchronized scrolling lyrics, smart home toggles, local media server monitoring, and ambient display modes.

---

## ✨ Features

- **Now Playing & Playback Controls**:
  - Live Spotify player tracking (progress bar, duration, volume controls, play/pause, next/previous).
  - Dynamic UI coloring extracted dynamically from current album artwork using `AndroidX Palette`.
  - Background dynamic blur effects.
- **Synchronized Lyrics Engine**:
  - Word-by-word karaoke style & synced line-by-line animations powered by **Jetpack Compose**.
  - Multi-tiered provider pipeline with circuit breaker protection (LRCLIB, Lrcmux, local Jellyfin).
  - Smart caching: In-memory LRU cache + persistent 50MB disk cache.
  - Word timing synthesis when only line-level LRC is available.
- **Jellyfin Media Server Support**:
  - Seamless tracking of local Jellyfin audio playback.
  - Automatic detection and switching between Spotify and Jellyfin as media playback shifts.
- **Smart Home & Automation**:
  - **Home Assistant** webhooks integration (quick toggles for room lights, cinema / project mode triggers).
  - **MacroDroid** webhooks support for device automation and ADB actions.
- **Ambient & Glanceable Info**:
  - Live weather display (current temperature, conditions, and icon via WeatherAPI).
  - Jewish Shabbat / Havdalah candle-lighting times via Hebcal.
  - Time & greeting headers.
  - **Always-on Display (AOD) / OLED Burn-in Protection**: Drifting clock and dimmed info overlay to safeguard OLED and AMOLED screens when idle.

---

## 🛠️ Tech Stack

- **Platform**: Android (Min SDK 29 / Android 10, Target SDK 35)
- **Languages**: Kotlin + Java
- **UI**: Android Views + Jetpack Compose (Interop via `ComposeView`)
- **Networking**: OkHttp 4
- **Image Processing**: Glide, Blurry, AndroidX Palette
- **Architecture**: MVVM with tiered repositories, Kotlin Coroutines & Flow

---

## 🚀 Getting Started & Setup

### 1. Prerequisites
- [Android Studio](https://developer.android.com/studio) Ladybug or newer.
- Android SDK 35.
- A physical Android tablet (Android 10+) or emulator.

### 2. Clone the Repository
```bash
git clone https://github.com/YOUR_USERNAME/SpotifyTablet.git
cd SpotifyTablet
```

### 3. Configure Secrets (`local.properties`)
Sensitive API keys, endpoints, and credentials are kept out of source control using `BuildConfig` fields injected at build time.

1. Make a copy of `local.properties.example` named `local.properties`:
   ```bash
   cp local.properties.example local.properties
   ```
2. Open `local.properties` and fill in your actual credentials:
   ```properties
   # Spotify Developer Dashboard (https://developer.spotify.com/dashboard)
   SPOTIFY_CLIENT_ID=your_spotify_client_id
   SPOTIFY_CLIENT_SECRET=your_spotify_client_secret

   # WeatherAPI (https://www.weatherapi.com/)
   WEATHER_API_KEY=your_weatherapi_key
   DEFAULT_WEATHER_CITY=31.7256,35.2225

   # Jellyfin Server
   DEFAULT_JELLYFIN_URL=http://192.168.1.100:8096
   DEFAULT_JELLYFIN_API_KEY=your_jellyfin_api_key
   DEFAULT_JELLYFIN_TARGET_USER=your_username

   # Home Assistant Webhooks
   DEFAULT_HA_URL=http://192.168.1.100:8123
   DEFAULT_LIGHT_WEBHOOK_URL=http://192.168.1.100:8123/api/webhook/light_toggle
   DEFAULT_HA_PROJECT_WEBHOOK_URL=http://192.168.1.100:8123/api/webhook/project_mode

   # MacroDroid Webhook
   DEFAULT_MACRODROID_WEBHOOK_URL=https://trigger.macrodroid.com/your-uuid/your-trigger
   ```

### 4. Spotify Developer App Configuration
In your [Spotify Developer Dashboard](https://developer.spotify.com/dashboard):
- Set **Redirect URI** to:
  ```text
  yourapp://callback
  ```
- Enable the necessary scopes for reading playback state and modifying playback.

### 5. Build and Run
Open the project in Android Studio, sync Gradle, and run on your tablet device!

---

## 🔒 Security Notice

Never commit your `local.properties` or keystore files to git. A pre-configured `.gitignore` is provided to safeguard:
- `local.properties`
- Keystore and signature credentials (`*.jks`, `*.keystore`, `key.properties`)
- Build outputs and temporary caches

---

## 📄 License

This is a personal open-source project intended for home automation and dashboard customization. Feel free to fork and adapt it for your own home tablet setup.
