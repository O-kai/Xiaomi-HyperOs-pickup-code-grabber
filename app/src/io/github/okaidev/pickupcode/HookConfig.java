package io.github.okaidev.pickupcode;

import android.content.SharedPreferences;

/** Framework-owned preferences, writable by the module app and read-only in targets. */
final class HookConfig {
    static final String GROUP = "pickup_config";
    private static volatile SharedPreferences preferences;
    private static volatile boolean published;
    static void attach(SharedPreferences value) { preferences = value; published = false; }
    static boolean published() { return published; }
    static String snapshot() {
        SharedPreferences p = preferences;
        return p == null ? "" : p.getString("snapshot", "");
    }
    static boolean publish(String snapshot) {
        SharedPreferences p = preferences;
        boolean wasPublished = published;
        boolean result = p != null && p.edit().putString("snapshot", snapshot).commit();
        published = result;
        if (wasPublished != result) FrameworkClient.configurationChanged();
        return result;
    }
}
