package io.github.okaidev.pickupcode;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 诊断与报告（v2.1.2 新增）：
 *  1) healthCheck：四项部署体检（LSPosed 注入 / root / sqlite3 / 笔记库），逐项给结论与修复指引
 *  2) collectReport：一步生成完整诊断报告（设备信息 + 体检 + 写库失败原因 + PICKUPDEBUG 日志
 *     [root 提取，含被 Hook 进程] + LSPosed modules.log + 注入进程清单）
 *  3) sanitize：报告自动脱敏（手机号 / 取件码）
 *  4) export：保存到 Download/pickup_diag_*.txt 并拉起系统分享
 *
 * 隐私约束：报告不读取、不包含短信正文与笔记内容；仅结构级探针（count / 表名 / 日志行），
 * 且对日志中的手机号、取件码做正则脱敏。
 */
public class Diagnostics {

    private static final String TAG = "PICKUPDEBUG";
    private static final String LIBS = "/data/local/tmp/pickup_sqlite/lib";
    private static final String SQLITE = "/data/local/tmp/pickup_sqlite/sqlite3";

    private static final Pattern P_PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern P_CODE = Pattern.compile("\\b\\d{1,4}-\\d{1,2}-\\d{1,5}\\b");
    private static final String PHONE_MASK = "138****8888";

    /** 单条体检结论 */
    public static class Check {
        public final String title;   // 项目名
        public final boolean ok;     // 是否通过
        public final String detail;  // 详情 / 修复指引
        public Check(String title, boolean ok, String detail) {
            this.title = title; this.ok = ok; this.detail = detail;
        }
    }

    // ==================== 体检 ====================

    /** 六项体检（含 su 执行，调用方需放到线程里）；v2.2.0 加入作用域与通知检测 */
    public static List<Check> healthCheck(Context ctx) {
        List<Check> out = new ArrayList<>();
        out.add(checkLsposedInject(ctx));
        Check su = checkRoot();
        out.add(su);
        out.add(checkScope(ctx, su.ok));                    // v2.2.0：作用域精确比对
        out.add(checkNotification(ctx));                    // v2.2.0：通知权限
        if (su.ok) {
            out.add(checkSqlite3());
            out.add(checkNotesDb(ctx));
        } else {
            out.add(new Check("sqlite3 部署", false, "需要先有 root 才能检测（见上一项）"));
            out.add(new Check("待办库访问", false, "需要先有 root 才能检测（见上一项）"));
        }
        return out;
    }

    /** 供 Repair 调用的 su 执行器（多路径：HyperOS 的 su 常不在应用 PATH） */
    public static String[] suExecPublic(String cmd, int timeoutSec) {
        String[] suBins = {"su", "/product/bin/su", "/system/bin/su", "/sbin/su", "/su/bin/su"};
        String[] last = null;
        for (String suBin : suBins) {
            String[] r = suExec(suBin, cmd, timeoutSec);
            if (r != null && r[0] != null && !r[0].trim().isEmpty()) return r;
            if (r != null) last = r;
        }
        return last;
    }

    /** 1) LSPosed 是否把模块加载进了模块自身（scope 勾选 + 重启的标志） */
    private static Check checkLsposedInject(Context ctx) {
        File flag = new File(ctx.getFilesDir(), "pickup_injected.flag");
        if (flag.exists()) {
            long age = System.currentTimeMillis() - flag.lastModified();
            String when = age < 60000 ? "刚刚" : (age / 60000) + " 分钟前";
            return new Check("LSPosed 注入", true,
                    "模块已被 LSPosed 加载（" + when + "）");
        }
        return new Check("LSPosed 注入", false,
                "未检测到注入标记 → 到 LSPosed 勾选本模块并【重启手机】；"
                        + "重启后打开本 App 一次再复检");
    }

