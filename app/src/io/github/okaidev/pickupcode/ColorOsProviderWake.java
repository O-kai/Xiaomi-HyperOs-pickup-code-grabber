package io.github.okaidev.pickupcode;

import android.content.pm.ProviderInfo;
import android.os.SystemClock;


/**
 * ColorOS：放行系统进程唤醒已冻结的模块 Provider（v4.0.2 第八轮，来自 PR #13）。
 *
 * <p><b>要解决的现象</b>：在 ColorOS 上把本模块从最近任务划掉，系统会把它强行停止。
 * 之后短信进程调用 {@code ContentResolver.call} 唤醒模块 Provider 时拿到
 * {@code Unknown authority}，取件码直接丢失。
 *
 * <p><b>现有兜底为什么不够</b>：「冻结免疫直写」需要给短信进程授权 root，
 * 而短信进程跑在 {@code android.uid.phone}（UID 1001）。KernelSU 内核直接禁止
 * UID &lt; 2000（system 1000 除外）获取 su，所以 KSU 用户根本开不了这个兜底。
 *
 * <p><b>根因</b>：ColorOS 在 {@code oplus-services.jar} 的
 * {@code ContentProviderHelperExtImpl} 里，于 AMS 拉起 Provider 之前设了四道拦截，
 * 任一返回 true 就不拉起进程、{@code getContentProvider} 返回 null：
 * <ul>
 *   <li>{@code interceptStartProvider} —— 关联启动 / 启动配额 / 链式启动；</li>
 *   <li>{@code hookHansProviderIfNeeded} —— 场景限制 / 恶意行为策略；</li>
 *   <li>{@code hookbeforeInactiveProviderGet} —— 恶意行为策略；</li>
 *   <li>{@code hookPreloadProviderBlock} —— 资源预加载拦截。</li>
 * </ul>
 *
 * <p><b>处理边界（很重要）</b>：只有当
 * 「被拉起的 Provider 属于本模块」<b>且</b>「调用方是系统身份（system 1000 / phone 1001）」
 * 时才返回 false 放行；其余调用一律保持系统原判定，不扩大任何权限。
 *
 * <p><b>HyperOS 上如何</b>：找不到 {@code ContentProviderHelperExtImpl} 这个类，
 * {@link Reflect#findClassIfExists} 返回 null，直接跳过并记一行日志。
 * 小米系统上本类完全不参与，行为与未合入时完全一致。
 *
 * <p><b>进程活着但被 Hans 冻结的情况不用处理</b>：Binder 事务会触发内核侧解冻。
 */
public class ColorOsProviderWake {

    private static final String TAG = "PICKUPDEBUG";
    private static final String EXT_CLASS = ColorOs.GATE_CLASS_NAME;
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
        Class<?> ext = Reflect.findClassIfExists(EXT_CLASS, sysCl);
        if (ext == null) {
            // 非 ColorOS：正常路径，不是错误
            log("WAKE skip: " + EXT_CLASS + " not found (non-ColorOS)");
            return;
        }
        int hooked = 0;
        for (String name : GATES) {
            try {
                hooked += HookRuntime.hookAllMethods(ext, name, GATE_HOOK);
            } catch (Throwable t) {
                log("WAKE hook err " + name + ": " + t);
            }
        }
        log("WAKE OK: " + hooked + " provider gate(s) hooked");
    }

    private static final io.github.libxposed.api.XposedInterface.Hooker GATE_HOOK = chain -> {
        boolean allow = false;
        try {
            java.lang.reflect.Method method = (java.lang.reflect.Method) chain.getExecutable();
            Class<?> ret = method.getReturnType();
            Object[] args = chain.getArgs().toArray();
            if ((ret == boolean.class || ret == Boolean.class) && targetsModule(args)) {
                String caller = callingPackage(args);
                int uid = callingUid(args);
                allow = isSystemCaller(uid, caller);
                if (allow) logAllowed(method.getName(), caller, uid);
            }
        } catch (Throwable t) { log("WAKE gate err: " + t.getClass().getSimpleName()); }
        // Never catch/retry the original call; preserve its result and exception.
        return allow ? Boolean.FALSE : chain.proceed();
    };

    private static boolean targetsModule(Object[] args) {
        if (args == null) return false;
        for (Object a : args) {
            if (a instanceof ProviderInfo) return MODULE_PKG.equals(((ProviderInfo) a).packageName);
        }
        return false;
    }

    private static String callingPackage(Object[] args) {
        if (args == null) return null;
        for (Object a : args) {
            if (a instanceof String) return (String) a;
        }
        return null;
    }

    /** 各拦截方法里 callingUid 都是最后一个 int 参数（前面的 int 是 pid）；无 int 参数返回 -1。 */
    private static int callingUid(Object[] args) {
        int uid = -1;
        if (args != null) {
            for (Object a : args) {
                if (a instanceof Integer) uid = (Integer) a;
            }
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
        ModuleLog.log(msg);
    }
}
