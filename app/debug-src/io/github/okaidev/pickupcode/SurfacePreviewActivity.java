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
    private android.appwidget.AppWidgetHost liveHost;

    public static final class DismissReceiver extends android.content.BroadcastReceiver {
        @Override public void onReceive(android.content.Context context, android.content.Intent intent) {
            if (intent.getBooleanExtra("cleanup_widget_host", false)) {
                new android.appwidget.AppWidgetHost(context, 9142).deleteHost();
                return;
            }
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
        if (getIntent().getBooleanExtra("live_widget", false)) {
            showLiveWidget();
            return;
        }
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
        View preview = PickupWidgetProvider.applyPreview(this, PickupWidgetProvider.buildForDiag(this, null));
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

    /** Temporary host for checking the real provider/service without adding a home-screen card. */
    private void showLiveWidget() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(22), dp(48), dp(22), dp(32));
        column.setBackgroundColor(getColor(R.color.widget_line));
        TextView label = new TextView(this);
        label.setText("实际桌面组件 · 临时验收\n关闭本页会移除此测试实例");
        label.setTextSize(14);
        label.setTextColor(getColor(R.color.widget_ink));
        label.setPadding(0, 0, 0, dp(20));
        column.addView(label);
        liveHost = new android.appwidget.AppWidgetHost(this, 9142);
        liveHost.deleteHost();
        liveHost.startListening();
        int id = liveHost.allocateAppWidgetId();
        android.appwidget.AppWidgetManager manager = android.appwidget.AppWidgetManager.getInstance(this);
        android.os.Bundle options = new android.os.Bundle();
        options.putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 300);
        options.putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 300);
        options.putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 180);
        options.putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 180);
        boolean bound = manager.bindAppWidgetIdIfAllowed(id, new android.content.ComponentName(
                RuntimePaths.PKG, WidgetPresence.PROVIDER_CLASS), options);
        if (bound) {
            android.appwidget.AppWidgetHostView view = liveHost.createView(this, id, manager.getAppWidgetInfo(id));
            view.setPadding(0, 0, 0, 0);
            column.addView(view, new LinearLayout.LayoutParams(dp(300), dp(180)));
            android.content.Intent refresh = new android.content.Intent(android.appwidget.AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .setComponent(new android.content.ComponentName(RuntimePaths.PKG, WidgetPresence.PROVIDER_CLASS))
                    .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_IDS, new int[]{id});
            sendBroadcast(refresh);
        } else {
            label.append("\n需要先授予此探针临时 widget bind 权限。");
        }
        setContentView(column);
    }

    @Override protected void onDestroy() {
        if (liveHost != null) {
            liveHost.stopListening();
            liveHost.deleteHost();
        }
        super.onDestroy();
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

    private int dp(int n) { return (int) (getResources().getDisplayMetrics().density * n + .5f); }
}
