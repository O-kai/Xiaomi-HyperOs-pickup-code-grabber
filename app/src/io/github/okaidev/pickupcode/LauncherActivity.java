package io.github.okaidev.pickupcode;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 主界面（v4.0.0 视觉重构，按 prototype/index.html 落地）
 *
 * 结构：固定头部（标题 + 版本副标题）+ 内容区 + 悬浮磨砂底栏（首页 / 应用 / 规则 / 设置）。
 *  - 首页：状态卡（体检结论）→ 双统计卡 → 项目版本信息 → 是否有新版本 → 反馈入口
 *  - 应用：通知来源 6 个 App（官方图标）+ 自定义来源 + 写入目标 / 环境适配下拉
 *  - 规则：官方规则集 / 我的规则 / 黑名单（可展开编辑）/ 工具（链路测试、错题本、排查、诊断）
 *  - 设置：提取与写入（写入目标、待办模板、通知取件、冻结免疫直写）/ 个性化（外观、模糊效果、语言）/ 常规 / 帮助与关于
 *
 * 交互约定：所有编辑类操作都在页面内「就地展开」（不用 AlertDialog 承载 EditText——
 * 历史实测部分 ROM 上 dialog window 会吞掉触摸事件），选择类操作用系统单选弹窗。
 */
public class LauncherActivity extends Activity {

    private static final String PREFS = "dedup";
    private static final String KEY_BACKEND = "backend_override";
    private static final String KEY_SYS_DIRECT = "sys_direct_write";
    private static final String REPO_URL = "https://github.com/O-kai/Xiaomi-HyperOs-pickup-code-grabber";
    private static final String ISSUES_URL = REPO_URL + "/issues";
    private static final String COOLAPK_URL = "https://www.coolapk.com/feed/73558591";
    private static final String CHANGELOG_URL = REPO_URL + "/blob/main/CHANGELOG.md";
    /** 黑名单默认关键词（恢复默认时回填用） */
    private static final String BLACKLIST_DEFAULT = "12306,验证码,余额,充值,账单,银行,优惠券,退订";
    /** 来源识别覆盖的快递公司数（菜鸟 / 丰巢 / 京东 / 顺丰 / 中通 / 圆通 / 韵达 / 申通 / 邮政） */
    private static final int IDENTIFY_SOURCES = 9;
    private static final String TPL_DEFAULT = "取件码 {code}｜{source}｜{place}｜{time}";
    /** 缺作用域时的统一提示文案（Toast 与开关态共用一份，避免两处说法不一致） */
    private static final String NOTI_SCOPE_TOAST =
            "开关虽已打开，但 LSPosed 作用域没勾「Android 系统（android）」，\n"
            + "这种状态下通知取件【不会生效】。\n"
            + "请到：LSPosed → 模块 → 取件码助手 → 作用域勾选「Android 系统」\n"
            + "（列表底部、不带推荐角标的那一项），再重启手机。";
    /** 作用域校验节流窗口：同一次打开里 onResume 可能连着触发多次，不必每次都 su+sqlite3 */
    private static final long NOTI_SCOPE_CHECK_INTERVAL = 60_000L;

    // ==================== 界面骨架 ====================
    private UiKit.Palette P;
    private int currentTab = 0;
    private final View[] tabViews = new View[4];
    private LinearLayout contentHost;
    private FrameLayout contentArea;
    private LinearLayout navHolder;
    private TextView headerTitle, headerSub;

    // ==================== 首页 ====================
    private LinearLayout heroCard;
    private ImageView heroIcon;
    private TextView heroTitle, heroSub;
    private LinearLayout checklistCard;
    private LinearLayout checklistBox;
    private TextView deployBtn, troubleshootBtn;

    // ==================== 应用 ====================
    private TextView backendValue, envValue, extraSummary;
    private LinearLayout extraBox;

    // ==================== 规则 ====================
    private TextView urSubView, urBadgeView;
    private TextView blPreview;
    private LinearLayout blActionRow, blBtnRow;
    private EditText blEdit;

    // ==================== 设置 ====================
    private UiKit.Switch notiSw, sdwSw, blurSw, netSw;
    private TextView notiScopeHintView;
    private LinearLayout sdwRow;
    private TextView tplValueView, tplPreviewText;
    private LinearLayout tplPreview;          // 预览容器：标签 + 矢量图标 + 文本
    private ImageView tplPreviewIcon;
    private LinearLayout tplEditorBox, tplBtnRow;
    private EditText tplEdit;
    private TextView tplEditBtn;
    private final List<TextView> modeBtns = new ArrayList<>(3);
    private final int[] modeIds = { TodoWriter.MODE_MIN, TodoWriter.MODE_FULL, TodoWriter.MODE_CUSTOM };
    private int currentMode = TodoWriter.MODE_FULL;

    // ==================== 运行状态 ====================
    private Boolean notiScopeBlocked = null;
    private boolean notiSwitchOn = false;
    private long lastNotiScopeCheck = 0L;
    private boolean healthAllOk = false;

    // ================================================================
    //  创建
    // ================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        P = UiKit.palette(this);
        currentMode = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("todo_mode", TodoWriter.MODE_FULL);
        if (savedInstanceState != null) currentTab = savedInstanceState.getInt("tab", 0);

