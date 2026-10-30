package io.github.okaidev.pickupcode;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机后重注册取件提醒。
 *
 * <p>AlarmManager 的闹钟不跨重启，开机后必须重新挂上，否则提醒会静默失效。
 * 这里只做「重新排期」，不做别的动作，也不申请敏感权限。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            if (Reminder.isEnabled(context)) {
                Reminder.schedule(context);
                Log.i("PICKUPDEBUG", "REMINDER rescheduled after boot");
            }
        } catch (Throwable t) {
            Log.w("PICKUPDEBUG", "REMINDER boot reschedule failed: " + t);
        }
    }
}
