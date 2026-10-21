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
        }
    }

    private static int c(String hex) { return Color.parseColor(hex); }

    /** A · 米金 */
    public static final Palette SAND = new Palette("sand", "米金",
            "#FBEDE1", "#FCF7F1", "#F2E7DB",
            "#F9DDB8", "#F5C6AB", "#33240E", "#6E5B41", "#6B2E1A", "#8A5741",
            "#A8813C", "#8A6A2F", "#7A5A22", "#F6E8CE", "#FBEFDD",
            "#8B7358", "#F1ECE5", "#928B82", "#3A2E1F", "#F7EFE4", "#F1EBE3");

    /** B · 雾霾蓝（默认，与原型当前设计一致） */
    public static final Palette MIST = new Palette("mist", "雾霾蓝",
            "#F2F3F5", "#F6F9FC", "#E6ECF3",
            "#D6E4F2", "#F2D6D2", "#1F3450", "#4A5F7A", "#6B2E1A", "#7A4A48",
            "#4A6FA5", "#3D5C8A", "#2F4E7A", "#E2EAF4", "#F2F6FB",
            "#6B7C92", "#EAEEF3", "#868F9B", "#26303E", "#EAF0F7", "#E8EDF4");

    /** C · 青瓷绿 */
    public static final Palette TEAL = new Palette("teal", "青瓷绿",
            "#E9F2EE", "#F5FAF8", "#E2EDE9",
            "#CFE8DE", "#F3DDD0", "#133F35", "#3E6B5E", "#6B2E1A", "#7A5A48",
            "#2F7F6F", "#256356", "#1F5648", "#DDF0E9", "#F1F8F5",
            "#5F7E75", "#E7EFEB", "#83938D", "#21372F", "#EAF4F0", "#E5EFEA");

    /** D · 藕荷玫瑰 */
    public static final Palette ROSE = new Palette("rose", "藕荷玫瑰",
            "#F7EDF1", "#FCF7F9", "#F0E4E9",
            "#F2D8E0", "#F6CBBE", "#4A2130", "#6E4A58", "#6B2E1A", "#8A4A58",
            "#A65D78", "#8A4A63", "#7A4159", "#F3DFE7", "#FAF0F4",
            "#8A6E7A", "#F0E7EB", "#97878E", "#372530", "#F8EDF1", "#F1E6EA");

    public static final Palette[] ALL = { MIST, SAND, TEAL, ROSE };

    // 语义色（四套配色通用，与原型一致）
    public static final int INK = 0xFF191919, INK2 = 0xFF3D3A36, INK3 = 0xFF8E8E8E;
    public static final int OK = 0xFF3E8E5E, OK_SOFT = 0xFFEAF3EC;
    public static final int WARN = 0xFFC97F2E, WARN_SOFT = 0xFFFBEEDC;
    public static final int DANGER = 0xFFC2543C, DANGER_SOFT = 0xFFFBE7E1;

    private static final String PREFS = "dedup";
    private static final String KEY_PALETTE = "ui_palette";
    private static final String KEY_BLUR = "ui_nav_blur";

    public static Palette palette(Context ctx) {
        String id = prefs(ctx).getString(KEY_PALETTE, MIST.id);
        for (Palette p : ALL) if (p.id.equals(id)) return p;
        return MIST;
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
     * 一行：左侧图标（可选）+ 标题/说明 + 右侧配件
     * accessory 为 null 时右侧放一个右箭头（表示可点进二级页）
     */
    public static LinearLayout row(Context ctx, Palette p, int iconRes, String title, String sub, View accessory) {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(ctx, 13), 0, dp(ctx, 13));

        if (iconRes != 0) {
            ImageView iv = icon(ctx, iconRes, 23, p.navInk);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(ctx, 23), dp(ctx, 23));
            lp.setMargins(0, 0, dp(ctx, 13), 0);
            iv.setLayoutParams(lp);
            r.addView(iv);
        }

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(ctx, title, 15.5f, INK);
        col.addView(t);
        if (sub != null && !sub.isEmpty()) {
            TextView s = text(ctx, sub, 12.5f, INK3);
            s.setLineSpacing(dp(ctx, 1), 1.0f);
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
            case "ok": b.setBackground(bg(OK, ctx, 15)); break;
            case "warn": b.setBackground(bg(WARN, ctx, 15)); break;
            case "ghost":
                b.setTextColor(p.accentInk);
                b.setBackground(bg(p.card, ctx, 15));
                break;
            case "ghostLine":
                b.setTextColor(p.accentInk);
                b.setBackground(bg(0x00000000, ctx, 15, p.accent, 1.4f));
                break;
            default: b.setBackground(bg(p.accent, ctx, 15));
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
        private boolean touchMoved;

        public Switch(Context ctx, Palette p, boolean checked) {
            super(ctx);
            this.palette = p;
            this.checked = checked;
            this.anim = checked ? 1f : 0f;
            setClickable(true);
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
            if (notify && listener != null) listener.onChanged(v);
        }

        public void setOnToggle(OnToggle l) { listener = l; }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(UiKit.dp(getContext(), 52), UiKit.dp(getContext(), 31));
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            track.set(0, 0, w, h);
            paint.setColor(checked ? palette.accent : 0xFFE5DDD3);
            cv.drawRoundRect(track, h / 2, h / 2, paint);
            float r = h / 2 - UiKit.dp(getContext(), 2);
            float cx = UiKit.dp(getContext(), 2) + r + anim * (w - 2 * UiKit.dp(getContext(), 2) - 2 * r);
            paint.setColor(0xFFFFFFFF);
            paint.setShadowLayer(UiKit.dp(getContext(), 1.5f), 0, UiKit.dp(getContext(), 0.6f), 0x33000000);
            cv.drawCircle(cx, h / 2, r, paint);
            paint.clearShadowLayer();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: touchMoved = false; return true;
                case MotionEvent.ACTION_MOVE: touchMoved = true; return true;
                case MotionEvent.ACTION_UP:
                    if (!touchMoved) { setChecked(!checked); performClick(); }
                    return true;
                default: return super.onTouchEvent(e);
            }
        }

        @Override
        public boolean performClick() { super.performClick(); return true; }
    }

    // ================================================================
    //  底栏（透明磨砂胶囊 + 强调色选中块）
    // ================================================================

    public interface OnTab { void onTab(int index); }

    private static final int[] TAB_ICONS = {
            R.drawable.ic_home, R.drawable.ic_grid, R.drawable.ic_sparkle, R.drawable.ic_gear };
    private static final String[] TAB_LABELS = { "首页", "应用", "规则", "设置" };

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
        int fill = blur ? alpha(0xFFFFFFFF, 0.85f) : 0xFFFFFFFF;
        bar.setBackground(bg(fill, ctx, 36, alpha(0xFFFFFFFF, blur ? 0.6f : 0.95f), 1f));
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
            ImageView ic = icon(ctx, TAB_ICONS[i], on ? 21 : 23, on ? 0xFFFFFFFF : p.navInk);
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
        int tint = "ok".equals(kind) || "warn".equals(kind) || kind == null || "primary".equals(kind)
                ? 0xFFFFFFFF : p.accentInk;
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
        if (message != null && !message.isEmpty()) {
            TextView m = text(a, message, 13.5f, INK2);
            m.setLineSpacing(dp(a, 4), 1.0f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(a, 12), 0, 0);
            card.addView(m, lp);
        } else if (contentView != null) {
            ScrollView sc = new ScrollView(a);
            sc.setClipToPadding(false);
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
                TextView btn = text(a, act.label, 14f, act.primary ? 0xFFFFFFFF : p.accentInk);
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
