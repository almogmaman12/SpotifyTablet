package com.almog.spotifytablet;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What is wrong right now, for the banner: offline, Spotify or Jellyfin unreachable, lyrics missing.
 * Items are keyed, so a repeat report only refreshes it, and a success clears it. Everything runs
 * on the main thread; callers may report from any thread.
 */
public final class AppStatus {
    private AppStatus() {}

    public static final int INFO = 0, WARN = 1, ERROR = 2;

    public interface Listener { void onStatus(String message, int severity); }

    private static final class Item {
        final String message; final int severity; final long seq;
        Runnable expiry;
        Item(String message, int severity, long seq) { this.message = message; this.severity = severity; this.seq = seq; }
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, Item> ITEMS = new LinkedHashMap<>();
    private static Listener listener;
    private static long counter;
    private static Context appContext;
    private static ConnectivityManager.NetworkCallback netCallback;

    public static void init(Context context) {
        if (appContext == null) appContext = context.getApplicationContext();
    }

    /** [ttlMs] 0 keeps it until [clear]. */
    public static void report(String key, String message, int severity, long ttlMs) {
        MAIN.post(() -> {
            Item old = ITEMS.remove(key);
            if (old != null && old.expiry != null) MAIN.removeCallbacks(old.expiry);
            Item item = new Item(message, severity, ++counter);
            if (ttlMs > 0) {
                item.expiry = () -> clearNow(key);
                MAIN.postDelayed(item.expiry, ttlMs);
            }
            ITEMS.put(key, item);
            publish();
        });
    }

    public static void clear(String key) {
        MAIN.post(() -> clearNow(key));
    }

    private static void clearNow(String key) {
        Item old = ITEMS.remove(key);
        if (old != null && old.expiry != null) MAIN.removeCallbacks(old.expiry);
        publish();
    }

    /** Hides what is showing now (tap on the banner). Persistent items return on their next report. */
    public static void dismissCurrent() {
        MAIN.post(() -> {
            String top = topKey();
            if (top != null) clearNow(top);
        });
    }

    public static void setListener(Listener l) {
        MAIN.post(() -> {
            listener = l;
            publish();
        });
    }

    private static String topKey() {
        String best = null;
        Item bestItem = null;
        for (Map.Entry<String, Item> e : ITEMS.entrySet()) {
            Item i = e.getValue();
            if (bestItem == null || i.severity > bestItem.severity
                    || (i.severity == bestItem.severity && i.seq > bestItem.seq)) {
                best = e.getKey();
                bestItem = i;
            }
        }
        return best;
    }

    private static void publish() {
        if (listener == null) return;
        String key = topKey();
        if (key == null) listener.onStatus(null, INFO);
        else listener.onStatus(ITEMS.get(key).message, ITEMS.get(key).severity);
    }

    // ---- network watching -------------------------------------------------

    public static void startNetworkWatch(Context context) {
        init(context);
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null || netCallback != null) return;
        if (cm.getActiveNetwork() == null) report("offline", "Offline: check the Wi-Fi connection", ERROR, 0);
        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) { clear("offline"); }
            @Override public void onLost(Network n) {
                report("offline", "Offline: check the Wi-Fi connection", ERROR, 0);
            }
        };
        try {
            cm.registerDefaultNetworkCallback(netCallback);
        } catch (Exception e) {
            netCallback = null;
        }
    }

    public static void stopNetworkWatch(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null && netCallback != null) {
            try { cm.unregisterNetworkCallback(netCallback); } catch (Exception ignored) {}
        }
        netCallback = null;
    }

    // ---- results of HTTP calls (from DiagInterceptor) ---------------------

    /** Which service a host belongs to, or null if it is not one worth a banner. */
    private static String service(String host) {
        if (host == null) return null;
        if (host.contains("spotify.com")) return "spotify";
        if (appContext == null) return null;
        SharedPreferences p = appContext.getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE);
        String jelly = hostOf(p.getString(Constants.PREF_KEY_JELLYFIN_SERVER_URL, ""));
        if (jelly != null && jelly.equals(host)) return "jellyfin";
        String ha = hostOf(p.getString(Constants.PREF_KEY_HA_URL, ""));
        if (ha != null && ha.equals(host)) return "homeassistant";
        return null;
    }

    private static String hostOf(String url) {
        try {
            return url == null || url.isEmpty() ? null : android.net.Uri.parse(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    private static String serviceName(String service) {
        switch (service) {
            case "spotify": return "Spotify";
            case "jellyfin": return "Jellyfin";
            default: return "Home Assistant";
        }
    }

    static void noteHttp(String host, int code, String error) {
        String service = service(host);
        if (service == null) return;
        String key = "svc:" + service;
        boolean down = error != null || code >= 500 || code == 429;
        if (!down) {
            clear(key);
            return;
        }
        // While offline the offline banner already says it.
        String msg = error != null ? "Can't reach " + serviceName(service)
                : code == 429 ? serviceName(service) + " is limiting requests, slowing down"
                : serviceName(service) + " is having problems (" + code + ")";
        report(key, msg, WARN, 20_000L);
    }
}
