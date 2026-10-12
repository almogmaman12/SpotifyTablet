package com.almog.spotifytablet;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Opens the app after the tablet starts, when "Start when the tablet boots" is on. Android only lets
 * a background app open its own screen with the "display over other apps" permission, which the
 * setting asks for.
 */
public class BootReceiver extends BroadcastReceiver {
    public static final String PREF_START_ON_BOOT = "start_on_boot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        boolean on = context.getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_START_ON_BOOT, false);
        if (!on) return;
        Intent launch = new Intent(context, MainActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(launch);
        } catch (Exception e) {
            DebugLog.e("BootReceiver", "Could not start on boot: " + e.getMessage());
        }
    }
}
