package com.almog.spotifytablet;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;

public class
SettingsActivity extends AppCompatActivity {

    public static final String PREF_HOME_STYLE = "home_screen_style";
    public static final String STYLE_OLD = "old";
    public static final String STYLE_NEW = "new";

    public static final String PREF_KEEP_SCREEN_ON = "keep_screen_on";
    public static final String PREF_DYNAMIC_SPACING = "lyrics_dynamic_spacing";
    public static final String PREF_PAUSE_DOTS = "lyrics_pause_dots_enabled";

    // -----------------------------------------------------------------------
    // Views
    // -----------------------------------------------------------------------

    // Display & Dashboard
    private RadioGroup radioGroupHomeScreen;
    private RadioButton radioHomeScreenNew;
    private RadioButton radioHomeScreenOld;
    private SwitchMaterial switchKeepScreenOn;
    private SeekBar seekBlurIntensity;
    private TextView tvBlurValue;
    private SwitchMaterial switchAodShowSong;

    // Lyrics Engine
    private SwitchMaterial switchLyricsAnimation;
    private SwitchMaterial switchDynamicSpacing;
    private SwitchMaterial switchPauseDots;
    private SeekBar seekLyricsFontSize;
    private TextView tvLyricsFontValue;

    // Playback & Sources
    private RadioGroup radioGroupMediaSource;
    private RadioButton radioSourceAuto;
    private RadioButton radioSourceSpotify;
    private RadioButton radioSourceJellyfin;
    private SwitchMaterial switchSpotifyUpdates;
    private SeekBar seekPollPlaying;
    private TextView tvPollPlayingValue;
    private SeekBar seekPollPaused;
    private TextView tvPollPausedValue;
    private SeekBar seekVolumeStep;
    private TextView tvVolumeStepValue;

    // Jellyfin
    private TextInputEditText etJellyfinUrl;
    private TextInputEditText etJellyfinApiKey;
    private TextInputEditText etJellyfinTargetUser;
    private TextInputEditText etJellyfinClient;
    private MaterialButton btnSaveJellyfin;

    // Home Assistant
    private TextInputEditText etHaUrl;
    private TextInputEditText etHaToken;
    private TextInputEditText etHaLightEntity;
    private TextInputEditText etVolUpWebhook;
    private TextInputEditText etVolDownWebhook;
    private MaterialButton btnSaveHa;

    // Weather
    private TextInputEditText etWeatherCity;
    private MaterialButton btnSaveWeather;

    // Cache & Account
    private MaterialButton btnClearLyricsCache;
    private MaterialButton btnResetSettings;
    private MaterialButton btnReauthorizeSpotify;

    // -----------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
        );
        if (getSupportActionBar() != null) getSupportActionBar().hide();

        setContentView(R.layout.activity_settings);

        ImageButton btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());

        SharedPreferences prefs = getSharedPreferences(Constants.PREF_NAME, MODE_PRIVATE);

        // ===================================================================
        // 1. DISPLAY & DASHBOARD
        // ===================================================================
        radioGroupHomeScreen = findViewById(R.id.radioGroupHomeScreen);
        radioHomeScreenNew   = findViewById(R.id.radioHomeScreenNew);
        radioHomeScreenOld   = findViewById(R.id.radioHomeScreenOld);

        String currentStyle = prefs.getString(PREF_HOME_STYLE, STYLE_NEW);
        if (STYLE_OLD.equals(currentStyle)) radioHomeScreenOld.setChecked(true);
        else                                 radioHomeScreenNew.setChecked(true);

        radioGroupHomeScreen.setOnCheckedChangeListener((g, id) -> {
            String s = (id == R.id.radioHomeScreenOld) ? STYLE_OLD : STYLE_NEW;
            prefs.edit().putString(PREF_HOME_STYLE, s).apply();
            Toast.makeText(this, "Dashboard layout updated", Toast.LENGTH_SHORT).show();
        });

        // Keep Screen On
        switchKeepScreenOn = findViewById(R.id.switchKeepScreenOn);
        switchKeepScreenOn.setChecked(prefs.getBoolean(PREF_KEEP_SCREEN_ON, true));
        switchKeepScreenOn.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean(PREF_KEEP_SCREEN_ON, on).apply();
            if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });

        // Background Blur Intensity
        seekBlurIntensity = findViewById(R.id.seekBlurIntensity);
        tvBlurValue        = findViewById(R.id.tvBlurValue);
        int blurSaved = prefs.getInt(Constants.PREF_KEY_BLUR_INTENSITY, 100);
        seekBlurIntensity.setProgress(blurSaved - 10); // seekBar min=10 stored as progress offset
        tvBlurValue.setText(String.valueOf(blurSaved));
        seekBlurIntensity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                int v = p + 10;
                tvBlurValue.setText(String.valueOf(v));
                prefs.edit().putInt(Constants.PREF_KEY_BLUR_INTENSITY, v).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        // AOD show song on pause
        switchAodShowSong = findViewById(R.id.switchAodShowSong);
        switchAodShowSong.setChecked(prefs.getBoolean(Constants.PREF_KEY_AOD_SHOW_SONG, true));
        switchAodShowSong.setOnCheckedChangeListener((b, on) ->
                prefs.edit().putBoolean(Constants.PREF_KEY_AOD_SHOW_SONG, on).apply());

        // ===================================================================
        // 2. LYRICS ENGINE
        // ===================================================================
        switchLyricsAnimation = findViewById(R.id.switchLyricsAnimation);
        switchLyricsAnimation.setChecked(prefs.getBoolean("lyrics_animation_enabled", true));
        switchLyricsAnimation.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean("lyrics_animation_enabled", on).apply();
            Toast.makeText(this, on ? "Lyrics animation enabled" : "Disabled", Toast.LENGTH_SHORT).show();
        });

        switchDynamicSpacing = findViewById(R.id.switchDynamicSpacing);
        switchDynamicSpacing.setChecked(prefs.getBoolean(PREF_DYNAMIC_SPACING, true));
        switchDynamicSpacing.setOnCheckedChangeListener((b, on) ->
                prefs.edit().putBoolean(PREF_DYNAMIC_SPACING, on).apply());

        switchPauseDots = findViewById(R.id.switchPauseDots);
        switchPauseDots.setChecked(prefs.getBoolean(PREF_PAUSE_DOTS, true));
        switchPauseDots.setOnCheckedChangeListener((b, on) ->
                prefs.edit().putBoolean(PREF_PAUSE_DOTS, on).apply());

        // Lyrics Font Size: seekBar progress 0..24 → sp 24..48
        seekLyricsFontSize = findViewById(R.id.seekLyricsFontSize);
        tvLyricsFontValue  = findViewById(R.id.tvLyricsFontValue);
        int savedFontSp = prefs.getInt(Constants.PREF_KEY_LYRICS_FONT_SIZE, 32);
        int fontProgress = Math.max(0, Math.min(24, savedFontSp - 24));
        seekLyricsFontSize.setProgress(fontProgress);
        tvLyricsFontValue.setText(savedFontSp + "sp");
        seekLyricsFontSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                int sp = p + 24;
                tvLyricsFontValue.setText(sp + "sp");
                prefs.edit().putInt(Constants.PREF_KEY_LYRICS_FONT_SIZE, sp).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        // ===================================================================
        // 3. PLAYBACK & SOURCES
        // ===================================================================
        radioGroupMediaSource = findViewById(R.id.radioGroupMediaSource);
        radioSourceAuto       = findViewById(R.id.radioSourceAuto);
        radioSourceSpotify    = findViewById(R.id.radioSourceSpotify);
        radioSourceJellyfin   = findViewById(R.id.radioSourceJellyfin);

        String src = prefs.getString(Constants.PREF_KEY_MEDIA_SOURCE, "auto");
        if ("spotify".equals(src))   radioSourceSpotify.setChecked(true);
        else if ("jellyfin".equals(src)) radioSourceJellyfin.setChecked(true);
        else                          radioSourceAuto.setChecked(true);

        radioGroupMediaSource.setOnCheckedChangeListener((g, id) -> {
            String s = "auto";
            if (id == R.id.radioSourceSpotify) s = "spotify";
            else if (id == R.id.radioSourceJellyfin) s = "jellyfin";
            prefs.edit().putString(Constants.PREF_KEY_MEDIA_SOURCE, s).apply();
            Toast.makeText(this, "Active source: " + s, Toast.LENGTH_SHORT).show();
        });

        switchSpotifyUpdates = findViewById(R.id.switchSpotifyUpdates);
        switchSpotifyUpdates.setChecked(prefs.getBoolean(Constants.PREF_KEY_SPOTIFY_UPDATES_ENABLED, true));
        switchSpotifyUpdates.setOnCheckedChangeListener((b, on) ->
                prefs.edit().putBoolean(Constants.PREF_KEY_SPOTIFY_UPDATES_ENABLED, on).apply());

        // Polling interval — playing (seekBar: 0..55 → 1..56 s)
        seekPollPlaying    = findViewById(R.id.seekPollPlaying);
        tvPollPlayingValue = findViewById(R.id.tvPollPlayingValue);
        long savedPollPlay = prefs.getLong(Constants.PREF_KEY_SONG_POLL_INTERVAL, 5000L);
        int playProgress   = (int) Math.max(0, Math.min(55, savedPollPlay / 1000L - 1));
        seekPollPlaying.setProgress(playProgress);
        tvPollPlayingValue.setText((playProgress + 1) + "s");
        seekPollPlaying.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                long ms = (p + 1) * 1000L;
                tvPollPlayingValue.setText((p + 1) + "s");
                prefs.edit().putLong(Constants.PREF_KEY_SONG_POLL_INTERVAL, ms).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        // Polling interval — paused
        seekPollPaused    = findViewById(R.id.seekPollPaused);
        tvPollPausedValue = findViewById(R.id.tvPollPausedValue);
        long savedPollPaused = prefs.getLong(Constants.PREF_KEY_PAUSED_POLL_INTERVAL, 15000L);
        int pausedProgress   = (int) Math.max(0, Math.min(55, savedPollPaused / 1000L - 1));
        seekPollPaused.setProgress(pausedProgress);
        tvPollPausedValue.setText((pausedProgress + 1) + "s");
        seekPollPaused.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                long ms = (p + 1) * 1000L;
                tvPollPausedValue.setText((p + 1) + "s");
                prefs.edit().putLong(Constants.PREF_KEY_PAUSED_POLL_INTERVAL, ms).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        // Volume Step (seekBar: 0..19 → 1..20 %)
        seekVolumeStep    = findViewById(R.id.seekVolumeStep);
        tvVolumeStepValue = findViewById(R.id.tvVolumeStepValue);
        int savedStep     = prefs.getInt(Constants.PREF_KEY_VOLUME_STEP, Constants.VOLUME_STEP);
        int stepProgress  = Math.max(0, Math.min(19, savedStep - 1));
        seekVolumeStep.setProgress(stepProgress);
        tvVolumeStepValue.setText(savedStep + "%");
        seekVolumeStep.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                int v = p + 1;
                tvVolumeStepValue.setText(v + "%");
                prefs.edit().putInt(Constants.PREF_KEY_VOLUME_STEP, v).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        // ===================================================================
        // 4. JELLYFIN
        // ===================================================================
        etJellyfinUrl        = findViewById(R.id.etJellyfinUrl);
        etJellyfinApiKey     = findViewById(R.id.etJellyfinApiKey);
        etJellyfinTargetUser = findViewById(R.id.etJellyfinTargetUser);
        etJellyfinClient     = findViewById(R.id.etJellyfinClient);
        btnSaveJellyfin      = findViewById(R.id.btnSaveJellyfin);

        etJellyfinUrl.setText(prefs.getString(Constants.PREF_KEY_JELLYFIN_SERVER_URL, Constants.DEFAULT_JELLYFIN_URL));
        etJellyfinApiKey.setText(prefs.getString(Constants.PREF_KEY_JELLYFIN_API_KEY, Constants.DEFAULT_JELLYFIN_API_KEY));
        etJellyfinTargetUser.setText(prefs.getString(Constants.PREF_KEY_JELLYFIN_TARGET_USER, Constants.DEFAULT_JELLYFIN_TARGET_USER));
        etJellyfinClient.setText(prefs.getString(Constants.PREF_KEY_JELLYFIN_CLIENT_NAME, ""));

        btnSaveJellyfin.setOnClickListener(v -> {
            String url    = str(etJellyfinUrl);
            String key    = str(etJellyfinApiKey);
            String user   = str(etJellyfinTargetUser);
            String client = str(etJellyfinClient);
            prefs.edit()
                    .putString(Constants.PREF_KEY_JELLYFIN_SERVER_URL, url)
                    .putString(Constants.PREF_KEY_JELLYFIN_API_KEY, key)
                    .putString(Constants.PREF_KEY_JELLYFIN_TARGET_USER, user)
                    .putString(Constants.PREF_KEY_JELLYFIN_CLIENT_NAME, client)
                    .apply();
            Toast.makeText(this, "Jellyfin settings saved", Toast.LENGTH_SHORT).show();
        });

        // ===================================================================
        // 5. HOME ASSISTANT
        // ===================================================================
        etHaUrl        = findViewById(R.id.etHaUrl);
        etHaToken      = findViewById(R.id.etHaToken);
        etHaLightEntity = findViewById(R.id.etHaLightEntity);
        etVolUpWebhook  = findViewById(R.id.etVolUpWebhook);
        etVolDownWebhook = findViewById(R.id.etVolDownWebhook);
        btnSaveHa       = findViewById(R.id.btnSaveHa);

        etHaUrl.setText(prefs.getString(Constants.PREF_KEY_HA_URL, Constants.DEFAULT_HA_URL));
        etHaToken.setText(prefs.getString(Constants.PREF_KEY_HA_TOKEN, ""));
        etHaLightEntity.setText(prefs.getString(Constants.PREF_KEY_LIGHT_ENTITY_ID,
                "light.lmvg_1, light.lmvg_2"));
        etVolUpWebhook.setText(prefs.getString(Constants.PREF_KEY_VOL_UP_WEBHOOK, ""));
        etVolDownWebhook.setText(prefs.getString(Constants.PREF_KEY_VOL_DOWN_WEBHOOK, ""));

        btnSaveHa.setOnClickListener(v -> {
            prefs.edit()
                    .putString(Constants.PREF_KEY_HA_URL, str(etHaUrl))
                    .putString(Constants.PREF_KEY_HA_TOKEN, str(etHaToken))
                    .putString(Constants.PREF_KEY_LIGHT_ENTITY_ID, str(etHaLightEntity))
                    .putString(Constants.PREF_KEY_VOL_UP_WEBHOOK, str(etVolUpWebhook))
                    .putString(Constants.PREF_KEY_VOL_DOWN_WEBHOOK, str(etVolDownWebhook))
                    .apply();
            Toast.makeText(this, "Smart Home settings saved", Toast.LENGTH_SHORT).show();
        });

        // ===================================================================
        // 6. WEATHER & LOCATION
        // ===================================================================
        etWeatherCity  = findViewById(R.id.etWeatherCity);
        btnSaveWeather = findViewById(R.id.btnSaveWeather);

        etWeatherCity.setText(prefs.getString(Constants.PREF_KEY_WEATHER_CITY,
                Constants.DEFAULT_WEATHER_CITY));

        btnSaveWeather.setOnClickListener(v -> {
            prefs.edit().putString(Constants.PREF_KEY_WEATHER_CITY, str(etWeatherCity)).apply();
            Toast.makeText(this, "Location saved — restart app to apply", Toast.LENGTH_SHORT).show();
        });

        // ===================================================================
        // 7. ACCOUNT & CACHE
        // ===================================================================
        btnClearLyricsCache = findViewById(R.id.btnClearLyricsCache);
        btnClearLyricsCache.setOnClickListener(v ->
                new AlertDialog.Builder(this)
                        .setTitle("Clear Lyrics Cache?")
                        .setMessage("This will delete all cached lyrics from disk and memory. They will be re-fetched from the network next time each song plays.")
                        .setPositiveButton("Clear", (d, w) -> {
                            // Clears both disk cache (lyrics_cache/) and in-memory LruCache
                            com.almog.spotifytablet.lyrics.repository.LyricsRepository.clearAllCache();
                            deleteRecursively(new java.io.File(getCacheDir(), "lyrics"));
                            deleteRecursively(new java.io.File(getCacheDir(), "translations"));
                            Toast.makeText(this, "Lyrics cache cleared", Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton("Cancel", null)
                        .show()
        );

        setupSpicyMobileSettings();

        btnResetSettings = findViewById(R.id.btnResetSettings);
        btnResetSettings.setOnClickListener(v ->
                new AlertDialog.Builder(this)
                        .setTitle("Reset All Settings?")
                        .setMessage("This will erase all API keys, credentials, and preferences. Are you sure?")
                        .setPositiveButton("Reset", (d, w) -> {
                            prefs.edit().clear().apply();
                            Toast.makeText(this, "All settings reset to defaults", Toast.LENGTH_LONG).show();
                            finish();
                        })
                        .setNegativeButton("Cancel", null)
                        .show()
        );

        btnReauthorizeSpotify = findViewById(R.id.btnReauthorizeSpotify);
        btnReauthorizeSpotify.setOnClickListener(v ->
                new AlertDialog.Builder(this)
                        .setTitle("Log Out of Spotify?")
                        .setMessage("Clears saved OAuth tokens. You will need to log in again on the next launch.")
                        .setPositiveButton("Log Out", (d, w) -> {
                            prefs.edit()
                                    .remove(Constants.PREF_KEY_ACCESS_TOKEN)
                                    .remove(Constants.PREF_KEY_REFRESH_TOKEN)
                                    .remove(Constants.PREF_KEY_EXPIRES_AT)
                                    .apply();
                            Toast.makeText(this, "Spotify session cleared", Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton("Cancel", null)
                        .show()
        );
    }

    /** Safe non-null text extractor from TextInputEditText. */
    private String str(TextInputEditText et) {
        return et.getText() != null ? et.getText().toString().trim() : "";
    }

    private static void deleteRecursively(java.io.File f) {
        if (f == null || !f.exists()) return;
        java.io.File[] children = f.listFiles();
        if (children != null) for (java.io.File c : children) deleteRecursively(c);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    // ===================================================================
    // Spicy Lyrics Mobile renderer / romanization / translation (added programmatically)
    // ===================================================================
    private static final String[] RENDERER_IDS = {"mobile", "web", "native"};
    private static final String[] RENDERER_NAMES = {
            "Spicy Lyrics Mobile (native canvas)", "Spicy Lyrics (web view)", "Original native renderer"};
    private static final String[] TRANSLATE_CODES = {"", "en", "he", "ar", "ru", "es", "fr", "de"};
    private static final String[] TRANSLATE_NAMES = {
            "Off", "English", "Hebrew", "Arabic", "Russian", "Spanish", "French", "German"};

    private void setupSpicyMobileSettings() {
        android.view.ViewGroup row = (android.view.ViewGroup) btnClearLyricsCache.getParent();
        android.view.ViewGroup card = (android.view.ViewGroup) row.getParent();
        MaterialButton open = new MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        open.setText("Lyrics renderer, romanization and translation");
        open.setAllCaps(false);
        open.setOnClickListener(v -> showSpicyMobileDialog());
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (16 * getResources().getDisplayMetrics().density);
        card.addView(open, card.indexOfChild(row), lp);
    }

    private void showSpicyMobileDialog() {
        final SharedPreferences p = getSharedPreferences(Constants.PREF_NAME, MODE_PRIVATE);
        String renderer = p.getString("lyrics_renderer", "mobile");
        boolean romanize = p.getBoolean("lyrics_romanize", false);
        String target = p.getString("lyrics_translate_target", "");
        boolean replace = "replace".equals(p.getString("lyrics_translation_mode", "under"));
        String[] items = {
                "Renderer: " + RENDERER_NAMES[Math.max(0, java.util.Arrays.asList(RENDERER_IDS).indexOf(renderer))],
                "Romanization: " + (romanize ? "On" : "Off"),
                "Translation (Google): " + TRANSLATE_NAMES[Math.max(0, java.util.Arrays.asList(TRANSLATE_CODES).indexOf(target))],
                "Translation style: " + (replace ? "Replace the line" : "Under each line")
        };
        new AlertDialog.Builder(this)
                .setTitle("Lyrics")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        new AlertDialog.Builder(this).setTitle("Renderer (applies after restarting the app)")
                                .setSingleChoiceItems(RENDERER_NAMES, java.util.Arrays.asList(RENDERER_IDS).indexOf(renderer), (d2, i) -> {
                                    p.edit().putString("lyrics_renderer", RENDERER_IDS[i]).apply();
                                    d2.dismiss();
                                    showSpicyMobileDialog();
                                }).show();
                    } else if (which == 1) {
                        p.edit().putBoolean("lyrics_romanize", !romanize).apply();
                        showSpicyMobileDialog();
                    } else if (which == 2) {
                        new AlertDialog.Builder(this).setTitle("Translate lyrics to (sends lyric text to Google)")
                                .setSingleChoiceItems(TRANSLATE_NAMES, java.util.Arrays.asList(TRANSLATE_CODES).indexOf(target), (d2, i) -> {
                                    p.edit().putString("lyrics_translate_target", TRANSLATE_CODES[i]).apply();
                                    d2.dismiss();
                                    showSpicyMobileDialog();
                                }).show();
                    } else {
                        p.edit().putString("lyrics_translation_mode", replace ? "under" : "replace").apply();
                        showSpicyMobileDialog();
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }
}
