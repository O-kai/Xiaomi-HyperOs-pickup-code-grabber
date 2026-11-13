package io.github.okaidev.pickupcode;

import android.app.Application;
import android.content.Context;
import java.lang.reflect.Method;
import io.github.libxposed.api.XposedInterface;

/** API 102 interceptors. Chains never escape the synchronous interception call. */
final class HookRuntime {
    interface Observer { void observe(Object receiver, Object[] args, Object result) throws Throwable; }
    private static XposedInterface framework;
    private static volatile Context context;
    static void init(XposedInterface value) { framework = value; }
    static void intercept(Method method, XposedInterface.Hooker hooker) {
        framework.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(hooker);
    }
    static Context context() { return context; }
    static void captureApplication() throws Throwable {
        Method attach = Application.class.getDeclaredMethod("attach", Context.class);
        framework.hook(attach).intercept(after((receiver, args, result) -> context = (Application) receiver));
    }
    static XposedInterface.Hooker after(Observer observer) {
        return chain -> {
            Object result = chain.proceed(); // Original failures propagate; observers run only on success.
            observe(observer, chain.getThisObject(), chain.getArgs().toArray(), result);
            return result;
        };
    }
    static XposedInterface.Hooker before(Observer observer) {
        return chain -> {
            observe(observer, chain.getThisObject(), chain.getArgs().toArray(), null);
            return chain.proceed();
        };
    }
    private static void observe(Observer observer, Object receiver, Object[] args, Object result) {
        try { observer.observe(receiver, args, result); }
        catch (Throwable t) { ModuleLog.log("Hook observer failed: " + t.getClass().getSimpleName()); }
    }
    static int hookAllMethods(Class<?> type, String name, XposedInterface.Hooker hooker) {
        int count = 0;
        for (Method method : type.getDeclaredMethods()) {
            if (!method.getName().equals(name)) continue;
            framework.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(hooker);
            count++;
        }
        return count;
    }
}
