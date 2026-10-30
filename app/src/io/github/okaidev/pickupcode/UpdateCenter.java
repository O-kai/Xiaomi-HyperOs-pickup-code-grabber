package io.github.okaidev.pickupcode;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 更新调度中心（v3.1.0）：
 *
 * 【为什么要有这个类】
 * 此前「软件更新」与「规则集更新」是两个互不知情的平行入口：
 *   · 软件：LauncherActivity.onResume 触发（回前台就会判闸门）
 *   · 规则：LauncherActivity.onCreate 触发（只有冷启动才判闸门）
 * 体感就是「规则集好像没有自动化」；而菜单里也是两项分散的入口。
 *
 * 【设计：温和版串行，不短路】
 *   ① force=true（用户手动）→ 忽略「允许联网」总开关，立刻检查；
 *      force=false（自动）   → 先过「允许联网」开关，再各自过 3 天闸门；
 *   ② 先查软件版本，再查规则集（多花一次请求，但用户即使点「下次再说」，
 *      规则也已经悄悄拉到最新——这是温和版相对短路版的唯一价值点）；
 *   ③ 结果合并成一条反馈：
 *        · 有新 APK      → 弹「🔔 发现新版本」对话框
 *        · 规则有更新     → Toast「规则集已更新至 vN ✓」
 *        · 两者都没有     → Toast「✅ 软件 vX 与规则集 vN 均为最新」
 *
 * 【边界】
 * 只做调度与提示，不下载 APK（跳浏览器 Releases 页）、不上传任何数据。
 */
public class UpdateCenter {

    private static final String TAG = "PICKUPDEBUG";
    private static final String PREFS = "dedup";
    private static final String KEY_UPDATE_LAST = "update_last_check";
    private static final String KEY_RULES_LAST = "rules_last_check";
    private static final String KEY_LAST_APP_VER = "last_app_version_code";

    /** 各自的懒检查间隔：3 天（保持原设计，不做定时循环） */
    private static final long INTERVAL_MS = 3L * 24 * 60 * 60 * 1000;

    private static final String LATEST_URL =
            "https://api.github.com/repos/O-kai/Xiaomi-HyperOs-pickup-code-grabber/releases/latest";
    private static final String RELEASES_URL =
            "https://github.com/O-kai/Xiaomi-HyperOs-pickup-code-grabber/releases";

    /** 规则集的两个互相独立的镜像：内容必须一致，否则拒绝更新 */
    private static final String MIRROR_A =
            "https://cdn.jsdelivr.net/gh/O-kai/Xiaomi-HyperOs-pickup-code-grabber@main/rules/rules.json";
    private static final String MIRROR_B =
            "https://raw.githubusercontent.com/O-kai/Xiaomi-HyperOs-pickup-code-grabber/main/rules/rules.json";

    /**
     * 自动检查（懒加载）：受「允许联网」总开关约束，各自 3 天一次。
     * 与 App 冷启动 / 回前台同频调用即可。
     */
    public static void maybeCheck(Activity act) {
        if (act == null) return;
        final Context ctx = act.getApplicationContext();
        if (!NetPolicy.autoNetAllowed(ctx)) return; // 离线模式：自动检查完全停止
        // v3.1.0：刚升级过 APK → 清一次规则检查时间戳，强制下次拉齐最新规则
        clearRulesStampIfUpgraded(ctx);
        long now = System.currentTimeMillis();
        boolean needApp = now - sp(ctx).getLong(KEY_UPDATE_LAST, 0L) >= INTERVAL_MS;
        boolean needRules = now - sp(ctx).getLong(KEY_RULES_LAST, 0L) >= INTERVAL_MS;
        if (!needApp && !needRules) return;
        run(act, false, needApp, needRules, null);
    }

    /**
     * 手动检查：用户主动发起 → 不受「允许联网」开关约束，两项都查。
     */
    public static void checkNow(Activity act, Runnable onComplete) {
        if (act == null) return;
        run(act, true, true, true, onComplete);
    }

