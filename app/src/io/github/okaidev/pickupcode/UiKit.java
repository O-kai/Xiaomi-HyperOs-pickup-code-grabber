package io.github.okaidev.pickupcode;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * UI 工具集（v4.0.0 视觉重构）
 *
 * 与原型 prototype/index.html 一一对应：
 *  - 四套配色（米金 / 雾霾蓝 / 青瓷绿 / 藕荷玫瑰），默认雾霾蓝，可在「设置 → 外观」切换
 *  - 组件：分组小标题 / 白卡 / 行（图标 + 标题 + 说明 + 右侧配件）/ 米金开关 / 徽标 / 按钮 / 透明磨砂底栏
 *  - 图标统一走 res/drawable/ic_*.xml（矢量，运行时用 setColorFilter 着色）
 */
public final class UiKit {

    private UiKit() { }

    // ================================================================
    //  配色
    // ================================================================

    /** 一套配色：与原型 CSS 变量一一对应 */
    public static final class Palette {
        public final String id, name;
        public final int bg, card, cardSoft, track,
                hero, heroWarn, heroInk, heroSubInk, heroWarnInk, heroWarnSubInk,
                accent, accentDeep, accentInk, accentSoft, accentTint,
                label, line, muteBg, muteInk, navInk, chipBg;
        /**
         * 压在强调色底上的文字/图标颜色。
         *
         * <p>为什么需要它：强调色在深色模式下会被提亮（要在深底上醒目），
         * 这时白字压在亮强调色上的对比度会掉到 1.9–2.9，远低于可读下限。
         * 反过来浅色模式下的米金强调色，白字对比度也只有 3.58。
         * 所以「压在强调色上的字是什么色」不能写死，由底色反推。
         */
        public final int onAccent;

        Palette(String id, String name, String bg, String cardSoft, String track,
                String hero, String heroWarn, String heroInk, String heroSubInk,
                String heroWarnInk, String heroWarnSubInk,
                String accent, String accentDeep, String accentInk, String accentSoft, String accentTint,
                String label, String muteBg, String muteInk, String navInk, String chipBg, String line) {
            this.id = id; this.name = name;
            this.bg = c(bg); this.card = 0xFFFFFFFF; this.cardSoft = c(cardSoft); this.track = c(track);
            this.hero = c(hero); this.heroWarn = c(heroWarn);
            this.heroInk = c(heroInk); this.heroSubInk = c(heroSubInk);
            this.heroWarnInk = c(heroWarnInk); this.heroWarnSubInk = c(heroWarnSubInk);
            this.accent = c(accent); this.accentDeep = c(accentDeep); this.accentInk = c(accentInk);
            this.accentSoft = c(accentSoft); this.accentTint = c(accentTint);
            this.label = c(label); this.muteBg = c(muteBg); this.muteInk = c(muteInk);
            this.navInk = c(navInk); this.chipBg = c(chipBg); this.line = c(line);
            this.onAccent = inkOn(this.accent);
        }

        /** 深色变体的构造：直接给 ARGB 值（由 HSL 推导而来） */
        Palette(String id, String name, int bg, int card, int cardSoft, int track,
                int hero, int heroWarn, int heroInk, int heroSubInk,
                int heroWarnInk, int heroWarnSubInk,
                int accent, int accentDeep, int accentInk, int accentSoft, int accentTint,
                int label, int muteBg, int muteInk, int navInk, int chipBg, int line) {
            this.id = id; this.name = name;
            this.bg = bg; this.card = card; this.cardSoft = cardSoft; this.track = track;
            this.hero = hero; this.heroWarn = heroWarn;
            this.heroInk = heroInk; this.heroSubInk = heroSubInk;
            this.heroWarnInk = heroWarnInk; this.heroWarnSubInk = heroWarnSubInk;
            this.accent = accent; this.accentDeep = accentDeep; this.accentInk = accentInk;
            this.accentSoft = accentSoft; this.accentTint = accentTint;
            this.label = label; this.muteBg = muteBg; this.muteInk = muteInk;
            this.navInk = navInk; this.chipBg = chipBg; this.line = line;
            this.onAccent = inkOn(this.accent);
        }

        /**
         * 深色变体：按同一套强调色的色相推导一整套夜间配色。
         *
         * <p>不做「反色」而是按角色重新给亮度：
         * 页面底最暗、卡片略亮、分割线再亮一点；文字统一提亮；
         * 强调色提高明度，保证在深底上仍然醒目。
         *
         * <p>注意：强调色提亮之后，白字压上去的对比度会掉到 2–3，
         * 所以「压在强调色上的字」由 {@link #onAccent} 按底色自动反推，
         * 这里不再假设它一定是白色。
         */
        Palette toDark() {
            PaletteMath.Dark d = PaletteMath.darkOf(accent);
            return new Palette(id, name,
                    d.bg, d.card, d.cardSoft, d.track,
                    d.hero, d.heroWarn, d.heroInk, d.heroSubInk,
                    d.heroWarnInk, d.heroWarnSubInk,
                    d.accent, d.accentDeep, d.accentInk, d.accentSoft, d.accentTint,
                    d.label, d.muteBg, d.muteInk, d.navInk, d.chipBg, d.line);
        }
    }

