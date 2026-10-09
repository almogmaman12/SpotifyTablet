package com.almog.spotifytablet;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.graphics.drawable.GradientDrawable;
import android.content.Intent;
import java.util.Calendar;
import java.util.Locale;
import java.text.SimpleDateFormat;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class WeatherActivity extends AppCompatActivity {

    private static final String TAG = "WeatherPage";
    private static final String WEATHER_API_KEY = Constants.WEATHER_API_KEY;
    private static final String CITY = Constants.DEFAULT_WEATHER_CITY;
    private static final long IDLE_EXIT_TIMEOUT = 10 * 60 * 1000L;

    private final OkHttpClient http = NetworkClient.getInstance();
    private final Handler idleHandler = new Handler(Looper.getMainLooper());
    private final Runnable idleExitRunnable = this::finish;


    private int currentDayIndex = 0;
    private JSONArray allDays;

    private View rootView;
    private LinearLayout hourlyContainer;
    private ImageView imgWeatherIconLarge;
    private TextView txtTemperature, txtCondition, txtLocation;
    private TextView txtHumidity, txtWind, txtUV, txtFeelsLike;
    private ImageButton btnNextDays;


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

        // Fullscreen setup
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
        );

        setContentView(R.layout.activity_weather);
        if (getSupportActionBar() != null) getSupportActionBar().hide();

        initUI();
        fetchWeather();
        resetIdleTimer();
    }

    private void initUI() {
        rootView = findViewById(android.R.id.content);
        imgWeatherIconLarge = findViewById(R.id.imgWeatherIconLarge);
        txtTemperature = findViewById(R.id.txtTemperature);
        txtCondition = findViewById(R.id.txtCondition);
        txtLocation = findViewById(R.id.txtLocation);
        txtHumidity = findViewById(R.id.txtHumidity);
        txtWind = findViewById(R.id.txtWind);
        txtUV = findViewById(R.id.txtUV);
        txtFeelsLike = findViewById(R.id.txtFeelsLike);
        hourlyContainer = findViewById(R.id.hourlyContainer);
        // Navigation to Days screen
        btnNextDays = findViewById(R.id.btnNextDays);
        btnNextDays.setOnClickListener(v -> {
            Intent intent = new Intent(WeatherActivity.this, DaysActivity.class);
            if (allDays != null) {
                intent.putExtra("allDays", allDays.toString());
            }
            startActivity(intent);
        });

        findViewById(R.id.btnExitWeather).setOnClickListener(v -> finish());

        // Start with content invisible for fade-in
        rootView.setAlpha(0f);
        rootView.animate().alpha(1f).setDuration(500).setStartDelay(100).start();
    }

    private void openDayDetail(int dayIndex) {
        if (allDays == null) return;
        try {
            JSONObject dayObj = allDays.getJSONObject(dayIndex);
            Intent intent = new Intent(this, DayDetailActivity.class);
            intent.putExtra("DAY_JSON", dayObj.toString());
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Open day detail error: " + e.getMessage());
        }
    }

    private void fetchWeather() {
        String url = "https://api.weatherapi.com/v1/forecast.json?key=" + WEATHER_API_KEY + "&q=" + CITY + "&days=7&aqi=no&alerts=no";
        Request request = new Request.Builder().url(url).build();

        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Fetch failed: " + e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
        try {
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        String body = response.body().string();
                        JSONObject json = new JSONObject(body);
                        // Store all forecast days for navigation
                        allDays = json.getJSONObject("forecast").getJSONArray("forecastday");
                        updateUI(json);
                    } catch (Exception e) {
                        Log.e(TAG, "Parse error: " + e.getMessage());
                    }
                }
                
            } finally {
            if (response != null) response.close();
        }
    }
        });
    }

    private void updateUI(JSONObject json) throws JSONException {
        JSONObject current = json.getJSONObject("current");
        JSONObject condition = current.getJSONObject("condition");

        double temp = current.getDouble("temp_c");
        String desc = condition.getString("text");
        // Translate common English condition descriptions to Hebrew
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
        // Translate known English condition words to Hebrew
        if (desc.equalsIgnoreCase("Mist")) {
            desc = "ערפל";
        }
        String locName = "ירושלים, ישראל";
        String iconUrl = "https:" + condition.getString("icon").replace("64x64", "128x128");
        int humidity = current.getInt("humidity");
        double windKmh = current.getDouble("wind_kph");
        double uv = current.getDouble("uv");
        double feelsLike = current.getDouble("feelslike_c");
        int weatherCode = condition.getInt("code");
        int isDay = current.getInt("is_day");

        JSONArray hourly = json.getJSONObject("forecast").getJSONArray("forecastday").getJSONObject(0).getJSONArray("hour");
                final String finalDesc = desc;
        runOnUiThread(() -> {
            try {
                // Set main info
                txtTemperature.setText(Math.round(temp) + "°");
                txtCondition.setText(finalDesc);
                txtLocation.setText(locName);
                txtFeelsLike.setText(Math.round(feelsLike) + "°");
                txtHumidity.setText(humidity + "%");
                txtWind.setText(Math.round(windKmh) + " קמ\"ש");
                txtUV.setText(getUVLevelText(uv));



                // Dynamic background based on weather
                applyDynamicBackground(weatherCode, isDay);

                // Hourly forecast - logic for centering is now handled by XML
                hourlyContainer.removeAllViews();
                int currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
                int itemCount = 0;

                for (int i = 0; i < hourly.length(); i++) {
                    JSONObject hourObj = hourly.getJSONObject(i);
                    String time = hourObj.getString("time");
                    int hour = Integer.parseInt(time.split(" ")[1].split(":")[0]);

                    // Skip hours that have already passed
                    if (hour < currentHour) continue;

                    double hTemp = hourObj.getDouble("temp_c");
                    String hIcon = "https:" + hourObj.getJSONObject("condition").getString("icon");

                    View hourView = getLayoutInflater().inflate(R.layout.item_weather_hour, hourlyContainer, false);
                    TextView timeTv = hourView.findViewById(R.id.hourTime);
                    TextView tempTv = hourView.findViewById(R.id.hourTemp);
                    ImageView iconIv = hourView.findViewById(R.id.hourIcon);

                    timeTv.setText(hour == currentHour ? "עכשיו" : String.format("%02d:00", hour));
                    tempTv.setText(Math.round(hTemp) + "°");
                    Glide.with(WeatherActivity.this).load(hIcon).into(iconIv);

                    // Staggered fade-in animation
                    hourView.setAlpha(0f);
                    hourView.setTranslationY(20f);
                    int delay = itemCount * 50;
                    hourView.animate()
                            .alpha(1f)
                            .translationY(0f)
                            .setDuration(300)
                            .setStartDelay(delay)
                            .start();

                    hourlyContainer.addView(hourView);
                    itemCount++;
                }

            } catch (JSONException e) {
                Log.e(TAG, "UI update error: " + e.getMessage());
            }
        });
    }

    private void applyDynamicBackground(int weatherCode, int isDay) {
        int[] colors;

        if (isDay == 0) {
            // Night
            colors = new int[]{0xFF0D1B2A, 0xFF1B2838, 0xFF1A1A2E};
        } else if (weatherCode == 1000) {
            // Sunny
            colors = new int[]{0xFF0F2027, 0xFF2C5364, 0xFF1B4F72};
        } else if (weatherCode >= 1003 && weatherCode <= 1009) {
            // Cloudy
            colors = new int[]{0xFF1A1A2E, 0xFF16213E, 0xFF0F3460};
        } else if (weatherCode >= 1063 && weatherCode <= 1282) {
            // Rain/Snow
            colors = new int[]{0xFF0C0C1E, 0xFF1A1A2E, 0xFF2D2D44};
        } else {
            // Default
            colors = new int[]{0xFF0F2027, 0xFF203A43, 0xFF2C5364};
        }

        GradientDrawable gradient = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        rootView.setBackground(gradient);
    }

    private String getUVLevelText(double uv) {
        if (uv <= 2) return "נמוך";
        if (uv <= 5) return "בינוני";
        if (uv <= 7) return "גבוה";
        if (uv <= 10) return "גבוה מאוד";
        return "קיצוני";
    }

    private void resetIdleTimer() {
        idleHandler.removeCallbacks(idleExitRunnable);
        idleHandler.postDelayed(idleExitRunnable, IDLE_EXIT_TIMEOUT);
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        resetIdleTimer();
    }

    @Override
    protected void onPause() {
        super.onPause();
        idleHandler.removeCallbacks(idleExitRunnable);
    }
}