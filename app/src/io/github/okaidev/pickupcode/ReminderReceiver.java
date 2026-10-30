package io.github.okaidev.pickupcode;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 取件提醒闹钟接收器。
 *
 * <p>只在 App 自己的进程里跑（exported=false，外部无法触发）；
 * 真正的动作在 {@link Reminder#fire(Context)} 里。
 */
public class ReminderReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Reminder.fire(context);
    }
}
