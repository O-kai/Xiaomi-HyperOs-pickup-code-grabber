package io.github.okaidev.pickupcode;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;

import java.util.ArrayList;
import java.util.List;

/** Native parcel cards shared by Android launchers and Xiaomi App Vault. */
public class PickupWidgetProvider extends AppWidgetProvider {
    static final String ACTION_COPY = "io.github.okaidev.pickupcode.widget.COPY";
    static final String ACTION_DONE = "io.github.okaidev.pickupcode.widget.DONE";
    static final String EXTRA_ID = "record_id";
    static final int MAX_ROWS = 12;
    private static final String TAG = "PICKUPDEBUG";
    private static final int[] ROW_IDS = {
            R.id.widget_row_1, R.id.widget_row_2, R.id.widget_row_3,
            R.id.widget_row_4, R.id.widget_row_5, R.id.widget_row_6,
            R.id.widget_row_7, R.id.widget_row_8, R.id.widget_row_9,
            R.id.widget_row_10, R.id.widget_row_11, R.id.widget_row_12
    };
    private static final int[] CODE_IDS = {
            R.id.widget_code_1, R.id.widget_code_2, R.id.widget_code_3,
            R.id.widget_code_4, R.id.widget_code_5, R.id.widget_code_6,
            R.id.widget_code_7, R.id.widget_code_8, R.id.widget_code_9,
            R.id.widget_code_10, R.id.widget_code_11, R.id.widget_code_12
    };
    private static final int[] PLACE_IDS = {
            R.id.widget_place_1, R.id.widget_place_2, R.id.widget_place_3,
            R.id.widget_place_4, R.id.widget_place_5, R.id.widget_place_6,
            R.id.widget_place_7, R.id.widget_place_8, R.id.widget_place_9,
            R.id.widget_place_10, R.id.widget_place_11, R.id.widget_place_12
    };
    private static final int[] DONE_IDS = {
            R.id.widget_done_1, R.id.widget_done_2, R.id.widget_done_3,
            R.id.widget_done_4, R.id.widget_done_5, R.id.widget_done_6,
            R.id.widget_done_7, R.id.widget_done_8, R.id.widget_done_9,
            R.id.widget_done_10, R.id.widget_done_11, R.id.widget_done_12
    };


    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (AppWidgetManager.ACTION_APPWIDGET_UPDATE.equals(action)
                || AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED.equals(action)
                || AppWidgetManager.ACTION_APPWIDGET_RESTORED.equals(action)
                || "miui.appwidget.action.APPWIDGET_UPDATE".equals(action)) {
            Bundle options = intent.getBundleExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS);
            if (options != null && options.getBoolean("miuiIdChanged")
                    && !options.getBoolean("miuiIdChangedComplete")) {
                Bundle complete = new Bundle(options);
                complete.putBoolean("miuiIdChangedComplete", true);
                AppWidgetManager.getInstance(context).updateAppWidgetOptions(
                        intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1), complete);
            }
            PendingResult pending = goAsync();
            requestRefresh(context, () -> { if (pending != null) pending.finish(); });
        } else {
            super.onReceive(context, intent);
        }
    }

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        refreshAsync(context);
    }

    @Override public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager,
                                                    int id, Bundle options) {
        refreshAsync(context);
    }

    @Override public void onRestored(Context context, int[] oldIds, int[] newIds) {
        refreshAsync(context);
    }

    public static void refreshAsync(Context context) { requestRefresh(context, () -> { }); }

    // Compatibility with completion callers. Rows compact immediately, without unsupported
    // RemoteViews reflection methods such as View.setAlpha/setTranslationY on older hosts.
    static void refresh(Context context, boolean animate) { refreshAsync(context); }

    private static void requestRefresh(Context context, Runnable finished) {
        if (context == null) { finished.run(); return; }
        Context app = context.getApplicationContext();
        try {
            int[] ids = WidgetPresence.ids(app);
            if (ids.length == 0) { finished.run(); return; }
            PickupRepository.get(app).pendingSnapshotAsync(Integer.MAX_VALUE, WidgetPrefs.showAll(app), snapshot -> {
                try {
                    AppWidgetManager manager = AppWidgetManager.getInstance(app);
                    for (int id : ids) {
                        try {
                            Bundle options = manager.getAppWidgetOptions(id);
                            manager.updateAppWidget(id, build(app, snapshot,
                                    options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH),
                                    options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT), false, id));
                            manager.notifyAppWidgetViewDataChanged(id, R.id.widget_list);
                        } catch (Exception e) {
                            Log.w(TAG, "widget host update failed: " + e.getClass().getSimpleName());
                        }
                    }
                } finally { finished.run(); }
            });
        } catch (Exception e) {
            Log.w(TAG, "widget refresh failed: " + e.getClass().getSimpleName());
            finished.run();
        }
    }

    /** Synchronous final render before disabling either widget provider. */
    static void showClosedState(Context context) {
        try {
            for (int id : WidgetPresence.ids(context)) {
                AppWidgetManager.getInstance(context).updateAppWidget(id, closed(context));
            }
        } catch (Exception e) { Log.w(TAG, "widget closed render failed"); }
    }

    private static RemoteViews closed(Context context) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_pickup_list);
        views.setTextViewText(R.id.widget_title, "取件记录已关闭");
        views.setViewVisibility(R.id.widget_count, View.GONE);
        views.setViewVisibility(R.id.widget_list, View.GONE);
        views.setViewVisibility(R.id.widget_empty, View.VISIBLE);
        views.setTextViewText(R.id.widget_empty, "点这里重新打开软件内部记录");
        views.setOnClickPendingIntent(android.R.id.background, openApp(context));
        return views;
    }

    static RemoteViews buildForDiag(Context context, PickupRepository.PendingSnapshot snapshot) {
        return build(context, snapshot == null ? sampleSnapshot(WidgetPrefs.showAll(context)) : snapshot,
                300, 244, true);
    }

    static View applyPreview(Context context, RemoteViews views) {
        // Collection adapter actions require an AppWidgetHostView, even for a local preview.
        android.appwidget.AppWidgetHostView host = new android.appwidget.AppWidgetHostView(context);
        host.setPadding(0, 0, 0, 0);
        host.addView(views.apply(context, host), new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.Gravity.TOP | android.view.Gravity.START));
        return host;
    }

    /** A size-specific preview also exercises the exact host rendering path in local tests. */
    static RemoteViews build(Context context, PickupRepository.PendingSnapshot snapshot,
                             int widthDp, int heightDp, boolean preview) {
        return build(context, snapshot, widthDp, heightDp, preview, 0);
    }

    private static RemoteViews build(Context context, PickupRepository.PendingSnapshot snapshot,
                                     int widthDp, int heightDp, boolean preview, int widgetId) {
        if (!preview && !NotesBackend.localSelected(context)) return closed(context);
        // Live widgets scroll on every supported Android version. Android 12+ can also
        // render a sample collection in-process without binding a real widget service.
        boolean collection = !preview || android.os.Build.VERSION.SDK_INT >= 31;
        RemoteViews views = new RemoteViews(context.getPackageName(), collection
                ? R.layout.widget_pickup_list : R.layout.widget_pickup_v5);
        PendingIntent open = openApp(context);
        if (!preview) views.setOnClickPendingIntent(android.R.id.background, open);
        List<PickupRecord> items = snapshot == null ? java.util.Collections.emptyList() : snapshot.items;
        int pending = snapshot == null ? 0 : snapshot.pendingTotal;
        boolean all = snapshot != null && snapshot.showAll;
        views.setTextViewText(R.id.widget_title, "取件码");
        views.setViewVisibility(R.id.widget_count, View.VISIBLE);
        views.setTextViewText(R.id.widget_count, all
                ? pending + " 待取 · " + Math.max(0, snapshot.total - pending) + " 已取"
                : pending + " 件待取");
        views.setViewVisibility(R.id.widget_open_icon,
                widthDp > 0 && widthDp < 260 ? View.GONE : View.VISIBLE);
        boolean showCode = WidgetPrefs.showCode(context);
        boolean empty = items.isEmpty();
        views.setViewVisibility(R.id.widget_empty, !showCode || empty ? View.VISIBLE : View.GONE);
        views.setViewVisibility(R.id.widget_list, !showCode || empty ? View.GONE : View.VISIBLE);
        if (!showCode) {
            views.setTextViewText(R.id.widget_title, "取件记录");
            views.setViewVisibility(R.id.widget_count, View.GONE);
            views.setTextViewText(R.id.widget_empty, all
                    ? pending + " 个待取 · " + Math.max(0, snapshot.total - pending) + " 个已取"
                    : pending + " 个待取");
        }
        if (collection) {
            // Start with complete rows rather than a clipped code at the bottom edge.
            RowStyle style = new RowStyle(context, heightDp);
            int available = heightDp - WidgetPrefs.PAD_DP - WidgetPrefs.HEADER_DP;
            int remainder = available >= style.rowDp ? available % style.rowDp : 0;
            views.setViewPadding(R.id.widget_list, 0, 0, 0, dp(context, remainder));
            if (preview) {
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    RemoteViews.RemoteCollectionItems.Builder adapter =
                            new RemoteViews.RemoteCollectionItems.Builder()
                                    .setHasStableIds(true).setViewTypeCount(1);
                    if (showCode) for (PickupRecord record : items) {
                        adapter.addItem(record.id, collectionRow(context, record, widthDp, heightDp, true));
                    }
                    views.setRemoteAdapter(R.id.widget_list, adapter.build());
                }
            } else {
                Intent adapter = new Intent(context, WidgetCollectionService.class)
                        .setData(Uri.parse("pickup://widget/list/" + widgetId + "/" + widthDp + "/" + heightDp))
                        .putExtra(WidgetCollectionService.WIDTH, widthDp)
                        .putExtra(WidgetCollectionService.HEIGHT, heightDp);
                views.setRemoteAdapter(R.id.widget_list, adapter);
                views.setPendingIntentTemplate(R.id.widget_list, collectionActions(context, widgetId));
            }
            return views;
        }
        // Static sample fallback for Android 9–11; it never controls the live list size.
        RowStyle style = new RowStyle(context, heightDp);
        int rows = WidgetPrefs.rowsForHeight(heightDp, widthDp, MAX_ROWS, style.rowDp);
        for (int i = 0; i < MAX_ROWS; i++) {
            boolean visible = showCode && i < rows && i < items.size();
            views.setViewVisibility(ROW_IDS[i], visible ? View.VISIBLE : View.GONE);
            if (visible) bindRecord(context, views, items.get(i), i, widthDp, style);
        }
        return views;
    }

    private static final class RowStyle {
        final int rowDp;
        final float scale;
        final float lineScale;

        RowStyle(Context context, int heightDp) {
            float systemFont = Math.max(1f, context.getResources().getConfiguration().fontScale);
            int desired = (int) Math.ceil(WidgetPrefs.ROW_DP
                    * Math.max(1f, WidgetPrefs.fontScale(context) * systemFont));
            rowDp = heightDp > 0 ? Math.min(desired,
                    Math.max(WidgetPrefs.ROW_DP, heightDp - WidgetPrefs.PAD_DP - WidgetPrefs.HEADER_DP)) : desired;
            scale = Math.min(WidgetPrefs.fontScale(context), rowDp / (float) WidgetPrefs.ROW_DP / systemFont);
            lineScale = Math.max(1f, scale * systemFont);
        }
    }

    private static void bindRecord(Context context, RemoteViews views, PickupRecord record,
                                    int index, int widthDp, RowStyle style) {
        boolean pending = record.isPending();
        int code = CODE_IDS[index], place = PLACE_IDS[index], done = DONE_IDS[index];
        views.setInt(ROW_IDS[index], "setMinimumHeight", dp(context, style.rowDp));
        views.setTextViewText(code, pending ? record.code
                : Html.fromHtml("<s>" + android.text.TextUtils.htmlEncode(record.code) + "</s>", 0));
        views.setBoolean(code, "setEnabled", pending);
        views.setContentDescription(code, "取件码 " + record.code + (pending ? "，待取件" : "，已取件"));
        views.setTextViewTextSize(code, TypedValue.COMPLEX_UNIT_SP,
                fittingCodeSize(context, record.code, widthDp, 25f * style.scale));
        views.setInt(code, "setHeight", dp(context, 30f * style.lineScale));
        views.setTextViewText(place, place(record));
        views.setTextViewTextSize(place, TypedValue.COMPLEX_UNIT_SP, 11f * style.scale);
        views.setInt(place, "setHeight", dp(context, 18f * style.lineScale));
        views.setViewVisibility(place, WidgetPrefs.showPlace(context) ? View.VISIBLE : View.GONE);
        views.setViewVisibility(done, pending ? View.VISIBLE : View.INVISIBLE);
    }

    static RemoteViews collectionRow(Context context, PickupRecord record,
                                     int widthDp, int heightDp, boolean preview) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_pickup_item);
        bindRecord(context, views, record, 0, widthDp, new RowStyle(context, heightDp));
        if (!preview) {
            views.setOnClickFillInIntent(R.id.widget_code_1, fillIn(ACTION_COPY, record.id));
            views.setOnClickFillInIntent(R.id.widget_place_1, fillIn(ACTION_COPY, record.id));
            if (record.isPending()) views.setOnClickFillInIntent(R.id.widget_done_1, fillIn(ACTION_DONE, record.id));
        }
        return views;
    }

    private static Intent fillIn(String action, long id) {
        return new Intent().setAction(action).putExtra(EXTRA_ID, id);
    }

    static PendingIntent collectionActions(Context context, int widgetId) {
        // Collection clicks need fill-in extras. The mutable template is explicitly
        // bound to our non-exported receiver, which validates both action and record id.
        int mutable = android.os.Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        return PendingIntent.getBroadcast(context, 0,
                new Intent(context, WidgetActionReceiver.class)
                        .setData(Uri.parse("pickup://widget/actions/" + widgetId)),
                PendingIntent.FLAG_UPDATE_CURRENT | mutable);
    }

    private static String place(PickupRecord record) {
        if (record.place != null && !record.place.trim().isEmpty() && !"—".equals(record.place.trim())) {
            String source = record.source == null ? "" : record.source.trim();
            if (!source.isEmpty() && !record.place.contains(source)) return source + " · " + record.place;
            return record.place;
        }
        return record.source == null || record.source.isEmpty() ? "待取包裹" : record.source;
    }

    private static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static float fittingCodeSize(Context context, String code, int widthDp, float desiredSp) {
        android.util.DisplayMetrics display = context.getResources().getDisplayMetrics();
        float available = Math.max(24, (widthDp > 0 ? widthDp : 300) - 94) * display.density;
        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setTypeface(android.graphics.Typeface.create("sans-serif", 0));
        paint.setTextSize(desiredSp * display.scaledDensity);
        float measured = paint.measureText(code == null ? "" : code);
        return measured <= available ? desiredSp : desiredSp * available / measured;
    }

    private static PickupRepository.PendingSnapshot sampleSnapshot(boolean all) {
        List<PickupRecord> items = new ArrayList<>();
        items.add(sample(1, "12-3-4567", "菜鸟", "江南绿城西门", true));
        items.add(sample(2, "58-2-9041", "拼多多", "望江路驿站", true));
        if (all) items.add(sample(3, "09-8-7654", "中通快递", "锦绣路 18 号", false));
        return new PickupRepository.PendingSnapshot(items, items.size(), 2, all);
    }

    private static PickupRecord sample(long id, String code, String source, String place, boolean pending) {
        PickupRecord record = new PickupRecord();
        record.id = id; record.code = code; record.source = source; record.place = place;
        record.status = pending ? PickupRecord.STATUS_PENDING : PickupRecord.STATUS_PICKED_UP;
        return record;
    }

    private static PendingIntent openApp(Context context) {
        return PendingIntent.getActivity(context, 9001,
                new Intent(context, PickupListActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Background widget actions never bring an Activity to the foreground. */
    public static class WidgetActionReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            long id = intent.getLongExtra(EXTRA_ID, -1);
            if (id <= 0 || (!ACTION_COPY.equals(action) && !ACTION_DONE.equals(action))) return;
            PendingResult pending = goAsync();
            Context app = context.getApplicationContext();
            PickupRepository.get(app).getByIdAsync(id, record -> {
                if (record == null) { pending.finish(); return; }
                if (ACTION_DONE.equals(action) && record.isPending()) {
                    PickupActions.markDone(app, record, (changed, backend) -> pending.finish(), true);
                    return;
                }
                try {
                    if (ACTION_COPY.equals(action)) {
                        android.content.ClipboardManager clipboard = app.getSystemService(android.content.ClipboardManager.class);
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("取件码", record.code));
                        android.widget.Toast.makeText(app, "已复制取件码", android.widget.Toast.LENGTH_SHORT).show();
                    }
                } finally { pending.finish(); }
            });
        }
    }
}
