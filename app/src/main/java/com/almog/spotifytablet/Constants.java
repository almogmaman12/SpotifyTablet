package com.almog.spotifytablet;

import okhttp3.MediaType;

/**
 * Centralized Application Configuration & Settings File.
 * Secrets are injected at build time from local.properties via BuildConfig.
 * To configure: copy local.properties.example to local.properties and fill in your values.
 */
public final class Constants {
    private Constants() {} // Prevent instantiation

    // =========================================================================
    // 1. SPOTIFY AUTHENTICATION & LOGIN
    // =========================================================================
    public static final String SPOTIFY_CLIENT_ID = BuildConfig.SPOTIFY_CLIENT_ID; // Injected from local.properties
    public static final String SPOTIFY_CLIENT_SECRET = BuildConfig.SPOTIFY_CLIENT_SECRET; // Injected from local.properties
    public static final String SPOTIFY_REDIRECT_URI = "yourapp://callback"; // Spotify OAuth Redirect URI registered in Spotify Dashboard
    public static final String SPOTIFY_TOKEN_URL = "https://accounts.spotify.com/api/token"; // Spotify API Endpoint for requesting access tokens
    public static final String SPOTIFY_AUTH_BASE_URL = "https://accounts.spotify.com/authorize"; // Spotify User Login Authorization Web Page URL
    public static final String SPOTIFY_SCOPES = "user-read-playback-state user-read-currently-playing user-modify-playback-state"; // Permissions requested from Spotify
    public static final String SPOTIFY_DJ_PLAYLIST_URI = "spotify:playlist:37i9dQZF1EYkqdzj48dyYq"; // Spotify AI DJ Playlist URI identifier
    public static final String SPOTIFY_DJ_CONTEXT_KEY = "context_uri"; // JSON payload parameter key for starting Spotify DJ playlist

    // Full Spotify Auth Web Page Intent URL
    public static final String SPOTIFY_AUTH_URI = SPOTIFY_AUTH_BASE_URL +
            "?client_id=" + SPOTIFY_CLIENT_ID +
            "&response_type=code" +
            "&redirect_uri=" + SPOTIFY_REDIRECT_URI +
            "&scope=" + SPOTIFY_SCOPES;

    // =========================================================================
    // 2. JELLYFIN SERVER & AUTHENTICATION
    // =========================================================================
    public static final String DEFAULT_JELLYFIN_URL = BuildConfig.DEFAULT_JELLYFIN_URL; // Injected from local.properties
    public static final String DEFAULT_JELLYFIN_API_KEY = BuildConfig.DEFAULT_JELLYFIN_API_KEY; // Injected from local.properties

    public static final String DEFAULT_JELLYFIN_TARGET_USER = BuildConfig.DEFAULT_JELLYFIN_TARGET_USER; // Injected from local.properties
    public static final String JELLYFIN_TOKEN_HEADER = "Authorization"; // HTTP Header key required by Jellyfin API authentication
    public static String jellyfinAuthHeaderValue(String apiKey) {
        return "MediaBrowser Token=\"" + apiKey + "\"";
    }

    // =========================================================================
    // 3. HOME ASSISTANT & MACRODROID WEBHOOKS
    // =========================================================================
    public static final String DEFAULT_HA_URL = BuildConfig.DEFAULT_HA_URL; // Injected from local.properties
    public static final String DEFAULT_LIGHT_WEBHOOK_URL = BuildConfig.DEFAULT_LIGHT_WEBHOOK_URL; // Injected from local.properties
    public static final String DEFAULT_MACRODROID_WEBHOOK_URL = BuildConfig.DEFAULT_MACRODROID_WEBHOOK_URL; // Injected from local.properties
    public static final String DEFAULT_HA_PROJECT_WEBHOOK_URL = BuildConfig.DEFAULT_HA_PROJECT_WEBHOOK_URL; // Injected from local.properties

    // =========================================================================
    // 4. WEATHER API & LOCATION
    // =========================================================================
    public static final String WEATHER_API_KEY = BuildConfig.WEATHER_API_KEY; // Injected from local.properties
    public static final String DEFAULT_WEATHER_CITY = BuildConfig.DEFAULT_WEATHER_CITY; // Injected from local.properties
    public static final String SHABBAT_API_URL = "https://www.hebcal.com/shabbat?cfg=json&latitude=" + DEFAULT_WEATHER_CITY.split(",")[0] + "&longitude=" + DEFAULT_WEATHER_CITY.split(",")[1] + "&tzid=Asia/Jerusalem&b=40&havdalahDeg=8.5&ue=on&elev=785"; // Hebcal Shabbat times API URL built from weather coordinates
    public static final String WEATHER_BASE_URL = "https://api.weatherapi.com/v1/"; // Base API URL for WeatherAPI service

