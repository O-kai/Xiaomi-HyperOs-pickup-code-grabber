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
    /** v4.0.3 第二轮：正向开关。旧的 hide_code 只用于迁移 */
    private static final String KEY_SHOW_CODE = "show_code";
    @Deprecated
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

    /** 取件码基准 24sp，副标题 11sp，按档位等比缩放。 */
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
     * 地点现在是否真的会显示。
     *
     * <p>地点是取件码的附属信息：码都不显示了，地点无所依附。
     * 所以真正的生效条件是「显示取件码 且 显示取件地点」。
     * 单看 {@link #showPlace} 只代表用户在下级开关上的选择，可能被上级压制。
     */
    public static boolean showPlaceEffective(Context ctx) {
        return showCode(ctx) && showPlace(ctx);
    }

    /**
     * 最多显示条数。
     *
     * <p>v4.0.2：<b>不再作为硬上限</b>。用户原话：
     * 「最多可显示条数不应该有太明显的限制框架，我们只需要根据小组件的实际大小
     * 来展示就可以了。组件够大，展示的内容就多，反正用户会自己调整大小，
     * 他自己能感知得到。」
     *
     * <p>所以现在条数完全由组件高度决定（见 PickupWidgetProvider.build），
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
     * Count complete rows in the Android 9–11 static sample preview. Live widgets use a scrollable list.
     * The renderer supplies a larger row height for increased font preferences.
     */
    public static int rowsForHeight(int heightDp, int widthDp, int maxRows) {
        return rowsForHeight(heightDp, widthDp, maxRows, ROW_DP);
    }

    static int rowsForHeight(int heightDp, int widthDp, int maxRows, int rowDp) {
        if (heightDp <= 0) return Math.min(3, Math.max(1, maxRows));
        int avail = heightDp - PAD_DP - HEADER_DP;
        if (avail <= 0) return 1;                          // 极小高度：至少留一行
        int rows = avail / Math.max(1, rowDp);
        rows = Math.min(rows, Math.max(1, maxRows));
        if (widthDp > 0 && widthDp < 150) rows = Math.min(rows, 3);
        return Math.max(1, rows);
    }

    /** 紧凑布局上下内边距之和（dp）；桌面标准布局为 16dp。 */
    public static final int PAD_DP = 12;
    /** 紧凑布局标题高度（dp）；桌面标准布局为 24dp。 */
    public static final int HEADER_DP = 20;
    /** 紧凑布局单行高度（dp）；桌面标准布局为 48dp。 */
    public static final int ROW_DP = 44;
    /** 兼容旧名（补位动画位移量要用） */
    public static final int ROW_MIN_DP = ROW_DP;

    /**
     * 是否显示取件码（默认显示）。
     *
     * <p>v4.0.3 第二轮：从「隐藏取件码」改成「显示取件码」这个正向开关。
     * 两个理由：
     * <ol>
     *   <li>开关列表里混一个否定式选项，用户每次都要在脑子里取反一次——
     *       「隐藏取件码：关」到底是什么意思？</li>
     *   <li>「显示取件地点」是它的事实下级：码都不显示了，地点无从谈起。
     *       只有把上级做成正向的「显示」，层级关系才说得通。</li>
     * </ol>
     *
     * <p>旧值迁移：老用户存的是 {@code hide_code}，取反一次写进新键。
     * 只在第一次读时写回，之后以 {@code show_code} 为准。
     */
    public static boolean showCode(Context ctx) {
        SharedPreferences sp = prefs(ctx);
        if (sp.contains(KEY_SHOW_CODE)) {
            return sp.getBoolean(KEY_SHOW_CODE, true);
        }
        boolean show = !sp.getBoolean(KEY_HIDE_CODE, false);
        // 迁移完就把旧键删掉：两个语义相反的键同时留在文件里，
        // 以后排查「配置快照」的人还得先猜以哪个为准。
        sp.edit().putBoolean(KEY_SHOW_CODE, show).remove(KEY_HIDE_CODE).apply();
        return show;
    }

    public static void setShowCode(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_SHOW_CODE, on).apply();
    }
}
