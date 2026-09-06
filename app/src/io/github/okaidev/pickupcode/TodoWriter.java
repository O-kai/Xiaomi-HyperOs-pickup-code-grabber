package io.github.okaidev.pickupcode;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 待办写入器（P2）：在模块自身进程运行。
 * 流程：提取取件码 → 去重（本地指纹 On）→ su sqlite3 直写 todo.db（堆栈置顶）→ 日志
 */
public class TodoWriter {

    private static final String TAG = "PICKUPDEBUG";
    // sqlite3 部署在 /data/local/tmp（对 App su 子进程可执行），依赖库同目录
    private static final String LIBS = "/data/local/tmp/pickup_sqlite/lib";
    private static final String SQLITE = "/data/local/tmp/pickup_sqlite/sqlite3";
    private static final String DB = "/data/user/0/com.miui.notes/databases/todo.db";
    private static final String PREFS = "dedup";
    private static final int DEDUP_MAX = 500;

    /** 地点提取：到/在 + 路段 + 店/站/柜/点 */
    private static final Pattern P_PLACE = Pattern.compile(
            "(?:到|在)([\\u4e00-\\u9fa5A-Za-z0-9]{2,30}?(?:店|站|柜|点|自提))");

    /** 最近一次 su/sqlite 执行记录（诊断报告用）：含失败原因与命令输出 */
    private static volatile String lastWriteDiag =
            "尚未执行过写库操作（还没有收到取件短信/跑过一键测试）";

    public static String getLastWriteDiag() { return lastWriteDiag; }

    /** 模板模式：0=极简（仅取件码） 1=完整（默认） 2=自定义模板 */
    public static final int MODE_MIN = 0;
    public static final int MODE_FULL = 1;
    public static final int MODE_CUSTOM = 2;

    /** 入口：模块进程收到短信事件后处理一条短信 */
    public static List<String> handle(Context ctx, String sender, String body) {
        // 黑名单预检（12306/银行/广告等）
        if (isBlacklisted(ctx, body)) {
            Log.i(TAG, "blacklist skip: " + body.substring(0, Math.min(body.length(), 60)));
            return new ArrayList<>();
        }
        List<String> codes = PickupExtractor.extract(body);
        if (codes.isEmpty()) return codes;

        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(new Date());
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> seen = new LinkedHashSet<>(prefs.getStringSet("seen", new LinkedHashSet<>()));
        int mode = prefs.getInt("todo_mode", MODE_FULL);
        String customTpl = prefs.getString("todo_custom", "📦 取件码 {code}｜{source}｜{place}｜{time}");

        List<String> wrote = new ArrayList<>();
        String place = extractPlace(body);
        String source = resolveSource(sender, body);
        for (String code : codes) {
            String fingerprint = code + "|" + place;
            if (seen.contains(fingerprint)) {
                Log.i(TAG, "dedup skip: " + fingerprint);
                continue;
            }
            seen.add(fingerprint);
            String content = buildContent(mode, customTpl, code, source, place, time);
            boolean ok = writeTodo(ctx, content);
            if (ok) {
                wrote.add(code);
                Log.i(TAG, "TODO OK: " + content);
            } else {
                Log.e(TAG, "TODO FAIL: " + content);
            }
        }

        // 持久化去重集合（限制容量）
        while (seen.size() > DEDUP_MAX) {
            java.util.Iterator<String> it = seen.iterator();
            it.next();
            it.remove();
        }
        prefs.edit()
                .putStringSet("seen", seen)
                .putInt("todo_mode", mode)
                .apply();
        return wrote;
    }

    /** 按模板构建待办内容 */
    public static String buildContent(int mode, String customTpl, String code, String source, String place, String time) {
        switch (mode) {
            case MODE_MIN:
                return code;
            case MODE_CUSTOM:
                String tpl = customTpl == null || customTpl.isEmpty() ? "📦 取件码 {code}｜{source}｜{place}｜{time}" : customTpl;
                return tpl.replace("{code}", code)
                        .replace("{source}", source)
                        .replace("{place}", place)
                        .replace("{time}", time);
            case MODE_FULL:
            default:
                return "📦 取件码 " + code + "｜" + source + "｜" + place + "｜" + time;
        }
    }

    /** 黑名单：正文含任一关键词则跳过（默认常见误报词） */
    public static boolean isBlacklisted(Context ctx, String body) {
        if (body == null || body.isEmpty()) return false;
        String list = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("blacklist", "12306,验证码,余额,充值,账单,银行,优惠券,退订");
        if (list == null || list.isEmpty()) return false;
        for (String kw : list.split("[,，、;；\\s]+")) {
            if (kw.isEmpty()) continue;
            if (body.contains(kw)) return true;
        }
        return false;
    }

    /** 统一 SQL 执行器（一键测试 / 已取件 共用） */
    public static int runSql(Context ctx, String sql) {
        String out = runSqlForOutput(ctx, sql);
        return out == null ? -1 : 0;
    }

