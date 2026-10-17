package com.almog.spotifytablet;

import android.content.Context;
import android.text.format.DateFormat;

/** The clock's 12h/24h choice (Settings → Display → Clock format). */
public final class ClockFormat {
    public static final String PREF_CLOCK_FORMAT = "clock_format"; // "24" (default), "12" or "auto"

    private ClockFormat() {}

    /** Pattern for the big clock: hours and minutes split by a colon (the split display needs that). */
    public static String pattern(Context context) {
        String mode = context.getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE)
                .getString(PREF_CLOCK_FORMAT, "24");
        boolean twelve = "12".equals(mode) || ("auto".equals(mode) && !DateFormat.is24HourFormat(context));
        return twelve ? "h:mm" : "HH:mm";
    }
}
