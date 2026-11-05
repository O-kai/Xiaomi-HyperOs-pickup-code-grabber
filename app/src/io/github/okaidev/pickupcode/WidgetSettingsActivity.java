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
    private TextView previewTitle;
    private TextView previewCount;
    private UiKit.Switch placeSwitch;
    private UiKit.Switch codeSwitch;
    /** 下级的整行容器——上级关掉时要整行变淡 */
    private LinearLayout placeRow;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // v4.0：先定主题再 setContentView，否则会先按浅色建界面
        UiKit.applyTheme(this);
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
        sv.setPadding(dp(18), dp(4), dp(18), dp(24));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        sv.addView(box, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        box.addView(buildFontCard(), UiKit.cardLp(this, 0));
        box.addView(buildShowCard(), UiKit.cardLp(this, 10));
        box.addView(buildRowsCard(), UiKit.cardLp(this, 10));
        box.addView(buildPreviewCard(), UiKit.cardLp(this, 10));

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

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14), dp(14), dp(20), dp(10));

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

    private View buildPreviewCard() {
        LinearLayout card = UiKit.card(this, P);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));

        TextView title = UiKit.text(this, "预览", 14.5f, P.navInk);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        card.addView(title);

        LinearLayout preview = new LinearLayout(this);
        preview.setOrientation(LinearLayout.VERTICAL);
        preview.setBackgroundResource(R.drawable.widget_bg);
        preview.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.setMargins(0, dp(10), 0, 0);
        card.addView(preview, plp);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        previewTitle = UiKit.text(this, "待取件", 18f, 0xFFFFFFFF);
        previewTitle.setTypeface(null, Typeface.BOLD);
        head.addView(previewTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        previewCount = UiKit.text(this, "2 个", 12f, 0xB3FFFFFF);
        head.addView(previewCount);
        preview.addView(head);

        previewRows = new LinearLayout(this);
        previewRows.setOrientation(LinearLayout.VERTICAL);
        preview.addView(previewRows, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return card;
    }

    /** 预览里加一行（picked = true 表示已取件那一行：划掉 + 变灰 + 无 ✓） */
    private void addPreviewRow(boolean picked) {
        addPreviewRow(picked, picked ? "09-8-7654" : "12-3-4567",
                picked ? "中通快递 · 已取" : "菜鸟驿站 · 1 号柜");
    }

    /** 预览里加一行（可指定码与地点） */
    private void addPreviewRow(boolean picked, String codeText, String placeText) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        // 行高与真实布局的下限一致（真实布局按权重均分，实际会略大于 40dp）
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(WidgetPrefs.ROW_DP));
        previewRows.addView(row, rlp);

        boolean showCode = WidgetPrefs.showCode(this);
        boolean showPlace = showCode && WidgetPrefs.showPlace(this);

        TextView code = UiKit.text(this, codeText, 17f,
                picked ? 0x99FFFFFF : 0xFFFFFFFF);
        code.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        if (picked) {
            // 已取的加删除线：与小组件实际表现一致
            code.setPaintFlags(code.getPaintFlags()
                    | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
        }
        if (!showCode) code.setText("");
        row.addView(code);

        TextView place = UiKit.text(this, placeText, 11f,
                picked ? 0x80FFFFFF : 0xB3FFFFFF);
        place.setMaxLines(1);
        LinearLayout.LayoutParams plp2 = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        plp2.setMargins(dp(10), 0, dp(8), 0);
        row.addView(place, plp2);

        TextView check = UiKit.text(this, "✓", 16f, 0xFFFFFFFF);
        check.setGravity(Gravity.CENTER);
        check.setBackgroundResource(R.drawable.widget_done_bg);
        if (picked) {
            // 已取的不再显示 ✓（小组件里也是这样）
            check.setVisibility(View.INVISIBLE);
        }
        row.addView(check, new LinearLayout.LayoutParams(dp(28), dp(28)));

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
        boolean showAll = WidgetPrefs.showAll(this);
        boolean showCode = WidgetPrefs.showCode(this);

        previewTitle.setText(showAll ? "取件记录" : "待取件");
        previewCount.setText(showAll ? "2 待取 · 1 已取" : "2 个待取");

        previewRows.removeAllViews();
        // v4.0.2 第四轮：隐私模式（关掉「显示取件码」）与真实组件保持一致——
        // 不逐条显示，码/地点/✓ 全部取消，改在展示区直白给出汇总。
        if (!showCode) {
            previewCount.setText("");
            TextView sum = UiKit.text(this,
                    showAll ? "2 个待取 · 1 个已取" : "2 个待取",
                    16f, 0xFFFFFFFF);
            sum.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
            previewRows.addView(sum, slp);
            return;
        }
        addPreviewRow(false, "12-3-4567", "菜鸟驿站 · 1 号柜");
        addPreviewRow(false, "58-2-9041", "顺丰速运 · 望江路驿站");
        if (showAll) {
            // 全部信息模式：已取的也画出来（划掉 + 变灰 + 无 ✓）
            addPreviewRow(true);
        }
        // 字号缩放（预览里的行是新建的，所以要在这里统一设）
        for (int i = 0; i < previewRows.getChildCount(); i++) {
            View row = previewRows.getChildAt(i);
            if (!(row instanceof LinearLayout)) continue;
            LinearLayout r = (LinearLayout) row;
            TextView c = (TextView) r.getChildAt(0);
            TextView p = (TextView) r.getChildAt(1);
            c.setTextSize(17f * scale);
            p.setTextSize(11f * scale);
        }
    }

    private void applyToWidget() {
        PickupWidgetProvider.refreshAsync(this);
    }

    private int dp(float v) {
        return UiKit.dp(this, v);
    }
}
