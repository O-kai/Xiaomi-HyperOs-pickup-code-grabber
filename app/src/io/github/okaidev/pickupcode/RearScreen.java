package io.github.okaidev.pickupcode;

import android.app.Notification;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.RemoteViews;

import org.json.JSONException;
import org.json.JSONObject;

/** Notification RemoteViews consumed by Xiaomi's rear-screen center; no display takeover. */
final class RearScreen {
    static final String PARAM = "miui.rear.param";
    static final String VIEW = "miui.rear.rv";
    static final String AOD = "miui.rear.rvAOD";

    private RearScreen() { }

    /** A rear display is not a destination for the phone's full parcel list. */
    static PendingIntent openList(Context context) {
        Intent intent = new Intent(context, PickupListActivity.class)
                .setAction("io.github.okaidev.pickupcode.REAR_OPEN_LIST")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        ActivityOptions options = ActivityOptions.makeBasic()
                .setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY);
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            options.setPendingIntentCreatorBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
        }
        return PendingIntent.getActivity(context, 1203, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE, options.toBundle());
    }

    static boolean available(Context context) {
        // Package presence is also useful while the private rear display is powered off.
        try {
            context.getPackageManager().getApplicationInfo("com.xiaomi.subscreencenter", 0);
            return true;
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
            return false;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static void avoidCutout(Context context, RemoteViews views) {
        if (android.os.Build.VERSION.SDK_INT < 31) return;
        float leftDp = 0;
        // Private rear displays may be hidden from this app. The verified 17 Pro Max
        // geometry is 296px at 450dpi; reserve 106 host dp on that device.
        if ("popsicle".equals(android.os.Build.DEVICE)) leftDp = 106;
        try {
            android.hardware.display.DisplayManager manager = context.getSystemService(
                    android.hardware.display.DisplayManager.class);
            if (manager != null) for (android.view.Display display : manager.getDisplays()) {
                if (display.getDisplayId() == android.view.Display.DEFAULT_DISPLAY) continue;
                android.view.DisplayCutout cutout = display.getCutout();
                if (cutout == null) continue;
                android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
                display.getRealMetrics(metrics);
                leftDp = Math.max(leftDp, cutout.getSafeInsetLeft() / metrics.density);
            }
        } catch (RuntimeException ignored) { }
        views.setViewLayoutMargin(R.id.rear_content, RemoteViews.MARGIN_LEFT, leftDp,
                android.util.TypedValue.COMPLEX_UNIT_DIP);
    }

    static Bundle extras(Context context, String code, String place, int count,
                         Notification.Action open, Notification.Action done, boolean alert)
            throws JSONException {
        Bundle result = new Bundle();
        JSONObject options = new JSONObject().put("business", "express").put("index", 0)
                .put("priority", 500).put("disable_popup", !alert)
                .put("show_time_tip", false).put("enableFloat", alert)
                .put("swipe_out_screen_listener", false);
        result.putString(PARAM, new JSONObject().put("rear_param_v1", options).toString());
        RemoteViews card = new RemoteViews(context.getPackageName(), R.layout.rear_pickup_card);
        card.setTextViewText(R.id.rear_count, count + " 件待取");
        card.setTextViewText(R.id.rear_code, code);
        card.setTextViewText(R.id.rear_place, place);
        card.setTextViewText(R.id.rear_open, open.title);
        card.setTextViewText(R.id.rear_done, done.title);
        card.setOnClickPendingIntent(R.id.rear_open, openList(context));
        card.setOnClickPendingIntent(R.id.rear_done, done.actionIntent);
        avoidCutout(context, card);
        result.putParcelable(VIEW, card);
        // Keep the same parcel details visible in AOD, using a black passive layout.
        RemoteViews aod = new RemoteViews(context.getPackageName(), R.layout.rear_pickup_aod);
        aod.setTextViewText(R.id.rear_count, count + " 件待取");
        aod.setTextViewText(R.id.rear_code, code);
        aod.setTextViewText(R.id.rear_place, place);
        avoidCutout(context, aod);
        result.putParcelable(AOD, aod);
        return result;
    }

    static Bundle pendingExtras(Context context, String code, String place, int count,
                                Notification.Action open, Notification.Action done, boolean initial)
            throws JSONException {
        Bundle result = extras(context, code, place, count, open, done, initial);
        JSONObject payload = new JSONObject(result.getString(PARAM));
        // Keep the pending card in the visible group on silent updates as well.
        // disable_popup moves a card into the host's hidden group; it is not a sound control.
        payload.getJSONObject("rear_param_v1").put("disable_popup", false);
        result.putString(PARAM, payload.toString());
        return result;
    }
}
