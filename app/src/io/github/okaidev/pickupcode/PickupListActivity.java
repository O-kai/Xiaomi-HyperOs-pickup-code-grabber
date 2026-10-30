package io.github.okaidev.pickupcode;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 4.0 本地取件记录页：待取件 + 历史，支持筛选、搜索与数据管理。
 *
 * <p>这一页是「本地库」的入口页：简洁模式没有设置页，记录的保留期、
 * 导出导入、清空都在这里的「管理」面板里（与进阶模式设置页共用实现）。
 *
 * <p>配色全部走 UiKit.palette，跟随浅色/深色主题；此前这一页是写死浅色的，
 * 深色模式下会出现白底浅字、根本看不清。
 */
public class PickupListActivity extends Activity {

    /** 筛选条件：null = 全部 */
    private static final String[] FILTERS = {
            null,
            PickupRecord.STATUS_PENDING,
            PickupRecord.STATUS_PICKED_UP,
    };
    private static final String[] FILTER_LABELS = { "全部", "待取", "已取" };

    private UiKit.Palette p;
    private LinearLayout listBox;
    private LinearLayout filterRow;
    private EditText searchBox;
    private TextView countView;

    private int filterIndex = 0;
    private String keyword = "";

    private final RecordManager records = new RecordManager(this);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // v4.0：先定主题再 setContentView，否则会先按浅色建界面
        UiKit.applyTheme(this);
        super.onCreate(savedInstanceState);
        p = UiKit.palette(this);

        records.setOnChanged(this::refresh);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(p.bg);

        root.addView(buildHeader());

