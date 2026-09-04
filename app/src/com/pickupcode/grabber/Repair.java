package com.pickupcode.grabber;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 自动修复引擎（v2.2.0 新增）：
 *  1) deploySqlite3：把 APK 内置的 sqlite3 + 依赖库（已在你机器上验证可用的 3.53.4 arm64 套件）
 *     从 assets 释放到应用私有目录，再用 su 拷贝到约定目录 /data/local/tmp/pickup_sqlite/ 并赋权；
 *     从此网友无需 adb / Termux 手工部署；
 *  2) lsposedStatus：root 读取 LSPosed 配置库（/data/adb/lspd/config/modules_config.db，
 *     实测 schema：modules_state(enabled) + scope(module_pkg_name, app_pkg_name, user_id)），
 *     判断模块是否启用、作用域是否包含 4 个必需目标，返回人话结论。
 *
 * 全部操作只写约定目录与只读 LSPosed 配置，不做任何破坏性动作。
 */
public class Repair {

    public static final String TARGET_DIR = "/data/local/tmp/pickup_sqlite";
    public static final String LIBS = TARGET_DIR + "/lib";
    public static final String SQLITE = TARGET_DIR + "/sqlite3";
    public static final String DB = "/data/user/0/com.miui.notes/databases/todo.db";
    private static final String LSP_DB = "/data/adb/lspd/config/modules_config.db";
    private static final String SELF = "com.pickupcode.grabber";

    /** 作用域必需目标（实测 HyperOS 将 providers.telephony 合并进 phone 进程，故列为可选） */
    public static final String[] SCOPE_REQUIRED = {
            "android", "com.android.phone", "com.android.mms", "com.miui.notes"
    };
    public static final String SCOPE_OPTIONAL = "com.android.providers.telephony";

    // ==================== 一键部署 sqlite3 ====================

    /**
     * 从 assets 释放 sqlite3 并 su 部署。
     * 返回结果说明（人话，可直接展示/写入诊断报告）。
     */
    public static String deploySqlite3(Context ctx) {
        // 1. assets → 私有目录
        File stage;
        try {
            stage = new File(ctx.getFilesDir(), "sqlite3");
            new File(stage, "lib").mkdirs();
            copyAsset(ctx.getAssets(), "sqlite3/sqlite3", new File(stage, "sqlite3"));
            String[] libs = ctx.getAssets().list("sqlite3/lib");
            if (libs == null || libs.length == 0) {
                return "部署失败：APK 内未找到 sqlite3 资产（安装包不完整，请重新下载）";
            }
            for (String l : libs) {
                copyAsset(ctx.getAssets(), "sqlite3/lib/" + l, new File(stage, "lib/" + l));
            }
        } catch (Throwable t) {
            return "部署失败（释放资产）：" + t.getMessage();
        }

        // 2. su 拷贝到约定目录 + 赋权（与 TodoWriter 相同的 su 多路径策略）
        String from = stage.getAbsolutePath();
        String cmd = "mkdir -p " + LIBS + "; "
                + "cp -f " + from + "/sqlite3 " + SQLITE + "; "
                + "cp -f " + from + "/lib/* " + LIBS + "/; "
                + "chmod 755 " + SQLITE + "; chmod 755 " + LIBS + "; "
                + "chmod 644 " + LIBS + "/*; "
                + "echo DEPLOY_DONE";
        String[] r = Diagnostics.suExecPublic(cmd, 30);
        String out = r == null ? "" : (r[0] == null ? "" : r[0]);
        String err = r == null ? "" : (r[1] == null ? "" : r[1]);
        if (!out.contains("DEPLOY_DONE")) {
            return "部署失败（su 执行）：exit 输出=" + clip(out + " " + err, 160)
                    + "。请确认已在 Magisk 弹窗中允许授权。";
        }

        // 3. 验证可执行
        String[] v = Diagnostics.suExecPublic(
                "LD_LIBRARY_PATH=" + LIBS + " " + SQLITE + " --version", 15);
        String ver = v == null ? "" : (v[0] == null ? "" : v[0]).trim();
        if (ver.contains("3.")) {
            return "部署成功 ✓ sqlite3 " + clip(ver, 60) + "（已放入 " + TARGET_DIR + "）";
        }
        return "部署完成但验证未通过：" + clip(ver + " " + err, 160);
    }

