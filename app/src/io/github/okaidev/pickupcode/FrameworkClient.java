package io.github.okaidev.pickupcode;

import android.content.Context;
import android.os.UserManager;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArraySet;

/** Modern module app/framework IPC. Binding proves activation, not a successful parcel capture. */
final class FrameworkClient {
    private static volatile XposedService service;
    private static final CopyOnWriteArraySet<Runnable> listeners = new CopyOnWriteArraySet<>();
    static void init(Context context) {
        Context app = context.getApplicationContext();
        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override public void onServiceBind(XposedService value) {
                service = value;
                try {
                    HookConfig.attach(value.getRemotePreferences(HookConfig.GROUP));
                    UserManager users = app.getSystemService(UserManager.class);
                    if (users != null && users.isUserUnlocked()) ProcessSync.push(app);
                } catch (RuntimeException e) { ModuleLog.log("Framework service init failed: " + e.getClass().getSimpleName()); }
                changed();
            }
            @Override public void onServiceDied(XposedService value) {
                if (service != value) return;
                service = null;
                HookConfig.attach(null);
                changed();
            }
        });
    }
    static boolean connected() {
        XposedService value = service;
        try { return value != null && value.getApiVersion() >= 102; }
        catch (RuntimeException e) { return false; }
    }
    static void configurationChanged() { changed(); }
    static String description() {
        XposedService value = service;
        if (value == null) return "框架服务未连接；请在 LSPosed 启用模块，再重新打开应用";
        try {
            int api = value.getApiVersion();
            if (api < 102) return "当前框架 API " + api + "；" + BuildConfig.VERSION_NAME + " 需要 API 102，请升级框架";
            return value.getFrameworkName() + " " + value.getFrameworkVersion() + " · API " + api
                    + " · 服务已连接；目标进程需重新启动加载新 Hook";
        }
        catch (RuntimeException e) { return "框架服务暂不可用，请重新打开应用"; }
    }
    static List<String> scope() {
        XposedService value = service;
        return value == null ? Collections.emptyList() : value.getScope();
    }
    static void requestScope(List<String> packages, XposedService.OnScopeEventListener callback) {
        XposedService value = service;
        if (value == null) throw new IllegalStateException("Framework service unavailable");
        value.requestScope(packages, callback);
    }
    static void addListener(Runnable listener) { listeners.add(listener); }
    static void removeListener(Runnable listener) { listeners.remove(listener); }
    private static void changed() { for (Runnable listener : listeners) listener.run(); }
}
