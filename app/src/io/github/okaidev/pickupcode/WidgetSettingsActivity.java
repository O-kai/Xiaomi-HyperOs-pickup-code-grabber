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
    private TextView previewCode;
    private TextView previewPlace;
    private UiKit.Switch placeSwitch;

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
        box.addView(buildPlaceCard(), UiKit.cardLp(this, 10));
        box.addView(buildRowsCard(), UiKit.cardLp(this, 10));
        box.addView(buildPreviewCard(), UiKit.cardLp(this, 10));

        TextView note = UiKit.text(this,
                "小组件高度不够时会自动少显示几条；点取件码复制，点右侧 ✓ 标记已取。",
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

        TextView sub = UiKit.text(this, "地点会按比例同步缩放", 11.5f, P.muteInk);
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

    private View buildPlaceCard() {
        LinearLayout card = UiKit.card(this, P);
        placeSwitch = new UiKit.Switch(this, P, WidgetPrefs.showPlace(this));
        placeSwitch.setOnToggle(on -> {
            WidgetPrefs.setShowPlace(this, on);
            applyToWidget();
            refreshPreview();
        });
        LinearLayout row = UiKit.row(this, P, R.drawable.ic_target, "显示取件地点",
                "关掉后只显示大号取件码，更清爽", placeSwitch);
        card.addView(row);
        return card;
    }

    private View buildRowsCard() {
        LinearLayout card = UiKit.card(this, P);
        card.setPadding(dp(16), dp(14), dp(16), dp(16));

        TextView title = UiKit.text(this, "最多显示条数", 14.5f, P.navInk);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        card.addView(title);

        TextView sub = UiKit.text(this, "实际条数还会受组件高度限制", 11.5f, P.muteInk);
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
        TextView t = UiKit.text(this, "待取件", 16f, 0xFFFFFFFF);
        t.setTypeface(null, Typeface.BOLD);
        head.addView(t, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(UiKit.text(this, "2 个", 12f, 0xB3FFFFFF));
        preview.addView(head);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.setMargins(0, dp(10), 0, 0);
        preview.addView(row, rlp);

        previewCode = UiKit.text(this, "12-3-4567", 17f, 0xFFFFFFFF);
        previewCode.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        row.addView(previewCode);

        previewPlace = UiKit.text(this, "菜鸟驿站 · 1 号柜", 11f, 0xB3FFFFFF);
        previewPlace.setMaxLines(1);
        LinearLayout.LayoutParams plp2 = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        plp2.setMargins(dp(10), 0, dp(8), 0);
        row.addView(previewPlace, plp2);

        TextView check = UiKit.text(this, "✓", 16f, 0xFFFFFFFF);
        check.setGravity(Gravity.CENTER);
        check.setBackgroundResource(R.drawable.widget_done_bg);
        row.addView(check, new LinearLayout.LayoutParams(dp(34), dp(34)));
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
            int current = WidgetPrefs.maxRows(this);
            for (int n = 2; n <= 5; n++) {
                final int rows = n;
                addSeg(rowsSeg, n + " 条", current == n, () -> {
                    WidgetPrefs.setMaxRows(this, rows);
                    rebuildSegments();
                    applyToWidget();
                });
            }
        }
    }

    private void addSeg(LinearLayout parent, String label, boolean selected, Runnable onClick) {
        TextView tv = UiKit.text(this, label, 13.5f, selected ? 0xFFFFFFFF : P.accentInk);
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
        if (previewCode == null) return;
        float scale = WidgetPrefs.fontScale(this);
        previewCode.setTextSize(17f * scale);
        previewPlace.setTextSize(11f * scale);
        // 用 INVISIBLE 而不是 GONE：保留权重占位，勾选按钮才会留在最右侧
        previewPlace.setVisibility(WidgetPrefs.showPlace(this) ? View.VISIBLE : View.INVISIBLE);
    }

    private void applyToWidget() {
        PickupWidgetProvider.refreshAsync(this);
    }

    private int dp(float v) {
        return UiKit.dp(this, v);
    }
}
