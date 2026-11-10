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
    /** v4.0.3：上次运行过的 versionCode（用来判断「这次是升级」） */
    private static final String KEY_SEEN_VERSION_CODE = "seen_version_code";
    /** v4.0.3：上次运行过的点分版本名（只用于提示文案） */
    private static final String KEY_SEEN_VERSION_NAME = "seen_version_name";
    private static final String KEY_SYS_DIRECT = "sys_direct_write";
    private static final String REPO_URL = "https://github.com/O-kai/Xiaomi-HyperOs-pickup-code-grabber";
    private static final String ISSUES_URL = REPO_URL + "/issues";
    /**
     * 开发者的酷安个人主页。
     *
     * <p>v4.0.3 第二轮：原先指向一个具体帖子（/feed/73558591）——
     * 帖子会沉，链接也会随版本失效，新用户点进去可能看到几年前的动态。
     * 个人主页长期有效，也不会因为换版本而失效。
     */
    private static final String COOLAPK_URL = "https://coolapk.com/u/1222904";
    private static final String CHANGELOG_URL = REPO_URL + "/blob/main/CHANGELOG.md";
    /** 黑名单默认关键词（恢复默认时回填用；唯一来源在 TodoWriter） */
    private static final String BLACKLIST_DEFAULT = TodoWriter.DEFAULT_BLACKLIST;
    /** 来源识别覆盖的快递公司数（菜鸟 / 丰巢 / 京东 / 顺丰 / 中通 / 圆通 / 韵达 / 申通 / 邮政） */
    private static final int IDENTIFY_SOURCES = 9;
    /** 默认待办模板（唯一来源在 TodoWriter，避免两处各抄一份） */
    private static final String TPL_DEFAULT = TodoWriter.TPL_DEFAULT;
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
    private LinearLayout pickupCard;
    private LinearLayout pickupRows;
    private TextView pickupCount;
    private LinearLayout heroCard;
    private ImageView heroIcon;
    private TextView heroTitle, heroSub;
    private LinearLayout checklistCard;
    private LinearLayout checklistBox;
    private TextView deployBtn, troubleshootBtn;
    /** v4.0.2 第三轮：清单里的「点击体检」按钮（手动跑完整校验） */
    private TextView healthBtn;
    /** 清单底部的操作区（体检 / 部署 / 排查向导） */
    private LinearLayout healthActionsBox;
    /** v4.3：新用户「开始配置」引导入口（未完全就绪时显示） */
    private TextView guideBtn;
    /** 4.1：一键链路测试入口（从「规则 → 工具」移到首页） */
    private LinearLayout chainTestBtn;
    /** 4.2：自定义模板的占位符说明卡（选「自定义」时显示） */
    private LinearLayout tplVarHelp;

    /** 4.0 Stage 2.1：本地库变更监听（前台实时刷新待取件卡） */
    private PickupRepository.ChangeListener pickupListener;

    /** 4.0：界面是否已经建好。首启模式选择时不建界面，生命周期回调要跳过视图操作。 */
    private boolean uiReady = false;

    /** 4.0：简洁模式——单页（首页内容 + 反馈），不建另外三个 Tab。 */
    private boolean simpleMode = false;
    /** 底栏渐隐遮罩（简洁模式无底栏，需要一起隐藏）。 */
    private View navScrim;
    /**
     * 底栏渐隐遮罩的高度（dp）。
     *
     * <p>它同时是「内容底部要留多少空白」的基准：内容底部留白必须 ≥ 遮罩高度，
     * 否则最后一张卡片滚到底时仍然在遮罩下面（被底栏压住、也点不到）。
     */
    /**
     * 底部渐隐遮罩的高度（dp）。
     *
     * <p>它同时是「内容区底部要预留多少」的唯一标尺——
     * 预留小于遮罩高度，最后一张卡就会被底栏吞掉（4.0 踩过这个坑）。
     *
     * <p>v4.0.3：188 → 132。用户反馈「首页滑到底下面有一块空白区域」。
     * 原因不是内容留白，而是遮罩的下半段是纯色：188dp 里 55% 是实底，
     * 滚到底时那 100dp 看起来就是一块生硬的空白。
     * 现在把总高收短、实底比例降到 42%（132 × 0.42 ≈ 55dp，足够盖住底栏本体），
     * 上方留出更长的渐隐带——内容是「淡进」底栏的，而不是撞上一堵色墙。
     */
    /** 底栏渐隐遮罩的高度（dp）——只影响视觉，不影响滚动 */
    private static final int NAV_SCRIM_DP = 132;
    /**
     * 页面内容的底部留白（dp）。必须比遮罩更高：
     * 留白只是「能滚多远」，遮罩是「盖住多高」，两者相等时最后一行的下边缘
     * 正好抵在遮罩上沿，手指点下去会先被底栏接走。
     *
     * <p>v4.0.3 第二轮实测：留白沿用 132dp 时，首页的「一键链路测试」
     * 滚到底只露出半行、点不到。加到 176dp 后完整露出。
     */
    private static final int NAV_CONTENT_BOTTOM_DP = 176;
    /** 简洁模式右上角的「进阶模式 ›」入口。 */
    private TextView modeSwitch;

    /** 4.0 Stage 2.1：体检结果缓存，避免每次 onResume 都 su（Magisk 会反复弹授权） */
    private static final long HEALTH_CACHE_TTL = 120_000L;
    private volatile List<Diagnostics.Check> lastHealthChecks;
    private volatile boolean lastHealthOk;
    private volatile boolean lastHealthDeploy;
    private volatile String lastHealthErr;
    private volatile long lastHealthAt;
    /**
     * v4.0.1：上一次体检是不是「完整体检」。
     *
     * <p>用来区分措辞——轻量体检只覆盖 2 项，不能说成「N 项全部通过」。
     */
    private volatile boolean lastHealthFull;

    // ==================== 应用 ====================
    private TextView backendValue, envValue, extraSummary;
    /** v4.6：「取件码写入目标」整行（用于切换后就地刷新） */
    private LinearLayout backendRowRef;
    /** v4.6：该行右侧值视图的容器（放「自动/手动 · 目标名」组合 View） */
    private LinearLayout backendValueHolder;
    private LinearLayout extraBox;

    // ==================== 规则 ====================
    private TextView urSubView, urBadgeView;
    private TextView blPreview;
    private LinearLayout blActionRow, blBtnRow;
    private EditText blEdit;

    // ==================== 设置 ====================
    private UiKit.Switch notiSw, sdwSw, blurSw, netSw, reminderSw;
    /** 取件提醒行（时间变化后要刷新副标题） */
    private LinearLayout reminderRow;
    /** 「外观」行（主题/配色变化后要刷新副标题） */
    private LinearLayout appearanceRow;
    /** 「检查更新」行（自动检查开关变化后要刷新副标题） */
    private LinearLayout updateRow;
    /** 本地记录管理（保留期 / 导出导入 / 清理），与取件记录页共用实现 */
    private final RecordManager recordManager = new RecordManager(this);
    private TextView notiScopeHintView;
    private LinearLayout sdwRow;
    private TextView tplValueView, tplPreviewText;
    /** v4.0.3：「写入格式」整张卡——「仅内部记录」（不写系统待办）时它没有意义，需要整张隐藏 */
    private LinearLayout tplCardRef;
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
    /** v4.0.2 第三轮：本次体检是不是用户手动点的（决定体检后清单要不要保持展开） */
    private boolean healthManual = false;

    // ================================================================
    //  创建
    // ================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // v4.0：先定主题再 setContentView，否则会先按浅色建界面
        UiKit.applyTheme(this);
        super.onCreate(savedInstanceState);
        P = UiKit.palette(this);
        currentMode = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("todo_mode", TodoWriter.MODE_FULL);
        if (savedInstanceState != null) currentTab = savedInstanceState.getInt("tab", 0);

        PickupExtractor.init(this);
        UpdateCenter.maybeCheck(this);
        // v4.0.2 第八轮：部署位置迁到应用私有目录后，升级上来的用户需要补齐文件。
        // 纯文件拷贝、不开线程池也不碰 root —— 打开 App 绝不会因此弹授权框。
        new Thread(() -> {
            try { Repair.ensureInstalledSilently(this); } catch (Throwable ignored) { }
        }, "pickup-ensure-sqlite").start();
        ProcessSync.pushThrottled(this);
        // v4.0.3 第二轮：内部记录开着、Provider 却被停用，说明状态不一致
        // （可能来自旧版本的误操作），放回来。关着的时候不碰——那时它本就该是停用的。
        if (LocalRecord.isOn(this) && !WidgetPresence.isEnabled(this)) {
            LocalRecord.restoreWidget(this);
        }
        // v4.0.3：记录管理（保留期 / 导入导出 / 清理 / 导入历史）改到
        // 「取件记录 → 管理」这一个入口，设置页不再重复列出。
        // 数据变化后要刷新首页的待取件卡片——RecordManager 之前没人回调，
        // notifyChanged() 是空转的，导入历史后首页数字不会更新。
        recordManager.setOnChanged(this::refreshPickupCard);
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1001);
        }
        // v4.1.0：荣耀侧优先用 CalendarProvider 写待办（这条路不需要 root），
        // 首次打开时问一次日历权限。用户拒绝也不影响后续的 root 直写路径，
        // 所以这里不拦、不提示、不重复弹。
        if (NotesBackend.BACKEND_HONOR_TODO.equals(NotesBackend.detect(this))) {
            Honor.requestCalendarPermission(this, 1002);
        }

        // 4.0 双模式：首次启动先让用户选「简洁 / 完整」。
        // 简洁模式不是另一个 Activity，而是同一套首页组件的单页形态——
        // 这样规则集、写入目标、写入格式、链路测试只有一份实现，不会两套逻辑漂移。
        String uiMode = UiMode.get(this);
        if (uiMode == null) {
            showModeChooser();
            return;
        }
        simpleMode = UiMode.SIMPLE.equals(uiMode);

        // 页面骨架：头部（固定）+ 内容区（可滚动，随 Tab 切换）+ 底栏
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(P.bg);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(20), dp(14), dp(20), dp(6));
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        headerTitle = UiKit.text(this, "取件码助手", 26f, UiKit.INK);
        headerTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titleRow.addView(headerTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // v4.0.1：移除右上角的「进阶模式 ›」入口。
        // 用户要求把它移到页面最下方——「离开简洁模式」是页尾动作，
        // 放在标题栏里既占位置，又容易让人以为是另一个功能。
        modeSwitch = UiKit.text(this, "进阶模式 ›", 13f, P.accent);
        modeSwitch.setPadding(dp(12), dp(8), 0, dp(8));
        modeSwitch.setVisibility(View.GONE);
        modeSwitch.setOnClickListener(v -> showModeSwitchDialog());
        header.addView(titleRow);
        headerSub = UiKit.text(this, "", 13f, UiKit.INK3);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.setMargins(0, dp(5), 0, 0);
        header.addView(headerSub, subLp);
        // v4.7：头部是「项目版本信息」的入口。
        //
        // v4.0.3：只保留在【设置】页。用户原话：「在首页、通知提取、高级规则这 3 个页面，
        // 点击顶部的头部，就不要弹出来这个内容了；设置页依然保留。」
        // 理由很实在——那三页的标题写着功能名，点它弹版本号是纯粹的意外行为。
        View.OnClickListener verEntry = v -> {
            if (currentTab == 3) showVersionDialog();
        };
        headerTitle.setOnClickListener(verEntry);
        headerSub.setOnClickListener(verEntry);
        header.setOnClickListener(verEntry);
        page.addView(header);

        // v4.0.3：底栏改为【悬浮在内容之上】——用 FrameLayout 叠加，内容从底栏下方滚过。
        // 之前把底栏当作内容区下方的独立一行，导致内容被压短、底栏上方多出一条空白。
        contentArea = new FrameLayout(this);
        contentHost = new LinearLayout(this);
        contentHost.setOrientation(LinearLayout.VERTICAL);
        contentArea.addView(contentHost, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 底部渐隐遮罩：底栏所在的整段先铺满页面底色（完全不透），再往上柔和过渡到透明——
        // 既保证半透明底栏里不出现卡片文字残影，又保留"内容从底栏下方滚过"的悬浮观感
        navScrim = new View(this);
        GradientDrawable scrimBg = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{ P.bg, P.bg, P.bg & 0x00FFFFFF });
        // 4.0 Stage 2.1：底栏覆盖区必须是【实底】。
        // 原实现只给 55% 底色，85% 半透明的底栏叠上去后，下面的卡片文字仍能透出来，
        // 真机截图里「一键链路测试」与底栏文字撞在一起。现在改为：
        //   0 → 0.42 实底（盖住底栏本体，约 55dp）
        //   0.42 → 1 柔和淡出（保留「内容从底栏下滚过」的悬浮感）
        // v4.0.3：实底比例 0.55 → 0.42。遮罩总高已从 188 收到 132，
        // 再按原来的 0.55 算实底仍有 72dp，滚到底还是能看到一块生硬的空白。
        scrimBg.setColors(new int[]{ P.bg, P.bg, P.bg & 0x00FFFFFF },
                new float[]{ 0f, 0.42f, 1f });
        navScrim.setBackground(scrimBg);
        FrameLayout.LayoutParams scrimLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(NAV_SCRIM_DP));
        scrimLp.gravity = Gravity.BOTTOM;
        contentArea.addView(navScrim, scrimLp);

        navHolder = new LinearLayout(this);
        navHolder.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams navLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        navLp.gravity = Gravity.BOTTOM;
        contentArea.addView(navHolder, navLp);   // 后加 = 在遮罩之上

        page.addView(contentArea, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(page);

        // 四个 Tab 只构建一次：切换时换子视图，控件引用始终有效（体检刷新等后台回调不会打到废弃视图上）。
        // 简洁模式只建首页（含规则集/写入目标/写入格式/链路测试/反馈），不建通知提取与高级规则。
        tabViews[0] = buildHomeContent(simpleMode);
        if (!simpleMode) {
            tabViews[1] = buildAppsTab();
            tabViews[2] = buildRulesTab();
            tabViews[3] = buildSettingsTab();
        } else {
            modeSwitch.setVisibility(View.VISIBLE);
            navHolder.setVisibility(View.GONE);
            if (navScrim != null) navScrim.setVisibility(View.GONE);
        }

        // 进阶模式恢复上次停留的 Tab；简洁模式只有首页
        showTab(simpleMode ? 0 : currentTab);
        uiReady = true;
        refreshHealth();
        refreshUserRuleCard();
        renderNotiSwitchUi();
    }

    /**
     * 首次启动的模式选择。
     *
     * 不做成单独引导页：一次弹窗、两句话讲清差别即可，
     * 选错也能在「设置 → 界面模式」里改回来，不制造额外门槛。
     */
    private void showModeChooser() {
        UiKit.applySystemBars(this);

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setBackgroundColor(P.bg);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(dp(24), dp(28), dp(24), dp(28));

        // 品牌区
        TextView brand = UiKit.text(this, "取件码助手", 26f, P.navInk);
        brand.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        root.addView(brand);

        TextView tagline = UiKit.text(this, "把取件码放好，取件时一眼看到", 13f, P.label);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.setMargins(0, dp(6), 0, 0);
        root.addView(tagline, tlp);

        // 说明区
        TextView title = UiKit.text(this, "选择使用方式", 21f, P.navInk);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.setMargins(0, dp(36), 0, 0);
        root.addView(title, hlp);

        TextView sub = UiKit.text(this, "两种模式数据共享，之后可在设置里随时切换", 12.5f, P.label);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.setMargins(0, dp(8), 0, 0);
        root.addView(sub, slp);

        // 左右两张模式卡（按钮分别在各自卡片底部，形成左一个右一个）
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBaselineAligned(false);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.setMargins(0, dp(20), 0, 0);
        root.addView(row, rlp);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cardLp.setMargins(0, 0, dp(6), 0);
        row.addView(buildModeCard(true), cardLp);

        LinearLayout.LayoutParams cardLp2 = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cardLp2.setMargins(dp(6), 0, 0, 0);
        row.addView(buildModeCard(false), cardLp2);

        // 底部说明
        TextView foot = UiKit.text(this, "可随时在「设置 → 界面模式」切换", 11.5f, P.muteInk);
        foot.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.setMargins(0, dp(22), 0, 0);
        root.addView(foot, flp);

        sv.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(sv);

        // 入场动画：从下往上轻推 + 淡入
        root.setAlpha(0f);
        root.setTranslationY(dp(18));
        root.animate().alpha(1f).translationY(0f).setDuration(240).start();
    }

    /**
     * 模式选择卡：图标 + 标题 + 说明 + 底部按钮。
     * 简洁模式用主色实心按钮（推荐），进阶模式用浅色底按钮，保持同一套 UiKit 视觉。
     */
    private View buildModeCard(boolean simple) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));

        GradientDrawable cardBg = UiKit.bg(P.card, this, 20);
        cardBg.setStroke(dp(1), simple ? P.accent : P.line);
        card.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x14000000), cardBg, null));
        card.setElevation(UiKit.dp(this, 1.5f));

        // 图标：简洁=主色实心，进阶=浅色底
        LinearLayout iconWrap = new LinearLayout(this);
        iconWrap.setGravity(Gravity.CENTER);
        iconWrap.setBackground(UiKit.bg(simple ? P.accent : P.accentSoft, this, 13));
        ImageView icon = UiKit.icon(this,
                simple ? R.drawable.ic_home : R.drawable.ic_grid, 22,
                simple ? P.onAccent : P.accentInk);
        iconWrap.addView(icon);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(iconWrap, new LinearLayout.LayoutParams(dp(40), dp(40)));
        if (simple) {
            View gap = new View(this);
            head.addView(gap, new LinearLayout.LayoutParams(0, 1, 1f));
            TextView rec = UiKit.badge(this, "推荐", P.accentSoft, P.accentInk);
            head.addView(rec);
        }
        card.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = UiKit.text(this,
                simple ? "一页全包" : "四个页面分区", 15.5f, P.navInk);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.setMargins(0, dp(12), 0, 0);
        card.addView(title, tlp);

        TextView desc = UiKit.text(this,
                simple ? "取件信息、官方规则、写入格式、链路测试都在一页"
                        : "通知提取、高级规则、诊断与外观分开设置",
                11.5f, P.muteInk);
        desc.setLineSpacing(dp(2), 1f);
        desc.setMinLines(3);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.setMargins(0, dp(6), 0, 0);
        card.addView(desc, dlp);

        TextView btn = UiKit.text(this,
                simple ? "用简洁模式" : "用进阶模式", 13.5f,
                simple ? P.onAccent : P.accentInk);
        btn.setGravity(Gravity.CENTER);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setPadding(0, dp(11), 0, dp(11));
        GradientDrawable btnBg = UiKit.bg(simple ? P.accent : P.accentSoft, this, 13);
        int ripple = simple
                ? UiKit.alpha(P.onAccent, 0.22f)
                : 0x22000000;
        btn.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(ripple),
                btnBg, null));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.setMargins(0, dp(16), 0, 0);
        card.addView(btn, blp);

        View.OnClickListener pick = v -> chooseMode(simple ? UiMode.SIMPLE : UiMode.ADVANCED);
        card.setOnClickListener(pick);
        btn.setOnClickListener(pick);
        return card;
    }

    private void chooseMode(String mode) {
        UiMode.set(this, mode);
        recreate();
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
        // 4.1：Tab 名「应用」→「来源」（页面内容是"哪些 App 的通知会被读取"，
        // 叫「应用」容易与「写入目标 App」混淆）
        String[] titles = { "取件码助手", "通知提取", "高级规则", "设置" };
        headerTitle.setText(simpleMode ? "取件码助手" : titles[index]);
        headerSub.setText(headerSubtitle(simpleMode ? 0 : index));

        if (simpleMode) return;
        navHolder.removeAllViews();
        LinearLayout bar = UiKit.tabbar(this, P, UiKit.navBlur(this), index, this::showTab);
        navHolder.addView(bar, UiKit.tabbarLp(this));

        // v4.0.1：进入「通知提取」页时才查 LSPosed 作用域。
        // 这一步要走 su（读 LSPosed 配置库），会触发 Magisk 的
        // 「已授予超级用户权限」提示——放在这里，既不打扰每次打开 App 的人，
        // 又能保证进这一页的人看到准确的「通知能不能用」判断。
        if (index == 1) {
            checkNotiHookScopeAsync(false);
        }
    }

    /**
     * v4.3：每个 Tab 的头部副标题——只做这一页的信息归总，不再四个页面共用一句。
     *
     * 写法思路：首页给版本与定位（新用户第一次看到，需要知道这是什么）；
     * 其余三页给「这一页在干什么」的一句话总结。
     *
     * <p>v4.0.1：首页这一行改成两段不同样式——
     * 版本号用中灰半粗（「V4.0.0」），署名用更浅的灰与更小的字号（「design by 欧锴」）。
     * 之前是一整串同色同字号，署名和版本号混在一起看不出层次。
     */
    private CharSequence headerSubtitle(int index) {
        switch (index) {
            case 0:
                return versionSubtitle();
            case 1:
                return "从 App 通知里提取取件码 · 短信通道不受此限制";
            case 2:
                return "官方规则之外的自定义识别规则 · 一般无需改动";
            case 3:
                return "外观、更新、排查与关于";
            default:
                return "";
        }
    }

    /**
     * 首页副标题：「V4.0.0 · design by 欧锴」。
     *
     * <p>版本号与署名用不同字号与颜色：版本号是信息主体（半粗、中灰），
     * 署名是次要信息（小一号、浅灰），中间用「·」分隔。
     */
    private CharSequence versionSubtitle() {
        String ver = appVersionLabel();
        String credit = "design by 欧锴";
        String sep = "  ·  ";
        String all = ver + sep + credit;

        android.text.SpannableString ss = new android.text.SpannableString(all);
        // 版本号：半粗 + 主文字色
        ss.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                0, ver.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ss.setSpan(new android.text.style.ForegroundColorSpan(UiKit.INK2),
                0, ver.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ss.setSpan(new android.text.style.RelativeSizeSpan(1.06f),
                0, ver.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        // 署名：小一号 + 浅灰
        int creditStart = ver.length() + sep.length();
        ss.setSpan(new android.text.style.ForegroundColorSpan(UiKit.INK3),
                creditStart, all.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ss.setSpan(new android.text.style.RelativeSizeSpan(0.9f),
                creditStart, all.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return ss;
    }

    private void rebuildUi() {
        recreate();
    }

    private ScrollView scrollPage() {
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setHorizontalScrollBarEnabled(false);
        sv.setFillViewport(true);
        sv.setClipToPadding(false);
        // 底部留出底栏高度 + 呼吸空间，保证最后一张卡能完整滚到遮罩上方。
        // 留白要比遮罩更高一截（见 NAV_CONTENT_BOTTOM_DP 的说明）——
        // 之前两者都取 NAV_SCRIM_DP，最后一张卡片（首页的「一键链路测试」）
        // 滚到底时仍然压在遮罩下面，看起来像被底栏吞掉一半，也点不到。
        // 内容越长（待取件卡片 + 写入格式编辑器都展开）越容易撞上。
        sv.setPadding(dp(18), dp(4), dp(18), bottomSafePadding(NAV_CONTENT_BOTTOM_DP));
        return sv;
    }

    // ================================================================
    //  首页
    // ================================================================

    private View buildHomeTab() {
        return buildHomeContent(false);
    }

    /**
     * 升级后的一次性提示（v4.0.3）。
     *
     * <p><b>为什么需要</b>：本模块的抓取逻辑跑在 {@code system_server} 里，
     * 由 LSPosed 在<b>进程启动时</b>注入。用户装完新版本不重启的话，
     * 系统进程里跑的还是旧代码——新功能不生效、修好的 bug 也还在，
     * 很容易被当成「更新没用」。这个提示只出现一次，说清楚就行。
     *
     * <p><b>怎么判断「是升级」</b>：只看偏好里的历史版本是不够的——
     * 从没有这个键的旧版本（≤4.0.2）升上来时，键是空的，会被误判成首次安装。
     * 所以再用 {@code lastUpdateTime - firstInstallTime} 兜一层：
     * 明显大于 0 就说明这台机器上装过更早的版本。
     *
     * <p>首次安装不提示（没有「升级」这回事）；点关闭后记住当前版本，不再出现。
     */
    private void maybeAddUpgradeNotice(LinearLayout box) {
        final int cur = AppVersion.code(this);
        if (cur <= 0) return;
        final String curName = AppVersion.dotted(this);

        android.content.SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        final int seen = sp.getInt(KEY_SEEN_VERSION_CODE, 0);
        final String seenName = sp.getString(KEY_SEEN_VERSION_NAME, "");

        // v4.0.3：首次安装也要提示。
        //
        // 用户原话：「只要安装了 4.0.3 版本的 APP，安装完之后就要弹出来一次重启提示。
        // 因为很多其他用户可能是 3.1.0 版本，不是非得是 4.0.2 升级到 4.0.3。」
        // 而且首次安装本来也必须重启——LSPosed 只在进程启动时注入，
        // 不重启的话用户会以为「装完就能用」，结果什么都抓不到。
        boolean upgraded = false;
        try {
            android.content.pm.PackageInfo pi = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            upgraded = pi.lastUpdateTime - pi.firstInstallTime > 60_000L;
        } catch (Throwable ignored) { }
        if (seen != 0 && seen >= cur) return;   // 这个版本已经提示过，点掉后不再出现
        final boolean firstInstall = seen == 0 && !upgraded;

        final String head;
        if (seen != 0 && seenName != null && !seenName.isEmpty()) {
            head = "V" + seenName + " → V" + curName;
        } else {
            // seen==0：可能是真·首次安装，也可能是从没写过这个键的旧版本升上来（版本跳跃）
            head = (upgraded ? "已更新到 V" : "已安装 V") + curName;
        }

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(UiKit.bg(P.chipBg, this, 14));
        card.setPadding(dp(14), dp(11), dp(8), dp(11));

        ImageView ic = UiKit.icon(this, R.drawable.ic_refresh, 18, P.accentInk);
        card.addView(ic);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.setMargins(dp(10), 0, dp(6), 0);

        TextView t = UiKit.text(this, head + " · 建议重启一次", 13f, P.accentInk);
        t.setTypeface(null, Typeface.BOLD);
        col.addView(t);

        // v4.0.6：升级提示说清"哪些已经生效、哪些还没换"。
        // 原来只写「重启手机后新代码才会生效」，读起来像"不重启就不能用"，
        // 实际上界面、搜索、导出、规则热更新都已经生效，只有系统进程里的解析代码没换。
        TextView sub = UiKit.text(this,
                firstInstall
                        ? "LSPosed 要在重启后才会注入本模块；首次使用请先勾选作用域"
                        : "已经生效：打开、搜索、导出、规则更新。"
                        + "还没换：短信与通知的解析代码（它跑在系统进程里）。"
                        + "不重启也能正常用，只是新短信仍按旧逻辑认；方便时再重启一次即可",
                11.5f, P.label);
        sub.setLineSpacing(dp(1), 1f);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.setMargins(0, dp(3), 0, 0);
        col.addView(sub, slp);
        card.addView(col, clp);

        TextView close = UiKit.text(this, "✕", 13f, P.muteInk);
        close.setGravity(Gravity.CENTER);
        close.setPadding(dp(10), dp(8), dp(10), dp(8));
        close.setOnClickListener(v -> {
            sp.edit().putInt(KEY_SEEN_VERSION_CODE, cur)
                    .putString(KEY_SEEN_VERSION_NAME, curName).apply();
            box.removeView(card);
        });
        card.addView(close);

        box.addView(card, UiKit.cardLp(this, 12));
    }

    /**
     * 首页内容。simple=true 时追加「反馈与关于」卡片，并去掉底栏预留空间。
     */
    private View buildHomeContent(boolean simple) {
        ScrollView sv = scrollPage();
        if (simple) {
            // 简洁模式没有底栏，底部不需要 148dp 的遮挡留白
            sv.setPadding(dp(18), dp(4), dp(18), bottomSafePadding(28));
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        sv.addView(box);

        // 4.0：待取件优先于体检/设置，首页第一张卡就是本地取件库。
        // v4.0.3 第二轮：这张卡是「软件内部记录」的表现形式——
        // 用户把它关掉，整块（标题 / 条数 / 查看全部 / 每条记录）一起消失，
        // 下面的规则集、写入目标、链路测试照常显示，不留空壳。
        if (LocalRecord.isOn(this)) {
            buildPickupCard(box);
        }

        // v4.0.3：升级后的一次性提示，插在状态卡正上方
        maybeAddUpgradeNotice(box);

        // 状态卡
        heroCard = new LinearLayout(this);
        heroCard.setOrientation(LinearLayout.HORIZONTAL);
        heroCard.setGravity(Gravity.CENTER_VERTICAL);
        heroCard.setBackground(UiKit.bg(P.hero, this, 18));
        heroCard.setPadding(dp(20), dp(17), dp(20), dp(17));
        heroCard.setElevation(dp(1));
        // v4.0.2 第三轮：点状态卡 = 展开 / 收起校验清单（不再直接跑体检）。
        //
        // 用户原话：「点击『一切正常已激活』，正常列举出校验内容。
        // 在内容下方补充一个『点击体检』的步骤……再次点击上面的标题，内容可以缩回去，
        // 回到正常的场景。……省得每次用户点到外面的标题或内容上就开始体检。
        // 其实正常情况下一切都正常，不用整天体检，可以减少这样的操作。」
        //
        // 完整体检改由清单里的「点击体检」按钮手动触发；
        // 设置页的「排查问题向导」也并了进来（见清单底部「进入排查向导」）。
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
        // 与「待取件」卡之间留 12dp，和下面几张卡的间距一致
        box.addView(heroCard, UiKit.cardLp(this, 12));

        // v4.0.2：删掉了独立的「完整体检（含 root 检测）」按钮。
        // 完整体检已整合进上面的状态卡——点一下状态卡就跑完整校验。
        // 用户原话：「把完整体检完全整合进状态卡里……磨磨唧唧的东西那么多，没有任何意义」。

        // 体检清单（点状态卡展开 / 收起；异常时强制展开，不让红字被藏起来）
        checklistCard = UiKit.card(this, P);
        checklistBox = new LinearLayout(this);
        checklistBox.setOrientation(LinearLayout.VERTICAL);
        checklistCard.addView(checklistBox);

        // v4.0.2 第三轮：清单底部的操作区。
        //
        // 用户原话：「点击『一切正常已激活』，正常列举出校验内容。
        // 在内容下方补充一个『点击体检』的步骤……再次点击上面的标题，内容可以缩回去，
        // 回到正常的场景。这样做的话有个好处，省得每次用户点到外面的标题或内容上
        // 就开始体检。其实正常情况下一切都正常，不用整天体检。」
        //
        // 所以：状态卡点击 = 展开/收起清单（不触发任何体检）；
        // 真正的完整体检由清单里的「点击体检」手动触发。
        healthActionsBox = new LinearLayout(this);
        healthActionsBox.setOrientation(LinearLayout.VERTICAL);
        checklistCard.addView(healthActionsBox);

        // 「点击体检」——手动跑完整校验（含 root 检测）
        healthBtn = UiKit.button(this, P, "点击体检（完整校验，含 root）", "primary");
        LinearLayout.LayoutParams hbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        hbLp.setMargins(0, dp(12), 0, 0);
        healthBtn.setLayoutParams(hbLp);
        healthBtn.setOnClickListener(v -> {
            healthBtn.setEnabled(false);
            healthBtn.setText("正在体检…");
            refreshHealth(true);
        });
        healthActionsBox.addView(healthBtn);

        // 一键部署 sqlite3（只在缺 sqlite3 时出现）
        deployBtn = UiKit.buttonIcon(this, P, R.drawable.ic_rocket, "一键部署 sqlite3（自动完成）", "ok");
        LinearLayout.LayoutParams depLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        depLp.setMargins(0, dp(10), 0, 0);
        deployBtn.setLayoutParams(depLp);
        deployBtn.setVisibility(View.GONE);
        deployBtn.setOnClickListener(v -> deploySqlite3());
        healthActionsBox.addView(deployBtn);

        // 「进入排查向导」——状态不正常时的主要出口；
        // 状态正常时也保留（用户可能想主动做一次深度排查）
        troubleshootBtn = UiKit.button(this, P, "进入排查向导", "ghostLine");
        LinearLayout.LayoutParams tsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        tsLp.setMargins(0, dp(10), 0, 0);
        troubleshootBtn.setLayoutParams(tsLp);
        troubleshootBtn.setVisibility(View.GONE);
        troubleshootBtn.setOnClickListener(v -> runTroubleshoot());
        healthActionsBox.addView(troubleshootBtn);

        box.addView(checklistCard, UiKit.cardLp(this, 10));
        checklistCard.setVisibility(View.GONE);

        // v4.3：「开始配置」——新用户首次安装的总入口，按顺序走完六步。
        // 仅在未完全就绪时出现（全绿时藏起来，不打扰已用起来的用户）。
        guideBtn = UiKit.buttonIcon(this, P, R.drawable.ic_rocket, "开始配置（按顺序走完）", "ok");
        LinearLayout.LayoutParams gLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        gLp.setMargins(0, dp(10), 0, 0);
        guideBtn.setLayoutParams(gLp);
        guideBtn.setVisibility(View.GONE);
        guideBtn.setOnClickListener(v -> showSetupGuide());
        box.addView(guideBtn);

        // ============ v4.3 首页 ============
        // 用户要求：去掉所有分组小标题（写入目标 / 待办模板 / 官方规则集），
        // 卡片本身已经能表达含义，小标题是冗余。
        // 顺序：状态卡 → 官方规则集 → 取件码写入目标 → 写入格式 → 一键链路测试

        // 官方规则集（4.3：移到状态卡正下方）
        // v4.0 收口：副标题原先是「N 组锚点词 · N 特征词 · N 负向词」——
        // 那是给维护者看的词表规模，对使用者没有意义，还把这一行撑得很挤。
        // 改成说明这行可点（点一下检查最新规则），徽标负责显示结果。
        LinearLayout ruleCard = UiKit.card(this, P);
        // v4.5：「已是最新」徽标改为可点击，点一下手动拉取最新规则。
        // 频率限制 8 秒一次（见 checkRulesManually），避免连点造成无谓请求。
        ruleBadgeView = UiKit.badgeAccent(this, P, "已是最新");
        LinearLayout ruleRow = UiKit.row(this, P, R.drawable.ic_rules,
                "官方规则集 v" + PickupExtractor.getActiveRulesVersion(),
                "点击检查最新规则", ruleBadgeView);
        ruleRow.setOnClickListener(v -> checkRulesManually());
        ruleCard.addView(ruleRow);
        box.addView(ruleCard, UiKit.cardLp(this, 12));

        // 取件码写入目标（4.3：改名 + 右侧同时显示「自动」与自动选中的具体目标）
        LinearLayout waCard = UiKit.card(this, P);
        // v4.6：右侧值放进一个可复用的容器，切换后替换容器内容即可就地刷新
        backendValueHolder = new LinearLayout(this);
        backendValueHolder.setOrientation(LinearLayout.HORIZONTAL);
        backendValueHolder.setGravity(Gravity.CENTER_VERTICAL);
        backendValueHolder.addView(buildBackendValueView());
        LinearLayout backendRow = UiKit.row(this, P, R.drawable.ic_target, "取件码写入目标",
                null, backendValueHolder);
        backendRowRef = backendRow;
        LinearLayout backendAcc = (LinearLayout) backendRow.getChildAt(backendRow.getChildCount() - 1);
        backendAcc.addView(UiKit.icon(this, R.drawable.ic_chev_down, 17, P.muteInk));
        backendRow.setOnClickListener(v -> showBackendPicker());
        waCard.addView(backendRow);
        // v4.4：这两张是独立卡片，必须有间距（原为 0f 导致与上面的规则集贴在一起）
        box.addView(waCard, UiKit.cardLp(this, 12));

        // 写入格式（待办模板）
        LinearLayout tplCard = UiKit.card(this, P);
        tplCardRef = tplCard;
        tplValueView = UiKit.value(this, P, modeLabel(currentMode));
        LinearLayout tplRow = UiKit.row(this, P, R.drawable.ic_template, "写入格式",
                "待办里写什么内容", tplValueView);
        tplRow.setOnClickListener(v -> toggleTplEditor());
        tplCard.addView(tplRow);

        tplEditorBox = new LinearLayout(this);
        tplEditorBox.setOrientation(LinearLayout.VERTICAL);
        // v4.5：默认展开——用户要求三种模式都要直接看到效果预览，不用点开
        tplEditorBox.setVisibility(View.VISIBLE);
        buildTplEditor();
        tplCard.addView(tplEditorBox);
        box.addView(tplCard, UiKit.cardLp(this, 12));
        // 首屏也按当前写入目标同步一次（比如上次关掉了系统待办，只剩内部记录）
        applyBackendDependentUi();

        // 一键链路测试（放在页面最下方——整套流程走完后的最终验证）
        // v4.0.1：样式做得更显眼——它是这一页最该被用到的功能
        // （验证「收到短信 → 写进待办」整条链路通不通），
        // 之前是一行普通文字入口，跟别的行长得一样，用户根本注意不到。
        LinearLayout ctCard = UiKit.card(this, P);
        ctCard.setBackground(UiKit.bg(P.accentSoft, this, 18));
        chainTestBtn = toolRow(R.drawable.ic_flask, "一键链路测试",
                "验证「收到短信 → 写入」整条链路是否通", v -> precheckThenOpenChainTest());
        chainTestBtn.setPadding(dp(16), dp(13), dp(16), dp(13));
        ctCard.addView(chainTestBtn);
        box.addView(ctCard, UiKit.cardLp(this, 12));

        if (simple) {
            // v4.0.3 第二轮：简洁模式底部只留一个入口。
            //
            // 上一版把 5 条次要功能（错题本 / QQ 群 / 酷安 / 打赏 / 进阶模式）
            // 直接摊在首页最下面，有两点不好：
            //   · 用户得滚过整张写入格式卡才看得到，等于「藏起来了」；
            //   · 它们全是低频操作，却把「一页看完」的简洁模式又拉长了。
            // 现在收进一个「更多选项」弹窗，首页只留一个和「一键链路测试」
            // 同样分量的入口。
            LinearLayout moreCard = UiKit.card(this, P);
            moreCard.setBackground(UiKit.bg(P.accentSoft, this, 18));
            LinearLayout moreBtn = toolRow(R.drawable.ic_grid, "更多选项",
                    "反馈、打赏与更多设置",
                    v -> showMoreOptionsDialog());
            moreBtn.setPadding(dp(16), dp(13), dp(16), dp(13));
            moreCard.addView(moreBtn);
            box.addView(moreCard, UiKit.cardLp(this, 12));
        }

        return sv;
    }

    /** 简洁模式 → 进阶模式：同一 Activity 重建，数据与当前 Tab 都不受影响。 */
    private void switchToFullMode() {
        UiMode.set(this, UiMode.ADVANCED);
        recreate();
    }

    /**
     * 模式切换确认弹窗（v4.0.1）。
     *
     * <p>之前进阶→简洁用的是系统 AlertDialog，简洁→进阶则直接切换、没有弹窗——
     * 两边行为不一致，而且系统弹窗的观感跟 App 内部的 UiKit 卡片差很多
     * （用户反馈「这个对话框展示的内容和下面的按钮，与软件内部的 UI 差别有点大」）。
     *
     * <p>现在两个方向共用这一个弹窗，都走 UiKit，视觉与 App 内部一致。
     */
    private void showModeSwitchDialog() {
        boolean toSimple = !simpleMode;
        String title = toSimple ? "切换到简洁模式" : "切换到进阶模式";
        String message = toSimple
                ? "简洁模式是一页：取件信息、官方规则、写入目标、写入格式、"
                        + "一键链路测试都在首页，不出现通知来源与高级规则。\n\n"
                        + "进阶模式保留四个页面：首页、通知提取、高级规则、设置。"
                : "进阶模式保留四个页面：首页、通知提取、高级规则、设置，"
                        + "规则、来源与外观设置都能单独调整。\n\n"
                        + "简洁模式只有一页，把常用内容都收在一起。";
        String action = toSimple ? "切换到简洁模式" : "切换到进阶模式";

        UiKit.dialog(this, P, R.drawable.ic_grid, title, message, null,
                UiKit.primary(action, () -> {
                    UiMode.set(this, toSimple ? UiMode.SIMPLE : UiMode.ADVANCED);
                    recreate();
                }),
                UiKit.ghost("取消", null));
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

    // v4.0.2：原先的 toggleChecklist（点状态卡展开/收起清单）已移除——
    // 状态卡的点击现在用来跑完整体检，清单由体检结果自动决定显不显示。

    // ================================================================
    //  应用
    // ================================================================

    private void buildPickupCard(LinearLayout parent) {
        pickupCard = UiKit.card(this, P);
        pickupCard.setPadding(dp(16), dp(14), dp(16), dp(10));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = UiKit.text(this, "待取件", 17f, UiKit.INK);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        head.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        pickupCount = UiKit.text(this, "0 个", 12f, UiKit.INK3);
        head.addView(pickupCount);

        TextView all = UiKit.text(this, "查看全部 ›", 12f, P.accent);
        all.setPadding(dp(12), dp(4), 0, dp(4));
        all.setOnClickListener(v -> openPickupList());
        head.addView(all);
        pickupCard.addView(head);

        pickupRows = new LinearLayout(this);
        pickupRows.setOrientation(LinearLayout.VERTICAL);
        pickupCard.addView(pickupRows);

        parent.addView(pickupCard, UiKit.cardLp(this, 0));
    }

    private void refreshPickupCard() {
        if (pickupRows == null) return;
        PickupRepository.get(this).pendingAsync(3, list -> {
            if (isFinishing() || isDestroyed() || pickupRows == null) return;
            pickupRows.removeAllViews();
            int count = list == null ? 0 : list.size();
            pickupCount.setText(count >= 3 ? "3+ 个" : count + " 个");
            if (count == 0) {
                TextView empty = UiKit.text(this, "暂无待取件 · 收到短信后会自动出现",
                        12.5f, UiKit.INK3);
                empty.setPadding(0, dp(10), 0, dp(6));
                pickupRows.addView(empty);
                return;
            }
            for (PickupRecord record : list) {
                pickupRows.addView(buildPickupRow(record));
            }
        });
    }

    private View buildPickupRow(PickupRecord record) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(4));

        TextView code = UiKit.text(this, record.code, 19f, UiKit.INK);
        code.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        row.addView(code);

        TextView place = UiKit.text(this,
                record.place == null || record.place.trim().isEmpty() ? "—" : record.place,
                12f, UiKit.INK3);
        place.setMaxLines(1);
        place.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams placeLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        placeLp.setMargins(dp(10), 0, dp(8), 0);
        row.addView(place, placeLp);

        // v4.0.1：按钮文案从「已取」改成「✓ 取件」。
        // 「已取」在这里是个动作按钮（点了就标记为已取），
        // 但字面意思更像状态标签——用户看截图会以为这条已经取过了。
        // 改成动词短语后，一眼能看出「点它会把它取掉」。
        TextView done = UiKit.text(this, "✓ 取件", 12f, P.accent);
        done.setPadding(dp(8), dp(4), 0, dp(4));
        done.setOnClickListener(v -> PickupActions.markDone(this, record, (localChanged, backendRc) -> {
            String msg;
            if (localChanged <= 0) {
                msg = "标记失败";
            } else if (backendRc == 0) {
                msg = "已标记已取件";
            } else {
                msg = "已在本机标记，系统待办同步失败";
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
            refreshPickupCard();
        }));
        row.addView(done);

        row.setOnClickListener(v -> copyPickupCode(record.code));
        return row;
    }

    private void copyPickupCode(String code) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("pickupcode", code));
            Toast.makeText(this, "已复制：" + code, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "复制失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void openPickupList() {
        startActivity(new Intent(this, PickupListActivity.class));
    }

    private View buildAppsTab() {
        ScrollView sv = scrollPage();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        sv.addView(box);

        // v4.7：末尾补「添加/移除后需重启手机」，并加粗（用户要求）
        // v4.0 收口：「需要重启」这件事原本在开头说一遍、末尾又说一遍，
        // 合并到末尾那条提示里说一次就够——同一件事说两遍只会让人以为是两件事。
        String introText =
                "下方 App 收到含取件码的通知时，会被自动读取并按你的写入目标保存。\n"
                        + "短信通道不受此限制：任何号码的取件短信都会识别。\n"
                        + "识别规则与短信通道共用一套规则集。";
        android.text.SpannableString introSp = new android.text.SpannableString(introText);
        // UiKit.text(...) 只接受 String，会把 Span 压成纯文本，必须自己 new TextView
        TextView intro = new TextView(this);
        intro.setText(introSp);
        intro.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        intro.setTextColor(UiKit.INK3);        intro.setLineSpacing(dp(3), 1.1f);
        intro.setBackground(UiKit.bg(P.cardSoft, this, 14));
        intro.setPadding(dp(14), dp(11), dp(14), dp(11));
        box.addView(intro, UiKit.cardLp(this, 12));

        LinearLayout notiCard = UiKit.card(this, P);
        notiSwitchOn = isNotiHookFlagOn();
        verifyNotiFlagIfRootReady();
        notiSw = new UiKit.Switch(this, P, notiSwitchOn);
        notiSw.setOnToggle(this::onNotiToggle);
        // v4.4：去掉「多平台」可点击（弹窗已移除），副标题改为说明这个开关的作用
        LinearLayout notiRow = UiKit.row(this, P, R.drawable.ic_bell, "通知取件提取",
                "开启：拦截下方 App 的通知并提取取件码\n"
                        + "关闭：不再从这些 App 的通知里提取", notiSw);
        notiCard.addView(notiRow);

        notiScopeHintView = UiKit.text(this,
                "LSPosed 作用域未勾选「Android 系统（android）」，此时通知取件提取不会生效（重启手机也没用）。",
                11.5f, UiKit.INK3);
        notiScopeHintView.setLineSpacing(dp(2), 1.0f);
        notiScopeHintView.setVisibility(View.GONE);
        notiCard.addView(notiScopeHintView);
        box.addView(notiCard, UiKit.cardLp(this, 0f));

        box.addView(UiKit.groupLabel(this, P, "已支持的 App"));
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

        // v4.7：手动添加的来源排在内置之后，右侧放红色叉叉（可点移除）。
        // 内置项显示「内置」徽标、不可移除；手动项显示「手动添加」+ 红叉，
        // 让「哪些能删、哪些不能删」一眼可辨。
        final java.util.List<String> extras = new java.util.ArrayList<>();
        for (String p : ProcessSync.getExtraNotiPkgs(this).split("[\\n,，\\s]+")) {
            String t = p.trim();
            if (!t.isEmpty()) extras.add(t);
        }
        for (String pkg : extras) {
            appCard.addView(UiKit.divider(this, P));
            String label = NotiHook.PKG_LABELS.containsKey(pkg)
                    ? NotiHook.PKG_LABELS.get(pkg) : pkg;

            // 右侧配件：先「手动添加」徽标，再红色叉叉
            LinearLayout acc = new LinearLayout(this);
            acc.setOrientation(LinearLayout.HORIZONTAL);
            acc.setGravity(Gravity.CENTER_VERTICAL);
            acc.addView(UiKit.badgeMute(this, P, "手动添加"));
            ImageView del = UiKit.icon(this, R.drawable.ic_x, 18, UiKit.DANGER);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(18), dp(18));
            dlp.setMargins(dp(10), 0, dp(4), 0);
            del.setLayoutParams(dlp);
            acc.addView(del);

            LinearLayout row = UiKit.row(this, P, 0, label, pkg, acc);

            // v4.8：手动添加项也要显示应用图标，与上面内置项保持一致的排版。
            // 直接复用 AppIcons.bind —— 它会先查内置资源、再查系统图标，
            // 最后兜底到通用图标，不需要在这里重写一套取图逻辑。
            ImageView iv = new ImageView(this);
            AppIcons.bind(iv, this, pkg);
            LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(dp(38), dp(38));
            ivLp.setMargins(0, 0, dp(13), 0);
            iv.setLayoutParams(ivLp);
            row.addView(iv, 0);

            // 整行可点 + 红叉单独可点，都走移除确认
            row.setOnClickListener(v -> confirmRemoveExtra(pkg));
            del.setOnClickListener(v -> confirmRemoveExtra(pkg));
            appCard.addView(row);
        }

        appCard.addView(UiKit.divider(this, P));
        LinearLayout addBox = UiKit.row(this, P, R.drawable.ic_plus, "添加自定义来源",
                extras.isEmpty()
                        ? "填入任意 App 包名，或从已安装应用中选择"
                        : "已手动添加 " + extras.size() + " 个",
                UiKit.chevron(this, P));
        appCard.addView(addBox);
        box.addView(appCard);

        // v4.5：自定义来源改用弹窗承载。
        // 原先是「就地展开」——extraBox 挂在 box 里、addBox 在 appCard 里，
        // 两者不同父容器，展开后位置在卡片下方，用户滚到的地方看不到，
        // 表现为「点了没反应、根本点不开」。弹窗不受滚动与父容器影响，最可靠。
        addBox.setOnClickListener(v -> showCustomSourceDialog());

        // v4.0 收口：把「需要重启」的两处说法合成一条提示（见上方注释）。
        TextView scopeNote = UiKit.text(this,
                "需在 LSPosed 作用域勾选「Android 系统」；来源增删约 30 秒生效，不用重启",
                12.5f, P.heroSubInk);
        scopeNote.setBackground(UiKit.bg(P.accentTint, this, 12));
        scopeNote.setPadding(dp(13), dp(11), dp(13), dp(11));
        scopeNote.setLineSpacing(dp(2), 1.1f);
        box.addView(scopeNote, UiKit.cardLp(this, 10));

        // 4.1：写入与适配已移到首页（原在「应用」页，与设置页重复）
        return sv;
    }

    /**
     * v4.6：从列表里直接移除某个手动添加的来源（二次确认）。
     */
    private void confirmRemoveExtra(final String pkg) {
        UiKit.dialog(this, P, R.drawable.ic_ban, "移除这个来源？",
                pkg + "\n\n移除后它的通知不再被读取，约 30 秒生效。",
                null,
                UiKit.primary("移除", () -> {
                    java.util.List<String> keep = new java.util.ArrayList<>();
                    for (String p : ProcessSync.getExtraNotiPkgs(this).split("[\\n,，\\s]+")) {
                        String t = p.trim();
                        if (!t.isEmpty() && !t.equals(pkg)) keep.add(t);
                    }
                    StringBuilder sb = new StringBuilder();
                    for (String p : keep) {
                        if (sb.length() > 0) sb.append(',');
                        sb.append(p);
                    }
                    ProcessSync.setExtraNotiPkgs(this, sb.toString());
                    Toast.makeText(this, "已移除 " + pkg + "（约 30 秒生效）",
                            Toast.LENGTH_SHORT).show();
                    rebuildUi();
                }),
                UiKit.ghost("取消", null)).show();
    }

    /**
     * v4.7：自定义通知来源——**纯添加界面**。
     *
     * 用户要求：既然是点「添加」进来的，弹窗里就不该能删除、也不该显示
     * 已添加的包名。所有移除操作都放回外面的列表页（红叉）。
     *
     * 流程：填包名（手输或从已安装选择）→ 点「添加」→ 关闭弹窗并刷新列表。
     * 添加是「追加」语义，不会覆盖已有的手动来源。
     */
    private void showCustomSourceDialog() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);

        TextView hint = UiKit.text(this,
                "填入要监听的 App 包名，或点下方「已安装应用」直接挑。\n"
                        + "添加后约 30 秒生效，不用重启。",
                12.5f, UiKit.INK3);
        hint.setLineSpacing(dp(3), 1.1f);
        wrap.addView(hint);

        final EditText edit = new EditText(this);
        edit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        edit.setTextColor(UiKit.INK2);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        edit.setBackground(UiKit.bg(P.cardSoft, this, 12, P.line, 1f));
        edit.setPadding(dp(12), dp(10), dp(12), dp(10));
        edit.setHint("每行一个 App 包名，例：\ncom.shanshui.flash");
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(88));
        elp.setMargins(0, dp(10), 0, 0);
        wrap.addView(edit, elp);

        TextView pick = smallButton("从已安装选择", "ghostLine");
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        plp.setMargins(0, dp(10), 0, 0);
        pick.setLayoutParams(plp);
        pick.setOnClickListener(v -> showInstalledAppPicker(edit));
        wrap.addView(pick);

        final android.app.Dialog[] dlg = new android.app.Dialog[1];
        dlg[0] = UiKit.dialog(this, P, R.drawable.ic_plus, "添加自定义来源",
                null, wrap, 0,
                UiKit.primary("添加", () -> {
                    PkgInput.Result r = PkgInput.parse(
                            edit.getText().toString(), this, true);
                    if (r.valid.isEmpty()) {
                        String problem = PkgInput.describe(r);
                        Toast.makeText(this,
                                problem != null ? problem : "请先填写至少一个包名",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    // 追加到现有列表（去重），不覆盖
                    java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>();
                    for (String p : ProcessSync.getExtraNotiPkgs(this).split("[\\n,，\\s]+")) {
                        String t = p.trim();
                        if (!t.isEmpty()) merged.add(t);
                    }
                    int before = merged.size();
                    merged.addAll(r.valid);
                    StringBuilder sb = new StringBuilder();
                    for (String p : merged) {
                        if (sb.length() > 0) sb.append(',');
                        sb.append(p);
                    }
                    ProcessSync.setExtraNotiPkgs(this, sb.toString());
                    int added = merged.size() - before;
                    Toast.makeText(this,
                            added > 0
                                    ? "已添加 " + added + " 个来源 ✓\n约 30 秒生效，不用重启"
                                    : "这些来源已在列表中，无需重复添加",
                            Toast.LENGTH_SHORT).show();
                    if (dlg[0] != null) dlg[0].dismiss();
                    rebuildUi();
                }),
                UiKit.ghost("取消", null));
        dlg[0].show();
    }

    /**
     * v4.3：从已安装应用里选择通知来源。
     *
     * 原先只能手打包名，用户得自己去别处查包名再回来粘贴——门槛很高，还容易拼错。
     * 现在直接列出手机上已安装的、有桌面入口的应用（名称 + 包名），
     * 点选后自动把包名填进编辑框，用户点保存即可。
     *
     * 说明：
     * - 用 queryIntentActivities(LAUNCHER) 取「用户能看到的 App」，避免列出几百个系统组件
     * - 已在内置白名单里的 App 不再重复列出
     * - 列表按名称排序，支持搜索（App 多的时候靠滚动很难找）
     */
    private void showInstalledAppPicker(final EditText targetEdit) {
        final java.util.List<String[]> apps = new java.util.ArrayList<>();
        try {
            android.content.Intent main = new android.content.Intent(Intent.ACTION_MAIN);
            main.addCategory(Intent.CATEGORY_LAUNCHER);
            java.util.List<android.content.pm.ResolveInfo> ris =
                    getPackageManager().queryIntentActivities(main, 0);
            java.util.Set<String> seen = new java.util.HashSet<>();
            java.util.Set<String> builtin = new java.util.HashSet<>(
                    java.util.Arrays.asList(NotiHook.BUILTIN_PKGS));
            for (android.content.pm.ResolveInfo ri : ris) {
                String pkg = ri.activityInfo.packageName;
                if (pkg == null || pkg.equals(getPackageName())) continue;
                if (builtin.contains(pkg) || !seen.add(pkg)) continue;
                CharSequence label = ri.loadLabel(getPackageManager());
                String name = label == null ? pkg : label.toString().trim();
                if (name.isEmpty()) name = pkg;
                apps.add(new String[]{name, pkg});
            }
        } catch (Throwable t) {
            Toast.makeText(this, "读取已安装应用失败：" + t, Toast.LENGTH_LONG).show();
            return;
        }
        if (apps.isEmpty()) {
            Toast.makeText(this, "没有找到可添加的应用", Toast.LENGTH_SHORT).show();
            return;
        }
        // 按名称排序，方便找
        java.util.Collections.sort(apps, (a, b) -> a[0].compareToIgnoreCase(b[0]));

        // 搜索框 + 可滚动的应用列表
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);

        final EditText search = new EditText(this);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        search.setHint("搜索应用名或包名");
        search.setSingleLine(true);
        search.setBackground(UiKit.bg(P.cardSoft, this, 12, P.line, 1f));
        search.setPadding(dp(12), dp(9), dp(12), dp(9));
        wrap.addView(search);

        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(list);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(360));
        slp.setMargins(0, dp(8), 0, 0);
        wrap.addView(sv, slp);

        final android.app.Dialog[] dlg = new android.app.Dialog[1];

        final Runnable[] render = new Runnable[1];
        render[0] = () -> {
            list.removeAllViews();
            String q = search.getText().toString().trim().toLowerCase();
            int shown = 0;
            for (String[] app : apps) {
                if (!q.isEmpty()
                        && !app[0].toLowerCase().contains(q)
                        && !app[1].toLowerCase().contains(q)) continue;
                if (shown > 0) list.addView(UiKit.divider(this, P));
                LinearLayout row = UiKit.row(this, P, 0, app[0], app[1],
                        UiKit.icon(this, R.drawable.ic_plus, 17, P.accentInk));
                final String pkg = app[1];

                // v4.8：左侧补上应用图标，比纯文字列表好认得多
                try {
                    ImageView iv = new ImageView(this);
                    iv.setImageDrawable(getPackageManager().getApplicationIcon(pkg));
                    LinearLayout.LayoutParams ivLp =
                            new LinearLayout.LayoutParams(dp(34), dp(34));
                    ivLp.setMargins(0, 0, dp(12), 0);
                    iv.setLayoutParams(ivLp);
                    row.addView(iv, 0);
                } catch (Throwable ignored) {
                    // 取不到图标就保持纯文字，不影响选择
                }

                row.setOnClickListener(v -> {
                    // 追加到编辑框（保留用户已填的其它包名）
                    String cur = targetEdit.getText().toString().trim();
                    if (!cur.isEmpty() && !cur.endsWith("\n")) cur = cur + "\n";
                    targetEdit.setText(cur + pkg);
                    targetEdit.setSelection(targetEdit.getText().length());
                    if (dlg[0] != null) dlg[0].dismiss();
                    Toast.makeText(this, "已填入 " + pkg + "，点「添加」生效",
                            Toast.LENGTH_SHORT).show();
                });
                list.addView(row);
                shown++;
            }
            if (shown == 0) {
                TextView empty = UiKit.text(this, "没有匹配的应用", 13f, UiKit.INK3);
                empty.setPadding(dp(8), dp(14), dp(8), dp(14));
                list.addView(empty);
            }
        };
        render[0].run();

        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) { render[0].run(); }
        });

        dlg[0] = UiKit.dialog(this, P, R.drawable.ic_grid, "选择应用",
                "选中后会填入包名，点「添加」生效", wrap,
                UiKit.ghost("取消", null));
        dlg[0].show();
    }


    private String envLabel() {
        String b = NotesBackend.detect(this);
        if (NotesBackend.BACKEND_COLOROS_TODO.equals(b)
                || NotesBackend.BACKEND_COLOROS_NOTE.equals(b)) return "ColorOS 16";
        if (NotesBackend.BACKEND_HONOR_TODO.equals(b)) return "荣耀 MagicOS";
        return "小米 / 红米";
    }

    private String envDetail() {
        String b = NotesBackend.detect(this);
        if (NotesBackend.BACKEND_COLOROS_TODO.equals(b)
                || NotesBackend.BACKEND_COLOROS_NOTE.equals(b)) return "一加 / OPPO / realme · 已适配";
        if (NotesBackend.BACKEND_HONOR_TODO.equals(b)) return "HONOR · MagicOS · 已适配";
        return "MIUI · HyperOS · 已适配";
    }

    private void showEnvPicker() {
        CharSequence[] items = {
                "小米 / 红米（MIUI · HyperOS）— 已适配",
                "ColorOS 16（一加 / OPPO / realme）— 已适配",
                "荣耀（MagicOS 7+）— 已适配",
                "其他 ROM（OriginOS / Flyme / HarmonyOS …）— 可写自定义规则"
        };
        int checked = 0;
        if ("ColorOS 16".equals(envLabel())) checked = 1;
        else if ("荣耀 MagicOS".equals(envLabel())) checked = 2;
        UiKit.chooser(this, P, R.drawable.ic_phone, "环境适配", items, checked, w -> {
            if (w == 3) {
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

        // 4.2：官方规则集已移到首页，本页只放「高级规则」。
        // 加一段说明：这里是高级用户的自定义场景，普通用户一般不需要动。
        // v4.6：删掉「这里是高级用户的自定义场景」这句空话与随后的空行（用户要求）
        TextView advNote = UiKit.text(this,
                "内置的官方规则已覆盖菜鸟、京东、顺丰、丰巢、驿收发等主流驿站的"
                        + "常见取件短信写法，绝大多数情况开箱即用，不需要在这里添加任何东西。\n\n"
                        + "只有当你遇到「官方规则认不出、但确实是取件短信」的情况时，"
                        + "才需要在这里为那个驿站单独写一条规则。\n\n"
                        + "如果不确定怎么填，建议先到「首页 → 状态卡 → 进入排查向导」走一遍，"
                        + "比自行修改更稳妥。",
                12.5f, UiKit.INK3);
        advNote.setLineSpacing(dp(3), 1.15f);
        advNote.setBackground(UiKit.bg(P.cardSoft, this, 14));
        advNote.setPadding(dp(15), dp(13), dp(15), dp(13));
        box.addView(advNote, UiKit.cardLp(this, 12));

        // v4.6：整张卡片可点击进入子页面；「新建规则」不再暴露在外（用户要求：
        // 新建/测试/停用/删除/编辑 全部收进「用户自定义高级规则」页面内操作）
        box.addView(UiKit.groupLabel(this, P, "我的规则"));
        LinearLayout urCard = UiKit.card(this, P);
        urSubView = UiKit.text(this, userRuleSummary(), 12.5f, UiKit.INK3);
        urBadgeView = UiKit.badgeMute(this, P, "未启用");
        LinearLayout urRow = UiKit.row(this, P, R.drawable.ic_rules,
                "用户自定义高级规则", null, urBadgeView);
        urRow.setOnClickListener(v -> openUserRules());
        urCard.addView(urRow);
        urCard.addView(urSubView);
        // v4.6：子标题也绑定点击，避免「点了文字没反应」（用户反馈点不开）
        urSubView.setOnClickListener(v -> openUserRules());
        urCard.addView(UiKit.spacer(this, 10));
        urCard.setOnClickListener(v -> openUserRules());
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

        // 4.1：「工具」组已拆分——链路测试→首页；错题本/排查向导/诊断报告→设置→排查
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

        // v4.3：「写入行为」分组在普通设备上是空的（冻结免疫直写仅 ColorOS 显示），
        // 用户要求空的东西不要显示——所以这一组改成条件渲染：
        // 只有当冻结免疫直写真的可见时，才建这个卡片。
        sdwRow = new LinearLayout(this);
        sdwRow.setOrientation(LinearLayout.VERTICAL);
        boolean sdwOn = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_SYS_DIRECT, false);
        sdwSw = new UiKit.Switch(this, P, sdwOn);
        sdwSw.setOnToggle(this::onSdwToggle);
        LinearLayout sdwItem = UiKit.row(this, P, R.drawable.ic_shield, "冻结免疫直写",
                "ColorOS / 荣耀：划掉后台仍可写入（需给系统进程授权 root）", sdwSw);
        sdwRow.addView(sdwItem);
        updateSdwDisplay();
        // updateSdwDisplay() 内部会按环境把 sdwRow 设为 GONE/VISIBLE，这里先挂上再判断
        LinearLayout sdwCard = UiKit.card(this, P);
        sdwCard.addView(sdwRow);
        box.addView(sdwCard, UiKit.cardLp(this, 0f));
        sdwCard.setVisibility(sdwRow.getVisibility());

        // v4.5：「外观与更新」→「基础设置」。
        //
        // v4.0 收口：这一版把入口做了合并，理由是「设置里同一个去处出现多次」
        // 会让人以为有好几件事要做。合并后：
        //   · 外观 + 主题       → 一个「外观」入口（弹窗里分两段）
        //   · 自动检查 + 手动查 → 一个「检查更新」（右侧开关 + 点行立即查）
        //   · QQ群 / GitHub / 诊断报告 / 错题本 / sqlite3 自检
        //                       → 一个「反馈与支持」入口（弹窗里列全部渠道）
        //   · 项目仓库 + GitHub 反馈 + 更新日志 → 一个「项目主页」
        // 设置页行数从 21 降到 13，功能一项没少。
        // v4.0.3：打赏从「关于」提到设置页最上方（用户要求）。
        // 单独一张单行卡，不并进「基础设置」——它不是设置项，混进去反而更难找。
        LinearLayout donateCard = UiKit.card(this, P);
        donateCard.addView(toolRow(R.drawable.ic_heart, "打赏作者", "纯自愿，不影响任何功能",
                v -> startActivity(new Intent(this, DonateActivity.class))));
        box.addView(donateCard, UiKit.cardLp(this, 12));

        box.addView(UiKit.groupLabel(this, P, "基础设置"));
        LinearLayout perCard = UiKit.card(this, P);

        // 4.0：界面模式切换（简洁 / 进阶），放在基础设置第一位
        TextView modeValue = UiKit.value(this, P, "进阶");
        LinearLayout modeRow = UiKit.row(this, P, R.drawable.ic_grid, "界面模式",
                "简洁一页看完，进阶分四页", modeValue);
        modeRow.setOnClickListener(v -> showModeSwitchDialog());
        perCard.addView(modeRow);
        perCard.addView(UiKit.divider(this, P));

        // 4.0：桌面小组件外观设置（改为二级页入口；右侧不再重复报「字号 · 条数」）
        // v4.0.3 第二轮：内部记录关着时不出现——组件读的就是那份记录，
        // 记录关了它没有内容，Provider 也已经被停用，点进去只会一脸问号。
        if (LocalRecord.isOn(this)) {
            perCard.addView(toolRow(R.drawable.ic_home, "桌面小组件",
                    "字号、显示内容与隐私",
                    v -> startActivity(new Intent(this, WidgetSettingsActivity.class))));
        }

        // 4.0：外观（主题 + 配色合并成一个入口）
        perCard.addView(UiKit.divider(this, P));
        appearanceRow = UiKit.row(this, P, R.drawable.ic_palette, "外观",
                appearanceLabel(), UiKit.chevron(this, P));
        appearanceRow.setOnClickListener(v -> showAppearanceDialog());
        perCard.addView(appearanceRow);

        // 4.0：模糊效果
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

        // 4.0：取件提醒
        perCard.addView(UiKit.divider(this, P));
        reminderSw = new UiKit.Switch(this, P, Reminder.isEnabled(this));
        reminderSw.setOnToggle(on -> {
            Reminder.setEnabled(this, on);
            Toast.makeText(this, on
                    ? "已开启取件提醒，每天 " + Reminder.timeLabel(this) + " 检查一次"
                    : "已关闭取件提醒", Toast.LENGTH_SHORT).show();
            refreshReminderRow();
        });
        reminderRow = UiKit.row(this, P, R.drawable.ic_bell, "取件提醒",
                Reminder.isEnabled(this)
                        ? "每天 " + Reminder.timeLabel(this) + " 提醒还有多少件没取"
                        : "已关闭 · 点此设置每天的提醒时间",
                reminderSw);
        reminderRow.setOnClickListener(v -> pickReminderTime());
        perCard.addView(reminderRow);

        // 4.0：检查更新（自动开关与手动检查合并为一行）
        perCard.addView(UiKit.divider(this, P));
        netSw = new UiKit.Switch(this, P, NetPolicy.autoNetAllowed(this));
        netSw.setOnToggle(on -> {
            NetPolicy.setAutoNetAllowed(this, on);
            Toast.makeText(this, on
                    ? "已开启自动联网（3 天一次静默检查软件与规则更新）"
                    : "已进入纯离线模式 ✓\n自动联网已停止；核心功能不受影响，仍可手动检查更新",
                    Toast.LENGTH_LONG).show();
            refreshUpdateRow();
        });
        updateRow = UiKit.row(this, P, R.drawable.ic_refresh, "检查更新",
                updateLabel(), netSw);
        updateRow.setOnClickListener(v -> checkUpdateNow());
        perCard.addView(updateRow);

        box.addView(perCard, UiKit.cardLp(this, 0f));

        // ==================== 关于 ====================
        // v4.0.2 第三轮：「排查问题向导」并入首页状态卡
        // （点状态卡 → 校验清单 → 「进入排查向导」），
        // 「反馈与支持」移到「关于」分组里。
        //
        // 用户原话：「把设置里的『反馈与支持』内容放到『关于』里面去。
        // 这样设置里面就可以少一个细分类，界面清爽一点。」
        //
        // 设置页于是只剩「基础设置」+「关于」两组，入口不再分散在多处。
        box.addView(UiKit.groupLabel(this, P, "关于"));
        LinearLayout aboutCard = UiKit.card(this, P);
        aboutCard.addView(toolRow(R.drawable.ic_info, "关于模块",
                appVersionLabel() + " · 作者 欧锴（O-kai）", v -> showAboutDialog()));
        aboutCard.addView(UiKit.divider(this, P));
        aboutCard.addView(toolRow(R.drawable.ic_github, "项目主页",
                "GitHub · 仓库、更新日志与 Issue", v -> openUrl(REPO_URL)));
        // v4.0.3 第二轮：原来这里是一个「反馈与支持」入口，点开再套一层弹窗，
        // 弹窗里还有「复制 JSON 诊断报告」和「GitHub Issues」。用户的要求是：
        // 那两项都去掉，剩下的三项直接平铺——设置页里不必再套一层。
        aboutCard.addView(UiKit.divider(this, P));
        aboutCard.addView(buildMissedRow());
        aboutCard.addView(UiKit.divider(this, P));
        aboutCard.addView(toolRow(R.drawable.ic_phone, "开发者 QQ 群",
                "901543676 · 进群备注「取件码助手」", v -> copyQqGroup()));
        aboutCard.addView(UiKit.divider(this, P));
        aboutCard.addView(toolRow(R.drawable.ic_globe, "开发者酷安主页",
                "在酷安留言找作者", v -> openUrl(COOLAPK_URL)));
        aboutCard.addView(UiKit.divider(this, P));
        // v4.0.2 第八轮：卸载残留清理。
        //
        // sqlite3 与依赖库现在装在应用私有目录，卸载时系统会一起删掉；
        // 但旧版本把它们放在 /data/local/tmp（应用数据之外），
        // 升级上来的用户那里还留着约 1.5MB。这里手动清一次。
        // v4.0.3：原来这条只叫「清理旧版本残留」，现在把「卸载前清理」也收进来，
        // 但不新增分组/按钮——设置页仍然只有两组，这条也只是一行。
        aboutCard.addView(toolRow(R.drawable.ic_refresh, "清理与卸载",
                "旧版本残留 · 卸载后会在手机上留下什么",
                v -> showCleanupDialog()));
        box.addView(aboutCard, UiKit.cardLp(this, 0f));

        return sv;
    }

    /** 「外观」行的副标题：主题 + 当前配色 */
    private String appearanceLabel() {
        return UiKit.themeModeLabel(this) + " · " + P.name;
    }

    /** 「检查更新」行的副标题：说明自动检查状态，点行立即检查 */
    private String updateLabel() {
        return NetPolicy.autoNetAllowed(this)
                ? "自动检查已开启（3 天一次）· 点此检查"
                : "自动检查已关闭 · 点此手动检查一次";
    }

    private void refreshUpdateRow() {
        if (updateRow == null) return;
        LinearLayout col = (LinearLayout) updateRow.getChildAt(1);
        if (col == null || col.getChildCount() < 2) return;
        TextView sub = (TextView) col.getChildAt(1);
        if (sub != null) sub.setText(updateLabel());
    }

    /**
     * 「记录保留期」行的副标题。
     *
     * <p>v4.0.1：唯一来源在 {@link RecordManager#retentionSubText()}——
     * 设置页与「管理本地记录」弹窗都要显示这句话，各写一份迟早漂移。
     */
    private String retentionLabel() {
        return recordManager.retentionSubText();
    }

    /** v4.3：使用说明弹窗（原先是页面底部一大段裸露文字） */
    private void showUsageDialog() {
        String text =
                "1. 在 LSPosed 里启用本模块，作用域必勾「Android 系统」\n"
                        + "   （列表底部、不带推荐角标的那一项），再加：\n"
                        + "   电话、短信、com.android.providers.telephony\n"
                        + "   小米设备另勾「小米笔记」\n\n"
                        + "2. 收到取件短信后自动保存：系统待办里一码一条、新码置顶，\n"
                        + "   App 内「待取件」同步出现，桌面小组件跟着刷新\n\n"
                        + "3. 通知可点击复制取件码；通知上「已取件」可一键勾选\n\n"
                        + "4. 需要 root（Magisk / KernelSU）授权一次；\n"
                        + "   sqlite3 缺失时首页会出现「一键部署」按钮\n\n"
                        + "5. 首次使用可点首页的「一键链路测试」验证整条链路\n\n"
                        + "6. 交流反馈：QQ 群 901543676（进群备注「取件码助手」）";
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        TextView tv = UiKit.text(this, text, 13f, UiKit.INK2);
        tv.setLineSpacing(dp(4), 1.15f);
        tv.setPadding(dp(4), dp(4), dp(4), dp(4));
        sv.addView(tv);
        UiKit.dialog(this, P, R.drawable.ic_book, "使用说明", null, sv, 70,
                UiKit.primary("知道了", null)).show();
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

    /**
     * v4.8：写入格式这一行现在**不再可折叠**。
     *
     * v4.5 起编辑器默认展开（用户要求三种模式都要直接看到效果预览），
     * 但 toggleTplEditor 的折叠逻辑还留着——于是点一下这一行反而把编辑器
     * 收起来、页面突然变短（用户说的「它会缩小」），右侧值也跟着消失。
     *
     * 现在点击这一行只做一件事：确保编辑器展开（幂等），不收起。
     */
    private void toggleTplEditor() {
        if (tplEditorBox.getVisibility() != View.VISIBLE) {
            tplEditorBox.setVisibility(View.VISIBLE);
        }
        if (tplValueView != null) tplValueView.setVisibility(View.VISIBLE);
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
        // v4.5：编辑按钮只在「自定义」模式下可见（极简/完整是预设格式，无可编辑内容）
        tplEditBtn.setVisibility(
                currentMode == TodoWriter.MODE_CUSTOM ? View.VISIBLE : View.GONE);
        tplEditBtn.setOnClickListener(v -> {
            tplEdit.setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString("todo_custom", TPL_DEFAULT));
            tplEdit.setVisibility(View.VISIBLE);
            tplBtnRow.setVisibility(View.VISIBLE);
            tplEditBtn.setVisibility(View.GONE);
            tplPreview.setVisibility(View.GONE);
            // v4.3：进入编辑态时才露出占位符说明
            if (tplVarHelp != null) tplVarHelp.setVisibility(View.VISIBLE);
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

        // v4.2：自定义模式的占位符说明。
        // 用户反馈：选了「自定义」却看不到有哪些占位符，占位符被删掉后就不知道怎么写了。
        // 这里在编辑框下方常驻一张说明卡，逐条列出占位符与它代表的内容。
        tplVarHelp = UiKit.card(this, P);
        tplVarHelp.setPadding(dp(13), dp(11), dp(13), dp(11));
        TextView vhTitle = UiKit.text(this, "可用占位符（点击可插入）", 12.5f, UiKit.INK3);
        tplVarHelp.addView(vhTitle);
        String[][] vars = {
                {"{code}", "取件码，如 6-4-3009（必填）"},
                {"{source}", "来源，如 菜鸟驿站（识别不到显示 —）"},
                {"{place}", "地点，如 江南绿城西门（识别不到显示 —）"},
                {"{time}", "写入时间，如 2026-10-22 00:39"},
        };
        for (String[] v : vars) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(7), 0, 0);
            TextView key = UiKit.text(this, v[0], 12.5f, P.accentInk);
            key.setTypeface(Typeface.create("monospace", Typeface.NORMAL));
            LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(
                    dp(66), ViewGroup.LayoutParams.WRAP_CONTENT);
            row.addView(key, klp);
            row.addView(UiKit.text(this, v[1], 12f, UiKit.INK3),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.setOnClickListener(click -> {
                int st = tplEdit.getSelectionStart();
                if (st < 0) st = tplEdit.getText().length();
                tplEdit.getText().insert(st, v[0]);
            });
            tplVarHelp.addView(row);
        }
        // v4.3：占位符说明默认隐藏，只有「自定义模式 + 点了编辑模板」时才显示。
        // 用户要求：正常状态下不要把这些没意义的东西显示出来。
        tplVarHelp.setVisibility(View.GONE);
        tplEditorBox.addView(tplVarHelp, UiKit.cardLp(this, 8));

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
        if (tplVarHelp != null) tplVarHelp.setVisibility(View.GONE);   // v4.3：收起时一并隐藏
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
        // v4.4：切换模式时先收起编辑态（否则自定义→极简时编辑框会残留），
        // 再渲染预览——三种模式都必须能看到效果预览
        collapseTplEditState();
        if (tplPreview != null) tplPreview.setVisibility(View.VISIBLE);
        renderTplPreview();
        // v4.8：同步刷新右侧的值文字。
        // 之前这里漏了——tplValueView 在创建后再没被更新过，
        // 于是无论切到哪个模式，右边永远显示进入页面时的那个值，
        // 必须退出重进才正确。这是「很低级的错误」的真正原因。
        syncTplValueView();
        Toast.makeText(this, "待办模板已切换为「" + label + "」", Toast.LENGTH_SHORT).show();
    }

    /**
     * v4.8：把「写入格式」右侧的值文字同步成当前模式。
     *
     * 注意可见性：编辑器展开时该文字是被隐藏的（由 toggleTplEditor 控制），
     * 所以这里只改文字、不动可见性，避免把收起状态下的文字又露出来。
     */
    private void syncTplValueView() {
        if (tplValueView == null) return;
        tplValueView.setText(modeLabel(currentMode));
    }

    private void restyleModes() {
        for (int i = 0; i < modeBtns.size(); i++) {
            boolean sel = modeIds[i] == currentMode;
            TextView b = modeBtns.get(i);
            b.setTextColor(sel ? P.onAccent : UiKit.INK2);
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

    /**
     * 外观面板：主题（跟随系统 / 浅色 / 深色）与配色（四套）合并到一个弹窗。
     *
     * <p>v4.0 收口：原先这是设置里两个独立入口（「外观」「主题」），
     * 两者都是"改界面长什么样"，分开摆会让人以为是两件事。
     */
    private void showAppearanceDialog() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);

        wrap.addView(UiKit.text(this, "主题", 12.5f, UiKit.INK3));
        String[] themes = {"跟随系统", "浅色", "深色"};
        int themeChecked = UiKit.themeMode(this);
        wrap.addView(segmentRow(themes, themeChecked, w -> {
            UiKit.setThemeMode(this, w);
            rebuildUi();
        }));

        wrap.addView(UiKit.spacer(this, 16));
        wrap.addView(UiKit.text(this, "配色", 12.5f, UiKit.INK3));
        String[] palettes = new String[UiKit.ALL.length];
        int paletteChecked = 0;
        for (int i = 0; i < UiKit.ALL.length; i++) {
            palettes[i] = UiKit.ALL[i].name;
            if (UiKit.ALL[i].id.equals(P.id)) paletteChecked = i;
        }
        final int pc = paletteChecked;
        wrap.addView(segmentRow(palettes, pc, w -> {
            UiKit.setPalette(this, UiKit.ALL[w]);
            rebuildUi();
        }));

        UiKit.dialog(this, P, R.drawable.ic_palette, "外观", null, wrap, 0);
    }

    /** 一排分段按钮 */
    private LinearLayout segmentRow(String[] labels, int checked,
                                    java.util.function.IntConsumer onPick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            boolean on = i == checked;
            TextView seg = UiKit.text(this, labels[i], 13.5f, on ? P.onAccent : P.accentInk);
            seg.setGravity(Gravity.CENTER);
            seg.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
            seg.setPadding(0, dp(10), 0, dp(10));
            seg.setBackground(UiKit.bg(on ? P.accent : P.chipBg, this, 12));
            seg.setOnClickListener(v -> {
                if (onPick != null) onPick.accept(idx);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(i == 0 ? 0 : dp(8), 0, 0, 0);
            seg.setLayoutParams(lp);
            row.addView(seg);
        }
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.setMargins(0, dp(8), 0, 0);
        row.setLayoutParams(rlp);
        return row;
    }

    /**
     * 「疑似漏抓错题本」这一行（v4.0.3 第二轮：从弹窗里搬出来，直接平铺）。
     *
     * <p>副标题里的条数会随清空变化，所以这里把「清空后刷新自己」的回调
     * 直接绑在行上——外层页面<strong>不能</strong>靠 {@link #rebuildUi()}
     * 来刷新：那个方法是 recreate()，会把正在显示的对话框一起销毁，
     * 用户点完「确认清空」会莫名其妙回到上一页。
     */
    private LinearLayout buildMissedRow() {
        final LinearLayout row = toolRow(R.drawable.ic_notebook, "疑似漏抓错题本",
                missedSub(missedCount()), null);
        // 点击事件单独设：toolRow 的参数里不能再引用 row 自己（「可能尚未初始化」）
        row.setOnClickListener(v -> showMissedSmsDialog(
                () -> setRowSub(row, missedSub(missedCount()))));
        return row;
    }

    /** 错题本当前条数（读失败时按 0，不让一行副标题把页面拖崩） */
    private int missedCount() {
        try {
            return MissedSmsStore.getAll(this).size();
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String missedSub(int n) {
        return "当前 " + n + " 条 · 一键脱敏复制上报";
    }

    /** 就地改某一行的副标题（行结构固定：图标 / 文字列 / 右侧箭头） */
    private void setRowSub(LinearLayout row, String text) {
        if (row == null || text == null) return;
        try {
            LinearLayout col = (LinearLayout) row.getChildAt(1);
            ((TextView) col.getChildAt(1)).setText(text);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 简洁模式的「更多选项」。
     *
     * <p>v4.0.3 第二轮：这些原本摊在首页最下面——5 行卡片，用户得滚过整张
     * 写入格式卡才看得到，等于「藏起来了」；而它们全是低频操作。
     * 收进一个弹窗后，首页只留一个和「一键链路测试」同样分量的入口。
     *
     * <p>顺序：打赏在最前（用户要求，与进阶设置页把打赏提到最上方一致），
     * 之后是反馈 / 联系作者 / 关于，最后是「进阶模式」——
     * 它是切换形态的动作，放最末符合导航直觉。
     */
    private void showMoreOptionsDialog() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);

        wrap.addView(toolRow(R.drawable.ic_heart, "打赏作者",
                "纯自愿，不影响任何功能",
                v -> startActivity(new Intent(this, DonateActivity.class))));
        wrap.addView(UiKit.divider(this, P));
        wrap.addView(buildMissedRow());
        wrap.addView(UiKit.divider(this, P));
        wrap.addView(toolRow(R.drawable.ic_phone, "开发者 QQ 群",
                "901543676 · 进群备注「取件码助手」", v -> copyQqGroup()));
        wrap.addView(UiKit.divider(this, P));
        wrap.addView(toolRow(R.drawable.ic_globe, "开发者酷安主页",
                "在酷安留言找作者", v -> openUrl(COOLAPK_URL)));
        wrap.addView(UiKit.divider(this, P));
        wrap.addView(toolRow(R.drawable.ic_info, "关于模块",
                appVersionLabel() + " · 作者 欧锴（O-kai）", v -> showAboutDialog()));
        wrap.addView(UiKit.divider(this, P));
        wrap.addView(toolRow(R.drawable.ic_grid, "进阶模式",
                "更多规则、来源与设置", v -> showModeSwitchDialog()));

        UiKit.dialog(this, P, R.drawable.ic_grid, "更多选项", null, wrap, 0);
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
                // v4.0.3：部署没成功就直接做一次深度自检 + 自动修复。
                // 原先「sqlite3 自检与修复」是「反馈与支持」里的一个独立入口，
                // 现在那个入口删掉了——这条能力并进部署流程更合理：
                // 用户点「一键部署」要的就是「让它能用」，中间步骤不该让他再点一次。
                if (res == null || !res.contains("部署成功")) {
                    String repair = Repair.repairSqlite3(this);
                    res = res + "\n\n—— 已自动做一次深度自检 ——\n" + repair;
                }
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
        String msg = "模块版本：" + appVersionLabel() + "\n"
                + "设计：欧锴（O-kai）\n"
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
        // v4.7：合作者改成编号列表，一行一个角色，格式统一，不再挤成一团
        UiKit.dialog(this, P, R.drawable.ic_info, "关于模块",
                "取件码助手 " + appVersionLabel() + "\n"
                        + "自动提取快递取件码，写入系统待办，并在 App 内留一份可查的记录\n\n"
                        + "【作者】\n"
                        + "欧锴（O-kai）— 项目发起与主要开发\n\n"
                        + "【合作者】\n"
                        + "1. VWV2005 — ColorOS 适配\n"
                        + "   最早实现 ColorOS 上的捕获与写入，奠定跨 ROM 支持基础\n\n"
                        + "2. Artifical0 — ColorOS Hook 修正\n"
                        + "   修正 ColorOS 上失效的 Hook 类名，使短信在 ColorOS 16 可正常捕获\n\n"
                        + "3. Seven2023Six — 界面布局重构\n"
                        + "   四 Tab 结构、UiKit 设计系统、矢量图标体系\n\n"
                        + "4. ByteJuno — ColorOS 适配与体检修正\n"
                        + "   通知通道参数校验、冻结后 Provider 唤醒放行、短信 Hook 类名；\n"
                        + "   4.0.4 起：ColorOS 唤醒体检改读 DEX 类定义（不再误报）、\n"
                        + "   设置开关的触摸与无障碍交互（含写入目标整行可点）\n\n"
                        + "LSPosed 模块仓库已上架，酷安同步更新\n"
                        + "遇到问题欢迎到 QQ 群或酷安主页找作者", null,
                UiKit.primary("知道了", null),
                UiKit.ghost("用户协议", this::showAgreementDialog),
                UiKit.ghost("更新日志", () -> openUrl(CHANGELOG_URL)));
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
            tip = "通知取件提取已关闭（约 30 秒生效）";
        } else if (wasBlocked) {
            tip = NOTI_SCOPE_TOAST;
        } else {
            tip = "通知取件提取已开启 ✓（约 30 秒生效）\n"
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

    /** 冻结直写：ColorOS / 荣耀环境显示（小米不需要此功能，默认关闭） */
    private void updateSdwDisplay() {
        String currentBackend = NotesBackend.detect(this);
        boolean isColorOs = NotesBackend.BACKEND_COLOROS_TODO.equals(currentBackend)
                || NotesBackend.BACKEND_COLOROS_NOTE.equals(currentBackend);
        boolean supported = isColorOs || NotesBackend.BACKEND_HONOR_TODO.equals(currentBackend);
        if (!supported) {
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

    /**
     * 取件码写入目标（v4.0.3 第二轮：待办 + 软件内部记录）。
     *
     * <p>模型只有两处落点，默认都开：
     * <ul>
     *   <li><b>系统待办</b>——小米笔记待办；ColorOS 是日历待办与便签（两个独立的库）</li>
     *   <li><b>软件内部记录</b>——取件码落进本地库，首页顶部出现「待取件」，
     *       桌面小组件读的也是它</li>
     * </ul>
     *
     * <p>桌面小组件不在这张清单里。它不是一个写入目标：它没有自己的库，
     * 只是内部记录的展示投影——内部记录关掉，它自然没有内容可显示。
     */
    private void showBackendPicker() {
        java.util.Set<String> sel = NotesBackend.selection(this);

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);

        for (String t : NotesBackend.availableTodoTargets()) {
            addTargetRow(wrap, todoLabel(t), todoSub(t), t, sel);
            wrap.addView(UiKit.divider(this, P));
        }

        boolean onDesk = WidgetPresence.has(this);
        addTargetRow(wrap, "软件内部记录",
                onDesk
                        ? "首页「待取件」和桌面小组件都读它"
                        : "首页显示「待取件」 · 可在桌面添加小组件",
                NotesBackend.TARGET_LOCAL, sel);

        UiKit.dialog(this, P, R.drawable.ic_target, "取件码写入目标",
                "取件码最多可以同时存到两处，默认都开。\n\n"
                        + "· 系统待办：写进笔记 / 日历，跟着" + NotesBackend.cloudAccountName()
                        + "云同步——同账号的平板、另一台手机也能收到。\n"
                        + "· 软件内部记录：只在这台手机上。App 顶部显示「待取件」，"
                        + "桌面小组件读的也是它。\n\n"
                        + "想跨设备看就选系统待办，只想本机留一份选内部记录——"
                        + "所以默认两处都开。\n"
                        + "两处至少保留一个；关掉内部记录会清空 App 内的记录并移除桌面小组件，"
                        + "系统待办里的记录不受影响。",
                wrap, 0, UiKit.primary("完成", null));
    }

    /** 一行「目标 + 开关」 */
    private void addTargetRow(LinearLayout parent, String title, String sub,
                              String target, java.util.Set<String> sel) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = UiKit.text(this, title, 14.5f, P.navInk);
        col.addView(t);
        TextView s2 = UiKit.text(this, sub, 11.5f, P.muteInk);
        s2.setLineSpacing(dp(1), 1f);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.setMargins(0, dp(3), 0, 0);
        col.addView(s2, slp);
        row.addView(col, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        UiKit.Switch sw = new UiKit.Switch(this, P, sel.contains(target));
        col.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        UiKit.bindSwitchRow(row, sw, title + "，" + sub);
        sw.setOnToggle(checked -> {
            java.util.Set<String> now = NotesBackend.selection(this);

            // ---------- 打开 ----------
            if (checked) {
                now.add(target);
                NotesBackend.saveSelection(this, now);
                if (NotesBackend.TARGET_LOCAL.equals(target)) {
                    // 组件被停用时就一起放回来；桌面上原来那个实例已经被系统摘掉，
                    // 需要用户重新添加——这正是「关掉就没意义，打开再加回来」的路径。
                    LocalRecord.restoreWidget(this);
                }
                afterTargetChanged();
                return;
            }

            // ---------- 关闭 ----------
            if (NotesBackend.TARGET_LOCAL.equals(target)) {
                if (!NotesBackend.canTurnLocalOff(this)) {
                    sw.setChecked(true, false);
                    UiKit.info(this, P, R.drawable.ic_target, "先勾一个系统待办",
                            "「软件内部记录」关掉之后，取件码就只剩系统待办这一个去处。\n\n"
                                    + "请先打开上面任意一个待办目标，再回来关它。");
                    return;
                }
                // 关它等于删数据，必须先确认；取消时把开关恢复原位
                sw.setChecked(false, false);
                sw.setEnabled(false); // wait for the asynchronous count and confirmation
                confirmTurnLocalOff(() -> {
                    sw.setChecked(true, false);
                    sw.setEnabled(true);
                });
                return;
            }

            now.remove(target);
            if (now.isEmpty()) {
                // 内部记录也关着，再关最后一个待办就什么都不剩了
                sw.setChecked(true, false);
                Toast.makeText(this, "至少要保留一个写入目标", Toast.LENGTH_SHORT).show();
                return;
            }
            NotesBackend.saveSelection(this, now);
            afterTargetChanged();
        });
        row.addView(sw, new LinearLayout.LayoutParams(dp(52), dp(31)));
        parent.addView(row);
    }

    /** 目标变了之后要一起刷新的一串界面与跨进程状态 */
    private void afterTargetChanged() {
        ProcessSync.push(this);
        refreshBackendValue();
        applyBackendDependentUi();
        headerSub.setText(headerSubtitle(currentTab));
        refreshHealth();

        // 首页最上面那块「待取件」是 build 时就定死的结构：内部记录关着时
        // 根本没创建这张卡。开关一变，结构就变了，只能整页重建；
        // 只切换「待办目标」时结构没变，不重建（否则会打断用户连续调整）。
        boolean localOn = LocalRecord.isOn(this);
        boolean cardBuilt = pickupCard != null;
        if (localOn != cardBuilt) {
            recreate();
        }
    }

    /**
     * 关闭「软件内部记录」前的二次确认。
     *
     * <p>用户的要求是「只要调整开关，记录就清除」——所以要真的清，但清之前
     * 必须把「会删多少条、删了就回不来」说清楚。条数走 Room 异步查询，
     * 主线程同步查会被 Room 拒绝并吞掉异常（那边永远是 0）。
     *
     * @param onCancel 用户点了「取消」时把开关拨回去
     */
    private void confirmTurnLocalOff(Runnable onCancel) {
        PickupRepository.get(this).countAllAsync(n -> {
            if (isFinishing() || isDestroyed()) return;
            String msg = (n > 0
                    ? "App 内的 " + n + " 条取件记录会被立即删除，不可恢复。\n\n"
                    : "App 内当前没有取件记录。\n\n")
                    + "桌面上的小组件也会一起移除。系统待办里的记录不受影响。\n\n"
                    + "重新打开后可以从头开始记录；想在桌面再看，到「设置 → 桌面小组件」"
                    + "重新添加即可。";
            android.app.Dialog confirmation = UiKit.dialog(this, P, R.drawable.ic_target, "关闭「软件内部记录」", msg, null,
                    UiKit.primary("关闭并清空", () -> {
                        // 先把开关落盘：清库是异步的，这中间万一来了新短信，
                        // 也不该再往「已经关掉」的库里写。
                        NotesBackend.setLocalSelected(this, false);
                        LocalRecord.clearAllAsync(this, cleared -> {
                            // 清完再让组件退场，否则它刷新的还是清库前的旧内容
                            LocalRecord.retireWidget(this);
                            ProcessSync.push(this);
                            android.util.Log.i("PICKUPDEBUG",
                                    "local record OFF: cleared=" + cleared);
                            Toast.makeText(this,
                                    cleared > 0 ? "已删除 " + cleared + " 条记录" : "已关闭软件内部记录",
                                    Toast.LENGTH_SHORT).show();
                            // 首页整块要消失，直接重建最省事（与模式切换同一套做法）
                            recreate();
                        });
                    }),
                    UiKit.ghost("取消", onCancel));
            // Back/outside cancellation is the same as pressing the explicit Cancel button.
            confirmation.setOnCancelListener(d -> onCancel.run());
        });
    }

    private String todoLabel(String backend) {
        if (NotesBackend.BACKEND_XIAOMI.equals(backend)) return "小米笔记 待办";
        if (NotesBackend.BACKEND_HONOR_TODO.equals(backend)) return "荣耀日历 待办";
        if (NotesBackend.BACKEND_COLOROS_NOTE.equals(backend)) return "ColorOS 便签 笔记";
        return "ColorOS 日历 待办";
    }

    private String todoSub(String backend) {
        if (NotesBackend.BACKEND_XIAOMI.equals(backend)) return "写进小米笔记的待办列表";
        if (NotesBackend.BACKEND_HONOR_TODO.equals(backend)) return "写进日历的「待办」（MagicOS 默认，无需 root）";
        if (NotesBackend.BACKEND_COLOROS_NOTE.equals(backend)) return "在便签里建一条置顶笔记";
        return "写进日历的「待办」（ColorOS 16 默认）";
    }

    /**
     * 写入目标变了之后，联动其它行的可见性（v4.0.3）。
     *
     * <p>目前只有一条：「仅内部记录」（不写任何系统待办）时隐藏「写入格式」——
     * 那个模板只决定**写进待办的文字长什么样**，不写待办了，留着一张
     * 点了没效果的卡片只会让人困惑。
     */
    private void applyBackendDependentUi() {
        boolean noTodo = NotesBackend.todoTargets(this).isEmpty();
        if (tplCardRef != null) {
            tplCardRef.setVisibility(noTodo ? View.GONE : View.VISIBLE);
        }
    }

    /**
     * v4.6：就地重建「取件码写入目标」右侧的值视图。
     *
     * 该行右侧不是单个 TextView，而是「自动/手动 · 目标名」的组合 View，
     * 无法靠 setText 更新，只能把它从父容器里换掉。
     */
    private void refreshBackendValue() {
        if (backendRowRef == null || backendValueHolder == null) return;
        View fresh = buildBackendValueView();
        // 组合 View 是 backendAcc 里的子项，用新的替换旧的
        backendValueHolder.removeAllViews();
        backendValueHolder.addView(fresh);
    }

    private static String backendAppName(String value) {
        if (NotesBackend.BACKEND_XIAOMI.equals(value)) return "小米笔记";
        if (NotesBackend.BACKEND_HONOR_TODO.equals(value)) return "荣耀日历";
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

    /**
     * 写入目标的摘要文案（v4.0.3 第二轮）。
     *
     * <p>两处落点的组合只有三种形态：
     * 「小米笔记待办 · 内部记录」「日历待办 + 便签 · 内部记录」「仅内部记录」。
     * 不写待办时用「仅」开头，是为了让「我只想让 App 自己记着」一眼可辨。
     */
    private String backendLabel() {
        java.util.List<String> targets = NotesBackend.todoTargets(this);
        boolean local = NotesBackend.localSelected(this);
        StringBuilder sb = new StringBuilder();
        if (targets.isEmpty()) {
            return local ? "仅内部记录" : "未选择";
        }
        for (int i = 0; i < targets.size(); i++) {
            if (i > 0) sb.append(" + ");
            sb.append(shortName(targets.get(i)));
        }
        if (local) sb.append(" · 内部记录");
        return sb.toString();
    }

    private String shortName(String backend) {
        if (NotesBackend.BACKEND_XIAOMI.equals(backend)) return "小米笔记待办";
        if (NotesBackend.BACKEND_HONOR_TODO.equals(backend)) return "日历待办";
        if (NotesBackend.BACKEND_COLOROS_NOTE.equals(backend)) return "便签";
        return "日历待办";
    }

    /**
     * 「取件码写入目标」右侧的值。
     *
     * <p>v4.0.3 第二轮去掉了「自动 / 手动」前缀：新模型下这两处是并列的落点，
     * 「自动识别一个后端」这个概念已经不存在了。再挂一个「自动」，
     * 用户看到「自动 · 小米笔记待办 · 内部记录」只会更困惑。
     */
    private View buildBackendValueView() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setGravity(Gravity.CENTER_VERTICAL);

        TextView target = UiKit.text(this, backendLabel(), 13f, UiKit.INK2);
        target.setSingleLine(true);
        wrap.addView(target);
        return wrap;
    }

    /** v4.5：首页官方规则集右侧的徽标（点击可手动拉取最新规则） */
    private TextView ruleBadgeView;
    /** v4.5：手动拉取规则的节流时间戳（毫秒） */
    private long lastRuleCheckMs = 0L;
    /** v4.5：手动拉取规则的最小间隔（8 秒，防止连点） */
    private static final long RULE_CHECK_INTERVAL_MS = 8_000L;

    /**
     * v4.5：点「官方规则集」手动拉取最新规则。
     *
     * 设计要点：
     *  1. 节流：8 秒内只允许触发一次，连点无效（用户要求 5–10 秒）
     *  2. 复用现成的 UpdateCenter 检查逻辑，不另写一套网络请求
     *  3. 过程中把徽标改成「检查中…」，给用户明确反馈
     *  4. 结束后恢复徽标并 Toast 结果；规则真的更新了才提示重启相关通道
     */
    private void checkRulesManually() {
        long now = System.currentTimeMillis();
        long delta = now - lastRuleCheckMs;
        if (delta < RULE_CHECK_INTERVAL_MS) {
            long wait = (RULE_CHECK_INTERVAL_MS - delta) / 1000 + 1;
            Toast.makeText(this, "刚检查过，请 " + wait + " 秒后再试", Toast.LENGTH_SHORT).show();
            return;
        }
        lastRuleCheckMs = now;

        final int before = PickupExtractor.getActiveRulesVersion();
        if (ruleBadgeView != null) ruleBadgeView.setText("检查中…");

        final Context appCtx = getApplicationContext();
        new Thread(() -> {
            final int rr;
            try {
                rr = UpdateCenter.fetchRules(appCtx);
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    if (ruleBadgeView != null) ruleBadgeView.setText("已是最新");
                    Toast.makeText(this, "检查失败：" + t.getMessage(), Toast.LENGTH_SHORT).show();
                });
                return;
            }
            final int after = PickupExtractor.getActiveRulesVersion();
            runOnUiThread(() -> {
                String label;
                String msg;
                if (rr == 1) {
                    label = "已更新";
                    msg = "规则集已更新到 v" + after + " ✓";
                } else if (rr == 2) {
                    label = "已是最新";
                    msg = "规则集已是最新（v" + after + "）";
                } else {
                    label = "已是最新";
                    msg = "网络不可用，请检查网络后重试";
                }
                if (ruleBadgeView != null) ruleBadgeView.setText(label);
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                if (rr == 1 && after != before) rebuildUi();
            });
        }).start();
    }

    // ================================================================
    //  通知取件开关的真实可用状态（v3.1.1 逻辑原样保留）
    // ================================================================

    /**
     * 通知开关的当前状态。
     *
     * <p>v4.0.2 第八轮（来自 PR #14）：<b>不再直接读标志文件</b>。
     * App 沙箱根本进不去 /data/local/tmp（属主 shell:shell，权限 0771），
     * 旧实现每次 `f.exists()` 都是 false —— 于是<b>开关永远显示为「已关闭」</b>，
     * 哪怕通知通道其实是开着的。这正是 PR 里报的那个现象。
     *
     * <p>现在以本地偏好为准（开关每次改动都会同步写偏好，两者本来就是一起写的），
     * 再由 {@link #verifyNotiFlagIfRootReady()} 在有 root 会话时静默校对一次。
     */
    private boolean isNotiHookFlagOn() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("noti_hook", false);
    }

    /**
     * 有常驻 root 通道时，顺手把标志文件的真实值读回来校对（不额外唤 su）。
     *
     * <p>为什么不在页面渲染时直接跑 su：那会让用户每次打开设置都看到一次
     * Magisk 的「已授予超级用户权限」提示——正是上一轮刚清掉的东西。
     * 这里只在「本进程已经有 root 通道」时读，属于搭便车。
     */
    private void verifyNotiFlagIfRootReady() {
        if (!RootShell.isReady()) return;
        new Thread(() -> {
            String out;
            try {
                out = RootShell.exec("cat " + Repair.TARGET_DIR + "/enable_noti_hook 2>/dev/null",
                        5000);
            } catch (Throwable t) {
                return;
            }
            if (out == null) return;
            String v = out.trim();
            if (!"0".equals(v) && !"1".equals(v)) return;
            final boolean actual = "1".equals(v);
            final boolean cached = isNotiHookFlagOn();
            if (actual == cached) return;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean("noti_hook", actual).apply();
            runOnUiThread(() -> {
                notiSwitchOn = actual;
                if (notiSw != null) notiSw.setChecked(actual);
                renderNotiSwitchUi();
            });
            android.util.Log.i("PICKUPDEBUG", "noti flag corrected from file: " + actual);
        }, "pickup-noti-flag-verify").start();
    }

    private void toggleNotiHookFlag(boolean on) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("noti_hook", on).apply();
        new Thread(() -> {
            String flag = Repair.TARGET_DIR + "/enable_noti_hook";
            try {
                // 写完立刻回读：写入失败（没 root / 被拒绝）时不留「界面显示开着、
                // 实际没生效」的假象。回读结果同时作为界面的真实状态。
                String[] r = Diagnostics.suExecPublic("mkdir -p " + Repair.TARGET_DIR + "; "
                        + "echo " + (on ? "1" : "0") + " > " + flag + "; chmod 644 " + flag
                        + ProcessSync.readableConfigCommand(flag)
                        + "; cat " + flag + " 2>/dev/null", 15);
                String v = r != null && r.length > 0 && r[0] != null ? r[0].trim() : "";
                boolean actual = v.contains("1") && !v.contains("0");
                if (!v.isEmpty() && actual != on) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putBoolean("noti_hook", actual).apply();
                    runOnUiThread(() -> {
                        notiSwitchOn = actual;
                        if (notiSw != null) notiSw.setChecked(actual);
                        renderNotiSwitchUi();
                    });
                }
            } catch (Throwable ignored) { }
        }).start();
    }

    /**
     * 后台核对 LSPosed 作用域里有没有勾「Android 系统（android）」，有结论再回来改按钮。
     *
     * 复用现成能力，不自己重写查库逻辑：Diagnostics.checkScope 内部就是调
     * Repair.lsposedStatus(ctx)（root 跑 sqlite3 读 /data/adb/lspd/config/modules_config.db），
     * 但它是 private 且需要先知道 root 是否可用；这里直接用底层的公开方法
     * Repair.lsposedStatus，拿它已经算好的 missingScope 即可。
     *
     * 取舍（简化版）：**只在开关为「已开启」时才查**——
     *  ① 作用域检查要 root + sqlite3，约 2-5 秒，不能同步跑在按钮初始化里（界面必须秒开）；
     *  ② 开关默认关闭，对绝大多数用户这次查库毫无意义，白白多一次 su；
     *  ③ 「缺 android」这个结论只在开关打开时才有意义，关着时按钮本来就显示「已关闭」。
     * 另外：配置库读不到（没 root / 没装 LSPosed）或模块未启用时不下结论，
     * 保持标志文件的原始显示——这两种情况由上方「🔍 排查问题」向导负责。
     *
     * @param force true=忽略节流强制复查（开关刚被切换时用）
     */
    /**
     * 通知作用域检查。
     *
     * <p>v4.0.1：这一步要读 LSPosed 配置库，走的是 su ——于是每次回到前台
     * 都会触发一次 Magisk 的「已授予超级用户权限」提示。
     * 用户反馈这个提示「没有价值、没有意义」。
     *
     * <p>改法：默认<b>不自动检查</b>。它要回答的是「通知提取能不能用」，
     * 而这个问题的答案在用户打开「通知提取」页时才有意义——
     * 所以改成：进入那一页时才查一次（并缓存结果），
     * 以及用户主动打开通知开关时才查。
     */
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
        refreshHealth(false);
    }

    /**
     * 体检分两级（v4.0.1）。
     *
     * <p><b>轻量体检</b>（默认，自动跑）：只查不需要 root 的两项
     * （LSPosed 注入 + 通知权限），<b>完全不调 su</b>，所以不会触发
     * Magisk 的「已授予超级用户权限」提示框。
     *
     * <p><b>完整体检</b>（需用户主动触发）：包含 root 授权、作用域、
     * sqlite3 部署、待办库访问——这些都要 su，会弹授权框。
     * 由用户长按状态卡或点清单里的「重新体检」触发。
     *
     * <p>为什么这么分：用户反馈「每次打开软件都弹超级用户授权提示，
     * 这信息没有价值、没有意义，因为我本身都已经一切正常了」。
     * 确实如此——环境正常时这个框提供不了任何信息，只是噪音。
     * 所以默认不再自动跑；真出问题时，用户主动点一下就能看到完整诊断。
     */
    private void refreshHealth(boolean force) {
        // v4.0.2 第三轮：手动点的体检，跑完后清单保持展开（结果留在眼前）
        healthManual = force;
        if (!force && lastHealthChecks != null
                && System.currentTimeMillis() - lastHealthAt < HEALTH_CACHE_TTL) {
            renderHealth(lastHealthChecks, lastHealthOk, lastHealthDeploy, lastHealthErr);
            return;
        }
        heroTitle.setText("正在检查…");
        heroSub.setText(force ? "完整体检需要 root 授权，约需 2-5 秒" : "快速检查，不触发 root 授权");
        heroIcon.setImageResource(R.drawable.ic_check);
        heroCard.setBackground(UiKit.bg(P.hero, this, 18));
        heroIcon.setColorFilter(P.heroInk, android.graphics.PorterDuff.Mode.SRC_IN);
        heroTitle.setTextColor(P.heroInk);
        heroSub.setTextColor(P.heroSubInk);
        // 手动体检时不要收起清单（用户正在看这一块）
        if (!force) checklistCard.setVisibility(View.GONE);
        deployBtn.setVisibility(View.GONE);
        troubleshootBtn.setVisibility(View.GONE);

        final boolean full = force;
        new Thread(() -> {
            List<Diagnostics.Check> checks;
            boolean needDeploy = false;
            boolean allOk = true;
            String err = null;
            try {
                // force=false → 轻量体检（不碰 su）；force=true → 完整体检
                checks = full ? Diagnostics.healthCheck(this)
                        : Diagnostics.lightHealthCheck(this);
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
            // 完整体检刚用过 root（常驻通道已建立）：
            // 顺手把跨进程配置快照同步出去、并把旧版本留在 /data/local/tmp 的
            // sqlite3 + 依赖库清掉 —— 这两件事都不再额外唤 su，不会再弹授权框。
            if (full) {
                try { ProcessSync.flushIfReady(this); } catch (Throwable ignored) { }
                try { Repair.autoCleanupLegacyOnce(this); } catch (Throwable ignored) { }
            }
            final List<Diagnostics.Check> cs = checks;
            final boolean show = needDeploy, ok = allOk;
            final String errMsg = err;
            lastHealthChecks = cs;
            lastHealthOk = ok;
            lastHealthDeploy = show;
            lastHealthErr = errMsg;
            lastHealthAt = System.currentTimeMillis();
            lastHealthFull = full;
            runOnUiThread(() -> renderHealth(cs, ok, show, errMsg, full));
        }).start();
    }

    private void renderHealth(List<Diagnostics.Check> checks, boolean allOk, boolean needDeploy,
                              String err) {
        renderHealth(checks, allOk, needDeploy, err, lastHealthFull);
    }

    /**
     * @param full true = 完整体检结果；false = 轻量体检结果（2 项）。
     */
    private void renderHealth(List<Diagnostics.Check> checks, boolean allOk, boolean needDeploy,
                              String err, boolean full) {
        healthAllOk = allOk;
        int failed = 0;
        if (checks != null) for (Diagnostics.Check c : checks) if (!c.ok) failed++;
        int total = checks == null ? 0 : checks.size();

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
            if (full) {
                // 项数不再写死：体检项增加时（这轮加了「配置快照」）文案会自己跟上，
                // 否则会出现「清单里 7 项、副标题说 6 项」这种自相矛盾。
                int n = checks == null ? 0 : checks.size();
                heroSub.setText(n > 0
                        ? "LSPosed 已注入 · " + n + " 项体检全部通过"
                        : "LSPosed 已注入 · 体检全部通过");
            } else {
                // v4.0.2：轻量体检只覆盖 2 项，措辞必须准确——
                // 说「全部通过」会让用户以为跑过完整检测，那是骗人。
                //
                // v4.0.2 第三轮：点击语义变了（现在是展开清单，不是跑体检），
                // 文案跟着改，否则会误导用户以为点一下就会弹 root 授权。
                heroSub.setText("LSPosed 已注入 · 点此查看校验清单");
                heroSub.setMaxLines(1);
                heroSub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            }
            heroIcon.setImageResource(R.drawable.ic_check);
            heroCard.setBackground(UiKit.bg(P.hero, this, 18));
            heroIcon.setColorFilter(P.heroInk, android.graphics.PorterDuff.Mode.SRC_IN);
            heroTitle.setTextColor(P.heroInk);
            heroSub.setTextColor(P.heroSubInk);
        } else {
            heroTitle.setText(failed + " 项待处理");
            heroSub.setText("体检 " + (total - failed) + "/" + total + " 通过 · 点此查看清单");
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
                        UiKit.inkOn(c.ok ? UiKit.OK : UiKit.DANGER));
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
        // 全绿时默认收起清单（点状态卡可展开）；异常时强制展开，不让红字被藏起来。
        // v4.0.2 第三轮：手动点「点击体检」跑完体检后，清单要保持展开——
        // 用户刚点的按钮，结果应该留在眼前，而不是又缩回去。
        checklistCard.setVisibility(allOk && !healthManual ? View.GONE : View.VISIBLE);
        deployBtn.setVisibility(needDeploy ? View.VISIBLE : View.GONE);
        // 「进入排查向导」始终保留：状态正常时用户也可能想主动做一次深度排查。
        troubleshootBtn.setVisibility(View.VISIBLE);
        if (healthBtn != null) {
            healthBtn.setEnabled(true);
            healthBtn.setText("点击体检（完整校验，含 root）");
        }
        if (guideBtn != null) guideBtn.setVisibility(allOk ? View.GONE : View.VISIBLE);
    }

    /**
     * 点状态卡：展开 / 收起校验清单。
     *
     * <p>v4.0.2 第三轮恢复的行为——原先点一下就跑完整体检，
     * 用户反馈「正常情况下一切都正常，不用整天体检」，
     * 所以改成只展开清单，完整体检由清单里的「点击体检」手动触发。
     */
    private void toggleChecklist() {
        if (checklistCard == null) return;
        boolean show = checklistCard.getVisibility() != View.VISIBLE;
        checklistCard.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /**
     * v4.3：「开始配置」引导——新用户按顺序走完六步。
     *
     * 和「体检清单」的区别：清单是「哪里出问题了」，本向导是「接下来该做什么」。
     * 两者并存：清单给已用起来的用户看诊断结果，向导给第一次装的用户照着做。
     */
    private void showSetupGuide() {
        final java.util.List<Diagnostics.Check> checks;
        try {
            checks = Diagnostics.healthCheck(this);
        } catch (Throwable t) {
            Toast.makeText(this, "读取体检结果失败：" + t, Toast.LENGTH_LONG).show();
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("按下面顺序做，每步做完回到这里看是否变成「已通过」。\n")
          .append("全部通过后，收到取件短信就会按你的写入目标自动保存。\n\n");

        String[] howTo = {
                "在 LSPosed 里启用本模块，并勾选作用域「Android 系统」",
                "给 Magisk / KernelSU 授权 root（本模块需要读写待办库）",
                "LSPosed 作用域里勾选：电话、短信、com.android.providers.telephony",
                "点首页「一键部署 sqlite3」（若已存在则跳过这步）",
                "确认「取件码写入目标」是你想写入的 App",
                "点首页「一键链路测试」，验证「识别 → 写入」整条链路",
        };

        int passed = 0;
        for (int i = 0; i < checks.size(); i++) {
            Diagnostics.Check c = checks.get(i);
            if (c.ok) passed++;
            sb.append(c.ok ? "✓ " : "○ ")
              .append("第 ").append(i + 1).append(" 步：").append(c.title)
              .append(c.ok ? "（已通过）" : "")
              .append("\n");
            if (!c.ok && i < howTo.length) {
                sb.append("    → ").append(howTo[i]).append("\n");
            }
        }
        sb.append("\n当前进度：").append(passed).append(" / ").append(checks.size()).append(" 通过");

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        TextView tv = UiKit.text(this, sb.toString(), 13f, UiKit.INK2);
        tv.setLineSpacing(dp(4), 1.15f);
        tv.setPadding(dp(4), dp(4), dp(4), dp(4));
        sv.addView(tv);

        UiKit.dialog(this, P, R.drawable.ic_rocket, "开始配置", null, sv, 72,
                UiKit.primary("去排查向导", () -> runTroubleshoot()),
                UiKit.ghost("关闭", null)).show();
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
            if (total == 0) return "官方规则没覆盖到的短信？可为自家驿站单独写一条";
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
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!uiReady) return;
        if (pickupListener == null) {
            pickupListener = this::refreshPickupCard;
        }
        PickupRepository.addChangeListener(pickupListener);
    }

    @Override
    protected void onStop() {
        if (uiReady) {
            PickupRepository.removeChangeListener(pickupListener);
        }
        super.onStop();
    }

    /** 4.0：导出/导入走系统文件选择器，结果交给 RecordManager 处理 */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        recordManager.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 首启模式选择 / 简洁模式跳转时没有建界面，此时不能碰任何视图
        if (!uiReady) return;
        // v4.0.2：启动时自动清理过期记录。
        // 用户原话：「如果设置的过期时间是 90 天，那么过了日期应该立马就被清理掉，
        // 不需要我再点击管理并选择『立刻清理已过期的记录』」。
        // 所以保留期必须真的自动生效——用户不点按钮，记录也会按期消失。
        PickupRepository.get(this).autoPurgeAsync(this);
        refreshPickupCard();
        refreshHealth();
        notiSwitchOn = isNotiHookFlagOn();
        verifyNotiFlagIfRootReady();
        if (notiSw != null) notiSw.setChecked(notiSwitchOn, false);
        renderNotiSwitchUi();
        // v4.0.1：不再在这里自动查 LSPosed 作用域——那一步要走 su，
        // 会触发 Magisk 的「已授予超级用户权限」提示，用户明确表示这提示没有意义。
        // 改为进入「通知提取」页时才查一次（那时这个信息才有价值）。
        if (currentTab == 1) {
            checkNotiHookScopeAsync(false);
        }
        UpdateCenter.maybeCheck(this);
        refreshUserRuleCard();
    }

    /** v2.8.0：疑似漏抓错题本（菜单入口 → 现在挂在「规则 → 工具」） */
    /**
     * 疑似漏抓错题本详情。
     *
     * @param onCleared 清空成功后的回调。外层那一行显示的「当前 N 条」是
     *                  构建时算好的静态文字，清空后必须靠这个回调就地更新——
     *                  外层页面不能 recreate（会把本对话框一起销毁，
     *                  用户点完「确认清空」会莫名其妙回到上一页）。
     */
    private void showMissedSmsDialog(Runnable onCleared) {
        List<MissedSmsStore.Sample> list = MissedSmsStore.getAll(this);
        boolean enabled = MissedSmsStore.isEnabled(this);

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);

        // v4.0 收口：错题本默认关闭（P0-10「错题本默认关闭」）。
        // 这里给出显式开关，用户想用再打开。
        UiKit.Switch sw = new UiKit.Switch(this, P, enabled);
        sw.setOnToggle(on -> {
            MissedSmsStore.setEnabled(this, on);
            Toast.makeText(this, on
                    ? "已开启：疑似漏抓的短信会被记在本机（30 天后自动过期）"
                    : "已关闭：不再记录任何短信正文片段",
                    Toast.LENGTH_LONG).show();
        });
        LinearLayout swRow = UiKit.row(this, P, R.drawable.ic_notebook, "记录疑似漏抓短信",
                "开启后会把未识别成功的短信片段存到本机；30 天后自动过期", sw);
        wrap.addView(swRow);
        wrap.addView(UiKit.spacer(this, 12));

        if (list.isEmpty()) {
            TextView empty = UiKit.text(this,
                    enabled
                            ? "当前错题本为空。\n系统收到含快递特征词但没能提取出取件码的短信时，会记在这里供一键脱敏上报。"
                            : "错题本未启用，且当前为空。\n打开上面的开关后，系统收到「有快递特征词但没识别出取件码」的短信会记在这里。",
                    12.5f, UiKit.INK3);
            empty.setLineSpacing(dp(3), 1.15f);
            wrap.addView(empty);
            UiKit.dialog(this, P, R.drawable.ic_notebook, "疑似漏抓错题本", null, wrap, 0,
                    UiKit.primary("知道了", null),
                    UiKit.ghost("手动上报", this::openGithubIssueManual));
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("本地共捕获 ").append(list.size()).append(" 条疑似漏抓短信：\n\n");
        for (int i = 0; i < list.size(); i++) {
            sb.append(i + 1).append(". ").append(MissedSmsStore.sanitize(list.get(i).body)).append("\n\n");
        }
        TextView body = UiKit.text(this, sb.toString().trim(), 12.5f, UiKit.INK2);
        body.setLineSpacing(dp(3), 1.15f);
        wrap.addView(body);

        // 先声明再赋值：清空后要能关掉这一层，而 lambda 里需要引用它自己。
        final android.app.Dialog[] outer = new android.app.Dialog[1];
        outer[0] = UiKit.dialog(this, P, R.drawable.ic_notebook, "疑似漏抓错题本（" + list.size() + " 条）",
                null, wrap, 0,
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
                            // 顺序不能反：先刷新外层那行的条数，再关掉详情页。
                            // 反过来的话用户先看到「当前 0 条」的旧数字，才看到它变，
                            // 正是这个 bug 最初的样子。
                            if (onCleared != null) onCleared.run();
                            if (outer[0] != null) outer[0].dismiss();
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
                    + "- 模块版本：" + appVersionLabel() + "\n"
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

    /**
     * 反馈渠道选择（排查向导失败时的出口）。
     *
     * <p>v4.0.3 第二轮：去掉了 GitHub Issues——设置页那个独立入口已经删掉，
     * 这里再留一个入口就等于「删了又没删」。现在只留 QQ 群和酷安两个。
     */
    private void showFeedbackChannels() {
        UiKit.chooser(this, P, R.drawable.ic_chat, "联系作者",
                new CharSequence[]{
                        "QQ 群：901543676（复制群号）",
                        "酷安（开发者主页）"
                }, -1, which -> {
                    if (which == 0) copyQqGroup();
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
                                + "反馈渠道：QQ 群 901543676（备注「取件码助手」）/ 酷安开发者主页",
                        Toast.LENGTH_LONG).show());
            } catch (Throwable t) {
                runOnUiThread(() -> Toast.makeText(this,
                        "报告生成失败：" + t.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    /**
     * 滚动区底部留白：底栏是悬浮的，底下必须留出足够空间，
     * 否则最后一张卡会被底栏压住（1.0x 时 148dp 就够）。
     *
     * <p>系统字体放大后（老人常用 1.3x / 1.5x），底栏高度与文字行数都会变长，
     * 这里按 fontScale 额外补一点，避免内容被遮。
     */
    private int bottomSafePadding(int baseDp) {
        float fs = getResources().getConfiguration().fontScale;
        int base = dp(baseDp);
        // 简洁模式没有底栏（baseDp 很小），不需要额外留白
        if (fs <= 1.05f || baseDp < 60) return base;
        int extra = (int) (base * Math.min(0.45f, (fs - 1f) * 0.8f));
        return base + extra;
    }

    /** 取件提醒：点开设置面板（时间 + 立即测试一次） */
    private void pickReminderTime() {
        String msg = (Reminder.isEnabled(this) ? "已开启：每天 " + Reminder.timeLabel(this)
                : "已关闭") + "\n到点后会检查本地取件库，"
                + "还有没取的件才提醒，没有就保持安静。";
        new android.app.AlertDialog.Builder(this)
                .setTitle("取件提醒")
                .setMessage(msg)
                .setPositiveButton("选择时间", (d, w) -> showReminderTimePicker())
                .setNeutralButton("立即测试一次", (d, w) -> testReminderNow())
                .setNegativeButton("取消", null)
                .show();
    }

    /** 时间选择器 */
    private void showReminderTimePicker() {
        android.app.TimePickerDialog dlg = new android.app.TimePickerDialog(this,
                (view, hourOfDay, minute) -> {
                    Reminder.setTime(this, hourOfDay, minute);
                    refreshReminderRow();
                    Toast.makeText(this, "提醒时间已设为每天 " + Reminder.timeLabel(this),
                            Toast.LENGTH_SHORT).show();
                }, Reminder.hour(this), Reminder.minute(this), android.text.format.DateFormat.is24HourFormat(this));
        dlg.show();
    }

    /** 立即触发一次提醒：用来确认通知渠道与权限都正常 */
    private void testReminderNow() {
        // Room 不允许主线程查询，必须放后台
        new Thread(() -> {
            final int pending = Reminder.fireNow(this);
            runOnUiThread(() -> Toast.makeText(this, pending > 0
                    ? "已发送提醒通知（当前 " + pending + " 件待取）\n如果没有看到通知，请检查通知权限"
                    : "当前没有待取件，提醒按设计保持安静\n（可以先取一条快递再试）",
                    Toast.LENGTH_LONG).show());
        }).start();
    }

    /** 刷新取件提醒行的副标题与开关 */
    private void refreshReminderRow() {
        if (reminderSw != null) reminderSw.setChecked(Reminder.isEnabled(this));
        if (reminderRow == null) return;
        try {
            LinearLayout col = (LinearLayout) reminderRow.getChildAt(1);
            if (col != null && col.getChildCount() >= 2) {
                TextView sub = (TextView) col.getChildAt(1);
                if (sub != null) {
                    sub.setText(Reminder.isEnabled(this)
                            ? "每天 " + Reminder.timeLabel(this) + " 提醒还有多少件没取"
                            : "已关闭 · 点此设置每天的提醒时间");
                }
            }
        } catch (Throwable ignored) { }
    }

    /**
     * 「清理与卸载」主弹窗（v4.0.3）。
     *
     * <p>把两件相关但不同的事放在同一个入口下：
     * <ul>
     *   <li><b>清理旧版本残留</b>——升级上来的用户，旧目录里还留着 1.5MB 的二进制；</li>
     *   <li><b>卸载前说明</b>——卸载后到底会留下什么，要不要在卸载前手动清一次。</li>
     * </ul>
     *
     * <p>为什么不拆成两行：这两件事都属于「低频、点一次就完」的操作，
     * 拆开会让设置页多一个看起来很有内容的入口，反而诱导用户去点。
     * 现在只占一行，点开才展开说明。
     */
    private void showCleanupDialog() {
        UiKit.dialog(this, P, R.drawable.ic_refresh, "清理与卸载",
                "旧版本把 sqlite3 与依赖库放在系统临时目录"
                        + "（/data/local/tmp/pickup_sqlite），卸载 App 时系统不会清理，"
                        + "会一直占着约 1.5MB。\n\n"
                        + "新版本已经改到应用私有目录，卸载时会随应用数据一起删除。"
                        + "\n\n下面两个操作都只影响这个旧目录，不动你的取件记录。",
                null, 0,
                UiKit.primary("清理旧版本残留", this::runCleanupLegacy),
                UiKit.ghost("卸载前说明", this::showUninstallInfo));
    }

    /** 执行旧版本残留清理（只删二进制，保留配置——还在用的用户需要它们） */
    private void runCleanupLegacy() {
        Toast.makeText(this, "正在清理…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final String msg = Repair.cleanupLegacy(this);
            runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
        }).start();
    }

    /**
     * 卸载后残留说明（v4.0.3）。
     *
     * <p>要回答三个问题：系统会自动删什么、还会留下什么、为什么不能一起删。
     * 用户明确要求「所有的情况都表达清楚」——所以把三个文件连路径一起列出来，
     * 而不是只说「会留几 KB」。
     */
    private void showUninstallInfo() {
        UiKit.dialog(this, P, R.drawable.ic_refresh, "卸载会留下什么",
                "卸载 App 时，系统会自动删除：\n"
                        + "· 应用私有目录里的全部内容——sqlite3、依赖库、"
                        + "本地取件记录、所有设置\n"
                        + "· 桌面上的小组件\n\n"
                        + "卸载后仍会留下（约 5KB，3 个文本文件，都在 "
                        + RuntimePaths.SYNC_DIR + "）：\n"
                        + "· app_state.json　　规则与通知白名单\n"
                        + "· enable_noti_hook　通知通道开关\n"
                        + "· user_rules.json　　用户规则\n\n"
                        + "为什么不能一起删：抓取时是系统进程去读这些配置，"
                        + "而它进不去应用私有目录，只能放在这个公共位置。\n\n"
                        + "想彻底不留，就在卸载之前点下面的按钮。"
                        + "清掉之后通知抓取会先失效——做一次体检、或标记一次已取，"
                        + "配置就会自动重建。",
                null, 0,
                UiKit.primary("彻底清理（含配置）", () -> {
                    Toast.makeText(this, "正在清理…", Toast.LENGTH_SHORT).show();
                    new Thread(() -> {
                        final String msg = Repair.purgeSyncDir(this);
                        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
                    }).start();
                }),
                UiKit.ghost("关闭", null));
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
                        : NotesBackend.BACKEND_HONOR_TODO.equals(NotesBackend.detect(this))
                        ? "（荣耀侧短信 App 是 com.hihonor.mms；写入目标 App 无需勾选）"
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
                // v4.0.1：预检用【轻量体检】——不调 su，因此不会触发
                // Magisk 的「已授予超级用户权限」提示。
                // 需要 root 的检查留到链路测试第 3 步（真正写入时）再做，
                // 那时候用户本来就知道这一步要写系统待办库。
                for (Diagnostics.Check c : Diagnostics.lightHealthCheck(this)) {
                    if (!c.ok) {
                        warning.add("· " + c.title + "：" + c.detail);
                    }
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
            b.setTextColor(P.onAccent);
            b.setBackground(UiKit.bg(P.accent, this, 13));
        } else if ("warn".equals(kind)) {
            b.setTextColor(UiKit.inkOn(UiKit.WARN));
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

    /**
     * 版本显示：V + 点分版本号，例如 V4.0.0。
     *
     * <p>v4.0.1 起统一走 {@link AppVersion}，全项目只有一处取版本号的地方。
     * 此前这里显示的是「V400」（versionCode），而用户心里想的是「4.0.0」——
     * 两套数字并行的代价是：界面上的版本号和更新说明、热更兼容性都对不上。
     * versionCode 仍然保留，但只用于 Android 升级判定，不再参与展示。
     */
    private String appVersionLabel() {
        return AppVersion.label(this);
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