    private static int c(String hex) { return Color.parseColor(hex); }

    /**
     * 压在任意底色上的「字色」：白或深墨，取对比度更高的一方。
     *
     * <p>这是 4.0 补上的可访问性兜底：强调色会随主题变亮变暗，
     * 写死白字就会在某些配色下掉到 2:1 上下——按钮看着有颜色，字却看不清。
     * 这里按 WCAG 相对亮度算，取能达到的最高对比度。
     */
    public static final int DARK_INK = PaletteMath.DARK_INK;

    public static int inkOn(int bgColor) {
        return PaletteMath.inkOn(bgColor);
    }

    /** A · 米金 */
    public static final Palette SAND = new Palette("sand", "米金",
            "#FBEDE1", "#FCF7F1", "#F2E7DB",
            "#F9DDB8", "#F5C6AB", "#33240E", "#6E5B41", "#6B2E1A", "#8A5741",
            PaletteMath.ACCENT_SAND, "#8A6A2F", "#7A5A22", "#F6E8CE", "#FBEFDD",
            "#8B7358", "#F1ECE5", "#928B82", "#3A2E1F", "#F7EFE4", "#F1EBE3");

    /** B · 雾霾蓝（默认，与原型当前设计一致） */
    public static final Palette MIST = new Palette("mist", "雾霾蓝",
            "#F2F3F5", "#F6F9FC", "#E6ECF3",
            "#D6E4F2", "#F2D6D2", "#1F3450", "#4A5F7A", "#6B2E1A", "#7A4A48",
            PaletteMath.ACCENT_MIST, "#3D5C8A", "#2F4E7A", "#E2EAF4", "#F2F6FB",
            "#6B7C92", "#EAEEF3", "#868F9B", "#26303E", "#EAF0F7", "#E8EDF4");

    /** C · 青瓷绿 */
    public static final Palette TEAL = new Palette("teal", "青瓷绿",
            "#E9F2EE", "#F5FAF8", "#E2EDE9",
            "#CFE8DE", "#F3DDD0", "#133F35", "#3E6B5E", "#6B2E1A", "#7A5A48",
            PaletteMath.ACCENT_TEAL, "#256356", "#1F5648", "#DDF0E9", "#F1F8F5",
            "#5F7E75", "#E7EFEB", "#83938D", "#21372F", "#EAF4F0", "#E5EFEA");

    /** D · 藕荷玫瑰 */
    public static final Palette ROSE = new Palette("rose", "藕荷玫瑰",
            "#F7EDF1", "#FCF7F9", "#F0E4E9",
            "#F2D8E0", "#F6CBBE", "#4A2130", "#6E4A58", "#6B2E1A", "#8A4A58",
            PaletteMath.ACCENT_ROSE, "#8A4A63", "#7A4159", "#F3DFE7", "#FAF0F4",
            "#8A6E7A", "#F0E7EB", "#97878E", "#372530", "#F8EDF1", "#F1E6EA");

    public static final Palette[] ALL = { MIST, SAND, TEAL, ROSE };

    // 语义色（四套配色通用，与原型一致）
    //
    // v4.0：改为可变字段并由 applyTheme() 按主题改写。
    // 之前是 static final 常量，编译器会把值直接内联到调用处，
    // 深色模式就没法在运行期换色了。
    public static int INK = 0xFF191919, INK2 = 0xFF3D3A36, INK3 = 0xFF8E8E8E;
    public static int OK = 0xFF3E8E5E, OK_SOFT = 0xFFEAF3EC;
    public static int WARN = 0xFFC97F2E, WARN_SOFT = 0xFFFBEEDC;
    public static int DANGER = 0xFFC2543C, DANGER_SOFT = 0xFFFBE7E1;

    private static final String PREFS = "dedup";
    private static final String KEY_PALETTE = "ui_palette";
    private static final String KEY_BLUR = "ui_nav_blur";

    public static Palette palette(Context ctx) {
        String id = prefs(ctx).getString(KEY_PALETTE, MIST.id);
        Palette base = MIST;
        for (Palette p : ALL) if (p.id.equals(id)) { base = p; break; }
        return isDark(ctx) ? darkOf(base) : base;
    }

    /** 深色变体缓存（每套配色算一次即可） */
    private static final java.util.Map<String, Palette> DARK_CACHE = new java.util.HashMap<>();

    public static synchronized Palette darkOf(Palette p) {
        Palette d = DARK_CACHE.get(p.id);
        if (d == null) {
            d = p.toDark();
            DARK_CACHE.put(p.id, d);
        }
        return d;
    }

