package io.github.okaidev.pickupcode;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 桌面小组件的外观设置（4.0 Stage 3.2）。
 *
 * 只放影响 RemoteViews 渲染的少量选项：字号档位、是否显示地点、最多条数。
 * 不做模板占位符——那会把短信正文带进桌面，和本项目“默认不存正文”的取舍冲突。
 */
public final class WidgetPrefs {

    public static final int FONT_SMALL = 0;
    public static final int FONT_NORMAL = 1;
    public static final int FONT_LARGE = 2;

    private static final String PREFS = "pickup_widget";
    private static final String KEY_FONT = "font_scale";
    private static final String KEY_SHOW_PLACE = "show_place";
    private static final String KEY_MAX_ROWS = "max_rows";

    private WidgetPrefs() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static int fontLevel(Context ctx) {
        int v = prefs(ctx).getInt(KEY_FONT, FONT_NORMAL);
        return v < 0 || v > 2 ? FONT_NORMAL : v;
    }

    public static void setFontLevel(Context ctx, int level) {
        prefs(ctx).edit().putInt(KEY_FONT, Math.max(FONT_SMALL, Math.min(FONT_LARGE, level))).apply();
    }

    /** 取件码基准 17sp，副标题 11sp，按档位等比缩放。 */
    public static float fontScale(Context ctx) {
        switch (fontLevel(ctx)) {
            case FONT_SMALL:
                return 0.88f;
            case FONT_LARGE:
                return 1.18f;
            default:
                return 1.0f;
        }
    }

    public static boolean showPlace(Context ctx) {
        return prefs(ctx).getBoolean(KEY_SHOW_PLACE, true);
    }

    public static void setShowPlace(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_SHOW_PLACE, on).apply();
    }

    /** 最多显示条数（高度不够时会再收敛，见 PickupWidgetProvider）。 */
    public static int maxRows(Context ctx) {
        int v = prefs(ctx).getInt(KEY_MAX_ROWS, 5);
        return Math.max(2, Math.min(5, v));
    }

    public static void setMaxRows(Context ctx, int rows) {
        prefs(ctx).edit().putInt(KEY_MAX_ROWS, Math.max(2, Math.min(5, rows))).apply();
    }
}
