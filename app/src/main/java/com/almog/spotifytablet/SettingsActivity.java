package com.almog.spotifytablet;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.Settings;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.switchmaterial.SwitchMaterial;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * Settings: a category list on the left, the open category's rows on the right, and a search box
 * that finds any setting. Rows are plain data (title, subtitle, a view factory), and only the open
 * section's views exist, so the screen opens instantly and stays light.
 */
public class SettingsActivity extends AppCompatActivity {

    // Keys other classes read (kept from the previous settings screen).
    public static final String PREF_HOME_STYLE = "home_screen_style";
    public static final String STYLE_OLD = "old";
    public static final String STYLE_NEW = "new";
    public static final String PREF_KEEP_SCREEN_ON = "keep_screen_on";
    public static final String PREF_DYNAMIC_SPACING = "lyrics_dynamic_spacing";
    public static final String PREF_PAUSE_DOTS = "lyrics_pause_dots_enabled";

    private static final String PREF_LYRICS_ANIMATION = "lyrics_animation_enabled";
    private static final String PREF_RENDERER = "lyrics_renderer";
    private static final String PREF_ROMANIZE = "lyrics_romanize";
    private static final String PREF_TRANSLATE_TARGET = "lyrics_translate_target";
    private static final String PREF_TRANSLATION_MODE = "lyrics_translation_mode";

    private static final int ACCENT = Color.parseColor("#1DB954");
    private static final int TEXT = Color.WHITE;
    private static final int TEXT_DIM = Color.parseColor("#9AA3B2");
    private static final int DIVIDER = Color.parseColor("#1F2430");

    // ---------------------------------------------------------------------
    // Model
    // ---------------------------------------------------------------------

    private static final class Row {
        final String title;
        final String subtitle;
        final Supplier<View> make;

        Row(String title, String subtitle, Supplier<View> make) {
            this.title = title;
            this.subtitle = subtitle == null ? "" : subtitle;
            this.make = make;
        }
    }

    /** A titled card of rows. */
    private static final class Group {
        final String label;
        final List<Row> rows = new ArrayList<>();

        Group(String label) {
            this.label = label;
        }
    }

    private static final class Section {
        final String name;
        final List<Group> groups = new ArrayList<>();

        Section(String name) {
            this.name = name;
        }
    }

    private SharedPreferences prefs;
    private float density;