    // ================================================================
    //  主题（浅色 / 深色）
    // ================================================================

    /** 主题模式：跟随系统 / 强制浅色 / 强制深色 */
    public static final int THEME_AUTO = 0;
    public static final int THEME_LIGHT = 1;
    public static final int THEME_DARK = 2;

    private static final String KEY_THEME = "ui_theme";

    public static int themeMode(Context ctx) {
        return prefs(ctx).getInt(KEY_THEME, THEME_AUTO);
    }

    public static void setThemeMode(Context ctx, int mode) {
        prefs(ctx).edit().putInt(KEY_THEME, mode).apply();
    }

    public static String themeModeLabel(Context ctx) {
        switch (themeMode(ctx)) {
            case THEME_LIGHT: return "浅色";
            case THEME_DARK: return "深色";
            default: return "跟随系统";
        }
    }

    /** 当前是否深色：用户显式选择优先；跟随系统时读系统夜间模式 */
    public static boolean isDark(Context ctx) {
        int mode = themeMode(ctx);
        if (mode == THEME_LIGHT) return false;
        if (mode == THEME_DARK) return true;
        int ui = ctx.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return ui == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * 应用主题。
     *
     * <p>必须在 super.onCreate() 之前调用：setTheme 决定窗口底色，
     * 语义色决定所有文字颜色，晚一步就会先闪一下浅色底。
     */
    public static void applyTheme(Activity a) {
        boolean dark = isDark(a);
        a.setTheme(dark ? R.style.AppTheme_NoActionBar_Dark : R.style.AppTheme_NoActionBar);
        if (dark) {
            INK = 0xFFEDEDED; INK2 = 0xFFC6C6CE; INK3 = 0xFF8B8B95;
            OK = 0xFF5FBF87; OK_SOFT = 0xFF1C2D24;
            WARN = 0xFFE2A851; WARN_SOFT = 0xFF332819;
            DANGER = 0xFFE87B63; DANGER_SOFT = 0xFF33201C;
        } else {
            INK = 0xFF191919; INK2 = 0xFF3D3A36; INK3 = 0xFF8E8E8E;
            OK = 0xFF3E8E5E; OK_SOFT = 0xFFEAF3EC;
            WARN = 0xFFC97F2E; WARN_SOFT = 0xFFFBEEDC;
            DANGER = 0xFFC2543C; DANGER_SOFT = 0xFFFBE7E1;
        }
        applySystemBars(a);
    }

    /** 状态栏：底色跟随主题，图标明暗跟随主题 */
    public static void applySystemBars(Activity a) {
        try {
            Window w = a.getWindow();
            if (w == null) return;
            w.setStatusBarColor(palette(a).bg);
            View d = w.getDecorView();
            int flags = d.getSystemUiVisibility() & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (!isDark(a)) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            d.setSystemUiVisibility(flags);
        } catch (Throwable ignored) { }
    }

    // ================================================================
    //  颜色工具（HSL 推导）
    // ================================================================

    /** HSL -> ARGB（实现在 PureColor，便于离线单测） */
    static int hsl2(float h, float s, float l) {
        return PureColor.hslToArgb(h, s, l);
    }

    /** ARGB -> {h, s, l}（实现在 PureColor，便于离线单测） */
    static float[] rgbToHsl(int color) {
        return PureColor.argbToHsl(color);
    }

    public static void setPalette(Context ctx, Palette p) {
        prefs(ctx).edit().putString(KEY_PALETTE, p.id).apply();
    }

    /** 底栏磨砂（半透明）开关：关掉则用接近纯白的实底，保证任何机型都清晰可读 */
    public static boolean navBlur(Context ctx) {
        return prefs(ctx).getBoolean(KEY_BLUR, true);
    }

    public static void setNavBlur(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_BLUR, on).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ================================================================
    //  基础度量与背景
    // ================================================================

    public static int dp(Context ctx, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics());
    }

    /** 圆角实底 */
    public static GradientDrawable bg(int color, Context ctx, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(color);
        g.setCornerRadius(dp(ctx, radiusDp));
        return g;
    }

    /** 圆角底 + 描边 */
    public static GradientDrawable bg(int color, Context ctx, float radiusDp, int strokeColor, float strokeDp) {
        GradientDrawable g = bg(color, ctx, radiusDp);
        g.setStroke(dp(ctx, strokeDp), strokeColor);
        return g;
    }

