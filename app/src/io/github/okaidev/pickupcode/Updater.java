package io.github.okaidev.pickupcode;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 检查更新（v2.5.1 新增）：
 *  - 本应用唯一的联网行为：每 3 天最多 1 次访问 GitHub Releases API 查询最新版本号；
 *  - 不下载 APK、不上传任何数据、无统计 SDK；
 *  - 发现新版本 → 弹窗跳转 Releases 页（浏览器下载）；
 *  - 手动入口：右上角「⋮」→「检查更新」（立即检查，无论结果都会提示）。
 */
public class Updater {

    private static final String LATEST_URL =
            "https://api.github.com/repos/O-kai/Xiaomi-HyperOs-pickup-code-grabber/releases/latest";
    private static final String RELEASES_URL =
            "https://github.com/O-kai/Xiaomi-HyperOs-pickup-code-grabber/releases";
    private static final long INTERVAL_MS = 3L * 24 * 60 * 60 * 1000; // 3 天

    /** 自动检查：距上次检查不足 3 天则静默跳过；force=true 立即检查并提示结果 */
    public static void maybeCheck(Activity act, boolean force) {
        final Context ctx = act.getApplicationContext();
        long last = ctx.getSharedPreferences("dedup", Context.MODE_PRIVATE)
                .getLong("update_last_check", 0L);
        if (!force && System.currentTimeMillis() - last < INTERVAL_MS) return;
        ctx.getSharedPreferences("dedup", Context.MODE_PRIVATE).edit()
                .putLong("update_last_check", System.currentTimeMillis()).apply();
        new Thread(() -> {
            String remote = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(LATEST_URL).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(10000);
                c.setRequestProperty("User-Agent", "pickupcode-updater");
                c.setRequestProperty("Accept", "application/vnd.github+json");
                if (c.getResponseCode() == 200) {
                    String body = readAll(c.getInputStream());
                    Matcher m = Pattern
                            .compile("\"tag_name\"\\s*:\\s*\"v([0-9]+\\.[0-9]+\\.[0-9]+)\"")
                            .matcher(body);
                    if (m.find()) remote = m.group(1);
                }
            } catch (Throwable ignored) { }
            final String latest = remote;
            act.runOnUiThread(() -> {
                if (latest == null) {
                    if (force) toast(act, "检查失败：网络不可用或 GitHub 未响应，稍后再试");
                    return;
                }
                String cur = currentVersion(ctx);
                if (compareVersions(latest, cur) > 0) {
                    new AlertDialog.Builder(act)
                            .setTitle("🔔 发现新版本 v" + latest)
                            .setMessage("当前版本：v" + cur
                                    + "\n最新版本：v" + latest
                                    + "\n\n更新内容见 Releases 页说明；下载安装后记得在 LSPosed 里确认模块仍已启用。")
                            .setPositiveButton("打开下载页", (d, w) -> {
                                try {
                                    act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL)));
                                } catch (Throwable ignored) { }
                            })
                            .setNegativeButton("下次再说", null)
                            .show();
                } else if (force) {
                    toast(act, "已是最新版本 v" + cur + " ✓");
                }
            });
        }).start();
    }

    private static String currentVersion(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName;
        } catch (Throwable t) {
            return "0.0.0";
        }
    }

    /** 比较 a.b.c 版本号；a 较新返回正数 */
    private static int compareVersions(String a, String b) {
        try {
            String[] sa = a.split("\\.");
            String[] sb = b.split("\\.");
            int n = Math.max(sa.length, sb.length);
            for (int i = 0; i < n; i++) {
                int va = i < sa.length ? Integer.parseInt(sa[i].trim()) : 0;
                int vb = i < sb.length ? Integer.parseInt(sb[i].trim()) : 0;
                if (va != vb) return va - vb;
            }
        } catch (Throwable ignored) { }
        return 0;
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void toast(Context ctx, String s) {
        Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show();
    }
}