    // =========================================================================
    // 5. SHAREDPREFERENCES STORAGE KEYS (APP PREFERENCES)
    // =========================================================================
    public static final String PREF_NAME = "SpotifyPrefs"; // SharedPreferences file storage name
    public static final String PREF_KEY_JELLYFIN_SERVER_URL = "jellyfin_server_url"; // Key for storing Jellyfin server URL setting
    public static final String PREF_KEY_JELLYFIN_API_KEY = "jellyfin_api_key"; // Key for storing Jellyfin API key setting
    public static final String PREF_KEY_JELLYFIN_CLIENT_NAME = "jellyfin_client_name"; // Key for storing Jellyfin client device filter
    public static final String PREF_KEY_HA_TOKEN = "ha_token"; // Key for storing Home Assistant Long-Lived Access Token
    public static final String PREF_KEY_HA_URL = "ha_url"; // Key for storing Home Assistant Server URL setting
    public static final String PREF_KEY_SPOTIFY_UPDATES_ENABLED = "spotify_updates_enabled"; // Key for toggling background Spotify updates
    public static final String PREF_KEY_MEDIA_SOURCE = "media_source"; // Key for storing active media player source ('spotify', 'jellyfin', 'auto')
    public static final String PREF_KEY_LAST_VOLUME = "last_volume"; // Key for storing last set playback volume percentage
    public static final String PREF_KEY_ACCESS_TOKEN = "access_token"; // Key for storing cached Spotify access token
    public static final String PREF_KEY_REFRESH_TOKEN = "refresh_token"; // Key for storing cached Spotify refresh token
    public static final String PREF_KEY_EXPIRES_AT = "expires_at"; // Key for storing token expiry timestamp

    // New user-configurable setting keys
    public static final String PREF_KEY_WEATHER_CITY = "weather_city"; // GPS coordinates or city name for weather
    public static final String PREF_KEY_VOLUME_STEP = "volume_step"; // Volume increment per button press (1–20%)
    public static final String PREF_KEY_SONG_POLL_INTERVAL = "song_poll_interval_ms"; // Polling interval while playing (ms)
    public static final String PREF_KEY_PAUSED_POLL_INTERVAL = "paused_poll_interval_ms"; // Polling interval while paused (ms)
    public static final String PREF_KEY_BLUR_INTENSITY = "blur_intensity"; // Background blur radius (20–200)
    public static final String PREF_KEY_JELLYFIN_TARGET_USER = "jellyfin_target_user"; // Jellyfin username to track
    public static final String PREF_KEY_LIGHT_ENTITY_ID = "ha_light_entity"; // Home Assistant light entity ID(s)
    public static final String PREF_KEY_VOL_UP_WEBHOOK = "ha_vol_up_webhook"; // HA webhook URL for volume up
    public static final String PREF_KEY_VOL_DOWN_WEBHOOK = "ha_vol_down_webhook"; // HA webhook URL for volume down
    public static final String PREF_KEY_AOD_SHOW_SONG = "aod_show_song_on_pause"; // Show song name on AOD clock when paused
    public static final String PREF_KEY_LYRICS_FONT_SIZE = "lyrics_font_size_sp"; // Active lyric line font size in sp (24–48)

    // =========================================================================
    // 6. INTENT EXTRA KEYS (ACTIVITY DATA PASSING)
    // =========================================================================
    public static final String EXTRA_SPOTIFY_TOKEN = "spotify_token"; // Key for passing Spotify OAuth Access Token between Activities

    // =========================================================================
    // 7. TIMEOUTS, REFRESH INTERVALS & APP BEHAVIOR
    // =========================================================================
    public static final int VOLUME_STEP = 4; // Percentage volume change per button click (+/- 4%)
    public static final long VOLUME_REPEAT_MS = 200L; // Delay interval (ms) when holding down volume control buttons
    public static final int VISIBLE_DAYS = 3; // Number of daily weather forecast columns shown on screen
    public static final long IDLE_EXIT_TIMEOUT_MS = 10 * 60 * 1000L; // Inactivity timeout (10 minutes) before returning to main screen
    public static final long SONG_UPDATE_INTERVAL = 5000L; // Polling interval (5 seconds) for active playing song metadata
    public static final long PAUSED_SONG_UPDATE_INTERVAL = 15000L; // Polling interval (15 seconds) when music is currently paused
    public static final long PROGRESS_UPDATE_INTERVAL = 100L; // UI progress bar smooth animation update step (100 ms)
    public static final long CLOCK_MOVE_INTERVAL = 60000L; // Clock position shift interval (1 minute) to prevent OLED burn-in

    // =========================================================================
    // 8. HTTP NETWORK MEDIA TYPES
    // =========================================================================
    public static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8"); // Content type header for JSON requests
    public static final MediaType FORM_MEDIA_TYPE = MediaType.parse("application/x-www-form-urlencoded; charset=utf-8"); // Content type header for OAuth form requests
}
