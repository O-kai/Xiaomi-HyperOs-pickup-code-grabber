package io.github.okaidev.pickupcode;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;
import android.widget.RemoteViews;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 桌面小组件自检。
 *
 * <p>为什么要有这个功能：真机上遇到过「添加小组件时提示『载入窗口小部件时出现问题』」，
 * 而系统日志里<b>没有任何</b>报错——失败发生在桌面进程 inflate RemoteViews 的阶段，
 * 桌面侧不会把异常回传给 App，光看 logcat 完全无从下手。
 *
 * <p>这个页面在 App 进程内做两件事：
 * <ol>
 *   <li>{@code RemoteViews.apply()} —— 会真实 inflate 布局并执行所有 action，
 *       任何不被系统接受的类或 action 都会在这里抛异常；</li>
 *   <li>把结论翻译成人话，并给出可行的修复动作。</li>
 * </ol>
 *
 * <p>实战价值（2026-10-31）：靠它一次就定位到了真凶——
 * Android 的 RemoteViews 只允许 inflate 一份白名单里的具体 View 类，
 * 裸的 {@code android.view.View}（当时用作弹性占位）不在白名单里，
 * 桌面 inflate 直接失败。自检页当场打印出
 * {@code Class not allowed to be inflated android.view.View}。
 */
public class WidgetDiagActivity extends Activity {

    private static final String TAG = "PICKUPDEBUG";

    private LinearLayout resultBox;
    /** 三项检查的结论（复制诊断信息时要用） */
    private String rLayout = "", rAction = "", rBanned = "";
    private boolean diagOk = true;
    /** ScrollView 里的内容盒（真实渲染预览要挂在这里） */
    private LinearLayout contentBox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        UiKit.applyTheme(this);
        super.onCreate(savedInstanceState);
        UiKit.Palette P = UiKit.palette(this);
        getWindow().setStatusBarColor(P.bg);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(P.bg);
        root.addView(buildHeader(P));

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setPadding(dp(18), dp(4), dp(18), dp(24));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        contentBox = box;
        sv.addView(box, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        // v4.2.1：这一页只干一件事 —— 出问题时给出能直接发给作者的证据。
        // 说明压到一句：跑一遍 → 复制 → 发群。
        TextView intro = UiKit.text(this,
                "桌面上组件显示错乱、点了没反应时，先在这里跑一遍，"
                        + "再点「复制诊断信息」，把结果发到 QQ 群 901543676。",
                12.5f, P.muteInk);
        intro.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.setMargins(dp(4), dp(4), dp(4), dp(10));
        box.addView(intro, ilp);

        resultBox = UiKit.card(this, P);
        resultBox.setPadding(dp(16), dp(14), dp(16), dp(16));
        box.addView(resultBox, UiKit.cardLp(this, 0f));

        runChecks(P);
    }

