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
    private static final int ISLAND_SAMPLE_ID = 1009;

    public static final class DismissReceiver extends android.content.BroadcastReceiver {
        @Override public void onReceive(android.content.Context context, android.content.Intent intent) {
            context.getSystemService(android.app.NotificationManager.class).cancel(ISLAND_SAMPLE_ID);
        }
    }

    @Override protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        recreate();
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (getIntent().getBooleanExtra("dismiss_island_sample", false)) {
            getSystemService(android.app.NotificationManager.class).cancel(ISLAND_SAMPLE_ID);
        }
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
        if (getIntent().getBooleanExtra("show_island_sample", false)) postIslandSample();
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

    /** Exercise the actual notification template without inserting or completing any records. */
    private void postIslandSample() {
        android.content.Intent preview = new android.content.Intent(this, SurfacePreviewActivity.class)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
        android.content.Intent dismiss = new android.content.Intent(this, DismissReceiver.class);
        int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE;
        android.app.PendingIntent open = android.app.PendingIntent.getActivity(this, 9109, preview, flags);
        android.app.PendingIntent close = android.app.PendingIntent.getBroadcast(this, 9110, dismiss, flags);
        android.graphics.drawable.Icon icon = android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_pickup_parcel);
        android.app.Notification.Action openAction = new android.app.Notification.Action.Builder(icon, "查看示例", open).build();
        android.app.Notification.Action closeAction = new android.app.Notification.Action.Builder(icon, "结束验证", close).build();
        android.app.Notification sample = Notifier.build(this,
                java.util.Arrays.asList("12-3-4567", "58-2-9041"), "示例驿站 · 不写入记录", 2, true);
        android.app.Notification.Builder builder = android.app.Notification.Builder.recoverBuilder(this, sample)
                .setContentIntent(open).setActions(closeAction, openAction);
        if (HyperIsland.protocol(this) >= 2) {
            try {
                builder.addExtras(HyperIsland.extras(this, "12-3-4567", "示例驿站 · 不写入记录",
                        2, openAction, closeAction, true));
            } catch (org.json.JSONException e) { throw new IllegalStateException(e); }
        }
        getSystemService(android.app.NotificationManager.class).notify(ISLAND_SAMPLE_ID, builder.build());
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
