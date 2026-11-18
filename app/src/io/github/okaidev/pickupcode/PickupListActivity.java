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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

    private static final String PREFS_LIST = "pickup_list_prefs";
    private static final String KEY_SORT = "sort_mode";
    /** 0 = 时间倒序；1 = 地址聚合 */
    private static final int SORT_TIME = 0, SORT_PLACE = 1;

    private int filterIndex = 0;
    private int sortMode = SORT_TIME;
    private String keyword = "";

    /** 当前展开的那一条（一次只允许展开一条） */
    private LinearLayout expandedView;
    private LinearLayout sortRow;

    private final RecordManager records = new RecordManager(this);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // v4.0：先定主题再 setContentView，否则会先按浅色建界面
        UiKit.applyTheme(this);
        super.onCreate(savedInstanceState);
        p = UiKit.palette(this);
        sortMode = getSharedPreferences(PREFS_LIST, MODE_PRIVATE).getInt(KEY_SORT, SORT_TIME);

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
                collapseExpanded();          // 搜索词变了：展开态清掉，避免高度错位
                refresh();
            }
        });
        toolbar.addView(searchBox);

        // v4.2.1：排序开关就放在搜索框下面 —— 搜索是高频动作，不收进「管理」。
        sortRow = new LinearLayout(this);
        sortRow.setOrientation(LinearLayout.HORIZONTAL);
        sortRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams srp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        srp.setMargins(0, dp(8), 0, 0);
        sortRow.setLayoutParams(srp);
        toolbar.addView(sortRow);
        rebuildSort();

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

        // v4.2.1：位置不动，裸文字换成胶囊按钮，一眼看得出可点
        TextView manage = UiKit.text(this, "管理", 13, p.accentInk);
        manage.setGravity(Gravity.CENTER);
        manage.setPadding(dp(14), dp(7), dp(14), dp(7));
        manage.setBackground(UiKit.bg(p.chipBg, this, 10));
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
                collapseExpanded();          // 切页签：展开态清掉
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

    /** 排序方式分段：时间倒序 / 地址聚合 */
    private void rebuildSort() {
        if (sortRow == null) return;
        sortRow.removeAllViews();
        String[] labels = { "时间倒序", "地址聚合" };
        for (int i = 0; i < 2; i++) {
            final int mode = i;
            boolean on = mode == sortMode;
            TextView seg = UiKit.text(this, labels[i], 12.5f,
                    on ? p.onAccent : UiKit.INK2);
            seg.setGravity(Gravity.CENTER);
            seg.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
            seg.setPadding(0, dp(8), 0, dp(8));
            seg.setBackground(UiKit.bg(on ? p.accent : p.chipBg, this, 11));
            seg.setOnClickListener(v -> {
                if (sortMode == mode) return;
                sortMode = mode;
                getSharedPreferences(PREFS_LIST, MODE_PRIVATE).edit()
                        .putInt(KEY_SORT, sortMode).apply();
                collapseExpanded();          // 切排序：展开态清掉
                rebuildSort();
                refresh();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(mode == 0 ? 0 : dp(8), 0, 0, 0);
            seg.setLayoutParams(lp);
            sortRow.addView(seg);
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
        // v4.0.2 第五轮：滞留提示的含义。
        //
        // 判据是「收到超过一天还没取」——标上具体滞留天数，
        // 让用户一眼判断还值不值得跑一趟。
        // 它只是提醒，绝不自动删（说不定用户过两天才去取）。
        int staleCount = 0;
        long now = System.currentTimeMillis();
        for (PickupRecord r : list) {
            if (r != null && r.isPending()
                    && Retention.staleDays(r.receivedAt, now) > 0) staleCount++;
        }
        if (staleCount > 0) {
            listBox.addView(staleHint(staleCount),
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        for (Object node : buildNodes(list)) {
            if (node instanceof Group) {
                listBox.addView(groupHeader((Group) node));
            } else if (node instanceof String) {
                listBox.addView(sectionLabel((String) node));
            } else if (node instanceof PickupRecord) {
                listBox.addView(card((PickupRecord) node));
            }
        }
    }

    // ==================== 排序 / 分组（v4.2.1） ====================

    /** 一个地点分组：key 是归一化后的完整地点，newest 决定整组的压栈位置 */
    private static final class Group {
        final String key;
        final long newest;
        final List<PickupRecord> items;

        Group(String key, List<PickupRecord> items) {
            this.key = key;
            this.items = items;
            this.newest = items.isEmpty() ? 0L : items.get(0).receivedAt;
        }
    }

    private static final Comparator<PickupRecord> BY_TIME_DESC =
            (a, b) -> Long.compare(b.receivedAt, a.receivedAt);

    /**
     * 把查询结果排成「分组头 / 小标题 / 记录」的线性序列。
     *
     * <p>规则（用户 2026-11-19 确认）：
     * <ul>
     *   <li>时间倒序：一条线，按 received_at 倒排；</li>
     *   <li>地址聚合：按完整地点分组，组内倒排，组间按各组最新一条倒排
     *       —— 新消息来，整组压栈顶；</li>
     *   <li>「全部」页：未取段可切两种，已取段固定时间倒序、不参与分组；</li>
     *   <li>「待取」「已取」页：整页都可切两种；</li>
     *   <li>未识别地点也是一个正常分组，按自己的时间参与压栈，不固定沉底。</li>
     * </ul>
     */
    private List<Object> buildNodes(List<PickupRecord> list) {
        List<Object> out = new ArrayList<>();
        if (list == null || list.isEmpty()) return out;

        if (filterIndex == 0) {
            List<PickupRecord> pending = new ArrayList<>();
            List<PickupRecord> picked = new ArrayList<>();
            for (PickupRecord r : list) {
                if (r == null) continue;
                if (r.isPending()) pending.add(r); else picked.add(r);
            }
            appendSorted(out, pending, sortMode == 1);
            if (!picked.isEmpty()) {
                out.add("已取件 · " + picked.size() + " 条 · 时间倒序");
                appendSorted(out, picked, false);
            }
        } else {
            appendSorted(out, list, sortMode == 1);
        }
        return out;
    }

    private void appendSorted(List<Object> out, List<PickupRecord> list, boolean byPlace) {
        if (list == null || list.isEmpty()) return;
        if (!byPlace) {
            List<PickupRecord> copy = new ArrayList<>(list);
            Collections.sort(copy, BY_TIME_DESC);
            out.addAll(copy);
            return;
        }
        Map<String, List<PickupRecord>> buckets = new LinkedHashMap<>();
        for (PickupRecord r : list) {
            if (r == null) continue;
            String key = placeKey(r.place);
            List<PickupRecord> bucket = buckets.get(key);
            if (bucket == null) {
                bucket = new ArrayList<>();
                buckets.put(key, bucket);
            }
            bucket.add(r);
        }
        List<Group> groups = new ArrayList<>();
        for (Map.Entry<String, List<PickupRecord>> e : buckets.entrySet()) {
            Collections.sort(e.getValue(), BY_TIME_DESC);
            groups.add(new Group(e.getKey(), e.getValue()));
        }
        // 组间按「各组最新一条」倒排 —— 这就是用户说的堆栈：新消息到，整组压顶
        Collections.sort(groups, (a, b) -> {
            int c = Long.compare(b.newest, a.newest);
            return c != 0 ? c : a.key.compareTo(b.key);
        });
        for (Group g : groups) {
            out.add(g);
            out.addAll(g.items);
        }
    }

    /** 分组键 = 完整地点文字，只统一空白；识别不出归到「未识别地点」 */
    private static String placeKey(String place) {
        if (place == null) return "未识别地点";
        StringBuilder sb = new StringBuilder();
        boolean pendingSpace = false;
        for (int i = 0; i < place.length(); i++) {
            char c = place.charAt(i);
            if (Character.isWhitespace(c) || c == 0x3000) {
                pendingSpace = true;
            } else {
                if (pendingSpace && sb.length() > 0) sb.append(' ');
                pendingSpace = false;
                sb.append(c);
            }
        }
        String s = sb.toString();
        if (s.isEmpty() || "—".equals(s) || "-".equals(s)
                || "未知".equals(s) || "unknown".equalsIgnoreCase(s)) return "未识别地点";
        return s;
    }

    private View groupHeader(Group g) {
        TextView t = UiKit.text(this, g.key + " · " + g.items.size() + " 件 · " + format(g.newest),
                12.5f, p.accentInk);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(dp(10), dp(8), dp(10), dp(8));
        t.setBackground(UiKit.bg(p.chipBg, this, 9));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(2));
        t.setLayoutParams(lp);
        return t;
    }

    private View sectionLabel(String text) {
        TextView t = UiKit.text(this, text, 12f, UiKit.INK3);
        t.setPadding(dp(2), dp(14), dp(2), dp(6));
        return t;
    }

    /**
     * 滞留提示是什么意思——用一句话讲清楚。
     *
     * <p>只负责【创建】这个 View，不自己 addView——
     * 由调用方添加。（v4.0.2 第四轮修掉的闪退：
     * 这里原本既自己 addView 又被调用方 addView，同一个 View 被挂两次，
     * 抛 IllegalStateException: The specified child already has a parent，
     * 表现就是「点首页的查看全部 → 闪退」。）
     */
    private View staleHint(int count) {
        TextView v = UiKit.text(this,
                "标了「滞留 N 天」的，是收到已经超过一天还没去取的件："
                        + "取件码一般只有几天有效期，驿站多半已清理。\n"
                        + "这只是提醒，不会自动删除——过两天才去取也还算数。",
                12f, UiKit.INK3);
        v.setLineSpacing(dp(2), 1f);
        return v;
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

        // v4.0.2 第五轮：滞留提示改成写具体天数。
        //
        // 用户原话：「收到信息 24 小时之内的取件码：不要展示内容。
        // 超过 24 小时的取件码：写具体的滞留日期，就显示『滞留1天』、『滞留2天』等等
        // 这个样式，然后最多显示到『滞留7天』。如果超过7天的东西，就写『滞留7天以上』」
        // 以及「单纯写『可能』已失效，对于用户来说概念太模糊了」。
        //
        // 所以：一天之内的新记录不标任何东西；超过一天就写明滞留了几天。
        // 提醒而不删除——说不定用户过两天才去取。
        if (pending) {
            int staleDays = Retention.staleDays(record.receivedAt, System.currentTimeMillis());
            String badge = Retention.staleBadge(staleDays);
            if (!badge.isEmpty()) {
                top.addView(UiKit.badgeWarn(this, badge));
            }
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

        // v4.2.1：来源原文详情（默认收起，点「完整信息」就地展开）
        final LinearLayout detail = new LinearLayout(this);
        detail.setOrientation(LinearLayout.VERTICAL);
        detail.setVisibility(View.GONE);
        // 吞掉详情区里的点击，别冒泡到卡片的「复制取件码」
        detail.setOnClickListener(v -> { });

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);

        TextView full = action("完整信息");
        full.setOnClickListener(v -> toggleExpanded(record, detail));
        actions.addView(full);

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
        col.addView(detail);

        card.setOnClickListener(v -> copy(record.code));
        return card;
    }

    // ==================== 完整信息：展开 / 收起 / 全文复制 ====================

    /** 切页签、切排序、改搜索词时调用：一次只允许一条展开 */
    private void collapseExpanded() {
        if (expandedView != null) {
            expandedView.removeAllViews();
            expandedView.setVisibility(View.GONE);
            expandedView = null;
        }
    }

    private void toggleExpanded(PickupRecord record, LinearLayout detail) {
        if (expandedView == detail) {
            collapseExpanded();
            return;
        }
        collapseExpanded();
        buildDetail(detail, record);
        detail.setVisibility(View.VISIBLE);
        expandedView = detail;
    }

    /** 详情区只画来源原文 —— 解析好的码/地点由原来的「复制」按钮负责 */
    private void buildDetail(LinearLayout box, PickupRecord record) {
        box.removeAllViews();
        box.setPadding(0, dp(8), 0, dp(2));

        List<RawSources.Item> items = RawSources.parse(record.rawSources);
        TextView head = UiKit.text(this,
                items.isEmpty() ? "这条记录没有留下原文" : "来源 " + items.size() + " 条",
                12f, UiKit.INK3);
        head.setPadding(0, 0, 0, dp(6));
        box.addView(head);

        for (RawSources.Item it : items) {
            TextView src = UiKit.text(this, it.label(), 12f, p.accentInk);
            src.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            src.setPadding(0, dp(6), 0, dp(2));
            box.addView(src);

            TextView raw = UiKit.text(this, empty(it.text) ? "（空）" : it.text,
                    12.5f, UiKit.INK2);
            raw.setLineSpacing(dp(2), 1f);
            raw.setBackground(UiKit.bg(p.cardSoft, this, 9));
            raw.setPadding(dp(10), dp(8), dp(10), dp(8));
            box.addView(raw);
        }

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        btns.setPadding(0, dp(10), 0, 0);

        TextView close = action("收起");
        close.setOnClickListener(v -> collapseExpanded());
        close.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        close.setTextColor(UiKit.INK3);
        btns.addView(close);

        TextView all = action("全文复制");
        all.setOnClickListener(v -> copyRaw(record));
        btns.addView(all);
        box.addView(btns);
    }

    /** 全文复制：只复制来源信息，不重复解析后的码/地点 */
    private void copyRaw(PickupRecord record) {
        String text = RawSources.fullText(record.rawSources);
        if (text.isEmpty()) {
            Toast.makeText(this, "这条记录没有可复制的原文", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("pickup-raw", text));
            Toast.makeText(this, "已复制 " + RawSources.parse(record.rawSources).size()
                    + " 条来源原文", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "复制失败", Toast.LENGTH_SHORT).show();
        }
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