    private final ActivityResultLauncher<String> exportLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/json"), uri -> {
                if (uri != null) writeBackup(uri);
            });
    private final ActivityResultLauncher<String[]> importLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) readBackup(uri);
            });
    private final List<Section> sections = new ArrayList<>();
    private final List<TextView> navItems = new ArrayList<>();
    private int selected = 0;
    private LinearLayout content;
    private ScrollView contentScroll;
    private TextView tvSectionTitle;

    // ---------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN);
        if (getSupportActionBar() != null) getSupportActionBar().hide();

        setContentView(R.layout.activity_settings);
        prefs = getSharedPreferences(Constants.PREF_NAME, MODE_PRIVATE);
        density = getResources().getDisplayMetrics().density;
        if (savedInstanceState != null) selected = savedInstanceState.getInt("section", 0);

        ImageButton back = findViewById(R.id.btnBack);
        back.setOnClickListener(v -> finish());
        content = findViewById(R.id.content);
        contentScroll = findViewById(R.id.contentScroll);
        tvSectionTitle = findViewById(R.id.tvSectionTitle);

        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            ((TextView) findViewById(R.id.tvVersion)).setText("Version " + info.versionName);
        } catch (Exception ignored) {
        }

        buildSections();
        buildNav();
        if (selected < 0 || selected >= sections.size()) selected = 0;
        showSection(selected);

        EditText search = findViewById(R.id.etSearch);
        search.addTextChangedListener(new SimpleWatcher(text -> {
            String q = text.trim();
            if (q.isEmpty()) showSection(selected);
            else showSearch(q);
        }));
    }

    private final ActivityResultLauncher<Intent> calendarSignIn = registerForActivityResult(
            new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
            result -> {
                toast(GoogleCalendarClient.isConnected(this) ? "Google Calendar connected" : "Google sign-in didn't finish");
                recreate();
            });

    private void connectGoogleCalendar() {
        calendarSignIn.launch(GoogleCalendarClient.signInClient(this).getSignInIntent());
    }

    /** Checklist of the account's calendars; the choice is saved as a list of ids. */
    private void chooseCalendars() {
        if (!GoogleCalendarClient.isConnected(this)) {
            toast("Connect Google Calendar first");
            return;
        }
        new Thread(() -> {
            java.util.List<GoogleCalendarClient.Cal> cals = GoogleCalendarClient.listCalendars(this);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (cals.isEmpty()) {
                    toast("Couldn't load your calendars");
                    return;
                }
                java.util.Set<String> chosen = new java.util.HashSet<>(java.util.Arrays.asList(
                        prefs.getString(GoogleCalendarClient.PREF_CALENDAR_IDS, "").split(",")));
                String[] names = new String[cals.size()];
                boolean[] checked = new boolean[cals.size()];
                for (int i = 0; i < cals.size(); i++) {
                    names[i] = cals.get(i).name;
                    checked[i] = chosen.contains(cals.get(i).id);
                }
                new AlertDialog.Builder(this).setTitle("Calendars on the clock")
                        .setMultiChoiceItems(names, checked, (d, which, on) -> checked[which] = on)
                        .setPositiveButton("Save", (d, w) -> {
                            StringBuilder sb = new StringBuilder();
                            for (int i = 0; i < cals.size(); i++) {
                                if (checked[i]) sb.append(sb.length() > 0 ? "," : "").append(cals.get(i).id);
                            }
                            prefs.edit().putString(GoogleCalendarClient.PREF_CALENDAR_IDS, sb.toString()).apply();
                        })
                        .setNegativeButton("Cancel", null).show();
            });
        }).start();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("section", selected);
    }

    // ---------------------------------------------------------------------
    // Sections (the whole settings catalogue lives here)
    // ---------------------------------------------------------------------

    private void buildSections() {
        // ---- Display ----
        Section display = new Section("Display");
        Group layout = new Group("Layout");
        layout.rows.add(segmentRow("Home layout",
                "Modern Split has the lyrics stage with controls and clock on the left.",
                PREF_HOME_STYLE, STYLE_NEW,
                new String[]{"Modern Split", "Classic"}, new String[]{STYLE_NEW, STYLE_OLD}));
        display.groups.add(layout);
        Group screen = new Group("Screen");
        screen.rows.add(switchRow("Keep screen on",
                "Stops the tablet display from sleeping.", PREF_KEEP_SCREEN_ON, true, on -> {
                    if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }));
        screen.rows.add(sliderRow("Background blur",
                "How strongly the album art is blurred behind the player.",
                10, 190, prefs.getInt(Constants.PREF_KEY_BLUR_INTENSITY, 100),
                v -> String.valueOf(v), v -> prefs.edit().putInt(Constants.PREF_KEY_BLUR_INTENSITY, v).apply()));
        screen.rows.add(segmentRow("Clock format",
                "12-hour or 24-hour time on the clock. \"System\" follows the tablet's own setting.",
                ClockFormat.PREF_CLOCK_FORMAT, "24",
                new String[]{"24-hour", "12-hour", "System"}, new String[]{"24", "12", "auto"}));
        display.groups.add(screen);
        Group aod = new Group("Always-on display");
        aod.rows.add(switchRow("Show song when paused",
                "Always-on clock keeps showing the last song's title.",
                Constants.PREF_KEY_AOD_SHOW_SONG, true, null));
        aod.rows.add(switchRow("Show weather",
                "Temperature and icon next to the greeting on the clock.", "aod_show_weather", true, null));
        aod.rows.add(switchRow("Show quote",
                "The daily quote under the greeting.", "aod_show_quote", true, null));
        aod.rows.add(switchRow("Show Shabbat times",
                "Candle lighting and havdalah times above the clock.", "aod_show_shabbat", true, null));
        aod.rows.add(switchRow("Show next calendar event",
                "Your next event within 24 hours, under the clock. Uses the same Google sign-in as Project Mode.",
                "aod_show_event", false, on -> {
                    if (on && !GoogleCalendarClient.isConnected(this)) connectGoogleCalendar();
                }));
        aod.rows.add(actionRow("Google Calendar",
                GoogleCalendarClient.isConnected(this) ? "Connected." : "Not connected yet.",
                GoogleCalendarClient.isConnected(this) ? "Reconnect" : "Connect", false, this::connectGoogleCalendar));
        aod.rows.add(actionRow("Calendars on the clock",
                "Pick which calendars the next event comes from. None picked means all of them.",
                "Choose", false, this::chooseCalendars));
        display.groups.add(aod);
        Group startup = new Group("Startup");
        startup.rows.add(switchRow("Start when the tablet boots",
                "Opens the app after the tablet restarts. Android asks you to allow \"display over other apps\" for this.",
                BootReceiver.PREF_START_ON_BOOT, false, on -> {
                    if (on && !Settings.canDrawOverlays(this)) {
                        toast("Allow \"display over other apps\" for this app, then come back");
                        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName())));
                    }
                }));
        display.groups.add(startup);
        sections.add(display);

        // ---- Battery ----
        Section battery = new Section("Battery");
        Group saver = new Group("Battery saver (turn on only what you want)");
        saver.rows.add(switchRow("Lower animation frame rate",
                "Lyrics animate at about 30 frames per second instead of up to 70. Motion looks a little less smooth.",
                PowerSettings.PREF_LOW_FPS, false, null));
        saver.rows.add(switchRow("No background blur",
                "Skips the blurred album art behind the player, which costs a lot of power.",
                PowerSettings.PREF_NO_BLUR, false, null));
        saver.rows.add(switchRow("Check for songs less often",
                "Asks Spotify and Jellyfin half as often. The song display can lag a few seconds longer.",
                PowerSettings.PREF_SLOW_POLL, false, null));
        saver.rows.add(switchRow("Dim the clock screen",
                "Lowers the screen brightness while the always-on clock is showing.",
                PowerSettings.PREF_DIM_CLOCK, false, null));
        saver.rows.add(sliderRow("Clock screen brightness",
                "Used when \"Dim the clock screen\" is on.",
                1, 50, prefs.getInt(PowerSettings.PREF_CLOCK_BRIGHTNESS, 10),
                v -> v + " %", v -> prefs.edit().putInt(PowerSettings.PREF_CLOCK_BRIGHTNESS, v).apply()));
        battery.groups.add(saver);
        sections.add(battery);

        // ---- Lyrics ----
        Section lyrics = new Section("Lyrics");
        Group look = new Group("Look");
        look.rows.add(choiceRow("Renderer",
                "Restart the app after changing.",
                PREF_RENDERER, "mobile",
                new String[]{"Spicy Lyrics Mobile", "Spicy Lyrics (web view)", "Original"},
                new String[]{"mobile", "web", "native"}, picked -> recreate()));
        // Only the original renderer has line blur, so the switch only appears when it is selected.
        if ("native".equals(prefs.getString(PREF_RENDERER, "mobile"))) {
            look.rows.add(switchRow("Line blur (original renderer)",
                    "Blurs lines that aren't being sung. Looks nice but uses noticeably more battery.",
                    "lyrics_line_blur", false, null));
        }
        look.rows.add(choiceRow("Colors from the cover",
                "Tints the lyrics area with the album cover's main color (lyrics stay white).",
                "lyrics_cover_colors", "off",
                new String[]{"Off", "Background", "Background + progress bars"},
                new String[]{"off", "background", "both"}));
        look.rows.add(switchRow("Up next",
                "A small strip over the lyrics naming the next song in your Spotify queue (modern layout).",
                "lyrics_up_next", false, null));
        look.rows.add(sliderRow("Translation & romanization size",
                "Size of the smaller lines under each lyric. 100% is the default.",
                50, 150, prefs.getInt(com.almog.spotifytablet.lyrics.mobile.LyricsFonts.PREF_NOTE_SIZE, 100),
                v -> v + " %", v -> prefs.edit().putInt(com.almog.spotifytablet.lyrics.mobile.LyricsFonts.PREF_NOTE_SIZE, v).apply()));
        look.rows.add(choiceRow("Lyrics font",
                "Each font has a size that looks best (shown next to it); picking one sets your Lyrics size to it. Applies to Latin-script lyrics.",
                com.almog.spotifytablet.lyrics.mobile.LyricsFonts.PREF_LYRICS_FONT, "system",
                com.almog.spotifytablet.lyrics.mobile.LyricsFonts.names(),
                com.almog.spotifytablet.lyrics.mobile.LyricsFonts.keys(),
                picked -> {
                    int sp = com.almog.spotifytablet.lyrics.mobile.LyricsFonts.find(picked).getRecommendedSp();
                    prefs.edit().putInt(Constants.PREF_KEY_LYRICS_FONT_SIZE, sp).apply();
                    toast("Lyrics size set to " + sp + " sp for this font");
                    recreate();
                }));
        look.rows.add(sliderRow("Lyrics size",
                "Size of the active line. Bigger reads better from a distance.",
                24, 48, prefs.getInt(Constants.PREF_KEY_LYRICS_FONT_SIZE, 32),
                v -> v + " sp", v -> prefs.edit().putInt(Constants.PREF_KEY_LYRICS_FONT_SIZE, v).apply()));
        look.rows.add(switchRow("Word animations",
                "Smooth word-by-word highlighting and movement.", PREF_LYRICS_ANIMATION, true, null));
        look.rows.add(switchRow("Dynamic line spacing",
                "Keeps multi-line lyrics evenly spaced (original renderer).", PREF_DYNAMIC_SPACING, true, null));
        look.rows.add(switchRow("Interlude dots",
                "Pulsing dots during instrumental gaps (original renderer).", PREF_PAUSE_DOTS, true, null));
        lyrics.groups.add(look);

        Group source = new Group("Sources");
        source.rows.add(sourcesRow());
        lyrics.groups.add(source);

        Group lang = new Group("Translation & romanization");
        lang.rows.add(switchRow("Romanization",
                "Latin letters for Japanese, Chinese, Korean, Cyrillic and Greek lyrics.",
                PREF_ROMANIZE, false, null));
        lang.rows.add(choiceRow("Translate to",
                "Uses Google Translate and sends the lyric text to Google.",
                PREF_TRANSLATE_TARGET, "",
                new String[]{"Off", "English", "Hebrew", "Arabic", "Russian", "Spanish", "French", "German"},
                new String[]{"", "en", "he", "ar", "ru", "es", "fr", "de"}));
        lang.rows.add(segmentRow("Translation style",
                "Show the translation under each line, or replace the original.",
                PREF_TRANSLATION_MODE, "under",
                new String[]{"Under line", "Replace"}, new String[]{"under", "replace"}));
        lyrics.groups.add(lang);
        sections.add(lyrics);

        // ---- Playback ----
        Section playback = new Section("Playback");
        Group src = new Group("Source");
        src.rows.add(segmentRow("Media source",
                "Which player the tablet follows. Auto picks whichever is playing.",
                Constants.PREF_KEY_MEDIA_SOURCE, "auto",
                new String[]{"Auto", "Spotify", "Jellyfin"}, new String[]{"auto", "spotify", "jellyfin"}));
        playback.groups.add(src);
        Group updates = new Group("Updates");
        updates.rows.add(switchRow("Live track updates",
                "Keeps checking what is playing. Turn off to save battery and API quota.",
                Constants.PREF_KEY_SPOTIFY_UPDATES_ENABLED, true, null));
        updates.rows.add(sliderRow("Check every (playing)",
                "How often to check the track while music plays.",
                1, 56, (int) (prefs.getLong(Constants.PREF_KEY_SONG_POLL_INTERVAL, 5000L) / 1000L),
                v -> v + " s", v -> prefs.edit().putLong(Constants.PREF_KEY_SONG_POLL_INTERVAL, v * 1000L).apply()));
        updates.rows.add(sliderRow("Check every (paused)",
                "Slower checks while paused, to save resources.",
                1, 56, (int) (prefs.getLong(Constants.PREF_KEY_PAUSED_POLL_INTERVAL, 15000L) / 1000L),
                v -> v + " s", v -> prefs.edit().putLong(Constants.PREF_KEY_PAUSED_POLL_INTERVAL, v * 1000L).apply()));
        playback.groups.add(updates);
        Group volume = new Group("Volume");
        volume.rows.add(sliderRow("Volume step",
                "How much one button press or hold tick changes the volume.",
                1, 20, prefs.getInt(Constants.PREF_KEY_VOLUME_STEP, Constants.VOLUME_STEP),
                v -> v + "%", v -> prefs.edit().putInt(Constants.PREF_KEY_VOLUME_STEP, v).apply()));
        playback.groups.add(volume);
        sections.add(playback);

        // ---- Jellyfin ----
        Section jelly = new Section("Jellyfin");
        Group js = new Group("Server");
        js.rows.add(textRow("Server URL", "Address of your Jellyfin server.",
                Constants.PREF_KEY_JELLYFIN_SERVER_URL, Constants.DEFAULT_JELLYFIN_URL, "http://192.168.1.100:8096", false));
        js.rows.add(textRow("API key", "Created in Jellyfin under Dashboard, API keys.",
                Constants.PREF_KEY_JELLYFIN_API_KEY, Constants.DEFAULT_JELLYFIN_API_KEY, "API key", true));
        jelly.groups.add(js);
        Group jf = new Group("Which session to follow");
        jf.rows.add(textRow("User", "Leave empty to follow any active user.",
                Constants.PREF_KEY_JELLYFIN_TARGET_USER, Constants.DEFAULT_JELLYFIN_TARGET_USER, "Any user", false));
        jf.rows.add(textRow("Device name", "Leave empty to match any Jellyfin device.",
                Constants.PREF_KEY_JELLYFIN_CLIENT_NAME, "", "Any device", false));
        jelly.groups.add(jf);
        sections.add(jelly);

        // ---- Smart home ----
        Section home = new Section("Smart home");
        Group ha = new Group("Home Assistant");
        ha.rows.add(textRow("Server URL", "Address of Home Assistant.",
                Constants.PREF_KEY_HA_URL, Constants.DEFAULT_HA_URL, "http://homeassistant.local:8123", false));
        ha.rows.add(textRow("Access token", "A long-lived access token from your Home Assistant profile.",
                Constants.PREF_KEY_HA_TOKEN, "", "Token", true));
        ha.rows.add(textRow("Light entities", "Lights the light button controls, separated by commas.",
                Constants.PREF_KEY_LIGHT_ENTITY_ID, "light.lmvg_1, light.lmvg_2", "light.living_room, light.kitchen", false));
        home.groups.add(ha);
        Group wh = new Group("Volume webhooks");
        wh.rows.add(textRow("Volume up webhook", "Called when you raise the volume.",
                Constants.PREF_KEY_VOL_UP_WEBHOOK, "", "https://", false));
        wh.rows.add(textRow("Volume down webhook", "Called when you lower the volume.",
                Constants.PREF_KEY_VOL_DOWN_WEBHOOK, "", "https://", false));
        home.groups.add(wh);
        sections.add(home);

        // ---- Weather ----
        Section weather = new Section("Weather");
        Group wl = new Group("Location");
        wl.rows.add(textRow("Location",
                "A city name like Tel Aviv, or coordinates as lat,lon. Restart the app to apply.",
                Constants.PREF_KEY_WEATHER_CITY, Constants.DEFAULT_WEATHER_CITY, "32.0853,34.7818", false));
        weather.groups.add(wl);
        sections.add(weather);

        // ---- Data & account ----
        Section data = new Section("Data & account");
        Group backup = new Group("Backup");
        backup.rows.add(actionRow("Export settings",
                "Saves your settings and keys to a file. Keep it private. Your Spotify login is not included.",
                "Export", false, () -> exportLauncher.launch("spotifytablet-settings.json")));
        backup.rows.add(actionRow("Import settings",
                "Loads a settings file you exported before. Restart the app afterwards.",
                "Import", false, () -> confirm("Import settings?",
                        "Settings in the file replace the ones you have now.", "Choose file",
                        () -> importLauncher.launch(new String[]{"application/json", "text/plain", "*/*"}))));
        data.groups.add(backup);
        Group diag = new Group("Diagnostics");
        diag.rows.add(actionRow("Debug page",
                "Which lyric source answered and how long it took, network calls and recent errors.",
                "Open", false, () -> startActivity(new Intent(this, DebugActivity.class))));
        data.groups.add(diag);
        Group cache = new Group("Cache");
        cache.rows.add(actionRow("Clear lyrics cache",
                "Deletes saved lyrics and translations. They are fetched again next time a song plays.",
                "Clear", false, () -> confirm("Clear lyrics cache?",
                        "All cached lyrics are deleted and fetched again as songs play.", "Clear", () -> {
                            com.almog.spotifytablet.lyrics.repository.LyricsRepository.clearAllCache();
                            deleteRecursively(new File(getCacheDir(), "lyrics"));
                            deleteRecursively(new File(getCacheDir(), "translations"));
                            toast("Lyrics cache cleared");
                        })));
        data.groups.add(cache);
        Group acct = new Group("Account");
        acct.rows.add(actionRow("Log out of Spotify",
                "Clears the saved login. You sign in again on the next launch.",
                "Log out", false, () -> confirm("Log out of Spotify?",
                        "You will need to sign in again on the next launch.", "Log out", () -> {
                            prefs.edit()
                                    .remove(Constants.PREF_KEY_ACCESS_TOKEN)
                                    .remove(Constants.PREF_KEY_REFRESH_TOKEN)
                                    .remove(Constants.PREF_KEY_EXPIRES_AT)
                                    .apply();
                            toast("Spotify session cleared");
                        })));
        data.groups.add(acct);
        Group reset = new Group("Reset");
        reset.rows.add(actionRow("Reset all settings",
                "Erases every setting, including API keys and tokens you typed in.",
                "Reset", true, () -> confirm("Reset all settings?",
                        "This erases all saved settings, keys and logins.", "Reset", () -> {
                            prefs.edit().clear().apply();
                            toast("All settings reset");
                            finish();
                        })));
        data.groups.add(reset);
        sections.add(data);
    }

    // ---------------------------------------------------------------------
    // Navigation + rendering
    // ---------------------------------------------------------------------

    private void buildNav() {
        LinearLayout nav = findViewById(R.id.navList);
        for (int i = 0; i < sections.size(); i++) {
            final int index = i;
            TextView item = new TextView(this);
            item.setText(sections.get(i).name);
            item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            item.setTextColor(TEXT_DIM);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(16), 0, dp(12), 0);
            item.setClickable(true);
            item.setOnClickListener(v -> {
                selected = index;
                ((EditText) findViewById(R.id.etSearch)).setText("");
                showSection(index);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
            lp.bottomMargin = dp(2);
            nav.addView(item, lp);
            navItems.add(item);
        }
    }

    private void markNav(int index) {
        for (int i = 0; i < navItems.size(); i++) {
            TextView t = navItems.get(i);
            boolean on = i == index;
            t.setBackgroundResource(on ? R.drawable.bg_nav_selected : 0);
            t.setTextColor(on ? TEXT : TEXT_DIM);
            t.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    private void showSection(int index) {
        markNav(index);
        Section s = sections.get(index);
        tvSectionTitle.setText(s.name);
        content.removeAllViews();
        for (Group g : s.groups) {
            addLabel(g.label);
            LinearLayout card = newCard();
            for (int i = 0; i < g.rows.size(); i++) {
                if (i > 0) card.addView(divider());
                card.addView(g.rows.get(i).make.get());
            }
            content.addView(card);
        }
        contentScroll.scrollTo(0, 0);
    }

    private void showSearch(String query) {
        markNav(-1);
        tvSectionTitle.setText("Search");
        content.removeAllViews();
        String q = query.toLowerCase(Locale.ROOT);
        LinearLayout card = newCard();
        int found = 0;
        for (Section s : sections) {
            for (Group g : s.groups) {
                for (Row r : g.rows) {
                    String hay = (r.title + " " + r.subtitle + " " + s.name + " " + g.label).toLowerCase(Locale.ROOT);
                    if (!hay.contains(q)) continue;
                    if (found++ > 0) card.addView(divider());
                    card.addView(r.make.get());
                }
            }
        }
        if (found == 0) {
            TextView none = new TextView(this);
            none.setText("No settings match \"" + query + "\"");
            none.setTextColor(TEXT_DIM);
            none.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            none.setPadding(dp(20), dp(20), dp(20), dp(20));
            card.addView(none);
        }
        content.addView(card);
        contentScroll.scrollTo(0, 0);
    }

    private void addLabel(String label) {
        TextView t = new TextView(this);
        t.setText(label.toUpperCase(Locale.ROOT));
        t.setTextColor(ACCENT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setLetterSpacing(0.08f);
        t.setTypeface(null, Typeface.BOLD);
        t.setPadding(dp(6), dp(20), 0, dp(8));
        content.addView(t);
    }

    private LinearLayout newCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_settings_card);
        return card;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(DIVIDER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.setMarginStart(dp(20));
        v.setLayoutParams(lp);
        return v;
    }

    // ---------------------------------------------------------------------
    // Row builders
    // ---------------------------------------------------------------------

    private LinearLayout rowShell() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(14), dp(20), dp(14));
        row.setMinimumHeight(dp(64));
        return row;
    }

    /** Title over subtitle, taking the free width. */
    private LinearLayout titleBlock(String title, String subtitle) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        block.addView(t);
        if (!subtitle.isEmpty()) {
            TextView s = new TextView(this);
            s.setText(subtitle);
            s.setTextColor(TEXT_DIM);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            s.setPadding(0, dp(2), 0, 0);
            block.addView(s);
        }
        block.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return block;
    }

    private Row switchRow(String title, String sub, String key, boolean def,
                          java.util.function.Consumer<Boolean> extra) {
        return new Row(title, sub, () -> {
            LinearLayout row = rowShell();
            row.addView(titleBlock(title, sub));
            SwitchMaterial sw = new SwitchMaterial(this);
            sw.setChecked(prefs.getBoolean(key, def));
            sw.setOnCheckedChangeListener((b, on) -> {
                prefs.edit().putBoolean(key, on).apply();
                if (extra != null) extra.accept(on);
            });
            row.addView(sw);
            row.setOnClickListener(v -> sw.toggle());
            return row;
        });
    }

    /** Inline pill buttons for two or three short options. */
    private Row segmentRow(String title, String sub, String key, String def,
                           String[] names, String[] values) {
        return new Row(title, sub, () -> {
            LinearLayout row = rowShell();
            row.addView(titleBlock(title, sub));
            LinearLayout pills = new LinearLayout(this);
            pills.setOrientation(LinearLayout.HORIZONTAL);
            final List<TextView> views = new ArrayList<>();
            Runnable paint = () -> {
                String cur = prefs.getString(key, def);
                for (int i = 0; i < views.size(); i++) {
                    boolean on = values[i].equals(cur);
                    views.get(i).setBackgroundResource(on ? R.drawable.bg_pill_selected : R.drawable.bg_pill);
                    views.get(i).setTextColor(on ? Color.BLACK : TEXT);
                }
            };
            for (int i = 0; i < names.length; i++) {
                final int idx = i;
                TextView p = new TextView(this);
                p.setText(names[i]);
                p.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                p.setGravity(Gravity.CENTER);
                p.setPadding(dp(16), dp(9), dp(16), dp(9));
                p.setOnClickListener(v -> {
                    prefs.edit().putString(key, values[idx]).apply();
                    paint.run();
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) lp.setMarginStart(dp(8));
                pills.addView(p, lp);
                views.add(p);
            }
            paint.run();
            row.addView(pills);
            return row;
        });
    }

    /** One row showing the current choice; tapping opens a list. For longer option lists. */
    private Row choiceRow(String title, String sub, String key, String def,
                          String[] names, String[] values) {
        return choiceRow(title, sub, key, def, names, values, null);
    }

    private Row choiceRow(String title, String sub, String key, String def,
                          String[] names, String[] values, java.util.function.Consumer<String> onPicked) {
        return new Row(title, sub, () -> {
            LinearLayout row = rowShell();
            row.addView(titleBlock(title, sub));
            TextView value = new TextView(this);
            value.setTextColor(ACCENT);
            value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            value.setPadding(dp(12), 0, 0, 0);
            Runnable paint = () -> {
                String cur = prefs.getString(key, def);
                int i = java.util.Arrays.asList(values).indexOf(cur);
                value.setText(names[Math.max(0, i)] + "  ›");
            };
            paint.run();
            row.addView(value);
            row.setOnClickListener(v -> {
                int cur = Math.max(0, java.util.Arrays.asList(values).indexOf(prefs.getString(key, def)));
                new AlertDialog.Builder(this).setTitle(title)
                        .setSingleChoiceItems(names, cur, (d, i) -> {
                            prefs.edit().putString(key, values[i]).apply();
                            d.dismiss();
                            paint.run();
                            if (onPicked != null) onPicked.accept(values[i]);
                        }).show();
            });
            return row;
        });
    }

    /** Title and live value on top, slider under; the value is saved when the finger lifts. */
    private Row sliderRow(String title, String sub, int min, int max, int current,
                          IntFunction<String> format, IntConsumer save) {
        return new Row(title, sub, () -> {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(20), dp(14), dp(20), dp(10));
            LinearLayout top = new LinearLayout(this);
            top.setGravity(Gravity.CENTER_VERTICAL);
            top.addView(titleBlock(title, sub));
            TextView value = new TextView(this);
            value.setTextColor(ACCENT);
            value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            value.setTypeface(null, Typeface.BOLD);
            int start = Math.max(min, Math.min(max, current));
            value.setText(format.apply(start));
            top.addView(value);
            row.addView(top);
            SeekBar bar = new SeekBar(this);
            bar.setMax(max - min);
            bar.setProgress(start - min);
            bar.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
            bar.setThumbTintList(android.content.res.ColorStateList.valueOf(ACCENT));
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                    value.setText(format.apply(p + min));
                }
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) {
                    save.accept(s.getProgress() + min);
                }
            });
            row.addView(bar, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(36)));
            return row;
        });
    }

    /** Label over a text field that saves as you type. */
    private Row textRow(String title, String sub, String key, String def, String hint, boolean secret) {
        return new Row(title, sub, () -> {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(20), dp(14), dp(20), dp(16));
            LinearLayout head = titleBlock(title, sub);
            head.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row.addView(head);
            EditText et = new EditText(this);
            et.setBackgroundResource(R.drawable.bg_input);
            et.setTextColor(TEXT);
            et.setHintTextColor(Color.parseColor("#5B6270"));
            et.setHint(hint);
            et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            et.setSingleLine(true);
            et.setPadding(dp(14), 0, dp(14), 0);
            et.setInputType(secret
                    ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                    : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            et.setText(prefs.getString(key, def));
            et.addTextChangedListener(new SimpleWatcher(text ->
                    prefs.edit().putString(key, text.trim()).apply()));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
            lp.topMargin = dp(10);
            row.addView(et, lp);
            return row;
        });
    }

    private Row actionRow(String title, String sub, String button, boolean danger, Runnable action) {
        return new Row(title, sub, () -> {
            LinearLayout row = rowShell();
            row.addView(titleBlock(title, sub));
            TextView b = new TextView(this);
            b.setText(button);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            b.setTextColor(danger ? Color.parseColor("#FF6B6B") : TEXT);
            b.setBackgroundResource(danger ? R.drawable.bg_btn_danger : R.drawable.bg_btn);
            b.setPadding(dp(18), dp(10), dp(18), dp(10));
            b.setOnClickListener(v -> action.run());
            row.addView(b);
            return row;
        });
    }

    /** Which lyric sites are asked, with how many are on. Opens a checklist. */
    private Row sourcesRow() {
        return new Row("Lyric sources",
                "Choose which sites are asked for lyrics (Spicy Lyrics, LRCLIB, AMLL, Unison, Apple, NetEase and more).",
                () -> {
                    com.almog.spotifytablet.lyrics.mobile.MobileLyricsSources.init(this);
                    LinearLayout row = rowShell();
                    row.addView(titleBlock("Lyric sources",
                            "Choose which sites are asked for lyrics (Spicy Lyrics, LRCLIB, AMLL, Unison, Apple, NetEase and more)."));
                    TextView value = new TextView(this);
                    value.setTextColor(ACCENT);
                    value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                    value.setPadding(dp(12), 0, 0, 0);
                    Runnable paint = () -> {
                        int on = 0, total = 0;
                        for (com.almog.spotifytablet.lyrics.mobile.MobileLyricsSources.SourceInfo s
                                : com.almog.spotifytablet.lyrics.mobile.MobileLyricsSources.sources()) {
                            total++;
                            if (s.getEnabled()) on++;
                        }
                        value.setText(on + "/" + total + " on  ›");
                    };
                    paint.run();
                    row.addView(value);
                    row.setOnClickListener(v -> showSourcesDialog(paint));
                    return row;
                });
    }

    private void showSourcesDialog(Runnable done) {
        final List<com.almog.spotifytablet.lyrics.mobile.MobileLyricsSources.SourceInfo> list =
                com.almog.spotifytablet.lyrics.mobile.MobileLyricsSources.sources();
        if (list.isEmpty()) {
            toast("Sources are not ready yet");
            return;
        }
        final String[] names = new String[list.size()];
        final boolean[] checked = new boolean[list.size()];
        for (int i = 0; i < list.size(); i++) {
            names[i] = list.get(i).getName();
            checked[i] = list.get(i).getEnabled();
        }
        new AlertDialog.Builder(this)
                .setTitle("Lyric sources (asked in this order)")
                .setMultiChoiceItems(names, checked, (d, which, on) -> checked[which] = on)
                .setPositiveButton("Save", (d, w) -> {
                    for (int i = 0; i < list.size(); i++) {
                        com.almog.spotifytablet.lyrics.mobile.MobileLyricsSources
                                .setSourceEnabled(list.get(i).getId(), checked[i]);
                    }
                    done.run();
                })
                .setNeutralButton("What they send", (d, w) -> {
                    StringBuilder sb = new StringBuilder();
                    for (com.almog.spotifytablet.lyrics.mobile.MobileLyricsSources.SourceInfo s : list) {
                        if (s.getNote() != null) {
                            sb.append("• ").append(s.getName()).append(": ").append(s.getNote()).append("\n\n");
                        }
                    }
                    new AlertDialog.Builder(this).setTitle("What turning sources on sends")
                            .setMessage(sb.length() == 0
                                    ? "The default sources only receive the song title, artist and length."
                                    : sb.toString())
                            .setPositiveButton("OK", null).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private void writeBackup(Uri uri) {
        try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new java.io.IOException("Could not open the file");
            out.write(SettingsBackup.export(this).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            toast("Settings exported");
        } catch (Exception e) {
            toast("Export failed: " + e.getMessage());
        }
    }

    private void readBackup(Uri uri) {
        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new java.io.IOException("Could not open the file");
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            int count = SettingsBackup.restore(this, buf.toString("UTF-8"));
            toast("Imported " + count + " settings. Restart the app to apply everything.");
            showSection(selected);
        } catch (Exception e) {
            toast("Import failed: " + e.getMessage());
        }
    }

    private void confirm(String title, String message, String yes, Runnable onYes) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(message)
                .setPositiveButton(yes, (d, w) -> onYes.run())
                .setNegativeButton("Cancel", null).show();
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * density);
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteRecursively(c);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /** TextWatcher that only reports the new text. */
    private static final class SimpleWatcher implements TextWatcher {
        private final java.util.function.Consumer<String> onText;

        SimpleWatcher(java.util.function.Consumer<String> onText) {
            this.onText = onText;
        }

        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
        @Override public void afterTextChanged(Editable s) {
            onText.accept(s.toString());
        }
    }
}
