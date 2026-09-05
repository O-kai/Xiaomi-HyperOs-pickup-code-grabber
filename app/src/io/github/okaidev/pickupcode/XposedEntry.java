package io.github.okaidev.pickupcode;

import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 取件码助手 v2 - 模块入口（P1：捕获验证版）
 *
 * 职责：按进程分流注册 Hook 点，捕获短信并输出日志（双通道：XposedBridge.log + Log.i）。
 * P1 只做捕获验证；提取/通知/待办写入在 P2+ 接入。
 */
public class XposedEntry implements IXposedHookLoadPackage {

    private static final String TAG = "PICKUPDEBUG";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        String pkg = lpparam.packageName;
        log("handleLoadPackage: pkg=" + pkg + " process=" + lpparam.processName);
        if (pkg == null) return;
        // 模块自身进程：写入"已被 LSPosed 注入"标记（诊断报告用），然后返回
        if (pkg.equals("io.github.okaidev.pickupcode")) {
            writeSelfInjectMarker(lpparam);
            return;
        }

        try {
            if (pkg.equals("com.android.mms")) {
                hookMmsApp(lpparam);
            } else if (pkg.equals("com.android.phone")
                    || pkg.equals("android")          // 部分 ROM 将 systemserver 报告为 android
                    || pkg.equals("system")) {         // LSPosed 常见写法
                hookTelephony(lpparam);
            } else if (pkg.equals("com.android.providers.telephony")) {
                hookSmsProvider(lpparam);             // S8：短信库写入兜底（网络短信也走这里）
            } else if (unexpectedLogCount < 20) {
                // 用户勾选了非目标应用：明确记录，方便诊断报告看出"作用域勾错"
                unexpectedLogCount++;
                log("SCOPE-NOT-TARGET: pkg=" + pkg + "（该应用不在 Hook 目标内，"
                        + "若大量出现说明作用域勾错）");
            }
        } catch (Throwable t) {
            log("hook setup err [" + pkg + "]: " + t);
        }
    }

    /** 在模块自身 dataDir 写标记文件（v2.1.2）：诊断报告据此判断模块是否真的被 LSPosed 加载 */
    private void writeSelfInjectMarker(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            if (lpparam.appInfo == null || lpparam.appInfo.dataDir == null) return;
            java.io.File f = new java.io.File(lpparam.appInfo.dataDir, "files/pickup_injected.flag");
            if (!f.getParentFile().exists()) return; // files 目录未建则跳过（onCreate 时 App 自己会建）
            java.io.FileWriter w = new java.io.FileWriter(f, false);
            w.write(String.valueOf(System.currentTimeMillis()));
            w.close();
            log("self-inject marker written: " + f.getAbsolutePath());
        } catch (Throwable t) {
            log("self-inject marker err: " + t);
        }
    }

    private static int unexpectedLogCount = 0;

    /**
     * 短信 Provider 进程：Hook insert/bulkInsert（唯一必经的入库点，兜底所有来源）
     */
    private void hookSmsProvider(XC_LoadPackage.LoadPackageParam lpparam) {
        String[] candidates = {
                "com.android.providers.telephony.SmsProvider",
                "com.android.providers.telephony.MiuiTelephonyProviderImpl"
        };
        for (String cn : candidates) {
            try {
                Class<?> cls = XposedHelpers.findClass(cn, lpparam.classLoader);
                XposedBridge.hookAllMethods(cls, "insert", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try { SmsBridge.processProviderInsert(param); } catch (Throwable t) { log("S8 err: " + t); }
                    }
                });
                XposedBridge.hookAllMethods(cls, "bulkInsert", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try { SmsBridge.processProviderBulkInsert(param); } catch (Throwable t) { log("S8b err: " + t); }
                    }
                });
                log("S8 OK (" + cn + ") @" + lpparam.packageName);
            } catch (Throwable t) {
                log("S8 NOT FOUND (" + cn + ") @" + lpparam.packageName + ": " + t);
            }
        }
    }

    /**
     * 短信应用进程（com.android.mms）：Hook S2-S7 多点冗余
     */
    private void hookMmsApp(XC_LoadPackage.LoadPackageParam lpparam) {
        // 入口一：服务层（低概率，保留）
        try {
            Class<?> cls = XposedHelpers.findClass(
                    "com.android.mms.transaction.SmsReceiverService", lpparam.classLoader);
            XposedBridge.hookAllMethods(cls, "onReceive", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try { SmsBridge.processArguments(param); } catch (Throwable t) { log("S3 err: " + t); }
                }
            });
            log("S3 OK (SmsReceiverService.onReceive) @" + lpparam.packageName);

            XposedBridge.hookAllMethods(cls, "handleSmsMessage", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try { SmsBridge.processArguments(param); } catch (Throwable t) { log("S2 err: " + t); }
                }
            });
            log("S2 OK (handleSmsMessage) @" + lpparam.packageName);

            XposedBridge.hookAllMethods(cls, "handleSmsReceived", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try { SmsBridge.processArguments(param); } catch (Throwable t) { log("S7 err: " + t); }
                }
            });
            log("S7 OK (handleSmsReceived) @" + lpparam.packageName);
        } catch (Throwable t) {
            log("S2/S3/S7 NOT FOUND @" + lpparam.packageName + ": " + t);
        }

        // 入口二：真正的广播接收器（HyperOS 实测主入口）
        hookReceiver(lpparam, "com.android.mms.transaction.SmsReceiver", "S4");
        hookReceiver(lpparam, "com.android.mms.transaction.HighPrivilegedSmsReceiver", "S5");
        hookReceiver(lpparam, "com.android.mms.transaction.BeidouSmsReceiver", "S6");
    }

    private void hookReceiver(XC_LoadPackage.LoadPackageParam lpparam, String className, String tag) {
        try {
            Class<?> cls = XposedHelpers.findClass(className, lpparam.classLoader);
            XposedBridge.hookAllMethods(cls, "onReceive", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try { SmsBridge.processArguments(param); } catch (Throwable t) { log(tag + " err: " + t); }
                }
            });
            log(tag + " OK (" + className + ") @" + lpparam.packageName);
        } catch (Throwable t) {
            log(tag + " NOT FOUND (" + className + ") @" + lpparam.packageName + ": " + t);
        }
    }

    /**
     * 电话/框架进程：Hook S1（系统级短信广播接收器）
     */
    private void hookTelephony(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> cls = XposedHelpers.findClass(
                    "com.android.internal.telephony.SmsBroadcastReceiver", lpparam.classLoader);
            XposedBridge.hookAllMethods(cls, "onReceive", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try { SmsBridge.processArguments(param); } catch (Throwable t) { log("S1 err: " + t); }
                }
            });
            log("S1 OK (SmsBroadcastReceiver.onReceive) @" + lpparam.packageName);
        } catch (Throwable t) {
            log("S1 NOT FOUND @" + lpparam.packageName + ": " + t);
        }
    }

    static void log(String msg) {
        try { XposedBridge.log(TAG + ": " + msg); } catch (Throwable ignored) { }
        try { Log.i(TAG, msg); } catch (Throwable ignored) { }
    }
}
