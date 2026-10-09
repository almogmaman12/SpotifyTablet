package com.almog.spotifytablet;

import android.content.Intent;
import android.os.Bundle;
import java.util.Locale;
import android.util.Log;
import android.widget.ImageView;
import android.widget.TextView;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;

import org.json.JSONObject;

public class DayDetailActivity extends AppCompatActivity {
    private static final String TAG = "DayDetail";
    private TextView txtDate;
    private TextView txtCondition;
    private TextView txtTemp;
    private ImageView imgIcon;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
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
        if (getSupportActionBar() != null) getSupportActionBar().hide();
        
        setContentView(R.layout.activity_day_detail);

        txtDate = findViewById(R.id.dayDetailDate);
        txtCondition = findViewById(R.id.dayDetailCondition);
        txtTemp = findViewById(R.id.dayDetailTemp);
        TextView txtHighLow = findViewById(R.id.dayDetailHighLow);
        TextView txtHumidity = findViewById(R.id.dayDetailHumidity);
        TextView txtWind = findViewById(R.id.dayDetailWind);
        TextView txtUV = findViewById(R.id.dayDetailUV);
        imgIcon = findViewById(R.id.dayDetailIcon);

        ImageView btnClose = findViewById(R.id.btnCloseDayDetail);
        btnClose.setOnClickListener(v -> finish());

        Intent intent = getIntent();
        if (intent != null && intent.hasExtra("DAY_JSON")) {
            try {
                String jsonStr = intent.getStringExtra("DAY_JSON");
                JSONObject dayObj = new JSONObject(jsonStr);
                String dateStr = dayObj.getString("date");
                
                // Format the date to "Day of week" in Hebrew
                String formattedDate = dateStr;
                try {
                    java.text.SimpleDateFormat inFormat = new java.text.SimpleDateFormat("yyyy-MM-dd", new Locale("he"));
                    java.util.Date date = inFormat.parse(dateStr);
                    java.text.SimpleDateFormat outFormat = new java.text.SimpleDateFormat("EEEE, MMM d", new Locale("he"));
                    formattedDate = outFormat.format(date);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to parse date: " + e.getMessage());
                }

                JSONObject day = dayObj.getJSONObject("day");
                double maxTemp = day.getDouble("maxtemp_c");
                double minTemp = day.getDouble("mintemp_c");
                double avgTemp = day.optDouble("avgtemp_c", maxTemp);
                String conditionText = day.getJSONObject("condition").getString("text");
                // Translate common English condition descriptions to Hebrew
                switch (conditionText.toLowerCase(Locale.ROOT)) {
                    case "clear":
                    case "sunny":
                        conditionText = "בהיר";
                        break;
                    case "partly cloudy":
                    case "partlycloudy":
                        conditionText = "חלקית מעונן";
                        break;
                    case "cloudy":
                        conditionText = "מעונן";
                        break;
                    case "mist":
                        conditionText = "ערפל";
                        break;
                    case "rain":
                        conditionText = "גשם";
                        break;
                    case "snow":
                        conditionText = "שלג";
                        break;
                    default:
                        // leave as is
                        break;
                }
                String iconUrl = "https:" + day.getJSONObject("condition").getString("icon").replace("64x64", "128x128");

                txtDate.setText(formattedDate);
                txtCondition.setText(conditionText);
                txtTemp.setText(Math.round(avgTemp) + "°");
                txtHighLow.setText(Math.round(maxTemp) + "° / " + Math.round(minTemp) + "°");
                // Assuming day object has avghumidity, maxwind_kph, uv
                double humidity = day.optDouble("avghumidity", 0);
                double wind = day.optDouble("maxwind_kph", 0);
                double uv = day.optDouble("uv", 0);
                txtHumidity.setText(Math.round(humidity) + "%");
                txtWind.setText(Math.round(wind) + " קמ\"ש");
                txtUV.setText(String.valueOf(uv));
                Glide.with(this).load(iconUrl).into(imgIcon);
            } catch (Exception e) {
                Log.e(TAG, "Failed to parse day detail: " + e.getMessage());
            }
        }
    }
}
