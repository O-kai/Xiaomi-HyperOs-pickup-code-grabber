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
 * 取件提醒（4.0.x，v4.2.1 改为精确闹钟）
 *
 * <p>每天在用户设定的时间检查一次：本地取件库里还有没取的件，就提醒一次；
 * 没有待取件就安静跳过——提醒只在真的有事时才出现，不制造噪音。
 *
 * <p><b>为什么从 setInexactRepeating 改成 setExactAndAllowWhileIdle</b>：
 * inexact 闹钟由系统按「省电窗口」批量合并，Android 文档原话是
 * 「the OS will shift the alarm's delivery to a later time」；再叠加 HyperOS
 * 自己的后台管控，实测偏差可以从几分钟到几十分钟 —— 这就是用户反馈的
 * 「选了 9:00，实际 9:1x 才响」。改成一次性精确闹钟 + 响过之后自己排下一天，
 * 时间点才是用户选的那个。
 *
 * <p>Android 12 起精确闹钟需要 {@code SCHEDULE_EXACT_ALARM} 权限；
 * 没给就退回 {@code setAndAllowWhileIdle}（仍然不准，但比不响好），
 * 设置页会给一条「去开启」的入口。
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

    /**
     * 本机是否允许精确闹钟（Android 12+ 需要用户授权「闹钟和提醒」）。
     *
     * <p>12 以下没有这个限制，直接算允许 —— 调用方不用自己判断版本。
     */
    public static boolean exactAllowed(Context ctx) {
        if (Build.VERSION.SDK_INT < 31) return true;
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            return am != null && am.canScheduleExactAlarms();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 跳到系统「闹钟和提醒」授权页（Android 12+）。
     *
     * @return true = 已跳转；false = 这个 ROM 没有这个页面，调用方自己给提示
     */
    public static boolean requestExactPermission(Context ctx) {
        if (Build.VERSION.SDK_INT < 31) return false;
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    android.net.Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void schedule(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            PendingIntent pi = firePendingIntent(ctx);
            am.cancel(pi);
            long at = nextTriggerAt(ctx);
            if (exactAllowed(ctx)) {
                // 一次性精确闹钟：响过之后由 fire() 自己排下一天（见 fire 里的 schedule）
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                Log.i(TAG, "REMINDER scheduled EXACT at " + timeLabel(ctx));
            } else {
                // 没给精确闹钟权限：至少保证会响，并在设置页提示用户去开
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                Log.w(TAG, "REMINDER scheduled INEXACT（未授予精确闹钟）at " + timeLabel(ctx));
            }
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
                } else {
                    notifyPending(app, pending);
                }
            } catch (Throwable t) {
                Log.w(TAG, "REMINDER fire failed: " + t);
            } finally {
                // v4.2.1：改成一次性精确闹钟之后，必须自己排下一天，
                // 否则明天就不响了（以前 setInexactRepeating 会自己重复）。
                // 放在 finally 里：即使今天查库失败，也不能把明天的闹钟弄丢。
                try { schedule(app); } catch (Throwable ignored) { }
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