        // 筛选 + 搜索
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.VERTICAL);
        toolbar.setPadding(dp(16), dp(4), dp(16), dp(8));

        filterRow = new LinearLayout(this);
        filterRow.setOrientation(LinearLayout.HORIZONTAL);
        filterRow.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.addView(filterRow);

        searchBox = new EditText(this);
        searchBox.setTextSize(13.5f);
        searchBox.setTextColor(UiKit.INK);
        searchBox.setHintTextColor(UiKit.INK3);
        searchBox.setSingleLine(true);
        searchBox.setHint("搜索取件码 / 来源 / 地点");
        searchBox.setBackground(UiKit.bg(p.cardSoft, this, 12, p.line, 1f));
        searchBox.setPadding(dp(12), dp(9), dp(12), dp(9));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.setMargins(0, dp(10), 0, 0);
        searchBox.setLayoutParams(slp);
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                keyword = s.toString().trim();
                refresh();
            }
        });
        toolbar.addView(searchBox);
        root.addView(toolbar);

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
        rebuildFilters();
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(14), dp(16), dp(10));

        TextView back = UiKit.text(this, "‹", 30, p.accentInk);
        back.setPadding(0, 0, dp(14), dp(4));
        back.setOnClickListener(v -> finish());
        header.addView(back);

        TextView title = UiKit.text(this, "取件记录", 22, UiKit.INK);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        countView = UiKit.text(this, "", 12, UiKit.INK3);
        header.addView(countView);

        TextView manage = UiKit.text(this, "管理", 13, p.accentInk);
        manage.setPadding(dp(12), dp(8), 0, dp(8));
        manage.setOnClickListener(v -> records.showManageDialog());
        header.addView(manage);

        return header;
    }

    /** 重建筛选分段（全部 / 待取 / 已取） */
    private void rebuildFilters() {
        filterRow.removeAllViews();
        for (int i = 0; i < FILTER_LABELS.length; i++) {
            final int idx = i;
            boolean on = i == filterIndex;
            TextView seg = UiKit.text(this, FILTER_LABELS[i], 13.5f,
                    on ? p.onAccent : p.accentInk);
            seg.setGravity(Gravity.CENTER);
            seg.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
            seg.setPadding(0, dp(9), 0, dp(9));
            seg.setBackground(UiKit.bg(on ? p.accent : p.chipBg, this, 11));
            seg.setOnClickListener(v -> {
                if (filterIndex == idx) return;
                filterIndex = idx;
                rebuildFilters();
                refresh();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(idx == 0 ? 0 : dp(8), 0, 0, 0);
            seg.setLayoutParams(lp);
            filterRow.addView(seg);
        }
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
        p = UiKit.palette(this);
        refresh();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        records.onActivityResult(requestCode, resultCode, data);
    }

    private void refresh() {
        PickupRepository.get(this).queryAsync(FILTERS[filterIndex], keyword, 300, this::render);
    }

    private void render(List<PickupRecord> list) {
        if (isFinishing() || isDestroyed()) return;
        listBox.removeAllViews();
        if (countView != null) {
            countView.setText(list == null ? "" : list.size() + " 条");
        }
        if (list == null || list.isEmpty()) {
            listBox.addView(emptyView());
            return;
        }
        for (PickupRecord record : list) {
            listBox.addView(card(record));
        }
    }

    private View emptyView() {
        TextView v = UiKit.text(this,
                keyword.isEmpty() ? "暂无取件记录\n收到短信后会自动出现" : "没有匹配的记录",
                14, UiKit.INK3);
        v.setGravity(Gravity.CENTER);
        v.setPadding(0, dp(80), 0, 0);
        return v;
    }

    private View card(PickupRecord record) {
        boolean pending = record.isPending();
        LinearLayout card = UiKit.card(this, p);
        card.setPadding(dp(16), dp(14), dp(16), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(10));
        card.setLayoutParams(lp);

        // v4.0.1：视觉区分——待取的卡片左侧有一条强调色竖条，
        // 已取的整张卡片灰掉（半透明 + 无竖条 + 取件码加删除线）。
        // 之前两者的差别只在右上角一个 12sp 的小标签，扫一眼根本分不出来。
        //
        // 注意：card 本身是竖向布局，竖条与内容列必须放进一个横向容器里，
        // 否则两者会上下排列（竖条独占一行，内容被挤到下一行）。
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        if (pending) {
            View bar = new View(this);
            bar.setBackgroundColor(p.accent);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    dp(3), LinearLayout.LayoutParams.MATCH_PARENT);
            blp.setMargins(0, dp(2), dp(12), dp(2));
            row.addView(bar, blp);
        } else {
            card.setAlpha(0.62f);
        }

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        row.addView(col, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView code = UiKit.text(this, record.code, 21,
                pending ? UiKit.INK : UiKit.INK3);
        code.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        // 已取的加删除线：一眼能看出「这条已经处理完了」
        if (!pending) {
            code.setPaintFlags(code.getPaintFlags()
                    | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
        }
        top.addView(code, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // 超过三天还没取的件多半已经过期了——提示一下，但绝不自动删
        if (pending
                && Retention.isProbablyExpired(record.receivedAt, System.currentTimeMillis())) {
            top.addView(UiKit.badgeWarn(this, "可能已过期"));
        }

        TextView state = UiKit.text(this, pending ? "待取" : "已取", 12,
                pending ? p.accentInk : UiKit.INK3);
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        stp.setMargins(dp(8), 0, 0, 0);
        top.addView(state, stp);
        col.addView(top);

        String meta = (empty(record.source) ? "—" : record.source)
                + " · " + (empty(record.place) ? "—" : record.place)
                + " · " + format(record.receivedAt);
        TextView metaView = UiKit.text(this, meta, 12, UiKit.INK3);
        metaView.setPadding(0, dp(4), 0, dp(10));
        col.addView(metaView);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);

        TextView copy = action("复制");
        copy.setOnClickListener(v -> copy(record.code));
        actions.addView(copy);

        if (pending) {
            TextView done = action("已取件");
            done.setOnClickListener(v -> markDone(record));
            actions.addView(done);
        } else {
            // v4.0.1：已取的可以「恢复为待取」——点错了能反悔，形成闭环
            TextView back = action("恢复待取");
            back.setOnClickListener(v -> restorePending(record));
            actions.addView(back);
        }

        // v4.0.1：单条删除。此前只有「管理」里的按策略清理，
        // 想删掉某一条误抓的记录只能清空全部——太粗了。
        TextView del = action("删除");
        del.setTextColor(UiKit.DANGER);
        del.setOnClickListener(v -> confirmDelete(record));
        actions.addView(del);

        col.addView(actions);

        card.setOnClickListener(v -> copy(record.code));
        return card;
    }

    /** 恢复为待取（v4.0.1）：点错了「已取件」能反悔。 */
    private void restorePending(PickupRecord record) {
        PickupRepository.get(this).restorePendingAsync(record.id, ok -> {
            if (isFinishing() || isDestroyed()) return;
            Toast.makeText(this, ok > 0 ? "已恢复为待取" : "恢复失败",
                    Toast.LENGTH_SHORT).show();
            refresh();
        });
    }

    /** 删除单条记录（v4.0.1）：二次确认，避免手滑。 */
    private void confirmDelete(PickupRecord record) {
        UiKit.dialog(this, p, R.drawable.ic_ban, "删除这条记录",
                "将从本机删除：" + record.code + "\n\n"
                        + (empty(record.source) ? "" : record.source + " · ")
                        + format(record.receivedAt) + "\n\n"
                        + "删除后无法恢复。（系统待办里的内容不受影响。）",
                null,
                UiKit.primary("确认删除", () -> {
                    PickupRepository.get(this).deleteAsync(record.id);
                    Toast.makeText(this, "已删除 " + record.code, Toast.LENGTH_SHORT).show();
                    refresh();
                }),
                UiKit.ghost("取消", null));
    }

    private TextView action(String label) {
        TextView v = UiKit.text(this, label, 13, p.accentInk);
        v.setPadding(dp(14), dp(8), dp(14), dp(8));
        v.setBackground(UiKit.bg(p.chipBg, this, 10));
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

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
