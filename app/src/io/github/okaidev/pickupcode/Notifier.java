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

/**
 * 通知（平台 API，无 androidx 依赖）：
 * 写入待办后弹通知；点击 = 复制取件码到剪贴板 + 打开目标待办 App
 * （v2.7.0：小米笔记 / ColorOS 日历 / ColorOS 便签，由 NotesBackend 当前后端决定）。
 */
public class Notifier {

    private static final String TAG = "PICKUPDEBUG";
    private static final String CHANNEL_ID = "pickup";
    /** 同一条短信里第 2 个及以后的码走这条静默渠道（不响铃、不震动） */
    private static final String CHANNEL_QUIET = "pickup_quiet";

    /**
     * 收到取件码后的提醒。
     *
     * <p><b>v4.2.1：一个取件码一条通知。</b>以前是「全部码拼成一条」——
     * 一条短信里有两个码时，待办里是两条，通知里却只有一条标题写着
     * 「12-3-4567 58-2-9041」，用户以为只收到一个件。
     *
     * <p>拆开之后：
     * <ul>
     *   <li>id 按取件码 hash 取模，同一个码再来一次是「更新」而不是多一条；</li>
     *   <li>只有第一条响铃/震动，后面的静音——一次来 5 个码不该响 5 次；</li>
     *   <li>每条通知各自带「已取件 <这个码>」按钮，点击也只复制这一个码。</li>
     * </ul>
     */
    public static void notifyCodes(Context ctx, List<String> codes, String titleExtra) {
        if (codes == null || codes.isEmpty()) return;
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager nm0 = ctx.getSystemService(NotificationManager.class);
                if (nm0 != null) {
                    NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "取件码通知",
                            NotificationManager.IMPORTANCE_HIGH);
                    ch.setDescription("收到快递取件码时提醒");
                    nm0.createNotificationChannel(ch);
                    // 同一条短信里的第 2、3 个码走静默渠道：一次来三个码不该响三声，
                    // 但每一条都要在通知栏里各占一行，别拼成一条
                    NotificationChannel quiet = new NotificationChannel(CHANNEL_QUIET,
                            "取件码通知（静默）", NotificationManager.IMPORTANCE_LOW);
                    quiet.setDescription("同一条短信里的其余取件码，只显示不响铃");
                    quiet.setSound(null, null);
                    quiet.enableVibration(false);
                    nm0.createNotificationChannel(quiet);
                }
            }

            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            for (int i = 0; i < codes.size(); i++) {
                String code = codes.get(i);
                if (code == null || code.trim().isEmpty()) continue;

                String text = titleExtra == null || titleExtra.isEmpty()
                        ? "点击复制并查看待办" : titleExtra + " · 点击复制";
                String channel = i == 0 ? CHANNEL_ID : CHANNEL_QUIET;

                Intent open = new Intent(ctx, NotificationActionActivity.class);
                open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                open.putExtra("copy", code);
                PendingIntent pi = PendingIntent.getActivity(ctx, 1000 + i, open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

                Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                        ? new Notification.Builder(ctx, channel)
                        : new Notification.Builder(ctx);
                b.setSmallIcon(ctx.getApplicationInfo().icon);
                b.setContentTitle(code);
                b.setContentText(text);
                b.setStyle(new Notification.BigTextStyle().bigText(text));
                b.setContentIntent(pi);
                b.setAutoCancel(true);
                b.setWhen(System.currentTimeMillis());

                // 动作按钮：已取件（Activity 通道，可唤醒冻结进程）/ 查看待办
                Intent done = new Intent(ctx, NotificationActionActivity.class);
                done.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                done.putExtra("done", code);
                PendingIntent pd = PendingIntent.getActivity(ctx, 1100 + i, done,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                b.addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_agenda, "已取件", pd).build());

                Intent notesIntent = ctx.getPackageManager().getLaunchIntentForPackage(NotesBackend.targetPkg(ctx));
                if (notesIntent != null) {
                    PendingIntent pn = PendingIntent.getActivity(ctx, 1200 + i, notesIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                    b.addAction(new Notification.Action.Builder(
                            android.R.drawable.ic_menu_agenda, "查看待办", pn).build());
                }

                // id 跟着取件码走（不用序号）：同一个码重复通知是更新同一条，
                // 不会因为两次短信条数不同而互相覆盖错位
                int id = 2000 + (Math.abs(code.hashCode()) % 900);
                nm.notify(id, b.build());
                Log.i(TAG, "NOTIFY #" + id + ": " + code);
            }
        } catch (Throwable t) {
            Log.e(TAG, "notify err: " + t);
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
