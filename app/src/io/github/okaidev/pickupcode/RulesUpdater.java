package io.github.okaidev.pickupcode;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 规则热更新调度器（v2.8.0）：
 * 负责从远程 CDN 与 GitHub 镜像定时/手动拉取最新的 rules.json。
 * 遵循多源降级、端侧沙箱冒烟门禁、全静默自愈设计。
 */
public class RulesUpdater {

    private static final String TAG = "PICKUPDEBUG";
    private static final String PREFS = "dedup";
    private static final String KEY_LAST_CHECK = "rules_last_check";

    // 优先使用 jsDelivr CDN 加速（大陆友好），次选 GitHub Raw 兜底
    private static final String[] RULES_URLS = {
            "https://cdn.jsdelivr.net/gh/O-kai/Xiaomi-HyperOs-pickup-code-grabber@main/rules/rules.json",
            "https://raw.githubusercontent.com/O-kai/Xiaomi-HyperOs-pickup-code-grabber/main/rules/rules.json"
    };

    // 默认检查间隔：3 天（259200 秒）
    private static final long CHECK_INTERVAL_MS = 3L * 24 * 60 * 60 * 1000;

    /**
     * 自动静默检查：与 App 启动/前台联动，不足 3 天直接跳过。
     * v2.8.0：受「允许联网」总开关约束——用户关闭后自动热更新完全停止（离线模式）。
     */
    public static void maybeCheck(Context ctx) {
        if (ctx == null) return;
        if (!NetPolicy.autoNetAllowed(ctx)) return; // 离线模式：不做任何自动联网
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long last = sp.getLong(KEY_LAST_CHECK, 0L);
        long now = System.currentTimeMillis();
        if (now - last < CHECK_INTERVAL_MS) {
            return;
        }
        sp.edit().putLong(KEY_LAST_CHECK, now).apply();
        checkUpdate(ctx, false, null);
    }

    /**
     * 手动触发更新检查（带 UI 回调提示）
     */
    public static void checkUpdate(Context ctx, boolean force, Runnable onComplete) {
        if (ctx == null) return;
        new Thread(() -> {
            boolean updated = false;
            String fetchedJson = null;

            for (String urlStr : RULES_URLS) {
                try {
                    HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(10000);
                    conn.setRequestProperty("User-Agent", "PickupCode-RulesUpdater");
                    if (conn.getResponseCode() == 200) {
                        fetchedJson = readStream(conn.getInputStream());
                        if (fetchedJson != null && !fetchedJson.trim().isEmpty()) {
                            break;
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "fetch rules failed from " + urlStr + ": " + t);
                }
            }

            if (fetchedJson != null) {
                try {
                    ExtractorRules candidate = ExtractorRules.fromJson(fetchedJson);
                    // v2.9.1 安全闸门（可用性/稳定性铁律）：
                    // 1) 候选规则的 minAppVersionCode 大于当前 App 版本 → 该规则为新引擎设计，老 App 直接忽略（不应用、不覆盖本地）；
                    // 2) 版本号不增 → 不动；
                    // 3) applyNewRules 内部还有端侧冒烟门禁兜底（任何一条用例失败即弃用回退）。
                    int appVer;
                    try {
                        appVer = ctx.getPackageManager()
                                .getPackageInfo(ctx.getPackageName(), 0).versionCode;
                    } catch (Throwable t) {
                        appVer = Integer.MAX_VALUE; // 取不到版本号时保守放行（仅本机调试场景）
                    }
                    if (candidate.minAppVersionCode > appVer) {
                        Log.w(TAG, "RulesUpdater: candidate v" + candidate.version
                                + " requires app >= " + candidate.minAppVersionCode
                                + ", current " + appVer + " — skip (incompatible)");
                    } else {
                        int curVer = PickupExtractor.getActiveRulesVersion();
                        if (candidate.version > curVer) {
                            boolean ok = PickupExtractor.applyNewRules(ctx, fetchedJson);
                            if (ok) {
                                updated = true;
                                Log.i(TAG, "RulesUpdater: successfully upgraded rules to v" + candidate.version);
                            }
                        }
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "parse candidate rules err: " + t);
                }
            }

            final boolean isUpdated = updated;
            final String finalJson = fetchedJson;

            if (force) {
                android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
                handler.post(() -> {
                    if (isUpdated) {
                        Toast.makeText(ctx, "规则集已更新至最新 v" + PickupExtractor.getActiveRulesVersion() + " ✓", Toast.LENGTH_SHORT).show();
                    } else if (finalJson != null) {
                        Toast.makeText(ctx, "规则集已是最新（v" + PickupExtractor.getActiveRulesVersion() + "）", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(ctx, "网络连接失败，未获取到最新规则", Toast.LENGTH_SHORT).show();
                    }
                    if (onComplete != null) onComplete.run();
                });
            } else {
                if (onComplete != null) {
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(onComplete);
                }
            }
        }).start();
    }

    private static String readStream(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[2048];
        int n;
        while ((n = is.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
