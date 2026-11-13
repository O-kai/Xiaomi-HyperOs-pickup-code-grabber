package io.github.okaidev.pickupcode;

import android.app.Notification;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Xiaomi's local notification protocol. No push account, network request or private API hook. */
final class HyperIsland {
    static final String PARAM = "miui.focus.param";
    private static final String PIC = "miui.focus.pic_pickup";
    private static final String OPEN = "miui.focus.action_open";
    private static final String DONE = "miui.focus.action_done";

    private HyperIsland() { }

    static int protocol(Context context) {
        // Capability version, not the HyperOS marketing version. OS4+ must not be excluded.
        try {
            return Settings.System.getInt(context.getContentResolver(), "notification_focus_protocol", 0);
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    /** Call off the main thread: SystemUI validates the real caller's UID. */
    static boolean hasPermission(Context context) {
        try {
            Bundle args = new Bundle();
            args.putString("package", context.getPackageName());
            Bundle result = context.getContentResolver().call(
                    Uri.parse("content://miui.statusbar.notification.public"),
                    "canShowFocus", null, args);
            return result != null && result.getBoolean("canShowFocus", false);
        } catch (Exception ignored) {
            return false;
        }
    }

    static Bundle extras(Context context, String code, String place, int count,
                         Notification.Action open, Notification.Action done, boolean alert)
            throws JSONException {
        Bundle extras = new Bundle();
        extras.putString(PARAM, payload(code, place, count, alert).toString());
        Bundle pictures = new Bundle();
        pictures.putParcelable(PIC, Icon.createWithResource(context, R.drawable.pickup_brand));
        extras.putBundle("miui.focus.pics", pictures);
        Bundle actions = new Bundle();
        actions.putParcelable(OPEN, open);
        actions.putParcelable(DONE, done);
        extras.putBundle("miui.focus.actions", actions);
        if (RearScreen.available(context)) {
            extras.putAll(RearScreen.extras(context, code, place, count, open, done, alert));
        }
        return extras;
    }

    static JSONObject payload(String code, String place, int count, boolean alert) throws JSONException {
        String summary = count + " 件待取";
        JSONObject picture = new JSONObject().put("type", 1).put("pic", PIC);
        JSONObject text = new JSONObject().put("frontTitle", "取件码")
                .put("title", code).put("content", summary).put("useHighLight", false);
        JSONObject island = new JSONObject().put("islandProperty", 1)
                .put("islandTimeout", 3600)
                .put("bigIslandArea", new JSONObject()
                        .put("imageTextInfoLeft", new JSONObject().put("type", 1)
                                .put("picInfo", picture).put("textInfo", text))
                        .put("picInfo", picture))
                .put("smallIslandArea", new JSONObject().put("picInfo", picture));
        JSONObject params = new JSONObject().put("protocol", 1).put("business", "express")
                .put("updatable", true).put("reopen", "reopen")
                .put("islandFirstFloat", alert).put("enableFloat", alert)
                .put("filterWhenNoPermission", false)
                // AOD deliberately contains only a count; codes stay in the unlocked view.
                .put("aodTitle", summary).put("aodPic", PIC)
                .put("ticker", summary).put("tickerPic", PIC)
                .put("param_island", island)
                .put("baseInfo", new JSONObject().put("type", 2)
                        .put("title", code).put("content", place)
                        .put("colorTitle", "#315CE8").put("colorTitleDark", "#A9BEFF"))
                .put("hintInfo", new JSONObject().put("type", 1).put("title", summary)
                        .put("content", "取件提醒")
                        .put("actionInfo", new JSONObject().put("action", OPEN)))
                .put("actions", new JSONArray()
                        .put(new JSONObject().put("action", OPEN))
                        .put(new JSONObject().put("action", DONE)));
        return new JSONObject().put("param_v2", params);
    }
}
