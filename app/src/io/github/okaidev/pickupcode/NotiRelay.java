package io.github.okaidev.pickupcode;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Notification events use the wakeable Provider, including no-code diagnostic samples. */
public class NotiRelay {
    private static final String TAG = "PICKUPDEBUG";
    private static final String ACTION = "io.github.okaidev.pickupcode.action.ON_SMS";
    private static final String MODULE_PKG = "io.github.okaidev.pickupcode";
    private static final Uri PROVIDER_URI = Uri.parse("content://" + MODULE_PKG + ".provider");

    // Never block NMS or create an unbounded number of threads for notification updates.
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(
            1, 1, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(64), r -> {
                Thread t = new Thread(r, "pickup-noti-relay");
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.AbortPolicy());
    static { WORKER.allowCoreThreadTimeOut(true); }

    private static Context nmsContext(Object svc) {
        try {
            return (Context) XposedHelpers.getObjectField(svc, "mContext");
        } catch (Throwable t) {
            log("NOTI context unavailable: " + t);
            return null;
        }
    }

    public static void relayHit(XC_MethodHook.MethodHookParam param,
                                String pkg, String text, List<String> codes) {
        relay(param, pkg, text, false);
    }

    public static void relayMissed(XC_MethodHook.MethodHookParam param,
                                   String pkg, String text) {
        relay(param, pkg, text, true);
    }

    private static void relay(XC_MethodHook.MethodHookParam param,
                              String pkg, String text, boolean missed) {
        Context ctx = nmsContext(param.thisObject);
        if (ctx == null) return;
        final long ts = System.currentTimeMillis();
        try {
            WORKER.execute(() -> deliver(ctx, pkg, text, missed, ts));
        } catch (java.util.concurrent.RejectedExecutionException t) {
            log("NOTI relay queue full: pkg=" + pkg);
        }
    }

    /** Package-visible for the offline transport tests; no notification text in logs. */
    static void deliver(Context ctx, String pkg, String text, boolean missed, long ts) {
        String sender = "通知:" + pkg;
        long identity = Binder.clearCallingIdentity();
        try {
            try {
                Bundle extras = new Bundle();
                extras.putString("sender", sender);
                extras.putString("body", text);
                extras.putLong("ts", ts);
                extras.putBoolean("miss", missed);
                Bundle result = ctx.getContentResolver().call(PROVIDER_URI, "onSms", null, extras);
                if (result != null) {
                    // Provider acceptance is not the same as a successful database INSERT.
                    String status = result.getString("status");
                    if (status == null || status.isEmpty()) status = "unconfirmed_legacy_reply";
                    log("NOTI-ACK pkg=" + pkg + " status=" + status
                            + " written=" + result.getInt("written_count", 0)
                            + " codes=" + result.getInt("code_count", 0)
                            + " deduplicated=" + result.getInt("dedup_count", 0)
                            + " failed=" + result.getInt("failed_count", 0));
                    return;
                }
                log("NOTI provider returned no receipt: pkg=" + pkg);
            } catch (Throwable t) {
                log("NOTI provider err: pkg=" + pkg + " error=" + t);
            }

            // Keep compatibility, but never claim sendBroadcast means delivered or written.
            try {
                Intent it = new Intent(ACTION);
                it.setPackage(MODULE_PKG);
                it.putExtra("sender", sender);
                it.putExtra("body", text);
                it.putExtra("ts", ts);
                it.putExtra("miss", missed);
                ctx.sendBroadcast(it);
                log("NOTI broadcast submitted (unconfirmed): pkg=" + pkg);
            } catch (Throwable t) {
                log("NOTI broadcast err: pkg=" + pkg + " error=" + t);
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private static void log(String message) {
        try { XposedBridge.log(TAG + ": " + message); } catch (Throwable ignored) { }
    }
}
