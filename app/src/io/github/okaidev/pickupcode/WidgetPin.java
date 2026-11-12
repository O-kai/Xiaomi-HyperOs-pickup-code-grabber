package io.github.okaidev.pickupcode;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.os.Bundle;
import android.widget.Toast;

/** User-controlled entry points for the native launcher and Xiaomi's widget center. */
public final class WidgetPin {
    private WidgetPin() { }

    public static void prompt(Activity activity) {
        UiKit.dialog(activity, UiKit.palette(activity), R.drawable.ic_home, "添加取件小组件",
                "桌面：长按空白处 → 小部件 → 取件码助手。\n\n"
                        + "小米负一屏：打开小米小部件中心，在详情页选择添加到负一屏。"
                        + "尚未被小米收录的版本可能无法显示，可先使用桌面组件。",
                null,
                UiKit.primary("添加到桌面", () -> request(activity, false)),
                UiKit.ghost("小米负一屏", () -> request(activity, true)));
    }

    static void request(Activity activity, boolean xiaomi) {
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(activity);
            if (!manager.isRequestPinAppWidgetSupported()) {
                Toast.makeText(activity, "请从系统的小部件列表手动添加", Toast.LENGTH_LONG).show();
                return;
            }
            ComponentName provider = new ComponentName(activity,
                    xiaomi ? XiaomiPickupWidgetProvider.class : PickupWidgetProvider.class);
            Bundle extras = new Bundle();
            if (xiaomi) {
                extras.putString("addType", "appWidgetDetail");
                extras.putString("widgetName", provider.flattenToString());
            }
            boolean requested = manager.requestPinAppWidget(provider, extras, null);
            Toast.makeText(activity, requested
                    ? (xiaomi ? "已请求打开小米小部件中心；未收录时请先使用桌面组件"
                              : "已请求添加；若无反应，请从桌面的小部件列表添加")
                    : "系统未接受请求，请从小部件列表手动添加", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(activity, "请从系统的小部件列表手动添加", Toast.LENGTH_LONG).show();
        }
    }
}
