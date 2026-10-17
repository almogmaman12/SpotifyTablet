package com.almog.spotifytablet;

import android.content.Context;
import android.text.format.DateFormat;

import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.Scope;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

import okhttp3.Request;
import okhttp3.Response;

/**
 * Google Calendar access shared by the always-on clock and Settings. It uses the same Google
 * sign-in and read-only calendar scope as Project Mode, so one sign-in serves both. Everything
 * here blocks: call it off the main thread.
 */
public final class GoogleCalendarClient {
    public static final String SCOPE = "https://www.googleapis.com/auth/calendar.readonly";
    /** Comma-separated calendar ids shown on the clock; empty means all of them. */
    public static final String PREF_CALENDAR_IDS = "aod_calendar_ids";

    private GoogleCalendarClient() {}

    public static final class Cal {
        public final String id, name, summary;
        Cal(String id, String name, String summary) { this.id = id; this.name = name; this.summary = summary; }
    }

    /** One calendar event. [startMs] of an all-day event is local midnight of its date. */
    public static final class Event {
        public final String summary, colorId, calendarSummary;
        public final long startMs;
        public final boolean allDay;
        Event(String summary, String colorId, String calendarSummary, long startMs, boolean allDay) {
            this.summary = summary; this.colorId = colorId; this.calendarSummary = calendarSummary;
            this.startMs = startMs; this.allDay = allDay;
        }
    }

    public static GoogleSignInClient signInClient(Context c) {
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(new Scope(SCOPE))
                .build();
        return GoogleSignIn.getClient(c, gso);
    }

    /** True when a Google account that has granted calendar access is already signed in. */
    public static boolean isConnected(Context c) {
        GoogleSignInAccount a = GoogleSignIn.getLastSignedInAccount(c);
        return a != null && GoogleSignIn.hasPermissions(a, new Scope(SCOPE));
    }

    /** A fresh access token, or null when not signed in / consent is missing. Blocking. */
    public static String token(Context c) {
        GoogleSignInAccount a = GoogleSignIn.getLastSignedInAccount(c);
        if (a == null || a.getAccount() == null || !GoogleSignIn.hasPermissions(a, new Scope(SCOPE))) return null;
        try {
            return GoogleAuthUtil.getToken(c, a.getAccount(), "oauth2:" + SCOPE);
        } catch (Exception e) {
            return null;
        }
    }

    private static String get(String token, String url) {
        Request r = new Request.Builder().url(url).addHeader("Authorization", "Bearer " + token).build();
        try (Response resp = NetworkClient.getInstance().newCall(r).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) return null;
            return resp.body().string();
        } catch (Exception e) {
            return null;
        }
    }

    /** The account's calendars, for the "Choose calendars" list. Empty when not connected. */
    public static List<Cal> listCalendars(Context c) {
        List<Cal> out = new ArrayList<>();
        String token = token(c);
        if (token == null) return out;
        String body = get(token, "https://www.googleapis.com/calendar/v3/users/me/calendarList");
        if (body == null) return out;
        try {
            JSONArray items = new JSONObject(body).getJSONArray("items");
            for (int i = 0; i < items.length(); i++) {
                JSONObject o = items.getJSONObject(i);
                String summary = o.optString("summary", "Unknown");
                out.add(new Cal(o.getString("id"), o.optString("summaryOverride", summary), summary));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /**
     * Upcoming events from [calendarIds] (null or empty = every calendar), starting at [fromMs] and,
     * when [toMs] > 0, ending before it; up to [maxPerCalendar] from each, merged and sorted by start.
     * Blocking. Empty when not connected or on a network error.
     */
    public static List<Event> upcomingEvents(Context c, Set<String> calendarIds, long fromMs, long toMs, int maxPerCalendar) {
        List<Event> out = new ArrayList<>();
        String token = token(c);
        if (token == null) return out;
        SimpleDateFormat utc = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        utc.setTimeZone(TimeZone.getTimeZone("UTC"));
        String range = "&timeMin=" + utc.format(new Date(fromMs)) + (toMs > 0 ? "&timeMax=" + utc.format(new Date(toMs)) : "");
        for (Cal cal : listCalendars(c)) {
            if (calendarIds != null && !calendarIds.isEmpty() && !calendarIds.contains(cal.id)) continue;
            String body = get(token, "https://www.googleapis.com/calendar/v3/calendars/" + android.net.Uri.encode(cal.id)
                    + "/events?singleEvents=true&orderBy=startTime&maxResults=" + maxPerCalendar + range);
            if (body == null) continue;
            try {
                JSONArray items = new JSONObject(body).getJSONArray("items");
                for (int i = 0; i < items.length(); i++) {
                    JSONObject e = items.getJSONObject(i);
                    if ("cancelled".equals(e.optString("status"))) continue;
                    JSONObject start = e.optJSONObject("start");
                    if (start == null) continue;
                    String dateTime = start.optString("dateTime", "");
                    String dateOnly = start.optString("date", "");
                    Date d;
                    boolean allDay = dateTime.isEmpty();
                    if (!allDay) d = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(dateTime);
                    else if (!dateOnly.isEmpty()) d = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dateOnly);
                    else continue;
                    if (d == null) continue;
                    out.add(new Event(e.optString("summary", "Unknown Event"), e.optString("colorId", "NONE"),
                            cal.summary, d.getTime(), allDay));
                }
            } catch (Exception ignored) {
            }
        }
        java.util.Collections.sort(out, (x, y) -> Long.compare(x.startMs, y.startMs));
        return out;
    }

    /** "19:30  Dinner" for the next timed event in the coming 24 hours, or null. */
    public static String nextEventLine(Context c, String timePattern) {
        String csv = c.getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE).getString(PREF_CALENDAR_IDS, "");
        Set<String> wanted = new HashSet<>();
        for (String s : csv.split(",")) if (!s.trim().isEmpty()) wanted.add(s.trim());
        long now = System.currentTimeMillis();
        for (Event e : upcomingEvents(c, wanted, now - 15 * 60_000L, now + 24L * 3600_000L, 10)) {
            if (e.allDay || e.summary.trim().isEmpty() || e.startMs < now - 15 * 60_000L) continue;
            return DateFormat.format(timePattern, new Date(e.startMs)) + "  " + e.summary.trim();
        }
        return null;
    }
}
