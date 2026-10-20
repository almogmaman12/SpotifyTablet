package com.almog.spotifytablet;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Enumeration;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Now-playing from a browser extension (YouTube, SoundCloud, any page with a playing video or audio).
 * The extension in /browser-extension posts the playing media to this tiny HTTP server every second
 * and picks up play/pause/next/previous/seek commands in the reply. Everything stays on the local network.
 */
public final class BrowserMedia {
    public static final int PORT = 8765;
    public static final String PREF_ENABLED = "browser_media_enabled";
    public static final String PREF_LYRICS = "browser_media_lyrics";
    private static final String PREF_TOKEN = "browser_media_token";
    /** A report older than this means the tab is gone, paused in the background, or the PC is off. */
    private static final long STALE_MS = 8_000L;

    public static final class State {
        public String key = "", title = "", artist = "", album = "", artwork = "", site = "";
        /** The cover as a small data: JPEG sent by the extension, so the tablet does not have to fetch it. */
        public String artworkData = "";
        public int positionMs, durationMs;
        public boolean playing;
        public long receivedAt;
    }

    private static volatile State latest;
    private static final ConcurrentLinkedQueue<JSONObject> commands = new ConcurrentLinkedQueue<>();
    private static volatile ServerSocket server;
    private static volatile Runnable onChange;
    private static volatile String token = "";
    private static volatile Context appContext;

    private BrowserMedia() { }

