package com.almog.spotifytablet;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;

/**
 * In-memory ring buffers behind the debug page: recent log lines, lyric fetches and network calls.
 * Cheap and thread-safe; nothing is written to disk.
 */
public final class DiagLog {
    private DiagLog() {}

    public static final class Entry {
        public final long time; public final char level; public final String tag; public final String msg;
        Entry(char level, String tag, String msg) {
            this.time = System.currentTimeMillis(); this.level = level; this.tag = tag; this.msg = msg;
        }
    }

    public static final class Fetch {
        public final long time; public final String song; public final String result;
        public final long ms; public final String detail;
        Fetch(String song, String result, long ms, String detail) {
            this.time = System.currentTimeMillis(); this.song = song; this.result = result;
            this.ms = ms; this.detail = detail == null ? "" : detail;
        }
    }

    public static final class Net {
        public final long time; public final String host; public final String path;
        public final int code; public final long ms; public final String error;
        Net(String host, String path, int code, long ms, String error) {
            this.time = System.currentTimeMillis(); this.host = host; this.path = path;
            this.code = code; this.ms = ms; this.error = error;
        }
        public boolean failed() { return error != null || code >= 400; }
    }

    private static final int MAX_LOG = 300, MAX_FETCH = 60, MAX_NET = 150;
    private static final ArrayDeque<Entry> LOG = new ArrayDeque<>();
    private static final ArrayDeque<Fetch> FETCHES = new ArrayDeque<>();
    private static final ArrayDeque<Net> NET = new ArrayDeque<>();

    static void log(char level, String tag, String msg) {
        synchronized (LOG) {
            if (LOG.size() >= MAX_LOG) LOG.removeFirst();
            LOG.addLast(new Entry(level, tag, msg));
        }
    }

    /** One finished lyrics lookup: what was asked, which source answered, how long it took. */
    public static void fetch(String song, String result, long ms, String detail) {
        synchronized (FETCHES) {
            if (FETCHES.size() >= MAX_FETCH) FETCHES.removeFirst();
            FETCHES.addLast(new Fetch(song, result, ms, detail));
        }
    }

    public static void net(String host, String path, int code, long ms, String error) {
        synchronized (NET) {
            if (NET.size() >= MAX_NET) NET.removeFirst();
            NET.addLast(new Net(host, path, code, ms, error));
        }
    }

    /** Newest first. */
    public static List<Entry> logs() { synchronized (LOG) { return reversed(LOG); } }
    public static List<Fetch> fetches() { synchronized (FETCHES) { return reversed(FETCHES); } }
    public static List<Net> nets() { synchronized (NET) { return reversed(NET); } }

    public static void clearAll() {
        synchronized (LOG) { LOG.clear(); }
        synchronized (FETCHES) { FETCHES.clear(); }
        synchronized (NET) { NET.clear(); }
    }

    private static <T> List<T> reversed(ArrayDeque<T> q) {
        List<T> out = new ArrayList<>(q);
        java.util.Collections.reverse(out);
        return out;
    }
}
