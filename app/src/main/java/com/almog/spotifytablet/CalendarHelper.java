package com.almog.spotifytablet;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.CalendarContract;
import android.text.format.DateFormat;

import java.util.Date;

/** The next calendar event, for the always-on display. Needs READ_CALENDAR; blocking, call off the main thread. */
public final class CalendarHelper {
    private CalendarHelper() {}

    public static boolean hasPermission(Context c) {
        return c.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED;
    }

    /** "19:30  Dinner" for the next event in the coming 24 hours, or null when there is none. */
    public static String nextEventLine(Context c, String timePattern) {
        if (!hasPermission(c)) return null;
        long now = System.currentTimeMillis();
        android.net.Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(b, now);
        ContentUris.appendId(b, now + 24L * 3600_000L);
        try (Cursor cur = c.getContentResolver().query(b.build(),
                new String[]{CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
                        CalendarContract.Instances.ALL_DAY},
                null, null, CalendarContract.Instances.BEGIN + " ASC")) {
            if (cur == null) return null;
            while (cur.moveToNext()) {
                if (cur.getInt(2) == 1) continue;        // all-day events have no time to show
                long begin = cur.getLong(1);
                if (begin < now - 15 * 60_000L) continue; // already well under way
                String title = cur.getString(0);
                if (title == null || title.trim().isEmpty()) continue;
                return DateFormat.format(timePattern, new Date(begin)) + "  " + title.trim();
            }
        } catch (SecurityException | IllegalArgumentException e) {
            return null;
        }
        return null;
    }
}
