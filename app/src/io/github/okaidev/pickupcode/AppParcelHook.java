package io.github.okaidev.pickupcode;

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
    private static final java.util.Set<String> LOGGED_FAILURES = java.util.Collections.synchronizedSet(
            new java.util.HashSet<>());

    private static final WeakIdentityMap<String> ENDPOINTS = new WeakIdentityMap<>(256);

    private AppParcelHook() { }

    public static void install(String pkg, ClassLoader loader) {
        if (AppParcelData.CAINIAO.equals(pkg)) {
            hook(loader, "com.cainiao.wireless.packagelist.manager.PackageListJsManager",
                    "packageListDataChange", HookRuntime.after((receiver, args, resultValue) -> {
                            if (args.length == 1 && args[0] instanceof String) {
                                String data = (String) args[0];
                                if (data.length() <= AppParcelData.MAX_PAYLOAD) enqueue(pkg, () -> {
                                    List<AppParcelData.Event> events = AppParcelData.cainiaoHomepage(data);
                                    if (!events.isEmpty()) ModuleLog.log("APP-DATA homepage candidates=" + events.size());
                                    return events;
                                });
                            }
                        }));
            // This data-layer utility persists proprietary arrival pushes as AGENT_SIGN
            // parcels. Read only the incoming item, never its multi-account cache.
            hook(loader, "com.cainiao.wireless.packagelist.util.c", "a", HookRuntime.after((receiver, args, resultValue) -> {
                    try {
                        if (args.length != 2 || args[1] == null) return;
                        Object item = args[1];
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
                        Object code = Reflect.getObjectField(item, codeField);
                        Object place = Reflect.getObjectField(item, placeField);
                        if (!(code instanceof String) || !AppParcelData.validCode(((String) code).trim())) return;
                        AppParcelData.Event event = new AppParcelData.Event(((String) code).trim(),
                                place instanceof String ? (String) place : null);
                        enqueue(pkg, () -> java.util.Collections.singletonList(event));
                    } catch (Throwable t) { logFailure("Cainiao arrival push", t); }
                }));
            hook(loader, "mtopsdk.mtop.domain.MtopResponse", "parseJsonByte", HookRuntime.after((receiver, args, resultValue) -> {
                    try {
                        String api = (String) Reflect.getObjectField(receiver, "api");
                        Object status = Reflect.getObjectField(receiver, "retCode");
                        if (api == null || !api.startsWith("mtop.cainiao.") || !"SUCCESS".equals(status)) return;
                        Object data = Reflect.getObjectField(receiver, "dataJsonObject");
                        byte[] bytes = (byte[]) Reflect.getObjectField(receiver, "bytedata");
                        if (!(data instanceof JSONObject) || bytes == null || bytes.length > AppParcelData.MAX_PAYLOAD) return;
                        String snapshot = data.toString();
                        if (snapshot.length() <= AppParcelData.MAX_PAYLOAD)
                            enqueue(pkg, () -> AppParcelData.cainiaoResponse(snapshot));
                    } catch (Throwable t) { logFailure("Cainiao response", t); }
                }));
            for (String manager : new String[]{"PickUpJsManager", "NewPickUpJsManager"}) {
                hook(loader, "com.cainiao.wireless.pickup.bifrost." + manager,
                        "onPickUpPageDataChange", HookRuntime.after((receiver, args, resultValue) -> {
                                if (args.length == 1 && args[0] instanceof String) {
                                    String data = (String) args[0];
                                    if (data.length() <= AppParcelData.MAX_PAYLOAD) {
                                        enqueue(pkg, () -> AppParcelData.cainiaoPickup(data));
                                    }
                                }
                            }));
            }
        } else if (AppParcelData.PINDUODUO.equals(pkg)) {
            // Bind the response callback to these two logistics endpoints only.
            hook(loader, "com.xunmeng.pinduoduo.network_bridge.impl.AMNetworkImpl", "request", HookRuntime.before((receiver, args, resultValue) -> {
                    try {
                        if (args.length < 2 || args[1] == null) return;
                        ENDPOINTS.put(args[1], null);
                        if (!(args[0] instanceof JSONObject)) return;
                        String path = Uri.parse(((JSONObject) args[0]).optString("url")).getPath();
                        if (AppParcelData.PDD_TRACKBOX.equals(path) || AppParcelData.PDD_LIST.equals(path)) {
                            ENDPOINTS.put(args[1], path);
                            ModuleLog.log("APP-DATA PDD logistics request kind="
                                    + (AppParcelData.PDD_TRACKBOX.equals(path) ? "station" : "list"));
                        }
                    } catch (Throwable t) { logFailure("PDD request association", t); }
                }));
            hook(loader, "com.xunmeng.pinduoduo.network_bridge.impl.AMNetworkImpl", "callbackToJS", HookRuntime.after((receiver, args, resultValue) -> {
                    try {
                        if (args.length != 5 || !(args[0] instanceof Integer)
                                || !(args[1] instanceof String) || args[2] == null) return;
                        int status = (Integer) args[0];
                        if (status < 200 || status >= 300) return;
                        Object endpoint = ENDPOINTS.get(args[2]);
                        boolean cabinet = AppParcelData.PDD_TRACKBOX.equals(endpoint);
                        if (!cabinet && !AppParcelData.PDD_LIST.equals(endpoint)) return;
                        ModuleLog.log("APP-DATA PDD logistics response kind=" + (cabinet ? "station" : "list"));
                        // This argument is already decoded. Do not retain headers or mutate the JS callback.
                        String raw = (String) args[1];
                        if (raw.length() <= AppParcelData.MAX_PAYLOAD) enqueue(pkg, () -> cabinet
                                ? AppParcelData.pinduoduoTrackbox(raw) : AppParcelData.pinduoduoList(raw));
                    } catch (Throwable t) { logFailure("PDD logistics response", t); }
                }));
            hook(loader, "com.xunmeng.pinduoduo.basekit.http.callback.CommonCallback",
                    "parseResponseStringWrapper", HookRuntime.after((receiver, args, resultValue) -> {
                            try {
                                Object response = resultValue;
                                if (response == null || !"com.xunmeng.pinduoduo.express.entry.CabinetInfo"
                                        .equals(response.getClass().getName())) return;
                                Object result = Reflect.getObjectField(response, "result");
                                if (result == null) return;
                                // Snapshot just these fields; never retain the complete response.
                                JSONObject compact = new JSONObject();
                                for (String field : new String[]{"code", "address", "status", "showQrCodePickUp"}) {
                                    compact.put(field, Reflect.getObjectField(result, field));
                                }
                                if (compact.optString("address", "").trim().isEmpty()
                                        || compact.isNull("address")) {
                                    try {
                                        Object site = Reflect.getObjectField(result, "cnInOutBound");
                                        if (site != null) compact.put("cnInOutBound", new JSONObject()
                                                .put("address", Reflect.getObjectField(site, "address")));
                                    } catch (NoSuchFieldException ignored) { }
                                }
                                String data = new JSONObject().put("result", compact).toString();
                                enqueue(pkg, () -> AppParcelData.pinduoduo(data));
                            } catch (Throwable t) { logFailure("Pinduoduo response", t); }
                        }));
        }
    }

    private static void hook(ClassLoader loader, String cls, String method, io.github.libxposed.api.XposedInterface.Hooker callback) {
        try {
            int count = HookRuntime.hookAllMethods(Reflect.findClass(cls, loader), method, callback);
            ModuleLog.log("APP-DATA hook " + cls + "#" + method + " count=" + count);
        } catch (Throwable t) { logFailure("missing " + cls + "#" + method, t); }
    }

    private interface Extract { List<AppParcelData.Event> get(); }

    private static void enqueue(String pkg, Extract extract) {
        try {
            IO.execute(() -> {
                try {
                    Context context = HookRuntime.context();
                    if (context == null || !ProcessSync.load().appParcelEnabled) {
                        String state = context == null ? "no context" : "disabled config";
                        if (LOGGED_FAILURES.add("worker " + state)) ModuleLog.log("APP-DATA skipped: " + state);
                        return;
                    }
                    List<AppParcelData.Event> events = extract.get();
                    ModuleLog.log("APP-DATA parsed from " + pkg + " count=" + events.size());
                    for (AppParcelData.Event event : events) deliver(context, pkg, event);
                } catch (Throwable t) { logFailure("worker", t); }
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastQueueLog > 60_000L) {
                lastQueueLog = now;
                ModuleLog.log("APP-DATA queue full; data load skipped");
            }
        }
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
            ModuleLog.log("APP-DATA delivered from " + pkg);
        } else {
            ModuleLog.log("APP-DATA delivery rejected from " + pkg);
        }
    }

    private static void logFailure(String stage, Throwable t) {
        // Exception messages can contain response text or codes.
        if (LOGGED_FAILURES.size() >= 32 || !LOGGED_FAILURES.add(stage + t.getClass().getName())) return;
        ModuleLog.log("APP-DATA " + stage + " failed: " + t.getClass().getSimpleName());
    }

}
