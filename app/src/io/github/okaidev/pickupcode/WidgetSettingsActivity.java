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

    /**
     * 挑颜色时的临时配色：只影响预览，不写盘。
     *
     * <p>用户点一个色块先看效果，点「确定」才真正保存 —— 否则每点一下组件就变一下，
     * 退出后桌面还留着他不要的颜色。
     */
    private String tempPalette;
    private int tempColor;

    /** 配色展开区的容器与箭头 */
    private LinearLayout paletteBox;
    /** 配色按钮那一行（单色 + 四套彩色）：选中态会变，需要就地重画 */
    private LinearLayout paletteRow;
    private LinearLayout settingsCard;
    private LinearLayout bgSeg;

    /**
     * 透明度七块所在的容器。
     *
     * <p>v4.2.1：从「每次点都 recreate 整页」改成「只重画这一条」。
     * 色块底色跟着深/浅主题走，主题一变这条就得重画，但整页不用动。
     */
    private LinearLayout alphaStrip;
    /** 「透明度」标题右边那行当前档位（实底/微透…），改档位时要就地改字 */
    private TextView alphaHeadValue;
    /** 预览里那张卡片：主题/透明度一变就换背景 drawable */
    private android.widget.FrameLayout previewCard;

    /** 「配色」行右边那行小字（色点 + 名称/色值），选色时要实时改 */
    private TextView paletteValueText;
    /** 页内展开的色盘（不弹窗，免得盖住下面的实时预览） */
    private LinearLayout colorPanel;
    private final java.util.List<View> colorCells = new java.util.ArrayList<>();
    /** 透明度七块上的文字，颜色跟着配色走 */
    private final java.util.List<TextView> alphaChips = new java.util.ArrayList<>();
    private TextView paletteArrow;
    private boolean paletteOpen;
    private TextView previewCount;
    private UiKit.Switch placeSwitch;
    private UiKit.Switch codeSwitch;
    /** 下级的整行容器——上级关掉时要整行变淡 */
    private LinearLayout placeRow;
    /** 根布局 / 底部预览区：抓完背景只换这一块，不重建整页 */
    private LinearLayout rootView;
    private View previewDock;
    /** 屏幕像素尺寸（预览框换算要用） */
    private int screenW, screenH;
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
    /** 从「无障碍设置」回来、服务已开启 → 自动补一次重新校准 */
    private boolean recalibrateOnResume = false;
    private static final int REQ_BACKDROP = 1001;

    /**
     * ScrollView 的固定 ID（v4.2.1）。
     *
     * <p>代码里 new 出来的视图默认没有 ID，而 View 的状态保存（包括滚动位置）
     * 只认「有 ID 的视图」—— 没有 ID 就什么都不存，重建后页面回到最顶上。
     * 这一页虽然已经不再用 {@code recreate()} 刷界面了，但旋转、切换系统深色、
     * 改系统字号这些配置变更仍会重建 Activity，给个固定 ID 就能把滚动位置带回来。
     */
    private static final int SCROLL_ID = 0x0A0A0001;

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
        rootView = root;

        ScrollView sv = new ScrollView(this);
        // 有 ID 才会保存/恢复滚动位置（见 SCROLL_ID 的说明）
        sv.setId(SCROLL_ID);
        sv.setFillViewport(true);
        sv.setPadding(dp(18), dp(4), dp(18), dp(6));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        sv.addView(box, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        // v4.1.0：实时预览固定在底部，不参与滚动 —— 调上面任何一项都能立刻看到，
        // 不会因为滚动把预览滚出屏幕。
        previewDock = buildPreviewDock(pl, onDesktop, screenW, screenH);
        root.addView(previewDock);

        // v4.2.1：从长按图标的「添加小部件」快捷方式进来时，直接把添加请求发出去，
        // 用户少点一次。这时也不自动抓背景 —— 两套界面（抓图页 + 系统添加页）会打架。
        boolean autoPin = savedInstanceState == null && getIntent() != null
                && getIntent().getBooleanExtra(WidgetPin.EXTRA_AUTO_PIN, false);
        if (autoPin) {
            root.postDelayed(() -> WidgetPin.quickPin(this), 600);
        } else if (onDesktop && !WidgetBackdrop.exists(this)) {
            // v4.1.0：第一次进这个页面、还没有背景图时，自动抓一次。
            // 不让用户先面对一块灰底发愣，也不用教他再点一次按钮。
            root.postDelayed(this::captureBackdrop, 350);
        }
        // v4.2.1：长按快捷方式判定「没权限」时，由透明中转页把用户送到这里来弹引导。
        // 不在透明页弹 —— 那种窗口上的 Dialog 会点不动也不消失（真机实测）。
        if (savedInstanceState == null && getIntent() != null
                && getIntent().getBooleanExtra(WidgetAddActivity.EXTRA_SHOW_GUIDE, false)) {
            root.postDelayed(() -> WidgetPin.showGuideFor(this), 260);
        }
        box.addView(buildSettingsCard(), UiKit.cardLp(this, 10));

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
        // v4.2.1：和预览下面的「添加到桌面」走同一条路 —— 先真的请求系统添加，
        // 没有权限才给引导。以前这里只弹引导，用户明明有权限也被挡在门外。
        readd.setOnClickListener(v -> WidgetPin.quickPin(this));
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
        // v4.2.1：用户刚从「权限管理 → 桌面快捷方式」回来 —— 直接重试一次添加，
        // 不用他自己再点一遍按钮。只有「是引导弹窗把他送过去的」才会置这个标记。
        if (WidgetPin.consumePermissionRetry()) {
            rootView.postDelayed(() -> WidgetPin.quickPin(this), 400);
        }
        // 刚从「无障碍设置」回来、服务已经开了：自动补一次重新校准，
        // 用户不用再自己找「重新校准」按钮。
        if (recalibrateOnResume && a11yEnabled()) {
            recalibrateOnResume = false;
            // 服务刚开，但这一刻桌面被本页盖着，它还读不到；
            // 这里不自动抓背景（那会按估算位置裁一张错的图），只把下一步说清楚。
            Toast.makeText(this,
                    "已开启。回桌面看一眼再回来，预览就会按实际位置对齐",
                    Toast.LENGTH_LONG).show();
        }
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
     * 选一个颜色（只改预览，确定才落盘）。
     *
     * <p>以前是弹窗，弹窗会把下面的实时预览整块盖住 —— 组件拉到最大时预览本就
     * 只剩一条窄带，再盖住就完全看不到效果了。现在色盘在「配色」下面就地展开，
     * 预览始终露在外面，点一个色就能看见一个色。
     */
    private void pickTempColor(int color) {
        tempPalette = "custom";
        tempColor = color;
        paintColorCells(colorCells, tempColor);
        refreshPreview();        // 只动预览，不落盘
        refreshPaletteTint();    // 配色行的小字和透明度文字跟着变
    }

    private void paintColorCells(java.util.List<View> cells, int selected) {
        for (View v : cells) {
            int color = (int) v.getTag();
            android.graphics.drawable.GradientDrawable d =
                    new android.graphics.drawable.GradientDrawable();
            d.setCornerRadius(dp(10));
            d.setColor(color);
            if (color == selected) d.setStroke(dp(3), P.accentInk);
            else d.setStroke(Math.max(1, dp(1)), 0x22888888);
            v.setBackground(d);
        }
    }

    /** 展开/收起色盘（不弹窗，预览一直在下面露着） */
    private void toggleColorPanel() {
        if (colorPanel == null) return;
        boolean open = colorPanel.getVisibility() != View.VISIBLE;
        if (settingsCard != null) {
            android.transition.TransitionManager.beginDelayedTransition(
                    settingsCard, new android.transition.AutoTransition().setDuration(180));
        }
        colorPanel.setVisibility(open ? View.VISIBLE : View.GONE);
        if (open) {
            // 打开时按「当前自定义色」把色盘画好，但**不**改预览：
            // 用户还没点，没理由让预览先跳到一个他没选的颜色。
            paintColorCells(colorCells, WidgetPrefs.customColor(this));
        } else if (tempPalette != null) {
            tempPalette = null;
            refreshPreview();
            refreshPaletteTint();
        }
    }

    /** 确定：写盘、刷新桌面组件、收起色盘（不重建整页） */
    private void confirmTempColor() {
        if (tempPalette != null) {
            WidgetPrefs.setCustomColor(this, tempColor);
            WidgetPrefs.setPalette(this, "custom");
        }
        tempPalette = null;
        applyToWidget();
        // 收起色盘和配色区 —— 和原来 recreate 之后折叠态一致，也是「选完了」的反馈
        if (colorPanel != null) colorPanel.setVisibility(View.GONE);
        paletteOpen = false;
        if (paletteBox != null) paletteBox.setVisibility(View.GONE);
        refreshPaletteRow();
        refreshPaletteTint();
        refreshPreview();
    }

    /** 七段色阶条：每块直接用该档的底色画出来，点到哪块就是哪档 */
    private View buildAlphaStrip() {
        alphaStrip = new LinearLayout(this);
        alphaStrip.setOrientation(LinearLayout.HORIZONTAL);
        fillAlphaStrip();
        return alphaStrip;
    }

    /**
     * 重画七块色阶（不重建整页）。
     *
     * <p>选中的描边、色块本身的底色、文字色都跟「当前档位 + 深/浅主题 + 配色」有关，
     * 这三样任何一个变了都调这里。以前是整页 {@code recreate()}，页面会闪一下、
     * 滚动位置也会被重置。
     */
    private void fillAlphaStrip() {
        if (alphaStrip == null) return;
        int cur = WidgetPrefs.alphaIndex(this);
        int[] steps = WidgetPrefs.ALPHA_STEPS;

        // 浅色主题下色块是白的，铺在白页面上从实底到全透几乎没差别（真机反馈）。
        // 给整条铺一块**与主题相反**的底色，透明度差异立刻能看出来。
        boolean lightUi = PickupWidgetProvider.isLightTheme(this);
        android.graphics.drawable.GradientDrawable trough =
                new android.graphics.drawable.GradientDrawable();
        trough.setCornerRadius(dp(13));
        trough.setColor(lightUi ? 0xFF3A3A3E : 0xFFE8E8EC);
        alphaStrip.setBackground(trough);
        alphaStrip.setPadding(dp(4), dp(4), dp(4), dp(4));

        alphaStrip.removeAllViews();
        alphaChips.clear();
        for (int i = 0; i < steps.length; i++) {
            boolean on = i == cur;
            // 字色取当前配色的主色（和上面「配色」那行同一个色），
            // 但按各自色块的实际底色提亮/压暗，保证七块都看得清。
            TextView t = UiKit.text(this, WidgetPrefs.ALPHA_NAMES[i], 10.5f,
                    readableOn(paletteTint(), alphaChipBg(steps[i])));
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
            t.setOnClickListener(v -> pickAlpha(idx));
            alphaChips.add(t);
            alphaStrip.addView(t, lp);
        }
    }

    /**
     * 换透明度档位（v4.2.1：就地更新，不重建整页）。
     *
     * <p>原来这里 {@code WidgetPrefs.setAlphaPct → applyToWidget → recreate}，
     * 整页销毁重建一次：预览区会闪、会抖，滚动位置还被重置。
     * 真正需要变的只有「这一条七块 + 预览卡片的背景图」。
     */
    private void pickAlpha(int idx) {
        if (idx < 0 || idx >= WidgetPrefs.ALPHA_STEPS.length) return;
        if (WidgetPrefs.alphaIndex(this) == idx) return;   // 点的是当前档，什么都不用做
        WidgetPrefs.setAlphaPct(this, WidgetPrefs.ALPHA_STEPS[idx]);
        applyToWidget();
        applyPreviewCardBackground();
        if (alphaHeadValue != null) {
            alphaHeadValue.setText(WidgetPrefs.ALPHA_NAMES[WidgetPrefs.alphaIndex(this)]);
        }
        // 这一拍正在分发这条里某个色块的点击事件，重画要延后
        if (alphaStrip != null) alphaStrip.post(this::fillAlphaStrip);
    }

    /** 预览卡片换背景（深/浅 × 七档透明度，全在 drawable 里） */
    private void applyPreviewCardBackground() {
        if (previewCard == null) return;
        previewCard.setBackgroundResource(PickupWidgetProvider.backgroundRes(this));
    }

    /** 色阶块本身的底色：就是该档不透明度叠在主题底色上的样子 */
    private int alphaChipColor(int pct) {
        int a = Math.round(Math.max(0, Math.min(100, pct)) / 100f * 255f);
        return (a << 24) | (PickupWidgetProvider.isLightTheme(this) ? 0xFFFFFF : 0x1B1B1B);
    }

    private int paletteIndex() {
        String p = currentPaletteKey();
        if ("rainbow".equals(p)) return 1;
        if ("sunset".equals(p)) return 2;
        if ("aurora".equals(p)) return 3;
        if ("sakura".equals(p)) return 4;
        if ("custom".equals(p)) return 5;
        return 0;
    }

    private String paletteKey(int idx) {
        switch (idx) {
            case 1: return "rainbow";
            case 2: return "sunset";
            case 3: return "aurora";
            case 4: return "sakura";
            case 5: return "custom";
            default: return "single";
        }
    }

    /** 当前生效的配色名（挑色时用临时值，让预览/文案先跟着走） */
    private String currentPaletteKey() {
        return tempPalette != null ? tempPalette : WidgetPrefs.palette(this);
    }

    private int currentCustomColor() {
        return (tempPalette != null && "custom".equals(tempPalette))
                ? tempColor : WidgetPrefs.customColor(this);
    }

    /**
     * 「配色」行下面那行小字。
     *
     * <p>选了具体颜色之后显示的是**颜色本身**（色点 + 色值），不再是「单色」两个字：
     * 「单色」只说明「只有一种颜色」，不说明是哪一种，用户看不出自己选了什么。
     */
    private String paletteLabel() {
        String key = currentPaletteKey();
        if ("rainbow".equals(key)) return "雨后彩虹";
        if ("sunset".equals(key)) return "夏日夕阳";
        if ("aurora".equals(key)) return "极光夜空";
        if ("sakura".equals(key)) return "樱花初雪";
        if ("custom".equals(key)) {
            return String.format("#%06X", currentCustomColor() & 0xFFFFFF);
        }
        return "单色";
    }

    /** 文案前面加一个该配色的色点 */
    private CharSequence paletteSubText() {
        int[] pal = PickupWidgetProvider.rampOf(currentPaletteKey(), currentCustomColor());
        int dot = (pal != null && pal.length > 0) ? (pal[0] | 0xFF000000) : fg(0xFF);
        android.text.SpannableString sp =
                new android.text.SpannableString("■ " + paletteLabel());
        sp.setSpan(new android.text.style.ForegroundColorSpan(dot), 0, 1,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sp;
    }

    /** row(...) 里那一列：0=标题，1=副标题 */
    private TextView findRowSubText(LinearLayout row) {
        for (int i = 0; i < row.getChildCount(); i++) {
            View v = row.getChildAt(i);
            if (!(v instanceof LinearLayout)) continue;
            LinearLayout col = (LinearLayout) v;
            if (col.getChildCount() >= 2 && col.getChildAt(1) instanceof TextView) {
                return (TextView) col.getChildAt(1);
            }
        }
        return null;
    }

    /** 配色主色（单色时就是主题文字色） */
    private int paletteTint() {
        int[] pal = PickupWidgetProvider.rampOf(currentPaletteKey(), currentCustomColor());
        if (pal != null && pal.length > 0) return pal[0] | 0xFF000000;
        return PickupWidgetProvider.isLightTheme(this) ? 0xFF1C1C1E : 0xFFFFFFFF;
    }

    /** 配色/临时色一变，配色行小字和透明度七块的字色一起跟着变 */
    private void refreshPaletteTint() {
        if (paletteValueText != null) paletteValueText.setText(paletteSubText());
        int[] steps = WidgetPrefs.ALPHA_STEPS;
        for (int i = 0; i < alphaChips.size() && i < steps.length; i++) {
            alphaChips.get(i).setTextColor(readableOn(paletteTint(), alphaChipBg(steps[i])));
        }
    }

    /** 某档透明度色块叠在底衬上的实际颜色 */
    private int alphaChipBg(int pct) {
        boolean light = PickupWidgetProvider.isLightTheme(this);
        int base = light ? 0xFFFFFF : 0x1B1B1B;
        int trough = light ? 0xFF3A3A3E : 0xFFE8E8EC;
        float a = Math.max(0, Math.min(100, pct)) / 100f;
        int r = Math.round(((base >> 16) & 0xFF) * a + ((trough >> 16) & 0xFF) * (1 - a));
        int g = Math.round(((base >> 8) & 0xFF) * a + ((trough >> 8) & 0xFF) * (1 - a));
        int bl = Math.round((base & 0xFF) * a + (trough & 0xFF) * (1 - a));
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /**
     * 让文字色在给定底色上看得清：色相不变，只调明度。
     *
     * <p>不直接写死黑/白 —— 那样「文字跟着配色走」就断了；
     * 也不原样用 —— 浅色板（樱花、白色单色）压在浅底上会看不见。
     */
    private static int readableOn(int fg, int bg) {
        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(fg | 0xFF000000, hsv);
        int out = fg | 0xFF000000;
        boolean darker = relLum(bg) > 0.45f;
        for (int i = 0; i < 18 && contrast(out, bg) < 2.6f; i++) {
            hsv[2] = Math.max(0.05f, Math.min(1f, hsv[2] + (darker ? -0.06f : 0.06f)));
            out = android.graphics.Color.HSVToColor(hsv);
        }
        return out;
    }

    private static float relLum(int c) {
        float[] f = new float[3];
        for (int i = 0; i < 3; i++) {
            float v = ((c >> (16 - i * 8)) & 0xFF) / 255f;
            f[i] = v <= 0.03928f ? v / 12.92f : (float) Math.pow((v + 0.055f) / 1.055f, 2.4f);
        }
        return 0.2126f * f[0] + 0.7152f * f[1] + 0.0722f * f[2];
    }

    private static float contrast(int a, int b) {
        float la = relLum(a) + 0.05f;
        float lb = relLum(b) + 0.05f;
        return Math.max(la, lb) / Math.min(la, lb);
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
        // 这里以前会弹「正在重新校准…」的 Toast：它正好停在屏幕下方，
        // 450ms 后的截图会把它一起拍进背景图，预览底部就多出一条灰条。
        // 抓取页本身会闪一下，够当反馈了，不再用 Toast。

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
                    captureBackdrop();
                    return;
                }
                // 把这次算出的矩形直接带给抓取页，避免它再读一次缓存读到别的值
                WidgetBackdrop.setPending(got);

                boolean exact = got.fromLauncher || got.fromCalib;
                if (!exact && a11yEnabled()) {
                    // 服务开着，但这一刻桌面不在前台、还没读到 → 回桌面停一下就好
                    Toast.makeText(this,
                            "位置还是估算的：回桌面停一秒再进来，就能对上",
                            Toast.LENGTH_LONG).show();
                    captureBackdrop();
                    return;
                }
                if (!exact) {
                    // 没有标定、服务也关着：这才是真的需要用户开权限的场景。
                    // 不直接抓背景 —— 先让他决定，免得抓完再弹窗、页面又被重建。
                    realigning = false;
                    showA11yDialog(() -> {
                        realigning = true;
                        captureBackdrop();
                    });
                    return;
                }
                // 走到这里交给抓取页；回来后整页 recreate，realigning 自然复位
                captureBackdrop();
            });
        }, "realign").start();
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
     * 设置卡片（v4.2）：所有「组件长什么样」的选项合并成一张。
     *
     * <p>之前是「外观 / 字号 / 显示 / 显示内容」四张卡片，每张都带自己的标题、
     * 内边距和卡片间距，一屏里四个块把页面撑得很长；而它们本质上都在调同一件事。
     * 现在用细分割线分段，一张卡片到底，页面上只留「设置」和「预览」两个区域。
     */
    private View buildSettingsCard() {
        LinearLayout card = UiKit.card(this, P);
        card.setPadding(dp(16), dp(12), dp(16), dp(14));
        settingsCard = card;

        // ---- 配色：点一下就地展开，不弹窗 ----
        LinearLayout paletteHead = UiKit.row(this, P, R.drawable.ic_palette, "配色",
                paletteSubText(), UiKit.chevron(this, P));
        paletteValueText = findRowSubText(paletteHead);
        paletteHead.setOnClickListener(v -> togglePalette());
        card.addView(paletteHead);

        paletteBox = new LinearLayout(this);
        paletteBox.setOrientation(LinearLayout.VERTICAL);
        paletteBox.setVisibility(View.GONE);
        paletteBox.addView(buildPaletteChips());
        card.addView(paletteBox);

        // ---- 组件背景色（原来是「主题」，放在透明度上面）----
        card.addView(UiKit.divider(this, P));
        card.addView(sectionHead("组件背景色", null));
        bgSeg = new LinearLayout(this);
        bgSeg.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.setMargins(0, 0, 0, dp(4));
        card.addView(bgSeg, blp);

        // ---- 透明度 ----
        card.addView(UiKit.divider(this, P));
        LinearLayout alphaHead = (LinearLayout) sectionHead("透明度",
                WidgetPrefs.ALPHA_NAMES[WidgetPrefs.alphaIndex(this)]);
        // 右边那个「实底/微透…」要能就地改字（以前靠 recreate 重画）
        if (alphaHead.getChildCount() > 1
                && alphaHead.getChildAt(1) instanceof TextView) {
            alphaHeadValue = (TextView) alphaHead.getChildAt(1);
        }
        card.addView(alphaHead);
        card.addView(buildAlphaStrip());

        // ---- 字号 ----
        card.addView(UiKit.divider(this, P));
        card.addView(sectionHead("字号", "码和地点一起缩放"));
        fontSeg = new LinearLayout(this);
        fontSeg.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.setMargins(0, 0, 0, dp(4));
        card.addView(fontSeg, flp);

        // ---- 显示 ----
        card.addView(UiKit.divider(this, P));
        codeSwitch = new UiKit.Switch(this, P, WidgetPrefs.showCode(this));
        codeSwitch.setOnToggle(on -> {
            WidgetPrefs.setShowCode(this, on);
            refreshSubordinateRows();
            applyToWidget();
            refreshPreview();
        });
        card.addView(UiKit.row(this, P, R.drawable.ic_qr, "显示取件码",
                "关掉后只显示件数", codeSwitch));

        card.addView(UiKit.divider(this, P));
        placeSwitch = new UiKit.Switch(this, P, WidgetPrefs.showPlace(this));
        placeSwitch.setOnToggle(on -> {
            if (!WidgetPrefs.showCode(this)) return;
            WidgetPrefs.setShowPlace(this, on);
            applyToWidget();
            refreshPreview();
        });
        placeRow = UiKit.row(this, P, R.drawable.ic_pin, "显示取件地点",
                "关掉后只显示大号取件码", placeSwitch);
        card.addView(placeRow);
        refreshSubordinateRows();

        // ---- 显示内容 ----
        card.addView(UiKit.divider(this, P));
        card.addView(sectionHead("显示内容", "条数由组件高度决定"));
        rowsSeg = new LinearLayout(this);
        rowsSeg.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.setMargins(0, 0, 0, dp(4));
        card.addView(rowsSeg, rlp);

        return card;
    }

    /** 展开/收起配色区，带一次高度过渡，不做成弹窗 */
    private void togglePalette() {
        paletteOpen = !paletteOpen;
        if (settingsCard != null) {
            android.transition.TransitionManager.beginDelayedTransition(
                    settingsCard, new android.transition.AutoTransition()
                            .setDuration(180));
        }
        paletteBox.setVisibility(paletteOpen ? View.VISIBLE : View.GONE);
        if (!paletteOpen) {
            if (colorPanel != null) colorPanel.setVisibility(View.GONE);
            if (tempPalette != null) {
                tempPalette = null;
                refreshPreview();
                refreshPaletteTint();
            }
        }
    }

    /**
     * 配色按钮行：单色（含自定义）+ 四套彩色。
     *
     * <p>一行五个太挤时按权重让「单色」窄一点（它只有两个字），
     * 四套彩色各占一份 —— 4 个字刚好放得下。
     */
    private View buildPaletteChips() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        paletteRow = new LinearLayout(this);
        paletteRow.setOrientation(LinearLayout.HORIZONTAL);
        paletteRow.setPadding(0, dp(2), 0, dp(8));
        fillPaletteRow();
        box.addView(paletteRow);

        box.addView(buildColorPanel());
        return box;
    }

    /** 铺满配色按钮行（可重复调用：选中态变了只重画这一行） */
    private void fillPaletteRow() {
        if (paletteRow == null) return;
        paletteRow.removeAllViews();
        String cur = currentPaletteKey();
        boolean single = "single".equals(cur) || "custom".equals(cur);
        addPaletteChip(paletteRow, "单色", null, single, 0.72f, this::toggleColorPanel);
        addPaletteChip(paletteRow, "雨后彩虹", new int[]{0xFFFF9EC4, 0xFFC6A8FF},
                "rainbow".equals(cur), 1f, () -> selectPalette("rainbow"));
        addPaletteChip(paletteRow, "夏日夕阳", new int[]{0xFFFFB067, 0xFFFFD54A},
                "sunset".equals(cur), 1f, () -> selectPalette("sunset"));
        addPaletteChip(paletteRow, "极光夜空", new int[]{0xFF7BE0A6, 0xFFC9A2FF},
                "aurora".equals(cur), 1f, () -> selectPalette("aurora"));
        addPaletteChip(paletteRow, "樱花初雪", new int[]{0xFFFFC2D8, 0xFFDCC6EC},
                "sakura".equals(cur), 1f, () -> selectPalette("sakura"));
    }

    /**
     * 重画配色按钮行（延后一拍）。
     *
     * <p>调用点常常正处在「这一行里某个按钮的点击回调」中，
     * 直接 removeAllViews 会把正在分发事件的视图摘掉。等这一轮分发完再动。
     */
    private void refreshPaletteRow() {
        if (paletteRow == null) return;
        paletteRow.post(this::fillPaletteRow);
    }

    /**
     * 页内色盘（点「单色」时从按钮行下面展开）。
     *
     * <p>不做弹窗：弹窗正好压在实时预览上，把「边调边看」这件事废掉了。
     * 展开在页面里，预览始终在下面露着，选一个色就能看到预览跟着变。
     */
    private View buildColorPanel() {
        colorPanel = new LinearLayout(this);
        colorPanel.setOrientation(LinearLayout.VERTICAL);
        colorPanel.setVisibility(View.GONE);

        TextView hint = UiKit.text(this, "点一个颜色先看效果，确定后才会保存", 11.5f, P.muteInk);
        hint.setPadding(dp(2), 0, dp(2), dp(7));
        colorPanel.addView(hint);

        final int[] colors = {
                0xFFFFFFFF, 0xFFE5E5EA, 0xFFC7C7CC, 0xFF8E8E93, 0xFF3A3A3C, 0xFF1C1C1E,
                0xFFFFC2D8, 0xFFFF9EC4, 0xFFFF8A80, 0xFFFF6B6B, 0xFFE64980, 0xFFBE4BDB,
                0xFFFFD98E, 0xFFFFB067, 0xFFFFD54A, 0xFF9BE7C4, 0xFF51CF66, 0xFF20C997,
                0xFF8FC7FF, 0xFF4DABF7, 0xFF5C7CFA, 0xFFC6A8FF, 0xFF845EF7, 0xFFA9A2FF,
        };
        colorCells.clear();
        for (int r = 0; r < 4; r++) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < 6; c++) {
                final int color = colors[r * 6 + c];
                View cell = new View(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(34), 1f);
                lp.setMargins(dp(3), dp(3), dp(3), dp(3));
                cell.setTag(color);
                colorCells.add(cell);
                cell.setOnClickListener(v -> pickTempColor(color));
                line.addView(cell, lp);
            }
            colorPanel.addView(line);
        }
        paintColorCells(colorCells, WidgetPrefs.customColor(this));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);
        actions.setPadding(0, dp(10), 0, dp(2));

        TextView cancel = UiKit.text(this, "取消", 13f, P.muteInk);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(dp(20), dp(9), dp(20), dp(9));
        cancel.setBackground(UiKit.bg(P.chipBg, this, 10));
        cancel.setOnClickListener(v -> toggleColorPanel());

        TextView ok = UiKit.text(this, "确定", 13f, P.onAccent);
        ok.setGravity(Gravity.CENTER);
        ok.setPadding(dp(26), dp(9), dp(26), dp(9));
        ok.setBackground(UiKit.bg(P.accent, this, 10));
        ok.setOnClickListener(v -> confirmTempColor());

        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(0, 0, dp(8), 0);
        actions.addView(cancel, clp);
        actions.addView(ok);
        colorPanel.addView(actions);

        return colorPanel;
    }

    private void addPaletteChip(LinearLayout row, String label, int[] gradient,
                                boolean selected, float weight, Runnable onClick) {
        LinearLayout chip = new LinearLayout(this);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(0, dp(11), 0, dp(11));

        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(11));
        int labelColor;
        if (gradient == null) {
            bg.setColor(0x11000000);
            bg.setStroke(Math.max(1, dp(1)), 0x22888888);
            labelColor = P.navInk;
        } else {
            bg.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT);
            bg.setColors(gradient);
            labelColor = 0xFFFFFFFF;
        }
        if (selected) bg.setStroke(dp(2), P.accentInk);
        chip.setBackground(bg);

        TextView t = UiKit.text(this, label, 10f, labelColor);
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(1);
        if (gradient != null) {
            t.setShadowLayer(Math.max(2f, dp(2)), 0, dp(1), 0x99000000);
        }
        chip.addView(t);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        lp.setMargins(dp(1.5f), 0, dp(1.5f), 0);
        chip.setOnClickListener(v -> onClick.run());
        row.addView(chip, lp);
    }

    /**
     * 四套预设：点一下直接生效。
     *
     * <p>v4.2.1：不再 {@code recreate()}。整页重建会让预览闪一下、滚动位置回顶，
     * 而实际要变的只有「配色行的小字 + 配色按钮的选中态 + 透明度七块的字色 + 预览行」。
     */
    private void selectPalette(String key) {
        WidgetPrefs.setPalette(this, key);
        applyToWidget();
        // 之前可能正停在「挑自定义色」的临时态上：这次选了预设，临时值要作废，
        // 否则 currentPaletteKey() 还返回临时色，预览会继续画那个颜色。
        tempPalette = null;
        if (colorPanel != null) colorPanel.setVisibility(View.GONE);
        refreshPaletteRow();
        refreshPaletteTint();
        refreshPreview();
    }

    /** 分段小标题：左标题、右当前值（都为正文色，靠字号区分层级） */
    private View sectionHead(String title, String value) {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setPadding(0, dp(10), 0, dp(8));
        h.addView(UiKit.text(this, title, 14f, P.navInk),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (value != null) {
            h.addView(UiKit.text(this, value, 12f, P.muteInk));
        }
        return h;
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
    /**
     * 预览用的行颜色：优先取「正在挑但还没确定」的配色。
     *
     * <p>和 {@code PickupWidgetProvider.rowColor} 同一套规则，区别只在于数据源。
     */
    private int previewRowColor(int index, int alpha) {
        String key = tempPalette != null ? tempPalette : WidgetPrefs.palette(this);
        int custom = tempPalette != null && "custom".equals(tempPalette)
                ? tempColor : WidgetPrefs.customColor(this);
        int[] pal = PickupWidgetProvider.rampOf(key, custom);
        int rgb;
        if (pal == null) {
            rgb = PickupWidgetProvider.isLightTheme(this) ? 0x1C1C1E : 0xFFFFFF;
        } else {
            rgb = pal[((index % pal.length) + pal.length) % pal.length];
        }
        return (alpha << 24) | (rgb & 0xFFFFFF);
    }

    private View buildPreviewDock(WidgetPlacement pl, boolean onDesktop,
                                  int screenW, int screenH) {

        LinearLayout dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.VERTICAL);
        dock.setBackgroundColor(P.bg);
        dock.setPadding(dp(18), dp(8), dp(18), dp(20));

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
        if (!exact && onDesktop) {
            // 估算态给一个明确的出口：点这行能看到「为什么不准、怎么变准」
            title.setText("小组件实时预览 · 按格子估算（点此修正）");
            title.setOnClickListener(v -> showA11yDialog());
        }
        head.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView action = UiKit.text(this, onDesktop ? "重新校准" : "添加到桌面",
                12.5f, P.accentInk);
        action.setPadding(dp(14), dp(10), dp(2), dp(10));
        if (onDesktop) {
            action.setOnClickListener(v -> realign());
        } else {
            // v4.2.1：先真的发一次「放到桌面」请求（HyperOS 常常静默吞掉，
            // 所以 WidgetPin 会带回调 + 超时，失败再给手动步骤）
            action.setOnClickListener(v -> WidgetPin.quickPin(this));
        }
        head.addView(action);

        // 没组件时不读旧图：那可能是上一个组件留下的，容易误导。
        android.graphics.Bitmap bg = onDesktop ? WidgetBackdrop.load(this) : null;

        // 预览要展示的是「整块格子」——桌面上拖尺寸时那个蓝框，
        // 不是卡片本身。只展示卡片会把四角圆边和周围留白切掉，
        // 看着像一张贴纸，而不是它在桌面上的样子。
        float[] frame = pl != null ? pl.previewRatios(this) : null;
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
        // 0.40 时预览框底部会顶到系统手势导航栏下面，被遮出一条灰边。
        // 0.32 让出导航栏和「重新校准」那一行的空间，底部不再贴边。
        int maxH = (int) (screenH * 0.32f);
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

        // 卡片在框内按「四边等距」内缩：框本身就是卡片 ± 一圈壁纸生成的，
        // 所以上下左右留白一致，也不会把卡片底边裁掉（之前是画到框外被切）。
        float[] inset = pl != null ? pl.previewInset(this) : new float[]{0, 0, 0, 0};
        int cardL = Math.round(inset[0] * boxW);
        int cardT = Math.round(inset[1] * boxH);
        int cardR = Math.round(inset[2] * boxW);
        int cardB = Math.round(inset[3] * boxH);
        int cardW = Math.max(dp(60), boxW - cardL - cardR);
        int cardH = Math.max(dp(40), boxH - cardT - cardB);

        // 内容按「真实卡片宽度 → 预览卡片宽度」等比缩。
        // 用卡片自身宽度，不用框宽：框里含四周留白，拿它当基准会整体偏小。
        float realCardW = pl != null
                ? Math.max(1f, (pl.right - pl.left) * screenW)
                : Math.max(1f, frameW);
        float k = cardW / realCardW;
        k = Math.max(0.2f, Math.min(1.4f, k));

        // 圆角跟真实卡片一致（drawable 里是 20dp）
        final float corner = Math.max(4f, dp(20) * k);
        android.widget.FrameLayout card = new android.widget.FrameLayout(this);
        card.setBackgroundResource(PickupWidgetProvider.backgroundRes(this));
        previewCard = card;   // 主题/透明度变了就地换背景，不用重建整页
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
        applyDockInsets(dock);
        return dock;
    }

    /**
     * 底部让出系统手势条 / 导航栏。
     *
     * <p>本页是 edge-to-edge 的（内容画到系统栏下面），不处理 insets 的话
     * 预览框会一路画到导航条底下，真机上就是「预览最下面还有一条灰带」——
     * 那其实是系统手势条压在上面。这里按 insets 加内边距，取不到时退一个固定值。
     *
     * <p>v4.2.1：改成**建视图时同步算好**。以前整段放在 {@code post()} 里 ——
     * 第一帧用 20dp、第二帧才换成 nav+10dp，dock 高度一变，上面的预览就往上跳一下，
     * 这就是点按钮时看到的「抖」。
     */
    private void applyDockInsets(View dock) {
        int nav = navBarHeightPx();
        int bottom = Math.max(dp(20), nav > 0 ? nav + dp(10) : dp(30));
        if (dock.getPaddingBottom() != bottom) {
            dock.setPadding(dock.getPaddingLeft(), dock.getPaddingTop(),
                    dock.getPaddingRight(), bottom);
        }
    }

    /** 手势条 / 导航栏高度（px）；拿不到返回 0 */
    private int navBarHeightPx() {
        int nav = 0;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.view.WindowInsets in = getWindowManager()
                        .getCurrentWindowMetrics().getWindowInsets();
                if (in != null) {
                    nav = in.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom;
                }
            }
        } catch (Throwable ignored) { }
        if (nav == 0) {
            try {
                int id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
                if (id > 0) nav = getResources().getDimensionPixelSize(id);
            } catch (Throwable ignored) { }
        }
        return nav;
    }

    /**
     * 什么时候真的需要「桌面位置读取」。
     *
     * <p>标定（格子尺寸、网格原点、卡片留白）是一次性算出来存本地的，
     * 之后只要有格子就能换算像素 —— 所以关掉服务，挪动/缩放依然算得准。
     * 真正需要它的只有三种时候：
     * ① 还没建立过标定（新装、清过数据）；
     * ② 换了主题/字体/桌面网格，旧标定失效；
     * ③ 没有 root、读不到桌面库。
     * 所以提示只在「现在是估算」时出现，精确态不打扰。
     */
    private void showA11yDialog() {
        showA11yDialog(null);
    }

    /**
     * @param onUseEstimate 点「先用估算」后要做的事（重新校准流程里是继续抓背景）；
     *                      为 null 表示只是解释一下
     */
    private void showA11yDialog(Runnable onUseEstimate) {
        if (isFinishing() || isDestroyed()) return;

        if (a11yEnabled()) {
            // 已经开了、只是服务还没读到桌面：这时候再引导去开权限只会让人困惑
            UiKit.dialog(this, P, R.drawable.ic_pin, "已经开启，还差一步",
                    "「取件码助手 - 桌面位置读取」是开着的，但刚才桌面被这个页面盖住，"
                            + "服务读不到它。\n\n"
                            + "回桌面看一眼再回来（或点右上角「重新校准」前先回一次桌面），"
                            + "预览就会按实际位置对齐。",
                    (View) null,
                    UiKit.primary("知道了", () -> { }));
            return;
        }

        UiKit.dialog(this, P, R.drawable.ic_pin, "让预览和桌面完全对齐",
                "现在的位置是按桌面网格估算的，可能偏几十像素。\n\n"
                        + "「取件码助手 - 桌面位置读取」是系统的无障碍服务，只读桌面窗口里"
                        + "我们自己这个组件的位置和尺寸，不读其它应用的内容，也不替你执行任何操作。\n\n"
                        + "开启后按桌面实际位置对齐；不开启也能用 —— 已经存下的网格参数仍然有效，"
                        + "但换主题、改桌面网格之后会失准。",
                (View) null,
                UiKit.ghost("先用估算", () -> {
                    if (onUseEstimate != null) onUseEstimate.run();
                }),
                UiKit.primary("去开启", () -> {
                    recalibrateOnResume = true;
                    openA11ySettings();
                }));
    }

    private void openA11ySettings() {
        try {
            startActivity(new android.content.Intent(
                    android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Throwable t) {
            Toast.makeText(this, "打不开系统设置，请到「设置 → 无障碍」里手动开启",
                    Toast.LENGTH_LONG).show();
        }
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
            // v4.2.1：以前靠整页 recreate 把这个标志位「自然复位」，
            // 现在不重建页面了，得手动清 —— 否则「重新校准」点过一次就再用不了，
            // onResume 里的位置检查也会被它一直挡住。
            realigning = false;
            if (resultCode == RESULT_OK) rebuildPreviewDock();
            else Toast.makeText(this,
                    "没抓到背景（可能需要 root 授权），可以再试一次", Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 只重画底部预览区（v4.2.1）。
     *
     * <p>抓完背景回来要换图、可能还要换宽高比，以前是整页 {@code recreate()}：
     * 设置卡片会重新构建、滚动位置也回到顶上。其实上面那块完全没变，
     * 换掉 dock 一块就够。
     */
    private void rebuildPreviewDock() {
        if (rootView == null || previewDock == null) return;
        int index = rootView.indexOfChild(previewDock);
        if (index < 0) return;

        boolean onDesktop = WidgetPlacement.hasWidget(this);
        previewOnDesktop = onDesktop;
        WidgetPlacement pl = onDesktop ? WidgetPlacement.cached(this) : null;
        if (pl == null) pl = WidgetPlacement.fallback(this);
        placement = pl;

        rootView.removeViewAt(index);
        previewDock = buildPreviewDock(pl, onDesktop, screenW, screenH);
        rootView.addView(previewDock, index);
        refreshPreview();
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
                previewRowColor(index, picked ? 0x99 : 0xFF));
        code.setText(codeLabel);   // 保留 Html 的删除线 span
        code.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        if (!showCode) code.setText("");
        row.addView(code);

        // 以前这里写死白色：浅色主题下地点直接看不见（真机上发现）
        // v4.2.1：已取件的地点也要划掉——真实组件里就是「码 + 地址」整段划掉
        // （PickupWidgetProvider 那边早就这么做了），预览只划了码，两边对不上。
        TextView place = UiKit.text(this, "", 11f * k,
                previewRowColor(index, picked ? 0x80 : 0xB3));
        place.setText(picked
                ? PickupWidgetProvider.strikeText(placeText) : placeText);
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
        if (bgSeg != null) {
            bgSeg.removeAllViews();
            String t = WidgetPrefs.theme(this);
            addSeg(bgSeg, "深色", "dark".equals(t), () -> pickTheme("dark"));
            addSeg(bgSeg, "浅色", "light".equals(t), () -> pickTheme("light"));
            addSeg(bgSeg, "跟随系统", "system".equals(t), () -> pickTheme("system"));
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

    /**
     * 换组件背景色（深色 / 浅色 / 跟随系统）—— 就地更新，不重建整页（v4.2.1）。
     *
     * <p>原来这里 {@code setTheme} 之后直接 {@code recreate()}：整页销毁重建，
     * 预览要重新解一次背景图、dock 的底边距还要等一帧才补上，
     * 表现就是「点一下，下面闪一下、抖一下，页面还跳回最顶上」。
     *
     * <p>其实跟主题有关的只有四处：分段按钮的选中态、透明度七块的底色
     * （浅色底 vs 深色底）、预览卡片的背景 drawable、预览里的文字色。
     * 逐个就地换掉，页面的滚动位置和预览图都不用动。
     */
    private void pickTheme(String key) {
        if (key == null || key.equals(WidgetPrefs.theme(this))) return;
        WidgetPrefs.setTheme(this, key);
        applyToWidget();
        fillAlphaStrip();          // 七块色阶的底色/字色跟着深浅走
        applyPreviewCardBackground();
        refreshPreview();          // 预览行按新主题重新取色
        refreshPaletteTint();      // 「配色」小字里的色点（单色时取主题文字色）
        // 这一拍正在分发 bgSeg 里某个按钮的点击事件，重建这一段要延后
        if (bgSeg != null) bgSeg.post(this::rebuildSegments);
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
        previewTitle.setTextColor(previewRowColor(0, 0xFF));
        previewCount.setTextColor(previewRowColor(0, 0xB3));
        previewTitle.setText(showAll ? "取件记录" : "待取件");
        previewCount.setText(showAll ? "2 待取 · 1 已取" : "2 个待取");

        previewRows.removeAllViews();
        // v4.0.2 第四轮：隐私模式（关掉「显示取件码」）与真实组件保持一致——
        // 不逐条显示，码/地点/✓ 全部取消，改在展示区直白给出汇总。
        if (!showCode) {
            previewCount.setText("");
            TextView sum = UiKit.text(this,
                    showAll ? "2 个待取 · 1 个已取" : "2 个待取",
                    16f * previewScale, previewRowColor(0, 0xFF));
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
