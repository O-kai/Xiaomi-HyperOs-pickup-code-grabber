package io.github.okaidev.pickupcode;

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
    private static final String LSP_DB = "/data/adb/lspd/config/modules_config.db";

    /** 已知的配置库位置：LSPosed 官方 + 常见分支（Vector 等） */
    private static final String[] LSP_DB_CANDIDATES = {
            "/data/adb/lspd/config/modules_config.db",
            "/data/adb/vector/config/modules_config.db",
            "/data/adb/lsposed/config/modules_config.db",
    };
    private static final String SELF = "io.github.okaidev.pickupcode";

    /**
     * 作用域必需目标（v2.7.0 按 ROM 收敛）：
     *  - 通用（Hook 目标，两家 ROM 都要）：android、com.android.phone、com.android.mms；
     *  - 小米侧追加 com.miui.notes（旧版要求，仅小米后端时校验——保留兼容旧用户已勾配置）；
     *  - com.android.providers.telephony 为可选（S8 主通道所在；实测 HyperOS 将其合并进
     *    phone 进程，勾上更稳，未勾也不报缺）。
     *  写入目标 App（笔记/日历）不是 Hook 目标（模块靠 su 直写其库），无需勾选。
     */
    public static String[] scopeRequired(Context ctx) {
        String backend = NotesBackend.detect(ctx);
        if (NotesBackend.BACKEND_XIAOMI.equals(backend)) {
            return new String[]{"android", "com.android.phone", "com.android.mms", NotesBackend.PKG_XIAOMI};
        }
        return new String[]{"android", "com.android.phone", "com.android.mms"};
    }
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
        /** v4.0.2：配置库查询的原始输出（DBOK=/EN=/SC= 行，已脱敏无需处理）与所用路径，写进诊断报告用 */
        public String rawOut = "";
        public String dbPath = "";
        public boolean suRan = false;
    }

    /**
     * root 读取 LSPosed 配置库，返回模块启用状态与作用域比对结果。
     * SQL 经临时文件下发（规避多层 shell 引号转义）；任何失败都优雅降级。
     */
    // ================================================================
    //  v4.0.3：配置库「结构自适应」
    //  背景：LSPosed 各分支（Vector / JingMatrix 等）改过 modules_config.db 的表名与列名
    //        （真机实测：Vector 上 modules_state 表已不存在、scope 表也换了列名），
    //        写死 SQL 必然误报「模块未启用」。这里改为两步：
    //          ① 先读 sqlite_master 拿到真实表名 + 建表语句（列名）；
    //          ② 按真实结构拼查询 —— 识别不出结构时不下结论，并把结构写进诊断报告。
    // ================================================================

    private static final String[] COL_PKG = {"module_pkg_name", "module_package_name", "module",
            "pkg_name", "package_name", "apk_pkg_name", "package"};
    private static final String[] COL_ENABLED = {"enabled", "enable", "is_enabled", "state", "active"};
    private static final String[] COL_SCOPE = {"app_pkg_name", "target_pkg_name", "app_package_name",
            "scope_pkg_name", "pkg_name", "package_name", "app", "scope"};
    private static final String[] COL_ID = {"id", "_id", "mid", "module_id"};
    private static final String[] COL_MODREF = {"module_id", "mid", "module", "module_pkg_name",
            "module_package_name"};
    private static final String[] COL_USER = {"user_id", "userId", "uid"};

    public static LsposedStatus lsposedStatus(Context ctx) {
        LsposedStatus st = new LsposedStatus();
        st.dbReadable = false;
        st.moduleEnabled = false;
        st.missingScope = null;
        st.scopeList = "";
        st.detail = "";
        st.rawOut = "";
        st.dbPath = "";

        // ── 第 1 步：读结构（表名 + 建表语句 → 列名）──
        File schemaFile;
        try {
            schemaFile = new File(ctx.getFilesDir(), "lsp_schema.sql");
            FileOutputStream fos = new FileOutputStream(schemaFile);
            fos.write((".mode list\n"
                    + "SELECT 'TBL=' || name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%';\n"
                    + "SELECT 'SQL=' || replace(replace(sql, char(10), ' '), char(13), ' ') "
                    + "FROM sqlite_master WHERE type='table' AND sql IS NOT NULL;\n").getBytes("UTF-8"));
            fos.close();
        } catch (Throwable t) {
            st.detail = "SQL 文件写入失败: " + t.getMessage();
            return st;
        }

        String db = LSP_DB;
        String[] r1 = runSqlFile(schemaFile, db);
        if (!looksLikeDb(Diagnostics.suStdout(r1))) {
            // 换分支路径 + find 兜底
            for (int k = 1; k < LSP_DB_CANDIDATES.length; k++) {
                String[] r2 = runSqlFile(schemaFile, LSP_DB_CANDIDATES[k]);
                if (looksLikeDb(Diagnostics.suStdout(r2))) {
                    r1 = r2;
                    db = LSP_DB_CANDIDATES[k];
                    break;
                }
            }
            if (!looksLikeDb(Diagnostics.suStdout(r1))) {
                String found = findLspDb();
                if (found != null) {
                    String[] r3 = runSqlFile(schemaFile, found);
                    if (looksLikeDb(Diagnostics.suStdout(r3))) {
                        r1 = r3;
                        db = found;
                    }
                }
            }
        }

        st.dbPath = db;
        st.suRan = Diagnostics.suRan(r1);
        String schemaOut = Diagnostics.suStdout(r1);
        if (!st.suRan) {
            st.detail = "su 未执行（root 未授权或 su 路径不同），无法读取配置库";
            st.rawOut = schemaOut;
            return st;
        }
        if (schemaOut.isEmpty() || !schemaOut.contains("TBL=")) {
            st.detail = schemaOut.isEmpty()
                    ? "已尝试 lspd / vector 等常见路径并在 /data/adb 下搜索，都没有读到 modules_config.db"
                      + "（LSPosed / Vector 未安装，或配置库在非常规位置）"
                    : "读取配置库失败：" + clip(schemaOut, 160);
            st.rawOut = schemaOut;
            return st;
        }

        // ── 解析结构 ──
        java.util.LinkedHashMap<String, java.util.List<String>> tables = parseSchema(schemaOut);
        st.rawOut = "== 结构 ==\n" + schemaOut.trim();
        if (tables.isEmpty()) {
            st.detail = "读到配置库，但没能解析出任何表结构（详见报告中的原始输出）";
            return st;
        }

        // 模块表：既有「包名列」又有「启用列」
        String modTable = null, modPkgCol = null, modEnabledCol = null, modIdCol = null;
        for (java.util.Map.Entry<String, java.util.List<String>> e : tables.entrySet()) {
            java.util.List<String> cols = e.getValue();
            String pk = pick(cols, COL_PKG);
            String en = pick(cols, COL_ENABLED);
            if (pk != null && en != null) {
                modTable = e.getKey();
                modPkgCol = pk;
                modEnabledCol = en;
                modIdCol = pick(cols, COL_ID);
                break;
            }
        }

        // 作用域表：既有「目标包名列」，又能关联到模块（同表包名列 或 模块 id 列）
        String scopeTable = null, scopeCol = null, scopePkgCol = null, scopeModRef = null;
        for (java.util.Map.Entry<String, java.util.List<String>> e : tables.entrySet()) {
            if (e.getKey().equals(modTable)) continue;
            java.util.List<String> cols = e.getValue();
            String sc = pick(cols, COL_SCOPE);
            if (sc == null) continue;
            String pk = pick(cols, COL_PKG);
            String ref = pick(cols, COL_MODREF);
            if (pk != null || ref != null) {
                scopeTable = e.getKey();
                scopeCol = sc;
                scopePkgCol = pk;
                scopeModRef = ref;
                break;
            }
        }

        if (modTable == null && scopeTable == null) {
            st.detail = "配置库结构无法识别（已知分支：LSPosed 原版为 modules_state + scope；"
                    + "本机为 " + join(new java.util.ArrayList<>(tables.keySet()), "、")
                    + "）。完整结构已写入诊断报告，可直接反馈给作者适配";
            return st;
        }

        // ── 第 2 步：按真实结构查询 ──
        StringBuilder q = new StringBuilder();
        q.append(".mode list\n");
        q.append("SELECT 'DBOK=1';\n");
        if (modTable != null) {
            q.append("SELECT 'EN=' || ").append(modEnabledCol).append(" FROM ").append(modTable)
             .append(" WHERE ").append(modPkgCol).append("='").append(SELF).append("';\n");
            if (modIdCol != null) {
                q.append("SELECT 'MID=' || ").append(modIdCol).append(" FROM ").append(modTable)
                 .append(" WHERE ").append(modPkgCol).append("='").append(SELF).append("';\n");
            }
        } else {
            // 没有模块表：退化为「作用域表里有没有本模块的目标行」判断是否已启用
            q.append("SELECT 'DBOK=1';\n");
        }
        if (scopeTable != null) {
            String where;
            if (scopePkgCol != null) {
                where = scopePkgCol + "='" + SELF + "'";
            } else {
                where = scopeModRef + "=(SELECT " + (modIdCol == null ? "rowid" : modIdCol)
                        + " FROM " + modTable + " WHERE " + modPkgCol + "='" + SELF + "' LIMIT 1)";
            }
            q.append("SELECT 'SC=' || ").append(scopeCol).append(" FROM ").append(scopeTable)
             .append(" WHERE ").append(where).append(";\n");
        }

        File qFile;
        try {
            qFile = new File(ctx.getFilesDir(), "lsp_query.sql");
            FileOutputStream fos = new FileOutputStream(qFile);
            fos.write(q.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Throwable t) {
            st.detail = "查询语句写入失败: " + t.getMessage();
            return st;
        }

        String[] r = runSqlFile(qFile, db);
        String out = Diagnostics.suStdout(r);
        st.rawOut = st.rawOut + "\n== 查询（按实际结构）==\n"
                + "表: " + (modTable == null ? "(无模块表)" : modTable + "." + modPkgCol + "/" + modEnabledCol)
                + " · 作用域表: " + (scopeTable == null ? "(无)" : scopeTable + "." + scopeCol) + "\n"
                + out.trim();
        if (!Diagnostics.suRan(r)) {
            st.detail = "su 未执行（root 未授权或 su 路径不同），无法读取配置库";
            return st;
        }
        if (out.isEmpty() || !out.contains("DBOK=")) {
            st.detail = out.isEmpty()
                    ? "查询未返回任何内容（配置库可能被 SELinux 拒绝读取）"
                    : "查询失败：" + clip(out, 160);
            return st;
        }
        st.dbReadable = true;

        java.util.List<String> scopePkgs = new java.util.ArrayList<>();
        boolean stale = false;
        boolean sawEnabledRow = false;
        for (String line : out.split("\n")) {
            line = line.trim();
            if (line.startsWith("EN=")) {
                sawEnabledRow = true;
                // 多用户 / 历史残留可能留多行：任一行 enabled=1 即视为启用
                if (line.equals("EN=1")) st.moduleEnabled = true;
            } else if (line.startsWith("SC=")) {
                String pkg = line.substring(3).trim();
                if (pkg.isEmpty()) continue;
                scopePkgs.add(pkg);
                if ("system".equals(pkg)) stale = true;
            }
        }
        st.scopeList = join(scopePkgs, ", ");
        st.hasStaleSystem = stale;

        if (modTable == null) {
            // 没有模块状态表可用：只能用「有没有作用域行」间接判断是否启用；
            // 但作用域比对必须照做，否则会误报「必需目标齐全」
            st.moduleEnabled = !scopePkgs.isEmpty();
            if (st.moduleEnabled) {
                java.util.List<String> missing = new java.util.ArrayList<>();
                for (String need : scopeRequired(ctx)) {
                    if (!scopePkgs.contains(need)) missing.add(need);
                }
                st.missingScope = missing.isEmpty() ? null : join(missing, "、");
            }
            st.detail = st.moduleEnabled
                    ? "本机配置库结构为分支变体（无模块状态表），已按「存在作用域记录」判定为已启用"
                    : "本机配置库结构为分支变体（无模块状态表），且查不到本模块的作用域记录——请手动核对";
            return st;
        }
        if (sawEnabledRow && !st.moduleEnabled) {
            st.detail = "配置库中本模块的启用记录为 0，但模块已被注入（同一份体检里注入为通过）——"
                    + "可能是多用户/残留记录，或该分支的启用状态记在别处；请以 LSPosed 界面显示为准";
        }
        if (st.moduleEnabled) {
            // 精确集合比对（不能用子串：com.android.phone 会误匹配 android）
            java.util.List<String> missing = new java.util.ArrayList<>();
            for (String need : scopeRequired(ctx)) {
                if (!scopePkgs.contains(need)) missing.add(need);
            }
            st.missingScope = missing.isEmpty() ? null : join(missing, "、");
        }
        return st;
    }

    /** 在候选列名里挑第一个存在的列 */
    private static String pick(java.util.List<String> cols, String[] cands) {
        for (String c : cands) if (cols.contains(c)) return c;
        return null;
    }

    /**
     * 解析 sqlite_master 的输出 → 表名与列名。
     * 输入形如：TBL=modules / SQL=CREATE TABLE modules ( id INTEGER PRIMARY KEY, module_pkg_name TEXT, enabled INTEGER )
     */
    private static java.util.LinkedHashMap<String, java.util.List<String>> parseSchema(String out) {
        java.util.LinkedHashMap<String, java.util.List<String>> map = new java.util.LinkedHashMap<>();
        for (String line : out.split("\n")) {
            line = line.trim();
            if (!line.startsWith("SQL=")) continue;
            String sql = line.substring(4).trim();
            int p1 = sql.indexOf('(');
            if (p1 < 0) continue;
            String head = sql.substring(0, p1).trim();
            String[] hp = head.split("\\s+");
            String tbl = hp.length > 0 ? hp[hp.length - 1] : "";
            tbl = tbl.replace("\"", "").replace("`", "").replace("[", "").replace("]", "");
            if (tbl.isEmpty()) continue;
            // 括号内按顶层逗号切分，每段首个词即列名（跳过约束关键字）
            String body = sql.substring(p1 + 1);
            int depth = 0, cut = body.lastIndexOf(')');
            if (cut >= 0) body = body.substring(0, cut);
            java.util.List<String> cols = new java.util.ArrayList<>();
            StringBuilder seg = new StringBuilder();
            for (int i = 0; i < body.length(); i++) {
                char ch = body.charAt(i);
                if (ch == '(') depth++;
                if (ch == ')') depth--;
                if (ch == ',' && depth == 0) {
                    addCol(cols, seg.toString());
                    seg.setLength(0);
                } else {
                    seg.append(ch);
                }
            }
            addCol(cols, seg.toString());
            if (!cols.isEmpty()) map.put(tbl, cols);
        }
        return map;
    }

    private static void addCol(java.util.List<String> cols, String seg) {
        String t = seg.trim();
        if (t.isEmpty()) return;
        String first = t.split("\\s+")[0].replace("\"", "").replace("`", "").replace("[", "").replace("]", "");
        String up = first.toUpperCase(java.util.Locale.ROOT);
        if (up.equals("PRIMARY") || up.equals("FOREIGN") || up.equals("UNIQUE")
                || up.equals("CHECK") || up.equals("CONSTRAINT")) return;
        cols.add(first);
    }

    /** 在指定配置库上跑一个 SQL 文件（带哨兵，见 Diagnostics.PROBE_MARK 的约定） */
    private static String[] runSqlFile(File sqlFile, String db) {
        return Diagnostics.suExecPublic(
                "LD_LIBRARY_PATH=" + LIBS + " " + SQLITE + " " + db + " < " + sqlFile.getAbsolutePath()
                        + " 2>&1; echo " + Diagnostics.PROBE_MARK, 20);
    }

    /** 输出里出现 DBOK= 才算「库打开了」 */
    private static boolean looksLikeDb(String out) {
        return out != null && (out.contains("DBOK=") || out.contains("TBL="));
    }

    /** 兜底：在 /data/adb 下搜 modules_config.db（覆盖分支重命名目录的情况） */
    private static String findLspDb() {
        try {
            String[] r = Diagnostics.suExecPublic(
                    "find /data/adb -maxdepth 5 -name modules_config.db 2>/dev/null; echo "
                            + Diagnostics.PROBE_MARK, 20);
            if (!Diagnostics.suRan(r)) return null;
            for (String line : Diagnostics.suStdout(r).split("\n")) {
                String pth = line.trim();
                if (pth.startsWith("/") && pth.endsWith("modules_config.db")) return pth;
            }
        } catch (Throwable ignored) { }
        return null;
    }



    private static String join(java.util.List<String> l, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) { if (sb.length() > 0) sb.append(sep); sb.append(s); }
        return sb.toString();
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String clip(String s, int max) {
        if (s == null) return "";
        s = s.trim();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
