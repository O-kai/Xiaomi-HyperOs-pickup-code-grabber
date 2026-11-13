package io.github.okaidev.pickupcode;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 桌面小组件设置：字号档位、是否显示地点、最多条数。
 *
 * 改动即时生效（写 WidgetPrefs 后立刻刷小组件），不需要「保存」按钮。
 */
public class WidgetSettingsActivity extends Activity {

    private UiKit.Palette P;
    private LinearLayout fontSeg;
    private LinearLayout rowsSeg;
    /** v4.0.2：预览改成动态行列表（支持「全部信息」模式下多画一行已取的） */
    private LinearLayout previewRows;
    /** 预览的外框：主题/透明度变化时要换背景 */
    private LinearLayout previewBox;
    private TextView previewTitle;
    private TextView previewCount;
    private UiKit.Switch placeSwitch;
    private UiKit.Switch codeSwitch;
    /** 下级的整行容器——上级关掉时要整行变淡 */
    private LinearLayout placeRow;
    /** 组件在桌面上的位置（v4.1.0）：预览卡片按它的宽高比渲染 */
    private WidgetPlacement placement;
    /** 预览内容相对真实组件的缩放系数 */
    private float previewScale = 1f;
    /** 「重新对齐」进行中：连点会连开抓取页，用标志位挡住 */
    private boolean realigning = false;
    /** 桌面上到底有没有组件。示例预览要覆盖待取 + 已取，和真实组件分开算。 */
    private boolean previewOnDesktop = false;
    /** 本次进页面是否已经做过「位置有没有被拖动」的静默检查 */
    private boolean resumeChecked = false;
    private static final int REQ_BACKDROP = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // v4.0：先定主题再 setContentView，否则会先按浅色建界面
        // v4.1.0：这一页要透出真实壁纸，用 wallpaper 变体
        // v4.1.0：这一页恢复成**不透明**页面 —— 背景由「抓下来的那块壁纸」提供，
        // 不再靠窗口透明。这样布局完全自由：预览放哪、放多大都行。
        UiKit.applyTheme(this);
        super.onCreate(savedInstanceState);
        P = UiKit.palette(this);
        getWindow().setStatusBarColor(P.bg);
        try {
            getWindow().getDecorView().setSystemUiVisibility(
                    UiKit.isDark(this) ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        } catch (Throwable ignored) { }

        final int screenW;
        final int screenH;
        {
            int ww, hh;
            try {
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Rect b = getWindowManager()
                            .getMaximumWindowMetrics().getBounds();
                    ww = b.width();
                    hh = b.height();
                } else {
                    android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
                    getWindowManager().getDefaultDisplay().getRealMetrics(dm);
                    ww = dm.widthPixels;
                    hh = dm.heightPixels;
                }
            } catch (Throwable t) {
                ww = getResources().getDisplayMetrics().widthPixels;
                hh = getResources().getDisplayMetrics().heightPixels;
            }
            screenW = ww;
            screenH = hh;
        }