        PickupExtractor.init(this);
        UpdateCenter.maybeCheck(this);
        ProcessSync.pushThrottled(this);
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1001);
        }

        // 页面骨架：头部（固定）+ 内容区（可滚动，随 Tab 切换）+ 底栏
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(P.bg);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(20), dp(14), dp(20), dp(6));
        headerTitle = UiKit.text(this, "取件码助手", 26f, UiKit.INK);
        headerTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.addView(headerTitle);
        headerSub = UiKit.text(this, "", 12.5f, UiKit.INK3);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.setMargins(0, dp(5), 0, 0);
        header.addView(headerSub, subLp);
        page.addView(header);

        // v4.0.4：底栏改为【悬浮在内容之上】——用 FrameLayout 叠加，内容从底栏下方滚过。
        // 之前把底栏当作内容区下方的独立一行，导致内容被压短、底栏上方多出一条空白。
        contentArea = new FrameLayout(this);
        contentHost = new LinearLayout(this);
        contentHost.setOrientation(LinearLayout.VERTICAL);
        contentArea.addView(contentHost, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 底部渐隐遮罩：底栏所在的整段先铺满页面底色（完全不透），再往上柔和过渡到透明——
        // 既保证半透明底栏里不出现卡片文字残影，又保留"内容从底栏下方滚过"的悬浮观感
        View scrim = new View(this);
        GradientDrawable scrimBg = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{ P.bg, P.bg, P.bg & 0x00FFFFFF });
        // 底栏【下方】留一条实底（约 34dp），底栏覆盖区只轻微淡化（55%）——
        // 这样 85% 半透明的底栏才能真正透出下面滚过的内容，同时避免文字硬碰硬
        scrimBg.setColors(new int[]{ P.bg, UiKit.alpha(P.bg, 0.55f), P.bg & 0x00FFFFFF },
                new float[]{ 0f, 0.18f, 1f });
        scrim.setBackground(scrimBg);
        FrameLayout.LayoutParams scrimLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(188));
        scrimLp.gravity = Gravity.BOTTOM;
        contentArea.addView(scrim, scrimLp);

        navHolder = new LinearLayout(this);
        navHolder.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams navLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        navLp.gravity = Gravity.BOTTOM;
        contentArea.addView(navHolder, navLp);   // 后加 = 在遮罩之上

        page.addView(contentArea, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(page);

        // 四个 Tab 只构建一次：切换时换子视图，控件引用始终有效（体检刷新等后台回调不会打到废弃视图上）
        tabViews[0] = buildHomeTab();
        tabViews[1] = buildAppsTab();
        tabViews[2] = buildRulesTab();
        tabViews[3] = buildSettingsTab();

        showTab(currentTab);
        refreshHealth();
        refreshUserRuleCard();
        renderNotiSwitchUi();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("tab", currentTab);
    }

    /** 切换 Tab：换内容 + 重建底栏选中态 + 更新头部标题 */
    private void showTab(int index) {
        currentTab = index;
        contentHost.removeAllViews();
        contentHost.addView(tabViews[index], new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        String[] titles = { "取件码助手", "应用", "规则", "设置" };
        headerTitle.setText(titles[index]);
        headerSub.setText(appVersionLabel() + " · LSPosed 模块 · 自动提取取件码写入 " + backendLabel());

        navHolder.removeAllViews();
        LinearLayout bar = UiKit.tabbar(this, P, UiKit.navBlur(this), index, this::showTab);
        navHolder.addView(bar, UiKit.tabbarLp(this));
    }

    /** 全局换肤（配色 / 模糊效果）：重建界面，保留当前 Tab（onSaveInstanceState 兜底） */
    private void rebuildUi() {
        recreate();
    }

    private ScrollView scrollPage() {
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setHorizontalScrollBarEnabled(false);
        sv.setFillViewport(true);
        sv.setClipToPadding(false);
        sv.setPadding(dp(18), dp(4), dp(18), dp(124));
        return sv;
    }

    // ================================================================
    //  首页
    // ================================================================

    private View buildHomeTab() {
        ScrollView sv = scrollPage();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        sv.addView(box);

        // 状态卡
        heroCard = new LinearLayout(this);
        heroCard.setOrientation(LinearLayout.HORIZONTAL);
        heroCard.setGravity(Gravity.CENTER_VERTICAL);
        heroCard.setBackground(UiKit.bg(P.hero, this, 18));
        heroCard.setPadding(dp(20), dp(17), dp(20), dp(17));
        heroCard.setElevation(dp(1));
        heroCard.setOnClickListener(v -> toggleChecklist());
        heroIcon = UiKit.icon(this, R.drawable.ic_check, 32, P.heroInk);
        heroCard.addView(heroIcon);
        LinearLayout heroCol = new LinearLayout(this);
        heroCol.setOrientation(LinearLayout.VERTICAL);
        heroTitle = UiKit.text(this, "正在体检…", 19.5f, P.heroInk);
        heroTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heroCol.addView(heroTitle);
        heroSub = UiKit.text(this, "root 检测约需 2-5 秒", 12.5f, P.heroSubInk);
        heroSub.setLineSpacing(dp(1), 1.0f);
        LinearLayout.LayoutParams hsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hsLp.setMargins(0, dp(4), 0, 0);
        heroCol.addView(heroSub, hsLp);
        LinearLayout.LayoutParams hcLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        hcLp.setMargins(dp(15), 0, 0, 0);
        heroCard.addView(heroCol, hcLp);
        box.addView(heroCard);

        // 体检清单（仅异常时显示）
        checklistCard = UiKit.card(this, P);
        checklistBox = new LinearLayout(this);
        checklistBox.setOrientation(LinearLayout.VERTICAL);
        checklistCard.addView(checklistBox);
        box.addView(checklistCard, UiKit.cardLp(this, 10));
        checklistCard.setVisibility(View.GONE);

        // 一键部署 / 排查问题
        deployBtn = UiKit.buttonIcon(this, P, R.drawable.ic_rocket, "一键部署 sqlite3（自动完成）", "ok");
        LinearLayout.LayoutParams depLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        depLp.setMargins(0, dp(10), 0, 0);
        deployBtn.setLayoutParams(depLp);
        deployBtn.setVisibility(View.GONE);
        deployBtn.setOnClickListener(v -> deploySqlite3());
        box.addView(deployBtn);

        troubleshootBtn = UiKit.button(this, P, "排查问题向导（点不了 / 收不到）", "warn");
        LinearLayout.LayoutParams tsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        tsLp.setMargins(0, dp(10), 0, 0);
        troubleshootBtn.setLayoutParams(tsLp);
        troubleshootBtn.setVisibility(View.GONE);
        troubleshootBtn.setOnClickListener(v -> runTroubleshoot());
        box.addView(troubleshootBtn);

        // 双统计卡
        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        stats.addView(statCard("适配应用", String.valueOf(AppIcons.PKGS.length), "通知取件来源"),
                weighted(1f, 0));
        stats.addView(statCard("识别来源", String.valueOf(IDENTIFY_SOURCES), "菜鸟 · 丰巢 · 京东 …"),
                weighted(1f, 12));
        box.addView(stats, UiKit.cardLp(this, 12));

        // 项目版本信息
        LinearLayout verCard = UiKit.card(this, P);
        verCard.addView(UiKit.row(this, P, R.drawable.ic_box, "项目版本信息",
                appVersionLabel() + " (310) · 规则集 v" + PickupExtractor.getActiveRulesVersion()
                        + " · MIT License", UiKit.chevron(this, P)));
        verCard.setOnClickListener(v -> showVersionDialog());
        box.addView(verCard, UiKit.cardLp(this, 12));

        // 是否有新版本
        LinearLayout updCard = UiKit.card(this, P);
        updCard.addView(UiKit.row(this, P, R.drawable.ic_refresh, "是否有新版本",
                "3 天自动检查一次 · 点此立即检查软件与规则",
                UiKit.badgeOk(this, P, "已是最新")));
        updCard.setOnClickListener(v -> checkUpdateNow());
        box.addView(updCard, UiKit.cardLp(this, 10));

        // 反馈入口
        LinearLayout helpCard = UiKit.card(this, P);
        TextView helpTitle = UiKit.text(this, "遇到问题?", 16.5f, UiKit.INK);
        helpTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        helpTitle.setPadding(0, dp(13), 0, dp(2));
        helpCard.addView(helpTitle);
        LinearLayout ghRow = UiKit.row(this, P, 0, "在 GitHub 反馈（Issues）", null,
                UiKit.icon(this, R.drawable.ic_github, 21, UiKit.INK));
        ghRow.setOnClickListener(v -> openUrl(ISSUES_URL));
        helpCard.addView(ghRow);
        helpCard.addView(UiKit.divider(this, P));
        LinearLayout qqRow = UiKit.row(this, P, 0, "加入开发者 QQ 群 901543676", null,
                UiKit.text(this, "复制群号", 13.5f, P.accentInk));
        qqRow.setOnClickListener(v -> copyQqGroup());
        helpCard.addView(qqRow);
        box.addView(helpCard, UiKit.cardLp(this, 10));

        return sv;
    }

    private View statCard(String label, String value, String hint) {
        LinearLayout c = UiKit.card(this, P);
        c.setPadding(dp(16), dp(13), dp(16), dp(14));
        c.addView(UiKit.text(this, label, 14.5f, UiKit.INK));
        TextView v = UiKit.text(this, value, 22f, UiKit.INK);
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.setMargins(0, dp(5), 0, 0);
        c.addView(v, vlp);
        TextView hintView = UiKit.text(this, hint, 11.5f, UiKit.INK3);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.setMargins(0, dp(2), 0, 0);
        c.addView(hintView, hlp);
        return c;
    }

    private LinearLayout.LayoutParams weighted(float w, float leftMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, w);
        lp.setMargins(dp((int) leftMarginDp), 0, 0, 0);
        return lp;
    }

    /** 点状态卡：展开/收起体检清单（异常时强制展开，不给藏红字） */
    private void toggleChecklist() {
        if (healthAllOk) {
            checklistCard.setVisibility(
                    checklistCard.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        }
    }

    // ================================================================
    //  应用
    // ================================================================

    private View buildAppsTab() {
        ScrollView sv = scrollPage();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        sv.addView(box);

        box.addView(UiKit.groupLabel(this, P, "通知取件来源（" + AppIcons.PKGS.length + "）"));
        LinearLayout appCard = UiKit.card(this, P);
        for (int i = 0; i < AppIcons.PKGS.length; i++) {
            String pkg = AppIcons.PKGS[i];
            if (i > 0) appCard.addView(UiKit.divider(this, P));
            ImageView iv = new ImageView(this);
            iv.setLayoutParams(new LinearLayout.LayoutParams(dp(38), dp(38)));
            AppIcons.bind(iv, this, pkg);
            LinearLayout row = UiKit.row(this, P, 0, AppIcons.nameOf(pkg), pkg,
                    UiKit.badgeMute(this, P, "内置"));
            LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(dp(38), dp(38));
            ivLp.setMargins(0, 0, dp(13), 0);
            iv.setLayoutParams(ivLp);
            row.addView(iv, 0);
            appCard.addView(row);
        }
        appCard.addView(UiKit.divider(this, P));
        LinearLayout addBox = UiKit.row(this, P, R.drawable.ic_plus, "添加自定义来源",
                "高级：填入任意 App 包名，随规则一起热同步", UiKit.chevron(this, P));
        appCard.addView(addBox);
        box.addView(appCard);

        // 自定义来源的展开编辑区（就地编辑，不用 dialog 承载输入框）
        extraBox = new LinearLayout(this);
        extraBox.setOrientation(LinearLayout.VERTICAL);
        extraBox.setVisibility(View.GONE);
        extraBox.setBackground(UiKit.bg(P.card, this, 18));
        extraBox.setPadding(dp(16), dp(6), dp(16), dp(14));
        extraBox.setElevation(dp(1));
        box.addView(extraBox, UiKit.cardLp(this, 10));
        buildExtraBoxContent();
        addBox.setOnClickListener(v -> {
            boolean show = extraBox.getVisibility() != View.VISIBLE;
            extraBox.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) extraBox.post(() -> extraBox.requestFocus());
        });

        TextView scopeNote = UiKit.text(this, "需在 LSPosed 作用域勾选「Android 系统」并重启后生效",
                12.5f, P.heroSubInk);
        scopeNote.setBackground(UiKit.bg(P.accentTint, this, 12));
        scopeNote.setPadding(dp(13), dp(11), dp(13), dp(11));
        scopeNote.setLineSpacing(dp(2), 1.1f);
        box.addView(scopeNote, UiKit.cardLp(this, 10));

        box.addView(UiKit.groupLabel(this, P, "写入与适配"));
        LinearLayout waCard = UiKit.card(this, P);
        backendValue = UiKit.value(this, P, backendLabel());
        LinearLayout backendRow = UiKit.row(this, P, R.drawable.ic_target, "待办写入目标",
                "取件码写进哪个 App 的待办", backendValue);
        LinearLayout backendAcc = (LinearLayout) backendRow.getChildAt(backendRow.getChildCount() - 1);
        backendAcc.addView(UiKit.icon(this, R.drawable.ic_chev_down, 17, P.muteInk));
        backendRow.setOnClickListener(v -> showBackendPicker());
        waCard.addView(backendRow);

        waCard.addView(UiKit.divider(this, P));
        envValue = UiKit.value(this, P, envLabel());
        LinearLayout envRow = UiKit.row(this, P, R.drawable.ic_phone, "环境适配",
                envDetail(), envValue);
        LinearLayout envAcc = (LinearLayout) envRow.getChildAt(envRow.getChildCount() - 1);
        envAcc.addView(UiKit.icon(this, R.drawable.ic_chev_down, 17, P.muteInk));
        envRow.setOnClickListener(v -> showEnvPicker());
        waCard.addView(envRow);
        box.addView(waCard, UiKit.cardLp(this, 0f));

        TextView tip = UiKit.text(this, "环境适配为自动识别结果；写入目标切换后立即生效，无需重启。",
                12.5f, UiKit.INK3);
        tip.setPadding(dp(6), dp(12), dp(6), 0);
        box.addView(tip);
        return sv;
    }

    /** 自定义来源：只读预览 + 展开编辑（保存 / 只留内置 / 取消） */
    private void buildExtraBoxContent() {
        extraBox.removeAllViews();
        final TextView title = UiKit.text(this, "自定义来源 App", 15.5f, UiKit.INK);
        title.setPadding(0, dp(10), 0, dp(4));
        extraBox.addView(title);

        extraSummary = UiKit.text(this, "", 12.5f, UiKit.INK3);
        extraSummary.setLineSpacing(dp(1), 1.0f);
        extraBox.addView(extraSummary);

        final EditText edit = new EditText(this);
        edit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        edit.setTextColor(UiKit.INK2);
        edit.setBackground(UiKit.bg(P.cardSoft, this, 12, P.line, 1f));
        edit.setPadding(dp(12), dp(10), dp(12), dp(10));
        edit.setHint("每行一个 App 包名，例：\ncom.shanshui.flash");
        edit.setVisibility(View.GONE);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.setMargins(0, dp(8), 0, 0);
        extraBox.addView(edit, elp);

        final LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setVisibility(View.GONE);
        final TextView save = smallButton("保存", "primary");
        final TextView clear = smallButton("只留内置", "mute");
        final TextView cancel = smallButton("取消", "mute");
        btnRow.addView(save, weighted(1f, 0));
        btnRow.addView(clear, weighted(1f, 8));
        btnRow.addView(cancel, weighted(1f, 8));
        extraBox.addView(btnRow, UiKit.cardLp(this, 8));

        final Runnable[] refresh = new Runnable[1];
        final Runnable[] exit = new Runnable[1];
        exit[0] = () -> {
            edit.setVisibility(View.GONE);
            btnRow.setVisibility(View.GONE);
            extraSummary.setVisibility(View.VISIBLE);
            refresh[0].run();
        };
        refresh[0] = () -> {
            final String extra = ProcessSync.getExtraNotiPkgs(this).trim();
            extraSummary.setText(extra.isEmpty()
                    ? "当前：仅内置 " + AppIcons.PKGS.length + " 个"
                    : "当前：内置 " + AppIcons.PKGS.length + " 个 + 你添加的 "
                      + extra.split("[\\n,，]+").length + " 个");
            extraSummary.setOnClickListener(v -> {
                edit.setText(extra);
                edit.setVisibility(View.VISIBLE);
                btnRow.setVisibility(View.VISIBLE);
                extraSummary.setVisibility(View.GONE);
            });
            clear.setOnClickListener(v -> {
                ProcessSync.setExtraNotiPkgs(this, "");
                Toast.makeText(this, "已恢复为仅内置 App", Toast.LENGTH_SHORT).show();
                exit[0].run();
            });
            cancel.setOnClickListener(v -> exit[0].run());
            save.setOnClickListener(v -> {
                String raw = edit.getText().toString();
                StringBuilder bad = new StringBuilder();
                for (String line : raw.split("[\\n,，]+")) {
                    String p = line.trim();
                    if (p.isEmpty()) continue;
                    if (!p.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) {
                        if (bad.length() > 0) bad.append("、");
                        bad.append(p);
                    }
                }
                if (bad.length() > 0) {
                    Toast.makeText(this, "这些看起来不是 App 包名：\n" + bad, Toast.LENGTH_LONG).show();
                    return;
                }
                ProcessSync.setExtraNotiPkgs(this, raw.trim());
                Toast.makeText(this, "已保存 ✓ 需重启手机生效", Toast.LENGTH_SHORT).show();
                exit[0].run();
            });
        };
        refresh[0].run();
    }

    private String envLabel() {
        String b = NotesBackend.detect(this);
        if (NotesBackend.BACKEND_COLOROS_TODO.equals(b)
                || NotesBackend.BACKEND_COLOROS_NOTE.equals(b)) return "ColorOS 16";
        return "小米 / 红米";
    }

    private String envDetail() {
        String b = NotesBackend.detect(this);
        if (NotesBackend.BACKEND_COLOROS_TODO.equals(b)
                || NotesBackend.BACKEND_COLOROS_NOTE.equals(b)) return "一加 / OPPO / realme · 已适配";
        return "MIUI · HyperOS · 已适配";
    }

    private void showEnvPicker() {
        CharSequence[] items = {
                "小米 / 红米（MIUI · HyperOS）— 已适配",
                "ColorOS 16（一加 / OPPO / realme）— 已适配",
                "其他 ROM（OriginOS / Flyme / HarmonyOS …）— 可写自定义规则"
        };
        int checked = "ColorOS 16".equals(envLabel()) ? 1 : 0;
        UiKit.chooser(this, P, R.drawable.ic_phone, "环境适配", items, checked, w -> {
            if (w == 2) {
                Toast.makeText(this, "其他 ROM 可通过「规则 → 我的规则」为各驿站单独写规则；"
                        + "写入目标需社区适配", Toast.LENGTH_LONG).show();
            }
        });
    }

    // ================================================================
    //  规则
    // ================================================================

    private View buildRulesTab() {
        ScrollView sv = scrollPage();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        sv.addView(box);

        ExtractorRules rules = PickupExtractor.getActiveRules();
        String counts = rules == null ? "" :
                rules.keywordAnchors.size() + " 组锚点词 · " + rules.featureWords.size() + " 特征词 · "
                + rules.negativeKeywords.size() + " 负向词";

        box.addView(UiKit.groupLabel(this, P, "官方规则集"));
        LinearLayout offCard = UiKit.card(this, P);
        offCard.addView(UiKit.row(this, P, R.drawable.ic_link,
                "官方规则集 v" + PickupExtractor.getActiveRulesVersion(), counts,
                UiKit.badgeAccent(this, P, "已是最新")));
        offCard.addView(UiKit.divider(this, P));
        LinearLayout updRow = UiKit.row(this, P, R.drawable.ic_refresh, "检查更新（软件 + 规则）",
                "一次查完，先拉规则再提示软件升级", UiKit.chevron(this, P));
        updRow.setOnClickListener(v -> checkUpdateNow());
        offCard.addView(updRow);
        box.addView(offCard);

        box.addView(UiKit.groupLabel(this, P, "我的规则"));
        LinearLayout urCard = UiKit.card(this, P);
        urSubView = UiKit.text(this, userRuleSummary(), 12.5f, UiKit.INK3);
        urBadgeView = UiKit.badgeMute(this, P, "未启用");
        LinearLayout urRow = UiKit.row(this, P, R.drawable.ic_rules, "用户自定义规则", null, urBadgeView);
        urRow.setOnClickListener(v -> openUserRules());
        urCard.addView(urRow);
        urCard.addView(urSubView);
        urCard.addView(UiKit.spacer(this, 12));
        urCard.addView(UiKit.divider(this, P));
        LinearLayout newRuleRow = UiKit.row(this, P, R.drawable.ic_template, "新建规则",
                "四层发送方匹配 · 自测样本不通过不允许启用", UiKit.chevron(this, P));
        newRuleRow.setOnClickListener(v -> openUserRules());
        urCard.addView(newRuleRow);
        box.addView(urCard, UiKit.cardLp(this, 0f));

        box.addView(UiKit.groupLabel(this, P, "过滤与兜底"));
        LinearLayout blCard = UiKit.card(this, P);
        blPreview = UiKit.text(this, "", 12.5f, UiKit.INK3);
        blPreview.setLineSpacing(dp(1), 1.0f);
        LinearLayout blHead = UiKit.row(this, P, R.drawable.ic_ban, "黑名单关键词", null,
                UiKit.text(this, "修改", 13.5f, P.accentInk));
        blHead.setOnClickListener(v -> enterBlacklistEdit());
        blCard.addView(blHead);
        blCard.addView(blPreview);
        blCard.addView(UiKit.spacer(this, 12));

        blEdit = new EditText(this);
        blEdit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        blEdit.setTextColor(UiKit.INK2);
        blEdit.setBackground(UiKit.bg(P.cardSoft, this, 12, P.line, 1f));
        blEdit.setPadding(dp(12), dp(10), dp(12), dp(10));
        blEdit.setHint("逗号分隔，如：12306,验证码,银行");
        blEdit.setVisibility(View.GONE);
        blCard.addView(blEdit, UiKit.cardLp(this, 6));

        blBtnRow = new LinearLayout(this);
        blBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        blBtnRow.setVisibility(View.GONE);
        TextView blSave = smallButton("保存", "primary");
        TextView blReset = smallButton("恢复默认", "mute");
        TextView blCancel = smallButton("取消", "mute");
        blBtnRow.addView(blSave, weighted(1f, 0));
        blBtnRow.addView(blReset, weighted(1f, 8));
        blBtnRow.addView(blCancel, weighted(1f, 8));
        blCard.addView(blBtnRow, UiKit.cardLp(this, 8));

        blSave.setOnClickListener(v -> saveBlacklist());
        blReset.setOnClickListener(v -> {
            blEdit.setText(BLACKLIST_DEFAULT);
            blEdit.setSelection(blEdit.getText().length());
            Toast.makeText(this, "已填入默认关键词，点「保存」生效", Toast.LENGTH_SHORT).show();
        });
        blCancel.setOnClickListener(v -> exitBlacklistEdit());
        refreshBlacklistPreview();
        box.addView(blCard, UiKit.cardLp(this, 0f));

        box.addView(UiKit.groupLabel(this, P, "工具"));
        LinearLayout toolCard = UiKit.card(this, P);
        toolCard.addView(toolRow(R.drawable.ic_flask, "一键链路测试",
                "不消耗短信 · 分步验证「识别 → 写入」", v -> precheckThenOpenChainTest()));
        toolCard.addView(UiKit.divider(this, P));
        int missed = 0;
        try { missed = MissedSmsStore.getAll(this).size(); } catch (Throwable ignored) { }
        toolCard.addView(toolRow(R.drawable.ic_notebook, "疑似漏抓错题本",
                "当前 " + missed + " 条 · 一键脱敏复制上报", v -> showMissedSmsDialog()));
        toolCard.addView(UiKit.divider(this, P));
        toolCard.addView(toolRow(R.drawable.ic_search, "排查问题向导",
                "未注入 → root → 作用域 → 通知 → sqlite3 → 笔记库", v -> runTroubleshoot()));
        toolCard.addView(UiKit.divider(this, P));
        toolCard.addView(toolRow(R.drawable.ic_doc, "导出诊断报告",
                "自动脱敏，可直接发给作者", v -> exportDiag()));
        box.addView(toolCard, UiKit.cardLp(this, 0f));
        return sv;
    }

    private LinearLayout toolRow(int iconRes, String title, String sub, View.OnClickListener cb) {
        LinearLayout r = UiKit.row(this, P, iconRes, title, sub, UiKit.chevron(this, P));
        r.setOnClickListener(cb);
        return r;
    }

    private void refreshBlacklistPreview() {
        String list = getSharedPreferences(PREFS, MODE_PRIVATE).getString("blacklist", BLACKLIST_DEFAULT);
        blPreview.setText("当前：" + list.replace(",", "，"));
    }

    private void enterBlacklistEdit() {
        String list = getSharedPreferences(PREFS, MODE_PRIVATE).getString("blacklist", BLACKLIST_DEFAULT);
        blEdit.setText(list);
        blEdit.setSelection(blEdit.getText().length());
        blEdit.setVisibility(View.VISIBLE);
        blBtnRow.setVisibility(View.VISIBLE);
        blPreview.setVisibility(View.GONE);
    }

    private void exitBlacklistEdit() {
        blEdit.setVisibility(View.GONE);
        blBtnRow.setVisibility(View.GONE);
        blPreview.setVisibility(View.VISIBLE);
        refreshBlacklistPreview();
    }

    private void saveBlacklist() {
        String newList = blEdit.getText().toString().trim();
        if (newList.isEmpty()) {
            Toast.makeText(this, "黑名单已清空（所有短信不再被关键词过滤）", Toast.LENGTH_SHORT).show();
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("blacklist", newList).apply();
        ProcessSync.push(this);
        if (!newList.isEmpty()) Toast.makeText(this, "黑名单已保存", Toast.LENGTH_SHORT).show();
        exitBlacklistEdit();
    }

    // ================================================================
    //  设置
    // ================================================================

    private View buildSettingsTab() {
        ScrollView sv = scrollPage();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        sv.addView(box);

        box.addView(UiKit.groupLabel(this, P, "提取与写入"));
        LinearLayout setCard = UiKit.card(this, P);

        // 写入目标
        LinearLayout beRow = UiKit.row(this, P, R.drawable.ic_target, "写入目标",
                backendLabel() + " · 自动识别", UiKit.value(this, P, "切换"));
        beRow.setOnClickListener(v -> showBackendPicker());
        setCard.addView(beRow);

        // 待办模板（就地展开编辑）
        setCard.addView(UiKit.divider(this, P));
        tplValueView = UiKit.value(this, P, modeLabel(currentMode));
        LinearLayout tplRow = UiKit.row(this, P, R.drawable.ic_template, "待办模板",
                "极简 / 完整 / 自定义占位符", tplValueView);
        tplRow.setOnClickListener(v -> toggleTplEditor());
        setCard.addView(tplRow);

        tplEditorBox = new LinearLayout(this);
        tplEditorBox.setOrientation(LinearLayout.VERTICAL);
        tplEditorBox.setVisibility(View.GONE);
        buildTplEditor();
        setCard.addView(tplEditorBox);

        // 通知取件提取
        setCard.addView(UiKit.divider(this, P));
        notiSwitchOn = isNotiHookFlagOn();
        notiSw = new UiKit.Switch(this, P, notiSwitchOn);
        notiSw.setOnToggle(this::onNotiToggle);
        LinearLayout notiRow = UiKit.row(this, P, R.drawable.ic_bell, "通知取件提取",
                "拦截菜鸟 / 京东 / 淘宝 / 拼多多等 App 的取件通知", notiSw);
        setCard.addView(notiRow);

        notiScopeHintView = UiKit.text(this,
                "LSPosed 作用域未勾选「Android 系统（android）」，此时通知取件提取不会生效（重启手机也没用）。",
                11.5f, UiKit.INK3);
        notiScopeHintView.setLineSpacing(dp(2), 1.0f);
        notiScopeHintView.setVisibility(View.GONE);
        setCard.addView(notiScopeHintView);

        // 冻结免疫直写（仅 ColorOS 环境显示）
        sdwRow = new LinearLayout(this);
        sdwRow.setOrientation(LinearLayout.VERTICAL);
        boolean sdwOn = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_SYS_DIRECT, false);
        sdwSw = new UiKit.Switch(this, P, sdwOn);
        sdwSw.setOnToggle(this::onSdwToggle);
        LinearLayout sdwItem = UiKit.row(this, P, R.drawable.ic_shield, "冻结免疫直写",
                "仅 ColorOS：划掉后台仍可写入（需给系统进程授权 root）", sdwSw);
        sdwRow.addView(dividerWithTopPadding());
        sdwRow.addView(sdwItem);
        setCard.addView(sdwRow);
        updateSdwDisplay();
        box.addView(setCard);

        // 个性化
        box.addView(UiKit.groupLabel(this, P, "个性化"));
        LinearLayout perCard = UiKit.card(this, P);
        LinearLayout appeRow = UiKit.row(this, P, R.drawable.ic_palette, "外观",
                "模块界面配色（四套可选）", UiKit.value(this, P, P.name));
        appeRow.setOnClickListener(v -> showPalettePicker());
        perCard.addView(appeRow);
        perCard.addView(UiKit.divider(this, P));
        blurSw = new UiKit.Switch(this, P, UiKit.navBlur(this));
        blurSw.setOnToggle(on -> {
            UiKit.setNavBlur(this, on);
            Toast.makeText(this, on ? "底栏已切回半透明（85%）" : "底栏已切换为纯实底（最清晰）",
                    Toast.LENGTH_SHORT).show();
            rebuildUi();
        });
        perCard.addView(UiKit.row(this, P, R.drawable.ic_blur, "模糊效果",
                "底栏半透明（85%）；关闭则使用纯实底", blurSw));
        perCard.addView(UiKit.divider(this, P));
        perCard.addView(UiKit.row(this, P, R.drawable.ic_globe, "语言", "跟随系统", null));
        box.addView(perCard, UiKit.cardLp(this, 0f));

        // 常规
        box.addView(UiKit.groupLabel(this, P, "常规"));
        LinearLayout genCard = UiKit.card(this, P);
        netSw = new UiKit.Switch(this, P, NetPolicy.autoNetAllowed(this));
        netSw.setOnToggle(on -> {
            NetPolicy.setAutoNetAllowed(this, on);
            Toast.makeText(this, on
                    ? "已开启自动联网（3 天一次静默检查软件与规则更新）"
                    : "已进入纯离线模式 ✓\n自动联网已停止；核心功能不受影响，仍可手动检查更新",
                    Toast.LENGTH_LONG).show();
        });
        genCard.addView(UiKit.row(this, P, R.drawable.ic_refresh, "自动检查更新",
                "启动时检查软件与规则（3 天最多一次）· 关闭后完全离线", netSw));
        genCard.addView(UiKit.divider(this, P));
        genCard.addView(toolRow(R.drawable.ic_download, "检查更新", "软件 + 规则一次查完",
                v -> checkUpdateNow()));
        genCard.addView(UiKit.divider(this, P));
        genCard.addView(toolRow(R.drawable.ic_doc, "更新日志", "查看模块的更新日志",
                v -> openUrl(CHANGELOG_URL)));
        box.addView(genCard, UiKit.cardLp(this, 0f));

        // 帮助与关于
        box.addView(UiKit.groupLabel(this, P, "帮助与关于"));
        LinearLayout aboutCard = UiKit.card(this, P);
        aboutCard.addView(toolRow(R.drawable.ic_file, "用户协议", "MIT License · 隐私说明",
                v -> showAgreementDialog()));
        aboutCard.addView(UiKit.divider(this, P));
        aboutCard.addView(toolRow(R.drawable.ic_info, "关于模块",
                appVersionLabel() + " · 作者 欧锴（O-kai）", v -> showAboutDialog()));
        aboutCard.addView(UiKit.divider(this, P));
        aboutCard.addView(toolRow(R.drawable.ic_heart, "打赏作者", "纯自愿，不影响任何功能",
                v -> startActivity(new Intent(this, DonateActivity.class))));
        aboutCard.addView(UiKit.divider(this, P));
        aboutCard.addView(toolRow(R.drawable.ic_github, "项目仓库", "GitHub · 欢迎 Star",
                v -> openUrl(REPO_URL)));
        box.addView(aboutCard, UiKit.cardLp(this, 0f));

        box.addView(UiKit.groupLabel(this, P, "使用说明"));
        TextView help = UiKit.text(this,
                "1. LSPosed 激活模块；作用域必勾「Android 系统」（列表底部、不带推荐角标），"
                        + "再加 电话、短信、com.android.providers.telephony；小米设备另勾「小米笔记」\n"
                        + "2. 收到取件短信后自动写入待办（一码一条，新码置顶）\n"
                        + "3. 通知可点击复制取件码；通知上「已取件」可一键勾选\n"
                        + "4. 需要 root（Magisk/KernelSU）授权一次；sqlite3 缺失时首页会出现一键部署\n"
                        + "5. 交流反馈：QQ 群 901543676（进群备注「取件码助手」）",
                12f, UiKit.INK3);
        help.setLineSpacing(dp(3), 1.0f);
        help.setPadding(dp(6), 0, dp(6), 0);
        box.addView(help);
        return sv;
    }

    private View dividerWithTopPadding() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(UiKit.divider(this, P));
        return wrap;
    }

    private static String modeLabel(int mode) {
        if (mode == TodoWriter.MODE_MIN) return "极简";
        if (mode == TodoWriter.MODE_CUSTOM) return "自定义";
        return "完整";
    }

    private void toggleTplEditor() {
        boolean show = tplEditorBox.getVisibility() != View.VISIBLE;
        tplEditorBox.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) tplValueView.setVisibility(View.GONE);
        else {
            tplValueView.setVisibility(View.VISIBLE);
            collapseTplEditState();
        }
    }

    /** 模板编辑器（就地展开）：三模式分段 + 效果预览 + 自定义编辑 */
    private void buildTplEditor() {
        tplEditorBox.setPadding(0, dp(6), 0, dp(12));

        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setBackground(UiKit.bg(P.track, this, 14));
        seg.setPadding(dp(3), dp(3), dp(3), dp(3));
        String[] labels = { "极简", "完整", "自定义" };
        modeBtns.clear();
        for (int i = 0; i < 3; i++) {
            final int mode = modeIds[i];
            TextView b = UiKit.text(this, labels[i], 13.5f, UiKit.INK2);
            b.setGravity(Gravity.CENTER);
            b.setPadding(0, dp(8), 0, dp(8));
            b.setOnClickListener(v -> switchMode(mode, labels[indexOfMode(mode)]));
            modeBtns.add(b);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp(3), 0, dp(3), 0);
            seg.addView(b, lp);
        }
        tplEditorBox.addView(seg);
        restyleModes();

        // 效果预览：标签 + 矢量图标（替代模板开头的 emoji）+ 文本
        tplPreview = new LinearLayout(this);
        tplPreview.setOrientation(LinearLayout.VERTICAL);
        tplPreview.setBackground(UiKit.bg(P.cardSoft, this, 12));
        tplPreview.setPadding(dp(12), dp(10), dp(12), dp(10));
        tplPreview.addView(UiKit.text(this, "效果预览：", 12.5f, UiKit.INK3));
        LinearLayout pvRow = new LinearLayout(this);
        pvRow.setOrientation(LinearLayout.HORIZONTAL);
        pvRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams pvLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pvLp.setMargins(0, dp(4), 0, 0);
        tplPreview.addView(pvRow, pvLp);
        tplPreviewIcon = UiKit.icon(this, R.drawable.ic_box, 16, UiKit.INK2);
        LinearLayout.LayoutParams pilp = new LinearLayout.LayoutParams(dp(16), dp(16));
        pilp.setMargins(0, 0, dp(6), 0);
        tplPreviewIcon.setLayoutParams(pilp);
        pvRow.addView(tplPreviewIcon);
        tplPreviewText = UiKit.text(this, "", 12.5f, UiKit.INK2);
        tplPreviewText.setLineSpacing(dp(2), 1.05f);
        pvRow.addView(tplPreviewText, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tplEditorBox.addView(tplPreview, UiKit.cardLp(this, 8));

        tplEditBtn = smallButton("编辑模板", "ghostLine", R.drawable.ic_template);
        tplEditorBox.addView(tplEditBtn, UiKit.cardLp(this, 8));
        tplEditBtn.setOnClickListener(v -> {
            tplEdit.setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString("todo_custom", TPL_DEFAULT));
            tplEdit.setVisibility(View.VISIBLE);
            tplBtnRow.setVisibility(View.VISIBLE);
            tplEditBtn.setVisibility(View.GONE);
            tplPreview.setVisibility(View.GONE);
        });

        tplEdit = new EditText(this);
        tplEdit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        tplEdit.setTextColor(UiKit.INK2);
        tplEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        tplEdit.setBackground(UiKit.bg(P.cardSoft, this, 12, P.line, 1f));
        tplEdit.setPadding(dp(12), dp(10), dp(12), dp(10));
        tplEdit.setHint("自定义模板，占位符：{code} {source} {place} {time}");
        tplEdit.setVisibility(View.GONE);
        tplEditorBox.addView(tplEdit, UiKit.cardLp(this, 8));

        tplBtnRow = new LinearLayout(this);
        tplBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        tplBtnRow.setVisibility(View.GONE);
        TextView save = smallButton("保存", "primary");
        TextView reset = smallButton("恢复默认", "mute");
        TextView cancel = smallButton("取消", "mute");
        tplBtnRow.addView(save, weighted(1f, 0));
        tplBtnRow.addView(reset, weighted(1f, 8));
        tplBtnRow.addView(cancel, weighted(1f, 8));
        tplEditorBox.addView(tplBtnRow, UiKit.cardLp(this, 8));
        cancel.setOnClickListener(v -> collapseTplEditState());
        reset.setOnClickListener(v -> {
            tplEdit.setText(TPL_DEFAULT);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("todo_custom", TPL_DEFAULT).apply();
            ProcessSync.push(this);
            Toast.makeText(this, "已恢复默认模板", Toast.LENGTH_SHORT).show();
            collapseTplEditState();
        });
        save.setOnClickListener(v -> {
            String t = tplEdit.getText().toString().trim();
            if (t.isEmpty()) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("todo_custom", TPL_DEFAULT).apply();
                ProcessSync.push(this);
                Toast.makeText(this, "内容为空，已恢复默认模板", Toast.LENGTH_SHORT).show();
                collapseTplEditState();
                return;
            }
            if (t.length() > 500) {
                Toast.makeText(this, "模板过长（" + t.length() + "/500 字），请精简", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!t.contains("{code}")) {
                Toast.makeText(this, "模板必须包含 {code} 占位符（否则待办里看不到取件码）",
                        Toast.LENGTH_LONG).show();
                return;
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("todo_custom", t).apply();
            ProcessSync.push(this);
            Toast.makeText(this, "自定义模板已保存并生效 ✓", Toast.LENGTH_SHORT).show();
            collapseTplEditState();
        });

        renderTplPreview();
    }

    private void collapseTplEditState() {
        if (tplEdit != null) tplEdit.setVisibility(View.GONE);
        if (tplBtnRow != null) tplBtnRow.setVisibility(View.GONE);
        if (tplPreview != null) tplPreview.setVisibility(View.VISIBLE);
        if (tplEditBtn != null) {
            tplEditBtn.setVisibility(currentMode == TodoWriter.MODE_CUSTOM ? View.VISIBLE : View.GONE);
        }
        renderTplPreview();
    }

    private int indexOfMode(int mode) {
        for (int i = 0; i < modeIds.length; i++) if (modeIds[i] == mode) return i;
        return 1;
    }

    private void switchMode(int mode, String label) {
        if (mode == currentMode) return;
        currentMode = mode;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("todo_mode", mode).apply();
        ProcessSync.push(this);
        restyleModes();
        renderTplPreview();
        collapseTplEditState();
        Toast.makeText(this, "待办模板已切换为「" + label + "」", Toast.LENGTH_SHORT).show();
    }

    private void restyleModes() {
        for (int i = 0; i < modeBtns.size(); i++) {
            boolean sel = modeIds[i] == currentMode;
            TextView b = modeBtns.get(i);
            b.setTextColor(sel ? 0xFFFFFFFF : UiKit.INK2);
            b.setTypeface(null, sel ? Typeface.BOLD : Typeface.NORMAL);
            b.setBackground(sel ? UiKit.bg(P.accent, this, 11) : UiKit.bg(0x00000000, this, 11));
        }
    }

    private void renderTplPreview() {
        if (tplPreviewText == null) return;
        String savedTpl = getSharedPreferences(PREFS, MODE_PRIVATE).getString("todo_custom", TPL_DEFAULT);
        String text;
        if (currentMode == TodoWriter.MODE_CUSTOM) {
            text = renderTemplate(savedTpl, "99-9-8888", "菜鸟驿站", "XX小区", "09-06 18:00");
        } else if (currentMode == TodoWriter.MODE_MIN) {
            text = "99-9-8888";
        } else {
            text = "取件码 99-9-8888｜菜鸟驿站｜XX小区｜09-06 18:00";
        }
        // 开头的 emoji 用矢量图标呈现（写入待办的文本本身不变）
        boolean hasEmoji = !text.isEmpty() && isEmoji(text.codePointAt(0));
        tplPreviewIcon.setVisibility(hasEmoji ? View.VISIBLE : View.GONE);
        tplPreviewText.setText(hasEmoji ? stripLeadingEmoji(text) : text);
    }

    /** 判断码点是否属于常见 emoji 区段 */
    private static boolean isEmoji(int cp) {
        return (cp >= 0x1F000 && cp <= 0x1FAFF) || (cp >= 0x2600 && cp <= 0x27BF)
                || (cp >= 0x2B00 && cp <= 0x2BFF) || cp == 0x2705 || cp == 0x274C;
    }

    /** 去掉开头连续的 emoji 与其后的空白 */
    private static String stripLeadingEmoji(String s) {
        if (s == null || s.isEmpty()) return "";
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            if (isEmoji(cp) || cp == 0xFE0F) i += Character.charCount(cp);
            else break;
        }
        String rest = s.substring(i);
        while (rest.startsWith(" ") || rest.startsWith("\u3000")) rest = rest.substring(1);
        return rest;
    }

    private static String renderTemplate(String tpl, String code, String source, String place, String time) {
        if (tpl == null || tpl.isEmpty()) return "（空模板）";
        return tpl.replace("{code}", code).replace("{source}", source)
                .replace("{place}", place).replace("{time}", time);
    }

    private void showPalettePicker() {
        CharSequence[] items = new CharSequence[UiKit.ALL.length];
        int checked = 0;
        for (int i = 0; i < UiKit.ALL.length; i++) {
            items[i] = UiKit.ALL[i].name + (UiKit.ALL[i].id.equals(P.id) ? "（当前）" : "");
            if (UiKit.ALL[i].id.equals(P.id)) checked = i;
        }
        UiKit.chooser(this, P, R.drawable.ic_palette, "外观 · 选择配色", items, checked, w -> {
            UiKit.setPalette(this, UiKit.ALL[w]);
            rebuildUi();
        });
    }

    // ================================================================
    //  事件：更新 / 反馈 / 关于
    // ================================================================

    private void checkUpdateNow() {
        Toast.makeText(this, "正在检查更新…", Toast.LENGTH_SHORT).show();
        UpdateCenter.checkNow(this, this::refreshHealth);
    }

    private void deploySqlite3() {
        deployBtn.setEnabled(false);
        Toast.makeText(this, "正在部署 sqlite3…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String res;
            try {
                res = Repair.deploySqlite3(this);
            } catch (Throwable t) {
                res = "部署异常：" + t.getMessage();
            }
            final String r2 = res;
            runOnUiThread(() -> {
                deployBtn.setEnabled(true);
                Toast.makeText(this, r2, Toast.LENGTH_LONG).show();
                refreshHealth();
            });
        }).start();
    }

    private void showVersionDialog() {
        ExtractorRules r = PickupExtractor.getActiveRules();
        String counts = r == null ? "—" :
                r.keywordAnchors.size() + " 组锚点词 / " + r.featureWords.size() + " 特征词 / "
                        + r.negativeKeywords.size() + " 负向词";
        String msg = "模块版本：" + appVersionLabel() + " (310)\n"
                + "规则集：v" + PickupExtractor.getActiveRulesVersion() + "\n"
                + "词表规模：" + counts + "\n"
                + "许可协议：MIT License\n\n"
                + "设备：" + android.os.Build.MODEL + "\n"
                + "系统：Android " + android.os.Build.VERSION.RELEASE
                + " (API " + android.os.Build.VERSION.SDK_INT + ")\n"
                + "架构：" + String.join(", ", android.os.Build.SUPPORTED_ABIS) + "\n"
                + "写入目标：" + backendLabel();
        UiKit.dialog(this, P, R.drawable.ic_box, "项目版本信息", msg, null,
                UiKit.primary("知道了", null),
                UiKit.ghost("项目仓库", () -> openUrl(REPO_URL)));
    }

    private void showAgreementDialog() {
        UiKit.dialog(this, P, R.drawable.ic_file, "用户协议与隐私说明",
                "· 本项目以 MIT License 开源，仅供学习交流；使用即表示你自行承担相应风险。\n\n"
                        + "· 隐私：模块不申请短信权限，不收集、不上传任何个人信息；短信内容仅在本机内存中解析，"
                        + "写入的是你自己的待办。\n\n"
                        + "· 联网：仅在「自动检查更新」开启时访问 GitHub Releases 与规则热更新，"
                        + "可在设置里关闭，关闭后完全离线。\n\n"
                        + "· 风险提示：需要 root 与 LSPosed 环境；「冻结免疫直写」会给系统进程授权 root，"
                        + "请自行评估后再开启。", null,
                UiKit.primary("我知道了", null),
                UiKit.ghost("完整协议", () -> openUrl(REPO_URL + "/blob/main/LICENSE")));
    }

    private void showAboutDialog() {
        UiKit.dialog(this, P, R.drawable.ic_info, "关于模块",
                "取件码助手 " + appVersionLabel() + "\n"
                        + "自动提取快递取件码并写入待办（小米笔记 / ColorOS 日历·便签）\n\n"
                        + "作者：欧锴（O-kai）\n"
                        + "LSPosed 模块仓库已上架，酷安同步更新\n\n"
                        + "遇到问题欢迎附「导出诊断报告」反馈", null,
                UiKit.primary("知道了", null),
                UiKit.ghost("GitHub", () -> openUrl(REPO_URL)),
                UiKit.ghost("酷安帖子", () -> openUrl(COOLAPK_URL)));
    }

    // ================================================================
    //  通知取件 / 冻结直写 / 写入目标
    // ================================================================

    private void onNotiToggle(boolean next) {
        boolean wasBlocked = Boolean.TRUE.equals(notiScopeBlocked);
        notiSwitchOn = next;
        toggleNotiHookFlag(next);
        notiScopeBlocked = null;
        if (next) checkNotiHookScopeAsync(true);
        renderNotiSwitchUi();
        String tip;
        if (!next) {
            tip = "通知取件提取已关闭（重启手机后系统侧同步生效）";
        } else if (wasBlocked) {
            tip = NOTI_SCOPE_TOAST;
        } else {
            tip = "通知取件提取已开启 ✓（重启手机后生效）\n"
                    + "将拦截菜鸟/京东/淘宝/拼多多等 App 的取件通知";
        }
        Toast.makeText(this, tip, Toast.LENGTH_LONG).show();
    }

    /** 渲染通知开关的「真实可用状态」：开关开着但缺 android 作用域时露出灰字说明 */
    private void renderNotiSwitchUi() {
        if (notiScopeHintView == null) return;
        boolean blocked = notiSwitchOn && Boolean.TRUE.equals(notiScopeBlocked);
        notiScopeHintView.setVisibility(blocked ? View.VISIBLE : View.GONE);
    }

    private void onSdwToggle(boolean next) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_SYS_DIRECT, next).apply();
        new Thread(() -> {
            String flag = Repair.TARGET_DIR + "/enable_sys_direct_write";
            try {
                Diagnostics.suExecPublic("mkdir -p " + Repair.TARGET_DIR + "; "
                        + "echo " + (next ? "1" : "0") + " > " + flag + "; chmod 644 " + flag, 15);
            } catch (Throwable ignored) { }
        }).start();
        if (next) {
            Toast.makeText(this, "已开启冻结免疫直写 ✓\n还需两步：\n"
                    + "1. Magisk/KernelSU 给「com.android.providers.telephony」（短信存储）授权 root\n"
                    + "2. 重启手机生效", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "已关闭冻结免疫直写（重启手机后系统进程侧同步生效）",
                    Toast.LENGTH_SHORT).show();
        }
    }

    /** 冻结直写：仅 ColorOS 环境显示（小米不需要此功能，默认关闭） */
    private void updateSdwDisplay() {
        String currentBackend = NotesBackend.detect(this);
        boolean isColorOs = NotesBackend.BACKEND_COLOROS_TODO.equals(currentBackend)
                || NotesBackend.BACKEND_COLOROS_NOTE.equals(currentBackend);
        if (!isColorOs) {
            sdwRow.setVisibility(View.GONE);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_SYS_DIRECT, false).apply();
            if (sdwSw != null) sdwSw.setChecked(false, false);
            return;
        }
        sdwRow.setVisibility(View.VISIBLE);
        android.content.SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean sdwOn;
        if (!sp.contains(KEY_SYS_DIRECT)) {
            sdwOn = true;
            sp.edit().putBoolean(KEY_SYS_DIRECT, true).apply();
            new Thread(() -> {
                String flag = Repair.TARGET_DIR + "/enable_sys_direct_write";
                try {
                    Diagnostics.suExecPublic("mkdir -p " + Repair.TARGET_DIR + "; "
                            + "echo 1 > " + flag + "; chmod 644 " + flag, 15);
                } catch (Throwable ignored) { }
            }).start();
        } else {
            sdwOn = sp.getBoolean(KEY_SYS_DIRECT, true);
        }
        if (sdwSw != null) sdwSw.setChecked(sdwOn, false);
    }

    private void showBackendPicker() {
        String cur = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_BACKEND, "");
        final String[] values = {
                "", NotesBackend.BACKEND_XIAOMI, NotesBackend.BACKEND_COLOROS_TODO, NotesBackend.BACKEND_COLOROS_NOTE
        };
        CharSequence[] items = {
                "自动识别（推荐）",
                "小米笔记 待办（HyperOS/MIUI）",
                "ColorOS 日历 待办（ColorOS 16 默认）",
                "ColorOS 便签 置顶笔记（备用）"
        };
        int checked = 0;
        for (int i = 0; i < values.length; i++) if (values[i].equals(cur)) { checked = i; break; }
        UiKit.chooser(this, P, R.drawable.ic_target, "选择写入目标", items, checked,
                w -> applyBackend(values[w]));
    }

    private void applyBackend(String value) {
        if (value != null && !value.isEmpty()) {
            String pkg = NotesBackend.targetPkgOf(value);
            if (!isPackageInstalled(pkg)) {
                Toast.makeText(this, "未检测到 " + backendAppName(value) + " App，无法作为写入目标\n"
                        + "（" + pkg + "）\n请先安装该 App，或改选「自动识别」。", Toast.LENGTH_LONG).show();
                return;
            }
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_BACKEND, value).apply();
        NotesBackend.invalidateCache();
        ProcessSync.push(this);
        String name;
        if (NotesBackend.BACKEND_XIAOMI.equals(value)) name = "小米笔记待办";
        else if (NotesBackend.BACKEND_COLOROS_TODO.equals(value)) name = "ColorOS 日历待办";
        else if (NotesBackend.BACKEND_COLOROS_NOTE.equals(value)) name = "ColorOS 便签置顶笔记";
        else name = "自动识别";
        Toast.makeText(this, "写入目标已切换：\n" + name
                + "\n（下次收到取件短信即写入新目标；可点「规则 → 一键链路测试」立即验证）",
                Toast.LENGTH_LONG).show();
        if (backendValue != null) backendValue.setText(backendLabel());
        headerSub.setText(appVersionLabel() + " · LSPosed 模块 · 自动提取取件码写入 " + backendLabel());
        updateSdwDisplay();
        refreshHealth();
    }

    private static String backendAppName(String value) {
        if (NotesBackend.BACKEND_XIAOMI.equals(value)) return "小米笔记";
        if (NotesBackend.BACKEND_COLOROS_TODO.equals(value)) return "ColorOS 日历";
        if (NotesBackend.BACKEND_COLOROS_NOTE.equals(value)) return "ColorOS 便签";
        return "";
    }

    private boolean isPackageInstalled(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return false;
        } catch (Throwable t) {
            return true;   // 探测本身出错时按「已安装」放行，不因查询异常挡住用户
        }
    }

    /** 副标题/选择器当前后端的人话名 */
    private String backendLabel() {
        String b = NotesBackend.detect(this);
        if (NotesBackend.BACKEND_XIAOMI.equals(b)) return "小米笔记待办";
        if (NotesBackend.BACKEND_COLOROS_NOTE.equals(b)) return "ColorOS 便签置顶笔记";
        return "ColorOS 日历待办";
    }

    // ================================================================
    //  通知取件开关的真实可用状态（v3.1.1 逻辑原样保留）
    // ================================================================

    private boolean isNotiHookFlagOn() {
        try {
            java.io.File f = new java.io.File(Repair.TARGET_DIR + "/enable_noti_hook");
            if (!f.exists()) return false;
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
            String line = r.readLine();
            r.close();
            return line != null && line.trim().equals("1");
        } catch (Throwable t) {
            return false;
        }
    }

    private void toggleNotiHookFlag(boolean on) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("noti_hook", on).apply();
        new Thread(() -> {
            String flag = Repair.TARGET_DIR + "/enable_noti_hook";
            try {
                Diagnostics.suExecPublic("mkdir -p " + Repair.TARGET_DIR + "; "
                        + "echo " + (on ? "1" : "0") + " > " + flag + "; chmod 644 " + flag, 15);
            } catch (Throwable ignored) { }
        }).start();
    }

    private void checkNotiHookScopeAsync(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastNotiScopeCheck < NOTI_SCOPE_CHECK_INTERVAL) return;
        lastNotiScopeCheck = now;
        if (!notiSwitchOn) {
            notiScopeBlocked = null;
            renderNotiSwitchUi();
            return;
        }
        new Thread(() -> {
            boolean blocked = false;
            try {
                blocked = androidMissingFromScope(Repair.lsposedStatus(this));
            } catch (Throwable ignored) { }
            final boolean b = blocked;
            runOnUiThread(() -> {
                notiScopeBlocked = b;
                renderNotiSwitchUi();
                if (b) Toast.makeText(this, NOTI_SCOPE_TOAST, Toast.LENGTH_LONG).show();
            });
        }).start();
    }

    private boolean androidMissingFromScope(Repair.LsposedStatus st) {
        if (st == null) return false;
        if (!st.dbReadable || !st.moduleEnabled) return false;
        if (st.missingScope == null) return false;
        for (String p : st.missingScope.split("[、,，]")) {
            if ("android".equals(p.trim())) return true;
        }
        return false;
    }

    // ================================================================
    //  体检（首页状态卡 + 清单）
    // ================================================================

    private void refreshHealth() {
        heroTitle.setText("正在体检…");
        heroSub.setText("root 检测约需 2-5 秒");
        heroIcon.setImageResource(R.drawable.ic_check);
        heroCard.setBackground(UiKit.bg(P.hero, this, 18));
        heroIcon.setColorFilter(P.heroInk, android.graphics.PorterDuff.Mode.SRC_IN);
        heroTitle.setTextColor(P.heroInk);
        heroSub.setTextColor(P.heroSubInk);
        checklistCard.setVisibility(View.GONE);
        deployBtn.setVisibility(View.GONE);
        troubleshootBtn.setVisibility(View.GONE);

        new Thread(() -> {
            List<Diagnostics.Check> checks;
            boolean needDeploy = false;
            boolean allOk = true;
            String err = null;
            try {
                checks = Diagnostics.healthCheck(this);
                for (Diagnostics.Check c : checks) {
                    if (!c.ok) allOk = false;
                    if ("sqlite3 部署".equals(c.title) && !c.ok
                            && c.detail != null && c.detail.contains("一键部署")) {
                        needDeploy = true;
                    }
                }
            } catch (Throwable t) {
                checks = null;
                allOk = false;
                err = String.valueOf(t.getMessage());
            }
            final List<Diagnostics.Check> cs = checks;
            final boolean show = needDeploy, ok = allOk;
            final String errMsg = err;
            runOnUiThread(() -> renderHealth(cs, ok, show, errMsg));
        }).start();
    }

    private void renderHealth(List<Diagnostics.Check> checks, boolean allOk, boolean needDeploy, String err) {
        healthAllOk = allOk;
        int failed = 0;
        if (checks != null) for (Diagnostics.Check c : checks) if (!c.ok) failed++;

        if (err != null) {
            heroTitle.setText("体检异常");
            heroSub.setText(err);
            heroIcon.setImageResource(R.drawable.ic_alert);
            heroCard.setBackground(UiKit.bg(P.heroWarn, this, 18));
            heroIcon.setColorFilter(P.heroWarnInk, android.graphics.PorterDuff.Mode.SRC_IN);
            heroTitle.setTextColor(P.heroWarnInk);
            heroSub.setTextColor(P.heroWarnSubInk);
        } else if (allOk) {
            heroTitle.setText("一切正常 · 已激活");
            heroSub.setText(appVersionLabel() + " · LSPosed 已注入 · 六项体检全部通过\n"
                    + "规则集 v" + PickupExtractor.getActiveRulesVersion()
                    + " · 写入目标：" + backendLabel());
            heroIcon.setImageResource(R.drawable.ic_check);
            heroCard.setBackground(UiKit.bg(P.hero, this, 18));
            heroIcon.setColorFilter(P.heroInk, android.graphics.PorterDuff.Mode.SRC_IN);
            heroTitle.setTextColor(P.heroInk);
            heroSub.setTextColor(P.heroSubInk);
        } else {
            heroTitle.setText(failed + " 项待处理");
            heroSub.setText("体检 " + (6 - failed) + "/6 通过 · 点此查看六项清单\n"
                    + "规则集 v" + PickupExtractor.getActiveRulesVersion()
                    + " · 写入目标：" + backendLabel());
            heroIcon.setImageResource(R.drawable.ic_alert);
            heroCard.setBackground(UiKit.bg(P.heroWarn, this, 18));
            heroIcon.setColorFilter(P.heroWarnInk, android.graphics.PorterDuff.Mode.SRC_IN);
            heroTitle.setTextColor(P.heroWarnInk);
            heroSub.setTextColor(P.heroWarnSubInk);
        }

        // 清单
        checklistBox.removeAllViews();
        if (checks != null) {
            for (int i = 0; i < checks.size(); i++) {
                Diagnostics.Check c = checks.get(i);
                if (i > 0) checklistBox.addView(UiKit.divider(this, P));
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(11), 0, dp(11));
                ImageView dot = UiKit.icon(this, c.ok ? R.drawable.ic_check : R.drawable.ic_x, 21,
                        0xFFFFFFFF);
                dot.setBackground(UiKit.bg(c.ok ? UiKit.OK : UiKit.DANGER, this, 999));
                dot.setPadding(dp(4), dp(4), dp(4), dp(4));
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(21), dp(21));
                dlp.setMargins(0, 0, dp(11), 0);
                dot.setLayoutParams(dlp);
                row.addView(dot);
                row.addView(UiKit.text(this, c.title, 14f, UiKit.INK2),
                        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                // v4.0.1：只显示结论（通过 / 未通过），具体原因交给「排查问题向导」——
                // 长错误串塞在清单里既难读，也和绿色通过的排版不一致
                TextView why = UiKit.text(this, c.ok ? "通过" : "未通过", 12f,
                        c.ok ? UiKit.INK3 : UiKit.DANGER);
                why.setGravity(Gravity.END);
                row.addView(why);
                checklistBox.addView(row);
            }
        }
        // 全绿时默认收起清单（点状态卡可展开）；异常时强制展开，不让红字被藏起来
        checklistCard.setVisibility(allOk ? View.GONE : View.VISIBLE);
        deployBtn.setVisibility(needDeploy ? View.VISIBLE : View.GONE);
        troubleshootBtn.setVisibility(allOk ? View.GONE : View.VISIBLE);
    }

    // ================================================================
    //  用户规则卡片 / 通知与意图处理 / 反馈
    // ================================================================

    private String userRuleSummary() {
        try {
            boolean on = UserRules.isEnabled(this);
            int total = UserRules.loadAll(this).size();
            int enabled = 0;
            for (UserRules.Rule r : UserRules.loadAll(this)) if (r.enabled) enabled++;
            if (total == 0) return "官方规则没覆盖到的短信？可为自家驿站单独写一条（当前 0 条）";
            return "共 " + total + " 条，已启用 " + enabled + " 条｜" + (on ? "已开启" : "已关闭");
        } catch (Throwable t) {
            return "为自家驿站 / 学校自提柜写专属提取规则";
        }
    }

    private void refreshUserRuleCard() {
        if (urSubView == null) return;
        try {
            boolean on = UserRules.isEnabled(this);
            int total = UserRules.loadAll(this).size();
            urSubView.setText(userRuleSummary());
            urBadgeView.setText(on ? "已启用" : "未启用");
            urBadgeView.setBackground(UiKit.bg(on ? UiKit.OK_SOFT : P.muteBg, this, 999));
            urBadgeView.setTextColor(on ? UiKit.OK : P.muteInk);
            if (total == 0) urBadgeView.setText("0 条");
        } catch (Throwable ignored) { }
    }

    private void openUserRules() {
        try {
            startActivity(new Intent(this, UserRulesActivity.class));
        } catch (Throwable t) {
            Toast.makeText(this, "打开失败：" + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleCopy(intent);
        handleDone(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handleCopy(getIntent());
        handleDone(getIntent());
        refreshHealth();
        notiSwitchOn = isNotiHookFlagOn();
        if (notiSw != null) notiSw.setChecked(notiSwitchOn, false);
        renderNotiSwitchUi();
        checkNotiHookScopeAsync(false);
        UpdateCenter.maybeCheck(this);
        refreshUserRuleCard();
    }

    private void handleCopy(Intent intent) {
        if (intent == null) return;
        String copy = intent.getStringExtra("copy");
        if (copy == null || copy.isEmpty()) return;
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("pickupcode", copy));
            Toast.makeText(this, "已复制取件码：" + copy, Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Toast.makeText(this, "复制失败：" + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
        try {
            Intent notes = getPackageManager().getLaunchIntentForPackage(NotesBackend.targetPkg(this));
            if (notes != null) startActivity(notes);
        } catch (Throwable ignored) { }
        intent.removeExtra("copy");
    }

    private void handleDone(Intent intent) {
        if (intent == null) return;
        String code = intent.getStringExtra("done");
        if (code == null || code.isEmpty()) return;
        intent.removeExtra("done");
        final String sql = NotesBackend.markDoneSql(this, code);
        new Thread(() -> {
            final int rc = TodoWriter.runSql(this, sql);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                Toast.makeText(this, rc == 0 ? "已标记已取件：" + code : "标记失败（rc=" + rc + "）",
                        Toast.LENGTH_LONG).show();
                if (rc == 0) {
                    try {
                        Intent notes = getPackageManager().getLaunchIntentForPackage(
                                NotesBackend.targetPkg(this));
                        if (notes != null) startActivity(notes);
                    } catch (Throwable ignored) { }
                    finish();
                }
            });
        }).start();
    }

    /** v2.8.0：疑似漏抓错题本（菜单入口 → 现在挂在「规则 → 工具」） */
    private void showMissedSmsDialog() {
        List<MissedSmsStore.Sample> list = MissedSmsStore.getAll(this);
        if (list.isEmpty()) {
            UiKit.dialog(this, P, R.drawable.ic_notebook, "疑似漏抓错题本",
                    "当前错题本为空 ✓\n\n当系统收到含有快递特征词但未能提取出取件码的短信时，"
                            + "会自动记录在此处，供您一键脱敏上报排查。", null,
                    UiKit.primary("知道了", null),
                    UiKit.ghost("手动上报", this::openGithubIssueManual));
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("本地共捕获 ").append(list.size()).append(" 条疑似漏抓短信：\n\n");
        for (int i = 0; i < list.size(); i++) {
            sb.append(i + 1).append(". ").append(MissedSmsStore.sanitize(list.get(i).body)).append("\n\n");
        }
        UiKit.dialog(this, P, R.drawable.ic_notebook, "疑似漏抓错题本（" + list.size() + " 条）",
                sb.toString().trim(), null,
                UiKit.primary("复制上报文本", () -> {
                    String report = MissedSmsStore.formatReport(list);
                    try {
                        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(ClipData.newPlainText("missed_sms_report", report));
                        Toast.makeText(this, "已复制脱敏上报文本 ✓", Toast.LENGTH_LONG).show();
                    } catch (Throwable t) {
                        Toast.makeText(this, "复制失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                }),
                UiKit.ghost("手动上报", this::openGithubIssueManual),
                UiKit.ghost("清空", () -> UiKit.dialog(this, P, R.drawable.ic_ban, "清空错题本",
                        "将删除本机保存的全部 " + list.size() + " 条疑似漏抓样本。\n\n"
                                + "如果还没上报，建议先「复制上报文本」。", null,
                        UiKit.primary("确认清空", () -> {
                            MissedSmsStore.clear(this);
                            Toast.makeText(this, "错题本已清空 ✓", Toast.LENGTH_SHORT).show();
                        }),
                        UiKit.ghost("取消", null))));
    }

    private void openGithubIssueManual() {
        try {
            String title = "【短信样本上报】未识别或误识别取件码";
            String tpl = "### 1. 收到怎样的短信（请自行将姓名、手机号等隐私脱敏）\n"
                    + "【示例驿站】您的包裹已到站，取件码为...\n\n"
                    + "### 2. 实际得到了怎样的反馈？\n"
                    + "- [ ] 毫无反应（未写入待办）\n"
                    + "- [ ] 错误提取（如将小时、单号误认为取件码）\n"
                    + "- [ ] 地点或来源识别错误\n\n"
                    + "具体情况说明：\n\n"
                    + "### 3. 运行环境\n"
                    + "- 模块版本：v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName + "\n"
                    + "- 规则版本：v" + PickupExtractor.getActiveRulesVersion() + "\n"
                    + "- 当前系统：Android " + android.os.Build.VERSION.RELEASE
                    + " (" + android.os.Build.MANUFACTURER + ")\n";
            openUrl(ISSUES_URL + "/new?title=" + Uri.encode(title) + "&body=" + Uri.encode(tpl));
        } catch (Throwable t) {
            openUrl(ISSUES_URL);
        }
    }

    private void copyQqGroup() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("qq_group", "901543676"));
            Toast.makeText(this, "群号已复制：901543676\n进群请备注「取件码助手」", Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Toast.makeText(this, "复制失败，群号：901543676", Toast.LENGTH_SHORT).show();
        }
    }

    private void showFeedbackChannels() {
        UiKit.chooser(this, P, R.drawable.ic_chat, "选择反馈渠道",
                new CharSequence[]{
                        "QQ 群：901543676（复制群号）",
                        "GitHub Issues",
                        "酷安发布帖"
                }, -1, which -> {
                    if (which == 0) copyQqGroup();
                    else if (which == 1) openUrl(ISSUES_URL);
                    else openUrl(COOLAPK_URL);
                });
    }

    private void exportDiag() {
        Toast.makeText(this, "正在生成诊断报告…（含日志提取，约 5-10 秒）", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                String report = Diagnostics.collectReport(this);
                String where = Diagnostics.exportAndShare(this, report);
                runOnUiThread(() -> Toast.makeText(this,
                        "报告已生成：" + where + "（已自动脱敏）\n"
                                + "反馈渠道：QQ 群 901543676（备注「取件码助手」）/ GitHub Issues / 酷安帖子留言",
                        Toast.LENGTH_LONG).show());
            } catch (Throwable t) {
                runOnUiThread(() -> Toast.makeText(this,
                        "报告生成失败：" + t.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable t) {
            Toast.makeText(this, "未找到可用的浏览器", Toast.LENGTH_SHORT).show();
        }
    }

    // ================================================================
    //  排查向导（决策树原样保留）
    // ================================================================

    private void runTroubleshoot() {
        Toast.makeText(this, "正在排查…（约 2-5 秒）", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            List<Diagnostics.Check> checks;
            try {
                checks = Diagnostics.healthCheck(this);
            } catch (Throwable t) {
                showTroubleshootDialog("排查异常：" + t.getMessage(), null, null);
                return;
            }
            Diagnostics.Check inject = findCheck(checks, "LSPosed 注入");
            Diagnostics.Check root = findCheck(checks, "root 授权");
            Diagnostics.Check scope = findCheck(checks, "LSPosed 作用域");
            Diagnostics.Check notif = findCheck(checks, "通知权限");
            Diagnostics.Check sqlite = findCheck(checks, "sqlite3 部署");
            Diagnostics.Check notes = findCheck(checks, "待办库访问");

            if (inject == null || !inject.ok) {
                String backendHint = NotesBackend.BACKEND_XIAOMI.equals(NotesBackend.detect(this))
                        ? "（小米侧含 com.miui.notes；写入目标 App 无需勾选）"
                        : "（勾 3 项即可；写入目标 App 无需勾选）";
                showTroubleshootDialog(
                        "【第 1 步：让模块生效】\n\n"
                        + "1. 打开 LSPosed 管理器 → 模块 → 取件码助手\n"
                        + "2. 打开「启用模块」开关\n"
                        + "3. 勾选作用域：必须包含「Android 系统」，再加 电话、短信" + backendHint + "\n"
                        + "4. 重启手机（必须）\n"
                        + "5. 重启后打开本 App，再点一次「排查问题向导」", null, null);
                return;
            }
            if (root == null || !root.ok) {
                showTroubleshootDialog(
                        "【第 2 步：把 root 授权给本模块】\n\n"
                        + "注入已成功 ✓，但 root 授权还没通过（体检红色叉的原因）。\n\n"
                        + "1. 打开 Magisk → 超级用户 → 找到「取件码助手」→ 打开开关\n"
                        + "   （列表里没有的话：点一次本页任意按钮触发授权弹窗，弹窗选【允许】并勾选\"不再询问\"）\n"
                        + "2. 授权后关掉本弹窗，页面会自动重新核查 → 再点「排查问题」继续",
                        "打开 Magisk", "com.topjohnwu.magisk");
                return;
            }
            if (scope != null && !scope.ok) {
                showTroubleshootDialog(
                        "【第 3 步：补齐作用域】\n\n" + nz(scope.detail)
                        + "\n\n特别提醒：「Android 系统（android）」在列表底部，"
                        + "【不带\"推荐应用\"角标】也必须勾选（带角标的 5 个推荐应用不含它）；"
                        + "别勾成中部的「系统框架（system）」——勾了无效！\n补勾后务必【重启手机】",
                        null, null);
                return;
            }
            if (notif != null && !notif.ok) {
                showTroubleshootDialog("【第 4 步：打开通知权限】\n\n" + nz(notif.detail)
                        + "\n\n（不处理不影响写待办，但收不到取件码弹窗）", null, null);
                return;
            }
            if (sqlite != null && !sqlite.ok
                    && sqlite.detail != null && sqlite.detail.contains("一键部署")) {
                runOnUiThread(() -> Toast.makeText(this,
                        "检测到 sqlite3 未部署 → 自动安装中（如弹出授权请允许）…", Toast.LENGTH_SHORT).show());
                new Thread(() -> {
                    String res;
                    try {
                        res = Repair.deploySqlite3(this);
                    } catch (Throwable t) {
                        res = "部署异常：" + t.getMessage();
                    }
                    final String r2 = res;
                    runOnUiThread(() -> {
                        Toast.makeText(this, r2, Toast.LENGTH_LONG).show();
                        refreshHealth();
                    });
                }).start();
                return;
            }
            if (notes != null && !notes.ok) {
                String target = NotesBackend.targetAppName(this);
                showTroubleshootDialog("【第 5 步：待办库访问异常（" + target + "）】\n\n" + nz(notes.detail)
                        + "\n\n先点「规则 → 一键链路测试」触发一次自动修复；仍失败 → "
                        + "导出诊断报告 + 加 QQ 群 901543676 反馈给作者", null, null);
                return;
            }
            runOnUiThread(() -> UiKit.dialog(this, P, R.drawable.ic_check, "排查完成",
                    "一切 OK！六项检查全部通过。\n\n"
                            + "当前规则集：v" + PickupExtractor.getActiveRulesVersion() + "\n\n"
                            + "如果还是收不到取件码：\n"
                            + "1. 点「规则 → 一键链路测试」验证写入链路\n"
                            + "2. 到「规则 → 疑似漏抓错题本」一键上报未识别短信\n"
                            + "3. 仍异常 → 导出诊断报告 + QQ 群 901543676（备注「取件码助手」）", null,
                    UiKit.primary("导出诊断报告", this::exportDiag),
                    UiKit.ghost("反馈给作者", this::showFeedbackChannels),
                    UiKit.ghost("知道了", null)));
        }).start();
    }

    private void showTroubleshootDialog(String msg, String actionLabel, String actionPkg) {
        runOnUiThread(() -> {
            UiKit.Action act = (actionPkg != null && actionLabel != null)
                    ? UiKit.ghost(actionLabel, () -> {
                        try {
                            Intent li = getPackageManager().getLaunchIntentForPackage(actionPkg);
                            if (li != null) startActivity(li);
                            else openUrl(REPO_URL);
                        } catch (Throwable t) {
                            openUrl(REPO_URL);
                        }
                    })
                    : UiKit.ghost("反馈给作者", this::showFeedbackChannels);
            UiKit.dialog(this, P, R.drawable.ic_search, "排查结果", msg, null,
                    UiKit.primary("导出诊断报告", this::exportDiag),
                    act,
                    UiKit.ghost("知道了", null));
        });
    }

    private Diagnostics.Check findCheck(List<Diagnostics.Check> checks, String titlePrefix) {
        for (Diagnostics.Check c : checks) {
            if (c.title != null && c.title.startsWith(titlePrefix)) return c;
        }
        return null;
    }

    // ================================================================
    //  链路测试入口（体检预检 + 分级提示）
    // ================================================================

    private void precheckThenOpenChainTest() {
        new Thread(() -> {
            final List<String> blocking = new ArrayList<>();
            final List<String> warning = new ArrayList<>();
            try {
                for (Diagnostics.Check c : Diagnostics.healthCheck(this)) {
                    if (c.ok) continue;
                    if (isBlockingCheck(c.title)) blocking.add("· " + c.title + "：" + c.detail);
                    else warning.add("· " + c.title + "：" + c.detail);
                }
            } catch (Throwable t) {
                blocking.add("体检执行异常：" + t.getMessage());
            }
            runOnUiThread(() -> showPrecheckResult(blocking, warning));
        }).start();
    }

    private void showPrecheckResult(List<String> blocking, List<String> warning) {
        if (blocking.isEmpty() && warning.isEmpty()) {
            openChainTest();
            return;
        }
        StringBuilder sb = new StringBuilder();
        if (!blocking.isEmpty()) {
            sb.append("以下问题可能影响写入环节：\n\n");
            for (String x : blocking) sb.append(x).append("\n");
        }
        if (!warning.isEmpty()) {
            sb.append("\n").append(blocking.isEmpty() ? "以下提示不影响测试：\n\n"
                    : "其他提示（不影响测试）：\n\n");
            for (String x : warning) sb.append(x).append("\n");
        }
        sb.append("\n\n仍想先试试完整流程？点「继续测试」。");
        if (!blocking.isEmpty()) sb.append("\n若确认是环境问题，点「去排查」按向导逐项处理。");

        UiKit.dialog(this, P,
                blocking.isEmpty() ? R.drawable.ic_flask : R.drawable.ic_alert,
                blocking.isEmpty() ? "可以测试" : "体检未全通过",
                sb.toString().trim(), null,
                UiKit.primary("继续测试", this::openChainTest),
                blocking.isEmpty() ? UiKit.ghost("取消", null) : UiKit.ghost("去排查", this::runTroubleshoot),
                UiKit.ghost("知道了", null));
    }

    private boolean isBlockingCheck(String title) {
        if (title == null) return false;
        return title.contains("root") || title.contains("Root")
                || title.contains("sqlite3") || title.contains("笔记库");
    }

    private void openChainTest() {
        try {
            startActivity(new Intent(this, ChainTestActivity.class));
        } catch (Throwable t) {
            Toast.makeText(this, "打开失败：" + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // ================================================================
    //  小工具
    // ================================================================

    /** 次要按钮（就地编辑区的保存 / 重置 / 取消） */
    private TextView smallButton(String label, String kind) {
        return smallButton(label, kind, 0);
    }

    /** v4.0.1：带矢量图标的小按钮（图标与文字同色） */
    private TextView smallButton(String label, String kind, int iconRes) {
        TextView b = UiKit.text(this, label, 13.5f, 0xFFFFFFFF);
        b.setGravity(Gravity.CENTER);
        b.setTypeface(null, Typeface.BOLD);
        b.setPadding(dp(8), dp(10), dp(8), dp(10));
        if ("primary".equals(kind)) {
            b.setBackground(UiKit.bg(P.accent, this, 13));
        } else if ("warn".equals(kind)) {
            b.setBackground(UiKit.bg(UiKit.WARN, this, 13));
        } else if ("ghostLine".equals(kind)) {
            b.setTextColor(P.accentInk);
            b.setBackground(UiKit.bg(0x00000000, this, 13, P.accent, 1.4f));
        } else {
            b.setTextColor(UiKit.INK2);
            b.setBackground(UiKit.bg(P.cardSoft, this, 13, P.line, 1f));
        }
        if (iconRes != 0) {
            android.graphics.drawable.Drawable d = getDrawable(iconRes);
            if (d != null) {
                d = d.mutate();
                d.setTint(b.getCurrentTextColor());
                d.setBounds(0, 0, dp(16), dp(16));
                b.setCompoundDrawables(d, null, null, null);
                b.setCompoundDrawablePadding(dp(6));
            }
        }
        return b;
    }

    private String appVersionLabel() {
        try {
            return "v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "v?";
        }
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
