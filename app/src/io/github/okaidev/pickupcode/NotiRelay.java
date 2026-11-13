package io.github.okaidev.pickupcode;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;



/**
 * 通知转发桥（v2.9.0，4.0 Stage 0 改造）：
 * system_server 进程（NotiHook 命中白名单通知后）通过 ContentResolver.call
 * 把通知文本交给模块 App 的 TodoProvider，与短信通道完全共用
 * 提取/去重/写入/通知链路。
 *
 * 4.0 Stage 0：不再使用 exported 广播 receiver。
 * 旧实现任何 App 都能发 ACTION_ON_SMS 伪造取件短信；现在只保留 Provider
 * 单入口，调用方由 CallerPolicy 按 UID/包名校验。
 */
public class NotiRelay {

    private static final String TAG = "PICKUPDEBUG";
    private static final java.util.concurrent.ThreadPoolExecutor IO = new java.util.concurrent.ThreadPoolExecutor(
            0, 1, 30, java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(16), runnable -> {
                Thread thread = new Thread(runnable, "pickup-noti-relay");
                thread.setDaemon(true);
                return thread;
            }, new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    private static final Uri PROVIDER_URI =
            Uri.parse("content://io.github.okaidev.pickupcode.provider");

    /** 从 NMS 服务对象里反射取 system Context（调用 Provider 用） */
    private static Context nmsContext(Object svc) {
        try {
            return (Context) Reflect.getObjectField(svc, "mContext");
        } catch (Throwable t) {
            return null;
        }
    }

    /** 提取成功：伪短信上报（与短信同链路去重写入） */
    public static void relayHit(Object service,
                                String pkg, String text, java.util.List<String> codes) {
        relayAsync(service, pkg, text, false, "NOTI-HIT count=" + codes.size());
    }

    /** 提取为空但含特征词：错题本上报（Provider 侧记录） */
    public static void relayMissed(Object service,
                                   String pkg, String text) {
        relayAsync(service, pkg, text, true, "NOTI-MISSED");
    }

    /**
     * 异步上报（v4.0.2 第八轮，来自 PR #14）。
     *
     * <p><b>为什么必须异步</b>：本方法由 {@code NotiHook} 在
     * {@code enqueueNotificationInternal} 的钩子里调用，也就是跑在
     * system_server 的<b>通知入队线程</b>上。
     * 而 Provider 调用会唤醒模块 App 的冷进程——实测几百毫秒。
     * 这段时间里整台手机的通知入队都被卡住，属于「用户能感觉到的系统卡顿」，
     * 严重时直接 ANR。
     *
     * <p>改成后台线程后，通知照常显示，取件码稍后异步写入。
     * 代价是拿不到同步回执——所以结果只写日志，不影响功能。
     */
    private static void relayAsync(Object service,
                                   final String pkg, final String text,
                                   final boolean miss, final String logTag) {
        final Object svc = service;
        try {
            IO.execute(() -> {
                Bundle result = relay(svc, pkg, text, miss);
                ModuleLog.log(TAG + ": " + logTag + " from " + pkg + " accepted=" + (result != null && result.getInt("rc", -1) == 0));
            });
        } catch (Throwable t) {
            ModuleLog.log("Notification relay unavailable: " + t.getClass().getSimpleName());
        }
    }

    private static Bundle relay(Object svc, String pkg, String text, boolean miss) {
        try {
            Context ctx = nmsContext(svc);
            if (ctx == null) {
                ModuleLog.log(TAG + ": noti relay skip: no NMS context");
                return null;
            }
            Bundle extras = new Bundle();
            extras.putString("sender", "通知:" + pkg);
            extras.putString("body", text);
            extras.putBoolean("miss", miss);
            extras.putLong("ts", System.currentTimeMillis());
            return ctx.getContentResolver().call(PROVIDER_URI, "onSms", null, extras);
        } catch (Throwable t) {
            ModuleLog.log("Notification relay failed: " + t.getClass().getSimpleName());
            return null;
        }
    }
}