    /** 3.5) 作用域精确比对（v2.2.0）：root 读 LSPosed 配置库，缺哪项说哪项 */
    private static Check checkScope(Context ctx, boolean rootOk) {
        if (!rootOk) {
            return new Check("LSPosed 作用域", false, "需要 root 才能自动核对（见上一项）");
        }
        Repair.LsposedStatus st = Repair.lsposedStatus(ctx);
        if (!st.dbReadable) {
            return new Check("LSPosed 作用域", false,
                    "无法读取 LSPosed 配置（" + st.detail + "）；请手动在 LSPosed → 模块 →"
                            + " 取件码助手 勾选 5 项作用域（⚠️ 必含「Android 系统/android」）");
        }
        if (!st.moduleEnabled) {
            return new Check("LSPosed 作用域", false,
                    "模块在 LSPosed 中未启用 → LSPosed 管理器 → 模块 → 勾选「取件码助手」，然后重启手机");
        }
        if (st.missingScope == null) {
            String extra = st.hasStaleSystem
                    ? "（发现勾了「系统框架/system」——它无效，请直接在 LSPosed 作用域里删除这一项）"
                    : "";
            return new Check("LSPosed 作用域", true,
                    "必需目标齐全 ✓ " + st.scopeList + extra);
        }
        boolean missAndroid = st.missingScope.contains("android");
        return new Check("LSPosed 作用域", false,
                "缺少必需作用域：" + st.missingScope
                        + " → LSPosed 管理器 → 模块 → 取件码助手 → 作用域补勾后【重启手机】；"
                        + (missAndroid
                            ? "⚠️ android 在列表底部「Android 系统」——该条目【不带推荐角标】也必须勾选；"
                              + "带角标的 5 个推荐应用不含它，别勾成中部的「系统框架（system）」；"
                            : "")
                        + "当前：" + (st.scopeList.isEmpty() ? "(空)" : st.scopeList));
    }

    /** 3.6) 通知权限（v2.2.0）：Android 13+ POST_NOTIFICATIONS */
    private static Check checkNotification(Context ctx) {
        if (android.os.Build.VERSION.SDK_INT < 33) {
            return new Check("通知权限", true, "Android 12 及以下默认允许");
        }
        boolean granted = ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (granted) return new Check("通知权限", true, "已授权 ✓");
        return new Check("通知权限", false,
                "未授权 → 系统设置 → 应用 → 取件码助手 → 通知，允许（否则收不到取件码弹窗）");
    }

    /** 2) root：任一 su 路径可拿到 uid=0 */
    private static Check checkRoot() {
        String[] suBins = {"su", "/product/bin/su", "/system/bin/su", "/sbin/su", "/su/bin/su"};
        for (String suBin : suBins) {
            String[] r = suExec(suBin, "id", 20);
            if (r != null && r[0] != null && r[0].contains("uid=0")) {
                String who = r[0].contains("uid=0(root)") ? "root" : r[0].trim();
                return new Check("root 授权（" + suBin + "）", true, "uid=0 ✓ " + who);
            }
        }
        return new Check("root 授权", false,
                "所有 su 路径不可用或被拒绝 → 确认 Magisk 已安装；"
                        + "若弹了授权框请选【允许】并勾选\"不再询问\"");
    }

    /** 3) sqlite3 二进制就位 */
    private static Check checkSqlite3() {
        String[] r = suExec("su", "ls -l " + SQLITE, 15);
        String all = (r == null ? "" : nz(r[0]) + nz(r[1]));
        if (all.contains("No such file")) {
            return new Check("sqlite3 部署", false,
                    SQLITE + " 不存在 → 点下方「🚀 一键部署 sqlite3」自动完成（无需 adb/Termux）");
        }
        if (all.contains("sqlite3")) {
            return new Check("sqlite3 部署", true, SQLITE + " 存在 ✓");
        }
        return new Check("sqlite3 部署", false, "探测失败：" + tail(all, 120));
    }

    private static String nz(String s) { return s == null ? "" : s; }

