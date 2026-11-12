package io.github.okaidev.pickupcode;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Local development surface. Excluded from release APKs; samples never enter the database. */
public final class SurfacePreviewActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(22), dp(48), dp(22), dp(32));
        column.setBackgroundColor(getColor(R.color.widget_line));
        TextView title = new TextView(this);
        title.setText("取件随行");
        title.setTextSize(30);
        title.setTextColor(getColor(R.color.widget_ink));
        column.addView(title);
        TextView label = new TextView(this);
        label.setText("大字看码 · 轻点复制 · 一键取完\n以下均为示例数据");
        label.setTextSize(13);
        label.setTextColor(getColor(R.color.widget_muted));
        label.setPadding(0, dp(8), 0, dp(24));
        column.addView(label);
        View preview = PickupWidgetProvider.buildForDiag(this, null).apply(this, column);
        disableActions(preview);
        column.addView(preview, new LinearLayout.LayoutParams(-1, dp(WidgetPrefs.showAll(this) ? 244 : 180)));
        if (getIntent().getBooleanExtra("export_preview", false)) {
            preview.post(() -> {
                android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(
                        preview.getWidth(), preview.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
                preview.draw(new android.graphics.Canvas(bitmap));
                try (java.io.FileOutputStream output = openFileOutput("widget-preview.png", MODE_PRIVATE)) {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
                } catch (java.io.IOException e) { throw new IllegalStateException(e); }
                finally { bitmap.recycle(); }
            });
        }
        TextView status = new TextView(this);
        status.setPadding(0, dp(24), 0, dp(24));
        status.setTextColor(getColor(R.color.widget_muted));
        column.addView(status);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(column);
        setContentView(scroll);
        new Thread(() -> {
            int protocol = HyperIsland.protocol(this);
            boolean allowed = HyperIsland.hasPermission(this);
            android.util.Log.i("PICKUPDEBUG", "ISLAND protocol=" + protocol + " allowed=" + allowed);
            runOnUiThread(() -> status.setText("超级岛协议 " + protocol + " · 焦点通知权限 " + allowed));
        }).start();
        if (getIntent().getBooleanExtra("refresh_reminder", false)) {
            PickupRepository.get(this).pendingSnapshotAsync(12, snap -> {
                if (!snap.items.isEmpty()) Notifier.notifyCodes(this,
                        java.util.Collections.singletonList(snap.items.get(0).code), null);
            });
        }
    }

    private static void disableActions(View v) {
        v.setOnClickListener(null);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) disableActions(g.getChildAt(i));
        }
    }

    private int dp(int n) { return (int) (getResources().getDisplayMetrics().density * n + .5f); }
}
