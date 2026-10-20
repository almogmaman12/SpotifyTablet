package com.almog.spotifytablet;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The phone control page: a web page and a small JSON API served by {@link BrowserMedia}'s server
 * (same port, same code). It shows what is playing, controls playback, and edits a hand-picked list
 * of settings (never keys, tokens or addresses). Everything stays on the local network.
 */
public final class RemoteControl {
    public static final String PREF_ENABLED = "remote_control_enabled";

    /** What the main screen provides: its state, and a way to carry out commands and apply settings. */
    public interface Host {
        JSONObject state() throws Exception;
        void command(String cmd, long arg);
        void settingsChanged();
    }

    public static final class Resp {
        final int code;
        final String type;
        final byte[] data;

        Resp(int code, String type, byte[] data) {
            this.code = code;
            this.type = type;
            this.data = data;
        }

        static Resp json(int code, JSONObject o) {
            return new Resp(code, "application/json; charset=utf-8", o.toString().getBytes(StandardCharsets.UTF_8));
        }

        static Resp error(int code, String message) {
            try {
                return json(code, new JSONObject().put("ok", false).put("error", message));
            } catch (Exception e) {
                return new Resp(code, "text/plain", message.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private static volatile Host host;
    private static volatile String cover = "";

    private RemoteControl() { }

    public static void setHost(Host h) { host = h; }

    public static void clearHost(Host h) { if (host == h) host = null; }

    /** The current cover: an http(s) address, or a data: image. */
    public static void setCover(String c) { cover = c == null ? "" : c; }

    public static boolean isEnabled(Context context) {
        return context.getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE).getBoolean(PREF_ENABLED, false);
    }

    // ------------------------------------------------------------ settings list

    private static final class Opt {
        final String value, label;
        Opt(String value, String label) { this.value = value; this.label = label; }
    }

    private static final class Setting {
        final String key, label, help, kind; // kind: bool, choice, int
        final Object def;
        final List<Opt> options = new ArrayList<>();
        int min, max, step = 1;
        String unit = "";

        Setting(String key, String label, String help, String kind, Object def) {
            this.key = key;
            this.label = label;
            this.help = help;
            this.kind = kind;
            this.def = def;
        }

        Setting opt(String value, String text) { options.add(new Opt(value, text)); return this; }

        Setting range(int min, int max, int step, String unit) {
            this.min = min;
            this.max = max;
            this.step = step;
            this.unit = unit;
            return this;
        }
    }

    private static final class Group {
        final String label;
        final List<Setting> items = new ArrayList<>();
        Group(String label) { this.label = label; }
    }

    private static final List<Group> CATALOGUE = new ArrayList<>();

    static {
        Group player = new Group("Player");
        player.items.add(new Setting(Constants.PREF_KEY_MEDIA_SOURCE, "Media source", "Which player the tablet follows.", "choice", "auto")
                .opt("auto", "Auto").opt("spotify", "Spotify").opt("jellyfin", "Jellyfin").opt("browser", "Browser"));
        player.items.add(new Setting("auto_source_order", "Auto priority", "Which player wins in Auto when several are playing.", "choice", "browser,spotify,jellyfin")
                .opt("browser,spotify,jellyfin", "Browser, Spotify, Jellyfin").opt("browser,jellyfin,spotify", "Browser, Jellyfin, Spotify")
                .opt("spotify,browser,jellyfin", "Spotify, Browser, Jellyfin").opt("spotify,jellyfin,browser", "Spotify, Jellyfin, Browser")
                .opt("jellyfin,browser,spotify", "Jellyfin, Browser, Spotify").opt("jellyfin,spotify,browser", "Jellyfin, Spotify, Browser"));
        player.items.add(new Setting(Constants.PREF_KEY_SPOTIFY_UPDATES_ENABLED, "Live track updates", "Keeps checking what is playing.", "bool", true));
        player.items.add(new Setting("auto_revert_minutes", "Return to Auto after being idle", "0 never goes back.", "int", 10).range(0, 120, 1, " min"));
        CATALOGUE.add(player);

        Group display = new Group("Display");
        display.items.add(new Setting(SettingsActivity.PREF_KEEP_SCREEN_ON, "Keep screen on", "", "bool", true));
        display.items.add(new Setting(SettingsActivity.PREF_HOME_STYLE, "Home layout", "", "choice", SettingsActivity.STYLE_NEW)
                .opt(SettingsActivity.STYLE_NEW, "Modern Split").opt(SettingsActivity.STYLE_OLD, "Classic"));
        display.items.add(new Setting(ClockFormat.PREF_CLOCK_FORMAT, "Clock format", "", "choice", "24")
                .opt("24", "24-hour").opt("12", "12-hour").opt("auto", "System"));
        display.items.add(new Setting("screen_orientation", "Screen orientation", "", "choice", "auto")
                .opt("auto", "Auto").opt("landscape", "Landscape").opt("portrait", "Portrait"));
        display.items.add(new Setting("clock_after_pause_s", "Show the clock after playback stops", "", "int", 5).range(1, 120, 1, " s"));
        display.items.add(new Setting("aod_show_weather", "Clock: show weather", "", "bool", true));
        display.items.add(new Setting("aod_show_quote", "Clock: show quote", "", "bool", true));
        display.items.add(new Setting("aod_show_shabbat", "Clock: show Shabbat times", "", "bool", true));
        display.items.add(new Setting("aod_show_event", "Clock: show next calendar event", "Needs Google Calendar connected on the tablet.", "bool", false));
        display.items.add(new Setting(Constants.PREF_KEY_AOD_SHOW_SONG, "Clock: show song when paused", "", "bool", true));
        CATALOGUE.add(display);

        Group battery = new Group("Battery");
        battery.items.add(new Setting(PowerSettings.PREF_LYRICS_FPS, "Lyrics frame rate", "Lower saves battery.", "choice", "60")
                .opt("24", "24 fps").opt("30", "30 fps").opt("45", "45 fps").opt("60", "60 fps").opt("0", "Max"));
        battery.items.add(new Setting(PowerSettings.PREF_NO_BLUR, "No background blur", "", "bool", false));
        battery.items.add(new Setting(PowerSettings.PREF_SLOW_POLL, "Check for songs less often", "", "bool", false));
        battery.items.add(new Setting(PowerSettings.PREF_DIM_CLOCK, "Dim the clock screen", "", "bool", false));
        battery.items.add(new Setting(PowerSettings.PREF_CLOCK_BRIGHTNESS, "Clock screen brightness", "", "int", 10).range(1, 50, 1, " %"));
        CATALOGUE.add(battery);

        Group lyrics = new Group("Lyrics");
        lyrics.items.add(new Setting("lyrics_animation_enabled", "Lyrics animation", "", "bool", true));
        lyrics.items.add(new Setting(Constants.PREF_KEY_LYRICS_FONT_SIZE, "Lyrics size", "", "int", 32).range(24, 48, 1, " sp"));
        lyrics.items.add(new Setting("lyrics_up_next", "Up next strip", "", "bool", false));
        lyrics.items.add(new Setting("lyrics_no_lyrics_cover", "Center the player when there are no lyrics", "", "bool", true));
        lyrics.items.add(new Setting("no_lyrics_wide_cover", "Show wide covers wide (no lyrics)", "", "bool", true));
        lyrics.items.add(new Setting("lyrics_patience", "Lookup patience", "How long to wait for the best lyrics.", "choice", "quick")
                .opt("quick", "Quick").opt("balanced", "Balanced").opt("best", "Best quality"));
        lyrics.items.add(new Setting("lyrics_prefetch_ahead", "Songs to look up ahead", "0 is off.", "int", 3).range(0, 10, 1, ""));
        lyrics.items.add(new Setting("lyrics_offset_ms", "Lyrics timing", "Positive shows lyrics earlier, negative later.", "int", 0).range(-2000, 2000, 50, " ms"));
        CATALOGUE.add(lyrics);
    }

    private static Setting find(String key) {
        for (Group g : CATALOGUE) for (Setting s : g.items) if (s.key.equals(key)) return s;
        return null;
    }

    private static Object read(SharedPreferences p, Setting s) {
        switch (s.kind) {
            case "bool": return p.getBoolean(s.key, (Boolean) s.def);
            case "int": return p.getInt(s.key, (Integer) s.def);
            default: return p.getString(s.key, (String) s.def);
        }
    }

    // ------------------------------------------------------------------ API

    /** Handles /api/... after the code has been checked. */
    public static Resp handle(Context context, String method, String path, String body) {
        Host h = host;
        try {
            switch (path) {
                case "/api/state": {
                    if (h == null) return Resp.json(200, new JSONObject().put("ok", true).put("title", "").put("idle", true));
                    JSONObject st = h.state();
                    String c = cover;
                    if (c.startsWith("data:")) st.put("cover", "/api/cover?v=" + (c.hashCode() & 0x7fffffff));
                    else st.put("cover", c);
                    st.put("ok", true);
                    return Resp.json(200, st);
                }
                case "/api/cover": {
                    String c = cover;
                    int comma = c.indexOf(',');
                    if (!c.startsWith("data:") || comma < 0) return Resp.error(404, "no cover");
                    String type = c.substring(5, c.indexOf(';') > 0 ? c.indexOf(';') : comma);
                    return new Resp(200, type, Base64.decode(c.substring(comma + 1), Base64.DEFAULT));
                }
                case "/api/command": {
                    if (!method.equals("POST")) return Resp.error(405, "POST only");
                    if (h == null) return Resp.error(503, "The tablet's player screen is not open");
                    JSONObject o = new JSONObject(body);
                    String cmd = o.optString("c", "");
                    if (!cmd.matches("playpause|next|previous|seek|volup|voldown")) return Resp.error(400, "unknown command");
                    h.command(cmd, o.optLong("ms", 0));
                    return Resp.json(200, new JSONObject().put("ok", true));
                }
                case "/api/settings": {
                    SharedPreferences p = context.getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE);
                    if (method.equals("GET")) return settingsJson(p);
                    JSONObject o = new JSONObject(body);
                    Setting s = find(o.optString("key", ""));
                    if (s == null) return Resp.error(400, "unknown setting");
                    SharedPreferences.Editor e = p.edit();
                    switch (s.kind) {
                        case "bool": e.putBoolean(s.key, o.getBoolean("value")); break;
                        case "int": {
                            int v = o.getInt("value");
                            e.putInt(s.key, Math.max(s.min, Math.min(s.max, v)));
                            break;
                        }
                        default: {
                            String v = o.getString("value");
                            boolean ok = false;
                            for (Opt opt : s.options) if (opt.value.equals(v)) ok = true;
                            if (!ok) return Resp.error(400, "not an allowed value");
                            e.putString(s.key, v);
                        }
                    }
                    e.apply();
                    if (h != null) h.settingsChanged();
                    return Resp.json(200, new JSONObject().put("ok", true));
                }
                default:
                    return Resp.error(404, "not found");
            }
        } catch (Exception e) {
            return Resp.error(400, "bad request");
        }
    }

    private static Resp settingsJson(SharedPreferences p) throws Exception {
        JSONArray groups = new JSONArray();
        for (Group g : CATALOGUE) {
            JSONArray items = new JSONArray();
            for (Setting s : g.items) {
                JSONObject o = new JSONObject()
                        .put("key", s.key).put("label", s.label).put("help", s.help)
                        .put("kind", s.kind).put("value", read(p, s));
                if (s.kind.equals("choice")) {
                    JSONArray opts = new JSONArray();
                    for (Opt opt : s.options) opts.put(new JSONObject().put("v", opt.value).put("l", opt.label));
                    o.put("options", opts);
                } else if (s.kind.equals("int")) {
                    o.put("min", s.min).put("max", s.max).put("step", s.step).put("unit", s.unit);
                }
                items.put(o);
            }
            groups.put(new JSONObject().put("label", g.label).put("items", items));
        }
        return Resp.json(200, new JSONObject().put("ok", true).put("groups", groups));
    }
}
