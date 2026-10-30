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
        sv.addView(box, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

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
            RemoteViews bare = new RemoteViews(getPackageName(), R.layout.widget_pickup_v2);
            View v = bare.apply(this, new FrameLayout(this));
            layoutErr = null;
            Log.i(TAG, "自检: 裸布局 apply 成功 view=" + (v == null ? "null" : v.getClass().getName()));
        } catch (Throwable t) {
            layoutErr = t;
            Log.e(TAG, "自检: 裸布局 apply 失败", t);
        }
        addResult(P, "布局能否加载", layoutErr == null,
                layoutErr == null ? "组件骨架正常" : rootMessage(layoutErr));

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
        addResult(P, "内容与交互能否应用", actionErr == null,
                actionErr == null ? actionMsg : rootMessage(actionErr));

        // ---- 检查 3：布局里是否混进了 RemoteViews 不允许的 View 类 ----
        String banned = findBannedClasses();
        addResult(P, "是否含不允许的控件", banned == null,
                banned == null ? "全部在系统白名单内" : banned);

        // ---- 结论 ----
        boolean allOk = layoutErr == null && actionErr == null && banned == null;
        TextView verdict = UiKit.text(this,
                allOk ? "✓ 小组件本身没有问题"
                        : "✗ 发现问题，见上方明细",
                15f, allOk ? P.accentInk : P.heroWarnInk);
        verdict.setTypeface(null, Typeface.BOLD);
        verdict.setPadding(0, dp(14), 0, 0);
        resultBox.addView(verdict);

        TextView hint = UiKit.text(this,
                allOk
                        ? "如果桌面上仍然显示异常，那是桌面还留着旧组件的视图——"
                        + "长按组件 → 移除 → 再添加一次即可。"
                        : "把上面的明细发给开发者，可以直接定位到具体是哪一处不兼容。",
                12f, P.muteInk);
        hint.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.setMargins(0, dp(8), 0, 0);
        resultBox.addView(hint, hlp);
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
            java.io.InputStream in = getResources().openRawResource(R.layout.widget_pickup_v2);
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
