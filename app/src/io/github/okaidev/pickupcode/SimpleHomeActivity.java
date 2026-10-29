package io.github.okaidev.pickupcode;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
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

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 4.0 简洁模式首页：只做一件事——把待取的码摆出来，点一下复制，取完点「已取」。
 *
 * 与进阶模式（LauncherActivity 四 Tab）共享同一个本地取件库与设置，
 * 互切不丢数据。首次启动由 LauncherActivity 让用户二选一。
 */
public class SimpleHomeActivity extends Activity {

    private UiKit.Palette P;
    private LinearLayout listBox;
    private TextView countView;
    private TextView emptyCard;

    private final PickupRepository.ChangeListener changeListener = this::reload;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        P = UiKit.palette(this);
        getWindow().setStatusBarColor(P.bg);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(P.bg);

        root.addView(buildHeader());

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setClipToPadding(false);
        sv.setPadding(dp(18), dp(4), dp(18), dp(24));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        sv.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        content.addView(listBox);

        content.addView(buildFooter(), UiKit.cardLp(this, 18));

        setContentView(root);
        reload();
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(20), dp(18), dp(20), dp(10));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = UiKit.text(this, "取件码助手", 24f, P.navInk);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titleRow.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView advanced = UiKit.text(this, "进阶 ›", 13f, P.accent);
        advanced.setPadding(dp(10), dp(6), dp(2), dp(6));
        advanced.setOnClickListener(v -> switchToAdvanced());
        titleRow.addView(advanced);
        header.addView(titleRow);

        countView = UiKit.text(this, "正在读取…", 12.5f, P.label);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(0, dp(5), 0, 0);
        header.addView(countView, clp);
        return header;
    }

    private View buildFooter() {
        LinearLayout card = UiKit.card(this, P);
        card.setPadding(dp(16), dp(12), dp(16), dp(12));

        TextView all = UiKit.text(this, "查看全部取件记录 ›", 14f, P.accent);
        all.setPadding(0, dp(8), 0, dp(8));
        all.setOnClickListener(v -> {
            startActivity(new Intent(this, PickupListActivity.class));
        });
        card.addView(all);

        View line = UiKit.divider(this, P);
        card.addView(line);

        TextView widget = UiKit.text(this, "添加到桌面小组件 ›", 14f, P.accent);
        widget.setPadding(0, dp(10), 0, dp(8));
        widget.setOnClickListener(v -> WidgetPin.prompt(this));
        card.addView(widget);

        View line2 = UiKit.divider(this, P);
        card.addView(line2);

        TextView hint = UiKit.text(this,
                "简洁模式 · 规则、诊断、写入格式等设置可在进阶模式里调整",
                12f, P.muteInk);
        hint.setPadding(0, dp(10), 0, dp(4));
        hint.setLineSpacing(dp(1), 1.0f);
        card.addView(hint);
        return card;
    }

    @Override
    protected void onStart() {
        super.onStart();
        PickupRepository.addChangeListener(changeListener);
    }

    @Override
    protected void onStop() {
        PickupRepository.removeChangeListener(changeListener);
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        PickupRepository.get(this).pendingAsync(50, list -> {
            if (isFinishing() || isDestroyed()) return;
            render(list);
        });
    }

    private void render(List<PickupRecord> list) {
        int n = list == null ? 0 : list.size();
        countView.setText(n == 0 ? "收到取件短信后会自动出现在这里" : "待取件 " + n + " 个");
        listBox.removeAllViews();

        if (n == 0) {
            LinearLayout empty = UiKit.card(this, P);
            empty.setPadding(dp(16), dp(28), dp(16), dp(28));
            TextView title = UiKit.text(this, "暂无待取件", 17f, P.navInk);
            title.setGravity(Gravity.CENTER);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            empty.addView(title);
            TextView sub = UiKit.text(this, "取件码会从短信和通知里自动识别，不需要手动录入",
                    12.5f, P.muteInk);
            sub.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.setMargins(0, dp(8), 0, 0);
            empty.addView(sub, slp);
            listBox.addView(empty, UiKit.cardLp(this, 0));
            return;
        }

        for (int i = 0; i < n; i++) {
            listBox.addView(buildRecordCard(list.get(i)), UiKit.cardLp(this, i == 0 ? 0 : 10));
        }
    }

    private View buildRecordCard(PickupRecord record) {
        LinearLayout card = UiKit.card(this, P);
        card.setPadding(dp(16), dp(14), dp(16), dp(12));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView code = UiKit.text(this, record.code, 28f, P.navInk);
        code.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        top.addView(code, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView done = chip("已取", P.accentSoft, P.accentInk);
        done.setOnClickListener(v -> PickupActions.markDone(this, record, (localChanged, backendRc) -> {
            if (isFinishing() || isDestroyed()) return;
            String msg;
            if (localChanged <= 0) {
                msg = "标记失败";
            } else if (backendRc == 0) {
                msg = "已标记已取件";
            } else {
                msg = "已在本机标记，系统待办同步失败";
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
            reload();
        }));
        top.addView(done);
        card.addView(top);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.setMargins(0, dp(8), 0, 0);

        String place = record.place == null || record.place.trim().isEmpty()
                || "—".equals(record.place.trim()) ? "来源未识别" : record.place;
        TextView sub = UiKit.text(this, place + " · " + timeOf(record.receivedAt),
                12f, P.muteInk);
        sub.setMaxLines(1);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bottom.addView(sub, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView copy = chip("复制", P.chipBg, P.accentInk);
        copy.setOnClickListener(v -> copyCode(record.code));
        bottom.addView(copy);
        card.addView(bottom, blp);

        card.setOnClickListener(v -> copyCode(record.code));
        return card;
    }

    private TextView chip(String label, int bgColor, int fgColor) {
        TextView t = UiKit.text(this, label, 12.5f, fgColor);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), dp(7), dp(14), dp(7));
        GradientDrawable g = new GradientDrawable();
        g.setColor(bgColor);
        g.setCornerRadius(dp(12));
        t.setBackground(g);
        return t;
    }

    private void copyCode(String code) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("pickupcode", code));
            Toast.makeText(this, "已复制取件码：" + code, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "复制失败：" + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void switchToAdvanced() {
        UiMode.set(this, UiMode.ADVANCED);
        Intent i = new Intent(this, LauncherActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        finish();
    }

    private static String timeOf(long ts) {
        if (ts <= 0) return "";
        return new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(ts));
    }

    private int dp(float v) {
        return UiKit.dp(this, v);
    }
}
