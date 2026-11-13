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

        TextView readd = UiKit.text(this, "添加小组件", 13.5f, P.accentInk);
        readd.setGravity(Gravity.CENTER);
        readd.setTypeface(null, Typeface.BOLD);
        readd.setPadding(0, dp(12), 0, dp(12));
        readd.setBackground(UiKit.bg(P.chipBg, this, 12));
        readd.setOnClickListener(v -> WidgetPin.prompt(this));
        LinearLayout.LayoutParams rlp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp2.setMargins(0, dp(10), 0, 0);
        box.addView(readd, rlp2);

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
                "点取件码复制，点右侧 ✓ 标记已取；点标题查看全部。"
                        + "组件拉高后会显示更多包裹。\n\n"
                        + "小米负一屏入口在「添加小组件」中；是否能添加取决于系统小部件中心的收录情况。",
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

        previewRows = new LinearLayout(this);
        previewRows.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(144));
        lp.topMargin = dp(12);
        card.addView(previewRows, lp);
        return card;
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
        previewRows.removeAllViews();
        android.widget.RemoteViews rv = PickupWidgetProvider.buildForDiag(this, null);
        View preview = PickupWidgetProvider.applyPreview(this, rv);
        // Same RemoteViews as the host; sample data never enters the parcel database.
        previewRows.addView(preview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // buildForDiag omits PendingIntents and collection actions. Do not clear
        // listeners recursively: AdapterView rejects setOnClickListener, even null.
    }

    private void applyToWidget() {
        PickupWidgetProvider.refreshAsync(this);
    }

    private int dp(float v) {
        return UiKit.dp(this, v);
    }
}