    /** 4) 待办库可打开（只读 count 探针，不读内容）；v2.7.0 起按 NotesBackend 后端分派 */
    private static Check checkNotesDb(Context ctx) {
        String db = NotesBackend.dbPath(ctx);
        String backend = NotesBackend.detect(ctx);
        String table = NotesBackend.BACKEND_COLOROS_TODO.equals(backend) ? "Tasks"
                : NotesBackend.BACKEND_COLOROS_NOTE.equals(backend) ? "rich_notes" : "todo";
        String dbFile = db.substring(db.lastIndexOf('/') + 1);
        String needHint = NotesBackend.BACKEND_COLOROS_TODO.equals(backend) ? "在日历 App 里建过一条待办"
                : NotesBackend.BACKEND_COLOROS_NOTE.equals(backend) ? "在便签 App 里建过至少一条笔记"
                : "在小米笔记里建过至少一条待办";
        String[] r = suExec("su", LD() + " " + SQLITE + " " + db
                + " '" + NotesBackend.countProbeSql(ctx) + "'", 15);
        if (r != null && r[0] != null && r[0].trim().matches("\\d+")) {
            return new Check("待办库访问", true,
                    table + " 表可读，当前 " + r[0].trim() + " 条 ✓");
        }
        String e = r == null ? "null" : (r[1] + " " + r[2]).trim();
        if (e.contains("unable to open")) {
            return new Check("待办库访问", false,
                    "打不开 " + dbFile + "（权限不足）→ 正常情况模块写入时会自动处理；"
                            + "可先跑一次「一键测试」让它自愈，再导出报告");
        }
        if (e.contains("no such table")) {
            return new Check("待办库访问", false,
                    table + " 表不存在 → 请先" + needHint + "，或系统版本过旧");
        }
        return new Check("待办库访问", false, "探测失败：" + tail(e, 140));
    }

    // ==================== 报告 ====================

    /** 一步生成完整诊断报告（脱敏后纯文本） */
    public static String collectReport(Context ctx) {
        String ver = "unknown";
        try {
            android.content.pm.PackageInfo pi = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0);
            ver = pi.versionName + " (code " + pi.versionCode + ")";
        } catch (Throwable ignored) { }
        StringBuilder sb = new StringBuilder();
        sb.append("===== 取件码助手 诊断报告 ").append(ver).append(" =====\n");
        sb.append("生成时间: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date())).append("\n\n");

        sb.append("---- 基本信息 ----\n");
        sb.append("设备: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
          .append("（").append(Build.DEVICE).append("）\n");
        sb.append("系统: Android ").append(Build.VERSION.RELEASE)
          .append(" (SDK ").append(Build.VERSION.SDK_INT).append(") / build ").append(Build.DISPLAY).append("\n");
        String miui = prop("ro.miui.ui.version.name");
        String miOs = prop("ro.mi.os.version.name");
        if (miOs != null && !miOs.isEmpty()) sb.append("澎湃OS: ").append(miOs).append("\n");
        else if (miui != null && !miui.isEmpty()) sb.append("MIUI: ").append(miui).append("\n");
        // v2.7.0：多 ROM 支持——ColorOS 版本与当前写入后端一并写入报告
        String oplus = prop("ro.build.version.oplusRom");
        if (oplus != null && !oplus.isEmpty()) sb.append("ColorOS: ").append(oplus).append("\n");
        sb.append("写入后端: ").append(NotesBackend.detect(ctx)).append("（")
          .append(NotesBackend.targetAppName(ctx)).append("）\n");
        sb.append("Magisk: ").append(prop("magisk.version") ).append("\n");
        try {
            android.content.pm.PackageInfo pi = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0);
            sb.append("模块版本: ").append(pi.versionName)
              .append(" (code ").append(pi.versionCode).append(")\n");
        } catch (Throwable ignored) { }
        sb.append("\n");

        sb.append("---- 部署体检 ----\n");
        try {
            for (Check c : healthCheck(ctx)) {
                sb.append(c.ok ? "[✓] " : "[✗] ").append(c.title).append(": ").append(c.detail).append("\n");
            }
        } catch (Throwable t) {
            sb.append("体检异常: ").append(t).append("\n");
        }
        sb.append("\n");

        sb.append("---- 最近一次写库结果 ----\n");
        sb.append(TodoWriter.getLastWriteDiag()).append("\n\n");

        sb.append("---- PICKUPDEBUG 日志（root 提取，近 400 行，已脱敏）----\n");
        String[] lg = suExec("su", "logcat -d -s PICKUPDEBUG:* | tail -n 400", 25);
        String logcat = lg == null ? "(提取失败)" : (lg[0] == null ? "" : lg[0]);
        sb.append(logcat.isEmpty() ? "(无 PICKUPDEBUG 日志——说明 Hook 从未触发，先检查作用域)" : logcat).append("\n\n");

        sb.append("---- 被注入的进程（从日志统计）----\n");
        TreeSet<String> injected = new TreeSet<>();
        Matcher m = Pattern.compile("handleLoadPackage: pkg=([\\w.]+)").matcher(logcat);
        while (m.find()) injected.add(m.group(1));
        if (injected.isEmpty()) {
            sb.append("(未统计到——日志为空)");
        } else {
            for (String p : injected) sb.append("  ").append(p).append("\n");
            boolean self = injected.contains("io.github.okaidev.pickupcode");
            boolean anyTarget = injected.contains("com.android.mms")
                    || injected.contains("com.android.phone")
                    || injected.contains("com.android.providers.telephony")
                    || injected.contains("android");
            sb.append("判定: 模块自身").append(self ? "已注入 ✓" : "未注入 ✗")
              .append("；Hook 目标进程").append(anyTarget ? "有注入 ✓" : "无注入 ✗（作用域未勾选目标应用或未重启）")
              .append("\n");
        }
        sb.append("\n");

        sb.append("---- LSPosed modules.log 尾部（已脱敏）----\n");
        String ml = readModulesLog();
        sb.append(ml.isEmpty() ? "(读取失败——可手动从 LSPosed 管理器导出模块日志)" : ml).append("\n\n");

        sb.append("===== 报告结束（内容已脱敏：手机号/取件码）=====\n");
        return sanitize(sb.toString());
    }

