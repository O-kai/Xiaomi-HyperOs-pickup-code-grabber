package io.github.okaidev.pickupcode;

import android.content.Intent;

import de.robv.android.xposed.XposedBridge;

/**
 * 通知转发桥（v2.9.0）：
 * system_server 进程（NotiHook 命中白名单通知后）通过本类把通知文本以
 * 「伪短信」形式广播到模块自身进程——与短信通道完全共用
 * SmsEventReceiver → TodoWriter.handle 的提取/去重/写入/通知链路。
 *
 * 设计要点：
 *  - 命中：优先 ContentResolver.call（与短信同一 Provider，ColorOS 冻结时由
 *    ColorOsProviderWake 放行拉起），失败再退回 ACTION_ON_SMS 广播；
 *  - sender 传「通知:包名」前缀，写入模板的 {source} 自然落到来源识别；
 *  - 漏抓样本：同样广播，但 extra miss=1 时由 receiver 侧记错题本
 *    （receiver 在模块 App 进程内，有 Context，可直接写 SharedPreferences）。
 */
public class NotiRelay {

    private static final String TAG = "PICKUPDEBUG";
    private static final String ACTION = "io.github.okaidev.pickupcode.action.ON_SMS";
    private static final String PROVIDER_URI = "content://io.github.okaidev.pickupcode.provider";

    /** 从 NMS 服务对象里反射取 system Context（发广播用） */
    private static android.content.Context nmsContext(Object svc) {
        try {
            return (android.content.Context) de.robv.android.xposed.XposedHelpers.getObjectField(svc, "mContext");
        } catch (Throwable t) {
            return null;
        }
    }

    /** 提取成功：伪短信广播（与短信同链路去重写入） */
    public static void relayHit(de.robv.android.xposed.XC_MethodHook.MethodHookParam param,
                                String pkg, final String text, final java.util.List<String> codes) {
        try {
            android.content.Context ctx = nmsContext(param.thisObject);
            if (ctx == null) {
                XposedBridge.log(TAG + ": relayHit skip: no NMS context");
                return;
            }
            final android.content.Context appCtx = ctx;
            final String sender = "通知:" + pkg;
            final long ts = System.currentTimeMillis();
            // 主通道改为 ContentResolver.call（与短信通道一致）：模块被 ColorOS 冻结时广播送不到，
            // Provider 则由 ColorOsProviderWake 放行拉起。拉起冷进程可能要数百毫秒，
            // 不能卡在 NMS 的通知入队线程里，放到后台线程执行。
            new Thread(() -> {
                try {
                    android.os.Bundle extras = new android.os.Bundle();
                    extras.putString("sender", sender);
                    extras.putString("body", text);
                    extras.putLong("ts", ts);
                    appCtx.getContentResolver().call(
                            android.net.Uri.parse(PROVIDER_URI), "onSms", null, extras);
                    XposedBridge.log(TAG + ": NOTI-HIT relayed (provider) from " + pkg + " codes=" + codes);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": NOTI-HIT provider err: " + t + "，改用广播");
                    Intent it = new Intent(ACTION);
                    it.setPackage("io.github.okaidev.pickupcode");
                    it.putExtra("sender", sender);
                    it.putExtra("body", text);
                    it.putExtra("ts", ts);
                    appCtx.sendBroadcast(it);
                    XposedBridge.log(TAG + ": NOTI-HIT relayed (broadcast) from " + pkg + " codes=" + codes);
                }
            }, "pickup-noti-relay").start();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": relayHit err: " + t);
        }
    }

    /** 提取为空但含特征词：错题本广播（receiver 侧记录） */
    public static void relayMissed(de.robv.android.xposed.XC_MethodHook.MethodHookParam param,
                                  String pkg, String text) {
        try {
            android.content.Context ctx = nmsContext(param.thisObject);
            if (ctx == null) {
                XposedBridge.log(TAG + ": relayMissed skip: no NMS context");
                return;
            }
            Intent it = new Intent(ACTION);
            it.setPackage("io.github.okaidev.pickupcode");
            it.putExtra("sender", "通知:" + pkg);
            it.putExtra("body", text);
            it.putExtra("miss", 1);
            it.putExtra("ts", System.currentTimeMillis());
            ctx.sendBroadcast(it);
            XposedBridge.log(TAG + ": NOTI-MISSED relayed from " + pkg);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": relayMissed err: " + t);
        }
    }
}
