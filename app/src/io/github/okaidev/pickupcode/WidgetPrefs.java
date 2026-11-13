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

    /** 取件码基准 17sp，副标题 11sp，按档位等比缩放。 */
    // ==================== 外观主题 / 透明度（v4.1.0） ====================

    /** dark / light / system */
    public static String theme(Context ctx) {
        return prefs(ctx).getString(PickupWidgetProvider.KEY_THEME, "dark");
    }

    public static void setTheme(Context ctx, String v) {
        prefs(ctx).edit().putString(PickupWidgetProvider.KEY_THEME, v).apply();
    }

    /**
     * 七档透明度（定稿方案）：实底 92 / 微透 84 / 轻透 76 / 半透 62 /
     * 偏透 48 / 很透 30 / 全透 0，数值是底色的不透明度百分比。
     *
     * <p>档位是固定的七个，不做连续滑杆：连续值要处理「拖到 37 是什么样」，
     * 而用户在壁纸上根本判断不出 37 和 42 的差别，只会困惑。七档每档都有名字。
     */
    public static final int[] ALPHA_STEPS = {92, 84, 76, 62, 48, 30, 0};

    public static final String[] ALPHA_NAMES = {"实底", "微透", "轻透", "半透", "偏透", "很透", "全透"};

    /**
     * 当前透明度百分比。
     *
     * <p>兼容 v4.1.0 存下的 {@code solid/half/clear}：分别对应 92/62/0，
     * 老用户升级后不会突然变成别的样子。
     */
    public static int alphaPct(Context ctx) {
        String v = prefs(ctx).getString(PickupWidgetProvider.KEY_ALPHA, "92");
        if ("solid".equals(v)) return 92;
        if ("half".equals(v)) return 62;
        if ("clear".equals(v)) return 0;
        try {
            int pct = Integer.parseInt(v);
            int best = 0;
            int diff = 999;
            for (int i = 0; i < ALPHA_STEPS.length; i++) {
                int d = Math.abs(ALPHA_STEPS[i] - pct);
                if (d < diff) { diff = d; best = i; }
            }
            return ALPHA_STEPS[best];
        } catch (Throwable t) {
            return 92;
        }
    }

    /** 当前透明度档位下标 0~6（对应 {@link #ALPHA_STEPS}） */
    public static int alphaIndex(Context ctx) {
        int pct = alphaPct(ctx);
        for (int i = 0; i < ALPHA_STEPS.length; i++) {
            if (ALPHA_STEPS[i] == pct) return i;
        }
        return 0;
    }

    public static void setAlphaPct(Context ctx, int pct) {
        prefs(ctx).edit().putString(PickupWidgetProvider.KEY_ALPHA, String.valueOf(pct)).apply();
    }

    /** 旧接口：只用于兼容，内部转成百分比 */
    public static String alpha(Context ctx) {
        return String.valueOf(alphaPct(ctx));
    }

    // ==================== 配色（v4.2） ====================

    /**
     * 文字配色：{@code single} / {@code rainbow} / {@code sunset} / {@code aurora} / {@code sakura}。
     *
     * <p>{@code single} 是单色（深色主题白字、浅色主题墨字），其余四套是彩色主题，
     * 每一行的取件码按色板循环换色，让多行之间能一眼分开。
     */
    public static String palette(Context ctx) {
        String v = prefs(ctx).getString("widget_palette", "single");
        if ("rainbow".equals(v) || "sunset".equals(v) || "aurora".equals(v)
                || "sakura".equals(v) || "custom".equals(v)) {
            return v;
        }
        return "single";
    }

    public static void setPalette(Context ctx, String v) {
        prefs(ctx).edit().putString("widget_palette", v == null ? "single" : v).apply();
    }

    /**
     * 自定义配色的主色。
     *
     * <p>默认给一个紫色，不是白色：白色做主色时「自定义」按钮就是白底白字，
     * 打开配色面板会看到一块空白（真机上出现过）。
     */
    public static int customColor(Context ctx) {
        return prefs(ctx).getInt("widget_custom_color", 0xFF845EF7);
    }

    public static void setCustomColor(Context ctx, int color) {
        prefs(ctx).edit().putInt("widget_custom_color", color).apply();
    }

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
     * <p>v4.0.2 第八轮：常量与布局 v3 对齐（padding 20 / 标题 26 / 行 36）。
     *
     * <p>行高固定、行数按高度取满，剩不到一行的零头留在底部——
     * 绝不把行拉高去填满（用户原话：「明明没几个件要取的，
     * 我把尺寸调大了，你就把它全部填满，这种效果显得太低端了」）。
     *
     * <p><b>为什么只需要一个高度值就够</b>：实测桌面给的高度比真实可用高度
     * 少约 15dp（左右各 7.5dp 的单元格内边距被扣掉了）。按偏小的值算行数
     * 只会少显示一行、留一点空白；算多了则会出现「半个取件码」——
     * 两害相权取其轻，所以这里一律用偏保守的高度。
     *
     * @param heightDp 组件高度（dp）
     * @param widthDp  组件宽度（dp）；窄组件（&lt;150dp）最多 3 行
     * @param maxRows  布局允许的最大行数
     * @return 实际显示的行数
     */
    public static int rowsForHeight(int heightDp, int widthDp, int maxRows) {
        if (heightDp <= 0) return 3;                       // 拿不到尺寸时的保守值
        int avail = heightDp - PAD_DP - HEADER_DP;
        if (avail <= 0) return 1;                          // 极小高度：至少留一行
        int rows = avail / ROW_DP;
        rows = Math.min(rows, Math.max(1, maxRows));
        if (widthDp > 0 && widthDp < 150) rows = Math.min(rows, 3);
        return Math.max(1, rows);
    }

    /** 上下内边距之和（dp）——与布局 v3 的 padding 10dp 对齐 */
    public static final int PAD_DP = 20;
    /** 标题行高度（dp）——与布局 v3 的 minHeight 26dp 对齐 */
    public static final int HEADER_DP = 26;
    /** 单行高度（dp）——固定值，内容多少都不变，与布局 v3 对齐 */
    public static final int ROW_DP = 36;
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
