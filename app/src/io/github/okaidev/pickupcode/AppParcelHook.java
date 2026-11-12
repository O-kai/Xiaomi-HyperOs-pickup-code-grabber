package io.github.okaidev.pickupcode;

import android.app.AndroidAppHelper;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Observes completed data loads. Does not read Views or consume network streams. */
public final class AppParcelHook {
    private static final Uri PROVIDER = Uri.parse("content://io.github.okaidev.pickupcode.provider");
    private static final ThreadPoolExecutor IO = new ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), r -> {
                Thread t = new Thread(r, "pickup-app-data");
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.AbortPolicy());
    // Accessed only by the single worker; failed deliveries are never added.
    private static final LinkedHashMap<String, Long> DELIVERED = new LinkedHashMap<>();
    private static long lastQueueLog;
    private static boolean enabled;
    private static long nextConfigCheck;
    private static final java.util.Set<String> LOGGED_FAILURES = java.util.Collections.synchronizedSet(
            new java.util.HashSet<>());

    private AppParcelHook() { }

    public static void install(String pkg, ClassLoader loader) {
        if (AppParcelData.CAINIAO.equals(pkg)) {
            hook(loader, "com.cainiao.wireless.packagelist.manager.PackageListJsManager",
                    "packageListDataChange", new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (!param.hasThrowable() && param.args.length == 1 && param.args[0] instanceof String) {
                                String data = (String) param.args[0];
                                if (data.length() <= AppParcelData.MAX_PAYLOAD) enqueue(pkg, () -> {
                                    List<AppParcelData.Event> events = AppParcelData.cainiaoHomepage(data);
                                    if (!events.isEmpty()) XposedEntry.log("APP-DATA homepage candidates=" + events.size());
                                    return events;
                                });
                            }
                        }
                    });
            // This data-layer utility persists proprietary arrival pushes as AGENT_SIGN
            // parcels. Read only the incoming item, never its multi-account cache.
            hook(loader, "com.cainiao.wireless.packagelist.util.c", "a", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.hasThrowable() || param.args.length != 2 || param.args[1] == null) return;
                        Object item = param.args[1];
                        String type = item.getClass().getName();
                        String codeField;
                        String placeField;
                        if ("tb".equals(type)) {
                            codeField = "ciR";
                            placeField = "ciS";
                        } else if ("tc".equals(type) || "td".equals(type)) {
                            codeField = "authCode";
                            placeField = "stationName";
                        } else return;
                        Object code = XposedHelpers.getObjectField(item, codeField);
                        Object place = XposedHelpers.getObjectField(item, placeField);
                        if (!(code instanceof String) || !AppParcelData.validCode(((String) code).trim())) return;
                        AppParcelData.Event event = new AppParcelData.Event(((String) code).trim(),
                                place instanceof String ? (String) place : null);
                        enqueue(pkg, () -> java.util.Collections.singletonList(event));
                    } catch (Throwable t) { logFailure("Cainiao arrival push", t); }
                }
            });
            hook(loader, "mtopsdk.mtop.domain.MtopResponse", "parseJsonByte", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.hasThrowable()) return;
                        String api = (String) XposedHelpers.getObjectField(param.thisObject, "api");
                        Object status = XposedHelpers.getObjectField(param.thisObject, "retCode");
                        if (api == null || !api.startsWith("mtop.cainiao.") || !"SUCCESS".equals(status)) return;
                        Object data = XposedHelpers.getObjectField(param.thisObject, "dataJsonObject");
                        byte[] bytes = (byte[]) XposedHelpers.getObjectField(param.thisObject, "bytedata");
                        if (!(data instanceof JSONObject) || bytes == null || bytes.length > AppParcelData.MAX_PAYLOAD) return;
                        enqueue(pkg, () -> AppParcelData.cainiaoResponse(data.toString()));
                    } catch (Throwable t) { logFailure("Cainiao response", t); }
                }
            });
            for (String manager : new String[]{"PickUpJsManager", "NewPickUpJsManager"}) {
                hook(loader, "com.cainiao.wireless.pickup.bifrost." + manager,
                        "onPickUpPageDataChange", new XC_MethodHook() {
                            @Override protected void afterHookedMethod(MethodHookParam param) {
                                if (!param.hasThrowable() && param.args.length == 1 && param.args[0] instanceof String) {
                                    String data = (String) param.args[0];
                                    if (data.length() <= AppParcelData.MAX_PAYLOAD) {
                                        enqueue(pkg, () -> AppParcelData.cainiaoPickup(data));
                                    }
                                }
                            }
                        });
            }
        } else if (AppParcelData.PINDUODUO.equals(pkg)) {
            hook(loader, "com.xunmeng.pinduoduo.basekit.http.callback.CommonCallback",
                    "parseResponseStringWrapper", new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (param.hasThrowable()) return;
                                Object response = param.getResult();
                                if (response == null || !"com.xunmeng.pinduoduo.express.entry.CabinetInfo"
                                        .equals(response.getClass().getName())) return;
                                Object result = XposedHelpers.getObjectField(response, "result");
                                if (result == null) return;
                                // Snapshot just these fields; never retain the complete response.
                                JSONObject compact = new JSONObject();
                                for (String field : new String[]{"code", "address", "status", "showQrCodePickUp"}) {
                                    compact.put(field, XposedHelpers.getObjectField(result, field));
                                }
                                String data = new JSONObject().put("result", compact).toString();
                                enqueue(pkg, () -> AppParcelData.pinduoduo(data));
                            } catch (Throwable t) { logFailure("Pinduoduo response", t); }
                        }
                    });
        }
    }

    private static void hook(ClassLoader loader, String cls, String method, XC_MethodHook callback) {
        try {
            int count = XposedBridge.hookAllMethods(XposedHelpers.findClass(cls, loader), method, callback).size();
            XposedEntry.log("APP-DATA hook " + cls + "#" + method + " count=" + count);
        } catch (Throwable t) { logFailure("missing " + cls + "#" + method, t); }
    }

    private interface Extract { List<AppParcelData.Event> get(); }

    private static void enqueue(String pkg, Extract extract) {
        try {
            IO.execute(() -> {
                try {
                    Context context = AndroidAppHelper.currentApplication();
                    if (context == null || !isEnabled(context, pkg)) return;
                    for (AppParcelData.Event event : extract.get()) deliver(context, pkg, event);
                } catch (Throwable t) { logFailure("worker", t); }
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastQueueLog > 60_000L) {
                lastQueueLog = now;
                XposedEntry.log("APP-DATA queue full; data load skipped");
            }
        }
    }

    private static boolean isEnabled(Context context, String pkg) {
        long now = SystemClock.elapsedRealtime();
        if (now >= nextConfigCheck) {
            enabled = false;
            nextConfigCheck = now + 5_000L;
            Bundle result = context.getContentResolver().call(PROVIDER, "appParcelEnabled", pkg, null);
            enabled = result != null && result.getBoolean("enabled", false);
        }
        return enabled;
    }

    private static void deliver(Context context, String pkg, AppParcelData.Event event) {
        long now = SystemClock.elapsedRealtime();
        String key = pkg + '\0' + event.code + '\0' + event.place;
        Long previous = DELIVERED.get(key);
        if (previous != null && now - previous < 30_000L) return;
        Bundle data = new Bundle();
        data.putString("code", event.code);
        data.putString("place", event.place);
        Bundle result = context.getContentResolver().call(PROVIDER, "onAppParcel", pkg, data);
        if (result != null && result.getInt("rc", -1) == 0) {
            DELIVERED.put(key, now);
            if (DELIVERED.size() > 128) DELIVERED.remove(DELIVERED.keySet().iterator().next());
            XposedEntry.log("APP-DATA delivered from " + pkg);
        } else {
            XposedEntry.log("APP-DATA delivery rejected from " + pkg);
        }
    }

    private static void logFailure(String stage, Throwable t) {
        // Exception messages can contain response text or codes.
        if (LOGGED_FAILURES.size() >= 32 || !LOGGED_FAILURES.add(stage + t.getClass().getName())) return;
        XposedEntry.log("APP-DATA " + stage + " failed: " + t.getClass().getSimpleName());
    }

}