    private static void run(final Activity act, final boolean force,
                            final boolean checkApp, final boolean checkRules,
                            final Runnable onComplete) {
        final Context ctx = act.getApplicationContext();
        // v3.1.0：时间戳只在真正拿到结果后才记（见 stampOnSuccess）。
        // 修复前是发起网络请求【之前】就写入，于是断网点一次手动检查
        // → 之后 3 天内自动检查都认为「刚查过」，用户恢复了网络也得干等。

        new Thread(() -> {
            // ① 软件版本
            String latest = null;
            if (checkApp) {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(LATEST_URL).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(10000);
                    c.setRequestProperty("User-Agent", "pickupcode-updater");
                    c.setRequestProperty("Accept", "application/vnd.github+json");
                    if (c.getResponseCode() == 200) {
                        Matcher m = Pattern
                                .compile("\"tag_name\"\\s*:\\s*\"v([0-9]+\\.[0-9]+\\.[0-9]+)\"")
                                .matcher(readAll(c.getInputStream()));
                        if (m.find()) latest = m.group(1);
                    }
                } catch (Throwable ignored) { }
            }

            // ② 规则集（软件即使有新版本也照拉——温和版：用户可以选择不升级软件）
            final int[] rulesResult = {0}; // 0=未查 1=已更新 2=已是最新 3=网络失败 4=完整性未通过
            if (checkRules) {
                rulesResult[0] = fetchRules(ctx);
            }

            final String latestVer = latest;
            final int rr = rulesResult[0];
            // v3.1.0：拿到结果才记时间戳（详见 stampOnSuccess 注释）
            // v4.0：只有「已更新 / 已是最新」才算拿到可用结果；
            // 网络失败与完整性校验失败都不记，下次仍可重试。
            stampOnSuccess(ctx, checkApp, latestVer != null, checkRules, rr == 1 || rr == 2);
            act.runOnUiThread(() -> {
                String cur = currentVersion(ctx);
                boolean hasNewApp = latestVer != null && compareVersions(latestVer, cur) > 0;

                if (hasNewApp) {
                    new AlertDialog.Builder(act)
                            .setTitle("发现新版本 v" + latestVer)
                            .setMessage("当前版本：v" + cur
                                    + "\n最新版本：v" + latestVer
                                    + "\n\n更新内容见 Releases 页说明；下载安装后记得在 LSPosed 里确认模块仍已启用。")
                            .setPositiveButton("打开下载页", (d, w) -> {
                                try {
                                    act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL)));
                                } catch (Throwable ignored) { }
                            })
                            .setNegativeButton("下次再说", null)
                            .show();
                }

                // 规则结果提示（有 APK 弹窗时，Toast 仍照常给出规则结论）
                if (checkRules && force) {
                    if (rr == 1) {
                        toast(ctx, "规则集已更新至 v" + PickupExtractor.getActiveRulesVersion() + " ✓");
                    } else if (rr == 2) {
                        toast(ctx, "软件" + (hasNewApp ? "可升级至 v" + latestVer : "已是最新 v" + cur)
                                + "，规则集已是最新（v" + PickupExtractor.getActiveRulesVersion() + "）");
                    } else if (rr == 3) {
                        toast(ctx, "网络不可用，未获取到最新规则");
                    } else if (rr == 4) {
                        toast(ctx, "规则集完整性校验未通过，已拒绝更新\n"
                                + "两个镜像内容不一致，可能是网络被劫持或文件已损坏");
                    }
                } else if (force && !hasNewApp && latestVer == null) {
                    toast(ctx, "检查失败：网络不可用或 GitHub 未响应，稍后再试");
                }

                if (onComplete != null) onComplete.run();
            });
        }).start();
    }

    /**
     * v3.1.0：只有真正拿到了结果才记「上次检查时间」。
     * 网络失败时不记 —— 否则用户在断网时点一次手动检查，
     * 就会把随后 3 天的自动检查一起「锁死」。
     */
    private static void stampOnSuccess(Context ctx, boolean checkApp, boolean gotAppResult,
                                       boolean checkRules, boolean gotRulesResult) {
        long now = System.currentTimeMillis();
        SharedPreferences.Editor e = sp(ctx).edit();
        if (checkApp && gotAppResult) e.putLong(KEY_UPDATE_LAST, now);
        if (checkRules && gotRulesResult) e.putLong(KEY_RULES_LAST, now);
        e.apply();
    }

    /** 拉规则；返回 1=已更新 2=已是最新 3=失败 */
    /**
     * v4.5：拉取最新规则集。改为 public，供首页「官方规则集」徽标点击时直接调用
     * （原先只在内部 run() 里用，用户无法单独触发规则更新）。
     *
     * <p>v4.0 收口：加了完整性校验。两个互相独立的镜像必须给出完全一致的
     * 内容；同版本号的规则必须与 APK 内置的摘要一致。任何一项对不上就
     * <b>拒绝应用</b>并明确告知用户——一份被改坏的规则可能让提取引擎
     * 把错误内容当成取件码写进待办。
     *
     * @return 0=未查 1=已更新 2=已是最新 3=网络失败 4=完整性校验未通过（已拒绝）
     */
    public static int fetchRules(Context ctx) {
        String fromA = fetchFrom(MIRROR_A);
        String fromB = fetchFrom(MIRROR_B);

        int fetchedVersion = -1;
        String probe = fromA != null ? fromA : fromB;
        if (probe != null) {
            try {
                fetchedVersion = ExtractorRules.fromJson(probe).version;
            } catch (Throwable ignored) {
                fetchedVersion = -1;
            }
        }

        int integrity = RuleIntegrity.verify(fromA, fromB, bundledRulesHash(ctx),
                fetchedVersion, PickupExtractor.getActiveRulesVersion());
        if (integrity == RuleIntegrity.NO_CONTENT) return 3;
        if (integrity == RuleIntegrity.MIRROR_MISMATCH) {
            Log.w(TAG, "RULES INTEGRITY: two mirrors disagree, update refused");
            return 4;
        }
        if (integrity == RuleIntegrity.BUNDLED_MISMATCH) {
            Log.w(TAG, "RULES INTEGRITY: same version but different hash, update refused");
            return 4;
        }
        if (integrity == RuleIntegrity.SINGLE_SOURCE) {
            Log.w(TAG, "RULES INTEGRITY: only one mirror reachable, cross-check skipped");
        }

        String fetched = fromA != null ? fromA : fromB;
        if (fetched == null) return 3;
        try {
            ExtractorRules cand = ExtractorRules.fromJson(fetched);
            // minAppVersionCode 是【构建号】语义（数字），必须用 versionCode 比，
            // 不能拿点分版本号去比——那是两套体系。
            int appVer = AppVersion.code(ctx);
            if (appVer > 0 && cand.minAppVersionCode > appVer) return 2; // 不兼容，静默跳过
            if (RuleIntegrity.isNewer(cand.version, PickupExtractor.getActiveRulesVersion())) {
                if (PickupExtractor.applyNewRules(ctx, fetched)) {
                    try { ProcessSync.push(ctx); } catch (Throwable ignored) { }
                    return 1;
                }
            }
            return 2;
        } catch (Throwable t) {
            return 3;
        }
    }

    /** 从单个镜像拉一份文本（失败返回 null） */
    private static String fetchFrom(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", "PickupCode-UpdateCenter");
            if (c.getResponseCode() == 200) {
                String text = readAll(c.getInputStream());
                if (text != null && !text.trim().isEmpty()) return text;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /** APK 内置规则文件的摘要（构建期生成，用于同版本锚定） */
    private static String bundledRulesHash(Context ctx) {
        try {
            InputStream in = ctx.getAssets().open("rules/rules.sha256");
            if (in == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            String hash = RuleIntegrity.parseHashFile(
                    new String(bos.toByteArray(), StandardCharsets.UTF_8));
            return hash.isEmpty() ? null : hash;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 升级 APK 后清一次规则时间戳，保证「装完新包会拉一次最新规则」 */
    private static void clearRulesStampIfUpgraded(Context ctx) {
        try {
            // versionCode 只用于「App 是不是换了」这一件事：它单调递增，
            // 装新包必然变大，所以变化即代表升级。
            int ver = AppVersion.code(ctx);
            int last = sp(ctx).getInt(KEY_LAST_APP_VER, 0);
            if (ver > 0 && last != ver) {
                sp(ctx).edit()
                        .putInt(KEY_LAST_APP_VER, ver)
                        .putLong(KEY_RULES_LAST, 0L)
                        .apply();
            }
        } catch (Throwable ignored) { }
    }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String currentVersion(Context ctx) {
        // v4.0.1：点分版本号是唯一事实源，版本比较也统一走它
        return AppVersion.dotted(ctx);
    }

    /**
     * 比较两个版本号（如 3.9.4 与 4.0.0）。
     *
     * <p>v4.0.1：实现搬到 {@link AppVersion#compare}，与版本号解析同源。
     * 原先这里是另一份手写实现——展示与比较两套解析，改格式时容易只改一边。
     */
    private static int compareVersions(String a, String b) {
        return AppVersion.compare(a, b);
    }

    private static void toast(Context ctx, String s) {
        try { Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) { }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
