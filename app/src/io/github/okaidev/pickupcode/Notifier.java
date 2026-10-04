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

    public static void notifyCodes(Context ctx, List<String> codes, String titleExtra) {
        if (codes == null || codes.isEmpty()) return;
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "取件码通知",
                        NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("收到快递取件码时提醒");
                ctx.getSystemService(NotificationManager.class).createNotificationChannel(ch);
            }

            String codesJoined = String.join(" ", codes);
            String title = "📦 " + codesJoined;
            String text = titleExtra == null || titleExtra.isEmpty()
                    ? "点击复制并查看待办" : titleExtra + " · 点击复制";

            Intent open = new Intent(ctx, LauncherActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            open.putExtra("copy", codesJoined);
            PendingIntent pi = PendingIntent.getActivity(ctx, 0, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                    ? new Notification.Builder(ctx, CHANNEL_ID)
                    : new Notification.Builder(ctx);
            b.setSmallIcon(ctx.getApplicationInfo().icon);
            b.setContentTitle(title);
            b.setContentText(text);
            b.setStyle(new Notification.BigTextStyle().bigText(text));
            b.setContentIntent(pi);
            b.setAutoCancel(true);

            // 动作按钮：已取件（Activity 通道，可唤醒冻结进程）/ 查看待办（逐码添加）
            int req = 100;
            for (String c : codes) {
                Intent done = new Intent(ctx, LauncherActivity.class);
                done.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                done.putExtra("done", c);
                PendingIntent pd = PendingIntent.getActivity(ctx, req++, done,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                b.addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_agenda, "已取件 " + c, pd).build());
            }
            Intent notesIntent = ctx.getPackageManager().getLaunchIntentForPackage(NotesBackend.targetPkg(ctx));
            if (notesIntent != null) {
                PendingIntent pn = PendingIntent.getActivity(ctx, 300, notesIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                b.addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_agenda, "查看待办", pn).build());
            }

            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(1001, b.build());
            Log.i(TAG, "NOTIFY: " + title);
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
            b.setContentTitle("⚠️ 取件码已识别，但未能写入待办");
            b.setContentText(text);
            b.setStyle(new Notification.BigTextStyle().bigText(
                    text + "\n\n可打开模块点「🔍 排查问题」按向导处理，"
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
