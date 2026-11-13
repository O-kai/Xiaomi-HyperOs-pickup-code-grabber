package io.github.okaidev.pickupcode;

import android.util.Log;
import io.github.libxposed.api.XposedModule;
import java.util.Set;

/** Modern API 102 entry. Each target is installed once per process/classloader. */
public final class XposedEntry extends XposedModule {
    private final java.util.Map<ClassLoader, Set<String>> installed = new java.util.IdentityHashMap<>();
    private String processName;
    private boolean systemServer;

    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        processName = param.getProcessName();
        systemServer = param.isSystemServer();
        ModuleLog.framework = message -> log(Log.INFO, "PICKUPDEBUG", message);
        HookRuntime.init(this);
        try { HookConfig.attach(getRemotePreferences(HookConfig.GROUP)); }
        catch (Throwable t) { ModuleLog.log("Framework preferences unavailable: " + t.getClass().getSimpleName()); }
        if (!systemServer) {
            try { HookRuntime.captureApplication(); }
            catch (Throwable t) { ModuleLog.log("Application context hook failed: " + t.getClass().getSimpleName()); }
        }
        ModuleLog.log("API102 module loaded process=" + processName + " systemServer=" + systemServer);
    }

    @Override public synchronized void onPackageReady(PackageReadyParam param) {
        if (systemServer) return;
        String pkg = param.getPackageName();
        ClassLoader loader = param.getClassLoader();
        if (!isTarget(pkg) || !installed.computeIfAbsent(loader, ignored -> new java.util.HashSet<>()).add(pkg)) return;
        ModuleLog.log("onPackageReady: pkg=" + pkg + " process=" + processName);
        if (RearHostHook.HOST.equals(pkg)) RearHostHook.install(loader);
        else if (AppParcelData.supports(pkg)) AppParcelHook.install(pkg, loader);
        else if ("com.android.providers.telephony".equals(pkg)) hookSmsProvider(loader);
        else if ("com.android.phone".equals(pkg)) hookTelephony(loader);
        else if (Honor.PKG_MMS.equals(pkg)) hookHonorMms(loader);
        else hookMmsApp(loader);
    }

    @Override public void onSystemServerStarting(SystemServerStartingParam param) {
        ModuleLog.log("onSystemServerStarting: pkg=system process=" + processName);
        ClassLoader loader = param.getClassLoader();
        hookTelephony(loader);
        NotiHook.install(loader);
        ColorOsProviderWake.install(loader);
    }

    private static boolean isTarget(String pkg) {
        return RearHostHook.HOST.equals(pkg) || AppParcelData.supports(pkg) || "com.android.mms".equals(pkg)
                || Honor.PKG_MMS.equals(pkg) || "com.android.phone".equals(pkg)
                || "com.android.providers.telephony".equals(pkg);
    }

    private static int observe(ClassLoader loader, String cls, String method, boolean before,
                               HookRuntime.Observer observer) {
        try {
            int count = HookRuntime.hookAllMethods(Reflect.findClass(cls, loader), method,
                    before ? HookRuntime.before(observer) : HookRuntime.after(observer));
            ModuleLog.log("SMS hook " + cls + "#" + method + " count=" + count);
            return count;
        } catch (Throwable t) {
            ModuleLog.log("SMS hook unavailable " + cls + "#" + method + ": " + t.getClass().getSimpleName());
            return 0;
        }
    }

    private static void hookSmsProvider(ClassLoader loader) {
        for (String cls : new String[]{"com.android.providers.telephony.SmsProvider",
                "com.android.providers.telephony.MiuiTelephonyProviderImpl"}) {
            observe(loader, cls, "insert", false, (receiver, args, result) -> {
                if (result != null) SmsBridge.processProviderInsert(args);
            });
            observe(loader, cls, "bulkInsert", false, (receiver, args, result) -> {
                if (result instanceof Integer && (Integer) result > 0) SmsBridge.processProviderBulkInsert(args);
            });
        }
    }

    private static void hookMmsApp(ClassLoader loader) {
        String service = "com.android.mms.transaction.SmsReceiverService";
        for (String method : new String[]{"onReceive", "handleSmsMessage", "handleSmsReceived"}) {
            observe(loader, service, method, !"onReceive".equals(method),
                    (receiver, args, result) -> SmsBridge.processArguments(args));
        }
        for (String cls : new String[]{"com.android.mms.transaction.SmsReceiver",
                "com.android.mms.transaction.PrivilegedSmsReceiver",
                "com.android.mms.transaction.HighPrivilegedSmsReceiver",
                "com.android.mms.transaction.BeidouSmsReceiver",
                "com.android.mms.receiver.SmsReceiver", "com.android.mms.receiver.SmsDeliverReceiver",
                "com.oplus.mms.datamodel.SmsReceivedReceiver"}) receiver(loader, cls);
    }

    private static void hookHonorMms(ClassLoader loader) {
        for (String cls : Honor.MMS_RECEIVER_CANDIDATES) receiver(loader, cls);
        for (String cls : new String[]{"com.android.mms.transaction.SmsReceiverService",
                "com.hihonor.mms.transaction.SmsReceiverService"}) {
            observe(loader, cls, "handleSmsMessage", true,
                    (receiver, args, result) -> SmsBridge.processArguments(args));
        }
    }

    private static void hookTelephony(ClassLoader loader) {
        receiver(loader, "com.android.internal.telephony.InboundSmsHandler$SmsBroadcastReceiver");
        receiver(loader, "com.android.internal.telephony.SmsBroadcastReceiver");
    }

    private static void receiver(ClassLoader loader, String cls) {
        observe(loader, cls, "onReceive", false, (receiver, args, result) -> SmsBridge.processArguments(args));
    }
}
