package io.github.okaidev.pickupcode;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 系统「组件已固定到桌面」的回调（v4.2.1）。
 *
 * <p>由 {@link WidgetPin#quickPin} 作为 {@code successCallback} 的 PendingIntent 注册。
 * 只有组件真的被加到桌面上，系统才会发这条广播 —— 这是判断「HyperOS 到底有没有
 * 把请求吞掉」的唯一可靠信号。
 *
 * <p>exported=false：只能由本 App 自己注册的 PendingIntent 触发。
 */
public class WidgetPinReceiver extends BroadcastReceiver {

    public static final String ACTION_PINNED =
            "io.github.okaidev.pickupcode.action.WIDGET_PINNED";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        if (!ACTION_PINNED.equals(intent.getAction())) return;
        WidgetPin.onPinned(context);
    }
}