    /** 统一 SQL 执行器（返回输出；多路径 su 尝试） */
    public static String runSqlForOutput(Context ctx, String sql) {
        try {
            File dir = ctx.getFilesDir();
            File sqlFile = new File(dir, "todo_sql.sql");
            try (FileOutputStream fos = new FileOutputStream(sqlFile)) {
                fos.write(sql.getBytes("UTF-8"));
            }
            // HyperOS 的 Magisk su 位于 /product/bin/su（应用 PATH 不含该目录）→ 多路径尝试
            String[] suBins = {"su", "/product/bin/su", "/system/bin/su", "/sbin/su", "/su/bin/su"};
            String trace = "";
            for (String suBin : suBins) {
                String cmd = suBin + " -M -c \"id; " + buildSuCmd(sqlFile) + "\"";
                Log.i(TAG, "su attempt(" + suBin + ")");
                Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
                StringBuilder out = new StringBuilder();
                StringBuilder err = new StringBuilder();
                drain(p.getInputStream(), out);
                drain(p.getErrorStream(), err);
                int code = p.waitFor();
                Log.i(TAG, "sqlite exit=" + code + " out=" + out.toString().trim()
                        + " err=" + err.toString().trim());
                if (code == 0) {
                    lastWriteDiag = "OK（su=" + suBin + "）" + (err.length() > 0
                            ? " stderr=" + tail(err.toString(), 160) : "");
                    return out.toString();
                }
                trace += "su=" + suBin + " exit=" + code
                        + " err=" + tail(err.toString(), 160) + "；";
                // 未授权的 su 会输出 "not found"/"Permission denied" —— 记录下来便于诊断
                lastWriteDiag = "全部 su 路径失败 → " + trace;
            }
            return null;
        } catch (Throwable t) {
            lastWriteDiag = "runSqlForOutput 异常: " + t;
            Log.e(TAG, "runSqlForOutput err: " + t);
            return null;
        }
    }

    private static String tail(String s, int max) {
        if (s == null) return "";
        s = s.trim();
        return s.length() <= max ? s : s.substring(s.length() - max);
    }

    /** 简单 SQL 单引号转义 */
    public static String escape(String s) {
        if (s == null) return "";
        return s.replace("'", "''").replace("\0", "");
    }

    private static String extractPlace(String body) {
        Matcher m = P_PLACE.matcher(body == null ? "" : body);
        if (m.find()) return m.group(1);
        return "未知地点";
    }

    private static String resolveSource(String sender, String body) {
        String t = (body == null ? "" : body);
        if (t.contains("菜鸟") || t.contains("驿站")) return "菜鸟驿站";
        if (t.contains("丰巢") || t.contains("柜")) return "丰巢快递柜";
        if (t.contains("京东")) return "京东快递";
        if (t.contains("顺丰") || t.contains("SF")) return "顺丰速运";
        if (t.contains("中通")) return "中通快递";
        if (t.contains("圆通")) return "圆通速递";
        if (t.contains("韵达")) return "韵达快递";
        if (t.contains("申通")) return "申通快递";
        if (t.contains("邮政") || t.contains("EMS")) return "中国邮政";
        return sender == null || sender.isEmpty() ? "快递" : "快递(" + sender + ")";
    }

    /**
     * 直写 todo.db：内容转义后写入模块私有 SQL 文件，su -M sqlite3 执行。
     * custom_sort_id = MAX + 0x100000（系统原生堆栈置顶）
     */
    private static synchronized boolean writeTodo(Context ctx, String content) {
        String esc = sqlEscape(content);
        String sql = ".timeout 5000\n"
                + "INSERT INTO todo (content, plain_text, is_finish, list_type, type, category, folder_id, source, input_type, remind_type, priority, hide_type, custom_sort_id, sort_id, version, local_status, server_status, words_count, create_time, last_modified_time)\n"
                + "VALUES ('" + esc + "', '" + esc + "', 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (SELECT COALESCE(MAX(custom_sort_id), 0) FROM todo) + 1048576, 0, 1, 0, 0, 0, (strftime('%s','now')*1000), (strftime('%s','now')*1000));\n";
        return runSql(ctx, sql) == 0;
    }

    private static String buildSuCmd(File sqlFile) {
        // 自愈式：每次写入前确保目录/文件可访问（App su 子进程缺 DAC 特权，需要放开）
        // LD_LIBRARY_PATH 必须写在命令串内（su 会保留命令行内的环境变量赋值）
        return "chmod 711 /data/user/0/com.miui.notes; "
                + "chmod 771 /data/user/0/com.miui.notes/databases; "
                + "chmod 666 /data/user/0/com.miui.notes/databases/todo.db; "
                + "LD_LIBRARY_PATH=" + LIBS + " " + SQLITE + " " + DB + " < " + sqlFile.getAbsolutePath();
    }

    private static void drain(java.io.InputStream in, StringBuilder sb) {
        try {
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                sb.append(new String(buf, 0, n, "UTF-8"));
            }
        } catch (Throwable ignored) { }
    }

    private static String sqlEscape(String s) {
        if (s == null) return "";
        return s.replace("'", "''").replace("\0", "");
    }
}
