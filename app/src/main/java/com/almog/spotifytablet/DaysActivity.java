// DaysActivity.java – shows upcoming days in a glass‑morphic list
package com.almog.spotifytablet;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import com.bumptech.glide.Glide;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class DaysActivity extends AppCompatActivity {
    private LinearLayout dayListContainer;
    private JSONArray allDays;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        
        // Fullscreen setup
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
        );
        if (getSupportActionBar() != null) getSupportActionBar().hide();

        setContentView(R.layout.activity_days);

        // Close‑icon (X) button
        ImageView btnClose = findViewById(R.id.btnCloseDays);
        btnClose.setOnClickListener(v -> finish());

        dayListContainer = findViewById(R.id.dayListContainer);
        // Retrieve data passed from WeatherActivity
        String daysJson = getIntent().getStringExtra("allDays");
        if (daysJson != null) {
            try {
                allDays = new JSONArray(daysJson);
                renderDayList();
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
    }

    private void renderDayList() {
        LayoutInflater inflater = LayoutInflater.from(this);
        // Show up to 7 days, but skip today (i = 0)
        int count = Math.min(8, allDays.length()); 
        for (int i = 1; i < count; i++) {
            try {
                JSONObject forecastDay = allDays.getJSONObject(i);
                View dayCard = inflater.inflate(R.layout.item_weather_day, dayListContainer, false);

                TextView txtDay = dayCard.findViewById(R.id.dayDate);
                TextView txtTemp = dayCard.findViewById(R.id.dayTemp);
                ImageView imgIcon = dayCard.findViewById(R.id.dayIcon);

                String dateStr = forecastDay.optString("date", "");
                
                // Format the date to just the day of the week (e.g. "Monday")
                String dayOfWeek = dateStr;
                try {
                    java.text.SimpleDateFormat inFormat = new java.text.SimpleDateFormat("yyyy-MM-dd", new java.util.Locale("he"));
                    java.util.Date date = inFormat.parse(dateStr);
                    java.text.SimpleDateFormat outFormat = new java.text.SimpleDateFormat("EEEE", new java.util.Locale("he"));
                    dayOfWeek = outFormat.format(date);
                } catch (Exception e) {
                    Log.e("DaysActivity", "Failed to parse date: " + e.getMessage());
                }

                JSONObject dayData = forecastDay.optJSONObject("day");
                if (dayData != null) {
                    double maxTemp = dayData.optDouble("maxtemp_c", 0);
                    double minTemp = dayData.optDouble("mintemp_c", 0);
                    JSONObject condition = dayData.optJSONObject("condition");
                    String iconUrl = "";
                    if (condition != null) {
                        iconUrl = "https:" + condition.optString("icon", "").replace("64x64", "128x128");
                    }
                    
                    txtDay.setText(dayOfWeek);
                    txtTemp.setText(Math.round(minTemp) + "° / " + Math.round(maxTemp) + "°");
                    
                    if (!iconUrl.isEmpty()) {
                        Glide.with(DaysActivity.this).load(iconUrl).into(imgIcon);
                    }
                }

                // Click opens DayDetailActivity for this day
                final int index = i;
                final String dayJsonStr = forecastDay.toString();
                dayCard.setOnClickListener(v -> {
                    Log.i("DaysActivity", "Clicked day " + index);
                    try {
                        Intent intent = new Intent(DaysActivity.this, DayDetailActivity.class);
                        intent.putExtra("DAY_JSON", dayJsonStr);
                        startActivity(intent);
                    } catch (Exception ex) {
                        Log.e("DaysActivity", "Error starting DayDetailActivity: " + ex.getMessage());
                        android.widget.Toast.makeText(DaysActivity.this, "Error: " + ex.getMessage(), android.widget.Toast.LENGTH_LONG).show();
                    }
                });

                dayListContainer.addView(dayCard);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
    }
}
