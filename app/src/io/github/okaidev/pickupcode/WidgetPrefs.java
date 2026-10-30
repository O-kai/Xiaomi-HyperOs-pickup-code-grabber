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

    /** 显示内容：只显示待取件（默认） */
    public static final int SHOW_PENDING = 0;
    /** 显示内容：全部信息（待取在上、已取在下，已取划掉） */
    public static final int SHOW_ALL = 1;

    private static final String PREFS = "pickup_widget";
    private static final String KEY_FONT = "font_scale";
    private static final String KEY_SHOW_PLACE = "show_place";
    private static final String KEY_MAX_ROWS = "max_rows";
    private static final String KEY_HIDE_CODE = "hide_code";
    private static final String KEY_SHOW_MODE = "show_mode";

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

    /**
     * 最多显示条数。
     *
     * <p>v4.0.2：<b>不再作为硬上限</b>。用户原话：
     * 「最多可显示条数不应该有太明显的限制框架，我们只需要根据小组件的实际大小
     * 来展示就可以了。组件够大，展示的内容就多，反正用户会自己调整大小，
     * 他自己能感知得到。」
     *
     * <p>所以现在条数完全由组件高度决定（见 PickupWidgetProvider.rowsFor），
     * 这个值只作为「用户手动设定的上限」，0 表示不限。
     * 保留这个设置是为了兼容旧数据与将来可能的降级需求。
     */
    public static int maxRows(Context ctx) {
        int v = prefs(ctx).getInt(KEY_MAX_ROWS, 0);
        return v <= 0 ? 0 : Math.min(20, v);
    }

    public static void setMaxRows(Context ctx, int rows) {
        prefs(ctx).edit().putInt(KEY_MAX_ROWS, Math.max(0, Math.min(20, rows))).apply();
    }

    /**
     * 显示内容模式。
     *
     * <p>v4.0.2 新增。用户要求：小组件可以选「待取件」（默认）
     * 或「全部信息」——后者把已取件的一起显示，待取在上、已取在下，
     * 已取的划掉，让用户一眼看出哪些取过、哪些还没取。
     */
    public static int showMode(Context ctx) {
        int v = prefs(ctx).getInt(KEY_SHOW_MODE, SHOW_PENDING);
        return v == SHOW_ALL ? SHOW_ALL : SHOW_PENDING;
    }

    public static void setShowMode(Context ctx, int mode) {
        prefs(ctx).edit().putInt(KEY_SHOW_MODE, mode == SHOW_ALL ? SHOW_ALL : SHOW_PENDING).apply();
    }

    /** 是否显示全部信息（含已取件） */
    public static boolean showAll(Context ctx) {
        return showMode(ctx) == SHOW_ALL;
    }

    /**
     * 按组件高度算能显示几行（纯逻辑，可离线单测）。
     *
     * <p>v4.0.2：条数完全由组件的实际尺寸决定，不再有「最多 5 条」这种硬上限——
     * 用户原话「最多可显示条数不应该有太明显的限制框架，
     * 我们只需要根据小组件的实际大小来展示就可以了」。
     *
     * <p>与 {@code PickupWidgetProvider.rowsFor} 用的是同一套算法，
     * 抽到这里是为了能离线跑（Provider 那一层依赖 Android 上下文）。
     *
     * @param heightDp 组件高度（dp）
     * @param widthDp  组件宽度（dp）；窄组件（<150dp）最多 2 行
     * @param maxRows  布局允许的最大行数
     * @return 实际显示的行数
     */
    public static int rowsForHeight(int heightDp, int widthDp, int maxRows) {
        if (heightDp <= 0) return 3;                       // 拿不到尺寸时的保守值
        // 头部标题行约 26dp，底部「查看全部」约 18dp，组件内边距上下共 24dp
        int available = heightDp - 26 - 18 - 24;
        int rows = available / 40;                        // 行高固定 40dp
        if (widthDp > 0 && widthDp < 150) rows = Math.min(rows, 2);
        // 留一行安全余量：桌面给的 MIN_HEIGHT 有时比实际可用高度大，
        // 多算一行会导致最后一行被压扁。
        rows = Math.max(1, rows - 1);
        return Math.max(1, Math.min(maxRows, rows));
    }

    /**
     * 是否隐藏取件码（只显示件数）。
     *
     * <p>设计稿第四节要求提供这个开关：桌面上摆着组件时，别人一瞥就能看到
     * 取件码，等于把凭据摆在明面上。打开后组件只显示「N 个」，
     * 点组件进 App 才看得到码。默认显示（不改变原有行为）。
     */
    public static boolean hideCode(Context ctx) {
        return prefs(ctx).getBoolean(KEY_HIDE_CODE, false);
    }

    public static void setHideCode(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_HIDE_CODE, on).apply();
    }
}
