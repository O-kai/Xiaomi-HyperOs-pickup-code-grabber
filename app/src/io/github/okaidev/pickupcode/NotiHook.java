package io.github.okaidev.pickupcode;

import android.app.Notification;
import android.content.Context;
import android.os.Bundle;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.util.ArrayList;
import java.util.List;

/**
 * 通知拦截双通道（v2.9.0）：
 *
 * 原理：在 system_server（android 域，已在作用域内）Hook NotificationManagerService 的
 * enqueueNotificationInternal——全系统所有 App 的通知都必经此点，无需"通知使用权"权限，
 * 无保活问题，不受第三方后台冻结影响。
 *
 * 策略：
 *  1) 包名白名单初筛：只处理快递/电商 App 的通知（菜鸟/京东/淘宝/拼多多/丰巢/顺丰等），
 *     杜绝全量扫描的性能与误报面；
 *  2) 文本拼合（title + text + bigText）后喂给 PickupExtractor 动态规则引擎；
 *  3) 提取成功 → 与短信通道共用「码+地点」指纹去重 → 写入待办（与短信双通道无缝合并）；
 *  4) 提取为空但含特征词 → 记入疑似漏抓错题本（与短信同一闭环）；
 *  5) 开关：设置页「通知取件提取」（默认关；开启后 Hook 生效，无需重启）。
 *     开关状态经 /data/local/tmp/pickup_sqlite/enable_noti_hook 标志文件同步
 *     （system_server 读不到模块 SharedPreferences，与冻结免疫直写同一套机制）。
 */
public class NotiHook {

    private static final String TAG = "PICKUPDEBUG";
    private static final String FLAG_FILE = "/data/local/tmp/pickup_sqlite/enable_noti_hook";

    /** 快递/电商通知白名单（v1：主流大厂，按需热扩） */
    private static final List<String> PKG_WHITELIST = new ArrayList<>(java.util.Arrays.asList(
            "com.cainiao.wireless",     // 菜鸟
            "com.cainiao.guoguo",       // 菜鸟裹裹
            "com.jingdong.app.mall",    // 京东
            "com.taobao.taobao",        // 淘宝
            "com.xunmeng.pinduoduo",    // 拼多多
            "com.sf.cdsibm"             // 顺丰
    ));

    /** 模块入口调用：在 system_server 注册通知管线钩子 */
    public static void install(ClassLoader sysCl) {
        try {
            Class<?> nms = XposedHelpers.findClass(
                    "com.android.server.notification.NotificationManagerService", sysCl);
            XposedBridge.hookAllMethods(nms, "enqueueNotificationInternal", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        handleEnqueue(param);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": NotiHook err: " + t);
                    }
                }
            });
            XposedBridge.log(TAG + ": NotiHook installed on NotificationManagerService");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": NotiHook install FAILED: " + t);
        }
    }

    /** 单条通知处理：白名单 → 文本拼合 → 引擎提取 → 指纹去重 → 转发写入 */
    private static void handleEnqueue(XC_MethodHook.MethodHookParam param) {
        try {
            Object[] args = param.args;
            // 参数形态：enqueueNotificationInternal(String pkg, String opPkg, int uid, ...)，逐 ROM 稳定
            if (args == null || args.length < 10) return;
            String pkg = (String) args[0];
            if (pkg == null || !isWhitelisted(pkg)) return;
            if (!isFlagOn()) return;

            // 取 Notification 对象（倒数第 2/3 个参数位置因版本而异，稳妥做法：全参数扫）
            Notification n = findNotification(args);
            if (n == null) return;

            String text = buildText(n);
            if (text == null || text.isEmpty()) return;
            if (!PickupExtractor.hasFeatureWords(text)) return;

            List<String> codes = PickupExtractor.extract(text);
            if (codes.isEmpty()) {
                // 漏抓嫌疑：错题本（system_server 无 Context 写 prefs → 经广播转发给模块 App 记录）
                NotiRelay.relayMissed(param, pkg, text);
                return;
            }
            // 提取成功：转发模块 App（与短信通道共用 TodoWriter.handle 的去重与写入）
            NotiRelay.relayHit(param, pkg, text, codes);
        } catch (Throwable ignored) { }
    }

    private static boolean isWhitelisted(String pkg) {
        return PKG_WHITELIST.contains(pkg);
    }

    /** 标志文件开关（默认关；设置页经 su 写入 1/0 同步） */
    private static boolean isFlagOn() {
        try {
            java.io.File f = new java.io.File(FLAG_FILE);
            if (!f.exists()) return false;
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
            String line = r.readLine();
            r.close();
            return line != null && line.trim().equals("1");
        } catch (Throwable t) {
            return false;
        }
    }

    /** 在参数数组里找 Notification 对象 */
    private static Notification findNotification(Object[] args) {
        for (Object a : args) {
            if (a instanceof Notification) return (Notification) a;
        }
        return null;
    }

    /** 拼合通知全部可见文本（title + text + bigText + extras） */
    private static String buildText(Notification n) {
        StringBuilder sb = new StringBuilder();
        try {
            Bundle extras = n.extras;
            if (extras == null) return null;
            CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
            CharSequence big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
            if (title != null) sb.append(title).append(" ");
            if (text != null) sb.append(text).append(" ");
            if (big != null) sb.append(big);
        } catch (Throwable ignored) { }
        return sb.toString().trim();
    }
}
