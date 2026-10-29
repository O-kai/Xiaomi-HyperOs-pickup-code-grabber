package io.github.okaidev.pickupcode;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 4.0 本地取件列表：待取件 + 最近记录。
 * 第一阶段先做只读/复制/标记完成；历史归档、筛选、导出在后续阶段补。
 */
public class PickupListActivity extends Activity {

    private LinearLayout listBox;
    private TextView emptyView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(242, 243, 245));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(14), dp(20), dp(10));

        TextView back = text("‹", 30, Color.rgb(74, 111, 165));
        back.setPadding(0, 0, dp(14), dp(4));
        back.setOnClickListener(v -> finish());
        header.addView(back);

        TextView title = text("取件记录", 22, Color.rgb(26, 26, 26));
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView addWidget = text("添加到桌面", 12, Color.rgb(74, 111, 165));
        addWidget.setPadding(dp(10), dp(6), 0, dp(6));
        addWidget.setOnClickListener(v -> requestPinWidget());
        header.addView(addWidget);
        root.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setPadding(dp(16), 0, dp(16), dp(24));
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(listBox, new ScrollView.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        emptyView = text("暂无取件记录\n收到短信后会自动出现", 14, Color.rgb(120, 120, 120));
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(0, dp(80), 0, 0);
    }

    /** 4.0 Stage 2.1：本地库变更时列表也要实时刷新（小组件/通知动作可能改状态） */
    private final PickupRepository.ChangeListener changeListener = this::refresh;

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
        refresh();
    }

    private void refresh() {
        PickupRepository.get(this).recentAsync(100, this::render);
    }

    /** 4.0：小组件添加入口，具体流程见 {@link WidgetPin}。 */
    private void requestPinWidget() {
        WidgetPin.prompt(this);
    }

    private void render(List<PickupRecord> records) {
        if (isFinishing() || isDestroyed()) return;
        listBox.removeAllViews();
        if (records == null || records.isEmpty()) {
            listBox.addView(emptyView);
            return;
        }
        for (PickupRecord record : records) {
            listBox.addView(card(record));
        }
    }

    private View card(PickupRecord record) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(16));
        card.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(10));
        card.setLayoutParams(lp);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView code = text(record.code, 22, record.isPending()
                ? Color.rgb(26, 26, 26) : Color.rgb(150, 150, 150));
        code.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        top.addView(code, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView state = text(record.isPending() ? "待取" : "已取", 12,
                record.isPending() ? Color.rgb(74, 111, 165) : Color.rgb(62, 142, 94));
        top.addView(state);
        card.addView(top);

        String meta = (empty(record.source) ? "—" : record.source)
                + " · " + (empty(record.place) ? "—" : record.place)
                + " · " + format(record.receivedAt);
        TextView metaView = text(meta, 12, Color.rgb(110, 110, 110));
        metaView.setPadding(0, dp(4), 0, dp(8));
        card.addView(metaView);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);

        TextView copy = action("复制");
        copy.setOnClickListener(v -> copy(record.code));
        actions.addView(copy);

        if (record.isPending()) {
            TextView done = action("已取件");
            done.setOnClickListener(v -> markDone(record));
            actions.addView(done);
        }
        card.addView(actions);

        card.setOnClickListener(v -> copy(record.code));
        return card;
    }

    private TextView action(String label) {
        TextView v = text(label, 13, Color.rgb(74, 111, 165));
        v.setPadding(dp(14), dp(6), dp(14), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(232, 238, 246));
        bg.setCornerRadius(dp(10));
        v.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(8), 0, 0, 0);
        v.setLayoutParams(lp);
        return v;
    }

    private void copy(String code) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("pickupcode", code));
            Toast.makeText(this, "已复制：" + code, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "复制失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void markDone(PickupRecord record) {
        PickupActions.markDone(this, record, (localChanged, backendRc) -> {
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
            refresh();
        });
    }

    private static boolean empty(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String format(long ts) {
        try {
            return new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(ts));
        } catch (Throwable t) {
            return "";
        }
    }

    private TextView text(String s, float sp, int color) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        return v;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
