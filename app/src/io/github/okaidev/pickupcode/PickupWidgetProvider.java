package io.github.okaidev.pickupcode;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.RemoteViews;

import java.util.List;

/**
 * 4.0 桌面小组件：固定 3 行，不做滚动列表。
 * 事件驱动刷新（updatePeriodMillis=0），数据只从本地取件库读取。
 */
public class PickupWidgetProvider extends AppWidgetProvider {

    static final String ACTION_COPY = "io.github.okaidev.pickupcode.widget.COPY";
    static final String ACTION_DONE = "io.github.okaidev.pickupcode.widget.DONE";
    static final String EXTRA_ID = "record_id";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        refreshAsync(context);
    }

    public static void refreshAsync(Context context) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        PickupRepository.get(app).pendingAsync(3, list -> {
            AppWidgetManager manager = AppWidgetManager.getInstance(app);
            ComponentName component = new ComponentName(app, PickupWidgetProvider.class);
            int[] ids = manager.getAppWidgetIds(component);
            for (int id : ids) {
                manager.updateAppWidget(id, build(app, list));
            }
        });
    }

    private static RemoteViews build(Context context, List<PickupRecord> records) {
        RemoteViews rv = new RemoteViews(context.getPackageName(), R.layout.widget_pickup);
        int count = records == null ? 0 : records.size();
        rv.setTextViewText(R.id.widget_count, count + " 个");
        rv.setViewVisibility(R.id.widget_empty, count == 0 ? android.view.View.VISIBLE : android.view.View.GONE);
        rv.setViewVisibility(R.id.widget_more, count == 0 ? android.view.View.GONE : android.view.View.VISIBLE);

        int[] rows = {R.id.widget_row_1, R.id.widget_row_2, R.id.widget_row_3};
        int[] codes = {R.id.widget_code_1, R.id.widget_code_2, R.id.widget_code_3};
        int[] places = {R.id.widget_place_1, R.id.widget_place_2, R.id.widget_place_3};
        int[] dones = {R.id.widget_done_1, R.id.widget_done_2, R.id.widget_done_3};

        for (int i = 0; i < rows.length; i++) {
            if (records != null && i < records.size()) {
                PickupRecord record = records.get(i);
                rv.setViewVisibility(rows[i], android.view.View.VISIBLE);
                rv.setTextViewText(codes[i], record.code);
                rv.setTextViewText(places[i], record.place == null ? "—" : record.place);

                PendingIntent copy = action(context, record.id, ACTION_COPY, 1);
                PendingIntent done = action(context, record.id, ACTION_DONE, 2);
                rv.setOnClickPendingIntent(codes[i], copy);
                rv.setOnClickPendingIntent(places[i], copy);
                rv.setOnClickPendingIntent(dones[i], done);
            } else {
                rv.setViewVisibility(rows[i], android.view.View.GONE);
            }
        }

        Intent open = new Intent(context, PickupListActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent more = PendingIntent.getActivity(context, 9001, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        rv.setOnClickPendingIntent(R.id.widget_more, more);
        rv.setOnClickPendingIntent(R.id.widget_title, more);
        return rv;
    }

    private static PendingIntent action(Context context, long recordId, String action, int kind) {
        Intent intent = new Intent(context, WidgetActionActivity.class);
        intent.setAction(action);
        intent.putExtra(EXTRA_ID, recordId);
        intent.setData(Uri.parse("pickup://widget/" + recordId + "/" + kind));
        int requestCode = (int) (recordId % 100000L) * 10 + kind;
        return PendingIntent.getActivity(context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
