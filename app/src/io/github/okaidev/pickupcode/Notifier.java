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
}
