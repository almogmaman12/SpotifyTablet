package com.almog.spotifytablet;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The individual battery-saver options. Each is its own switch in Settings → Battery; nothing is
 * bundled, so the user picks what to give up. Cached here so hot paths (the lyrics frame loop)
 * never touch SharedPreferences. Call {@link #refresh} whenever the screen comes back.
 */
public final class PowerSettings {
    public static final String PREF_LOW_FPS = "battery_low_fps"; // old on/off switch, still honoured
    public static final String PREF_LYRICS_FPS = "battery_lyrics_fps"; // "24", "30", "45", "60" or "0" (no cap)
    public static final String PREF_NO_BLUR = "battery_no_blur";
    public static final String PREF_SLOW_POLL = "battery_slow_poll";
    public static final String PREF_DIM_CLOCK = "battery_dim_clock";
    public static final String PREF_CLOCK_BRIGHTNESS = "battery_clock_brightness"; // percent

    /** Shortest time between lyrics frames, from the "Lyrics frame rate" choice (0 = no cap). */
    public static volatile long minFrameNanos = 14_000_000L; // 60 fps until refresh() runs
    private static volatile long userMinFrameNanos = 14_000_000L;
    /** 0 = fine, 1 = hot or Battery Saver (30 fps), 2 = very hot (24 fps). Only ever slows lyrics drawing, never changes how it looks. */
    private static volatile int coolDownLevel = 0;
    private static Object thermalListener;
    public static volatile boolean noBackgroundBlur = false;
    public static volatile boolean slowPolling = false;
    public static volatile boolean dimClock = false;
    public static volatile float clockBrightness = 0.10f;

    // Scheduled dimming of the always-on clock: between start and end it uses its own level.
    public static final String PREF_DIM_SCHEDULE = "aod_dim_schedule";
    public static final String PREF_DIM_START = "aod_dim_start"; // minutes after midnight
    public static final String PREF_DIM_END = "aod_dim_end";
    public static final String PREF_DIM_LEVEL = "aod_dim_level"; // percent
    public static final String PREF_DIM_EXTRA = "aod_dim_extra"; // percent of extra darkening below the screen's lowest brightness
    public static volatile boolean dimSchedule = false;
    public static volatile int dimStartMin = 23 * 60, dimEndMin = 7 * 60;
    public static volatile float dimLevel = 0.03f;
    public static volatile float dimExtra = 0f;

    private PowerSettings() {}

    /** The chosen frame rate, or a slower one while the tablet is hot or in Battery Saver. */
    private static void applyFrameCap(Context context) {
        long floor = coolDownLevel >= 2 ? (long) (1_000_000_000d / 24 * 0.85)
                : coolDownLevel == 1 ? (long) (1_000_000_000d / 30 * 0.85) : 0L;
        minFrameNanos = Math.max(userMinFrameNanos, floor);
    }

    /** Starts following the tablet's temperature and Battery Saver. Safe to call again. */
    public static synchronized void startCoolDownWatch(final Context context) {
        final android.os.PowerManager pm = (android.os.PowerManager) context.getApplicationContext()
                .getSystemService(Context.POWER_SERVICE);
        if (pm == null) return;
        final Context app = context.getApplicationContext();
        updateCoolDown(app, pm, pm.getCurrentThermalStatus());
        if (thermalListener != null) return;
        final android.os.PowerManager.OnThermalStatusChangedListener l = status -> updateCoolDown(app, pm, status);
        thermalListener = l;
        try {
            pm.addThermalStatusListener(l);
        } catch (Throwable t) {
            thermalListener = null;
        }
    }

    public static synchronized void stopCoolDownWatch(Context context) {
        if (thermalListener == null) return;
        android.os.PowerManager pm = (android.os.PowerManager) context.getApplicationContext()
                .getSystemService(Context.POWER_SERVICE);
        try {
            if (pm != null) pm.removeThermalStatusListener((android.os.PowerManager.OnThermalStatusChangedListener) thermalListener);
        } catch (Throwable ignored) {
        }
        thermalListener = null;
    }

    private static void updateCoolDown(Context app, android.os.PowerManager pm, int thermal) {
        int level = thermal >= android.os.PowerManager.THERMAL_STATUS_SEVERE ? 2
                : (thermal >= android.os.PowerManager.THERMAL_STATUS_MODERATE || pm.isPowerSaveMode()) ? 1 : 0;
        if (level == coolDownLevel) return;
        coolDownLevel = level;
        applyFrameCap(app);
        DebugLog.i("PowerSettings", "Cool-down level " + level + " (thermal status " + thermal + ")");
    }

    public static void refresh(Context context) {
        SharedPreferences p = context.getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE);
        String fps = p.getString(PREF_LYRICS_FPS, p.getBoolean(PREF_LOW_FPS, false) ? "30" : "60");
        int n;
        try { n = Integer.parseInt(fps); } catch (NumberFormatException e) { n = 60; }
        // A frame is drawn when at least ~85% of the target interval has passed, so a cap of N lands on
        // the nearest rate the screen can show (a 60 Hz panel can't do 45, it does 30 or 60).
        userMinFrameNanos = n <= 0 ? 0L : (long) (1_000_000_000d / n * 0.85);
        applyFrameCap(context);
        noBackgroundBlur = p.getBoolean(PREF_NO_BLUR, false);
        slowPolling = p.getBoolean(PREF_SLOW_POLL, false);
        dimClock = p.getBoolean(PREF_DIM_CLOCK, false);
        dimSchedule = p.getBoolean(PREF_DIM_SCHEDULE, false);
        dimStartMin = p.getInt(PREF_DIM_START, 23 * 60);
        dimEndMin = p.getInt(PREF_DIM_END, 7 * 60);
        dimExtra = Math.max(0, Math.min(90, p.getInt(PREF_DIM_EXTRA, 0))) / 100f;
        dimLevel = Math.max(0.01f, Math.min(1f, p.getInt(PREF_DIM_LEVEL, 3) / 100f));
        clockBrightness = Math.max(0.01f, Math.min(1f, p.getInt(PREF_CLOCK_BRIGHTNESS, 10) / 100f));
    }

    /** Whether the scheduled dim window (which may cross midnight) is on right now. */
    public static boolean scheduleActive() {
        if (!dimSchedule) return false;
        java.util.Calendar c = java.util.Calendar.getInstance();
        int now = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE);
        return dimStartMin <= dimEndMin
                ? now >= dimStartMin && now < dimEndMin
                : now >= dimStartMin || now < dimEndMin;
    }

    /**
     * How much darker than the screen's own lowest brightness the clock is right now (0 to 0.9): a
     * black layer over the clock, for when even brightness 1% is too bright. Only inside the schedule.
     */
    public static float extraDimNow() {
        return scheduleActive() ? dimExtra : 0f;
    }

    /**
     * Brightness for the clock screen right now, or a negative number for "leave it to the system".
     * The schedule wins inside its window; otherwise the plain dim option.
     */
    public static float clockBrightnessNow() {
        if (scheduleActive()) return dimLevel;
        return dimClock ? clockBrightness : -1f;
    }
}
