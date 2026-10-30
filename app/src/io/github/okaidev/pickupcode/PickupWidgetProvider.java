package io.github.okaidev.pickupcode;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;

/**
 * 4.0 桌面小组件。
 *
 * 设计要点（2026-10-30 参考社区改版后收敛）：
 *  - 不做滚动列表：RemoteViewsService + ListView 在部分桌面/系统版本上刷新不可靠，
 *    固定行数 + 事件驱动更稳；行数按组件高度自适应（1–5 行）。
 *  - 刷新分两段：立即刷一次 + 600ms 后补刷一次，绕开系统下发 options/数据不同步的时序问题。
 *  - 数据只来自本地取件库，不读短信库、不存正文。
 */
public class PickupWidgetProvider extends AppWidgetProvider {

    static final String ACTION_COPY = "io.github.okaidev.pickupcode.widget.COPY";
    static final String ACTION_DONE = "io.github.okaidev.pickupcode.widget.DONE";
    static final String EXTRA_ID = "record_id";

    private static final String TAG = "PICKUPDEBUG";
    private static final long SECOND_PASS_DELAY = 600L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile Context appContext;

    private static final Runnable SECOND_PASS = () -> {
        Context ctx = appContext;
        if (ctx != null) {
            doRefresh(ctx);
        }
    };

