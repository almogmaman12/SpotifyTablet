package com.almog.spotifytablet;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The individual battery-saver options. Each is its own switch in Settings → Battery; nothing is
 * bundled, so the user picks what to give up. Cached here so hot paths (the lyrics frame loop)
 * never touch SharedPreferences. Call {@link #refresh} whenever the screen comes back.
 */
public final class PowerSettings {
    public static final String PREF_LOW_FPS = "battery_low_fps";
    public static final String PREF_NO_BLUR = "battery_no_blur";
    public static final String PREF_SLOW_POLL = "battery_slow_poll";
    public static final String PREF_DIM_CLOCK = "battery_dim_clock";
    public static final String PREF_CLOCK_BRIGHTNESS = "battery_clock_brightness"; // percent

    /** Shortest time between lyrics frames: ~70 fps normally, 30 fps with "Lower animation frame rate". */
    public static volatile long minFrameNanos = 14_000_000L;
    public static volatile boolean noBackgroundBlur = false;
    public static volatile boolean slowPolling = false;
    public static volatile boolean dimClock = false;
    public static volatile float clockBrightness = 0.10f;

    // Scheduled dimming of the always-on clock: between start and end it uses its own level.
    public static final String PREF_DIM_SCHEDULE = "aod_dim_schedule";
    public static final String PREF_DIM_START = "aod_dim_start"; // minutes after midnight
    public static final String PREF_DIM_END = "aod_dim_end";
    public static final String PREF_DIM_LEVEL = "aod_dim_level"; // percent
    public static volatile boolean dimSchedule = false;
    public static volatile int dimStartMin = 23 * 60, dimEndMin = 7 * 60;
    public static volatile float dimLevel = 0.03f;

    private PowerSettings() {}

    public static void refresh(Context context) {
        SharedPreferences p = context.getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE);
        minFrameNanos = p.getBoolean(PREF_LOW_FPS, false) ? 33_000_000L : 14_000_000L;
        noBackgroundBlur = p.getBoolean(PREF_NO_BLUR, false);
        slowPolling = p.getBoolean(PREF_SLOW_POLL, false);
        dimClock = p.getBoolean(PREF_DIM_CLOCK, false);
        dimSchedule = p.getBoolean(PREF_DIM_SCHEDULE, false);
        dimStartMin = p.getInt(PREF_DIM_START, 23 * 60);
        dimEndMin = p.getInt(PREF_DIM_END, 7 * 60);
        dimLevel = Math.max(0.01f, Math.min(1f, p.getInt(PREF_DIM_LEVEL, 3) / 100f));
        clockBrightness = Math.max(0.01f, Math.min(1f, p.getInt(PREF_CLOCK_BRIGHTNESS, 10) / 100f));
    }

    /**
     * Brightness for the clock screen right now, or a negative number for "leave it to the system".
     * The schedule wins inside its window (which may cross midnight); otherwise the plain dim option.
     */
    public static float clockBrightnessNow() {
        if (dimSchedule) {
            java.util.Calendar c = java.util.Calendar.getInstance();
            int now = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE);
            boolean inside = dimStartMin <= dimEndMin
                    ? now >= dimStartMin && now < dimEndMin
                    : now >= dimStartMin || now < dimEndMin;
            if (inside) return dimLevel;
        }
        return dimClock ? clockBrightness : -1f;
    }
}
