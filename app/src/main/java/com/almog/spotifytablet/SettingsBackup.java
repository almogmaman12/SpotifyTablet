package com.almog.spotifytablet;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Settings as a JSON file: the app's preferences plus the lyric-source choices. The Spotify login
 * (tokens) is left out, so a backup never lets someone else use your account. API keys and webhook
 * addresses you typed in are included, so the file should be kept private.
 */
public final class SettingsBackup {
    private SettingsBackup() {}

    private static final String[] FILES = {Constants.PREF_NAME, "lyrics_sources"};
    private static final Set<String> SKIP = new HashSet<>(Arrays.asList(
            Constants.PREF_KEY_ACCESS_TOKEN, Constants.PREF_KEY_REFRESH_TOKEN, Constants.PREF_KEY_EXPIRES_AT));

    public static String export(Context context) throws JSONException {
        JSONObject files = new JSONObject();
        for (String file : FILES) {
            JSONObject out = new JSONObject();
            SharedPreferences prefs = context.getSharedPreferences(file, Context.MODE_PRIVATE);
            for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
                if (SKIP.contains(e.getKey())) continue;
                JSONObject typed = typed(e.getValue());
                if (typed != null) out.put(e.getKey(), typed);
            }
            files.put(file, out);
        }
        JSONObject root = new JSONObject();
        root.put("format", "spotifytablet-settings");
        root.put("version", 1);
        root.put("files", files);
        return root.toString(2);
    }

    /** Writes the settings in [json] over the current ones. Returns how many were applied. */
    public static int restore(Context context, String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        if (!"spotifytablet-settings".equals(root.optString("format"))) {
            throw new JSONException("Not a settings backup file");
        }
        JSONObject files = root.getJSONObject("files");
        int count = 0;
        for (String file : FILES) {
            JSONObject in = files.optJSONObject(file);
            if (in == null) continue;
            SharedPreferences.Editor ed = context.getSharedPreferences(file, Context.MODE_PRIVATE).edit();
            java.util.Iterator<String> keys = in.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (SKIP.contains(key)) continue;
                JSONObject t = in.optJSONObject(key);
                if (t != null && apply(ed, key, t)) count++;
            }
            ed.apply();
        }
        return count;
    }

    private static JSONObject typed(Object v) throws JSONException {
        JSONObject o = new JSONObject();
        if (v instanceof Boolean) o.put("t", "b").put("v", v);
        else if (v instanceof Integer) o.put("t", "i").put("v", v);
        else if (v instanceof Long) o.put("t", "l").put("v", v);
        else if (v instanceof Float) o.put("t", "f").put("v", (double) (Float) v);
        else if (v instanceof String) o.put("t", "s").put("v", v);
        else if (v instanceof Set) {
            JSONArray a = new JSONArray();
            for (Object x : (Set<?>) v) a.put(String.valueOf(x));
            o.put("t", "set").put("v", a);
        } else return null;
        return o;
    }

    private static boolean apply(SharedPreferences.Editor ed, String key, JSONObject t) throws JSONException {
        switch (t.optString("t")) {
            case "b": ed.putBoolean(key, t.getBoolean("v")); return true;
            case "i": ed.putInt(key, t.getInt("v")); return true;
            case "l": ed.putLong(key, t.getLong("v")); return true;
            case "f": ed.putFloat(key, (float) t.getDouble("v")); return true;
            case "s": ed.putString(key, t.getString("v")); return true;
            case "set": {
                JSONArray a = t.getJSONArray("v");
                Set<String> set = new HashSet<>();
                for (int i = 0; i < a.length(); i++) set.add(a.getString(i));
                ed.putStringSet(key, set);
                return true;
            }
            default: return false;
        }
    }
}
