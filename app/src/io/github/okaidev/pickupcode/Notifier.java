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
    static final int REAR_ID = 1003;
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
        PickupRepository.get(app).pendingSnapshotAsync(Integer.MAX_VALUE, snapshot -> {
            if (REVISION.get() != revision) return;
            postSnapshot(app, snapshot, true);
        });
    }

    /** Keep an existing reminder consistent after completion/deletion, without resurrecting a swipe. */
    static void refreshExisting(Context ctx) {
        refreshExisting(ctx, () -> { });
    }

    static void refreshExisting(Context ctx, Runnable finished) {
        try {
            NotificationManager manager = ctx.getSystemService(NotificationManager.class);
            boolean active = false;
            for (android.service.notification.StatusBarNotification n : manager.getActiveNotifications()) {
                if (n.getId() == PICKUP_ID) { active = true; break; }
            }
            long revision = REVISION.incrementAndGet();
            if (!NotesBackend.localSelected(ctx)) {
                manager.cancel(PICKUP_ID);
                manager.cancel(REAR_ID);
                finished.run();
                return;
            }
            final boolean refreshMain = active;
            PickupRepository.get(ctx).pendingSnapshotAsync(Integer.MAX_VALUE, snapshot -> {
                try {
                    if (REVISION.get() != revision) return;
                    postRearSnapshot(ctx, snapshot);
                    if (refreshMain) postMainSnapshot(ctx, snapshot, false);
                } finally { finished.run(); }
            });
        } catch (Exception e) {
            Log.w(TAG, "notification refresh failed: " + e.getClass().getSimpleName());
            finished.run();
        }
    }

    static void postSnapshot(Context ctx, PickupRepository.PendingSnapshot snapshot, boolean alert) {
        postRearSnapshot(ctx, snapshot);
        // The user may have dismissed the reminder while the asynchronous DB read was running.
        if (!alert && !hasActiveReminder(ctx)) return;
        postMainSnapshot(ctx, snapshot, alert);
    }

    /** Explicit selection may restore a dismissed notification. Rear screen always uses all pending. */
    static void pushSelected(Context context) {
        Context app = context.getApplicationContext();
        long revision = REVISION.incrementAndGet();
        PickupRepository.get(app).pendingSnapshotAsync(Integer.MAX_VALUE, snapshot -> {
            if (REVISION.get() == revision) postSnapshot(app, snapshot, true);
        });
    }

    private static void postMainSnapshot(Context ctx, PickupRepository.PendingSnapshot snapshot, boolean alert) {
        List<PickupRecord> selected = NotificationSelection.filter(ctx, snapshot.items);
        List<String> codes = new ArrayList<>();
        for (PickupRecord item : selected) if (codes.size() < 12) codes.add(item.code);
        if (codes.isEmpty()) {
            ctx.getSystemService(NotificationManager.class).cancel(PICKUP_ID);
            return;
        }
        PickupRecord first = selected.get(0);
        String place = first.place == null || first.place.isEmpty() ? first.source : first.place;
        post(ctx, codes, place, selected.size(), alert);
    }

    private static void postRearSnapshot(Context ctx, PickupRepository.PendingSnapshot snapshot) {
        NotificationManager manager = ctx.getSystemService(NotificationManager.class);
        if (!RearScreen.available(ctx) || snapshot.pendingTotal == 0 || snapshot.items.isEmpty()) {
            manager.cancel(REAR_ID);
            return;
        }
        PickupRecord first = snapshot.items.get(0);
        try {
            boolean initial = true;
            for (android.service.notification.StatusBarNotification value : manager.getActiveNotifications())
                if (value.getId() == REAR_ID) initial = false;
            manager.notify(REAR_ID, buildRear(ctx, first.code, first.place, snapshot.pendingTotal, initial));
        } catch (Exception e) { Log.w(TAG, "rear notification failed: " + e.getClass().getSimpleName()); }
    }

    static Notification buildRear(Context ctx, String code, String place, int count) throws org.json.JSONException {
        return buildRear(ctx, code, place, count, false);
    }

    private static Notification buildRear(Context ctx, String code, String place, int count, boolean initial) throws org.json.JSONException {
        NotificationManager manager = ctx.getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel("pickup_rear", "背屏待取件",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("随待取件更新，取完后自动消失");
        channel.setSound(null, null);
        manager.createNotificationChannel(channel);
        Notification base = build(ctx, java.util.Collections.singletonList(code), place, count, false);
        base.extras.remove(HyperIsland.PARAM);
        base.extras.remove("miui.focus.pics");
        base.extras.remove("miui.focus.actions");
        // recoverBuilder retains the source extras, which take precedence over addExtras.
        base.extras.remove(RearScreen.PARAM);
        base.extras.remove(RearScreen.VIEW);
        base.extras.remove(RearScreen.AOD);
        Notification.Builder builder = Notification.Builder.recoverBuilder(ctx, base)
                .setChannelId("pickup_rear").setOngoing(true).setOnlyAlertOnce(true)
                .setDeleteIntent(PendingIntent.getBroadcast(ctx, REAR_ID,
                        new Intent(ctx, RearReminderReceiver.class),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .setContentTitle(count + " 件待取").setContentText("背屏待取件 · 取完自动收起")
                .setStyle(new Notification.BigTextStyle().bigText("背屏待取件 · 取完自动收起"));
        builder.addExtras(RearScreen.pendingExtras(ctx, code, place == null ? "—" : place, count,
                base.actions[1], base.actions[0], initial));
        return builder.build();
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
            Notification notification = build(ctx, codes, place, count, alert);
            // The independent rear reminder follows every pending parcel, not the manual island selection.
            notification.extras.remove(RearScreen.PARAM);
            notification.extras.remove(RearScreen.VIEW);
            notification.extras.remove(RearScreen.AOD);
            ctx.getSystemService(NotificationManager.class).notify(PICKUP_ID, notification);
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
