package io.github.okaidev.pickupcode;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Notification selection uses record identities; completing a parcel cannot select a later one. */
final class NotificationSelection {
    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("pickup_notification", Context.MODE_PRIVATE);
    }
    static boolean manual(Context context) { return prefs(context).getBoolean("manual", false); }
    static Set<String> ids(Context context) {
        return new HashSet<>(prefs(context).getStringSet("ids", java.util.Collections.emptySet()));
    }
    static void save(Context context, Set<String> ids) {
        prefs(context).edit().putBoolean("manual", true).putStringSet("ids", new HashSet<>(ids)).apply();
    }
    static void automatic(Context context) { prefs(context).edit().clear().apply(); }
    static List<PickupRecord> filter(Context context, List<PickupRecord> pending) {
        boolean manual = manual(context);
        Set<String> ids = ids(context);
        List<PickupRecord> result = new ArrayList<>();
        for (PickupRecord record : pending) {
            if (record.isPending() && record.isTest == 0
                    && (!manual || ids.contains(Long.toString(record.id)))) result.add(record);
        }
        return result;
    }
}