    /** 保存到 Download/pickup_diag_*.txt 并拉起分享 */
    public static String exportAndShare(Context ctx, String report) {
        String name = "pickup_diag_" + new SimpleDateFormat("MMdd-HHmmss", Locale.CHINA).format(new Date()) + ".txt";
        String where;
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = ctx.getContentResolver().insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                OutputStream os = ctx.getContentResolver().openOutputStream(uri);
                os.write(report.getBytes("UTF-8"));
                os.close();
                where = "Download/" + name;
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), "");
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, name);
                java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
                fos.write(report.getBytes("UTF-8"));
                fos.close();
                where = f.getAbsolutePath();
            }
        } catch (Throwable t) {
            where = "(保存失败: " + t.getMessage() + ")";
        }
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_SUBJECT, "取件码助手诊断报告");
            i.putExtra(Intent.EXTRA_TEXT, report);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(Intent.createChooser(i, "分享诊断报告").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable ignored) { }
        return where;
    }

    // ==================== 工具 ====================

    private static String LD() { return "LD_LIBRARY_PATH=" + LIBS; }

    /** 执行 su 命令；返回 {stdout, stderr, 原始err消息}，异常时 null */
    private static String[] suExec(String suBin, String cmd, int timeoutSec) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c",
                    suBin + " -M -c \"" + cmd.replace("\"", "'") + "\""});
            StringBuilder out = new StringBuilder();
            StringBuilder err = new StringBuilder();
            drain(p.getInputStream(), out);
            drain(p.getErrorStream(), err);
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroy();
                return new String[]{out.toString(), err.toString(), "(超时 " + timeoutSec + "s)"};
            }
            return new String[]{out.toString(), err.toString(), err.toString()};
        } catch (Throwable t) {
            return new String[]{null, null, String.valueOf(t)};
        }
    }

    private static void drain(java.io.InputStream in, StringBuilder sb) {
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
        } catch (Throwable ignored) { }
    }

    /** 系统属性（反射，不依赖隐藏 API） */
    private static String prop(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            return (String) sp.getMethod("get", String.class).invoke(null, key);
        } catch (Throwable t) {
            return "";
        }
    }

    /** LSPosed 模块日志（多路径尝试） */
    private static String readModulesLog() {
        String[] paths = {
                "/data/adb/lspd/log/modules.log",
                "/data/adb/lsposed/logs/modules.log",
                "/data/adb/lsposed/modules.log"
        };
        for (String p : paths) {
            String[] r = suExec("su", "tail -n 200 " + p, 15);
            if (r != null && r[0] != null && !r[0].isEmpty() && !r[0].contains("No such file")) {
                return "(" + p + ")\n" + r[0];
            }
        }
        return "";
    }

    /** 脱敏：手机号 → 138****8888；取件码数字段 → X 化（保留形状） */
    public static String sanitize(String s) {
        if (s == null) return "";
        s = P_PHONE.matcher(s).replaceAll(PHONE_MASK);
        Matcher m = P_CODE.matcher(s);
        StringBuffer sbf = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sbf, "XX-X-XXXX");
        }
        m.appendTail(sbf);
        return sbf.toString();
    }

    private static String tail(String s, int max) {
        if (s == null) return "";
        s = s.trim();
        return s.length() <= max ? s : s.substring(s.length() - max);
    }
}