    private static final int[] ROW_IDS = {
            R.id.widget_row_1, R.id.widget_row_2, R.id.widget_row_3,
            R.id.widget_row_4, R.id.widget_row_5
    };
    private static final int[] CODE_IDS = {
            R.id.widget_code_1, R.id.widget_code_2, R.id.widget_code_3,
            R.id.widget_code_4, R.id.widget_code_5
    };
    private static final int[] PLACE_IDS = {
            R.id.widget_place_1, R.id.widget_place_2, R.id.widget_place_3,
            R.id.widget_place_4, R.id.widget_place_5
    };
    private static final int[] DONE_IDS = {
            R.id.widget_done_1, R.id.widget_done_2, R.id.widget_done_3,
            R.id.widget_done_4, R.id.widget_done_5
    };

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        refreshAsync(context);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager appWidgetManager,
                                          int appWidgetId, Bundle newOptions) {
        // 尺寸变化后按新高度重算行数
        refreshAsync(context);
    }

    public static void refreshAsync(Context context) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        appContext = app;
        doRefresh(app);
        // 二次补刷：系统在添加/改尺寸后可能先渲染旧 RemoteViews，再补一次保证最终一致
        MAIN.removeCallbacks(SECOND_PASS);
        MAIN.postDelayed(SECOND_PASS, SECOND_PASS_DELAY);
    }

    private static void doRefresh(final Context app) {
        try {
            final int maxRows = WidgetPrefs.maxRows(app);
            PickupRepository.get(app).pendingSnapshotAsync(maxRows, snap -> {
                AppWidgetManager manager = AppWidgetManager.getInstance(app);
                ComponentName component = new ComponentName(app, PickupWidgetProvider.class);
                int[] ids;
                try {
                    ids = manager.getAppWidgetIds(component);
                } catch (Throwable t) {
                    Log.w(TAG, "widget ids failed: " + t);
                    return;
                }
                for (int id : ids) {
                    try {
                        manager.updateAppWidget(id, build(app, id, snap));
                    } catch (Throwable t) {
                        Log.w(TAG, "widget update failed id=" + id + ": " + t);
                    }
                }
                Log.i(TAG, "WIDGET refresh: n=" + ids.length
                        + " pending=" + (snap == null ? 0 : snap.total));
            });
        } catch (Throwable t) {
            Log.w(TAG, "widget refresh failed: " + t);
        }
    }

    private static RemoteViews build(Context context, int widgetId,
                                     PickupRepository.PendingSnapshot snap) {
        RemoteViews rv = new RemoteViews(context.getPackageName(), R.layout.widget_pickup);

        int rows = rowsFor(context, widgetId);
        boolean showPlace = WidgetPrefs.showPlace(context);
        float scale = WidgetPrefs.fontScale(context);
        int total = snap == null ? 0 : snap.total;
        java.util.List<PickupRecord> items = snap == null ? null : snap.items;

        rv.setTextViewText(R.id.widget_count, total + " 个");
        rv.setTextViewTextSize(R.id.widget_title, TypedValue.COMPLEX_UNIT_SP, 16f);
        rv.setTextViewTextSize(R.id.widget_count, TypedValue.COMPLEX_UNIT_SP, 12f);

        boolean empty = total == 0;
        rv.setViewVisibility(R.id.widget_empty, empty ? View.VISIBLE : View.GONE);
        rv.setTextViewTextSize(R.id.widget_empty, TypedValue.COMPLEX_UNIT_SP, 12f * scale);

        int shown = 0;
        for (int i = 0; i < ROW_IDS.length; i++) {
            boolean has = !empty && i < rows && items != null && i < items.size();
            if (!has) {
                rv.setViewVisibility(ROW_IDS[i], View.GONE);
                continue;
            }
            PickupRecord record = items.get(i);
            shown++;
            rv.setViewVisibility(ROW_IDS[i], View.VISIBLE);
            rv.setTextViewText(CODE_IDS[i], record.code);
            rv.setTextViewTextSize(CODE_IDS[i], TypedValue.COMPLEX_UNIT_SP, 17f * scale);

            // 关闭地点时不能 GONE：这一列是带 weight 的占位视图，
            // 一旦隐藏，右侧 ✓ 会贴到取件码旁边、右边留一大块空白。
            rv.setViewVisibility(PLACE_IDS[i], View.VISIBLE);
            rv.setTextViewText(PLACE_IDS[i], showPlace
                    ? (record.place == null || record.place.trim().isEmpty() ? "—" : record.place)
                    : "");
            rv.setTextViewTextSize(PLACE_IDS[i], TypedValue.COMPLEX_UNIT_SP, 11f * scale);

            PendingIntent copy = action(context, record.id, ACTION_COPY, 1);
            PendingIntent done = action(context, record.id, ACTION_DONE, 2);
            rv.setOnClickPendingIntent(CODE_IDS[i], copy);
            rv.setOnClickPendingIntent(PLACE_IDS[i], copy);
            rv.setOnClickPendingIntent(DONE_IDS[i], done);
        }

        // 还有更多没显示时才提示「查看全部」
        boolean more = !empty && total > shown;
        rv.setViewVisibility(R.id.widget_more, more ? View.VISIBLE : View.GONE);
        rv.setTextViewTextSize(R.id.widget_more, TypedValue.COMPLEX_UNIT_SP, 11f * scale);

        PendingIntent open = openApp(context);
        rv.setOnClickPendingIntent(R.id.widget_more, open);
        rv.setOnClickPendingIntent(R.id.widget_title, open);
        rv.setOnClickPendingIntent(R.id.widget_count, open);
        rv.setOnClickPendingIntent(R.id.widget_empty, open);
        return rv;
    }

    /**
     * 按组件高度推算能放几行：头部约 30dp，每行随字号约 34–48dp。
     * 拿不到 options 时退回 3 行，保证默认观感。
     */
    private static int rowsFor(Context context, int widgetId) {
        int maxPref = WidgetPrefs.maxRows(context);
        int heightDp = 0;
        int widthDp = 0;
        try {
            Bundle options = AppWidgetManager.getInstance(context).getAppWidgetOptions(widgetId);
            if (options != null) {
                heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0);
                widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
            }
        } catch (Throwable t) {
            Log.w(TAG, "widget options failed: " + t);
        }
        if (heightDp <= 0) {
            return Math.min(3, maxPref);
        }
        int rowHeight = (int) (38 * WidgetPrefs.fontScale(context)) + 6;
        int rows = (heightDp - 30) / Math.max(1, rowHeight);
        if (widthDp > 0 && widthDp < 150) {
            rows = Math.min(rows, 2);
        }
        return Math.max(1, Math.min(maxPref, rows));
    }

    private static PendingIntent openApp(Context context) {
        Intent open = new Intent(context, PickupListActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(context, 9001, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
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