    private View buildHeader(UiKit.Palette P) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14), dp(14), dp(20), dp(10));

        TextView back = UiKit.text(this, "‹", 30f, P.navInk);
        back.setGravity(Gravity.CENTER);
        back.setPadding(dp(10), 0, dp(10), dp(4));
        back.setOnClickListener(v -> finish());
        header.addView(back);

        TextView title = UiKit.text(this, "小组件自检", 21f, P.navInk);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return header;
    }

    private void runChecks(UiKit.Palette P) {
        // ---- 检查 1：布局能否 inflate ----
        Throwable layoutErr = null;
        try {
            RemoteViews bare = new RemoteViews(getPackageName(), R.layout.widget_pickup_v4);
            View v = bare.apply(this, new FrameLayout(this));
            layoutErr = null;
            Log.i(TAG, "自检: 裸布局 apply 成功 view=" + (v == null ? "null" : v.getClass().getName()));
        } catch (Throwable t) {
            layoutErr = t;
            Log.e(TAG, "自检: 裸布局 apply 失败", t);
        }
        rLayout = layoutErr == null ? "正常" : rootMessage(layoutErr);
        addResult(P, "布局能否加载", layoutErr == null,
                layoutErr == null ? "组件骨架正常" : rLayout);

        // ---- 检查 2：完整内容（含全部 action）能否应用 ----
        Throwable actionErr = null;
        String actionMsg = "内容与交互正常";
        try {
            RemoteViews full = PickupWidgetProvider.buildForDiag(this, null);
            full.apply(this, new FrameLayout(this));
            Log.i(TAG, "自检: 完整 build apply 成功");
        } catch (Throwable t) {
            actionErr = t;
            Log.e(TAG, "自检: 完整 build apply 失败", t);
        }
        rAction = actionErr == null ? "正常" : rootMessage(actionErr);
        addResult(P, "内容与交互能否应用", actionErr == null,
                actionErr == null ? actionMsg : rAction);

        // ---- 检查 3：布局里是否混进了 RemoteViews 不允许的 View 类 ----
        String banned = findBannedClasses();
        rBanned = banned == null ? "全部在系统白名单内" : banned;
        addResult(P, "是否含不允许的控件", banned == null, rBanned);

        // ---- 结论 ----
        boolean allOk = layoutErr == null && actionErr == null && banned == null;
        diagOk = allOk;
        TextView verdict = UiKit.text(this,
                allOk ? "✓ 小组件本身没有问题"
                        : "✗ 发现问题，见上方明细",
                15f, allOk ? P.accentInk : P.heroWarnInk);
        verdict.setTypeface(null, Typeface.BOLD);
        verdict.setPadding(0, dp(14), 0, 0);
        resultBox.addView(verdict);

        TextView hint = UiKit.text(this,
                allOk
                        ? "自检正常，但桌面上还是不对 → 那是桌面留着旧组件的视图："
                        + "长按组件「移除」，再重新添加一次。"
                        : "自检失败 → 点下面的按钮复制结果，发到 QQ 群 901543676，"
                        + "这份结果能直接定位到是哪一处不兼容。",
                12f, P.muteInk);
        hint.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.setMargins(0, dp(8), 0, 0);
        resultBox.addView(hint, hlp);

        TextView copy = UiKit.text(this, "复制诊断信息", 13.5f, P.onAccent);
        copy.setGravity(Gravity.CENTER);
        copy.setTypeface(null, Typeface.BOLD);
        copy.setPadding(0, dp(12), 0, dp(12));
        copy.setBackground(UiKit.bg(P.accent, this, 14));
        copy.setOnClickListener(v -> copyDiag(P));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(0, dp(14), 0, 0);
        resultBox.addView(copy, clp);

        // ---- 真实渲染预览 ----
        // v4.0.2 第三轮：RemoteViews.recreateView()（API 31+）能在普通 Activity 里
        // 把小组件布局渲染成真实 View——不需要桌面授权、不需要 AppWidgetHost。
        // 于是「行高按权重均分」这种只有真机才看得出的效果，这里能直接验。
        addLivePreview(P);
    }

    /** 用真实布局渲染一份小组件出来看看 */
    private void addLivePreview(UiKit.Palette P) {
        TextView title = UiKit.text(this, "真实渲染（模拟 3 排格子高度）", 14.5f, P.navInk);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.setMargins(0, dp(16), 0, 0);

        android.widget.FrameLayout host = new android.widget.FrameLayout(this);
        android.widget.FrameLayout.LayoutParams hostLp = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(280));
        hostLp.setMargins(0, dp(10), 0, 0);

        try {
            RemoteViews rv = PickupWidgetProvider.buildForDiag(this, null);
            // apply() 用的是与桌面同一套受限制的 inflater（所以它能抓到
            // 「不允许 inflate 的控件」这类错误），并会真实执行所有 action。
            // 容器固定 280dp 高 → 根布局 match_parent 撑满 → 行按权重均分，
            // 于是「行高自适应、填满组件」的效果在这里能直接看到。
            View v = rv.apply(this, host);
            if (v != null) {
                host.addView(v, new android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            }
        } catch (Throwable t) {
            Log.w(TAG, "live preview failed: " + t);
        }

        // 说明（一句话即可：这里看到的就是桌面上的样子）
        TextView note = UiKit.text(this,
                "上面就是组件在桌面上的实际样子；放不下的整行不显示，不会露出半个取件码。",
                12f, P.muteInk);
        note.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        noteLp.setMargins(0, dp(8), 0, 0);

        // 挂到页面（加进 ScrollView 的内容盒，而不是 content 根——
        // 加到根上会盖住整个页面）
        if (contentBox == null) return;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(title, titleLp);
        box.addView(host, hostLp);
        box.addView(note, noteLp);
        contentBox.addView(box, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** 把三项结论 + 环境信息拼成一段可直接发群里的文本 */
    private void copyDiag(UiKit.Palette P) {
        String txt = "取件码助手 " + AppVersion.label(this) + " · 小组件自检\n"
                + "设备：" + android.os.Build.BRAND + " " + android.os.Build.MODEL
                + " / Android " + android.os.Build.VERSION.RELEASE + "\n"
                + "结果：" + (diagOk ? "自检正常" : "发现问题") + "\n"
                + "1. 布局能否加载：" + rLayout + "\n"
                + "2. 内容与交互：" + rAction + "\n"
                + "3. 控件白名单：" + rBanned;
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(
                    android.content.ClipData.newPlainText("小组件自检", txt));
            Toast.makeText(this, "已复制，直接粘贴到 QQ 群即可", Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Toast.makeText(this, "复制失败：" + t, Toast.LENGTH_SHORT).show();
        }
    }

    private void addResult(UiKit.Palette P, String title, boolean ok, String detail) {
        if (resultBox.getChildCount() > 0) resultBox.addView(UiKit.divider(this, P));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);

        TextView t = UiKit.text(this, (ok ? "✓ " : "✗ ") + title, 14f,
                ok ? P.accentInk : P.heroWarnInk);
        t.setTypeface(null, Typeface.BOLD);
        row.addView(t);

        TextView d = UiKit.text(this, detail, 12f, P.muteInk);
        d.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.setMargins(0, dp(4), 0, 0);
        row.addView(d, dlp);

        resultBox.addView(row);
    }

    /**
     * 扫一遍布局里用到的类名，找出 RemoteViews 白名单之外的。
     *
     * <p>这是本次事故的通用化检查：桌面 inflate 失败时最常见的原因就是
     * 用了 {@code <View>} / {@code <Space>} 这类抽象或不在白名单的容器。
     */
    private String findBannedClasses() {
        String xml;
        try {
            java.io.InputStream in = getResources().openRawResource(R.layout.widget_pickup_v4);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            xml = new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
        java.util.List<String> banned = new java.util.ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<([A-Za-z][A-Za-z0-9_.]*)").matcher(xml);
        while (m.find()) {
            String cls = m.group(1);
            if (cls.contains(".")) continue;                 // 自定义 View 类，不在此检查范围
            String full = "android.widget." + cls;
            if (!ALLOWED.contains(full)) banned.add(full);
        }
        return banned.isEmpty() ? null : "这些控件不允许出现在小组件布局里：" + banned;
    }

    /**
     * RemoteViews 允许 inflate 的 View 类。
     *
     * <p>取自 Android 框架的 {@code RemoteViews.ALLOWED_LAYOUT_CLASSES}。
     * 注意 {@code android.view.View} 与 {@code android.widget.Space} 都不在其中——
     * 前者是所有 View 的基类（抽象语义），后者是纯占位控件。
     */
    private static final java.util.Set<String> ALLOWED = new java.util.HashSet<>(
            java.util.Arrays.asList(
                    "android.widget.FrameLayout",
                    "android.widget.LinearLayout",
                    "android.widget.RelativeLayout",
                    "android.widget.GridLayout",
                    "android.view.ViewFlipper",
                    "android.widget.ScrollView",
                    "android.widget.AbsListView",
                    "android.widget.ListView",
                    "android.widget.Spinner",
                    "android.widget.TextView",
                    "android.widget.ImageView",
                    "android.widget.ImageButton",
                    "android.widget.ProgressBar",
                    "android.widget.Button",
                    "android.widget.CheckBox",
                    "android.widget.RadioButton",
                    "android.widget.RadioGroup",
                    "android.widget.EditText"));

    /** 取最内层的异常信息——真正的原因往往藏在 cause 里 */
    private String rootMessage(Throwable t) {
        Throwable c = t;
        int depth = 0;
        while (c.getCause() != null && depth < 8) {
            c = c.getCause();
            depth++;
        }
        String msg = c.getMessage();
        if (msg == null || msg.trim().isEmpty()) msg = c.getClass().getName();
        return msg;
    }

    private int dp(float v) {
        return UiKit.dp(this, v);
    }
}
