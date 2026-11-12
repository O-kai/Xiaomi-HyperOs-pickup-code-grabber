package io.github.okaidev.pickupcode;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 通知（平台 API，无 androidx 依赖）：
 * 写入待办后弹通知；点击 = 复制取件码到剪贴板 + 打开目标待办 App
 * （v2.7.0：小米笔记 / ColorOS 日历 / ColorOS 便签，由 NotesBackend 当前后端决定）。
 */
public class Notifier {

    private static final String TAG = "PICKUPDEBUG";
    private static final String CHANNEL_ID = "pickup";

    static final int PICKUP_ID = 1001;
    private static final AtomicLong REVISION = new AtomicLong();

    public static void notifyCodes(Context ctx, List<String> codes, String titleExtra) {
        if (ctx == null || codes == null || codes.isEmpty()) return;
        Context app = ctx.getApplicationContext();
        long revision = REVISION.incrementAndGet();
        List<String> fallback = new ArrayList<>(codes);
        if (!NotesBackend.localSelected(app)) {
            post(app, fallback, titleExtra, fallback.size(), true);
            return;
        }
        PickupRepository.get(app).pendingSnapshotAsync(12, snapshot -> {
            if (REVISION.get() != revision) return;
            postSnapshot(app, snapshot, true);
        });
    }

    /** Keep an existing reminder consistent after completion/deletion, without resurrecting a swipe. */
    static void refreshExisting(Context ctx) {
        try {
            NotificationManager manager = ctx.getSystemService(NotificationManager.class);
            boolean active = false;
            for (android.service.notification.StatusBarNotification n : manager.getActiveNotifications()) {
                if (n.getId() == PICKUP_ID) { active = true; break; }
            }
            if (!active) return;
            long revision = REVISION.incrementAndGet();
            if (!NotesBackend.localSelected(ctx)) {
                manager.cancel(PICKUP_ID);
                return;
            }
            PickupRepository.get(ctx).pendingSnapshotAsync(12, snapshot -> {
                if (REVISION.get() == revision) postSnapshot(ctx, snapshot, false);
            });
        } catch (Exception e) {
            Log.w(TAG, "notification refresh failed: " + e.getClass().getSimpleName());
        }
    }

    static void postSnapshot(Context ctx, PickupRepository.PendingSnapshot snapshot, boolean alert) {
        // The user may have dismissed the reminder while the asynchronous DB read was running.
        if (!alert && !hasActiveReminder(ctx)) return;
        List<String> codes = new ArrayList<>();
        for (PickupRecord item : snapshot.items) codes.add(item.code);
        if (codes.isEmpty()) {
            ctx.getSystemService(NotificationManager.class).cancel(PICKUP_ID);
            return;
        }
        PickupRecord first = snapshot.items.get(0);
        String place = first.place == null || first.place.isEmpty() ? first.source : first.place;
        post(ctx, codes, place, snapshot.pendingTotal, alert);
    }

    private static boolean hasActiveReminder(Context context) {
        for (android.service.notification.StatusBarNotification notification :
                context.getSystemService(NotificationManager.class).getActiveNotifications()) {
            if (notification.getId() == PICKUP_ID) return true;
        }
        return false;
    }