        // 没有组件时不要用缓存：那是上一个组件的坐标/尺寸，套到「示例预览」上
        // 会得到一条形状奇怪的框（真机上出现过占满整屏的预览）。
        final boolean onDesktop = WidgetPlacement.hasWidget(this);
        previewOnDesktop = onDesktop;
        WidgetPlacement pl = onDesktop ? WidgetPlacement.cached(this) : null;
        if (pl == null) pl = WidgetPlacement.fallback(this);
        placement = pl;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(P.bg);
        root.addView(buildHeader());

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setPadding(dp(18), dp(4), dp(18), dp(24));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        sv.addView(box, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        // v4.1.0：实时预览固定在底部，不参与滚动 —— 调上面任何一项都能立刻看到，
        // 不会因为滚动把预览滚出屏幕。
        root.addView(buildPreviewDock(pl, onDesktop, screenW, screenH));

        // v4.1.0：第一次进这个页面、还没有背景图时，自动抓一次。
        // 不让用户先面对一块灰底发愣，也不用教他再点一次按钮。
        if (onDesktop && !WidgetBackdrop.exists(this)) {
            root.postDelayed(this::captureBackdrop, 350);
        }
        box.addView(buildLookCard(), UiKit.cardLp(this, 10));
        box.addView(buildFontCard(), UiKit.cardLp(this, 10));
        box.addView(buildShowCard(), UiKit.cardLp(this, 10));
        box.addView(buildRowsCard(), UiKit.cardLp(this, 10));

        // v4.0.2：加「重新添加」入口。
        //
        // 真机结论（K90 Pro Max / HyperOS 4.0）：App 更新后，
        // 桌面【不会重建】已有小组件的视图树——新的 initialLayout 不会生效，
        // 表现为「行距被压扁、标题不见」这种明显错乱。
        // 系统层面没有让 App 主动重建的 API，只能让用户重新添加一次。
        // 所以这里给一个明确入口，别让用户自己猜「怎么桌面变乱了」。
        TextView readd = UiKit.text(this, "重新添加小组件", 13.5f, P.accentInk);
        readd.setGravity(Gravity.CENTER);
        readd.setTypeface(null, Typeface.BOLD);
        readd.setPadding(0, dp(12), 0, dp(12));
        readd.setBackground(UiKit.bg(P.chipBg, this, 12));
        readd.setOnClickListener(v -> WidgetPin.prompt(this));
        LinearLayout.LayoutParams rlp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp2.setMargins(0, dp(10), 0, 0);
        box.addView(readd, rlp2);

        // v4.0.3：若桌面上已有小组件实例，提醒移除重加。
        //
        // 真机踩过的坑：Android 桌面在 App 更新后【不会重建】已有小组件的视图树——
        // 它只在首次添加时 inflate 一次 initialLayout，之后的 updateAppWidget
        // 只是往那棵已存在的视图树上套新数据。布局结构一旦变了，桌面上的组件
        // 就会显示成错乱的样子（码和地点分行、底部有残留文字）。
        // 系统层面没有让 App 主动重建的 API，只能移除后重新添加。
        //
        // 所以这里主动检测并提示，别让用户对着一个坏掉的组件猜原因。
        try {
            int[] ids = android.appwidget.AppWidgetManager.getInstance(this)
                    .getAppWidgetIds(new android.content.ComponentName(this,
                            PickupWidgetProvider.class));
            if (ids != null && ids.length > 0) {
                TextView warn = UiKit.text(this,
                        "桌面上已有 " + ids.length + " 个小组件。"
                                + "App 更新后桌面不会重建旧组件的视图，显示异常时"
                                + "请先长按组件「移除」，再重新添加一次。",
                        12f, P.heroWarnInk);
                warn.setLineSpacing(dp(2), 1f);
                warn.setBackground(UiKit.bg(P.heroWarn, this, 12));
                warn.setPadding(dp(12), dp(10), dp(12), dp(10));
                LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                wlp.setMargins(0, dp(10), 0, 0);
                box.addView(warn, wlp);
            }
        } catch (Throwable ignored) { }

        // v4.0.3：小组件自检。
        //
        // 桌面加小组件失败时，系统日志里【什么都不会有】——失败发生在桌面进程
        // inflate RemoteViews 的阶段，异常不会回传给 App。自检页在 App 进程内
        // 复现同一次 inflate，当场就能看出是哪一处不兼容。
        TextView diag = UiKit.text(this, "小组件自检", 13.5f, P.accentInk);
        diag.setGravity(Gravity.CENTER);
        diag.setTypeface(null, Typeface.BOLD);
        diag.setPadding(0, dp(12), 0, dp(12));
        diag.setBackground(UiKit.bg(P.chipBg, this, 12));
        diag.setOnClickListener(v -> startActivity(
                new android.content.Intent(this, WidgetDiagActivity.class)));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.setMargins(0, dp(8), 0, 0);
        box.addView(diag, dlp);

        TextView note = UiKit.text(this,
                "显示条数由组件高度决定——把组件拉大就能看到更多；"
                        + "点取件码复制，点右侧 ✓ 标记已取；点顶部标题进入取件记录页。\n\n"
                        + "提示：App 更新后，桌面上已有的组件不会自动套用新样式"
                        + "（Android 桌面不重建已有小组件的视图树），"
                        + "显示错乱时请先「移除」旧的，再点上面的「重新添加小组件」。",
                12f, P.muteInk);
        note.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.setMargins(dp(4), dp(14), dp(4), 0);
        box.addView(note, nlp);

        setContentView(root);
        rebuildSegments();
        refreshPreview();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // v4.1.0：用户在桌面上把组件拖走 / 拉大拉小之后，缓存里的格子就过期了。
        // 这里静默比一次：只有 root 通道已经建好（之前授权过）才做，
        // 不会因为进这个页面就弹 Magisk 授权；位置真变了才重抓背景。
        if (!previewOnDesktop || realigning || resumeChecked) return;
        resumeChecked = true;
        if (!RootShell.isReady()) return;

        new Thread(() -> {
            final WidgetPlacement now;
            try {
                now = WidgetPlacement.remeasure(this);
            } catch (Throwable t) {
                return;
            }
            if (now == null) return;
            WidgetPlacement old = placement;
            boolean moved = old == null
                    || Math.abs(old.top - now.top) > 0.002f
                    || Math.abs(old.bottom - now.bottom) > 0.002f
                    || Math.abs(old.left - now.left) > 0.002f
                    || Math.abs(old.right - now.right) > 0.002f;
            if (!moved) return;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                captureBackdrop();
            });
        }, "placement-resume").start();
    }

    /** 文字色：深色主题白字、浅色主题深字（与组件里的 fg() 同一套口径） */
    private int fg(int alpha) {
        return (alpha << 24) | (PickupWidgetProvider.isLightTheme(this) ? 0x1C1C1E : 0xFFFFFF);
    }

    /**
     * 「外观」卡片（v4.2）：配色 + 七档透明度 + 主题。
     *
     * <p>配色分两类：单色（深色主题白字、浅色主题墨字）和四套彩色主题
     * （雨后彩虹 / 夏日夕阳 / 极光夜空 / 樱花初雪）——彩色主题下每行取件码
     * 按色板循环换色，多行之间一眼能分开。
     *
     * <p>透明度做成七段色阶条：每块直接用该档的底色画出来，
     * 铺在壁纸上一眼能看出「实底 → 全透」的差别，不用挨个点开试。
     */
    private View buildLookCard() {
        LinearLayout card = UiKit.card(this, P);

        TextView head = UiKit.text(this, "外观", 14.5f, P.navInk);
        head.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        card.addView(head);
        card.addView(UiKit.divider(this, P));

        // ---- 配色 ----
        card.addView(UiKit.row(this, P, R.drawable.ic_palette, "配色",
                paletteLabel(), UiKit.chevron(this, P)));
        card.getChildAt(card.getChildCount() - 1).setOnClickListener(v -> showPaletteDialog());

        card.addView(UiKit.divider(this, P));

        // ---- 七档透明度 ----
        LinearLayout alphaHead = new LinearLayout(this);
        alphaHead.setOrientation(LinearLayout.HORIZONTAL);
        alphaHead.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams ahlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ahlp.setMargins(0, dp(12), 0, dp(8));
        alphaHead.addView(UiKit.text(this, "透明度", 14f, P.navInk),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        alphaHead.addView(UiKit.text(this, WidgetPrefs.ALPHA_NAMES[WidgetPrefs.alphaIndex(this)],
                12f, P.muteInk));
        card.addView(alphaHead);

        card.addView(buildAlphaStrip());

        TextView alphaHint = UiKit.text(this,
                "越往右越透，全透时只剩文字压在你的壁纸上", 11.5f, P.muteInk);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.setMargins(dp(2), dp(8), dp(2), 0);
        card.addView(alphaHint, hlp);

        card.addView(UiKit.divider(this, P));

        // ---- 主题（决定单色文字与浅底/深底）----
        card.addView(UiKit.row(this, P, R.drawable.ic_grid, "主题",
                themeLabel(), UiKit.chevron(this, P)));
        card.getChildAt(card.getChildCount() - 1).setOnClickListener(v ->
                UiKit.chooser(this, P, R.drawable.ic_grid, "主题",
                        new CharSequence[]{"深色", "浅色", "跟随系统"},
                        themeIndex(), idx -> {
                            WidgetPrefs.setTheme(this,
                                    idx == 0 ? "dark" : idx == 1 ? "light" : "system");
                            applyToWidget();
                            recreate();
                        }));

        return card;
    }

    /**
     * 配色选择（v4.2）：彩色主题的按钮**本身就是那套渐变色**。
     *
     * <p>彩色主题不用色点：按钮整体铺开，一眼就是「雨后彩虹」「夏日夕阳」的样子。
     * 单色相反 —— 它只有白/黑，必须放一个色点才看得出是什么颜色。
     */
    private void showPaletteDialog() {
        final String[] keys = {"single", "rainbow", "sunset", "aurora", "sakura"};
        final String[] names = {"单色", "雨后彩虹", "夏日夕阳", "极光夜空", "樱花初雪"};
        final int[][] grad = {
                null,
                {0xFFFF9EC4, 0xFFC6A8FF},
                {0xFFFFB067, 0xFFFFD54A},
                {0xFF7BE0A6, 0xFFC9A2FF},
                {0xFFFFC2D8, 0xFFDCC6EC},
        };
        final int cur = paletteIndex();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        final android.app.Dialog[] holder = new android.app.Dialog[1];
        for (int i = 0; i < keys.length; i++) {
            final int idx = i;

            LinearLayout btn = new LinearLayout(this);
            btn.setOrientation(LinearLayout.HORIZONTAL);
            btn.setGravity(Gravity.CENTER_VERTICAL);
            btn.setPadding(dp(16), 0, dp(16), 0);

            android.graphics.drawable.GradientDrawable bg =
                    new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(dp(14));
            // 单色按钮直接画成「组件实底的样子」：深色主题就是深灰底白字，
            // 浅色主题就是白底黑字。放在白色弹窗上时，白底那块要补一圈细边，
            // 否则按钮会「消失」在白底里。
            int labelColor;
            if (grad[i] == null) {
                // 「单色」不是一个颜色（深色主题是白、浅色主题是墨），
                // 所以不画成实心色块 —— 那样总有一种主题下文字会糊掉。
                // 用浅灰底 + 正文色 + 一个墨色圆点，弹窗在什么主题下都清楚。
                bg.setColor(0x11000000);
                bg.setStroke(Math.max(1, dp(1)), 0x22888888);
                labelColor = P.navInk;
            } else {
                bg.setOrientation(
                        android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT);
                bg.setColors(grad[i]);
                labelColor = 0xFFFFFFFF;
            }
            if (i == cur) bg.setStroke(dp(2), P.accentInk);
            btn.setBackground(bg);

            if (grad[i] == null) {
                View dot = new View(this);
                android.graphics.drawable.GradientDrawable dotBg =
                        new android.graphics.drawable.GradientDrawable();
                dotBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                dotBg.setColor(P.navInk);
                dotBg.setStroke(Math.max(1, dp(1)), 0x55FFFFFF);
                dot.setBackground(dotBg);
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(16), dp(16));
                dlp.setMargins(0, 0, dp(12), 0);
                btn.addView(dot, dlp);
            }

            TextView label = UiKit.text(this, names[i], 14.5f, labelColor);
            // 樱花那几套本身很浅（#F0E2F5 之类），纯白字放上去几乎看不见；
            // 一圈淡阴影能把字从渐变里「拎」出来，又不改变配色本身。
            if (grad[i] != null) {
                label.setShadowLayer(Math.max(2f, dp(3)), 0, dp(1), 0x99000000);
            }
            btn.addView(label, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            if (i == cur) {
                TextView tick = UiKit.text(this, "✓", 15f,
                        grad[i] == null ? P.accentInk : 0xFFFFFFFF);
                if (grad[i] != null) {
                    tick.setShadowLayer(Math.max(2f, dp(3)), 0, dp(1), 0x99000000);
                }
                btn.addView(tick);
            }

            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
            blp.setMargins(0, dp(5), 0, dp(5));

            btn.setOnClickListener(v -> {
                WidgetPrefs.setPalette(this, keys[idx]);
                applyToWidget();
                if (holder[0] != null) holder[0].dismiss();
                recreate();
            });
            box.addView(btn, blp);
        }

        holder[0] = UiKit.dialog(this, P, R.drawable.ic_palette, "配色", null, box,
                UiKit.ghost("取消", () -> { }));
    }

    /** 七段色阶条：每块直接用该档的底色画出来，点到哪块就是哪档 */
    private View buildAlphaStrip() {
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        int cur = WidgetPrefs.alphaIndex(this);
        int[] steps = WidgetPrefs.ALPHA_STEPS;

        for (int i = 0; i < steps.length; i++) {
            boolean on = i == cur;
            TextView t = UiKit.text(this, WidgetPrefs.ALPHA_NAMES[i], 10.5f,
                    on ? P.accentInk : P.navInk);
            t.setGravity(Gravity.CENTER);
            t.setIncludeFontPadding(false);

            android.graphics.drawable.GradientDrawable bg =
                    new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(dp(9));
            bg.setColor(alphaChipColor(steps[i]));
            // 全透明那块在页面底色上看不见，给个细边把形状勾出来
            bg.setStroke(steps[i] == 0 ? Math.max(1, dp(1)) : 0, 0x33888888);
            if (on) bg.setStroke(dp(2), P.accentInk);
            t.setBackground(bg);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp(2), 0, dp(2), 0);
            t.setPadding(0, dp(10), 0, dp(10));

            final int idx = i;
            t.setOnClickListener(v -> {
                WidgetPrefs.setAlphaPct(this, steps[idx]);
                applyToWidget();
                recreate();
            });
            strip.addView(t, lp);
        }
        return strip;
    }

    /** 色阶块本身的底色：就是该档不透明度叠在主题底色上的样子 */
    private int alphaChipColor(int pct) {
        int a = Math.round(Math.max(0, Math.min(100, pct)) / 100f * 255f);
        return (a << 24) | (PickupWidgetProvider.isLightTheme(this) ? 0xFFFFFF : 0x1B1B1B);
    }

    private int paletteIndex() {
        String p = WidgetPrefs.palette(this);
        if ("rainbow".equals(p)) return 1;
        if ("sunset".equals(p)) return 2;
        if ("aurora".equals(p)) return 3;
        if ("sakura".equals(p)) return 4;
        return 0;
    }

    private String paletteKey(int idx) {
        switch (idx) {
            case 1: return "rainbow";
            case 2: return "sunset";
            case 3: return "aurora";
            case 4: return "sakura";
            default: return "single";
        }
    }

    private String paletteLabel() {
        switch (paletteIndex()) {
            case 1: return "雨后彩虹";
            case 2: return "夏日夕阳";
            case 3: return "极光夜空";
            case 4: return "樱花初雪";
            default: return "单色";
        }
    }

    private int themeIndex() {
        String t = WidgetPrefs.theme(this);
        return "light".equals(t) ? 1 : "system".equals(t) ? 2 : 0;
    }

    private String themeLabel() {
        int i = themeIndex();
        if (i == 1) return "浅色";
        if (i == 2) {
            return "跟随系统（当前"
                    + (PickupWidgetProvider.isLightTheme(this) ? "浅色" : "深色") + "）";
        }
        return "深色";
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14), dp(14), dp(20), dp(10));
        // v4.1.0：这一页整窗透明（透壁纸），标题栏不加底色就会被壁纸糊住。
        header.setBackgroundColor(P.bg);

        TextView back = UiKit.text(this, "‹", 30f, P.navInk);
        back.setGravity(Gravity.CENTER);
        back.setPadding(dp(10), 0, dp(10), dp(4));
        back.setOnClickListener(v -> finish());
        header.addView(back);

        TextView title = UiKit.text(this, "桌面小组件", 21f, P.navInk);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return header;
    }

    /**
     * 「重新校准」（v4.1.0，原名「重新对齐」）。
     *
     * <p>把「重测位置」和「重抓背景」合成动作。这两件事对用户是同一件事——
     * 都是「让预览和桌面一致」，拆成两个按钮只会让人犹豫点哪个。
     * 所以原来那张「预览位置」卡片整个去掉了，入口就留在预览区右上角。
     *
     * <p>位置读不到也照样抓背景：至少壁纸那块能对上，不至于白点一下。
     */
    private void realign() {
        if (realigning) return;
        realigning = true;
        Toast.makeText(this, "正在重新校准…", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            WidgetPlacement p = null;
            try {
                p = WidgetPlacement.remeasure(this);
            } catch (Throwable t) {
                android.util.Log.w("PICKUPDEBUG", "realign 读位置失败: " + t);
            }
            final WidgetPlacement got = p;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    realigning = false;
                    return;
                }
                if (got == null) {
                    Toast.makeText(this,
                            "没读到桌面位置（需要 root 或无障碍权限），先按原有位置更新背景",
                            Toast.LENGTH_LONG).show();
                } else if (!got.fromLauncher && !got.fromCalib) {
                    // 格子读到了，但还没标定过网格参数 → 只能估算。
                    Toast.makeText(this,
                            a11yEnabled()
                                    ? "位置还是估算的：回桌面停一秒再进来，就能对上"
                                    : "位置是估算的：开启「桌面位置读取」后点这里即可精确对齐",
                            Toast.LENGTH_LONG).show();
                }
                // 把这次算出的矩形直接带给抓取页，避免它再读一次缓存读到别的值
                if (got != null) WidgetBackdrop.setPending(got);
                // 走到这里交给抓取页；回来后整页 recreate，realigning 自然复位
                captureBackdrop();
            });
        }, "realign").start();
    }

    private View buildFontCard() {
        LinearLayout card = UiKit.card(this, P);
        card.setPadding(dp(16), dp(14), dp(16), dp(16));

        TextView title = UiKit.text(this, "取件码字号", 14.5f, P.navInk);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        card.addView(title);

        TextView sub = UiKit.text(this, "码和地点一起缩放", 11.5f, P.muteInk);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.setMargins(0, dp(4), 0, 0);
        card.addView(sub, slp);

        fontSeg = new LinearLayout(this);
        fontSeg.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.setMargins(0, dp(12), 0, 0);
        card.addView(fontSeg, flp);
        return card;
    }

    /**
     * 「显示」卡片（v4.0.3 第二轮重做）。
     *
     * <p>原来是并列的两个开关：「显示取件地点」+「隐藏取件码」。
     * 两个问题：
     * <ol>
     *   <li><b>否定式</b>——「隐藏取件码：关」要在脑子里取反一次才知道是什么意思；</li>
     *   <li><b>没有层级</b>——地点是码的附属信息，码都不显示了，
     *       地点开关就没有任何意义，它俩不该平起平坐。</li>
     * </ol>
     *
     * <p>所以改成上下级：「显示取件码」在上（默认开），「显示取件地点」缩进在下面；
     * 上级关掉时下级整行变淡且不可点——<b>不是隐藏</b>，隐藏会让人不知道还有这一项。
     * 下级开关的值不清空：下次打开上级，地点按他原来的选择恢复。
     */
    private View buildShowCard() {
        LinearLayout card = UiKit.card(this, P);

        codeSwitch = new UiKit.Switch(this, P, WidgetPrefs.showCode(this));
        codeSwitch.setOnToggle(on -> {
            WidgetPrefs.setShowCode(this, on);
            refreshSubordinateRows();
            applyToWidget();
            refreshPreview();
        });
        card.addView(UiKit.row(this, P, R.drawable.ic_qr, "显示取件码",
                "关掉后组件只显示件数；下面的地点也一并失效", codeSwitch));
        card.addView(UiKit.divider(this, P));

        placeSwitch = new UiKit.Switch(this, P, WidgetPrefs.showPlace(this));
        placeSwitch.setOnToggle(on -> {
            // 上级关着时这个开关本来就被禁用，这里是第二道保险
            if (!WidgetPrefs.showCode(this)) return;
            WidgetPrefs.setShowPlace(this, on);
            applyToWidget();
            refreshPreview();
        });
        placeRow = UiKit.row(this, P, R.drawable.ic_pin, "显示取件地点",
                "关掉后只显示大号取件码，更清爽", placeSwitch);
        // 缩进 28dp 表示它是上一行的下级；垂直 padding 保持不变
        placeRow.setPadding(dp(28), dp(11), 0, dp(11));
        card.addView(placeRow);

        refreshSubordinateRows();
        return card;
    }

    /**
     * 按上级开关的状态刷新下级的可用性。
     *
     * <p>用「变淡 + 不可点」而不是「隐藏」：隐藏会让用户以为没这个功能；
     * 变淡保留位置和当前值，一眼能看出「它还在，只是现在不生效」。
     */
    private void refreshSubordinateRows() {
        boolean on = WidgetPrefs.showCode(this);
        if (placeRow != null) {
            placeRow.setAlpha(on ? 1f : 0.4f);
        }
        if (placeSwitch != null) {
            placeSwitch.setEnabled(on);
        }
    }

    /**
     * 「显示内容」卡片。
     *
     * <p>v4.0.2：原先这里���「最多显示条数」（2–5 条可选）。用户指出：
     * 「最多可显示条数不应该有太明显的限制框架，我们只需要根据小组件的实际大小
     * 来展示就可以了。组件够大，展示的内容就多，反正用户会自己调整大小，
     * 他自己能感知得到。」
     *
     * <p>所以条数选项整个去掉——行数现在完全由组件高度决定。
     * 这里换成「显示内容」：待取件（默认）或全部信息（含已取件）。
     */
    private View buildRowsCard() {
        LinearLayout card = UiKit.card(this, P);
        card.setPadding(dp(16), dp(14), dp(16), dp(16));

        TextView title = UiKit.text(this, "显示内容", 14.5f, P.navInk);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        card.addView(title);

        TextView sub = UiKit.text(this,
                "显示条数由组件高度决定——把组件拉大就能看到更多", 11.5f, P.muteInk);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.setMargins(0, dp(4), 0, 0);
        card.addView(sub, slp);

        rowsSeg = new LinearLayout(this);
        rowsSeg.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.setMargins(0, dp(12), 0, 0);
        card.addView(rowsSeg, rlp);
        return card;
    }

    /**
     * 底部固定预览（v4.1.0）。
     *
     * <p>背景完全透明：窗口后面是系统画的真实壁纸，所以这里看到的就是
     * 桌面上那张壁纸的原始渲染 —— 不截图、不读图、不要任何权限。
     *
     * <p>它挂在根布局底部而不是滚动区里：调上面的控件时它始终露着，
     * 不用来回滚。
     */
    /**
     * 底部固定的「实时预览」区（v4.1.0）。
     *
     * <p>不参与滚动：调上面的字号、配色、显示项时它始终露着，改一下就能看到结果。
     *
     * <p><b>桌面上有没有组件，这里都画预览。</b>没有组件时用一块中性渐变当背景，
     * 用户照样能先把样式调好，等他添加了组件，同一套设置直接生效——不让「还没添加」
     * 变成「什么都不能试」。
     *
     * <p>右上角只有一个动作：有组件是「重新对齐」（重测位置 + 重抓背景），
     * 没组件是「添加到桌面」。位置信息不再单独占一行。
     */
    private View buildPreviewDock(WidgetPlacement pl, boolean onDesktop,
                                  int screenW, int screenH) {

        LinearLayout dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.VERTICAL);
        dock.setBackgroundColor(P.bg);
        dock.setPadding(dp(18), dp(12), dp(18), dp(18));

        View line = new View(this);
        line.setBackgroundColor(0x1A888888);
        dock.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.8f))));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.setMargins(0, dp(10), 0, dp(8));
        dock.addView(head, hlp);

        // 告诉用户当前预览是「桌面给的精确矩形」还是「按格子估算」——
        // 加了桌面作用域就能变前者，用户自己看得见，不用猜。
        boolean exact = pl != null && (pl.fromLauncher || pl.fromCalib);
        String src = exact ? "精确对齐" : "按格子估算";
        TextView title = UiKit.text(this, "小组件实时预览 · " + src, 13f, P.muteInk);
        head.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView action = UiKit.text(this, onDesktop ? "重新校准" : "添加到桌面",
                12.5f, P.accentInk);
        action.setPadding(dp(14), dp(10), dp(2), dp(10));
        if (onDesktop) {
            action.setOnClickListener(v -> realign());
        } else {
            action.setOnClickListener(v -> WidgetPin.prompt(this));
        }
        head.addView(action);

        // 没组件时不读旧图：那可能是上一个组件留下的，容易误导。
        android.graphics.Bitmap bg = onDesktop ? WidgetBackdrop.load(this) : null;

        // 预览要展示的是「整块格子」——桌面上拖尺寸时那个蓝框，
        // 不是卡片本身。只展示卡片会把四角圆边和周围留白切掉，
        // 看着像一张贴纸，而不是它在桌面上的样子。
        float[] frame = pl != null ? pl.frameRatios(this) : null;
        float frameW;
        float frameH;
        if (frame != null) {
            frameW = Math.max(1f, (frame[2] - frame[0]) * screenW);
            frameH = Math.max(1f, (frame[3] - frame[1]) * screenH);
        } else if (pl != null) {
            frameW = Math.max(1f, (pl.right - pl.left) * screenW);
            frameH = Math.max(1f, (pl.bottom - pl.top) * screenH);
        } else {
            frameW = screenW;
            frameH = screenH * 0.30f;
        }
        float ratio = frameH / frameW;
        // 背景图就是按格子框裁的，以它为准（比例和画面天然对齐）
        if (bg != null && bg.getWidth() > 0) {
            ratio = bg.getHeight() / (float) bg.getWidth();
        }

        int availW = screenW - dp(18) * 2;
        int maxH = (int) (screenH * 0.40f);
        int boxW = availW;
        int boxH = (int) (boxW * ratio);
        if (boxH > maxH) {
            boxH = maxH;
            boxW = (int) (boxH / Math.max(0.01f, ratio));
        }

        android.widget.FrameLayout stage = new android.widget.FrameLayout(this);
        if (bg != null) {
            stage.setBackground(new android.graphics.drawable.BitmapDrawable(getResources(), bg));
        } else {
            stage.setBackgroundResource(R.drawable.widget_preview_placeholder);
        }
        // 框本身不裁圆角：它是桌面上的格子边界，直角才对。
        // 曲边由里面的卡片负责——那才是组件真正的样子。
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(boxW, boxH);
        slp.gravity = Gravity.CENTER_HORIZONTAL;
        dock.addView(stage, slp);

        // 卡片在框内按真实留白内缩（K90 上横向各 40/41、纵向 0/44）
        float[] inset = pl != null ? pl.cardInset(this) : new float[]{0, 0, 0, 0};
        int cardL = Math.round(inset[0] * boxW);
        int cardT = Math.round(inset[1] * boxH);
        int cardR = Math.round(inset[2] * boxW);
        int cardB = Math.round(inset[3] * boxH);
        int cardW = Math.max(dp(60), boxW - cardL - cardR);
        int cardH = Math.max(dp(40), boxH - cardT - cardB);

        // 内容按「真实卡片宽度 → 预览卡片宽度」等比缩
        float realCardW = Math.max(1f, frameW * (1f - inset[0] - inset[2]));
        float k = cardW / realCardW;
        k = Math.max(0.35f, Math.min(1.4f, k));

        final float corner = Math.max(6f, dp(16) * k);
        android.widget.FrameLayout card = new android.widget.FrameLayout(this);
        card.setBackgroundResource(PickupWidgetProvider.backgroundRes(this));
        card.setClipToOutline(true);
        card.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(android.view.View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), corner);
            }
        });
        android.widget.FrameLayout.LayoutParams clp =
                new android.widget.FrameLayout.LayoutParams(cardW, cardH);
        clp.leftMargin = cardL;
        clp.topMargin = cardT;
        stage.addView(card, clp);

        int padPx = (int) (dp(10) * k);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(padPx, padPx, padPx, padPx);
        card.addView(content, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        previewBox = content;
        previewScale = k;

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        previewTitle = UiKit.text(this, "取件记录", 18f * k, 0xFFFFFFFF);
        previewTitle.setTypeface(null, Typeface.BOLD);
        row.addView(previewTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        previewCount = UiKit.text(this, "", 12f * k, 0xB3FFFFFF);
        row.addView(previewCount);
        content.addView(row);

        previewRows = new LinearLayout(this);
        previewRows.setOrientation(LinearLayout.VERTICAL);
        content.addView(previewRows, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 一行说明，只在「画面不是真桌面」时出现。
        // 放在卡片**外面**：示例有 3 行，压在里面会把第三行挡住（真机上出现过）。
        String hintText = null;
        if (!onDesktop) {
            hintText = "示例预览 · 添加到桌面后会自动换成你的桌面";
        } else if (bg == null) {
            hintText = "点右上角「重新校准」，换成你的桌面背景";
        }
        if (hintText != null) {
            TextView hint = UiKit.text(this, hintText, 11.5f, P.muteInk);
            hint.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams hlp2 = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hlp2.setMargins(0, dp(8), 0, 0);
            dock.addView(hint, hlp2);
        }
        return dock;
    }

    /** 开一次透明页抓背景，回来刷新界面 */
    /**
     * 本 App 的无障碍服务现在是不是开着。
     *
     * <p>覆盖安装会把它从「已启用」里踢出去（系统认为服务变了），
     * 用户不知道，只会觉得「又不准了」。这里读一次做提示，不申请任何权限。
     */
    private boolean a11yEnabled() {
        try {
            android.view.accessibility.AccessibilityManager am =
                    (android.view.accessibility.AccessibilityManager)
                            getSystemService(ACCESSIBILITY_SERVICE);
            if (am == null) return false;
            for (android.accessibilityservice.AccessibilityServiceInfo info
                    : am.getEnabledAccessibilityServiceList(
                            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
                android.content.pm.ResolveInfo ri = info.getResolveInfo();
                if (ri != null && ri.serviceInfo != null
                        && getPackageName().equals(ri.serviceInfo.packageName)) return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private void captureBackdrop() {
        try {
            android.util.Log.d("PICKUPDEBUG", "captureBackdrop: 启动抓取页");
            Toast.makeText(this, "正在抓取桌面背景…", Toast.LENGTH_SHORT).show();
            startActivityForResult(
                    new android.content.Intent(this, BackdropCaptureActivity.class), REQ_BACKDROP);
        } catch (Throwable t) {
            Toast.makeText(this, "打不开抓取页：" + t, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_BACKDROP) {
            if (resultCode == RESULT_OK) recreate();
            else Toast.makeText(this,
                    "没抓到背景（可能需要 root 授权），可以再试一次", Toast.LENGTH_LONG).show();
        }
    }

    /** 状态栏高度：整屏铺满后，标题栏要自己让出这一段 */
    private int statusBarHeight() {
        try {
            int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) return getResources().getDimensionPixelSize(id);
        } catch (Throwable ignored) { }
        return dp(28);
    }

    /** 预览里加一行（picked = true 表示已取件那一行：划掉 + 变灰 + 无 ✓） */
    private void addPreviewRow(int index, boolean picked) {
        addPreviewRow(index, picked, picked ? "09-8-7654" : "12-3-4567",
                picked ? "中通快递 · 已取" : "菜鸟驿站 · 1 号柜");
    }

    /** 预览里加一行（可指定码与地点） */
    private void addPreviewRow(int index, boolean picked, String codeText, String placeText) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        // 行高与真实布局的下限一致（真实布局按权重均分，实际会略大于 40dp）。
        // v4.1.0：再乘预览缩放系数——预览比真实组件小时，行高和字号要同步缩，
        // 否则会出现「字小了、行没小」的错位。
        float k = previewScale;
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(dp(18), (int) (dp(WidgetPrefs.ROW_DP) * k)));
        previewRows.addView(row, rlp);

        boolean showCode = WidgetPrefs.showCode(this);
        boolean showPlace = showCode && WidgetPrefs.showPlace(this);

        // 已取的用与组件同一套「双横线」实现，避免预览和实际长得不一样
        CharSequence codeLabel = picked
                ? PickupWidgetProvider.strikeText(codeText) : codeText;
        TextView code = UiKit.text(this, "", 17f * k,
                PickupWidgetProvider.rowColor(this, index, picked ? 0x99 : 0xFF));
        code.setText(codeLabel);   // 保留 Html 的删除线 span
        code.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        if (!showCode) code.setText("");
        row.addView(code);

        // 以前这里写死白色：浅色主题下地点直接看不见（真机上发现）
        TextView place = UiKit.text(this, placeText, 11f * k,
                PickupWidgetProvider.rowColor(this, index, picked ? 0x80 : 0xB3));
        place.setMaxLines(1);
        LinearLayout.LayoutParams plp2 = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        plp2.setMargins((int) (dp(10) * k), 0, (int) (dp(8) * k), 0);
        row.addView(place, plp2);

        TextView check = UiKit.text(this, "✓", 16f * k, 0xFFFFFFFF);
        check.setGravity(Gravity.CENTER);
        check.setBackgroundResource(R.drawable.widget_done_bg);
        if (picked) {
            // 已取的不再显示 ✓（小组件里也是这样）
            check.setVisibility(View.INVISIBLE);
        }
        int box = Math.max(dp(16), (int) (dp(28) * k));
        row.addView(check, new LinearLayout.LayoutParams(box, box));

        // 「显示取件地点」关掉时：地点列【保留占位】只清空文字——
        // 与真实小组件一致（PickupWidgetProvider.build 里也是 setViewVisibility(VISIBLE)
        // + setTextViewText("")）。若这里改成 GONE，右侧的 ✓ 会贴到取件码旁边。
        if (!showPlace) {
            place.setText("");
        }
    }

    private void rebuildSegments() {
        if (fontSeg != null) {
            fontSeg.removeAllViews();
            int level = WidgetPrefs.fontLevel(this);
            addSeg(fontSeg, "小", level == WidgetPrefs.FONT_SMALL, () -> {
                WidgetPrefs.setFontLevel(this, WidgetPrefs.FONT_SMALL);
                rebuildSegments();
                refreshPreview();
                applyToWidget();
            });
            addSeg(fontSeg, "标准", level == WidgetPrefs.FONT_NORMAL, () -> {
                WidgetPrefs.setFontLevel(this, WidgetPrefs.FONT_NORMAL);
                rebuildSegments();
                refreshPreview();
                applyToWidget();
            });
            addSeg(fontSeg, "大", level == WidgetPrefs.FONT_LARGE, () -> {
                WidgetPrefs.setFontLevel(this, WidgetPrefs.FONT_LARGE);
                rebuildSegments();
                refreshPreview();
                applyToWidget();
            });
        }
        if (rowsSeg != null) {
            rowsSeg.removeAllViews();
            // v4.0.2：「显示内容」——待取件（默认）/ 全部信息（含已取件）
            int current = WidgetPrefs.showMode(this);
            addSeg(rowsSeg, "只看待取件", current == WidgetPrefs.SHOW_PENDING, () -> {
                WidgetPrefs.setShowMode(this, WidgetPrefs.SHOW_PENDING);
                rebuildSegments();
                refreshPreview();
                applyToWidget();
            });
            addSeg(rowsSeg, "全部信息", current == WidgetPrefs.SHOW_ALL, () -> {
                WidgetPrefs.setShowMode(this, WidgetPrefs.SHOW_ALL);
                rebuildSegments();
                refreshPreview();
                applyToWidget();
            });
        }
    }

    private void addSeg(LinearLayout parent, String label, boolean selected, Runnable onClick) {
        TextView tv = UiKit.text(this, label, 13.5f, selected ? P.onAccent : P.accentInk);
        tv.setGravity(Gravity.CENTER);
        tv.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        tv.setPadding(0, dp(10), 0, dp(10));
        GradientDrawable bg = UiKit.bg(selected ? P.accent : P.chipBg, this, 12);
        tv.setBackground(bg);
        tv.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(3), 0, dp(3), 0);
        parent.addView(tv, lp);
    }

    private void refreshPreview() {
        if (previewRows == null) return;
        float scale = WidgetPrefs.fontScale(this);
        // 没有组件时按「全部信息」画：示例要把待取、已取、划掉三种状态都覆盖到，
        // 否则用户看不到「已取件」长什么样就没法调它的样式。
        boolean showAll = WidgetPrefs.showAll(this) || !previewOnDesktop;
        boolean showCode = WidgetPrefs.showCode(this);

        // v4.1.0：背景是「抓下来的实景图」，不再由这里画底色，
        // 否则会把图片盖掉。文字色仍然按深浅主题走。
        previewTitle.setTextColor(PickupWidgetProvider.rowColor(this, 0, 0xFF));
        previewCount.setTextColor(PickupWidgetProvider.rowColor(this, 0, 0xB3));
        previewTitle.setText(showAll ? "取件记录" : "待取件");
        previewCount.setText(showAll ? "2 待取 · 1 已取" : "2 个待取");

        previewRows.removeAllViews();
        // v4.0.2 第四轮：隐私模式（关掉「显示取件码」）与真实组件保持一致——
        // 不逐条显示，码/地点/✓ 全部取消，改在展示区直白给出汇总。
        if (!showCode) {
            previewCount.setText("");
            TextView sum = UiKit.text(this,
                    showAll ? "2 个待取 · 1 个已取" : "2 个待取",
                    16f * previewScale, PickupWidgetProvider.rowColor(this, 0, 0xFF));
            sum.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.max(dp(30), (int) (dp(56) * previewScale)));
            previewRows.addView(sum, slp);
            return;
        }
        // 预览的条数跟着组件实际能放几行走：4×2 的组件在桌面上就是 2 条，
        // 这里塞 3 条会顶到卡片底边（真机上出现过第三行贴边）。
        // spanY 是组件占的行数，读不到时按 3 条估。
        int maxRows = previewOnDesktop && placement != null && placement.spanY > 0
                ? placement.spanY : 3;
        int shown = 0;
        if (shown < maxRows) { addPreviewRow(shown, false, "12-3-4567", "菜鸟驿站 · 1 号柜"); shown++; }
        if (shown < maxRows) { addPreviewRow(shown, false, "58-2-9041", "顺丰速运 · 望江路驿站"); shown++; }
        if (showAll && shown < maxRows) {
            // 全部信息模式：已取的也画出来（划掉 + 变灰 + 无 ✓）
            addPreviewRow(shown, true, "09-8-7654", "中通快递 · 已取");
        }
        // 字号缩放（预览里的行是新建的，所以要在这里统一设）
        for (int i = 0; i < previewRows.getChildCount(); i++) {
            View row = previewRows.getChildAt(i);
            if (!(row instanceof LinearLayout)) continue;
            LinearLayout r = (LinearLayout) row;
            TextView c = (TextView) r.getChildAt(0);
            TextView p = (TextView) r.getChildAt(1);
            c.setTextSize(17f * scale * previewScale);
            p.setTextSize(11f * scale * previewScale);
        }
    }

    private void applyToWidget() {
        PickupWidgetProvider.refreshAsync(this);
    }

    private int dp(float v) {
        return UiKit.dp(this, v);
    }
}
