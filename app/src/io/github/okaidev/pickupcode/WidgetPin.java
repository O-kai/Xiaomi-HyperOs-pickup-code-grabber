package io.github.okaidev.pickupcode;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.widget.Toast;

/**
 * 桌面小组件的添加入口。
 *
 * 真机结论（K90 Pro Max / HyperOS 4.0）：requestPinAppWidget 返回成功，
 * 但桌面 AddItemActivity 会在 30ms 内自行结束、不显示任何界面，也不会创建实例。
 * 因此这里把「系统直接添加」当作 best-effort，始终同时给出可手动完成的步骤，
 * 避免用户点完没有任何反馈。
 */
public final class WidgetPin {

    private WidgetPin() {
    }

    public static void prompt(Context ctx) {
        String steps = "系统支持时会直接弹出放置界面。\n\n"
                + "若没有任何反应（部分 MIUI / HyperOS 会拦截直接添加），请手动添加：\n"
                + "1. 回到桌面，长按空白处\n"
                + "2. 点「小部件」→「全部应用」\n"
                + "3. 找到「取件码助手」，拖到桌面";
        new android.app.AlertDialog.Builder(ctx)
                .setTitle("添加到桌面")
                .setMessage(steps)
                .setPositiveButton("立即尝试", (d, w) -> tryPin(ctx))
                .setNeutralButton("样式设置", (d, w) -> ctx.startActivity(
                        new android.content.Intent(ctx, WidgetSettingsActivity.class)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)))
                .setNegativeButton("知道了", null)
                .show();
    }

    private static void tryPin(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) {
            Toast.makeText(ctx, "系统版本过低，请长按桌面手动添加", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(ctx);
            ComponentName provider = new ComponentName(ctx, PickupWidgetProvider.class);
            if (!manager.isRequestPinAppWidgetSupported()) {
                Toast.makeText(ctx, "请长按桌面 → 小部件 → 取件码助手", Toast.LENGTH_LONG).show();
                return;
            }
            manager.requestPinAppWidget(provider, null, null);
        } catch (Throwable t) {
            Toast.makeText(ctx, "添加失败，请长按桌面手动添加", Toast.LENGTH_LONG).show();
        }
    }
}