    private static void copyAsset(AssetManager am, String assetPath, File out) throws Exception {
        InputStream is = null;
        OutputStream os = null;
        try {
            is = am.open(assetPath);
            os = new FileOutputStream(out);
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
        } finally {
            try { if (is != null) is.close(); } catch (Throwable ignored) { }
            try { if (os != null) os.close(); } catch (Throwable ignored) { }
        }
    }

    // ==================== LSPosed 状态检测 ====================

    public static class LsposedStatus {
        public boolean dbReadable;      // 配置库是否可读（root + sqlite3 就绪才可读）
        public boolean moduleEnabled;   // 模块在 LSPosed 中已启用
        public String missingScope;     // 缺失的必需作用域（逗号分隔；空=齐全）
        public String scopeList;        // 当前作用域（逗号分隔）
        public boolean hasStaleSystem;  // 是否残留无效的 system 条目（v2.1.1 旧推荐）
        public String detail;           // 附加上下文（失败原因等）
    }

    /**
     * root 读取 LSPosed 配置库，返回模块启用状态与作用域比对结果。
     * SQL 经临时文件下发（规避多层 shell 引号转义）；任何失败都优雅降级。
     */
    public static LsposedStatus lsposedStatus(Context ctx) {
        LsposedStatus st = new LsposedStatus();
        st.dbReadable = false;
        st.moduleEnabled = false;
        st.missingScope = null;
        st.scopeList = "";
        st.detail = "";

        File sqlFile;
        try {
            StringBuilder sql = new StringBuilder();
            sql.append(".mode list\n");
            sql.append("SELECT 'EN=' || enabled FROM modules_state WHERE module_pkg_name='")
               .append(SELF).append("' AND user_id=0;\n");
            sql.append("SELECT 'SC=' || app_pkg_name FROM scope WHERE module_pkg_name='")
               .append(SELF).append("';\n");
            sqlFile = new File(ctx.getFilesDir(), "lsp_query.sql");
            FileOutputStream fos = new FileOutputStream(sqlFile);
            fos.write(sql.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Throwable t) {
            st.detail = "SQL 文件写入失败: " + t.getMessage();
            return st;
        }

        String[] r = Diagnostics.suExecPublic(
                "LD_LIBRARY_PATH=" + LIBS + " " + SQLITE + " " + LSP_DB + " < " + sqlFile.getAbsolutePath(), 15);
        if (r == null || r[0] == null || r[0].isEmpty()) {
            st.detail = "读取 " + LSP_DB + " 失败（"
                    + (r == null ? "null" : clip(nz(r[1]) + " " + nz(r[2]), 100))
                    + "）——LSPosed 未安装或路径不同，请手动核对";
            return st;
        }
        st.dbReadable = true;

        StringBuilder scopeSb = new StringBuilder();
        java.util.List<String> missing = new java.util.ArrayList<>();
        boolean stale = false;
        for (String line : r[0].split("\n")) {
            line = line.trim();
            if (line.startsWith("EN=")) {
                st.moduleEnabled = "EN=1".equals(line);
            } else if (line.startsWith("SC=")) {
                String pkg = line.substring(3).trim();
                if (pkg.isEmpty()) continue;
                if (scopeSb.length() > 0) scopeSb.append(", ");
                scopeSb.append(pkg);
                if ("system".equals(pkg)) stale = true;
            }
        }
        st.scopeList = scopeSb.toString();
        st.hasStaleSystem = stale;
        if (st.moduleEnabled && !st.scopeList.isEmpty()) {
            for (String need : SCOPE_REQUIRED) {
                if (!st.scopeList.contains(need)) missing.add(need);
            }
            st.missingScope = missing.isEmpty() ? null : join(missing);
        }
        return st;
    }

    private static String join(java.util.List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) { if (sb.length() > 0) sb.append("、"); sb.append(s); }
        return sb.toString();
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String clip(String s, int max) {
        if (s == null) return "";
        s = s.trim();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
