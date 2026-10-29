package io.github.okaidev.pickupcode;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

import de.robv.android.xposed.XposedBridge;

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
    private static final Uri PROVIDER_URI =
            Uri.parse("content://io.github.okaidev.pickupcode.provider");

    /** 从 NMS 服务对象里反射取 system Context（调用 Provider 用） */
    private static Context nmsContext(Object svc) {
        try {
            return (Context) de.robv.android.xposed.XposedHelpers.getObjectField(svc, "mContext");
        } catch (Throwable t) {
            return null;
        }
    }

    /** 提取成功：伪短信上报（与短信同链路去重写入） */
    public static void relayHit(de.robv.android.xposed.XC_MethodHook.MethodHookParam param,
                                String pkg, String text, java.util.List<String> codes) {
        Bundle result = relay(param, pkg, text, false);
        XposedBridge.log(TAG + ": NOTI-HIT relayed from " + pkg
                + " codes=" + codes + " result=" + result);
    }

    /** 提取为空但含特征词：错题本上报（Provider 侧记录） */
    public static void relayMissed(de.robv.android.xposed.XC_MethodHook.MethodHookParam param,
                                   String pkg, String text) {
        Bundle result = relay(param, pkg, text, true);
        XposedBridge.log(TAG + ": NOTI-MISSED relayed from " + pkg + " result=" + result);
    }

    private static Bundle relay(de.robv.android.xposed.XC_MethodHook.MethodHookParam param,
                                String pkg, String text, boolean miss) {
        try {
            Context ctx = nmsContext(param.thisObject);
            if (ctx == null) {
                XposedBridge.log(TAG + ": noti relay skip: no NMS context");
                return null;
            }
            Bundle extras = new Bundle();
            extras.putString("sender", "通知:" + pkg);
            extras.putString("body", text);
            extras.putBoolean("miss", miss);
            extras.putLong("ts", System.currentTimeMillis());
            return ctx.getContentResolver().call(PROVIDER_URI, "onSms", null, extras);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": noti relay err: " + t);
            return null;
        }
    }
}
