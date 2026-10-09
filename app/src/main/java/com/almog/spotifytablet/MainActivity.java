package com.almog.spotifytablet;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.widget.LinearLayout;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import com.google.android.material.button.MaterialButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import jp.wasabeef.blurry.Blurry;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {
    private TextView weatherText;
    private TextView clockWeatherText;
    private ImageView weatherIcon;
    private ImageView clockWeatherIcon;

    //weatherapi
    private final String WEATHER_API_KEY = Constants.WEATHER_API_KEY;
    private final String CITY = Constants.DEFAULT_WEATHER_CITY;

    private static final String TAG = "SpotifyAuth";
    // SONG_UPDATE_INTERVAL / PAUSED_SONG_UPDATE_INTERVAL removed — now driven by songPollInterval / pausedPollInterval (user-configurable via Settings)
    // Progress update interval for linear progress bar and clock timers.
    // LyricsView runs on its own internal Compose withFrameNanos vsync loop.
    private static final int PROGRESS_UPDATE_INTERVAL = 50; // 20fps for progress bar/timers; saves 67% main thread wakeups
    private static final long CLOCK_MOVE_INTERVAL = 60000L;
    private static final long CLOCK_DELAY = 5000L;
    private static final long VOLUME_REPEAT_INTERVAL = 200L;
    // These two are user-configurable via Settings and refreshed in onResume:
    private long songPollInterval = 5000L;
    private long pausedPollInterval = 15000L;

    private TextView greetingText, clockGreetingText, clockTimeText, clockSongText,
            songTitle, artistName, timeDisplay, volumeText, clockQuoteText;
    private androidx.compose.ui.platform.ComposeView lyricsComposeView;
    private com.almog.spotifytablet.lyrics.viewmodel.LyricsViewModel lyricsViewModel;
    private TextView clockHourView, clockMinuteView;
    private RelativeLayout clockOverlay;
    private View clockContent;
    private ImageView albumArt, backgroundBlur;
    private ProgressBar progressBar;
    private MaterialButton btnPlayPause, btnNext, btnPrev, btnVolUp, btnVolDown;
    private TextView shabbatTimeText, clockShabbatTimeText;
    private OffsetDateTime shabbatCandlesTime = null;
    private OffsetDateTime shabbatHavdalahTime = null;
    private String shabbatEventName = "שבת";

    private AuthManager authManager;
    private String accessToken;
    private boolean isPlaying;
    private long lastAnchorSyncRealtime = 0L;
    private boolean lastAnchorIsPlaying = false;
    private int progressMs, durationMs;
    private long lastProgressTimestamp;
    private long lastPlayTimestamp = 0;
    private String lastBlurredTrackId = "";
    private boolean isSongPaused;
    private volatile String pausedSongTitle = "";
    private volatile String pausedSongArtist = "";
    private boolean isAuthorizing = false;
    private boolean isSpotifyUpdateEnabled = true;
    private boolean isLyricsAnimationEnabled = true;
    private MaterialButton btnToggleUpdates, btnToggleUpdatesAOD;
    // btnToggleAnimation and btnMediaSource removed — now controlled via SettingsActivity
    private String mediaSource = "auto";
    private String jellyfinServerUrl = Constants.DEFAULT_JELLYFIN_URL;
    private String jellyfinApiKey = Constants.DEFAULT_JELLYFIN_API_KEY;
    private String jellyfinClientName = "";
    private String jellyfinActiveSessionId = "";
    private String jellyfinTargetUserName = Constants.DEFAULT_JELLYFIN_TARGET_USER;

    // Auto Mode & Inactivity Tracking Fields
    private String currentAutoEffectiveSource = "spotify";
    private final Object autoModeLock = new Object();
    private boolean spotifyChecked = false;
    private boolean jellyfinChecked = false;
    private boolean spotifyActive = false;
    private boolean jellyfinActive = false;
    private String tempSpotifyBody = null;
    private String tempJellyfinBody = null;
    private long lastActivePlaybackTimestamp = System.currentTimeMillis();

    private final OkHttpClient http = NetworkClient.getAuthInstance();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable songUpdater = this::updateSong;
    private final Runnable progressUpdater = this::updateProgress;
    private final Runnable clockTimeUpdater = this::updateClockTime;
    private final Runnable shabbatUpdater = this::updateShabbatTime;
    private View newMainContent, oldMainContent;
    private View newAlbumFrame, oldAlbumFrame;
    private ImageView newAlbumArt;
    private ProgressBar newLinearProgressBar;
    private TextView newSongTitle, newArtistName, newGreetingText, newWeatherText, newTimeDisplayStart, newTimeDisplayEnd, newVolumeText;
    private ImageView newWeatherIcon;
    private TextView newClockHour, newClockMinute;
    private MaterialButton newBtnPlayPause, newBtnNext, newBtnPrev, newBtnVolUp, newBtnVolDown;
    private MaterialButton newBtnSettings, btnSettings, btnSettingsAOD;
    private MaterialButton newBtnLight, newBtnProjectMode, newBtnToggleUpdates;
    private androidx.compose.ui.platform.ComposeView newLyricsComposeView;
    private View newHeaderContainer, newSidebarTopGroup, newVerticalClock, newLyricsContainer, newPlaybackControls;
    private String currentHomeStyle = SettingsActivity.STYLE_NEW;

    private LinearLayout headerContainer, lightControlContainer, verticalClock, songInfoRow;
    private TextView volumeTextViewContainer; // This is the TextView with the id volumeText
    private ValueAnimator progressAnimator;

    private int currentVolume = -1;
    private long lastVolumeUpdateTime = 0;
    private boolean isVolUpPressed = false;
    private boolean isVolDownPressed = false;

    private AodBurnInController aodController;
    private String currentAlbumName = "";
    private String currentIsrc = "";
    private String cachedNextTrackId = "";
    private long lastQueueFetchTime = 0L;
    private int consecutivePlaybackFailures = 0;
    private int autoSpotifyFailures = 0;

    // Cached SharedPreferences values — refreshed in onResume() to avoid disk reads on hot paths.
    private boolean cachedAodShowSong = true;
    // Guards updateProgressDisplay against spurious View invalidations when nothing changed.
    private int lastRenderedProgress = -1;
    private String lastRenderedTimeStr = null;
    // Guards updateSourceTheme against spurious View invalidations — avoids 12+ setTextColor/
    // setIconTint/mutate calls every 5s when the theme color hasn't actually changed.
    private int lastAppliedThemeColor = -1;
    private String lastAppliedSource = null;

    private final Runnable volumeUpdater = new Runnable() {
        @Override
        public void run() {
            if (isVolUpPressed) {
                adjustVolume(true);
                handler.postDelayed(this, VOLUME_REPEAT_INTERVAL);
            } else if (isVolDownPressed) {
                adjustVolume(false);
                handler.postDelayed(this, VOLUME_REPEAT_INTERVAL);
            }
        }
    };

    private final String LIGHT_WEBHOOK_URL = Constants.DEFAULT_LIGHT_WEBHOOK_URL;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keep screen awake and show over the system keyguard/lockscreen.
        // FLAG_DISMISS_KEYGUARD + FLAG_SHOW_WHEN_LOCKED prevent the screen from
        // going to the lock-screen on idle, app-crash, or Android's own keyguard timeout.
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            android.app.KeyguardManager km = (android.app.KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            if (km != null) km.requestDismissKeyguard(this, null);
        }

        // Global crash handler: restart the app instead of dying to the lockscreen.
        // Without this, any uncaught exception drops the tablet to the system lock screen.
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            android.util.Log.e(TAG, "Uncaught exception — restarting app", ex);
            android.content.Intent intent = new android.content.Intent(getApplicationContext(), MainActivity.class);
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
            android.app.PendingIntent pendingIntent = android.app.PendingIntent.getActivity(
                    getApplicationContext(), 0, intent,
                    android.app.PendingIntent.FLAG_ONE_SHOT | android.app.PendingIntent.FLAG_IMMUTABLE);
            android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
            if (am != null) {
                am.set(android.app.AlarmManager.ELAPSED_REALTIME,
                        android.os.SystemClock.elapsedRealtime() + 600, pendingIntent);
            }
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(1);
        });

        enableFullScreenMode();
        setContentView(R.layout.activity_main);
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }
        DebugLog.i(TAG, "onCreate: Initializing Spotify Tablet Dashboard");

        authManager = new AuthManager(this);
        com.almog.spotifytablet.lyrics.repository.LyricsRepository.setDefaultDiskCacheDir(getCacheDir());
        lyricsViewModel = new androidx.lifecycle.ViewModelProvider(this).get(com.almog.spotifytablet.lyrics.viewmodel.LyricsViewModel.class);
        initViews();

        java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
            SharedPreferences prefs = getSharedPreferences("SpotifyPrefs", MODE_PRIVATE);
            currentVolume = prefs.getInt("last_volume", 50);
            isSpotifyUpdateEnabled = prefs.getBoolean("spotify_updates_enabled", true);
            mediaSource = prefs.getString("media_source", "auto");
            jellyfinServerUrl = prefs.getString(Constants.PREF_KEY_JELLYFIN_SERVER_URL, Constants.DEFAULT_JELLYFIN_URL);
            jellyfinApiKey = prefs.getString(Constants.PREF_KEY_JELLYFIN_API_KEY, Constants.DEFAULT_JELLYFIN_API_KEY);
            jellyfinClientName = prefs.getString(Constants.PREF_KEY_JELLYFIN_CLIENT_NAME, "");
            jellyfinTargetUserName = prefs.getString(Constants.PREF_KEY_JELLYFIN_TARGET_USER, Constants.DEFAULT_JELLYFIN_TARGET_USER);

            runOnUiThread(() -> {
                if (accessToken != null) TokenProvider.setToken(accessToken);
                syncToggleUI();
                updateVolumeUI();
                updateSourceTheme(mediaSource);
            });
        });

        handler.postDelayed(() -> {
            handler.post(clockTimeUpdater);
            if ("spotify".equals(mediaSource) || "auto".equals(mediaSource)) {
                if (!isAuthorizing) initAuthFlow();
            } else {
                startPlaybackUpdates();
            }
        }, 1000);
    }

    private void toggleLightHA() {
        Button btnLight = findViewById(R.id.btnLight);
        if (btnLight != null) {
            btnLight.animate().scaleX(1.2f).scaleY(1.2f).setDuration(100).withEndAction(() -> {
                btnLight.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
            }).start();
        }
        Button btnLightAOD = findViewById(R.id.btnLightAOD);
        if (btnLightAOD != null) {
            btnLightAOD.animate().scaleX(1.2f).scaleY(1.2f).setDuration(100).withEndAction(() -> {
                btnLightAOD.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
            }).start();
        }
        SharedPreferences prefs = getSharedPreferences("SpotifyPrefs", MODE_PRIVATE);
        String haToken = prefs.getString("ha_token", "");
        if (!haToken.isEmpty()) {
            String haUrl = prefs.getString("ha_url", Constants.DEFAULT_HA_URL);
            String url = haUrl + "/api/services/light/toggle";
            
            JSONObject payload = new JSONObject();
            try {
                JSONArray entities = new JSONArray();
                entities.put("light.lmvg_1");
                entities.put("light.lmvg_2");
                payload.put("entity_id", entities);
            } catch (Exception e) {
                e.printStackTrace();
            }
            
            RequestBody body = RequestBody.create(
                    okhttp3.MediaType.parse("application/json; charset=utf-8"),
                    payload.toString()
            );
            
            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer " + haToken)
                    .post(body)
                    .build();
                    
            http.newCall(request).enqueue(new Callback() {
                @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    DebugLog.e("HomeAssistant", "HA Toggle Service call failed: " + e.getMessage());
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "HA Service Error", Toast.LENGTH_SHORT).show());
                }
                @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
        try {
                    DebugLog.d("HomeAssistant", "HA Toggle Service call response code: " + response.code());
                    
                } finally {
            if (response != null) response.close();
        }
    }
            });
            return;
        }

        DebugLog.d("HomeAssistant", "Button clicked, sending request to: " + LIGHT_WEBHOOK_URL);

        Request request = new Request.Builder()
                .url(LIGHT_WEBHOOK_URL)
                .post(RequestBody.create(new byte[0]))
                .build();

        http.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                DebugLog.e("HomeAssistant", "Network Fail: " + e.getMessage());
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Network Error", Toast.LENGTH_SHORT).show());
            }
            @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
        try {
                DebugLog.d("HomeAssistant", "Response Code: " + response.code());
                
            } finally {
            if (response != null) response.close();
        }
    }
        });
    }



    private void launchProjectMode() {
        Intent intent = new Intent(this, ProjectModeActivity.class);
        if (accessToken != null) {
            intent.putExtra("spotify_token", accessToken);
        }
        startActivity(intent);
    }

    private void adjustVolume(boolean up) {
        lastVolumeUpdateTime = System.currentTimeMillis();
        if (currentVolume == -1) currentVolume = 50;

        int step = getSharedPreferences(Constants.PREF_NAME, MODE_PRIVATE)
                .getInt(Constants.PREF_KEY_VOLUME_STEP, Constants.VOLUME_STEP);
        currentVolume = up ? Math.min(currentVolume + step, 100) : Math.max(currentVolume - step, 0);

        updateVolumeUI();

        getSharedPreferences("SpotifyPrefs", MODE_PRIVATE)
                .edit()
                .putInt("last_volume", currentVolume)
                .apply();

        String activeSource = "auto".equals(mediaSource) ? currentAutoEffectiveSource : mediaSource;
        if ("spotify".equals(activeSource)) {
            if (accessToken == null) return;
            Request request = new Request.Builder()
                    .url("https://api.spotify.com/v1/me/player/volume?volume_percent=" + currentVolume)
                    .addHeader("Authorization", "Bearer " + accessToken)
                    .put(RequestBody.create(new byte[0]))
                    .build();

            http.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    DebugLog.e(TAG, "Failed to adjust volume: " + e.getMessage());
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
        try {
                    
                } finally {
            if (response != null) response.close();
        }
    }
            });
        } else {
            // ✅ JELLYFIN DUAL-PIPELINE TUNNEL (FIXED SYNTAX TYPO)

            // OPTIONAL HOME ASSISTANT WEBHOOK BACKUP:
            // If your Jellyfin player is completely uncooperative, you can uncomment these lines
            // to send the volume adjustments to your Home Assistant server instead!
        /*
        String haUrl = up ? "http://192.168.1.x:8123/api/webhook/volume_up"
                          : "http://192.168.1.x:8123/api/webhook/volume_down";
        Request haRequest = new Request.Builder().url(haUrl).post(RequestBody.create(new byte[0])).build();
        http.newCall(haRequest).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call c, @NonNull IOException e) {}
            @Override public void onResponse(@NonNull Call c, @NonNull Response r) throws IOException {
        try {  } finally {
            if (r != null) r.close();
        }
    }
        });
        */

            if (jellyfinActiveSessionId == null || jellyfinActiveSessionId.isEmpty()) return;

            String rawUrl = jellyfinServerUrl;
            if (rawUrl.endsWith("/")) {
                rawUrl = rawUrl.substring(0, rawUrl.length() - 1);
            }

            // --- PIPELINE 1: DIRECT SOCKET COMMAND LAYER ---
            // --- PIPELINE 1: DIRECT SOCKET COMMAND LAYER ---
            String commandUrl = rawUrl + "/Sessions/" + jellyfinActiveSessionId + "/Command";
            JSONObject jsonPayload = new JSONObject();
            try {
                JSONObject commandArgs = new JSONObject();
                commandArgs.put("Volume", String.valueOf(currentVolume));
                jsonPayload.put("Name", "SetVolume");
                jsonPayload.put("Arguments", commandArgs);
            } catch (JSONException e) {
                DebugLog.e("Jellyfin", "JSON composition failed: " + e.getMessage());
            }

            RequestBody jsonBody = RequestBody.create(Constants.JSON_MEDIA_TYPE, jsonPayload.toString());

            Request commandRequest = new Request.Builder()
                    .url(commandUrl)
                    .addHeader("Authorization", "MediaBrowser Token=\"" + jellyfinApiKey + "\"")
                    .post(jsonBody)
                    .build();

            http.newCall(commandRequest).enqueue(new Callback() {
                @Override public void onFailure(@NonNull Call c, @NonNull IOException e) {
                    DebugLog.e("Jellyfin", "Volume command failed: " + e.getMessage());
                }
                @Override public void onResponse(@NonNull Call c, @NonNull Response r) throws IOException {
        try {
                    fetchJellyfinVolume();
                    
                } finally {
            if (r != null) r.close();
        }
    }
            });

            // --- PIPELINE 2: URL PARAMETER FALLBACK LAYER ---
            String fallbackUrl = rawUrl + "/Sessions/" + jellyfinActiveSessionId + "/Playing/Volume"
                    + "?Volume=" + currentVolume
                    + "&volume=" + currentVolume
                    + "&VolumeLevel=" + currentVolume
                    + "&volumeLevel=" + currentVolume;

            RequestBody genericFormBody = RequestBody.create(Constants.FORM_MEDIA_TYPE,
                    "Volume=" + currentVolume + "&VolumeLevel=" + currentVolume);

            Request fallbackRequest = new Request.Builder()
                    .url(fallbackUrl)
                    .addHeader("Authorization", "MediaBrowser Token=\"" + jellyfinApiKey + "\"")
                    .post(genericFormBody)
                    .build();

            http.newCall(fallbackRequest).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    DebugLog.e("Jellyfin", "Fallback volume network path failed");
                }
                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
        try {
                    
                } finally {
            if (response != null) response.close();
        }
    }
            });
        }
    }

    // ✅ BACKEND CONTROL CHANNEL FALLBACK
