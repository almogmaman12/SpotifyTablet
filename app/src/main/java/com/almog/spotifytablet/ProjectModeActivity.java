package com.almog.spotifytablet;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.Task;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ProjectModeActivity extends AppCompatActivity {
    private static final String TAG = "ProjectMode";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final OkHttpClient http = NetworkClient.getInstance();
    private static final String MACRODROID_WEBHOOK_URL = Constants.DEFAULT_MACRODROID_WEBHOOK_URL;
    private boolean isDndActive = false;

    private String spotifyAccessToken;
    private AuthManager authManager;
    private boolean isSpotifyUpdateEnabled = true;
    private boolean isLyricsAnimationEnabled = true;
    private Button btnToggleUpdates, pmBtnToggleAnimation;

    // UI - Spotify
    private TextView songTitle, artistName;
    private ImageView albumArt;
    private ProgressBar progressBar;
    private int currentVolume = -1;
    private long lastVolumeUpdateTime = 0;
    private boolean isPlaying = false;
    private Button pmBtnMediaSource;
    private String mediaSource = "auto";
    private String jellyfinServerUrl = Constants.DEFAULT_JELLYFIN_URL;
    private String jellyfinApiKey = Constants.DEFAULT_JELLYFIN_API_KEY;
    private String jellyfinClientName = "";
    private String jellyfinActiveSessionId = "";

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
    private String jellyfinTargetUserName = Constants.DEFAULT_JELLYFIN_TARGET_USER;

    // UI - Clock
    private TextView clockTime, clockDate;

    // UI - Timer
    private TextView timerDisplay;
    private Button btnStartStop;
    private Button btnPlus5, btnMinus5, btnPreset25, btnPreset45, btnPreset60;
    private CountDownTimer focusTimer;
    private long timerTimeLeftMs = 25 * 60 * 1000L;
    private long initialTimerTimeMs = 25 * 60 * 1000L;
    private boolean isTimerRunning = false;
    private boolean isSessionInProgress = false;
    private CircularProgressIndicator pmTimerRing;

    // UI - Calendar
    private RecyclerView eventsRecyclerView;
    private CalendarEventAdapter calendarAdapter;
    private GoogleSignInClient mGoogleSignInClient;
    private String googleAuthToken;

    // Runnables
    private final Runnable spotifyUpdater = this::fetchCurrentSong;
    private final Runnable clockUpdater = this::updateClock;
    private final Runnable calendarUpdater = this::fetchCalendarEvents;

    // Idle Exit
    private static final long IDLE_EXIT_TIMEOUT = 10 * 60 * 1000L; // 10 minutes
    private final Handler idleHandler = new Handler(Looper.getMainLooper());
    private final Runnable idleExitRunnable = () -> {
        Log.d(TAG, "Idle timeout reached: Exiting Project Mode.");
        finish();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
        );
        setContentView(R.layout.activity_project_mode);
        isSpotifyUpdateEnabled = getSharedPreferences(Constants.PREF_NAME, MODE_PRIVATE).getBoolean(Constants.PREF_KEY_SPOTIFY_UPDATES_ENABLED, true);
        mediaSource = getSharedPreferences(Constants.PREF_NAME, MODE_PRIVATE).getString(Constants.PREF_KEY_MEDIA_SOURCE, "auto");
        jellyfinServerUrl = getSharedPreferences(Constants.PREF_NAME, MODE_PRIVATE).getString(Constants.PREF_KEY_JELLYFIN_SERVER_URL, Constants.DEFAULT_JELLYFIN_URL);
        jellyfinApiKey = getSharedPreferences(Constants.PREF_NAME, MODE_PRIVATE).getString(Constants.PREF_KEY_JELLYFIN_API_KEY, Constants.DEFAULT_JELLYFIN_API_KEY);
        jellyfinClientName = getSharedPreferences(Constants.PREF_NAME, MODE_PRIVATE).getString(Constants.PREF_KEY_JELLYFIN_CLIENT_NAME, "");

        if (getSupportActionBar() != null) getSupportActionBar().hide();

        authManager = new AuthManager(this);
        spotifyAccessToken = getIntent().getStringExtra(Constants.EXTRA_SPOTIFY_TOKEN);
        currentVolume = getSharedPreferences(Constants.PREF_NAME, MODE_PRIVATE).getInt(Constants.PREF_KEY_LAST_VOLUME, 50);

        initUI();
        setupTimer();
        setupSpotifyControls();
        setupGoogleSignIn();
        updateSourceTheme(mediaSource);

        handler.post(clockUpdater);
        handler.post(spotifyUpdater);
        resetIdleTimer();
    }

    private void resetIdleTimer() {
        idleHandler.removeCallbacks(idleExitRunnable);
        if (!isTimerRunning && !isSessionInProgress) {
            Log.d(TAG, "Idle timer started: Screen will exit in 10 minutes if no interaction.");
            idleHandler.postDelayed(idleExitRunnable, IDLE_EXIT_TIMEOUT);
        } else {
            String reason = isTimerRunning ? "Focus timer is active" : "Session is in progress (paused)";
            Log.d(TAG, "Idle timer skipped: " + reason);
        }
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        Log.d(TAG, "User interaction detected: Resetting idle timer.");
        resetIdleTimer();
    }

    private void initUI() {
        songTitle = findViewById(R.id.pmSongTitle);
        artistName = findViewById(R.id.pmArtistName);
        albumArt = findViewById(R.id.pmAlbumArt);
        progressBar = findViewById(R.id.pmProgressBar);
        
        clockTime = findViewById(R.id.pmClockTime);
        clockDate = findViewById(R.id.pmClockDate);
        
        timerDisplay = findViewById(R.id.pmTimerDisplay);
        btnStartStop = findViewById(R.id.pmBtnStartStopTimer);
        pmTimerRing = findViewById(R.id.pmTimerRing);
        
        btnPlus5 = findViewById(R.id.pmBtnPlus5);
        btnMinus5 = findViewById(R.id.pmBtnMinus5);
        btnPreset25 = findViewById(R.id.pmBtnPreset25);
        btnPreset45 = findViewById(R.id.pmBtnPreset45);
        btnPreset60 = findViewById(R.id.pmBtnPreset60);
        
        eventsRecyclerView = findViewById(R.id.pmEventsRecyclerView);
        eventsRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        calendarAdapter = new CalendarEventAdapter(new ArrayList<>());
        eventsRecyclerView.setAdapter(calendarAdapter);
        
        findViewById(R.id.pmBtnExit).setOnClickListener(v -> finish());
        findViewById(R.id.pmBtnLight).setOnClickListener(v -> toggleLightHA());
        
        btnToggleUpdates = findViewById(R.id.pmBtnToggleUpdates);
        if (btnToggleUpdates != null) {
            btnToggleUpdates.setOnClickListener(v -> toggleSpotifyUpdates());
            syncToggleUI();
        }

        pmBtnToggleAnimation = findViewById(R.id.pmBtnToggleAnimation);
        isLyricsAnimationEnabled = getSharedPreferences("SpotifyPrefs", MODE_PRIVATE).getBoolean("lyrics_animation_enabled", true);
        if (pmBtnToggleAnimation != null) {
            pmBtnToggleAnimation.setOnClickListener(v -> toggleLyricsAnimation());
            syncAnimationToggleUI();
        }

        pmBtnMediaSource = findViewById(R.id.pmBtnMediaSource);
        if (pmBtnMediaSource != null) {
            pmBtnMediaSource.setOnClickListener(v -> toggleMediaSource());
            pmBtnMediaSource.setOnLongClickListener(v -> {
                showJellyfinConfigDialog();
                return true;
            });
        }
    }

    private void toggleLightHA() {
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
                    Log.e("HomeAssistant", "HA Toggle Service call failed: " + e.getMessage());
                    runOnUiThread(() -> Toast.makeText(ProjectModeActivity.this, "HA Service Error", Toast.LENGTH_SHORT).show());
                }
                @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
        try {
                    Log.d("HomeAssistant", "HA Toggle Service call response code: " + response.code());
                    
                } finally {
            if (response != null) response.close();
        }
    }
            });
            return;
        }

        String LIGHT_WEBHOOK_URL = Constants.DEFAULT_LIGHT_WEBHOOK_URL;
        Log.d("HomeAssistant", "Button clicked, sending request to: " + LIGHT_WEBHOOK_URL);

        Request request = new Request.Builder()
                .url(LIGHT_WEBHOOK_URL)
                .post(RequestBody.create(new byte[0]))
                .build();

        http.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e("HomeAssistant", "Network Fail: " + e.getMessage());
                runOnUiThread(() -> Toast.makeText(ProjectModeActivity.this, "Network Error", Toast.LENGTH_SHORT).show());
            }
            @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
        try {
                Log.d("HomeAssistant", "Response Code: " + response.code());
                
            } finally {
            if (response != null) response.close();
        }
    }
        });
    }



    // ==========================================
    // POMODORO TIMER
    // ==========================================
    private void setupTimer() {
        updateTimerText();

        btnPreset25.setOnClickListener(v -> setTimer(25));
        btnPreset45.setOnClickListener(v -> setTimer(45));
        btnPreset60.setOnClickListener(v -> setTimer(60));

        setupAutoRepeatButton(btnPlus5, 5);
        setupAutoRepeatButton(btnMinus5, -5);

        btnStartStop.setOnClickListener(v -> {
            if (isTimerRunning) stopTimer();
            else startTimer();
        });
    }

    private void setTimer(int mins) {
        if (isTimerRunning) return;
        timerTimeLeftMs = mins * 60 * 1000L;
        initialTimerTimeMs = timerTimeLeftMs;
        updateTimerText();
        if (pmTimerRing != null) pmTimerRing.setProgress(1000);
        isSessionInProgress = false;
        resetIdleTimer();
    }

    private void adjustTimer(int mins) {
        if (isTimerRunning) return;
        timerTimeLeftMs += mins * 60 * 1000L;
        if (timerTimeLeftMs < 0) timerTimeLeftMs = 0;
        initialTimerTimeMs = timerTimeLeftMs;
        updateTimerText();
        if (pmTimerRing != null) pmTimerRing.setProgress(1000);
        isSessionInProgress = false;
        resetIdleTimer();
    }

    private void setupAutoRepeatButton(View button, int deltaMins) {
        button.setOnTouchListener(new View.OnTouchListener() {
            private Handler repeatHandler;
            private final Runnable repeatRunnable = new Runnable() {
                @Override
                public void run() {
                    adjustTimer(deltaMins);
                    if (repeatHandler != null) {
                        repeatHandler.postDelayed(this, 150);
                    }
                }
            };

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        if (repeatHandler != null) return true;
                        repeatHandler = new Handler(Looper.getMainLooper());
                        adjustTimer(deltaMins);
                        repeatHandler.postDelayed(repeatRunnable, 500);
                        v.setPressed(true);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (repeatHandler != null) {
                            repeatHandler.removeCallbacks(repeatRunnable);
                            repeatHandler = null;
                        }
                        v.setPressed(false);
                        return true;
                }
                return false;
            }
        });
    }

    private void startTimer() {
        if (timerTimeLeftMs <= 0) return;
        isTimerRunning = true;
        updateButtonStates(false);
        btnStartStop.setText("STOP");
        btnStartStop.setBackgroundTintList(ColorStateList.valueOf(android.graphics.Color.parseColor("#444444")));
        btnStartStop.setTextColor(android.graphics.Color.WHITE);
        
        isSessionInProgress = true;
        idleHandler.removeCallbacks(idleExitRunnable);
        Log.d(TAG, "Focus timer started: Idle exit disabled.");
        triggerMacroDroidWebhook(true);

        focusTimer = new CountDownTimer(timerTimeLeftMs, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                timerTimeLeftMs = millisUntilFinished;
                updateTimerText();
                if (pmTimerRing != null && initialTimerTimeMs > 0) {
                    int progress = (int) (millisUntilFinished * 1000 / initialTimerTimeMs);
                    pmTimerRing.setProgress(progress);
                }
            }

            @Override
            public void onFinish() {
                isTimerRunning = false;
                timerTimeLeftMs = 0;
                updateTimerText();
                updateButtonStates(true);
                btnStartStop.setText("START");
                btnStartStop.setBackgroundTintList(ColorStateList.valueOf(android.graphics.Color.WHITE));
                btnStartStop.setTextColor(android.graphics.Color.BLACK);
                if (pmTimerRing != null) pmTimerRing.setProgress(0);
                
                isSessionInProgress = false;
                Log.d(TAG, "Focus timer finished: Resuming idle timer.");
                resetIdleTimer();
                triggerMacroDroidWebhook(false);
                
                // Flash the screen
                View flashOverlay = findViewById(R.id.pmFlashOverlay);
                if (flashOverlay != null) {
                    flashOverlay.setVisibility(View.VISIBLE);
                    android.animation.ObjectAnimator animator = android.animation.ObjectAnimator.ofFloat(flashOverlay, "alpha", 0f, 1f, 0f, 1f, 0f);
                    animator.setDuration(1500);
                    animator.addListener(new android.animation.AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(android.animation.Animator animation) {
                            flashOverlay.setVisibility(View.GONE);
                        }
                    });
                    animator.start();
                }

                // Call the webhook
                Request req = new Request.Builder()
                        .url(Constants.DEFAULT_HA_PROJECT_WEBHOOK_URL)
                        .post(RequestBody.create(new byte[0]))
                        .build();
                http.newCall(req).enqueue(new Callback() {
                    @Override public void onFailure(@NonNull Call c, @NonNull IOException e) {}
                    @Override public void onResponse(@NonNull Call c, @NonNull Response r) {
        try {  } finally {
            if (r != null) r.close();
        }
    }
                });

                Toast.makeText(ProjectModeActivity.this, "Focus Time Complete!", Toast.LENGTH_LONG).show();
            }
        }.start();
    }

    private void stopTimer() {
        if (focusTimer != null) focusTimer.cancel();
        isTimerRunning = false;
        updateButtonStates(true);
        btnStartStop.setText("START");
        btnStartStop.setBackgroundTintList(ColorStateList.valueOf(android.graphics.Color.WHITE));
        btnStartStop.setTextColor(android.graphics.Color.BLACK);
        if (pmTimerRing != null) pmTimerRing.setProgress(1000);
        
        Log.d(TAG, "Focus timer stopped: Resuming idle timer.");
        resetIdleTimer();
        triggerMacroDroidWebhook(false);
    }

    private void triggerMacroDroidWebhook(boolean active) {
        if (isDndActive == active) return; // Already in desired state

        Log.d(TAG, "Triggering MacroDroid Webhook: " + (active ? "DND ON" : "DND OFF"));
        
        Request request = new Request.Builder()
                .url(MACRODROID_WEBHOOK_URL)
                .get() // MacroDroid trigger URLs usually work with GET
                .build();

        http.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "MacroDroid Webhook Failed: " + e.getMessage());
            }
            @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
        try {
                Log.d(TAG, "MacroDroid Webhook Success: " + response.code());
                isDndActive = active;
                
            } finally {
            if (response != null) response.close();
        }
    }
        });
    }

    private void updateTimerText() {
        long seconds = (timerTimeLeftMs / 1000) % 60;
        long totalMinutes = (timerTimeLeftMs / 1000) / 60;
        long minutes = totalMinutes % 60;
        long hours = totalMinutes / 60;
        
        if (hours > 0) {
            timerDisplay.setText(String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds));
        } else {
            timerDisplay.setText(String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds));
        }
    }

    private void updateButtonStates(boolean enabled) {
        if (btnPlus5 != null) btnPlus5.setEnabled(enabled);
        if (btnMinus5 != null) btnMinus5.setEnabled(enabled);
        if (btnPreset25 != null) btnPreset25.setEnabled(enabled);
        if (btnPreset45 != null) btnPreset45.setEnabled(enabled);
        if (btnPreset60 != null) btnPreset60.setEnabled(enabled);
        
        float alpha = enabled ? 1.0f : 0.4f;
        if (btnPlus5 != null) btnPlus5.setAlpha(alpha);
        if (btnMinus5 != null) btnMinus5.setAlpha(alpha);
        if (btnPreset25 != null) btnPreset25.setAlpha(alpha);
        if (btnPreset45 != null) btnPreset45.setAlpha(alpha);
        if (btnPreset60 != null) btnPreset60.setAlpha(alpha);
    }

    // ==========================================
    // CLOCK
    // ==========================================
    private void updateClock() {
        long now = System.currentTimeMillis();
        clockTime.setText(DateFormat.format("HH:mm", now));
        clockDate.setText(DateFormat.format("EEEE, MMMM dd", now));
        handler.postDelayed(clockUpdater, 60000L - (now % 60000L)); // sync to next minute
    }

    // ==========================================
    // SPOTIFY MEDIA HUB
    // ==========================================
    private void setupSpotifyControls() {
        findViewById(R.id.pmBtnPlayPause).setOnClickListener(v -> mediaAction("playpause"));
        findViewById(R.id.pmBtnNext).setOnClickListener(v -> mediaAction("next"));
        findViewById(R.id.pmBtnPrev).setOnClickListener(v -> mediaAction("previous"));
        findViewById(R.id.pmBtnVolUp).setOnClickListener(v -> adjustVolume(true));
        findViewById(R.id.pmBtnVolDown).setOnClickListener(v -> adjustVolume(false));
    }

    private void mediaAction(String action) {
        String activeSource = "auto".equals(mediaSource) ? currentAutoEffectiveSource : mediaSource;
        if ("spotify".equals(activeSource)) {
            authManager.getAccessToken(new AuthManager.AuthCallback() {
                @Override
                public void onTokenReceived(String token) {
                    String method = "PUT";
                    String urlAction = action;
                    if (action.equals("playpause")) {
                        urlAction = isPlaying ? "pause" : "play";
                    }
                    if (urlAction.equals("next") || urlAction.equals("previous")) {
                        method = "POST";
                    }
                    Request request = new Request.Builder()
                            .url("https://api.spotify.com/v1/me/player/" + urlAction)
                            .addHeader("Authorization", "Bearer " + token)
                            .method(method, RequestBody.create(new byte[0]))
                            .build();

                    http.newCall(request).enqueue(new Callback() {
                        @Override public void onFailure(@NonNull Call c, @NonNull IOException e) {}
                        @Override public void onResponse(@NonNull Call c, @NonNull Response r) {
        try { 
                             
                            handler.postDelayed(spotifyUpdater, 500); 
                        } finally {
            if (r != null) r.close();
        }
    }
                    });
                }
                @Override public void onError(String error) {}
            });
        } else {
            if (jellyfinActiveSessionId == null || jellyfinActiveSessionId.isEmpty()) return;
            String jAction = action;
            if (action.equals("playpause")) {
                jAction = isPlaying ? "Pause" : "Unpause";
            } else if (action.equals("next")) {
                jAction = "NextTrack";
            } else if (action.equals("previous")) {
                jAction = "PreviousTrack";
            }
            String url = jellyfinServerUrl + "/Sessions/" + jellyfinActiveSessionId + "/Playing/" + jAction;
            if (url.contains("//Sessions")) {
                url = url.replace("//Sessions", "/Sessions");
            }
            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "MediaBrowser Token=\"" + jellyfinApiKey + "\"")
                    .post(RequestBody.create(new byte[0]))
                    .build();

          http.newCall(request).enqueue(new Callback() {
              @Override public void onFailure(@NonNull Call c, @NonNull IOException e) {}
              @Override public void onResponse(@NonNull Call c, @NonNull Response r) {
        try {
                  
                  handler.postDelayed(spotifyUpdater, 500);
              } finally {
            if (r != null) r.close();
        }
    }
          });
        }
    }

    private void adjustVolume(boolean up) {
        lastVolumeUpdateTime = System.currentTimeMillis();
        if (currentVolume == -1) currentVolume = 50;
        currentVolume = up ? Math.min(currentVolume + 4, 100) : Math.max(currentVolume - 4, 0);

        String activeSource = "auto".equals(mediaSource) ? currentAutoEffectiveSource : mediaSource;
        if ("spotify".equals(activeSource)) {
            authManager.getAccessToken(new AuthManager.AuthCallback() {
                @Override
                public void onTokenReceived(String token) {
                    Request request = new Request.Builder()
                            .url("https://api.spotify.com/v1/me/player/volume?volume_percent=" + currentVolume)
                            .addHeader("Authorization", "Bearer " + token)
                            .put(RequestBody.create(new byte[0]))
                            .build();

                    http.newCall(request).enqueue(new Callback() {
                        @Override public void onFailure(@NonNull Call c, @NonNull IOException e) {}
                        @Override public void onResponse(@NonNull Call c, @NonNull Response r) {
        try {  } finally {
            if (r != null) r.close();
        }
    }
                    });
                }
                @Override public void onError(String error) {}
            });
        } else {
            if (jellyfinActiveSessionId == null || jellyfinActiveSessionId.isEmpty()) return;

            String baseUrl = jellyfinServerUrl;
            if (baseUrl.endsWith("/")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            }

            // --- PIPELINE 1: DIRECT SOCKET COMMAND LAYER ---
            String commandUrl = baseUrl + "/Sessions/" + jellyfinActiveSessionId + "/Command";
            JSONObject jsonPayload = new JSONObject();
            try {
                JSONObject commandArgs = new JSONObject();
                commandArgs.put("Volume", String.valueOf(currentVolume));
                jsonPayload.put("Name", "SetVolume");
                jsonPayload.put("Arguments", commandArgs);
            } catch (Exception e) {
                Log.e("Jellyfin", "JSON composition failed: " + e.getMessage());
            }

            RequestBody jsonBody = RequestBody.create(
                    okhttp3.MediaType.parse("application/json; charset=utf-8"),
                    jsonPayload.toString()
            );

            Request commandRequest = new Request.Builder()
                    .url(commandUrl)
                    .addHeader("Authorization", "MediaBrowser Token=\"" + jellyfinApiKey + "\"")
                    .post(jsonBody)
                    .build();

            http.newCall(commandRequest).enqueue(new Callback() {
                @Override public void onFailure(@NonNull Call c, @NonNull IOException e) {}
                @Override public void onResponse(@NonNull Call c, @NonNull Response r) throws IOException {
        try {
                    
                    fetchJellyfinVolume();
                } finally {
            if (r != null) r.close();
        }
    }
            });

            // --- PIPELINE 2: URL PARAMETER FALLBACK LAYER ---
            String fallbackUrl = baseUrl + "/Sessions/" + jellyfinActiveSessionId + "/Playing/Volume"
                    + "?Volume=" + currentVolume
                    + "&volume=" + currentVolume
                    + "&VolumeLevel=" + currentVolume
                    + "&volumeLevel=" + currentVolume;

            RequestBody genericFormBody = RequestBody.create(
                    okhttp3.MediaType.parse("application/x-www-form-urlencoded; charset=utf-8"),
                    "Volume=" + currentVolume + "&VolumeLevel=" + currentVolume
            );

            Request fallbackRequest = new Request.Builder()
                    .url(fallbackUrl)
                    .addHeader("Authorization", "MediaBrowser Token=\"" + jellyfinApiKey + "\"")
                    .post(genericFormBody)
                    .build();

            http.newCall(fallbackRequest).enqueue(new Callback() {
                @Override public void onFailure(@NonNull Call c, @NonNull IOException e) {}
                @Override public void onResponse(@NonNull Call c, @NonNull Response r) throws IOException {
        try {  } finally {
            if (r != null) r.close();
        }
    }
            });
        }

        getSharedPreferences("SpotifyPrefs", MODE_PRIVATE).edit().putInt("last_volume", currentVolume).apply();
    }

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
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Failed to fetch Jellyfin volume: " + e.getMessage());
            }
            @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
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
                    Log.e(TAG, "Error parsing volume response: " + ex.getMessage());
                } finally {
                    response.close();
                }
            }
        });
    }

    private void updateVolumeUI() {
        // Implementation logic for updating volume UI
    }

    private void fetchCurrentSong() {
        if (!isSpotifyUpdateEnabled) {
            rescheduleSpotify();
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
                spotifyAccessToken = token;
                Request request = new Request.Builder()
                        .url("https://api.spotify.com/v1/me/player?additional_types=track,episode")
                        .addHeader("Authorization", "Bearer " + spotifyAccessToken)
                        .build();

                http.newCall(request).enqueue(new Callback() {
                    @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        checkInactivityReversion();
                        rescheduleSpotify();
                    }
                    @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
        try {
                        if (response.code() == 401) {
                            authManager.clearTokens();
                            
                            checkInactivityReversion();
                            rescheduleSpotify();
                            return;
                        }

                        if (response.code() == 429) {
                            String retryAfter = response.header("Retry-After", "5");
                            int waitSeconds = Integer.parseInt(retryAfter);
                            
                            handler.removeCallbacks(spotifyUpdater);
                            handler.postDelayed(spotifyUpdater, waitSeconds * 1000L);
                            return;
                        }

                        if (response.isSuccessful() && response.body() != null) {
                            if (response.code() == 204) {
                                
                                isPlaying = false;
                                runOnUiThread(() -> {
                                    songTitle.setText("Not Playing");
                                    artistName.setText("");
                                    progressBar.setProgress(0);
                                    Button playBtn = findViewById(R.id.pmBtnPlayPause);
                                    if (playBtn != null) {
                                        playBtn.setText("►");
                                    }
                                });
                                checkInactivityReversion();
                                rescheduleSpotify();
                                return;
                            }
                            
                            try {
                                String bodyStr = response.body().string();
                                if (bodyStr.isEmpty()) {
                                    checkInactivityReversion();
                                    rescheduleSpotify();
                                    return;
                                }
                                parseSpotifySessionAndUpdate(bodyStr);
                            } catch (Exception e) { Log.e(TAG, "Spotify parse error", e); }
                        }
                        
                        checkInactivityReversion();
                        rescheduleSpotify();
                    } finally {
            if (response != null) response.close();
        }
    }
                });
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Token error: " + error);
                checkInactivityReversion();
                rescheduleSpotify();
            }
        });
    }

    private void parseSpotifySessionAndUpdate(String bodyStr) {
        try {
            if (bodyStr == null || bodyStr.isEmpty()) {
                isPlaying = false;
                runOnUiThread(() -> {
                    songTitle.setText("Not Playing");
                    artistName.setText("");
                    progressBar.setProgress(0);
                    Button playBtn = findViewById(R.id.pmBtnPlayPause);
                    if (playBtn != null) {
                        playBtn.setText("►");
                    }
                });
                return;
            }
            JSONObject root = new JSONObject(bodyStr);
            if (root.isNull("item")) {
                return;
            }
            JSONObject item = root.getJSONObject("item");
            String title = item.optString("name", "Unknown Title");
            String artist = "Unknown Artist";
            String imgUrl = "";

            String itemType = item.optString("type", root.optString("currently_playing_type", "track"));
            if ("episode".equals(itemType) || item.has("show")) {
                if (item.has("show")) {
                    JSONObject showObj = item.getJSONObject("show");
                    artist = showObj.optString("name", showObj.optString("publisher", "Podcast"));
                } else {
                    artist = item.optString("publisher", "Podcast");
                }
                if (item.has("images") && item.getJSONArray("images").length() > 0) {
                    imgUrl = item.getJSONArray("images").getJSONObject(0).optString("url", "");
                } else if (item.has("show") && item.getJSONObject("show").has("images") && item.getJSONObject("show").getJSONArray("images").length() > 0) {
                    imgUrl = item.getJSONObject("show").getJSONArray("images").getJSONObject(0).optString("url", "");
                }
            } else {
                if (item.has("artists") && item.getJSONArray("artists").length() > 0) {
                    artist = item.getJSONArray("artists").getJSONObject(0).optString("name", "Unknown Artist");
                }
                if (item.has("album") && item.getJSONObject("album").has("images") && item.getJSONObject("album").getJSONArray("images").length() > 0) {
                    imgUrl = item.getJSONObject("album").getJSONArray("images").getJSONObject(0).optString("url", "");
                }
            }
            int durationMs = item.optInt("duration_ms", 1);
            int progressMs = root.optInt("progress_ms", 0);
            isPlaying = root.optBoolean("is_playing", false);

            final String finalArtist = artist;
            final String finalImgUrl = imgUrl;
            runOnUiThread(() -> {
                songTitle.setText(title);
                artistName.setText(finalArtist);
                if (!isDestroyed() && !isFinishing() && finalImgUrl != null && !finalImgUrl.isEmpty()) {
                    Glide.with(ProjectModeActivity.this).load(finalImgUrl).into(albumArt);
                }
                progressBar.setMax(durationMs);
                progressBar.setProgress(progressMs);
                
                Button playBtn = findViewById(R.id.pmBtnPlayPause);
                if (playBtn != null) {
                    playBtn.setText(isPlaying ? "II" : "►");
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Spotify parse error", e);
        }
    }

    private void rescheduleSpotify() {
        handler.removeCallbacks(spotifyUpdater);
        handler.postDelayed(spotifyUpdater, 2000);
    }

    // ==========================================
    // GOOGLE CALENDAR OAUTH & API
    // ==========================================
    private final ActivityResultLauncher<Intent> signInLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(result.getData());
                    handleSignInResult(task);
                } else {
                    Toast.makeText(this, "Google Sign-In Cancelled", Toast.LENGTH_SHORT).show();
                }
            }
    );

    private void setupGoogleSignIn() {
        // NOTE: We need a string resource or BuildConfig for the Client ID
        // For now, we will try to silently sign in or prompt if needed.
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(new Scope("https://www.googleapis.com/auth/calendar.readonly"))
                .build();

        mGoogleSignInClient = GoogleSignIn.getClient(this, gso);

        // Try silent sign-in first
        mGoogleSignInClient.silentSignIn()
                .addOnCompleteListener(this, task -> {
                    handleSignInResult(task);
                });
    }

    private void handleSignInResult(Task<GoogleSignInAccount> completedTask) {
        try {
            GoogleSignInAccount account = completedTask.getResult(ApiException.class);
            // Sign in success. However, GoogleSignInAccount doesn't give an access token directly that can be used with OkHttp easily without GoogleAuthUtil.
            // Using GoogleAuthUtil requires a background thread.
            new Thread(() -> {
                try {
                    String token = com.google.android.gms.auth.GoogleAuthUtil.getToken(
                            ProjectModeActivity.this,
                            account.getAccount(),
                            "oauth2:https://www.googleapis.com/auth/calendar.readonly"
                    );
                    googleAuthToken = token;
                    Log.d(TAG, "Got Google Token! Google Sign-In Success!");
                    runOnUiThread(calendarUpdater);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to get auth token", e);
                    runOnUiThread(() -> Toast.makeText(ProjectModeActivity.this, "Auth Token Error: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            }).start();
        } catch (ApiException e) {
            Log.w(TAG, "signInResult:failed code=" + e.getStatusCode());
            // Prompt interactive sign in
            Intent signInIntent = mGoogleSignInClient.getSignInIntent();
            signInLauncher.launch(signInIntent);
        }
    }

    private void fetchCalendarEvents() {
        if (googleAuthToken == null) return;
        
        Request request = new Request.Builder()
                .url("https://www.googleapis.com/calendar/v3/users/me/calendarList")
                .addHeader("Authorization", "Bearer " + googleAuthToken)
                .build();

        http.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                runOnUiThread(() -> Toast.makeText(ProjectModeActivity.this, "Calendar Network Error", Toast.LENGTH_SHORT).show());
                handler.postDelayed(calendarUpdater, 60000);
            }
            @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
        try {
                if (!response.isSuccessful()) {
                    Log.e(TAG, "CalendarList API Error: " + response.code());
                    runOnUiThread(() -> Toast.makeText(ProjectModeActivity.this, "Calendar API Error " + response.code(), Toast.LENGTH_LONG).show());
                    
                    handler.postDelayed(calendarUpdater, 60000);
                    return;
                }
                
                try {
                    JSONObject root = new JSONObject(response.body().string());
                    JSONArray items = root.getJSONArray("items");
                    java.util.Map<String, String> calendarMap = new java.util.HashMap<>();
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject cal = items.getJSONObject(i);
                        calendarMap.put(cal.getString("id"), cal.optString("summary", "Unknown"));
                    }
                    fetchEventsFromCalendars(calendarMap);
                } catch (Exception e) {
                    Log.e(TAG, "Calendar parse error", e);
                    handler.postDelayed(calendarUpdater, 60000);
                }
                
            } finally {
            if (response != null) response.close();
        }
    }
        });
    }

    private void fetchEventsFromCalendars(java.util.Map<String, String> calendars) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        String timeMin = sdf.format(new Date());
        long now = System.currentTimeMillis();

        List<CalendarEventAdapter.CalendarEvent> allEvents = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicInteger pendingRequests = new java.util.concurrent.atomic.AtomicInteger(calendars.size());

        for (java.util.Map.Entry<String, String> entry : calendars.entrySet()) {
            String calId = entry.getKey();
            String calName = entry.getValue();
            String url = "https://www.googleapis.com/calendar/v3/calendars/" + android.net.Uri.encode(calId) + "/events" +
                    "?timeMin=" + timeMin +
                    "&orderBy=startTime" +
                    "&singleEvents=true" +
                    "&maxResults=50";

            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer " + googleAuthToken)
                    .build();

            http.newCall(request).enqueue(new Callback() {
                @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    checkIfDone(pendingRequests, allEvents);
                }
                @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
        try {
                    if (response.isSuccessful() && response.body() != null) {
                        try {
                            JSONObject root = new JSONObject(response.body().string());
                            JSONArray items = root.getJSONArray("items");
                            
                            for (int i = 0; i < items.length(); i++) {
                                JSONObject event = items.getJSONObject(i);
                                String summary = event.optString("summary", "Unknown Event");
                                String colorId = event.optString("colorId", "NONE");
                                
                                boolean isRedColor = "11".equals(colorId) || "4".equals(colorId);
                                boolean hasRedTitle = summary.toLowerCase().contains("[red]") || summary.contains("עגבנייה");
                                boolean isExamsCalendar = calName.contains("מבחנים") && "NONE".equals(colorId);

                                if (isRedColor || hasRedTitle || isExamsCalendar) {
                                    JSONObject start = event.optJSONObject("start");
                                    if (start != null) {
                                        String dateTime = start.optString("dateTime");
                                        String dateOnly = start.optString("date");

                                        try {
                                            Date date = null;
                                            if (dateTime != null && !dateTime.isEmpty()) {
                                                SimpleDateFormat isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US);
                                                date = isoFormat.parse(dateTime);
                                            } else if (dateOnly != null && !dateOnly.isEmpty()) {
                                                SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                                                date = dateFormat.parse(dateOnly);
                                            }

                                            if (date != null) {
                                                long timeUntil = date.getTime() - now;
                                                allEvents.add(new CalendarEventAdapter.CalendarEvent(summary, timeUntil, date.getTime()));
                                            }
                                        } catch (Exception e) {}
                                    }
                                }
                            }
                        } catch (Exception e) {}
                    }
                    
                    checkIfDone(pendingRequests, allEvents);
                } finally {
            if (response != null) response.close();
        }
    }
            });
        }
    }

    private void checkIfDone(java.util.concurrent.atomic.AtomicInteger pendingRequests, List<CalendarEventAdapter.CalendarEvent> allEvents) {
        if (pendingRequests.decrementAndGet() == 0) {
            // Sort by timeUntil
            List<CalendarEventAdapter.CalendarEvent> sortedEvents = new ArrayList<>(allEvents);
            sortedEvents.sort((e1, e2) -> Long.compare(e1.timeUntilMs, e2.timeUntilMs));
            
            runOnUiThread(() -> calendarAdapter.updateEvents(sortedEvents));
            
            handler.postDelayed(calendarUpdater, 60000); // Check again in a minute
        }
    }

    // ==========================================
    // LIFECYCLE CLEANUP
    // ==========================================
    @Override
    protected void onPause() {
        super.onPause();
        // We pause the intensive updates if we leave the activity
        handler.removeCallbacksAndMessages(null);
        idleHandler.removeCallbacks(idleExitRunnable);
    }

    private void toggleLyricsAnimation() {
        isLyricsAnimationEnabled = !isLyricsAnimationEnabled;
        getSharedPreferences("SpotifyPrefs", MODE_PRIVATE).edit().putBoolean("lyrics_animation_enabled", isLyricsAnimationEnabled).apply();
        syncAnimationToggleUI();
    }

    private void syncAnimationToggleUI() {
        if (pmBtnToggleAnimation != null) {
            pmBtnToggleAnimation.setAlpha(isLyricsAnimationEnabled ? 1.0f : 0.3f);
        }
    }

    private void toggleSpotifyUpdates() {
        isSpotifyUpdateEnabled = !isSpotifyUpdateEnabled;
        getSharedPreferences("SpotifyPrefs", MODE_PRIVATE).edit().putBoolean("spotify_updates_enabled", isSpotifyUpdateEnabled).apply();
        syncToggleUI();
        
        String msg = isSpotifyUpdateEnabled ? "Spotify updates enabled" : "Spotify updates disabled";

        if (!isSpotifyUpdateEnabled) {
            if (songTitle != null) songTitle.setText("Updates Paused");
            if (artistName != null) artistName.setText("");
            if (albumArt != null) {
                if (!isDestroyed() && !isFinishing()) {
                    Glide.with(ProjectModeActivity.this).clear(albumArt);
                }
                albumArt.setImageDrawable(null);
            }
            if (progressBar != null) progressBar.setProgress(0);
        }

        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        if (isSpotifyUpdateEnabled) {
            handler.postDelayed(this::fetchCurrentSong, 200); // Small delay to ensure state
            rescheduleSpotify();
        }
    }

    private void syncToggleUI() {
        float alpha = isSpotifyUpdateEnabled ? 0.3f : 1.0f;
        if (btnToggleUpdates != null) btnToggleUpdates.setAlpha(alpha);
    }

    @Override
    protected void onResume() {
        super.onResume();
        
        // Sync update state and media source from shared prefs
        isSpotifyUpdateEnabled = getSharedPreferences("SpotifyPrefs", MODE_PRIVATE).getBoolean("spotify_updates_enabled", true);
        mediaSource = getSharedPreferences("SpotifyPrefs", MODE_PRIVATE).getString("media_source", "auto");
        jellyfinServerUrl = getSharedPreferences(Constants.PREF_NAME, MODE_PRIVATE).getString(Constants.PREF_KEY_JELLYFIN_SERVER_URL, Constants.DEFAULT_JELLYFIN_URL);
        jellyfinApiKey = getSharedPreferences(Constants.PREF_NAME, MODE_PRIVATE).getString(Constants.PREF_KEY_JELLYFIN_API_KEY, Constants.DEFAULT_JELLYFIN_API_KEY);
        jellyfinClientName = getSharedPreferences(Constants.PREF_NAME, MODE_PRIVATE).getString(Constants.PREF_KEY_JELLYFIN_CLIENT_NAME, "");
        jellyfinTargetUserName = getSharedPreferences(Constants.PREF_NAME, MODE_PRIVATE).getString(Constants.PREF_KEY_JELLYFIN_TARGET_USER, Constants.DEFAULT_JELLYFIN_TARGET_USER);

        syncToggleUI();
        updateSourceTheme(mediaSource);

        if (!isSpotifyUpdateEnabled) {
            if (songTitle != null) songTitle.setText("Updates Paused");
            if (artistName != null) artistName.setText("");
            if (albumArt != null) albumArt.setImageDrawable(null);
            if (progressBar != null) progressBar.setProgress(0);
        }

        // Resume handlers
        handler.post(clockUpdater);
        handler.post(spotifyUpdater);
        if (googleAuthToken != null) handler.post(calendarUpdater);
        resetIdleTimer();
    }

    private JSONObject getJellyfinActiveSession(String body) {
        try {
            if (body == null || body.isEmpty()) return null;
            JSONArray sessions = new JSONArray(body);
            
            // Step 1: Session stickiness prioritizing our active session ID
            if (jellyfinActiveSessionId != null && !jellyfinActiveSessionId.isEmpty()) {
                for (int i = 0; i < sessions.length(); i++) {
                    JSONObject session = sessions.getJSONObject(i);
                    if (session.has("NowPlayingItem") && !session.isNull("NowPlayingItem")) {
                        String sId = session.optString("Id", "");
                        if (jellyfinActiveSessionId.equals(sId)) {
                            boolean match = true;
                            String sessionUserName = session.optString("UserName", "");
                            if (!jellyfinTargetUserName.isEmpty() &&
                                    !sessionUserName.equalsIgnoreCase(jellyfinTargetUserName)) {
                                match = false;
                            }
                            if (match && !jellyfinClientName.isEmpty()) {
                                String client = session.optString("Client", "");
                                String devName = session.optString("DeviceName", "");
                                if (!client.equalsIgnoreCase(jellyfinClientName) && !devName.equalsIgnoreCase(jellyfinClientName)) {
                                    match = false;
                                }
                            }
                            if (match) {
                                return session;
                            }
                        }
                    }
                }
            }

            // Step 2: Original fallback selection
            JSONObject activeSession = null;
            for (int i = 0; i < sessions.length(); i++) {
                JSONObject session = sessions.getJSONObject(i);
                if (session.has("NowPlayingItem") && !session.isNull("NowPlayingItem")) {
                    String sessionUserName = session.optString("UserName", "");
                    if (!jellyfinTargetUserName.isEmpty() &&
                            !sessionUserName.equalsIgnoreCase(jellyfinTargetUserName)) {
                        continue;
                    }
                    if (!jellyfinClientName.isEmpty()) {
                        String client = session.optString("Client", "");
                        String devName = session.optString("DeviceName", "");
                        if (client.equalsIgnoreCase(jellyfinClientName) || devName.equalsIgnoreCase(jellyfinClientName)) {
                            activeSession = session;
                            break;
                        }
                    }
                    if (activeSession == null) {
                        activeSession = session;
                    }
                }
            }
            return activeSession;
        } catch (Exception e) {
            Log.e("Jellyfin", "getJellyfinActiveSession: " + e.getMessage());
            return null;
        }
    }

    private void parseJellyfinSessionAndUpdate(JSONObject activeSession) {
        try {
            if (activeSession == null) {
                isPlaying = false;
                jellyfinActiveSessionId = "";
                runOnUiThread(() -> {
                    songTitle.setText("Not Playing");
                    artistName.setText("");
                    albumArt.setImageDrawable(null);
                    progressBar.setProgress(0);
                    Button playBtn = findViewById(R.id.pmBtnPlayPause);
                    if (playBtn != null) playBtn.setText("►");
                });
                return;
            }

            jellyfinActiveSessionId = activeSession.getString("Id");
            JSONObject item = activeSession.getJSONObject("NowPlayingItem");
            JSONObject playState = activeSession.getJSONObject("PlayState");

            final String title = item.optString("Name", "Unknown Title");
            final JSONArray artistsArr = item.optJSONArray("Artists");
            final String artist = (artistsArr != null && artistsArr.length() > 0) ? artistsArr.getString(0) : "Unknown Artist";
            final String itemId = item.getString("Id");
            
            long runtimeTicks = item.optLong("RunTimeTicks", 0);
            long positionTicks = playState.optLong("PositionTicks", 0);
            int durationMs = (int) (runtimeTicks / 10000);
            int progressMs = (int) (positionTicks / 10000);
            
            isPlaying = !playState.optBoolean("IsPaused", false);

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

            runOnUiThread(() -> {
                songTitle.setText(title);
                artistName.setText(artist);
                if (!isDestroyed() && !isFinishing() && imgUrl != null && !imgUrl.isEmpty()) {
                    Glide.with(ProjectModeActivity.this).load(imgUrl).into(albumArt);
                }
                progressBar.setMax(durationMs);
                progressBar.setProgress(progressMs);
                
                Button playBtn = findViewById(R.id.pmBtnPlayPause);
                if (playBtn != null) {
                    playBtn.setText(isPlaying ? "II" : "►");
                }
            });
        } catch (Exception e) {
            Log.e("Jellyfin", "Parse error: " + e.getMessage());
        }
    }

    private void fetchJellyfinCurrentSong() {
        if (jellyfinServerUrl == null || jellyfinServerUrl.isEmpty() || jellyfinApiKey == null || jellyfinApiKey.isEmpty()) {
            runOnUiThread(() -> {
                songTitle.setText("Jellyfin Not Configured");
                artistName.setText("Long-press bottom-left button to setup");
                albumArt.setImageDrawable(null);
                progressBar.setProgress(0);
            });
            checkInactivityReversion();
            rescheduleSpotify();
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
                });
                checkInactivityReversion();
                rescheduleSpotify();
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
        try {
                if (!response.isSuccessful()) {
                    runOnUiThread(() -> {
                        songTitle.setText("Jellyfin Connection Error");
                        artistName.setText("Status: " + response.code());
                    });
                    
                    checkInactivityReversion();
                    rescheduleSpotify();
                    return;
                }

                String body = response.body().string();
                
                JSONObject activeSession = getJellyfinActiveSession(body);
                parseJellyfinSessionAndUpdate(activeSession);
                checkInactivityReversion();
                rescheduleSpotify();
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
                spotifyAccessToken = token;
                Request request = new Request.Builder()
                        .url("https://api.spotify.com/v1/me/player?additional_types=track,episode")
                        .addHeader("Authorization", "Bearer " + spotifyAccessToken)
                        .build();

                http.newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        synchronized (autoModeLock) {
                            spotifyChecked = true;
                            spotifyActive = false;
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
                                    spotifyActive = json.optBoolean("is_playing", false);
                                } catch (Exception e) {
                                    spotifyActive = false;
                                }
                            } else {
                                spotifyActive = false;
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
                    spotifyActive = false;
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

            if (spotifyActive && jellyfinActive) {
                currentAutoEffectiveSource = "spotify";
            } else if (spotifyActive) {
                currentAutoEffectiveSource = "spotify";
            } else if (jellyfinActive) {
                currentAutoEffectiveSource = "jellyfin";
            }

            final String activeSource = currentAutoEffectiveSource;

            runOnUiThread(() -> {
                updateSourceTheme("auto");
            });

            if ("spotify".equals(activeSource)) {
                if (tempSpotifyBody != null && !tempSpotifyBody.isEmpty()) {
                    parseSpotifySessionAndUpdate(tempSpotifyBody);
                } else {
                    isPlaying = false;
                    runOnUiThread(() -> {
                        songTitle.setText("Not Playing");
                        artistName.setText("");
                        progressBar.setProgress(0);
                        Button playBtn = findViewById(R.id.pmBtnPlayPause);
                        if (playBtn != null) {
                            playBtn.setText("►");
                        }
                    });
                }
            } else {
                JSONObject activeSession = getJellyfinActiveSession(tempJellyfinBody);
                parseJellyfinSessionAndUpdate(activeSession);
            }

            checkInactivityReversion();
            rescheduleSpotify();
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

            updateSourceTheme("auto");
            Toast.makeText(ProjectModeActivity.this, "Inactivity timeout: Reverted to Auto Mode", Toast.LENGTH_LONG).show();

            handler.removeCallbacks(spotifyUpdater);
            handler.post(spotifyUpdater);
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
                rescheduleSpotify();
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

        runOnUiThread(() -> {
            songTitle.setText("Loading...");
            artistName.setText("");
            albumArt.setImageDrawable(null);
            progressBar.setProgress(0);
        });

        rescheduleSpotify();
    }

    private void updateSourceTheme(String source) {
        String effectiveSource = source;
        if ("auto".equals(source)) {
            effectiveSource = currentAutoEffectiveSource;
        }
        int color = android.graphics.Color.parseColor("spotify".equals(effectiveSource) ? "#1DB954" : "#A259FF");
        int sourceBtnColor = android.graphics.Color.parseColor(
                "auto".equals(source) ? "#007BFF" : ("spotify".equals(source) ? "#1DB954" : "#A259FF")
        );
        String indicatorIcon = "auto".equals(source) ? "🔵" : ("spotify".equals(source) ? "🟢" : "🟣");

        runOnUiThread(() -> {
            if (pmBtnMediaSource != null) {
                pmBtnMediaSource.setText(indicatorIcon);
                pmBtnMediaSource.setTextColor(sourceBtnColor);
            }
            Button playBtn = findViewById(R.id.pmBtnPlayPause);
            if (playBtn != null) playBtn.setTextColor(color);
            
            Button nextBtn = findViewById(R.id.pmBtnNext);
            if (nextBtn != null) nextBtn.setTextColor(color);
            
            Button prevBtn = findViewById(R.id.pmBtnPrev);
            if (prevBtn != null) prevBtn.setTextColor(color);
            
            Button volUp = findViewById(R.id.pmBtnVolUp);
            if (volUp != null) volUp.setTextColor(color);
            
            Button volDown = findViewById(R.id.pmBtnVolDown);
            if (volDown != null) volDown.setTextColor(color);
            
            if (progressBar != null) {
                progressBar.setProgressTintList(ColorStateList.valueOf(color));
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Fully cleanup to prevent leaks to MainActivity
        triggerMacroDroidWebhook(false);
        handler.removeCallbacksAndMessages(null);
        idleHandler.removeCallbacksAndMessages(null);
        if (focusTimer != null) {
            focusTimer.cancel();
            focusTimer = null;
        }
    }
}