    public static TextView text(Context ctx, String s, float sp, int color) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    /** 矢量图标（默认 22dp，运行时着色） */
    public static ImageView icon(Context ctx, int res, float dp, int tint) {
        ImageView iv = new ImageView(ctx);
        iv.setImageResource(res);
        iv.setColorFilter(tint, PorterDuff.Mode.SRC_IN);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(ctx, dp), dp(ctx, dp));
        iv.setLayoutParams(lp);
        return iv;
    }

    /**
     * 功能行左侧的「图标砖」：主题色圆角底块 + 主题深色图标。
     *
     * <p>v4.0.3 引入。此前功能行的图标是 23dp 的单色线框图标（黑白简笔画），
     * 整屏看上去很单调——用户原话「目前这个图标有点太简约了，是黑白样式或者
     * 简单的简笔画状态」。
     *
     * <p>现在每个图标坐在一块主题色的浅色圆角底上：
     * <ul>
     *   <li>底色用 {@code accentSoft}——每套配色里都是「带自己色相的浅底」，
     *       换成雾霾蓝/青瓷绿/藕荷玫瑰，图标砖整体跟着换色，
     *       这就是「图标跟着外观配色走」的落地方式；</li>
     *   <li>图形用 {@code accentInk}（主题深色），压在浅底上对比度足够，
     *       深色模式下底色变深、图形变亮，同样成立；</li>
     *   <li>底块统一 34dp、圆角 10dp、图形统一 18dp——所有行严格对齐，
     *       形成一套统一的派系规格。</li>
     * </ul>
     *
     * @return 底块容器（外层用 LinearLayout.LayoutParams 挂到行里）
     */
    public static View iconTile(Context ctx, int res, Palette p) {
        android.widget.FrameLayout tile = new android.widget.FrameLayout(ctx);
        // 底色：主题色的一点点透明度叠在 accentSoft 上，形成左上深、右下浅的斜向渐变。
        // 单用 accentSoft（如雾霾蓝的 #E2EAF4）几乎与白卡片融在一起，看不出「有配色」；
        // 叠上 22% 的主题色之后，色相明确，图标砖一眼就能和主题对应上。
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{ alpha(p.accent, 0.34f), p.accentSoft });
        bg.setCornerRadius(dp(ctx, 10));
        tile.setBackground(bg);
        tile.setLayoutParams(new LinearLayout.LayoutParams(dp(ctx, 34), dp(ctx, 34)));
        ImageView iv = icon(ctx, res, 18, p.accentInk);
        tile.addView(iv, new android.widget.FrameLayout.LayoutParams(
                dp(ctx, 18), dp(ctx, 18), Gravity.CENTER));
        return tile;
    }

    public static View spacer(Context ctx, float dp) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, dp)));
        return v;
    }

    public static int alpha(int color, float a) {
        return Color.argb((int) (255 * a), Color.red(color), Color.green(color), Color.blue(color));
    }

    // ================================================================
    //  组件
    // ================================================================

    /** 分组小标题（卡片之外的暖灰小字） */
    public static TextView groupLabel(Context ctx, Palette p, String s) {
        TextView t = text(ctx, s, 13.5f, p.label);
        t.setPadding(dp(ctx, 6), dp(ctx, 18), dp(ctx, 6), dp(ctx, 9));
        return t;
    }

    /** 白卡：圆角 18dp + 轻微高度阴影 + 上下 3dp 内边距（行自带间距） */
    public static LinearLayout card(Context ctx, Palette p) {
        LinearLayout c = new LinearLayout(ctx);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = bg(p.card, ctx, 18);
        c.setBackground(g);
        c.setPadding(dp(ctx, 16), dp(ctx, 3), dp(ctx, 16), dp(ctx, 3));
        c.setElevation(dp(ctx, 1.5f));
        c.setClipToOutline(true);
        return c;
    }

    /** 卡片间距：调用方在 addView 前设置 */
    public static LinearLayout.LayoutParams cardLp(Context ctx, float topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(ctx, topDp), 0, 0);
        return lp;
    }

    /** 行内分隔线（卡内相邻行之间） */
    public static View divider(Context ctx, Palette p) {
        View v = new View(ctx);
        v.setBackgroundColor(p.line);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(ctx, 0.6f)));
        v.setLayoutParams(lp);
        return v;
    }

    /**
     * 改一行里的副标题文字。
     *
     * <p>为什么需要这个 helper：行里的结构是「图标 + 标题/副标题列 + 右侧配件」，
     * 副标题在第二层容器里，调用方拿不到它的引用，只能靠猜 child 下标——
     * 一旦行里加了图标，下标就全错位了。这里按结构找（找到第一个含
     * 「标题 + 副标题」两个 TextView 的容器），加不加图标都对。
     */
    public static boolean setRowSub(LinearLayout row, String text) {
        if (row == null || text == null) return false;
        for (int i = 0; i < row.getChildCount(); i++) {
            View ch = row.getChildAt(i);
            if (!(ch instanceof LinearLayout)) continue;
            LinearLayout col = (LinearLayout) ch;
            if (col.getChildCount() < 2) continue;
            View first = col.getChildAt(0);
            View second = col.getChildAt(1);
            if (!(first instanceof TextView) || !(second instanceof TextView)) continue;
            ((TextView) second).setText(text);
            return true;
        }
        return false;
    }

    /**
     * 一行：左侧图标（可选）+ 标题/说明 + 右侧配件
     * accessory 为 null 时右侧放一个右箭头（表示可点进二级页）
     */
    public static LinearLayout row(Context ctx, Palette p, int iconRes, String title, String sub, View accessory) {
        return row(ctx, p, iconRes, title, (CharSequence) sub, accessory);
    }

    /**
     * v6.1：副标题支持 CharSequence 的重载。
     * 用于副标题里嵌 ClickableSpan（例：设置页「通知取件提取」的「多平台」可点开支持列表），
     * 同时保留 String 版本的既有调用点不变。
     */
    public static LinearLayout row(Context ctx, Palette p, int iconRes, String title,
                                   CharSequence sub, View accessory) {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        // v4.0.3：图标从 23dp 单色线框换成 34dp 的主题色图标砖，
        // 行高由图标撑起来了，padding 相应收一点，整屏的呼吸感不变。
        r.setPadding(0, dp(ctx, 11), 0, dp(ctx, 11));

        if (iconRes != 0) {
            View tile = iconTile(ctx, iconRes, p);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(ctx, 34), dp(ctx, 34));
            lp.setMargins(0, 0, dp(ctx, 12), 0);
            tile.setLayoutParams(lp);
            r.addView(tile);
        }

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(ctx, title, 15.5f, INK);
        col.addView(t);
        if (sub != null && sub.length() > 0) {
            // text(...) 只接受 String，这里直接 setText 以保留 CharSequence 上的 Span
            TextView s = new TextView(ctx);
            s.setText(sub);
            s.setTextSize(12.5f);
            s.setTextColor(INK3);
            s.setLineSpacing(dp(ctx, 1), 1.0f);
            if (sub instanceof android.text.Spanned
                    && ((android.text.Spanned) sub).getSpans(0, sub.length(),
                        android.text.style.ClickableSpan.class).length > 0) {
                s.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
                s.setHighlightColor(android.graphics.Color.TRANSPARENT);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(ctx, 3), 0, 0);
            s.setLayoutParams(lp);
            col.addView(s);
        }
        r.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (accessory != null) {
            LinearLayout acc = new LinearLayout(ctx);
            acc.setOrientation(LinearLayout.HORIZONTAL);
            acc.setGravity(Gravity.CENTER_VERTICAL);
            // 与左侧文字留 8dp：大字体下副标题会换行，第一行会长到贴住开关/箭头，
            // 顶在一起看着像被压住了。留一点空隙，文字才读得舒服。
            LinearLayout.LayoutParams accLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            accLp.setMargins(dp(ctx, 8), 0, 0, 0);
            acc.setLayoutParams(accLp);
            acc.addView(accessory);
            r.addView(acc);
        }
        return r;
    }

    /** 右箭头配件 */
    public static ImageView chevron(Context ctx, Palette p) {
        return icon(ctx, R.drawable.ic_chev, 17, p.muteInk);
    }

    /** 右侧文字值（下拉/切换类行的当前值） */
    public static TextView value(Context ctx, Palette p, String s) {
        TextView t = text(ctx, s, 14f, p.accentInk);
        t.setSingleLine(true);
        return t;
    }

    /** 徽标 */
    public static TextView badge(Context ctx, String s, int bgColor, int fgColor) {
        TextView t = text(ctx, s, 11.5f, fgColor);
        t.setTypeface(null, Typeface.BOLD);
        t.setBackground(bg(bgColor, ctx, 999));
        t.setPadding(dp(ctx, 10), dp(ctx, 4), dp(ctx, 10), dp(ctx, 4));
        return t;
    }

    public static TextView badgeOk(Context ctx, Palette p, String s) { return badge(ctx, s, OK_SOFT, OK); }
    public static TextView badgeMute(Context ctx, Palette p, String s) { return badge(ctx, s, p.muteBg, p.muteInk); }
    public static TextView badgeAccent(Context ctx, Palette p, String s) { return badge(ctx, s, p.accentSoft, p.accentInk); }
    public static TextView badgeWarn(Context ctx, String s) { return badge(ctx, s, WARN_SOFT, WARN); }

    /** 按钮：primary（强调色实心）/ ok / warn / ghost（白底强调字）/ ghostLine（描边） */
    public static TextView button(Context ctx, Palette p, String s, String kind) {
        TextView b = text(ctx, s, 15f, 0xFFFFFFFF);
        b.setGravity(Gravity.CENTER);
        b.setTypeface(null, Typeface.BOLD);
        float h = 48;
        switch (kind) {
            case "ok":
                b.setTextColor(inkOn(OK));
                b.setBackground(bg(OK, ctx, 15));
                break;
            case "warn":
                b.setTextColor(inkOn(WARN));
                b.setBackground(bg(WARN, ctx, 15));
                break;
            case "ghost":
                b.setTextColor(p.accentInk);
                b.setBackground(bg(p.card, ctx, 15));
                break;
            case "ghostLine":
                b.setTextColor(p.accentInk);
                b.setBackground(bg(0x00000000, ctx, 15, p.accent, 1.4f));
                break;
            default:
                b.setTextColor(p.onAccent);
                b.setBackground(bg(p.accent, ctx, 15));
        }
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, h)));
        return b;
    }

    // ================================================================
    //  开关（52×31dp 胶囊，强调色轨道）
    // ================================================================

    public interface OnToggle { void onChanged(boolean checked); }

    public static class Switch extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF track = new RectF();
        private final Palette palette;
        private boolean checked;
        private float anim;              // 0=关 1=开（用于旋钮动画）
        private OnToggle listener;
        private ValueAnimator va;

        public Switch(Context ctx, Palette p, boolean checked) {
            super(ctx);
            this.palette = p;
            this.checked = checked;
            this.anim = checked ? 1f : 0f;
            setClickable(true);
            setFocusable(true);
            // 旋钮阴影需要软件层；必须在构造期设置——onDraw 里调用会触发反复重绘
            setLayerType(LAYER_TYPE_SOFTWARE, null);
        }

        public boolean isChecked() { return checked; }

        public void setChecked(boolean v) { setChecked(v, true); }

        public void setChecked(boolean v, boolean notify) {
            if (checked == v) { invalidate(); return; }
            checked = v;
            if (va != null) va.cancel();
            va = ValueAnimator.ofFloat(anim, v ? 1f : 0f);
            va.setDuration(160);
            va.addUpdateListener(a -> { anim = (float) a.getAnimatedValue(); invalidate(); });
            va.start();
            sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
            if (notify && listener != null) listener.onChanged(v);
        }

        public void setOnToggle(OnToggle l) { listener = l; }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(
                    resolveSizeAndState(Math.max(getSuggestedMinimumWidth(), UiKit.dp(getContext(), 52)), w, 0),
                    resolveSizeAndState(Math.max(getSuggestedMinimumHeight(), UiKit.dp(getContext(), 31)), h, 0));
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            track.set(0, 0, w, h);
            boolean dark = UiKit.isDark(getContext());
            paint.setColor(checked ? palette.accent : (dark ? palette.track : 0xFFE5DDD3));
            cv.drawRoundRect(track, h / 2, h / 2, paint);
            float r = h / 2 - UiKit.dp(getContext(), 2);
            float cx = UiKit.dp(getContext(), 2) + r + anim * (w - 2 * UiKit.dp(getContext(), 2) - 2 * r);
            paint.setColor(dark ? 0xFFF2F3F5 : 0xFFFFFFFF);
            paint.setShadowLayer(UiKit.dp(getContext(), 1.5f), 0, UiKit.dp(getContext(), 0.6f), 0x33000000);
            cv.drawCircle(cx, h / 2, r, paint);
            paint.clearShadowLayer();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (!isEnabled()) return false;
            // View handles touch slop, pressed/cancel states and keyboard/touch click dispatch.
            // An ACTION_MOVE inside touch slop is still a tap; do not reject every MOVE.
            return super.onTouchEvent(e);
        }

        @Override
        public boolean performClick() {
            // Accessibility ACTION_CLICK, keyboard and row clicks use the same entry point.
            // Toggling here (not also on ACTION_UP) guarantees exactly one change per click.
            if (!isEnabled() || !isClickable()) return false;
            setChecked(!checked);
            super.performClick();
            return true;
        }

        @Override
        public CharSequence getAccessibilityClassName() {
            return "android.widget.Switch";
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName(getAccessibilityClassName());
            info.setCheckable(true);
            info.setChecked(checked);
        }

        @Override
        public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
            super.onInitializeAccessibilityEvent(event);
            event.setClassName(getAccessibilityClassName());
            event.setChecked(checked);
        }
    }

    /** Whole-row touch target, with a single labelled/checkable accessibility node. */
    static void bindSwitchRow(View row, Switch sw, CharSequence label) {
        row.setOnClickListener(v -> sw.performClick());
        row.setFocusable(false);
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        sw.setContentDescription(label);
    }

    // ================================================================
    //  底栏（透明磨砂胶囊 + 强调色选中块）
    // ================================================================

    public interface OnTab { void onTab(int index); }

    private static final int[] TAB_ICONS = {
            R.drawable.ic_home, R.drawable.ic_grid, R.drawable.ic_sparkle, R.drawable.ic_gear };
    private static final String[] TAB_LABELS = { "首页", "通知提取", "高级规则", "设置" };

    /**
     * 悬浮胶囊底栏：4 个 Tab，选中项为强调色圆角块 + 白色图标。
     * 「模糊效果」= 85% 不透明的半透明白（Android 无法对背后内容做 backdrop blur，以半透明近似）；
     * 关闭时为纯实底，保证任何机型下都清晰可读。
     */
    public static LinearLayout tabbar(Context ctx, Palette p, boolean blur, int active, OnTab cb) {
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(UiKit.dp(ctx, 12), 0, UiKit.dp(ctx, 12), 0);
        // v4.0.6：默认 85% 不透明度（真机反馈 42% 太透、95% 太实，取中间值）：
        // 底下内容能被隐约看到，但不会像 42% 那样让卡片文字与底栏文字撞在一起
        // 底色用 p.card：浅色模式下 p.card 就是纯白，与原设计完全一致；
        // 深色模式下则是深色卡面，不会再出现「浅底 + 深色文字」的错配。
        int fill = blur ? alpha(p.card, 0.85f) : p.card;
        bar.setBackground(bg(fill, ctx, 36, alpha(p.card, blur ? 0.6f : 0.95f), 1f));
        bar.setElevation(UiKit.dp(ctx, 8));

        for (int i = 0; i < TAB_ICONS.length; i++) {
            final int idx = i;
            boolean on = i == active;

            LinearLayout item = new LinearLayout(ctx);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setPadding(0, UiKit.dp(ctx, 6), 0, UiKit.dp(ctx, 6));

            LinearLayout chip = new LinearLayout(ctx);
            chip.setGravity(Gravity.CENTER);
            if (on) {
                chip.setBackground(bg(p.accent, ctx, 12));
            }
            ImageView ic = icon(ctx, TAB_ICONS[i], on ? 21 : 23, on ? p.onAccent : p.navInk);
            chip.addView(ic);
            LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                    UiKit.dp(ctx, 34), UiKit.dp(ctx, 34));
            item.addView(chip, chipLp);

            TextView label = text(ctx, TAB_LABELS[i], 11f, on ? p.navInk : alpha(p.navInk, 0.72f));
            if (on) label.setTypeface(null, Typeface.BOLD);
            LinearLayout.LayoutParams lblLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lblLp.setMargins(0, UiKit.dp(ctx, 3), 0, 0);
            item.addView(label, lblLp);

            item.setOnClickListener(v -> cb.onTab(idx));
            bar.addView(item, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        return bar;
    }

    /** 底栏在页面里的位置：左右 12dp、距底 29dp、高 72dp（与原型一致） */
    public static LinearLayout.LayoutParams tabbarLp(Context ctx) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 72));
        lp.setMargins(dp(ctx, 12), 0, dp(ctx, 12), dp(ctx, 29));
        return lp;
    }

    /** 带矢量图标的按钮（图标与文字同色，自动着色） */
    public static TextView buttonIcon(Context ctx, Palette p, int iconRes, String label, String kind) {
        TextView b = button(ctx, p, label, kind);
        // 图标跟随按钮实际文字色——不同底色下可读性不同，不能统一写死白色。
        int tint = b.getCurrentTextColor();
        Drawable d = ctx.getDrawable(iconRes);
        if (d != null) {
            d = d.mutate();
            d.setTint(tint);
            d.setBounds(0, 0, dp(ctx, 19), dp(ctx, 19));
            b.setCompoundDrawables(d, null, null, null);
            b.setCompoundDrawablePadding(dp(ctx, 8));
        }
        return b;
    }

    // ================================================================
    //  统一弹窗套件（圆角卡片 + 图标标题 + 矢量图标按钮，与主界面同一套配色）
    // ================================================================

    /** 弹窗里的一个动作按钮 */
    public static final class Action {
        final String label;
        final boolean primary;
        final boolean autoDismiss;
        final Runnable run;

        public Action(String label, boolean primary, Runnable run) {
            this(label, primary, true, run);
        }

        public Action(String label, boolean primary, boolean autoDismiss, Runnable run) {
            this.label = label;
            this.primary = primary;
            this.autoDismiss = autoDismiss;
            this.run = run;
        }
    }

    public static Action primary(String label, Runnable run) { return new Action(label, true, run); }
    public static Action ghost(String label, Runnable run) { return new Action(label, false, run); }
    /** 点击后不自动关闭（校验失败时要保留用户输入；成功后自行 dismiss） */
    public static Action keepOpen(String label, Runnable run) {
        return new Action(label, true, false, run);
    }

    /** 基础弹窗：iconRes 为 0 表示不显示图标；message 为 null 时用 contentView 作为正文 */
    public static Dialog dialog(Activity a, Palette p, int iconRes, String title, String message,
                                View contentView, Action... actions) {
        return dialog(a, p, iconRes, title, message, contentView, 0, actions);
    }

    /**
     * 长内容弹窗：正文区限制为屏幕高度的 contentPct%（内容再长也能滚动查看）；
     * contentPct 为 0 表示按内容自适应。
     */
    public static Dialog dialog(Activity a, Palette p, int iconRes, String title, String message,
                                View contentView, int contentPct, Action... actions) {
        Dialog d = new Dialog(a);
        LinearLayout wrap = new LinearLayout(a);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(a, 22), dp(a, 10), dp(a, 22), dp(a, 10));

        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(bg(p.card, a, 22));
        card.setElevation(dp(a, 6));
        card.setPadding(dp(a, 20), dp(a, 18), dp(a, 20), dp(a, 16));

        // 标题行（图标 + 标题）
        if (title != null || iconRes != 0) {
            LinearLayout head = new LinearLayout(a);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            if (iconRes != 0) {
                ImageView iv = icon(a, iconRes, 22, p.accentInk);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(a, 22), dp(a, 22));
                lp.setMargins(0, 0, dp(a, 10), 0);
                iv.setLayoutParams(lp);
                head.addView(iv);
            }
            TextView t = text(a, title == null ? "" : title, 17f, INK);
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            head.addView(t);
            card.addView(head);
        }

        // 正文
        // v4.5：原来 message 与 contentView 是 else-if 关系——只要传了 message，
        // contentView 就会被静默丢弃（自定义来源弹窗的输入框因此不显示）。
        // 改为两者都可以同时存在：先文字说明，再自定义内容。
        if (message != null && !message.isEmpty()) {
            TextView m = text(a, message, 13.5f, INK2);
            m.setLineSpacing(dp(a, 4), 1.0f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(a, 12), 0, 0);
            card.addView(m, lp);
        }
        if (contentView != null) {
            ScrollView sc = new ScrollView(a);
            sc.setClipToPadding(false);
            // v4.6：contentView 可能已经被 addView 到别的容器（调用方常先塞进一个
            // LinearLayout 再传进来）。若已有父容器，必须先 detach，
            // 否则 ScrollView.addView 会抛
            // IllegalStateException: The specified child already has a parent
            // ——这正是「用户自定义规则 → 编辑」闪退的根因。
            if (contentView.getParent() instanceof ViewGroup) {
                ((ViewGroup) contentView.getParent()).removeView(contentView);
            }
            sc.addView(contentView);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(a, 12), 0, 0);
            if (contentPct > 0) {
                lp.height = a.getResources().getDisplayMetrics().heightPixels * contentPct / 100;
            }
            sc.setLayoutParams(lp);
            card.addView(sc);
        }

        // 动作按钮行
        if (actions != null && actions.length > 0) {
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.setMargins(0, dp(a, 18), 0, dp(a, 2));
            row.setLayoutParams(rlp);
            for (Action act : actions) {
                TextView btn = text(a, act.label, 14f, act.primary ? p.onAccent : p.accentInk);
                btn.setGravity(Gravity.CENTER);
                btn.setTypeface(null, Typeface.BOLD);
                btn.setPadding(dp(a, 10), dp(a, 11), dp(a, 10), dp(a, 11));
                btn.setBackground(act.primary
                        ? bg(p.accent, a, 14)
                        : bg(0x00000000, a, 14));
                btn.setOnClickListener(v -> {
                    if (act.autoDismiss) d.dismiss();
                    if (act.run != null) act.run.run();
                });
                row.addView(btn, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, act.primary ? 1.25f : 1f));
            }
            card.addView(row);
        }

        wrap.addView(card);
        d.setContentView(wrap);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0x00000000));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.45f);
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        d.show();
        return d;
    }

    /** 简短信息弹窗（单个「知道了」） */
    public static Dialog info(Activity a, Palette p, int iconRes, String title, String message) {
        return dialog(a, p, iconRes, title, message, null, primary("知道了", null));
    }

    /** 单选列表弹窗（替代系统 setSingleChoiceItems）：圆点用强调色描边 */
    public static Dialog chooser(Activity a, Palette p, int iconRes, String title,
                                CharSequence[] items, int checked, java.util.function.IntConsumer onPick) {
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        Dialog[] holder = new Dialog[1];
        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            boolean on = i == checked;
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(a, 12), 0, dp(a, 12));

            TextView dot = text(a, "", 12f, p.accent);
            dot.setBackground(on
                    ? bg(p.accent, a, 999)
                    : bg(0x00000000, a, 999, p.muteInk, 1.8f));
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(a, 20), dp(a, 20));
            dlp.setMargins(0, 0, dp(a, 13), 0);
            row.addView(dot, dlp);
            if (on) {
                // 选中：用内嵌白点表示（实心圆 + 白环）
                dot.setBackground(bg(p.accent, a, 999));
                dot.setLayoutParams(dlp);
            }

            TextView label = text(a, items[i].toString(), 14.5f, on ? INK : INK2);
            if (on) label.setTypeface(null, Typeface.BOLD);
            row.addView(label, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (i > 0) {
                View line = divider(a, p);
                list.addView(line);
            }
            row.setOnClickListener(v -> {
                if (onPick != null) onPick.accept(idx);
                if (holder[0] != null) holder[0].dismiss();
            });
            list.addView(row);
        }
        holder[0] = dialog(a, p, iconRes, title, null, list, ghost("取消", null));
        return holder[0];
    }
}