// ---------- NEW: Fetch current volume from Jellyfin ----------
private void fetchJellyfinVolume() {
    if (jellyfinActiveSessionId == null || jellyfinActiveSessionId.isEmpty()) return;
    String rawUrl = jellyfinServerUrl;
    if (rawUrl.endsWith("/")) {
        rawUrl = rawUrl.substring(0, rawUrl.length() - 1);
    }
    String url = rawUrl + "/Sessions?Id=" + jellyfinActiveSessionId;
    Request request = new Request.Builder()
            .url(url)
            .addHeader("Authorization", "MediaBrowser Token=\"" + jellyfinApiKey + "\"")
            .get()
            .build();
    http.newCall(request).enqueue(new Callback() {
        @Override
        public void onFailure(@NonNull Call call, @NonNull IOException e) {
            DebugLog.e(TAG, "Failed to fetch Jellyfin volume: " + e.getMessage());
        }

        @Override
        public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
            try {
                String body = response.body().string();
                JSONArray sessions = new JSONArray(body);
                if (sessions.length() > 0) {
                    JSONObject session = sessions.getJSONObject(0);
                    if (session.has("PlayState")) {
                        JSONObject playState = session.getJSONObject("PlayState");
                        if (playState.has("VolumeLevel")) {
                            int vol = playState.getInt("VolumeLevel");
                            if (System.currentTimeMillis() - lastVolumeUpdateTime > 3000) {
                                currentVolume = vol;
                                runOnUiThread(() -> updateVolumeUI());
                            }
                        }
                    }
                }
            } catch (Exception ex) {
                DebugLog.e(TAG, "Error parsing volume response: " + ex.getMessage());
            } finally {
                response.close();
            }
        }
    });
}

    private void executeVolumeFallbackCommand(String baseUrl) {
        String commandUrl = baseUrl + "/Sessions/" + jellyfinActiveSessionId + "/Command";

        JSONObject jsonPayload = new JSONObject();
        try {
            JSONObject args = new JSONObject();
            args.put("Volume", String.valueOf(currentVolume));

            jsonPayload.put("Name", "SetVolume");
            jsonPayload.put("Arguments", args);
        } catch (JSONException e) {
            DebugLog.e("Jellyfin", "Fallback payload construction error");
        }

        RequestBody body = RequestBody.create(
                okhttp3.MediaType.parse("application/json; charset=utf-8"),
                jsonPayload.toString()
        );

        Request request = new Request.Builder()
                .url(commandUrl)
                .addHeader("Authorization", "MediaBrowser Token=\"" + jellyfinApiKey + "\"")
                .post(body)
                .build();

        http.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call c, @NonNull IOException e) {}
            @Override public void onResponse(@NonNull Call c, @NonNull Response r) throws IOException {
        try {  } finally {
            if (r != null) r.close();
        }
    }
        });
    }


    private void updateVolumeUI() {
        if (volumeText != null) {
            volumeText.setText(currentVolume + "%");
        }
        if (newVolumeText != null) {
            newVolumeText.setText(currentVolume + "%");
        }
    }

    private void applyHomeScreenStyle() {
        SharedPreferences prefs = getSharedPreferences(Constants.PREF_NAME, MODE_PRIVATE);
        currentHomeStyle = prefs.getString(SettingsActivity.PREF_HOME_STYLE, SettingsActivity.STYLE_NEW);

        if (SettingsActivity.STYLE_OLD.equals(currentHomeStyle)) {
            if (newMainContent != null) newMainContent.setVisibility(View.GONE);
            if (oldMainContent != null) oldMainContent.setVisibility(View.VISIBLE);
        } else {
            if (oldMainContent != null) oldMainContent.setVisibility(View.GONE);
            if (newMainContent != null) newMainContent.setVisibility(View.VISIBLE);
        }
    }

    private void openSettings() {
        Intent intent = new Intent(this, SettingsActivity.class);
        startActivity(intent);
    }

    private void initViews() {
        newMainContent = findViewById(R.id.newMainContent);
        oldMainContent = findViewById(R.id.oldMainContent);

        // --- New Layout Views ---
        newHeaderContainer = findViewById(R.id.newHeaderContainer);
        newSidebarTopGroup = findViewById(R.id.newSidebarTopGroup);
        newVerticalClock = findViewById(R.id.newVerticalClock);
        newLyricsContainer = findViewById(R.id.newLyricsContainer);
        newPlaybackControls = findViewById(R.id.newPlaybackControls);

        newAlbumFrame = findViewById(R.id.newAlbumFrame);
        newAlbumArt = findViewById(R.id.newAlbumArt);
        newLinearProgressBar = findViewById(R.id.newLinearProgressBar);
        newSongTitle = findViewById(R.id.newSongTitle);
        newArtistName = findViewById(R.id.newArtistName);
        newGreetingText = findViewById(R.id.newGreetingText);
        newWeatherText = findViewById(R.id.newWeatherText);
        newWeatherIcon = findViewById(R.id.newWeatherIcon);
        newTimeDisplayStart = findViewById(R.id.newTimeDisplayStart);
        newTimeDisplayEnd = findViewById(R.id.newTimeDisplayEnd);
        newClockHour = findViewById(R.id.newClockHour);
        newClockMinute = findViewById(R.id.newClockMinute);

        newBtnPlayPause = findViewById(R.id.newBtnPlayPause);
        newBtnNext = findViewById(R.id.newBtnNext);
        newBtnPrev = findViewById(R.id.newBtnPrev);
        newBtnVolUp = findViewById(R.id.newBtnVolUp);
        newBtnVolDown = findViewById(R.id.newBtnVolDown);
        newVolumeText = findViewById(R.id.newVolumeText);
        newBtnSettings = findViewById(R.id.newBtnSettings);
        newBtnLight = findViewById(R.id.newBtnLight);
        newBtnProjectMode = findViewById(R.id.newBtnProjectMode);
        newBtnToggleUpdates = findViewById(R.id.newBtnToggleUpdates);

        newLyricsComposeView = findViewById(R.id.newLyricsComposeView);
        if (newLyricsComposeView != null && lyricsViewModel != null) {
            com.almog.spotifytablet.lyrics.bridge.LyricsComposeBridge.initLyricsView(
                newLyricsComposeView,
                lyricsViewModel,
                positionMs -> seekToPosition(positionMs)
            );
        }

        // --- Old Layout Views ---
        headerContainer = findViewById(R.id.headerContainer);
        lightControlContainer = findViewById(R.id.lightControlContainer);
        verticalClock = findViewById(R.id.verticalClock);
        songInfoRow = findViewById(R.id.songInfoRow);
        volumeTextViewContainer = findViewById(R.id.volumeText);
        greetingText = findViewById(R.id.greetingText);
        clockGreetingText = findViewById(R.id.clockGreetingText);
        clockTimeText = findViewById(R.id.clockText);
        clockSongText = findViewById(R.id.clockSongText);
        songTitle = findViewById(R.id.songTitle);
        artistName = findViewById(R.id.artistName);
        timeDisplay = findViewById(R.id.timeDisplay);
        volumeText = findViewById(R.id.volumeText);
        clockHourView = findViewById(R.id.clockHour);
        clockMinuteView = findViewById(R.id.clockMinute);
        clockOverlay = findViewById(R.id.clockOverlay);
        clockContent = findViewById(R.id.clockContent);
        backgroundBlur = findViewById(R.id.backgroundBlur);
        oldAlbumFrame = findViewById(R.id.albumFrame);
        albumArt = findViewById(R.id.albumArt);
        progressBar = findViewById(R.id.progressFrameBar);
        btnPlayPause = findViewById(R.id.btnPlayPause);
        btnNext = findViewById(R.id.btnNext);
        btnPrev = findViewById(R.id.btnPrev);
        btnVolUp = findViewById(R.id.btnVolUp);
        btnVolDown = findViewById(R.id.btnVolDown);
        btnSettings = findViewById(R.id.btnSettings);
        weatherText = findViewById(R.id.weatherText);
        clockWeatherText = findViewById(R.id.clockWeatherText);
        weatherIcon = findViewById(R.id.weatherIcon);
        clockWeatherIcon = findViewById(R.id.clockWeatherIcon);
        shabbatTimeText = findViewById(R.id.shabbatTimeText);
        clockShabbatTimeText = findViewById(R.id.clockShabbatTimeText);
        clockQuoteText = findViewById(R.id.clockQuoteText);

        lyricsComposeView = findViewById(R.id.lyricsComposeView);
        if (lyricsComposeView != null && lyricsViewModel != null) {
            com.almog.spotifytablet.lyrics.bridge.LyricsComposeBridge.initLyricsView(
                lyricsComposeView,
                lyricsViewModel,
                positionMs -> seekToPosition(positionMs)
            );
        }

        // --- Weather click listeners ---
        if (headerContainer != null) headerContainer.setOnClickListener(v -> launchWeatherActivity());
        if (newHeaderContainer != null) newHeaderContainer.setOnClickListener(v -> launchWeatherActivity());
        View headerClockRow = findViewById(R.id.headerClockRow);
        if (headerClockRow != null) headerClockRow.setOnClickListener(v -> launchWeatherActivity());

        // --- Project Mode listeners ---
        View btnProjectMode = findViewById(R.id.btnProjectMode);
        if (btnProjectMode != null) btnProjectMode.setOnClickListener(v -> launchProjectMode());
        if (newBtnProjectMode != null) newBtnProjectMode.setOnClickListener(v -> launchProjectMode());
        View btnTargetAOD = findViewById(R.id.btnTargetAOD);
        if (btnTargetAOD != null) btnTargetAOD.setOnClickListener(v -> launchProjectMode());

        // --- Settings listeners ---
        btnSettingsAOD = findViewById(R.id.btnSettingsAOD);
        if (newBtnSettings != null) newBtnSettings.setOnClickListener(v -> openSettings());
        if (btnSettings != null) btnSettings.setOnClickListener(v -> openSettings());
        if (btnSettingsAOD != null) btnSettingsAOD.setOnClickListener(v -> openSettings());

        // --- Light listeners ---
        View btnLight = findViewById(R.id.btnLight);
        if (btnLight != null) btnLight.setOnClickListener(v -> toggleLightHA());
        if (newBtnLight != null) newBtnLight.setOnClickListener(v -> toggleLightHA());
        View btnLightAOD = findViewById(R.id.btnLightAOD);
        if (btnLightAOD != null) btnLightAOD.setOnClickListener(v -> toggleLightHA());

        updateGreeting();

        // --- Updates toggle listeners ---
        btnToggleUpdates = findViewById(R.id.btnToggleUpdates);
        btnToggleUpdatesAOD = findViewById(R.id.btnToggleUpdatesAOD);

        View.OnClickListener toggleListener = v -> toggleSpotifyUpdates();
        if (btnToggleUpdates != null) btnToggleUpdates.setOnClickListener(toggleListener);
        if (newBtnToggleUpdates != null) newBtnToggleUpdates.setOnClickListener(toggleListener);
        if (btnToggleUpdatesAOD != null) btnToggleUpdatesAOD.setOnClickListener(toggleListener);

        // --- Animation toggle: controlled via Settings; load pref and apply ---
        isLyricsAnimationEnabled = getSharedPreferences("SpotifyPrefs", MODE_PRIVATE).getBoolean("lyrics_animation_enabled", true);
        if (lyricsViewModel != null) {
            lyricsViewModel.setAnimationEnabled(isLyricsAnimationEnabled);
        }

        // --- AOD Settings button wired in Settings section above ---

        applyHomeScreenStyle();

        aodController = new AodBurnInController(clockContent, btnToggleUpdatesAOD);
        aodController.startToggleMovement();

        clockOverlay.setOnClickListener(v -> togglePlayPause());

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
        );
    }

    private void launchWeatherActivity() {
        Intent intent = new Intent(this, WeatherActivity.class);
        startActivity(intent);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void startPlaybackUpdates() {
        if (btnPlayPause != null) btnPlayPause.setOnClickListener(v -> togglePlayPause());
        if (newBtnPlayPause != null) newBtnPlayPause.setOnClickListener(v -> togglePlayPause());

        if (btnNext != null) btnNext.setOnClickListener(v -> skipTo("next"));
        if (newBtnNext != null) newBtnNext.setOnClickListener(v -> skipTo("next"));

        if (btnPrev != null) btnPrev.setOnClickListener(v -> skipTo("previous"));
        if (newBtnPrev != null) newBtnPrev.setOnClickListener(v -> skipTo("previous"));

        View.OnTouchListener volUpTouchListener = (v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                isVolUpPressed = true;
                handler.post(volumeUpdater);
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                isVolUpPressed = false;
                handler.removeCallbacks(volumeUpdater);
                return true;
            }
            return false;
        };

        View.OnTouchListener volDownTouchListener = (v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                isVolDownPressed = true;
                handler.post(volumeUpdater);
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                isVolDownPressed = false;
                handler.removeCallbacks(volumeUpdater);
                return true;
            }
            return false;
        };

        if (btnVolUp != null) btnVolUp.setOnTouchListener(volUpTouchListener);
        if (newBtnVolUp != null) newBtnVolUp.setOnTouchListener(volUpTouchListener);

        if (btnVolDown != null) btnVolDown.setOnTouchListener(volDownTouchListener);
        if (newBtnVolDown != null) newBtnVolDown.setOnTouchListener(volDownTouchListener);

        handler.postDelayed(songUpdater, 2000);
        handler.post(progressUpdater);
        updateWeather();
        updateShabbatTime();
        updateDailyQuote();
    }

    private void startSpotifyDj() {
        if (accessToken == null) return;

        // Optimistic UI update
        String oldTitle = songTitle.getText().toString();
        String oldArtist = artistName.getText().toString();
        crossfadeText(songTitle, "Starting DJ...");
        crossfadeText(artistName, "");

        // Since the actual AI DJ is blocked via API, and the recommendations endpoint is deprecated,
        // and requesting new scopes for top tracks caused an invalid_scope error for this Client ID,
        // we use a popular Spotify curated DJ-style playlist (Mint - Dance/Electronic Hits) as a fallback.
        
        org.json.JSONObject body = new org.json.JSONObject();
        try {
            // Using Spotify's "Mint" playlist as a simulated DJ mix
            body.put("context_uri", "spotify:playlist:37i9dQZF1DX4dyzvuaRJ0n");
        } catch (org.json.JSONException e) {
            e.printStackTrace();
        }

        okhttp3.Request request = new okhttp3.Request.Builder()
                .url("https://api.spotify.com/v1/me/player/play")
                .addHeader("Authorization", "Bearer " + accessToken)
                .put(okhttp3.RequestBody.create(Constants.JSON_MEDIA_TYPE, body.toString()))
                .build();

        http.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(@androidx.annotation.NonNull okhttp3.Call call, @androidx.annotation.NonNull java.io.IOException e) {
                DebugLog.e(TAG, "Failed to start DJ mix: " + e.getMessage());
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "Failed to start DJ mix", Toast.LENGTH_SHORT).show();
                    crossfadeText(songTitle, oldTitle);
                    crossfadeText(artistName, oldArtist);
                });
            }

            @Override
            public void onResponse(@androidx.annotation.NonNull okhttp3.Call call, @androidx.annotation.NonNull okhttp3.Response response) throws java.io.IOException {
                try {
                    if (response.isSuccessful()) {
                        runOnUiThread(() -> android.widget.Toast.makeText(MainActivity.this, "Started curated DJ mix (Mint)!", android.widget.Toast.LENGTH_LONG).show());
                        handler.removeCallbacks(songUpdater);
                        handler.postDelayed(songUpdater, 1000);
                    } else {
                        String errorBody = response.body() != null ? response.body().string() : "No body";
                        DebugLog.e(TAG, "Failed to start DJ mix: Code " + response.code() + " " + errorBody);
                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this, "Error starting DJ mix", Toast.LENGTH_SHORT).show();
                            crossfadeText(songTitle, oldTitle);
                            crossfadeText(artistName, oldArtist);
                        });
                    }
                } finally {
                    if (response != null) response.close();
                }
            }
        });
    }
    private void updateSong() {
        fetchCurrentSong();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (isPlaying) {
            handler.removeCallbacks(progressUpdater);
            handler.post(progressUpdater);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacks(progressUpdater);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (aodController != null) {
            aodController.cleanup();
        }
        handler.removeCallbacksAndMessages(null);
    }

    private float playbackSpeed = 1.0f;

    private void updateProgress() {
        // When the clock overlay is visible we do zero work and do NOT reschedule —
        // showClock(false) will re-post us when it hides the clock.
        if (clockOverlay != null && clockOverlay.getVisibility() == View.VISIBLE) {
            return;
        }
        if (!isPlaying) {
            // Paused but clock hidden: keep timer alive at a lower cadence.
            handler.postDelayed(progressUpdater, 250);
            return;
        }
        if (durationMs > 0) {
            long elapsed = (long) ((System.currentTimeMillis() - lastProgressTimestamp) * playbackSpeed);
            updateProgressDisplay(Math.min(progressMs + (int) elapsed, durationMs));
        }
        handler.postDelayed(progressUpdater, PROGRESS_UPDATE_INTERVAL);
    }

    private void fetchCurrentSong() {
        if (!isSpotifyUpdateEnabled) {
            DebugLog.d(TAG, "Spotify updates disabled, skipping fetch.");
            handler.removeCallbacks(songUpdater);
            handler.postDelayed(songUpdater, pausedPollInterval);
            return;
        }

        if ("jellyfin".equals(mediaSource)) {
            fetchJellyfinCurrentSong();
            return;
        }

        if ("auto".equals(mediaSource)) {
            fetchAutoCurrentSong();
            return;
        }

        authManager.getAccessToken(new AuthManager.AuthCallback() {
            @Override
            public void onTokenReceived(String token) {
                accessToken = token;
                TokenProvider.setToken(token);
                DebugLog.d(TAG, "fetchCurrentSong: Making request with token...");

                Request request = new Request.Builder()
                        .url("https://api.spotify.com/v1/me/player?additional_types=track,episode")
                        .addHeader("Authorization", "Bearer " + accessToken)
                        .build();

                http.newCall(request).enqueue(new Callback() {
                    @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        consecutivePlaybackFailures++;
                        if (consecutivePlaybackFailures >= 3) {
                            runOnUiThread(() -> showClock(true));
                        }
                        checkInactivityReversion();
                        handler.removeCallbacks(songUpdater);
                        handler.postDelayed(songUpdater, isPlaying ? songPollInterval : pausedPollInterval);
                    }

                    @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
        try {
                        if (response.code() == 401) {
                            authManager.clearTokens();
                            
                            runOnUiThread(() -> initAuthFlow());
                            return;
                        }

                        if (response.code() == 429) {
                            String retryAfter = response.header("Retry-After", "60");
                            DebugLog.w(TAG, "fetchCurrentSong: Rate limited! Retrying after " + retryAfter + "s");
                            long waitMs = Long.parseLong(retryAfter) * 1000L;
                            
                            handler.removeCallbacks(songUpdater);
                            handler.postDelayed(songUpdater, waitMs);
                            return;
                        }

                        if (response.code() == 204 || !response.isSuccessful()) {
                            consecutivePlaybackFailures++;
                            if (consecutivePlaybackFailures < 3 && (isPlaying || !pausedSongTitle.isEmpty())) {
                                DebugLog.d(TAG, "fetchCurrentSong: Transient empty/error response (" + response.code() + "), retrying...");
                                checkInactivityReversion();
                                handler.removeCallbacks(songUpdater);
                                handler.postDelayed(songUpdater, songPollInterval);
                                return;
                            }

                            if (response.code() != 204) {
                                DebugLog.d(TAG, "fetchCurrentSong: Request failed (Code " + response.code() + ")");
                            } else if (isPlaying || !pausedSongTitle.isEmpty()) {
                                DebugLog.d(TAG, "fetchCurrentSong: No active playback (204)");
                            }

                            pausedSongTitle = "";
                            pausedSongArtist = "";
                            isSongPaused = false;
                            isPlaying = false;

                            runOnUiThread(() -> {
                                if (clockSongText != null && clockSongText.getVisibility() != View.GONE) {
                                    clockSongText.setText("");
                                    clockSongText.setVisibility(View.GONE);
                                }
                                showClock(true);
                            });
                            
                            checkInactivityReversion();
                            handler.removeCallbacks(songUpdater);
                            handler.postDelayed(songUpdater, pausedPollInterval);
                            return;
                        }

                        parseCurrentSong(response.body().string());
                        
                        checkInactivityReversion();
                        handler.removeCallbacks(songUpdater);
                        handler.postDelayed(songUpdater, isPlaying ? songPollInterval : pausedPollInterval);
                    } finally {
            if (response != null) response.close();
        }
    }
                });
            }

            @Override
            public void onError(String error) {
                handler.removeCallbacks(songUpdater);
                handler.postDelayed(songUpdater, isPlaying ? songPollInterval : pausedPollInterval);
            }
        });
    }

    private void parseCurrentSong(String body) {
        try {
            consecutivePlaybackFailures = 0;
            JSONObject root = new JSONObject(body);

            if (root.has("device") && !root.isNull("device")) {
                JSONObject device = root.getJSONObject("device");
                boolean supportsVolume = device.optBoolean("supports_volume", true);

                runOnUiThread(() -> {
                    int visibility = supportsVolume ? View.VISIBLE : View.GONE;
                    if (btnVolUp != null) btnVolUp.setVisibility(visibility);
                    if (btnVolDown != null) btnVolDown.setVisibility(visibility);
                    if (volumeText != null) volumeText.setVisibility(visibility);
                    if (newBtnVolUp != null) newBtnVolUp.setVisibility(visibility);
                    if (newBtnVolDown != null) newBtnVolDown.setVisibility(visibility);
                    if (newVolumeText != null) newVolumeText.setVisibility(visibility);
                });

                int apiVol = device.optInt("volume_percent", -1);
                if (apiVol != -1) {
                    if (System.currentTimeMillis() - lastVolumeUpdateTime > 3000) {
                        currentVolume = apiVol;
                        runOnUiThread(() -> {
                            updateVolumeUI();
                            getSharedPreferences("SpotifyPrefs", MODE_PRIVATE).edit().putInt("last_volume", currentVolume).apply();
                        });
                    }
                }
            }

            if (root.isNull("item")) {
                return;
            }
            JSONObject item = root.getJSONObject("item");
            String title = item.optString("name", "Unknown Title");
            String artist = "Unknown Artist";
            String img = "";
            String albumName = "";

            String itemType = item.optString("type", root.optString("currently_playing_type", "track"));
            if ("episode".equals(itemType) || item.has("show")) {
                if (item.has("show")) {
                    JSONObject showObj = item.getJSONObject("show");
                    artist = showObj.optString("name", showObj.optString("publisher", "Podcast"));
                    albumName = showObj.optString("name", "");
                } else {
                    artist = item.optString("publisher", "Podcast");
                }

                if (item.has("images") && item.getJSONArray("images").length() > 0) {
                    img = item.getJSONArray("images").getJSONObject(0).optString("url", "");
                } else if (item.has("show") && item.getJSONObject("show").has("images") && item.getJSONObject("show").getJSONArray("images").length() > 0) {
                    img = item.getJSONObject("show").getJSONArray("images").getJSONObject(0).optString("url", "");
                }
            } else {
                if (item.has("artists") && item.getJSONArray("artists").length() > 0) {
                    artist = item.getJSONArray("artists").getJSONObject(0).optString("name", "Unknown Artist");
                }
                if (item.has("album")) {
                    JSONObject albumObj = item.getJSONObject("album");
                    albumName = albumObj.optString("name", "");
                    if (albumObj.has("images") && albumObj.getJSONArray("images").length() > 0) {
                        img = albumObj.getJSONArray("images").getJSONObject(0).optString("url", "");
                    }
                }
            }

            boolean isPodcast = "episode".equals(itemType) || item.has("show");

            String trackId = item.optString("id", "");
            currentAlbumName = albumName;

            // Extract ISRC for exact Musixmatch catalog matching
            String isrc = "";
            if (item.has("external_ids")) {
                isrc = item.getJSONObject("external_ids").optString("isrc", "");
            }
            currentIsrc = isrc;

            durationMs = item.optInt("duration_ms", 0);
            int newProgressMs = root.optInt("progress_ms", 0);
            boolean wasPlaying = isPlaying;
            isPlaying = root.optBoolean("is_playing", false);

            long now = System.currentTimeMillis();
            if (isPlaying && wasPlaying && progressMs > 0) {
                // Calculate expected local position
                long expectedMs = progressMs + (now - lastProgressTimestamp);
                long diff = Math.abs(newProgressMs - expectedMs);
                // Only snap if drift is larger than 1.2s (e.g. user seeked or track changed)
                if (diff > 1200) {
                    progressMs = newProgressMs;
                    lastProgressTimestamp = now;
                }
            } else {
                progressMs = newProgressMs;
                lastProgressTimestamp = now;
            }

            if (!wasPlaying && isPlaying) {
                handler.removeCallbacks(progressUpdater);
                handler.post(progressUpdater);
            }

            isSongPaused = !isPlaying;
            pausedSongTitle = title;
            pausedSongArtist = artist;

            boolean trackChanged = !trackId.equals(lastBlurredTrackId);
            if (trackChanged) {
                lastBlurredTrackId = trackId;
                if (lyricsViewModel != null) {
                    lyricsViewModel.clearLyrics();
                }
                if (!isPodcast) {
                    fetchSyncedLyrics(artist, title, isrc);
                }
                lastQueueFetchTime = 0L;
                fetchNextTrackQueue();
            } else if (isPlaying) {
                fetchNextTrackQueue();
            }

            final String finalArtist = artist;
            final String finalImg = img;
            runOnUiThread(() -> updateUIForSong(title, finalArtist, finalImg, trackChanged));

        } catch (Exception e) {
            DebugLog.e(TAG, "Parse error: " + e.getMessage());
            runOnUiThread(() -> showClock(true));
        }
    }

    private void fetchSyncedLyrics(String artist, String title, String isrc) {
        if (lyricsViewModel == null) return;
        String activeSource = "auto".equals(mediaSource) ? currentAutoEffectiveSource : mediaSource;
        lyricsViewModel.loadLyrics(artist, title, currentAlbumName, durationMs, activeSource, lastBlurredTrackId, isrc, jellyfinServerUrl, jellyfinApiKey);
    }

    private void fetchNextTrackQueue() {
        if ("auto".equals(mediaSource) && !"spotify".equals(currentAutoEffectiveSource)) {
            return;
        }
        if (accessToken == null || accessToken.isEmpty()) return;

        long now = System.currentTimeMillis();
        if (now - lastQueueFetchTime < 15000 && !cachedNextTrackId.isEmpty()) {
            return;
        }
        lastQueueFetchTime = now;

        Request request = new Request.Builder()
                .url("https://api.spotify.com/v1/me/player/queue")
                .addHeader("Authorization", "Bearer " + accessToken)
                .build();

        http.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                DebugLog.e(TAG, "[LyricsPrefetch] Queue API call failed: " + e.getMessage());
            }

            @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try {
                    if (response.isSuccessful() && response.body() != null) {
                        JSONObject json = new JSONObject(response.body().string());
                        if (json.has("queue")) {
                            JSONArray queue = json.getJSONArray("queue");
                            DebugLog.d(TAG, "[LyricsPrefetch] Queue fetched — " + queue.length() + " item(s) ahead");
                            if (queue.length() > 0) {
                                JSONObject nextItem = queue.getJSONObject(0);
                                String nextType = nextItem.optString("type", "");
                                boolean nextIsPodcast = "episode".equals(nextType) || nextItem.has("show");

                                if (nextIsPodcast) {
                                    DebugLog.d(TAG, "[LyricsPrefetch] Next item is a podcast/episode — skipping prefetch");
                                } else {
                                    String nextId = nextItem.optString("id", "");
                                    String nextTitle = nextItem.optString("name", "");
                                    String nextArtist = "";
                                    if (nextItem.has("artists") && nextItem.getJSONArray("artists").length() > 0) {
                                        nextArtist = nextItem.getJSONArray("artists").getJSONObject(0).optString("name", "");
                                    } else if (nextItem.has("show")) {
                                        nextArtist = nextItem.getJSONObject("show").optString("name", "");
                                    }
                                    int nextDurMs = nextItem.optInt("duration_ms", 0);
                                    String nextAlbum = nextItem.has("album") ? nextItem.getJSONObject("album").optString("name", "") : "";

                                    if (!nextId.isEmpty() && !nextTitle.isEmpty() && !nextArtist.isEmpty()) {
                                        if (!nextId.equals(cachedNextTrackId)) {
                                            cachedNextTrackId = nextId;
                                            DebugLog.d(TAG, "[LyricsPrefetch] New next track detected: \"" + nextTitle + "\" by " + nextArtist + " — starting lyrics prefetch");
                                            if (lyricsViewModel != null) {
                                                String prefetchArtist = nextArtist;
                                                String prefetchTitle = nextTitle;
                                                String prefetchAlbum = nextAlbum;
                                                int prefetchDur = nextDurMs;
                                                runOnUiThread(() -> lyricsViewModel.prefetchQueueTrack(prefetchArtist, prefetchTitle, prefetchAlbum, prefetchDur, ""));
                                            }
                                        } else {
                                            DebugLog.d(TAG, "[LyricsPrefetch] Next track unchanged (\"" + nextTitle + "\") — skipping prefetch, lyrics already cached");
                                            cachedNextTrackId = nextId;
                                        }
                                    } else {
                                        DebugLog.w(TAG, "[LyricsPrefetch] Next track missing fields — id='" + nextId + "' title='" + nextTitle + "' artist='" + nextArtist + "'");
                                    }
                                }
                            } else {
                                DebugLog.d(TAG, "[LyricsPrefetch] Queue is empty — nothing to prefetch");
                            }
                        } else {
                            DebugLog.w(TAG, "[LyricsPrefetch] Queue API response has no 'queue' key — HTTP " + response.code());
                        }
                    } else {
                        DebugLog.w(TAG, "[LyricsPrefetch] Queue API returned HTTP " + response.code());
                    }
                } catch (Exception e) {
                    DebugLog.e(TAG, "Queue fetch error: " + e.getMessage());
                } finally {
                    if (response != null) response.close();
                }
            }
        });
    }

    private void seekToPosition(long positionMs) {
        String activeSource = "auto".equals(mediaSource) ? currentAutoEffectiveSource : mediaSource;
        if ("spotify".equals(activeSource) && accessToken != null && !accessToken.isEmpty()) {
            Request req = new Request.Builder()
                    .url("https://api.spotify.com/v1/me/player/seek?position_ms=" + positionMs)
                    .put(RequestBody.create(new byte[0]))
                    .addHeader("Authorization", "Bearer " + accessToken)
                    .build();
            http.newCall(req).enqueue(new Callback() {
                @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {}
                @Override public void onResponse(@NonNull Call call, @NonNull Response response) {
                    if (response != null) response.close();
                }
            });
        }
    }

    private void updateProgressDisplay(int ms) {
        if (clockOverlay.isShown()) return;

        int targetProgress = durationMs > 0 ? (int) ((long) ms * 1000 / durationMs) : 0;
        // Only push to Views when the progress value has actually changed — avoids
        // ~20 spurious ProgressBar.setProgress + TextView.setText invalidations per second.
        if (targetProgress != lastRenderedProgress) {
            lastRenderedProgress = targetProgress;
            if (progressBar != null) progressBar.setProgress(targetProgress);
            if (newLinearProgressBar != null) newLinearProgressBar.setProgress(targetProgress);
        }

        // Time strings: only recompute and set when the second boundary is crossed.
        String currentTimeStr = formatTime(ms);
        if (!currentTimeStr.equals(lastRenderedTimeStr)) {
            lastRenderedTimeStr = currentTimeStr;
            String endStr = formatTime(durationMs);
            if (timeDisplay != null) timeDisplay.setText(currentTimeStr + " / " + endStr);
            if (newTimeDisplayStart != null) newTimeDisplayStart.setText(currentTimeStr);
            if (newTimeDisplayEnd != null) newTimeDisplayEnd.setText(endStr);
        }

        if (lyricsViewModel != null) {
            long nowRt = android.os.SystemClock.elapsedRealtime();
            boolean playingChanged = isPlaying != lastAnchorIsPlaying;
            // Sync immediately on play/pause transitions; otherwise only every 2000ms
            // for drift correction. Compose free-runs accurately from the anchor via
            // withFrameNanos — 2s sync interval is plenty and cuts PlaybackAnchor +
            // LyricsUiState copy() allocations from ~2/sec to ~0.5/sec.
            if (playingChanged || (nowRt - lastAnchorSyncRealtime) >= 2000L) {
                lyricsViewModel.setPlaybackAnchor(ms, isPlaying, playbackSpeed);
                lastAnchorSyncRealtime = nowRt;
                lastAnchorIsPlaying = isPlaying;
            }
        }
    }

    private void toggleLyricsAnimation() {
        isLyricsAnimationEnabled = !isLyricsAnimationEnabled;
        getSharedPreferences("SpotifyPrefs", MODE_PRIVATE).edit().putBoolean("lyrics_animation_enabled", isLyricsAnimationEnabled).apply();
        if (lyricsViewModel != null) {
            lyricsViewModel.setAnimationEnabled(isLyricsAnimationEnabled);
        }
        syncAnimationToggleUI();
        DebugLog.d(TAG, "Word-by-word lyrics mode toggled: " + isLyricsAnimationEnabled);
    }

    private void syncAnimationToggleUI() {
        // Animation toggle is now managed exclusively through SettingsActivity.
        // No UI buttons remain to sync.
    }

    private void updateWeather() {
        String url = "https://api.weatherapi.com/v1/current.json?key=" + WEATHER_API_KEY + "&q=" + CITY + "&aqi=no";
        Request request = new Request.Builder()
                .url(url)
                .header("Cache-Control", "no-cache")
                .header("Pragma", "no-cache")
                .build();

        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
        try {
                final String responseData = response.body() != null ? response.body().string() : null;
                

                if (response.isSuccessful() && responseData != null) {
                    try {
                        JSONObject json = new JSONObject(responseData);
                        JSONObject current = json.getJSONObject("current");

                        double temp = current.getDouble("temp_c");
                        JSONObject condition = current.getJSONObject("condition");
                        String desc = condition.getString("text");

                        switch (desc.toLowerCase(Locale.ROOT)) {
                            case "clear":
                            case "sunny":
                                desc = "בהיר";
                                break;
                            case "partly cloudy":
                            case "partlycloudy":
                                desc = "חלקית מעונן";
                                break;
                            case "cloudy":
                                desc = "מעונן";
                                break;
                            case "mist":
                                desc = "ערפל";
                                break;
                            case "rain":
                                desc = "גשם";
                                break;
                            case "snow":
                                desc = "שלג";
                                break;
                            default:
                                // leave as is for unknown conditions
                                break;
                        }

                        String iconUrl = "https:" + condition.getString("icon");

                        // FIX: Create an effectively final copy of the translated text for the lambda
                        final String finalDesc = desc;

                        runOnUiThread(() -> {
                            // Using finalDesc here fixes the compilation error
                            String weatherString = "|   "+ finalDesc + "  •  " +Math.round(temp)  +  "°"  ;
                            String shortWeatherString = Math.round(temp) + "°C • " + finalDesc;
                            if (weatherText != null) weatherText.setText(weatherString);
                            if (newWeatherText != null) newWeatherText.setText(shortWeatherString);
                            if (clockWeatherText != null) clockWeatherText.setText(weatherString);
                            if (weatherIcon != null) {
                                Glide.with(MainActivity.this).load(iconUrl).into(weatherIcon);
                            }
                            if (newWeatherIcon != null) {
                                Glide.with(MainActivity.this).load(iconUrl).into(newWeatherIcon);
                            }
                            if (clockWeatherIcon != null) {
                                Glide.with(MainActivity.this).load(iconUrl).into(clockWeatherIcon);
                            }
                        });

                        handler.postDelayed(() -> updateWeather(), 900000L);

                    } catch (Exception e) {
                        handler.postDelayed(() -> updateWeather(), 60000L);
                    }
                }
            } finally {
            if (response != null) response.close();
        }
    }

            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                handler.postDelayed(() -> updateWeather(), 20000L);
            }
        });
    }

    private void updateShabbatTime() {
        String url = Constants.SHABBAT_API_URL;
        Request request = new Request.Builder().url(url).build();

        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
        try {
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        String body = response.body().string();
                        JSONObject json = new JSONObject(body);
                        JSONArray items = json.getJSONArray("items");

                        OffsetDateTime now = OffsetDateTime.now();

                        // Build ordered list of candle-lighting and havdalah times
                        // For multi-day holidays (Shavuot, Pesach, Sukkot), there may be
                        // multiple candle-lightings before a single havdalah.
                        // We need to find the FIRST candle-lighting whose final havdalah
                        // is still in the future.
                        OffsetDateTime firstCandlesTime = null;
                        JSONObject firstCandlesObj = null;
                        OffsetDateTime finalHavdalahTime = null;
                        JSONObject finalHavdalahObj = null;

                        // Step 1: Find the havdalah that is still in the future
                        for (int i = 0; i < items.length(); i++) {
                            JSONObject item = items.getJSONObject(i);
                            if ("havdalah".equals(item.optString("category"))) {
                                OffsetDateTime hTime = OffsetDateTime.parse(item.getString("date"));
                                if (hTime.isAfter(now)) {
                                    finalHavdalahTime = hTime;
                                    finalHavdalahObj = item;
                                    // Step 2: Walk backward to find the FIRST candle-lighting
                                    // in the consecutive chain leading to this havdalah
                                    for (int j = i - 1; j >= 0; j--) {
                                        JSONObject prev = items.getJSONObject(j);
                                        if ("candles".equals(prev.optString("category"))) {
                                            firstCandlesTime = OffsetDateTime.parse(prev.getString("date"));
                                            firstCandlesObj = prev;
                                            // Keep going backward — there may be earlier
                                            // candle-lightings for multi-day holidays
                                        } else if ("havdalah".equals(prev.optString("category"))) {
                                            // Hit a previous havdalah — stop, the chain starts
                                            // after this previous havdalah
                                            break;
                                        }
                                    }
                                    break;
                                }
                            }
                        }

                        // Fallback: if no havdalah found, look for the next candle-lighting
                        if (finalHavdalahObj == null) {
                            for (int i = 0; i < items.length(); i++) {
                                JSONObject item = items.getJSONObject(i);
                                if ("candles".equals(item.optString("category"))) {
                                    OffsetDateTime cTime = OffsetDateTime.parse(item.getString("date"));
                                    if (cTime.isAfter(now)) {
                                        firstCandlesTime = cTime;
                                        firstCandlesObj = item;
                                        // Find the havdalah after it
                                        for (int j = i + 1; j < items.length(); j++) {
                                            JSONObject next = items.getJSONObject(j);
                                            if ("havdalah".equals(next.optString("category"))) {
                                                finalHavdalahTime = OffsetDateTime.parse(next.getString("date"));
                                                finalHavdalahObj = next;
                                                break;
                                            }
                                        }
                                        break;
                                    }
                                }
                            }
                        }

                        String timeStr = "";
                        if (firstCandlesObj != null && finalHavdalahObj != null) {
                            shabbatCandlesTime = firstCandlesTime;
                            shabbatHavdalahTime = finalHavdalahTime;

                            // Determine event name from nearby holiday items
                            String eventName = "שבת";
                            // Check all candle-lighting memos and holiday items in range
                            for (int i = 0; i < items.length(); i++) {
                                JSONObject item = items.getJSONObject(i);
                                String cat = item.optString("category", "");
                                if ("holiday".equals(cat)) {
                                    String subcat = item.optString("subcat", "");
                                    if ("major".equals(subcat)) {
                                        // Check if this holiday falls within our candles→havdalah window
                                        String dateStr = item.optString("date", "");
                                        if (dateStr.length() == 10) {
                                            // Date-only (e.g. "2026-05-22")
                                            java.time.LocalDate holidayDate = java.time.LocalDate.parse(dateStr);
                                            java.time.LocalDate candlesDate = firstCandlesTime.toLocalDate();
                                            java.time.LocalDate havdalahDate = finalHavdalahTime.toLocalDate();
                                            if (!holidayDate.isBefore(candlesDate) && !holidayDate.isAfter(havdalahDate)) {
                                                String hebrewName = item.optString("hebrew", "");
                                                // Use the holiday name, but skip "Erev" prefix items
                                                if (!hebrewName.isEmpty() && !hebrewName.startsWith("ערב")) {
                                                    eventName = hebrewName;
                                                    break;
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            shabbatEventName = eventName;

                        }
                        updateShabbatDisplay();

                    } catch (Exception e) {
                        DebugLog.e(TAG, "Shabbat parse error: " + e.getMessage());
                    }
                }
                
                handler.removeCallbacks(shabbatUpdater);
                handler.postDelayed(shabbatUpdater, 3600000L);
            } finally {
            if (response != null) response.close();
        }
    }

            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                handler.removeCallbacks(shabbatUpdater);
                handler.postDelayed(shabbatUpdater, 600000L);
            }
        });
    }

    private String getHebrewDayOfWeek(java.time.DayOfWeek day) {
        switch (day) {
            case SUNDAY: return "יום א'";
            case MONDAY: return "יום ב'";
            case TUESDAY: return "יום ג'";
            case WEDNESDAY: return "יום ד'";
            case THURSDAY: return "יום ה'";
            case FRIDAY: return "יום ו'";
            case SATURDAY: return "שבת";
            default: return "";
        }
    }

    private void updateShabbatDisplay() {
        if (shabbatCandlesTime == null || shabbatHavdalahTime == null) {
            runOnUiThread(() -> {
                if (shabbatTimeText != null) shabbatTimeText.setVisibility(View.GONE);
                if (clockShabbatTimeText != null) clockShabbatTimeText.setVisibility(View.GONE);
            });
            return;
        }

        OffsetDateTime now = OffsetDateTime.now();
        String timeStr = "";
        java.time.format.DateTimeFormatter timeFmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm");

        if (now.isBefore(shabbatCandlesTime)) {
            // Before candles lighting time
            boolean isShabbat = "שבת".equals(shabbatEventName);
            long daysBefore = java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), shabbatCandlesTime.toLocalDate());

            if (isShabbat) {
                // Show Shabbat only on the day itself
                if (now.toLocalDate().isEqual(shabbatCandlesTime.toLocalDate())) {
                    String timeOnly = shabbatCandlesTime.format(timeFmt);
                    timeStr = "כניסת " + shabbatEventName + ": " + timeOnly;
                }
            } else {
                // Show holiday up to 3 days before
                if (daysBefore <= 3) {
                    String timeOnly = shabbatCandlesTime.format(timeFmt);
                    if (now.toLocalDate().isEqual(shabbatCandlesTime.toLocalDate())) {
                        timeStr = "כניסת " + shabbatEventName + ": " + timeOnly;
                    } else {
                        String dayName = getHebrewDayOfWeek(shabbatCandlesTime.getDayOfWeek());
                        timeStr = "כניסת " + shabbatEventName + " (" + dayName + "): " + timeOnly;
                    }
                }
            }
        } else if (now.isBefore(shabbatHavdalahTime)) {
            // Inside Shabbat/holiday
            String timeOnly = shabbatHavdalahTime.format(timeFmt);
            timeStr = "יציאת " + shabbatEventName + ": " + timeOnly;
        } else {
            // After Havdalah - show exit info for holidays spanning multiple days
            if (!"שבת".equals(shabbatEventName)) {
                // Show day of week and time for holiday exit
                String dayName = getHebrewDayOfWeek(shabbatHavdalahTime.getDayOfWeek());
                String timeOnly = shabbatHavdalahTime.format(timeFmt);
                timeStr = "יציאת " + shabbatEventName + " (" + dayName + "): " + timeOnly;
            } else if (now.isBefore(shabbatHavdalahTime.plusHours(6))) {
                // For Shabbat, show "שבוע טוב" within 6 hours after Havdalah
                timeStr = "שבוע טוב";
            }
        }

        final String finalTime = timeStr;
        runOnUiThread(() -> {
            if (!finalTime.isEmpty()) {
                if (shabbatTimeText != null) {
                    shabbatTimeText.setText(finalTime);
                    shabbatTimeText.setVisibility(View.VISIBLE);
                }
                if (clockShabbatTimeText != null) {
                    clockShabbatTimeText.setText(finalTime);
                    clockShabbatTimeText.setVisibility(View.VISIBLE);
                }
            } else {
                if (shabbatTimeText != null) shabbatTimeText.setVisibility(View.GONE);
                if (clockShabbatTimeText != null) clockShabbatTimeText.setVisibility(View.GONE);
            }
            updateGreeting();
        });
    }

    private void initAuthFlow() {
        authManager.getAccessToken(new AuthManager.AuthCallback() {
            @Override
            public void onTokenReceived(String token) {
                accessToken = token;
                startPlaybackUpdates();
                fetchCurrentSong();
            }

            @Override
            public void onError(String error) {
                if (!isAuthorizing) {
                    isAuthorizing = true;
                    authManager.startLogin(MainActivity.this);
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();

        SharedPreferences prefs = getSharedPreferences(Constants.PREF_NAME, MODE_PRIVATE);

        // Keep screen on setting
        boolean keepScreenOn = prefs.getBoolean(SettingsActivity.PREF_KEEP_SCREEN_ON, true);
        if (keepScreenOn) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        applyHomeScreenStyle();

        // Reload animation preference
        isLyricsAnimationEnabled = prefs.getBoolean("lyrics_animation_enabled", true);
        if (lyricsViewModel != null) {
            lyricsViewModel.setAnimationEnabled(isLyricsAnimationEnabled);
        }
        syncAnimationToggleUI();

        // Reload media source preference
        String savedSource = prefs.getString(Constants.PREF_KEY_MEDIA_SOURCE, "auto");
        if (!savedSource.equals(mediaSource)) {
            mediaSource = savedSource;
            updateSourceTheme(mediaSource);
            lastBlurredTrackId = "";
            if (lyricsViewModel != null) {
                lyricsViewModel.clearLyrics();
            }
            handler.removeCallbacks(songUpdater);
            handler.post(songUpdater);
        }

        // Reload Jellyfin configuration
        jellyfinServerUrl = prefs.getString(Constants.PREF_KEY_JELLYFIN_SERVER_URL, Constants.DEFAULT_JELLYFIN_URL);
        jellyfinApiKey = prefs.getString(Constants.PREF_KEY_JELLYFIN_API_KEY, Constants.DEFAULT_JELLYFIN_API_KEY);
        jellyfinClientName = prefs.getString(Constants.PREF_KEY_JELLYFIN_CLIENT_NAME, "");
        jellyfinTargetUserName = prefs.getString(Constants.PREF_KEY_JELLYFIN_TARGET_USER, Constants.DEFAULT_JELLYFIN_TARGET_USER);

        // Reload user-configurable polling intervals
        songPollInterval   = prefs.getLong(Constants.PREF_KEY_SONG_POLL_INTERVAL,   5000L);
        pausedPollInterval = prefs.getLong(Constants.PREF_KEY_PAUSED_POLL_INTERVAL, 15000L);

        // Cache prefs that are read on hot paths (e.g. every showClock call).
        cachedAodShowSong = prefs.getBoolean(Constants.PREF_KEY_AOD_SHOW_SONG, true);

        boolean savedState = prefs.getBoolean(Constants.PREF_KEY_SPOTIFY_UPDATES_ENABLED, true);
        if (savedState != isSpotifyUpdateEnabled) {
            isSpotifyUpdateEnabled = savedState;
            syncToggleUI();
            if (isSpotifyUpdateEnabled) {
                handler.removeCallbacks(songUpdater);
                handler.post(songUpdater);
            } else {
                lastBlurredTrackId = "";
                if (songTitle != null) songTitle.setText("Updates Paused");
                if (newSongTitle != null) newSongTitle.setText("Updates Paused");
                if (artistName != null) artistName.setText("");
                if (newArtistName != null) newArtistName.setText("");
                if (albumArt != null) albumArt.setImageDrawable(null);
                if (newAlbumArt != null) newAlbumArt.setImageDrawable(null);
                if (clockSongText != null) {
                    clockSongText.setText("Updates Paused");
                    clockSongText.setVisibility(View.VISIBLE);
                }
                showClock(true);
            }
        }

        Uri data = getIntent().getData();
        if (data != null && "yourapp".equals(data.getScheme())) {
            handleSpotifyRedirect(getIntent());
            return;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    private void handleSpotifyRedirect(Intent intent) {
        Uri data = intent.getData();
        if (data != null && "yourapp".equals(data.getScheme())) {
            authManager.handleAuthResponse(intent, new AuthManager.AuthCallback() {
                @Override
                public void onTokenReceived(String token) {
                    accessToken = token;
                    isAuthorizing = false;
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "Spotify Login Successful", Toast.LENGTH_SHORT).show();
                        startPlaybackUpdates();
                        fetchCurrentSong();
                    });
                }

                @Override
                public void onError(String error) {
                    isAuthorizing = false;
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Spotify Login Failed: " + error, Toast.LENGTH_LONG).show());
                }
            });
            setIntent(new Intent());
        }
    }

//    private void updatePlayPauseUI() {
//        String symbol = isPlaying ? "⏸\uFE0E" : "▶\uFE0E";
//        if (btnPlayPause != null) {
//            btnPlayPause.setText(symbol);
//        }
//        if (newBtnPlayPause != null) {
//            newBtnPlayPause.setText(symbol);
//        }
//    }

    private void updateUIForSong(String title, String artist, String img, boolean trackChanged) {
        //updatePlayPauseUI();
        boolean forceUpdate = (songTitle != null && "Updates Paused".equals(songTitle.getText().toString()))
                || (newSongTitle != null && "Updates Paused".equals(newSongTitle.getText().toString()));

        if (trackChanged || forceUpdate) {
            if (songTitle != null) crossfadeText(songTitle, title);
            if (newSongTitle != null) crossfadeText(newSongTitle, title);

            if (artistName != null) crossfadeText(artistName, artist);
            if (newArtistName != null) crossfadeText(newArtistName, artist);

            if (!isDestroyed() && !isFinishing() && img != null && !img.isEmpty()) {
                if (albumArt != null) {
                    Glide.with(MainActivity.this).load(img).into(albumArt);
                }
                if (newAlbumArt != null) {
                    Glide.with(MainActivity.this).load(img).into(newAlbumArt);
                }

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    int blurRadius = getSharedPreferences(Constants.PREF_NAME, MODE_PRIVATE)
                            .getInt(Constants.PREF_KEY_BLUR_INTENSITY, 100);
                    backgroundBlur.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                            blurRadius, blurRadius, android.graphics.Shader.TileMode.CLAMP));
                    Glide.with(MainActivity.this).load(img).into(backgroundBlur);
                } else {
                    Glide.with(MainActivity.this)
                            .asBitmap()
                            .load(img)
                            .into(new CustomTarget<Bitmap>() {
                                @Override
                                public void onResourceReady(@NonNull Bitmap resource, Transition<? super Bitmap> transition) {
                                    if (!isDestroyed() && !isFinishing()) {
                                        jp.wasabeef.blurry.Blurry.with(MainActivity.this)
                                                .radius(25)
                                                .sampling(8)
                                                .async()
                                                .from(resource)
                                                .into(backgroundBlur);
                                    }
                                }

                                @Override
                                public void onLoadCleared(Drawable placeholder) {}
                            });
                }
            }

            if (songTitle != null) songTitle.setSelected(true);
            if (newSongTitle != null) newSongTitle.setSelected(true);
            if (artistName != null) artistName.setSelected(true);
            if (newArtistName != null) newArtistName.setSelected(true);
        }

        if (isPlaying) {
            lastPlayTimestamp = System.currentTimeMillis();
            showClock(false);
            if (songTitle != null && !songTitle.isSelected()) songTitle.setSelected(true);
            if (newSongTitle != null && !newSongTitle.isSelected()) newSongTitle.setSelected(true);
            if (artistName != null && !artistName.isSelected()) artistName.setSelected(true);
            if (newArtistName != null && !newArtistName.isSelected()) newArtistName.setSelected(true);

        } else if (System.currentTimeMillis() - lastPlayTimestamp > CLOCK_DELAY) {
            showClock(true);
        }
    }

    private void showClock(boolean show) {
        if (isAuthorizing) return;

        runOnUiThread(() -> {
            int targetVisibility = show ? View.VISIBLE : View.GONE;
            boolean visibilityChanged = clockOverlay.getVisibility() != targetVisibility;

            if (visibilityChanged) {
                if (backgroundBlur != null) {
                    backgroundBlur.setVisibility(show ? View.GONE : View.VISIBLE);
                }
                if (oldAlbumFrame != null) {
                    oldAlbumFrame.setVisibility(show ? View.GONE : View.VISIBLE);
                }
                if (newAlbumFrame != null) {
                    newAlbumFrame.setVisibility(show ? View.GONE : View.VISIBLE);
                }
            }

            boolean aodShowSong = cachedAodShowSong; // refreshed in onResume; avoids disk read on hot path
            if (show && aodShowSong && isSongPaused && !pausedSongTitle.isEmpty()) {
                clockSongText.setText(pausedSongTitle + " - " + pausedSongArtist);
                clockSongText.setVisibility(View.VISIBLE);
            } else {
                clockSongText.setVisibility(View.GONE);
            }

            if (!visibilityChanged) return;

            clockOverlay.setVisibility(targetVisibility);

            if (show) {
                handler.removeCallbacks(progressUpdater);
                if (aodController != null) aodController.startClockMovement();
            } else {
                if (aodController != null) aodController.stopClockMovement();
                handler.post(progressUpdater);
            }
        });
    }

    private void updateClockTime() {
        long now = System.currentTimeMillis();
        // Compute once; reuse for both the full label and the split display.
        String timeStr = DateFormat.format("HH:mm", now).toString();
        if (clockTimeText != null) clockTimeText.setText(timeStr);
        String[] parts = timeStr.split(":");
        if (parts.length == 2) {
            if (clockHourView != null) clockHourView.setText(parts[0]);
            if (clockMinuteView != null) clockMinuteView.setText(parts[1]);
            if (newClockHour != null) newClockHour.setText(parts[0]);
            if (newClockMinute != null) newClockMinute.setText(parts[1]);
        }
        updateGreeting();
        updateShabbatDisplay();
        handler.postDelayed(clockTimeUpdater, CLOCK_MOVE_INTERVAL - (now % CLOCK_MOVE_INTERVAL));
    }

    private void togglePlayPause() {
        // Optimistic UI update
        isPlaying = !isPlaying;
        //updatePlayPauseUI();
        if (isPlaying) {
            lastPlayTimestamp = System.currentTimeMillis();
            showClock(false);
            if (songTitle != null && !songTitle.isSelected()) songTitle.setSelected(true);
            if (newSongTitle != null && !newSongTitle.isSelected()) newSongTitle.setSelected(true);
            if (artistName != null && !artistName.isSelected()) artistName.setSelected(true);
            if (newArtistName != null && !newArtistName.isSelected()) newArtistName.setSelected(true);
        } else {
            showClock(true);
        }

        Runnable revert = () -> {
            isPlaying = !isPlaying;
            //updatePlayPauseUI();
            if (isPlaying) showClock(false);
            else showClock(true);
        };

        String activeSource = "auto".equals(mediaSource) ? currentAutoEffectiveSource : mediaSource;
        if ("spotify".equals(activeSource)) {
            if (accessToken == null) {
                revert.run();
                return;
            }
            String action = isPlaying ? "play" : "pause";
            Request r = new Request.Builder()
                    .url("https://api.spotify.com/v1/me/player/" + action)
                    .addHeader("Authorization", "Bearer " + accessToken)
                    .put(RequestBody.create(new byte[0]))
                    .build();
            http.newCall(r).enqueue(simpleCb(revert));
        } else {
            if (jellyfinActiveSessionId == null || jellyfinActiveSessionId.isEmpty()) {
                revert.run();
                return;
            }
            String action = isPlaying ? "Unpause" : "Pause";
            String url = jellyfinServerUrl + "/Sessions/" + jellyfinActiveSessionId + "/Playing/" + action;
            if (url.contains("//Sessions")) {
                url = url.replace("//Sessions", "/Sessions");
            }
            Request r = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "MediaBrowser Token=\"" + jellyfinApiKey + "\"")
                    .post(RequestBody.create(new byte[0]))
                    .build();
            http.newCall(r).enqueue(simpleCb(revert));
        }
    }

    private void skipTo(String d) {
        String oldTitle = songTitle != null ? songTitle.getText().toString() : "";
        String oldArtist = artistName != null ? artistName.getText().toString() : "";

        // Optimistic update
        String skipText = d.equals("next") ? "Skipping..." : "Previous...";
        if (songTitle != null) crossfadeText(songTitle, skipText);
        if (newSongTitle != null) crossfadeText(newSongTitle, skipText);
        if (artistName != null) crossfadeText(artistName, "");
        if (newArtistName != null) crossfadeText(newArtistName, "");

        Runnable revert = () -> {
            if (songTitle != null) crossfadeText(songTitle, oldTitle);
            if (newSongTitle != null) crossfadeText(newSongTitle, oldTitle);
            if (artistName != null) crossfadeText(artistName, oldArtist);
            if (newArtistName != null) crossfadeText(newArtistName, oldArtist);
        };

        String activeSource = "auto".equals(mediaSource) ? currentAutoEffectiveSource : mediaSource;
        if ("spotify".equals(activeSource)) {
            if (accessToken == null) {
                revert.run();
                return;
            }
            Request r = new Request.Builder()
                    .url("https://api.spotify.com/v1/me/player/" + d)
                    .addHeader("Authorization", "Bearer " + accessToken)
                    .post(RequestBody.create(new byte[0]))
                    .build();
            http.newCall(r).enqueue(simpleCb(revert));
        } else {
            if (jellyfinActiveSessionId == null || jellyfinActiveSessionId.isEmpty()) {
                revert.run();
                return;
            }
            String action = d.equals("next") ? "NextTrack" : "PreviousTrack";
            String url = jellyfinServerUrl + "/Sessions/" + jellyfinActiveSessionId + "/Playing/" + action;
            if (url.contains("//Sessions")) {
                url = url.replace("//Sessions", "/Sessions");
            }
            Request r = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "MediaBrowser Token=\"" + jellyfinApiKey + "\"")
                    .post(RequestBody.create(new byte[0]))
                    .build();
            http.newCall(r).enqueue(simpleCb(revert));
        }
    }

    private Callback simpleCb(Runnable revertRunnable) {
        return new Callback() {
            @Override
            public void onFailure(@NonNull Call c, @NonNull IOException e) {
                if (revertRunnable != null) runOnUiThread(revertRunnable);
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Command Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onResponse(@NonNull Call c, @NonNull Response r) throws IOException {
                try {
                    if (!r.isSuccessful()) {
                        if(r.code()!=404){
                            if (revertRunnable != null) runOnUiThread(revertRunnable);
                            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Command Error: " + r.code(), Toast.LENGTH_SHORT).show());
                        }
                    }
                    
                    handler.postDelayed(() -> {
                        handler.removeCallbacks(songUpdater);
                        fetchCurrentSong();
                    }, 500);
                } finally {
                    if (r != null) r.close();
                }
            }
        };
    }

    private void updateGreeting() {
        String g = " " + getGreetingMessage() + ", אלמוג ";
        if (greetingText != null) greetingText.setText(g);
        if (newGreetingText != null) newGreetingText.setText(g);
        if (clockGreetingText != null) clockGreetingText.setText(g);
    }

    private String getGreetingMessage() {
        OffsetDateTime now = OffsetDateTime.now();

        if (shabbatCandlesTime != null && shabbatHavdalahTime != null) {
            if (now.isAfter(shabbatCandlesTime) && now.isBefore(shabbatHavdalahTime)) {
                if ("שבת".equals(shabbatEventName)) {
                    return "שבת שלום";
                } else {
                    return "חג שמח";
                }
            } else if (now.isAfter(shabbatHavdalahTime)) {
                if (now.toLocalDate().isEqual(shabbatHavdalahTime.toLocalDate()) ||
                        now.isBefore(shabbatHavdalahTime.plusHours(6))) {
                    return "שבוע טוב";
                }
            }
        }

        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (h < 5) return "לילה טוב";
        if (h < 12) return "בוקר טוב";
        if (h < 18) return "צהריים טובים";
        if (h < 22) return "ערב טוב";
        return "לילה טוב";
    }

    private String formatTime(int ms) {
        return DateUtils.formatElapsedTime(ms / 1000L);
    }

    private void crossfadeText(TextView tv, String txt) {
        if (txt == null || tv == null) return;

        if (txt.equals(tv.getText().toString())) {
            return;
        }

        tv.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG_LTR);
        tv.animate().alpha(0f).setDuration(150).withEndAction(() -> {
            tv.setText(txt);
            tv.animate().alpha(1f).setDuration(150).withEndAction(() -> {
                tv.setSelected(true);
            }).start();
        }).start();
    }

    private void enableFullScreenMode() {
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
    }

    private void toggleSpotifyUpdates() {
        isSpotifyUpdateEnabled = !isSpotifyUpdateEnabled;
        getSharedPreferences("SpotifyPrefs", MODE_PRIVATE).edit().putBoolean("spotify_updates_enabled", isSpotifyUpdateEnabled).apply();
        syncToggleUI();

        String msg = isSpotifyUpdateEnabled ? "Spotify updates enabled" : "Spotify updates disabled";

        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        if (isSpotifyUpdateEnabled) {
            handler.removeCallbacks(songUpdater);
            handler.post(songUpdater);
        } else {
            lastBlurredTrackId = "";
            if (songTitle != null) {
                songTitle.animate().cancel();
                songTitle.setText("Updates Paused");
                songTitle.setAlpha(1.0f);
            }
            if (newSongTitle != null) {
                newSongTitle.animate().cancel();
                newSongTitle.setText("Updates Paused");
                newSongTitle.setAlpha(1.0f);
            }
            if (artistName != null) {
                artistName.animate().cancel();
                artistName.setText("");
                artistName.setAlpha(1.0f);
            }
            if (newArtistName != null) {
                newArtistName.animate().cancel();
                newArtistName.setText("");
                newArtistName.setAlpha(1.0f);
            }
            if (albumArt != null) {
                if (!isDestroyed() && !isFinishing()) {
                    Glide.with(MainActivity.this).clear(albumArt);
                }
                albumArt.setImageDrawable(null);
            }
            if (newAlbumArt != null) {
                if (!isDestroyed() && !isFinishing()) {
                    Glide.with(MainActivity.this).clear(newAlbumArt);
                }
                newAlbumArt.setImageDrawable(null);
            }
            if (clockSongText != null) {
                clockSongText.setText("Updates Paused");
                clockSongText.setVisibility(View.VISIBLE);
            }
            showClock(true);
        }
    }

    private void syncToggleUI() {
        float alphaMain = isSpotifyUpdateEnabled ? 0.3f : 1.0f;
        float alphaAOD = isSpotifyUpdateEnabled ? 0.2f : 1.0f;
        if (btnToggleUpdates != null) btnToggleUpdates.setAlpha(alphaMain);
        if (newBtnToggleUpdates != null) newBtnToggleUpdates.setAlpha(alphaMain);
        if (btnToggleUpdatesAOD != null) btnToggleUpdatesAOD.setAlpha(alphaAOD);
    }



    private void updateDailyQuote() {
        String today = DateFormat.format("yyyy-MM-dd", System.currentTimeMillis()).toString();
        String savedDate = getSharedPreferences("SpotifyPrefs", MODE_PRIVATE).getString("quote_date", "");
        String savedQuote = getSharedPreferences("SpotifyPrefs", MODE_PRIVATE).getString("quote_text", "");

        if (today.equals(savedDate) && !savedQuote.isEmpty()) {
            if (savedQuote.contains(" — ")) {
                savedQuote = savedQuote.split(" — ")[0];
            }
            if (clockQuoteText != null) clockQuoteText.setText(savedQuote);
            return;
        }

        Request request = new Request.Builder()
                .url("https://zenquotes.io/api/today")
                .build();

        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                loadFallbackQuote(today);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
        try {
                boolean success = false;
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        String body = response.body().string();
                        JSONArray array = new JSONArray(body);
                        if (array.length() > 0) {
                            JSONObject obj = array.getJSONObject(0);
                            String q = obj.getString("q");
                            final String quoteText = "\"" + q + "\"";

                            getSharedPreferences("SpotifyPrefs", MODE_PRIVATE).edit()
                                    .putString("quote_date", today)
                                    .putString("quote_text", quoteText)
                                    .apply();

                            runOnUiThread(() -> {
                                if (clockQuoteText != null) clockQuoteText.setText(quoteText);
                            });
                            success = true;
                        }
                    } catch (Exception e) {
                        DebugLog.e(TAG, "Quote parse error: " + e.getMessage());
                    }
                }
                
                if (!success) {
                    loadFallbackQuote(today);
                }
            } finally {
            if (response != null) response.close();
        }
    }
        });
    }

    private void loadFallbackQuote(String today) {
        String[] fallbackQuotes = {
                "\"The only way to do great work is to love what you do.\" — Steve Jobs",
                "\"Life is what happens when you're busy making other plans.\" — John Lennon",
                "\"The future belongs to those who believe in the beauty of their dreams.\" — Eleanor Roosevelt",
                "\"Success is not final, failure is not fatal: it is the courage to continue that counts.\" — Winston Churchill",
                "\"In the middle of difficulty lies opportunity.\" — Albert Einstein",
                "\"You miss 100% of the shots you don't take.\" — Wayne Gretzky",
                "\"It always seems impossible until it's done.\" — Nelson Mandela",
                "\"The only limit to our realization of tomorrow will be our doubts of today.\" — Franklin D. Roosevelt",
                "\"Believe you can and you're halfway there.\" — Theodore Roosevelt",
                "\"Act as if what you do makes a difference. It does.\" — William James",
                "\"Limit your 'always' and your 'nevers'.\" — Amy Poehler",
                "\"Never let the fear of striking out keep you from playing the game.\" — Babe Ruth"
        };
        String fallback = fallbackQuotes[new Random().nextInt(fallbackQuotes.length)];
        getSharedPreferences("SpotifyPrefs", MODE_PRIVATE).edit()
                .putString("quote_date", today)
                .putString("quote_text", fallback)
                .apply();
        runOnUiThread(() -> {
            if (clockQuoteText != null) clockQuoteText.setText(fallback);
        });
    }

    private JSONObject getJellyfinActiveSession(String body) {
        if (body == null || body.isEmpty()) return null;
        try {
            JSONArray sessions = new JSONArray(body);
            
            // Step 1: Prioritize the exact session we were already controlling (session stickiness)
            if (jellyfinActiveSessionId != null && !jellyfinActiveSessionId.isEmpty()) {
                for (int i = 0; i < sessions.length(); i++) {
                    JSONObject s = sessions.getJSONObject(i);
                    if (!s.has("NowPlayingItem") || s.isNull("NowPlayingItem")) continue;
                    
                    String sId = s.optString("Id", "");
                    if (jellyfinActiveSessionId.equals(sId)) {
                        boolean match = true;
                        if (!jellyfinTargetUserName.isEmpty()) {
                            String uName = s.optString("UserName", "");
                            if (!jellyfinTargetUserName.equalsIgnoreCase(uName)) match = false;
                        }
                        if (!jellyfinClientName.isEmpty()) {
                            String client = s.optString("Client", "");
                            String device = s.optString("DeviceName", "");
                            if (!jellyfinClientName.equalsIgnoreCase(client) && !jellyfinClientName.equalsIgnoreCase(device)) match = false;
                        }
                        if (match) {
                            return s;
                        }
                    }
                }
            }
            
            // Step 2: Fall back to original prioritization logic (non-paused session first)
            JSONObject firstMatch = null;
            for (int i = 0; i < sessions.length(); i++) {
                JSONObject s = sessions.getJSONObject(i);
                if (!s.has("NowPlayingItem") || s.isNull("NowPlayingItem")) continue;
                
                // User filter
                if (!jellyfinTargetUserName.isEmpty()) {
                    String uName = s.optString("UserName", "");
                    if (!jellyfinTargetUserName.equalsIgnoreCase(uName)) continue;
                }
                // Client/device filter
                if (!jellyfinClientName.isEmpty()) {
                    String client = s.optString("Client", "");
                    String device = s.optString("DeviceName", "");
                    if (!jellyfinClientName.equalsIgnoreCase(client) && !jellyfinClientName.equalsIgnoreCase(device)) continue;
                }
                
                if (firstMatch == null) firstMatch = s;
                
                JSONObject playState = s.optJSONObject("PlayState");
                boolean isPaused = playState != null && playState.optBoolean("IsPaused", false);
                if (!isPaused) {
                    return s;
                }
            }
            
            if (firstMatch != null) {
                JSONObject ps = firstMatch.optJSONObject("PlayState");
                boolean isPaused = ps != null && ps.optBoolean("IsPaused", false);
                DebugLog.d("Jellyfin", "Returning session ID: " + firstMatch.optString("Id", "<no-id>") + ", paused: " + isPaused);
            } else {
                DebugLog.d("Jellyfin", "No matching session found");
            }
            return firstMatch;
        } catch (JSONException e) {
            DebugLog.e("Jellyfin", "Failed to parse sessions JSON: " + e.getMessage());
            return null;
        }
    }

    private void parseJellyfinSessionAndUpdate(JSONObject activeSession) {
        try {
            if (activeSession == null) {
                pausedSongTitle = "";
                pausedSongArtist = "";
                isSongPaused = false;
                isPlaying = false;
                jellyfinActiveSessionId = "";

                runOnUiThread(() -> {
                    songTitle.setText("Not Playing");
                    artistName.setText("");
                    albumArt.setImageDrawable(null);
                    if (clockSongText != null) {
                        clockSongText.setText("");
                        clockSongText.setVisibility(View.GONE);
                    }
                    showClock(true);
                });
                return;
            }

            jellyfinActiveSessionId = activeSession.getString("Id");
            String jUserId = activeSession.optString("UserId", "");
            if (!jUserId.isEmpty()) {
                getSharedPreferences("SpotifyPrefs", MODE_PRIVATE).edit().putString("jellyfin_user_id", jUserId).apply();
            }
            JSONObject item = activeSession.getJSONObject("NowPlayingItem");
            JSONObject playState = activeSession.optJSONObject("PlayState");
            if (playState == null) {
                // No PlayState info, assume not playing
                playState = new JSONObject();
                playState.put("IsPaused", true);
            }

            final String title = item.optString("Name", "Unknown Title");
            final JSONArray artistsArr = item.optJSONArray("Artists");
            final String artist = (artistsArr != null && artistsArr.length() > 0) ? artistsArr.getString(0) : "Unknown Artist";
            final String itemId = item.getString("Id");
            currentAlbumName = item.optString("Album", "");

            DebugLog.d("Jellyfin", "Parsed song: " + title + " by " + artist + " from album: " + currentAlbumName);

            long runtimeTicks = item.optLong("RunTimeTicks", 0);
            long positionTicks = playState.optLong("PositionTicks", 0);
            durationMs = (int) (runtimeTicks / 10000);
            progressMs = (int) (positionTicks / 10000);

            boolean wasPlaying = isPlaying;
            isPlaying = !playState.optBoolean("IsPaused", false);
            lastProgressTimestamp = System.currentTimeMillis();

            if (!wasPlaying && isPlaying) {
                handler.removeCallbacks(progressUpdater);
                handler.post(progressUpdater);
            }

            isSongPaused = !isPlaying;
            pausedSongTitle = title;
            pausedSongArtist = artist;

            String baseUrl = jellyfinServerUrl;
            if (baseUrl.endsWith("/")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            }
            
            String imageId = itemId;
            JSONObject imageTags = item.optJSONObject("ImageTags");
            if (imageTags == null || !imageTags.has("Primary")) {
                String albumId = item.optString("AlbumId", "");
                if (!albumId.isEmpty()) {
                    imageId = albumId;
                }
            }
            final String imgUrl = baseUrl + "/Items/" + imageId + "/Images/Primary?api_key=" + jellyfinApiKey;
            final boolean trackChanged = !itemId.equals(lastBlurredTrackId);

            if (trackChanged) {
                lastBlurredTrackId = itemId;
                if (lyricsViewModel != null) {
                    lyricsViewModel.clearLyrics();
                }
                fetchSyncedLyrics(artist, title, currentIsrc);
            }

            runOnUiThread(() -> {
                if (btnVolUp != null) btnVolUp.setVisibility(View.VISIBLE);
                if (btnVolDown != null) btnVolDown.setVisibility(View.VISIBLE);
                if (volumeText != null) volumeText.setVisibility(View.VISIBLE);
                if (newBtnVolUp != null) newBtnVolUp.setVisibility(View.VISIBLE);
                if (newBtnVolDown != null) newBtnVolDown.setVisibility(View.VISIBLE);
                if (newVolumeText != null) newVolumeText.setVisibility(View.VISIBLE);
            });

            int apiVol = playState.optInt("VolumeLevel", -1);
            if (apiVol != -1) {
                if (System.currentTimeMillis() - lastVolumeUpdateTime > 3000) {
                    currentVolume = apiVol;
                    runOnUiThread(() -> {
                        updateVolumeUI();
                        getSharedPreferences("SpotifyPrefs", MODE_PRIVATE).edit().putInt("last_volume", currentVolume).apply();
                    });
                }
            }

            runOnUiThread(() -> updateUIForSong(title, artist, imgUrl, trackChanged));
        } catch (Exception e) {
            DebugLog.e("Jellyfin", "parseJellyfinSessionAndUpdate Parse error: " + e.getMessage());
            runOnUiThread(() -> showClock(true));
        }
    }

    private void fetchJellyfinCurrentSong() {
        if (jellyfinServerUrl == null || jellyfinServerUrl.isEmpty() || jellyfinApiKey == null || jellyfinApiKey.isEmpty()) {
            runOnUiThread(() -> {
                songTitle.setText("Jellyfin Not Configured");
                artistName.setText("Long-press bottom-left button to setup");
                albumArt.setImageDrawable(null);
                if (clockSongText != null) {
                    clockSongText.setText("Jellyfin Not Configured");
                    clockSongText.setVisibility(View.VISIBLE);
                }
                showClock(true);
            });
            checkInactivityReversion();
            handler.removeCallbacks(songUpdater);
            handler.postDelayed(songUpdater, pausedPollInterval);
            return;
        }

        String baseUrl = jellyfinServerUrl;
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        String url = baseUrl + "/Sessions";
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "MediaBrowser Token=\"" + jellyfinApiKey + "\"")
                .build();

        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                runOnUiThread(() -> {
                    songTitle.setText("Jellyfin Server Offline");
                    artistName.setText(e.getMessage());
                    showClock(true);
                });
                checkInactivityReversion();
                handler.removeCallbacks(songUpdater);
                handler.postDelayed(songUpdater, pausedPollInterval);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
        try {
                if (!response.isSuccessful()) {
                    runOnUiThread(() -> {
                        songTitle.setText("Jellyfin Connection Error");
                        artistName.setText("Status code: " + response.code());
                        showClock(true);
                    });
                    
                    checkInactivityReversion();
                    handler.removeCallbacks(songUpdater);
                    handler.postDelayed(songUpdater, pausedPollInterval);
                    return;
                }

                String body = response.body().string();
                
                JSONObject activeSession = getJellyfinActiveSession(body);
                parseJellyfinSessionAndUpdate(activeSession);
                checkInactivityReversion();
                handler.removeCallbacks(songUpdater);
                handler.postDelayed(songUpdater, isPlaying ? songPollInterval : pausedPollInterval);
            } finally {
            if (response != null) response.close();
        }
    }
        });
    }

    private void fetchAutoCurrentSong() {
        synchronized (autoModeLock) {
            spotifyChecked = false;
            jellyfinChecked = false;
            spotifyActive = false;
            jellyfinActive = false;
            tempSpotifyBody = null;
            tempJellyfinBody = null;
        }

        // 1. Fetch Spotify
        authManager.getAccessToken(new AuthManager.AuthCallback() {
            @Override
            public void onTokenReceived(String token) {
                accessToken = token;
                Request request = new Request.Builder()
                        .url("https://api.spotify.com/v1/me/player?additional_types=track,episode")
                        .addHeader("Authorization", "Bearer " + accessToken)
                        .build();

                http.newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        synchronized (autoModeLock) {
                            spotifyChecked = true;
                            autoSpotifyFailures++;
                            if (autoSpotifyFailures >= 3) {
                                spotifyActive = false;
                            }
                            checkAutoDecision();
                        }
                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
        try {
                        synchronized (autoModeLock) {
                            spotifyChecked = true;
                            if (response.code() == 200) {
                                tempSpotifyBody = response.body().string();
                                try {
                                    JSONObject json = new JSONObject(tempSpotifyBody);
                                    boolean active = json.optBoolean("is_playing", false);
                                    if (active) {
                                        autoSpotifyFailures = 0;
                                        spotifyActive = true;
                                    } else {
                                        autoSpotifyFailures++;
                                        if (autoSpotifyFailures >= 3 || !spotifyActive) {
                                            spotifyActive = false;
                                        }
                                    }
                                } catch (Exception e) {
                                    autoSpotifyFailures++;
                                    if (autoSpotifyFailures >= 3) spotifyActive = false;
                                }
                            } else {
                                autoSpotifyFailures++;
                                if (autoSpotifyFailures >= 3 || !spotifyActive) {
                                    spotifyActive = false;
                                }
                            }
                            
                            checkAutoDecision();
                        }
                    } finally {
            if (response != null) response.close();
        }
    }
                });
            }

            @Override
            public void onError(String error) {
                synchronized (autoModeLock) {
                    spotifyChecked = true;
                    autoSpotifyFailures++;
                    if (autoSpotifyFailures >= 3) {
                        spotifyActive = false;
                    }
                    checkAutoDecision();
                }
            }
        });

        // 2. Fetch Jellyfin
        if (jellyfinServerUrl == null || jellyfinServerUrl.isEmpty() || jellyfinApiKey == null || jellyfinApiKey.isEmpty()) {
            synchronized (autoModeLock) {
                jellyfinChecked = true;
                jellyfinActive = false;
                checkAutoDecision();
            }
        } else {
            String baseUrl = jellyfinServerUrl;
            if (baseUrl.endsWith("/")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            }
            String url = baseUrl + "/Sessions";
            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "MediaBrowser Token=\"" + jellyfinApiKey + "\"")
                    .build();

            http.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    synchronized (autoModeLock) {
                        jellyfinChecked = true;
                        jellyfinActive = false;
                        checkAutoDecision();
                    }
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
        try {
                    synchronized (autoModeLock) {
                        jellyfinChecked = true;
                        if (response.isSuccessful()) {
                            tempJellyfinBody = response.body().string();
                            JSONObject activeSession = getJellyfinActiveSession(tempJellyfinBody);
                            if (activeSession != null) {
                                JSONObject playState = activeSession.optJSONObject("PlayState");
                                jellyfinActive = playState != null && !playState.optBoolean("IsPaused", false);
                            } else {
                                jellyfinActive = false;
                            }
                        } else {
                            jellyfinActive = false;
                        }
                        
                        checkAutoDecision();
                    }
                } finally {
            if (response != null) response.close();
        }
    }
            });
        }
    }

    private void checkAutoDecision() {
        synchronized (autoModeLock) {
            if (!spotifyChecked || !jellyfinChecked) {
                return;
            }

            String oldEffectiveSource = currentAutoEffectiveSource;

            if (spotifyActive && jellyfinActive) {
                currentAutoEffectiveSource = "spotify";
            } else if (spotifyActive) {
                currentAutoEffectiveSource = "spotify";
            } else if (jellyfinActive) {
                currentAutoEffectiveSource = "jellyfin";
            }

            final String activeSource = currentAutoEffectiveSource;

            // Only repaint theme colors when the effective source actually changed —
            // avoids posting a runOnUiThread with 12+ view invalidations every poll cycle.
            if (!activeSource.equals(oldEffectiveSource)) {
                runOnUiThread(() -> updateSourceTheme("auto"));
            }

            if ("spotify".equals(activeSource)) {
                if (tempSpotifyBody != null && !tempSpotifyBody.isEmpty()) {
                    parseCurrentSong(tempSpotifyBody);
                } else {
                    if (autoSpotifyFailures >= 3 || (pausedSongTitle.isEmpty() && !isPlaying)) {
                        pausedSongTitle = "";
                        pausedSongArtist = "";
                        isSongPaused = false;
                        isPlaying = false;
                        runOnUiThread(() -> {
                            if (clockSongText != null && clockSongText.getVisibility() != View.GONE) {
                                clockSongText.setText("");
                                clockSongText.setVisibility(View.GONE);
                            }
                            showClock(true);
                        });
                    }
                }
            } else {
                JSONObject activeSession = getJellyfinActiveSession(tempJellyfinBody);
                parseJellyfinSessionAndUpdate(activeSession);
            }

            checkInactivityReversion();

            handler.removeCallbacks(songUpdater);
            handler.postDelayed(songUpdater, isPlaying ? songPollInterval : pausedPollInterval);
        }
    }

    private void checkInactivityReversion() {
        if (isPlaying) {
            lastActivePlaybackTimestamp = System.currentTimeMillis();
        } else {
            if (!"auto".equals(mediaSource)) {
                long elapsed = System.currentTimeMillis() - lastActivePlaybackTimestamp;
                if (elapsed >= 10 * 60 * 1000L) { // 10 minutes
                    revertToAuto();
                }
            }
        }
    }

    private void revertToAuto() {
        runOnUiThread(() -> {
            mediaSource = "auto";
            getSharedPreferences("SpotifyPrefs", MODE_PRIVATE)
                    .edit()
                    .putString("media_source", "auto")
                    .apply();

            syncToggleUI();
            updateSourceTheme("auto");
            Toast.makeText(MainActivity.this, "Inactivity timeout: Reverted to Auto Mode", Toast.LENGTH_LONG).show();

            handler.removeCallbacks(songUpdater);
            handler.post(songUpdater);
        });
    }

    private void showJellyfinConfigDialog() {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        builder.setTitle("Configure Jellyfin");

        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(40, 20, 40, 20);

        final android.widget.EditText inputUrl = new android.widget.EditText(this);
        inputUrl.setHint("Server URL (e.g. http://192.168.1.x:8096)");
        inputUrl.setText(jellyfinServerUrl);
        inputUrl.setTextColor(android.graphics.Color.WHITE);
        layout.addView(inputUrl);

        final android.widget.EditText inputKey = new android.widget.EditText(this);
        inputKey.setHint("API Key / Token");
        inputKey.setText(jellyfinApiKey);
        inputKey.setTextColor(android.graphics.Color.WHITE);
        layout.addView(inputKey);

        final android.widget.EditText inputClient = new android.widget.EditText(this);
        inputClient.setHint("Preferred Client/Device (Optional)");
        inputClient.setText(jellyfinClientName);
        inputClient.setTextColor(android.graphics.Color.WHITE);
        layout.addView(inputClient);

        builder.setView(layout);

        builder.setPositiveButton("Save", (dialog, which) -> {
            jellyfinServerUrl = inputUrl.getText().toString().trim();
            jellyfinApiKey = inputKey.getText().toString().trim();
            jellyfinClientName = inputClient.getText().toString().trim();

            getSharedPreferences(Constants.PREF_NAME, MODE_PRIVATE).edit()
                    .putString(Constants.PREF_KEY_JELLYFIN_SERVER_URL, jellyfinServerUrl)
                    .putString(Constants.PREF_KEY_JELLYFIN_API_KEY, jellyfinApiKey)
                    .putString(Constants.PREF_KEY_JELLYFIN_CLIENT_NAME, jellyfinClientName)
                    .apply();

            Toast.makeText(this, "Jellyfin Config Saved", Toast.LENGTH_SHORT).show();
            if ("jellyfin".equals(mediaSource) || "auto".equals(mediaSource)) {
                handler.removeCallbacks(songUpdater);
                handler.post(songUpdater);
            }
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private void toggleMediaSource() {
        if ("auto".equals(mediaSource)) {
            mediaSource = "spotify";
        } else if ("spotify".equals(mediaSource)) {
            mediaSource = "jellyfin";
        } else {
            mediaSource = "auto";
        }

        getSharedPreferences("SpotifyPrefs", MODE_PRIVATE).edit()
                .putString("media_source", mediaSource)
                .apply();

        Toast.makeText(this, "Active Source: " + mediaSource.toUpperCase(), Toast.LENGTH_SHORT).show();
        
        lastActivePlaybackTimestamp = System.currentTimeMillis();
        updateSourceTheme(mediaSource);

        lastBlurredTrackId = "";
        if (lyricsViewModel != null) {
            lyricsViewModel.clearLyrics();
        }

        handler.removeCallbacks(songUpdater);
        handler.post(songUpdater);
    }

    private void updateSourceTheme(String source) {
        String tempEffectiveSource = source;
        if ("auto".equals(source)) {
            tempEffectiveSource = currentAutoEffectiveSource;
        }
        final String effectiveSource = tempEffectiveSource;

        int themeColor = android.graphics.Color.parseColor("spotify".equals(effectiveSource) ? "#1DB954" : "#A259FF");
        int sourceBtnColor = android.graphics.Color.parseColor(
                "auto".equals(source) ? "#007BFF" : ("spotify".equals(source) ? "#1DB954" : "#A259FF")
        );
        String indicatorIcon = "auto".equals(source) ? "🔵" : ("spotify".equals(source) ? "🟢" : "🟣");

        // Skip all view updates if neither the color nor the source label changed.
        // This avoids 12+ setTextColor/setIconTint/mutate() invalidations every poll cycle.
        if (themeColor == lastAppliedThemeColor && source.equals(lastAppliedSource)) {
            return;
        }
        lastAppliedThemeColor = themeColor;
        lastAppliedSource = source;

        runOnUiThread(() -> {
            // Media source buttons removed from UI — now controlled via SettingsActivity
            if (btnPlayPause != null) btnPlayPause.setTextColor(themeColor);
            if (btnNext != null) btnNext.setTextColor(themeColor);
            if (btnPrev != null) btnPrev.setTextColor(themeColor);
            if (btnVolUp != null) btnVolUp.setIconTint(ColorStateList.valueOf(themeColor));
            if (btnVolDown != null) btnVolDown.setIconTint(ColorStateList.valueOf(themeColor));

            // New layout: playback buttons are kept pure white for clean aesthetic
            if (newBtnPlayPause != null) newBtnPlayPause.setTextColor(Color.WHITE);
            if (newBtnNext != null) newBtnNext.setTextColor(Color.WHITE);
            if (newBtnPrev != null) newBtnPrev.setTextColor(Color.WHITE);
            if (newBtnVolUp != null) newBtnVolUp.setIconTint(ColorStateList.valueOf(Color.WHITE));
            if (newBtnVolDown != null) newBtnVolDown.setIconTint(ColorStateList.valueOf(Color.WHITE));

            if (btnToggleUpdates != null) btnToggleUpdates.setTextColor(themeColor);
            if (newBtnToggleUpdates != null) newBtnToggleUpdates.setTextColor(themeColor);
            if (btnToggleUpdatesAOD != null) btnToggleUpdatesAOD.setTextColor(themeColor);

            // Progress bar indicator & framing ring
            if (progressBar != null) {
                progressBar.setProgressTintList(ColorStateList.valueOf(themeColor));
                Drawable progressDrawable = progressBar.getProgressDrawable();
                if (progressDrawable != null) {
                    progressDrawable.mutate().setTintList(ColorStateList.valueOf(themeColor));
                }
            }
            if (newLinearProgressBar != null) {
                newLinearProgressBar.setProgressTintList(ColorStateList.valueOf(themeColor));
            }

            // Fix: Update stroke border programmatically for containers
            applyStrokeBorderOnly(headerContainer, themeColor);
            applyStrokeBorderOnly(lightControlContainer, themeColor);
            applyStrokeBorderOnly(verticalClock, themeColor);
            applyStrokeBorderOnly(songInfoRow, themeColor);
            applyStrokeBorderOnly(volumeTextViewContainer, themeColor);
        });
    }

    private void applyStrokeBorderOnly(View view, int strokeColor) {
        if (view == null) return;

        Drawable background = view.getBackground();
        if (background instanceof android.graphics.drawable.GradientDrawable) {
            android.graphics.drawable.GradientDrawable shape =
                    (android.graphics.drawable.GradientDrawable) background.mutate();

            // Keeps the layout solid background dark/translucent (#121212 at 90% opacity)
            shape.setColor(android.graphics.Color.parseColor("#E6121212"));

            // Updates the border stroke line to green or purple
            shape.setStroke(10, strokeColor);
        }
    }
}

