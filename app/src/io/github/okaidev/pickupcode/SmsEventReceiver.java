package io.github.okaidev.pickupcode;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.util.List;

/**
 * 短信事件接收器（模块自身进程）：
 * Hook 进程抓到短信后发广播到这里，这里完成：提取 → 去重 → 写待办 → 通知。
 */
public class SmsEventReceiver extends BroadcastReceiver {

    public static final String ACTION = "io.github.okaidev.pickupcode.action.ON_SMS";
    private static final String TAG = "PICKUPDEBUG";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;

        String sender = intent.getStringExtra("sender");
        String body = intent.getStringExtra("body");
        long ts = intent.getLongExtra("ts", System.currentTimeMillis());
        Log.i(TAG, "EVENT: sender=" + sender + " ts=" + ts + " body=" + (body == null ? "" : body.substring(0, Math.min(body.length(), 120))));

        if (body == null || body.trim().isEmpty()) return;
        if (!PickupExtractor.lookLikePickupSms(body)) {
            Log.i(TAG, "EVENT: 非取件短信，跳过");
            return;
        }

        List<String> wrote = TodoWriter.handle(ctx, sender, body);
        if (!wrote.isEmpty()) {
            Log.i(TAG, "EVENT: 已写入待办 " + wrote);
        }
        // P3 起：通知栏提示 + 点击复制
    }
}
