package io.github.okaidev.pickupcode;

import android.content.pm.ProviderInfo;
import android.os.SystemClock;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * ColorOS 冻结后的 Provider 唤醒放行（system_server）：
 *
 * 现象：模块 App 被从最近任务划掉（ColorOS 会强行停止）后，短信 Hook 在
 * com.android.providers.telephony 里调用 ContentResolver.call 唤醒模块 Provider，
 * 得到 "Unknown authority"，取件码丢失。
 *
 * 根因（ColorOS 17 PLK110 oplus-services.jar 逆向）：AMS 拉起 Provider 前会经过
 * ContentProviderHelperExtImpl 的几道拦截，任一返回 true 即不拉起进程、getContentProvider 返回 null：
 *  - interceptStartProvider：目标进程未运行时，依次问 OplusAppStartupManager（关联启动）、
 *    OplusAppStartTracker（启动配额）、OplusLinkStartManager（链式启动）；
 *  - hookHansProviderIfNeeded：场景限制（IOplusSceneManager）与恶意行为策略；
 *  - hookbeforeInactiveProviderGet：恶意行为策略；
 *  - hookPreloadProviderBlock：资源预加载拦截。
 *
 * 处理：仅当「目标是本模块 Provider」且「调用方是系统身份（system 1000 / phone 1001）」时，
 * 让上述拦截返回 false（放行），其余调用保持系统原判定。这样短信进程（phone）与
 * system_server（通知通道）都能把已被冻结的模块拉起来，不需要给系统进程授权 root——
 * KernelSU 内核直接禁止 UID < 2000（system 除外）获取 su，「冻结免疫直写」在 KSU 上无法开启。
 *
 * 进程已存活但被 Hans 冻结的情况无需处理：Binder 事务会触发内核侧解冻（unfreezeForKernel）。
 * 非 ColorOS（找不到该类）时自动跳过。
 */
public class ColorOsProviderWake {

    private static final String TAG = "PICKUPDEBUG";
    private static final String EXT_CLASS = "com.android.server.am.ContentProviderHelperExtImpl";
    private static final String MODULE_PKG = "io.github.okaidev.pickupcode";
    private static final String[] GATES = {
            "interceptStartProvider",
            "hookHansProviderIfNeeded",
            "hookbeforeInactiveProviderGet",
            "hookPreloadProviderBlock",
    };
    private static final int SYSTEM_UID = 1000;
    private static final int PHONE_UID = 1001;
    private static final int PER_USER_RANGE = 100000;
    private static final long LOG_INTERVAL_MS = 10_000L;

    private static volatile long lastLogAt = -LOG_INTERVAL_MS;

    /** 在 system_server 进程调用一次。 */
    public static void install(ClassLoader sysCl) {
        Class<?> ext = XposedHelpers.findClassIfExists(EXT_CLASS, sysCl);
        if (ext == null) {
            log("WAKE skip: " + EXT_CLASS + " not found (non-ColorOS)");
            return;
        }
        int hooked = 0;
        for (String name : GATES) {
            try {
                hooked += XposedBridge.hookAllMethods(ext, name, GATE_HOOK).size();
            } catch (Throwable t) {
                log("WAKE hook err " + name + ": " + t);
            }
        }
        log("WAKE OK: " + hooked + " provider gate(s) hooked");
    }

    private static final XC_MethodHook GATE_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (!targetsModule(param.args)) return;
                String caller = callingPackage(param.args);
                int uid = callingUid(param.args);
                if (!isSystemCaller(uid, caller)) return;
                param.setResult(Boolean.FALSE);
                logAllowed(param.method.getName(), caller, uid);
            } catch (Throwable t) {
                log("WAKE gate err: " + t);
            }
        }
    };

    private static boolean targetsModule(Object[] args) {
        for (Object a : args) {
            if (a instanceof ProviderInfo) return MODULE_PKG.equals(((ProviderInfo) a).packageName);
        }
        return false;
    }

    private static String callingPackage(Object[] args) {
        for (Object a : args) {
            if (a instanceof String) return (String) a;
        }
        return null;
    }

    /** 各拦截方法里 callingUid 都是最后一个 int 参数（前面的 int 是 pid）；无 int 参数返回 -1。 */
    private static int callingUid(Object[] args) {
        int uid = -1;
        for (Object a : args) {
            if (a instanceof Integer) uid = (Integer) a;
        }
        return uid;
    }

    private static boolean isSystemCaller(int uid, String caller) {
        if (uid >= 0) {
            int appId = uid % PER_USER_RANGE;
            return appId == SYSTEM_UID || appId == PHONE_UID;
        }
        // hookbeforeInactiveProviderGet 只有调用方包名
        return "android".equals(caller)
                || "com.android.phone".equals(caller)
                || "com.android.providers.telephony".equals(caller);
    }

    private static void logAllowed(String gate, String caller, int uid) {
        long now = SystemClock.uptimeMillis();
        if (now - lastLogAt < LOG_INTERVAL_MS) return;
        lastLogAt = now;
        log("WAKE allowed: gate=" + gate + " caller=" + caller + " uid=" + uid);
    }

    private static void log(String msg) {
        try { XposedBridge.log(TAG + ": " + msg); } catch (Throwable ignored) { }
    }
}
