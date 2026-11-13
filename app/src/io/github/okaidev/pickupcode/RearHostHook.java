package io.github.okaidev.pickupcode;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Set;

/** OS4 host support-list gate. Only our package is admitted; other apps retain the original check. */
final class RearHostHook {
    static final String HOST = "com.xiaomi.subscreencenter";
    // Runs only inside the LSPosed-injected rear host, where framework methods are hook targets.
    @android.annotation.SuppressLint("BlockedPrivateApi")
    static void install(ClassLoader loader) {
        try {
            Class<?> type = Reflect.findClass("A2.g", loader);
            Method method = type.getDeclaredMethod("k", String.class, Set.class, Map.class);
            if (method.getReturnType() != boolean.class || !Modifier.isStatic(method.getModifiers()))
                throw new NoSuchMethodException("OS4 rear app gate signature changed");
            HookRuntime.intercept(method, hooker());
            ModuleLog.log("REAR own-package host gate installed");
        } catch (Throwable t) {
            ModuleLog.log("REAR host gate unavailable: " + t.getClass().getSimpleName());
        }
        try {
            Method start = android.widget.RemoteViews.class.getDeclaredMethod("startPendingIntent",
                    android.view.View.class, android.app.PendingIntent.class, android.util.Pair.class);
            HookRuntime.intercept(start, openOnMainScreen());
            ModuleLog.log("REAR own-package main display click hook installed");
        } catch (Throwable t) {
            ModuleLog.log("REAR main display click hook unavailable: " + t.getClass().getSimpleName());
        }
    }
    static io.github.libxposed.api.XposedInterface.Hooker openOnMainScreen() {
        return chain -> {
            android.app.PendingIntent pending = (android.app.PendingIntent) chain.getArg(1);
            android.util.Pair<?, ?> launch = (android.util.Pair<?, ?>) chain.getArg(2);
            if (android.os.Build.VERSION.SDK_INT >= 31
                    && pending != null && allows(pending.getCreatorPackage()) && pending.isActivity()
                    && launch != null && launch.second instanceof android.app.ActivityOptions) {
                android.app.ActivityOptions options = (android.app.ActivityOptions) launch.second;
                options.setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY);
                if (android.os.Build.VERSION.SDK_INT >= 34) options.setPendingIntentBackgroundActivityStartMode(
                        android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                ModuleLog.log("REAR opening own activity on main display");
            }
            return chain.proceed();
        };
    }
    static io.github.libxposed.api.XposedInterface.Hooker hooker() {
        return chain -> allows(chain.getArg(0)) ? true : chain.proceed();
    }
    static boolean allows(Object pkg) { return RuntimePaths.PKG.equals(pkg); }
}
