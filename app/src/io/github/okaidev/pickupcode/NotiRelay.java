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
 *  - 复用 ACTION_ON_SMS 广播（receiver 已注册、exported，模块 App 冻结时
 *    ContentResolver 通道失效的场景走 SystemDirectWriter，通知通道暂不做直写兜底）；
 *  - sender 传「通知:包名」前缀，写入模板的 {source} 自然落到来源识别；
 *  - 漏抓样本：同样广播，但 extra miss=1 时由 receiver 侧记错题本
 *    （receiver 在模块 App 进程内，有 Context，可直接写 SharedPreferences）。
 */
public class NotiRelay {

    private static final String TAG = "PICKUPDEBUG";
    private static final String ACTION = "io.github.okaidev.pickupcode.action.ON_SMS";

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
                                String pkg, String text, java.util.List<String> codes) {
        try {
            android.content.Context ctx = nmsContext(param.thisObject);
            if (ctx == null) {
                XposedBridge.log(TAG + ": relayHit skip: no NMS context");
                return;
            }
            Intent it = new Intent(ACTION);
            it.setPackage("io.github.okaidev.pickupcode");
            it.putExtra("sender", "通知:" + pkg);
            it.putExtra("body", text);
            it.putExtra("ts", System.currentTimeMillis());
            ctx.sendBroadcast(it);
            XposedBridge.log(TAG + ": NOTI-HIT relayed from " + pkg + " codes=" + codes);
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
