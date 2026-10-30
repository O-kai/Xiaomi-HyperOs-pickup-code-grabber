package io.github.okaidev.pickupcode;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import java.util.Locale;

/**
 * 取件提醒（4.0.x）
 *
 * <p>每天在用户设定的时间检查一次：本地取件库里还有没取的件，就提醒一次；
 * 没有待取件就安静跳过——提醒只在真的有事时才出现，不制造噪音。
 *
 * <p>调度用 {@code setInexactRepeating}：提醒不需要精确到分钟，
 * inexact 省电得多，也不会因为 doze 对 exact 闹钟的限制而失效。
 *
 * <p>重启后需要重新注册（AlarmManager 的闹钟不跨重启），
 * 由 {@link BootReceiver} 在 BOOT_COMPLETED 时补一次。
 */
public final class Reminder {

    private Reminder() { }

    private static final String TAG = "PICKUPDEBUG";
    private static final String PREFS = "pickup_reminder";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_HOUR = "hour";
    private static final String KEY_MINUTE = "minute";

    /** 闹钟广播动作 */
    public static final String ACTION_FIRE = "io.github.okaidev.pickupcode.action.REMINDER";

    private static final int REQUEST = 8801;
    private static final String CHANNEL_ID = "pickup_reminder";
    private static final int NOTIFY_ID = 8802;

    // ==================== 设置 ====================

    public static boolean isEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, on).apply();
        if (on) {
            schedule(ctx);
        } else {
            cancel(ctx);
        }
    }

    public static int hour(Context ctx) {
        return prefs(ctx).getInt(KEY_HOUR, 9);
    }

    public static int minute(Context ctx) {
        return prefs(ctx).getInt(KEY_MINUTE, 0);
    }

    public static void setTime(Context ctx, int h, int m) {
        prefs(ctx).edit().putInt(KEY_HOUR, h).putInt(KEY_MINUTE, m).apply();
        if (isEnabled(ctx)) schedule(ctx);
    }

    public static String timeLabel(Context ctx) {
        return String.format(Locale.CHINA, "%02d:%02d", hour(ctx), minute(ctx));
    }

    // ==================== 调度 ====================

    /** 下一次触发的时间戳（今天该点已过则顺延到明天） */
    public static long nextTriggerAt(Context ctx) {
        return nextTriggerAt(hour(ctx), minute(ctx), System.currentTimeMillis());
    }

    /** 下一次触发的时间戳（纯计算，便于离线单测） */
    public static long nextTriggerAt(int hour, int minute, long nowMillis) {
        return ReminderSchedule.nextTriggerAt(hour, minute, nowMillis);
    }

    public static void schedule(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            PendingIntent pi = firePendingIntent(ctx);
            am.cancel(pi);
            am.setInexactRepeating(AlarmManager.RTC_WAKEUP, nextTriggerAt(ctx),
                    AlarmManager.INTERVAL_DAY, pi);
            Log.i(TAG, "REMINDER scheduled daily at " + timeLabel(ctx));
        } catch (Throwable t) {
            Log.w(TAG, "REMINDER schedule failed: " + t);
        }
    }

    public static void cancel(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(firePendingIntent(ctx));
        } catch (Throwable ignored) { }
    }

    private static PendingIntent firePendingIntent(Context ctx) {
        Intent i = new Intent(ctx, ReminderReceiver.class);
        i.setAction(ACTION_FIRE);
        return PendingIntent.getBroadcast(ctx, REQUEST, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    // ==================== 触发 ====================

    /**
     * 闹钟到点：只有真有未取件才提醒。
     *
     * <p>刻意放到后台线程：BroadcastReceiver.onReceive 跑在主线程上，
     * 而 Room 不允许主线程访问数据库——在主线程查会把异常吞掉、
     * 计数变成 0，提醒就永远发不出来（这里曾经踩过）。
     */
    public static void fire(Context ctx) {
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                int pending = countPending(app);
                if (pending <= 0) {
                    Log.i(TAG, "REMINDER fire: 没有待取件，静默跳过");
                    return;
                }
                notifyPending(app, pending);
            } catch (Throwable t) {
                Log.w(TAG, "REMINDER fire failed: " + t);
            }
        }, "pickup-reminder").start();
    }

    /**
     * 同步执行一次提醒逻辑并返回待取件数。
     *
     * <p>给「立即测试一次」用；调用方必须已经不在主线程上。
     */
    public static int fireNow(Context ctx) {
        try {
            int pending = countPending(ctx);
            if (pending <= 0) {
                Log.i(TAG, "REMINDER fire: 没有待取件，静默跳过");
            } else {
                notifyPending(ctx, pending);
            }
            return pending;
        } catch (Throwable t) {
            Log.w(TAG, "REMINDER fire failed: " + t);
            return 0;
        }
    }

    /** 待取件数（必须在后台线程调用） */
    private static int countPending(Context ctx) {
        try {
            return PickupRepository.get(ctx).countPendingSync();
        } catch (Throwable t) {
            Log.w(TAG, "REMINDER count failed: " + t);
            return 0;
        }
    }

    private static void notifyPending(Context ctx, int pending) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "取件提醒",
                    NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("每天提醒还有多少件包裹没取");
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(ctx, PickupListActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(ctx, REQUEST + 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(ctx, CHANNEL_ID)
                : new Notification.Builder(ctx);
        b.setSmallIcon(ctx.getApplicationInfo().icon);
        b.setContentTitle("还有 " + pending + " 件包裹没取");
        b.setContentText("点击查看待取件清单");
        b.setContentIntent(pi);
        b.setAutoCancel(true);
        nm.notify(NOTIFY_ID, b.build());
        Log.i(TAG, "REMINDER notified pending=" + pending);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