    static Notification build(Context ctx, List<String> codes, String place, int count, boolean alert) {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "取件码通知",
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("收到取件码时提醒，支持小米超级岛");
        ctx.getSystemService(NotificationManager.class).createNotificationChannel(channel);
        String first = codes.get(0);
        String summary = count + " 件待取";
        String text = place == null || place.isEmpty() ? summary : place + " · " + summary;
        PendingIntent copy = action(ctx, "copy", first, 10);
        PendingIntent done = action(ctx, "done", first, 11);
        Intent listIntent = new Intent(ctx, PickupListActivity.class);
        if (!NotesBackend.localSelected(ctx)) {
            Intent backend = ctx.getPackageManager().getLaunchIntentForPackage(NotesBackend.targetPkg(ctx));
            if (backend != null) listIntent = backend;
        }
        PendingIntent list = PendingIntent.getActivity(ctx, 12, listIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Action openAction = new Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(ctx, R.drawable.ic_pickup_parcel),
                "查看全部", list).build();
        Notification.Action doneAction = new Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(ctx, android.R.drawable.checkbox_on_background),
                "已取件", done).build();
        Notification publicVersion = new Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_pickup_parcel).setContentTitle(summary)
                .setContentText("解锁后查看取件码").setContentIntent(list).build();
        Notification.Builder builder = new Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_pickup_parcel).setColor(0xFF315CE8)
                .setContentTitle(first).setContentText(text).setContentIntent(copy)
                .setStyle(new Notification.BigTextStyle().bigText(text + "\n" + String.join("  ·  ", codes)))
                .setSubText("取件码助手").setCategory(Notification.CATEGORY_REMINDER)
                .setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(publicVersion)
                .setOnlyAlertOnce(!alert).setAutoCancel(false)
                .addAction(doneAction).addAction(openAction);
        if (HyperIsland.protocol(ctx) >= 2) {
            try {
                builder.addExtras(HyperIsland.extras(ctx, first,
                        place == null || place.isEmpty() ? "点击查看取件记录" : place,
                        count, openAction, doneAction, alert));
            } catch (org.json.JSONException e) {
                Log.w(TAG, "island parameters unavailable");
            }
        }
        return builder.build();
    }

    private static PendingIntent action(Context ctx, String key, String code, int request) {
        boolean done = "done".equals(key);
        Intent intent = new Intent(ctx, done ? NotificationDoneReceiver.class : NotificationActionActivity.class)
                .setData(Uri.parse("pickupcode://notification/" + key + "/" + Uri.encode(code)))
                .putExtra(key, code);
        if (done) return PendingIntent.getBroadcast(ctx, request, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return PendingIntent.getActivity(ctx, request, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void post(Context ctx, List<String> codes, String place, int count, boolean alert) {
        try {
            ctx.getSystemService(NotificationManager.class).notify(PICKUP_ID,
                    build(ctx, codes, place, count, alert));
            Log.i(TAG, "NOTIFY pending=" + count + " alert=" + alert);
        } catch (Exception e) {
            Log.e(TAG, "notify failed: " + e.getClass().getSimpleName());
        }
    }

    /**
     * v3.1.0：写入失败通知。
     *
     * 此前「提取到取件码但没写进待办」是完全静默的，用户只看到待办里什么都没有，
     * 分不清是没抓到还是写失败，也无从下手。常见原因：目标待办表不存在（用户从没建过）、
     * root 未授权、sqlite3 未部署——这些都需要用户自己动手处理，给出提示才有意义。
     *
     * 用独立 notification id，避免覆盖正常取件通知；点击直达主界面（可展开体检卡）。
     */
    public static void notifyWriteFailed(Context ctx, String reason) {
        if (ctx == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel("pickup_error", "写入失败提示",
                        NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("取件码已识别但未能写入待办时提醒");
                ctx.getSystemService(NotificationManager.class).createNotificationChannel(ch);
            }
            String text = reason != null && !reason.trim().isEmpty() ? reason.trim() : "原因未知";
            if (text.length() > 160) text = text.substring(0, 160) + "…";

            Intent open = new Intent(ctx, LauncherActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(ctx, 500, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                    ? new Notification.Builder(ctx, "pickup_error")
                    : new Notification.Builder(ctx);
            b.setSmallIcon(ctx.getApplicationInfo().icon);
            b.setContentTitle("取件码已识别，但未能写入待办");
            b.setContentText(text);
            b.setStyle(new Notification.BigTextStyle().bigText(
                    text + "\n\n可打开模块点「排查问题向导」按向导处理，"
                            + "或点上方体检卡查看详情。"));
            b.setContentIntent(pi);
            b.setAutoCancel(true);
            b.setOngoing(false);

            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(1002, b.build());
            Log.w(TAG, "NOTIFY-FAIL: " + text);
        } catch (Throwable t) {
            Log.e(TAG, "notifyWriteFailed err: " + t);
        }
    }
}
