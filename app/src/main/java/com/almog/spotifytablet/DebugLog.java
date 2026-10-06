package com.almog.spotifytablet;

import android.util.Log;

public final class DebugLog {
    private DebugLog() {}

    // Set to false for production releases
    private static final boolean DEBUG = true;

    public static void d(String tag, String msg) {
        if (!DEBUG) return;
        try {
            Log.d(tag, msg);
        } catch (Throwable ignored) {
            System.out.println("[D/" + tag + "] " + msg);
        }
    }

    public static void e(String tag, String msg) {
        if (!DEBUG) return;
        try {
            Log.e(tag, msg);
        } catch (Throwable ignored) {
            System.err.println("[E/" + tag + "] " + msg);
        }
    }

    public static void w(String tag, String msg) {
        if (!DEBUG) return;
        try {
            Log.w(tag, msg);
        } catch (Throwable ignored) {
            System.out.println("[W/" + tag + "] " + msg);
        }
    }

    public static void i(String tag, String msg) {
        if (!DEBUG) return;
        try {
            Log.i(tag, msg);
        } catch (Throwable ignored) {
            System.out.println("[I/" + tag + "] " + msg);
        }
    }
}