    public static String token(Context context) {
        SharedPreferences p = context.getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE);
        String t = p.getString(PREF_TOKEN, "");
        if (t.isEmpty()) t = newToken(context);
        return t;
    }

    /** Replaces the token; extensions using the old one stop being accepted. */
    public static String newToken(Context context) {
        String chars = "abcdefghjkmnpqrstuvwxyz23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        context.getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE).edit().putString(PREF_TOKEN, sb.toString()).apply();
        token = sb.toString();
        return token;
    }

    public static boolean isEnabled(Context context) {
        return context.getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE).getBoolean(PREF_ENABLED, false);
    }

    /** "192.168.1.20:8765", or the port alone when no network address is found. */
    public static String address() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress() + ":" + PORT;
                }
            }
        } catch (Exception ignored) { }
        return "port " + PORT;
    }

    /** The newest report, or null when nothing was reported lately. */
    public static State fresh() {
        State s = latest;
        if (s == null || System.currentTimeMillis() - s.receivedAt > STALE_MS) return null;
        return s;
    }

    /** True when the browser is playing right now (used by Auto to prefer it). */
    public static boolean playingNow() {
        State s = fresh();
        return s != null && s.playing;
    }

    public static void sendCommand(String name) {
        try { commands.add(new JSONObject().put("c", name)); } catch (Exception ignored) { }
    }

    public static void sendSeek(int ms) {
        try { commands.add(new JSONObject().put("c", "seek").put("ms", ms)); } catch (Exception ignored) { }
    }

    /** The server runs while the browser extension or the phone control page is switched on. */
    public static boolean serverWanted(Context context) {
        return isEnabled(context) || RemoteControl.isEnabled(context);
    }

    public static synchronized void start(Context context, Runnable changed) {
        appContext = context.getApplicationContext();
        if (changed != null) onChange = changed;
        token = token(context);
        if (server != null) return;
        final ServerSocket ss;
        try {
            ss = new ServerSocket(PORT);
        } catch (IOException e) {
            DebugLog.e("BrowserMedia", "Could not listen on " + PORT + ": " + e.getMessage());
            return;
        }
        server = ss;
        Thread t = new Thread(() -> {
            while (!ss.isClosed()) {
                try {
                    final Socket c = ss.accept();
                    Thread h = new Thread(() -> handle(c), "browser-media-conn");
                    h.setDaemon(true);
                    h.start();
                } catch (IOException e) {
                    break;
                }
            }
        }, "browser-media-server");
        t.setDaemon(true);
        t.start();
    }

    public static synchronized void stop() {
        ServerSocket ss = server;
        server = null;
        latest = null;
        commands.clear();
        if (ss != null) try { ss.close(); } catch (IOException ignored) { }
    }

    // ---------------------------------------------------------------- HTTP

    private static void handle(Socket c) {
        try (Socket sock = c) {
            sock.setSoTimeout(5000);
            InputStream in = new BufferedInputStream(sock.getInputStream());
            String requestLine = readLine(in);
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "";
            String path = parts.length > 1 ? parts[1] : "/";
            int length = 0;
            String sent = "";
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                String name = line.substring(0, colon).trim().toLowerCase();
                String value = line.substring(colon + 1).trim();
                if (name.equals("content-length")) {
                    try { length = Integer.parseInt(value); } catch (NumberFormatException ignored) { }
                } else if (name.equals("x-token")) {
                    sent = value;
                }
            }
            OutputStream out = sock.getOutputStream();
            if (method.equals("OPTIONS")) {
                respond(out, 204, "");
                return;
            }
            String pathOnly = path;
            String query = "";
            int qi = path.indexOf('?');
            if (qi >= 0) {
                pathOnly = path.substring(0, qi);
                query = path.substring(qi + 1);
            }
            Context ctx = appContext;
            if (method.equals("GET") && (pathOnly.equals("/") || pathOnly.equals("/index.html"))) {
                servePage(out, ctx);
                return;
            }
            if (sent.isEmpty()) sent = queryParam(query, "t");
            if (!equalsConstTime(sent, token)) {
                respond(out, 401, "{\"ok\":false,\"error\":\"bad token\"}");
                return;
            }
            if (length > 600_000) {
                respond(out, 413, "{\"ok\":false}");
                return;
            }
            byte[] body = new byte[length];
            int got = 0;
            while (got < length) {
                int n = in.read(body, got, length - got);
                if (n < 0) break;
                got += n;
            }
            if (pathOnly.startsWith("/api/")) {
                if (ctx == null || !RemoteControl.isEnabled(ctx)) {
                    respond(out, 403, "{\"ok\":false,\"error\":\"The phone control page is switched off on the tablet\"}");
                    return;
                }
                RemoteControl.Resp r = RemoteControl.handle(ctx, method, pathOnly, new String(body, 0, got, StandardCharsets.UTF_8));
                respondRaw(out, r.code, r.type, r.data);
                return;
            }
            if (pathOnly.startsWith("/ping")) {
                respond(out, 200, "{\"ok\":true}");
                return;
            }
            if (method.equals("POST") && pathOnly.startsWith("/now")) {
                if (ctx == null || !isEnabled(ctx)) {
                    respond(out, 403, "{\"ok\":false,\"error\":\"The browser extension is switched off on the tablet\"}");
                    return;
                }
                ingest(new String(body, 0, got, StandardCharsets.UTF_8));
                JSONArray cmds = new JSONArray();
                JSONObject cmd;
                while ((cmd = commands.poll()) != null) cmds.put(cmd);
                respond(out, 200, new JSONObject().put("ok", true).put("cmds", cmds).toString());
                return;
            }
            respond(out, 404, "{\"ok\":false}");
        } catch (Exception e) {
            // A dropped connection is routine here.
        }
    }

    private static void ingest(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        State prev = latest;
        if (o.optBoolean("gone", false)) {
            latest = null;
            notifyChanged();
            return;
        }
        State s = new State();
        s.key = o.optString("key", "");
        s.title = o.optString("title", "").trim();
        s.artist = o.optString("artist", "").trim();
        s.album = o.optString("album", "").trim();
        s.artwork = o.optString("artwork", "");
        s.site = o.optString("site", "");
        s.artworkData = o.optString("artworkData", "");
        // The extension sends the picture once in a while; keep it while the same cover is still reported.
        if (s.artworkData.isEmpty() && prev != null && prev.artwork.equals(s.artwork)) s.artworkData = prev.artworkData;
        s.positionMs = (int) Math.max(0, o.optLong("positionMs", 0));
        s.durationMs = (int) Math.max(0, o.optLong("durationMs", 0));
        s.playing = o.optBoolean("playing", false);
        s.receivedAt = System.currentTimeMillis();
        latest = s;
        if (prev == null || !prev.key.equals(s.key) || !prev.artwork.equals(s.artwork)) {
            DebugLog.i("BrowserMedia", "now: " + s.title + " | " + s.artist + " | " + s.site + " | cover=" + (s.artwork.isEmpty() ? "(none)" : s.artwork));
        }
        boolean changed = prev == null || !prev.key.equals(s.key) || prev.playing != s.playing;
        if (!changed && prev != null) {
            long predicted = prev.positionMs + (prev.playing ? s.receivedAt - prev.receivedAt : 0);
            changed = Math.abs(predicted - s.positionMs) > 2500;
        }
        if (changed) notifyChanged();
    }

    private static void notifyChanged() {
        Runnable r = onChange;
        if (r != null) r.run();
    }

    private static boolean equalsConstTime(String a, String b) {
        if (b == null || b.isEmpty()) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') break;
            if (b != '\r') buf.write(b);
            if (buf.size() > 16_384) throw new IOException("line too long");
        }
        if (b < 0 && buf.size() == 0) return null;
        return buf.toString("UTF-8");
    }

    private static String queryParam(String query, String name) {
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).equals(name)) {
                try {
                    return java.net.URLDecoder.decode(part.substring(eq + 1), "UTF-8");
                } catch (Exception e) {
                    return "";
                }
            }
        }
        return "";
    }

    /** The phone control page (assets/remote.html); anyone can load it, the code is asked for inside. */
    private static void servePage(OutputStream out, Context ctx) throws IOException {
        if (ctx == null || !RemoteControl.isEnabled(ctx)) {
            respondRaw(out, 404, "text/plain; charset=utf-8",
                    "The phone control page is switched off. Turn it on in the tablet's Settings.".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try (InputStream in = ctx.getAssets().open("remote.html")) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            respondRaw(out, 200, "text/html; charset=utf-8", buf.toByteArray());
        }
    }

    private static void respond(OutputStream out, int code, String body) throws IOException {
        respondRaw(out, code, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private static void respondRaw(OutputStream out, int code, String type, byte[] data) throws IOException {
        String status = code == 200 ? "OK" : code == 204 ? "No Content" : code == 400 ? "Bad Request" : code == 401 ? "Unauthorized"
                : code == 403 ? "Forbidden" : code == 404 ? "Not Found" : code == 405 ? "Method Not Allowed" : code == 503 ? "Service Unavailable" : "Error";
        String head = "HTTP/1.1 " + code + " " + status + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + data.length + "\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Headers: Content-Type, X-Token\r\n"
                + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
                + "Access-Control-Allow-Private-Network: true\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.flush();
    }

    // ------------------------------------------------------ lyric lookup

    private static final java.util.regex.Pattern NOISE_BRACKETS = java.util.regex.Pattern.compile(
            "\\s*[(\\[][^)\\]]*(official|video|audio|lyric|visuali[sz]er|\\bhd\\b|4k|\\bhq\\b|remaster|\\bmv\\b|m/v|clip)[^)\\]]*[)\\]]",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * Artist and title to look lyrics up with. Plain YouTube videos name the song "Artist - Title" under
     * the channel's name, so that is split; music sites already give the two apart.
     */
    public static String[] lyricsQuery(State s) {
        String title = NOISE_BRACKETS.matcher(s.title).replaceAll("").trim();
        title = title.replaceAll("\\s*\\|.*$", "").trim();
        String artist = s.artist
                .replaceAll("(?i)\\s*-\\s*topic$", "")
                .replaceAll("(?i)\\s*vevo$", "")
                .replaceAll("(?i)\\s*official$", "").trim();
        boolean plainVideo = s.site.equals("www.youtube.com") || s.site.equals("youtube.com")
                || s.site.equals("m.youtube.com") || artist.isEmpty();
        if (plainVideo) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.+?)\\s+[-–—]\\s+(.+)$").matcher(title);
            if (m.find()) {
                artist = m.group(1).trim();
                title = m.group(2).trim();
            }
        }
        return new String[]{artist, title};
    }
}
