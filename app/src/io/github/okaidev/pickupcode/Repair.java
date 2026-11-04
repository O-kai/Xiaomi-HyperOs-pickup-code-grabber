package io.github.okaidev.pickupcode;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

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

    /**
     * 部署目录：v4.0.2 第八轮从 /data/local/tmp 搬到【应用私有目录】。
     *
     * <p>原因：/data/local/tmp 在应用数据之外，卸载 App 时系统不清理，
     * 手机上会永久残留 1.5MB 的 sqlite3 + 依赖库（用户明确要求卸载要干净）。
     * 私有目录随卸载自动删除；真机已验证 root 可以直接执行这里的二进制。
     */
    public static final String TARGET_DIR = RuntimePaths.RT_DIR;
    public static final String LIBS = RuntimePaths.LIBS;
    public static final String SQLITE = RuntimePaths.SQLITE;
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
     * 从 assets 释放 sqlite3 到应用私有目录。
     * 返回结果说明（人话，可直接展示/写入诊断报告）。
     */
    public static String deploySqlite3(Context ctx) {
        // 1. assets → 应用私有目录
        //
        // v4.0.2 第八轮：这里【不再需要 root】。
        // 旧实现是把资产先解到私有目录，再用 su cp 到 /data/local/tmp 并 chmod；
        // 现在目标就是私有目录，App 自己有写权限，纯 Java 拷贝即可。
        // 顺带把一个「每次部署必弹 Magisk 授权框」的来源也去掉了。
        File rt = new File(ctx.getFilesDir(), "rt");
        File libDst = new File(rt, "lib");
        if (!rt.exists() && !rt.mkdirs()) {
            return "部署失败：无法创建 " + rt.getAbsolutePath();
        }
        if (!libDst.exists() && !libDst.mkdirs()) {
            return "部署失败：无法创建 " + libDst.getAbsolutePath();
        }
        try {
            copyAsset(ctx.getAssets(), "sqlite3/sqlite3", new File(rt, "sqlite3"));
            String[] libs = ctx.getAssets().list("sqlite3/lib");
            if (libs == null || libs.length == 0) {
                return "部署失败：APK 内未找到 sqlite3 依赖库（安装包不完整）";
            }
            for (String l : libs) {
                copyAsset(ctx.getAssets(), "sqlite3/lib/" + l, new File(libDst, l));
            }
            // 权限：所有者可读写执行，其余无（应用私有目录本来也不对外）
            new File(rt, "sqlite3").setReadable(true, true);
            new File(rt, "sqlite3").setWritable(true, true);
            new File(rt, "sqlite3").setExecutable(true, true);
            File[] libFiles = libDst.listFiles();
            if (libFiles != null) {
                for (File f : libFiles) {
                    f.setReadable(true, true);
                    f.setExecutable(true, true);
                }
            }
        } catch (Throwable t) {
            return "部署失败（写私有目录）：" + t.getMessage();
        }

        // 3. 验证可执行（root 才能执行应用私有目录里的二进制）
        String[] v = Diagnostics.suExecPublic(
                RuntimePaths.ld() + SQLITE + " --version", 15);
        String ver = v == null ? "" : (v[0] == null ? "" : v[0]).trim();
        if (ver.contains("3.")) {
            return "部署成功 ✓ sqlite3 " + clip(ver, 60) + "（已放入应用私有目录）";
        }
        // 验证失败不算部署失败：文件已经就位，可能只是这次没拿到 root
        return "已部署到应用私有目录，但这次没能验证运行（" + clip(ver, 80) + "）"
                + "\n如果是第一次，请在 Magisk 弹窗里允许授权后重试一次。";
    }

    /**
     * 静默补齐 sqlite3 部署（v4.0.2 第八轮新增，用于版本升级后的迁移）。
     *
     * <p>为什么要它：部署位置从 /data/local/tmp 换到应用私有目录后，
     * 升级上来的用户私有目录里还没有 sqlite3——如果不补齐，
     * 第一次「标记已取」会因为找不到可执行文件而失败。
     *
     * <p>只做文件拷贝，<b>不做任何 root 验证</b>：拷贝不需要 root，
     * 而验证要跑 su 会弹授权框——那正是这一轮要消灭的东西。
     *
     * @return true 表示文件已就位
     */
    public static boolean ensureInstalledSilently(Context ctx) {
        if (ctx == null) return false;
        File rt = new File(ctx.getFilesDir(), "rt");
        File bin = new File(rt, "sqlite3");
        File libDst = new File(rt, "lib");
        if (bin.exists() && libDst.isDirectory()) {
            File[] libs = libDst.listFiles();
            if (libs != null && libs.length > 0) return true;
        }
        try {
            if (!libDst.exists() && !libDst.mkdirs()) return false;
            copyAsset(ctx.getAssets(), "sqlite3/sqlite3", bin);
            String[] libs = ctx.getAssets().list("sqlite3/lib");
            if (libs == null || libs.length == 0) return false;
            for (String l : libs) {
                copyAsset(ctx.getAssets(), "sqlite3/lib/" + l, new File(libDst, l));
            }
            bin.setReadable(true, true);
            bin.setWritable(true, true);
            bin.setExecutable(true, true);
            File[] fs = libDst.listFiles();
            if (fs != null) {
                for (File f : fs) {
                    f.setReadable(true, true);
                    f.setExecutable(true, true);
                }
            }
            Log.i("PICKUPDEBUG", "ensureInstalledSilently: deployed to " + rt.getAbsolutePath());
            return true;
        } catch (Throwable t) {
            Log.w("PICKUPDEBUG", "ensureInstalledSilently failed: " + t);
            return false;
        }
    }

    /** 自动清理只做一次（避免每次 root 会话都跑） */
    private static final String KEY_LEGACY_CLEANED = "legacy_cleaned_v802";

    /**
     * 首次升级后自动清理旧残留（不需要额外 root —— 复用已有的常驻通道）。
     *
     * <p>只做一次，并用偏好记住，之后不再执行。
     * 如果当前进程没有 root 通道，直接跳过（等下次）。
     */
    public static void autoCleanupLegacyOnce(Context ctx) {
        if (ctx == null || !RootShell.isReady()) return;
        try {
            if (ctx.getSharedPreferences("dedup", Context.MODE_PRIVATE)
                    .getBoolean(KEY_LEGACY_CLEANED, false)) return;
        } catch (Throwable t) {
            return;
        }
        String L = RuntimePaths.LEGACY_DIR;
        String script = "rm -f " + L + "/sqlite3 " + L + "/sqlite3.bak "
                + L + "/*.sql " + L + "/*.db " + L + "/wtest 2>/dev/null; "
                + "rm -rf " + L + "/lib 2>/dev/null; echo LEGACY_CLEANED";
        String out = RootShell.exec(script, 20000);
        if (out != null && out.contains("LEGACY_CLEANED")) {
            try {
                ctx.getSharedPreferences("dedup", Context.MODE_PRIVATE).edit()
                        .putBoolean(KEY_LEGACY_CLEANED, true).apply();
            } catch (Throwable ignored) { }
            Log.i("PICKUPDEBUG", "autoCleanupLegacyOnce: ok");
        }
    }

    // ================================================================
    //  配置快照的可读性（SELinux 标签）
    // ================================================================

    /** 自动修复只做一次的标记 */
    private static final String KEY_LABEL_FIXED = "config_label_fixed_v1002";

    /**
     * 让 {@code system_server} 读得到配置快照与开关文件（v4.0.2 第十轮）。
     *
     * <p><b>为什么需要</b>：App 用 root 往 {@code /data/local/tmp/pickup_sqlite}
     * 写文件时，新文件的 SELinux 标签是 {@code shell_data_file}（继承目录），
     * 个别 ROM 上 system_server 读它会被 SELinux 拦下——表现是
     * 「设置里改了规则/白名单，通知通道却一直用旧规则」，
     * 而且日志里只有一行 reloaded，看不出失败。
     *
     * <p>修法：{@code chmod 755} 目录 + {@code chmod 644} 文件 +
     * {@code chcon u:object_r:system_data_file:s0}。
     * 真机（HyperOS 4.0 / Android 17）实测 chcon 可用、rc=0。
     *
     * <p><b>失败不影响功能</b>：chcon 失败时命令仍返回成功（{@code || true}），
     * 因为文件本身已经写好，只是可能读不到；此时系统进程会回落到编译内置规则。
     *
     * @return true 表示命令执行且三个文件标签都已是 system_data_file
     */
    public static boolean fixConfigLabels(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("chmod 755 ").append(RuntimePaths.SYNC_DIR).append(" 2>/dev/null; ");
        for (String f : RuntimePaths.CONFIG_FILES) {
            sb.append("chmod 644 ").append(f).append(" 2>/dev/null; ");
        }
        sb.append("chcon u:object_r:system_data_file:s0 ")
                .append(RuntimePaths.SYNC_DIR).append(" ");
        for (String f : RuntimePaths.CONFIG_FILES) {
            sb.append(f).append(" ");
        }
        sb.append("2>/dev/null || true; ");
        sb.append("ls -Z ");
        for (String f : RuntimePaths.CONFIG_FILES) {
            sb.append(f).append(" ");
        }
        sb.append("2>/dev/null; echo LABEL_DONE");

        String[] r = Diagnostics.suExecPublic(sb.toString(), 15);
        String out = r == null ? "" : (r[0] == null ? "" : r[0]);
        if (!out.contains("LABEL_DONE")) {
            Log.w("PICKUPDEBUG", "fixConfigLabels failed: " + clip(out, 160));
            return false;
        }
        // 三个文件里只要还有一个不是 system_data_file，就认为没修好
        int good = 0;
        for (String line : out.split("\n")) {
            if (line.contains("system_data_file")) good++;
        }
        boolean ok = good >= RuntimePaths.CONFIG_FILES.length;
        Log.i("PICKUPDEBUG", "fixConfigLabels ok=" + ok + " (" + good + "/"
                + RuntimePaths.CONFIG_FILES.length + ")\n" + clip(out, 300));
        if (ok && ctx != null) {
            try {
                ctx.getSharedPreferences("dedup", Context.MODE_PRIVATE).edit()
                        .putBoolean(KEY_LABEL_FIXED, true).apply();
            } catch (Throwable ignored) { }
        }
        return ok;
    }

    /**
     * 有 root 会话时顺手修一次（只做一次，之后靠每次写入自带的 chcon 维持）。
     *
     * <p>为什么需要「一次」：老版本写下的 user_rules.json / enable_noti_hook
     * 从来没被 chcon 过，升级后不会自动变。第一次体检时补一次即可。
     */
    public static void fixConfigLabelsOnce(Context ctx) {
        if (ctx == null || !RootShell.isReady()) return;
        try {
            if (ctx.getSharedPreferences("dedup", Context.MODE_PRIVATE)
                    .getBoolean(KEY_LABEL_FIXED, false)) return;
        } catch (Throwable t) {
            return;
        }
        fixConfigLabels(ctx);
    }

    /**
     * 清理旧版本留在应用数据目录之外的残留（v4.0.2 第八轮）。
     *
     * <p>旧版把 sqlite3 + 依赖库部署在 /data/local/tmp/pickup_sqlite，
     * 卸载 App 时系统不会清理。这里删掉二进制与依赖库（约 1.5MB），
     * 以及历史调试留下的临时 sql/db 文件；
     * <b>跨进程同步用的几个文本文件保留</b>——系统进程要靠它们读配置，
     * 删了会让通知通道回落到编译内置规则。
     *
     * @return 人话结果，可直接展示
     */
    public static String cleanupLegacy(Context ctx) {
        String L = RuntimePaths.LEGACY_DIR;
        String cmd = "rm -f " + L + "/sqlite3 " + L + "/sqlite3.bak "
                + L + "/*.sql " + L + "/*.db " + L + "/wtest 2>/dev/null; "
                + "rm -rf " + L + "/lib 2>/dev/null; "
                + "ls -l " + L + " 2>&1 | head -20; echo CLEAN_DONE";
        String[] r = Diagnostics.suExecPublic(cmd, 20);
        String out = r == null ? "" : (r[0] == null ? "" : r[0]);
        if (!out.contains("CLEAN_DONE")) {
            return "清理失败（需要 root）：" + clip(out, 120);
        }
        Log.i("PICKUPDEBUG", "cleanupLegacy done: " + clip(out, 200));
        return "已清理旧版本残留（sqlite3 与依赖库）。"
                + "\n现在 sqlite3 装在应用私有目录，卸载 App 时会随应用数据一起删掉。";
    }

    // ================================================================
    //  sqlite3 深度自检与自动修复
    // ================================================================

    /**
     * sqlite3 自检结果。
     *
     * <p>体检里的「sqlite3 部署」只看文件在不在；文件在但动态库缺失、
     * 二进制损坏、无法真正打开数据库的情况，原来体检是发现不了的——
     * 表现是「写入待办一直失败」，但用户看不到原因。
     */
    public static class SqliteStatus {
        /** 文件已部署 */
        public boolean installed;
        /** 动态库目录非空 */
        public boolean libsOk;
        /** --version 能跑出 SQLite 3.x */
        public boolean runnable;
        /** 能真正建表-写入-查询 */
        public boolean dbWorks;
        /** sqlite3 版本串 */
        public String version = "";
        /** 失败原因（人话） */
        public String detail = "";

        public boolean ok() {
            return installed && runnable && dbWorks;
        }
    }

    /** 深度自检：文件 → 动态库 → 可执行 → 真正读写一个库 */
    public static SqliteStatus checkSqlite3Deep(Context ctx) {
        SqliteStatus st = new SqliteStatus();

        String[] r = Diagnostics.suExecPublic(
                "ls -l " + SQLITE + " 2>&1; echo " + Diagnostics.PROBE_MARK, 15);
        if (!Diagnostics.suRan(r)) {
            st.detail = "root 命令没跑起来：" + clip(Diagnostics.suErr(r), 120);
            return st;
        }
        String all = Diagnostics.suStdout(r);
        // 判据顺序：文件不存在时 ls 的报错里也带着 sqlite3 这个路径，
        // 必须先判「No such file」，否则会把「没部署」误报成「已部署」。
        st.installed = !all.contains("No such file") && !all.contains("Not a directory")
                && all.contains("sqlite3");
        if (!st.installed) {
            st.detail = SQLITE + " 不存在";
            return st;
        }

        String[] l = Diagnostics.suExecPublic(
                "ls " + LIBS + " 2>/dev/null | wc -l; echo " + Diagnostics.PROBE_MARK, 15);
        String libCount = Diagnostics.suRan(l) ? Diagnostics.suStdout(l).trim() : "0";
        st.libsOk = !"0".equals(libCount) && !"".equals(libCount);

        String[] v = Diagnostics.suExecPublic(
                "LD_LIBRARY_PATH=" + LIBS + " " + SQLITE + " --version 2>&1; echo "
                        + Diagnostics.PROBE_MARK, 15);
        st.version = (Diagnostics.suRan(v) ? Diagnostics.suStdout(v) : "").trim();
        st.runnable = st.version.contains("3.");
        if (!st.runnable) {
            st.detail = "二进制无法执行：" + clip(st.version, 100);
            return st;
        }

        // 功能验证：建表 → 写入 → 查询 → 删除
        //
        // SQL 必须走「文件重定向」而不是内联：
        // suExec 会把命令里的双引号换成单引号（cmd.replace("\"", "'")），
        // 内联 SQL 会被 shell 当成字符串字面量，执行必然失败——
        // 这也是本类 runSqlFile() 存在的同一个原因。
        String db = "/data/local/tmp/pickup_selftest.db";
        String sqlPath = null;
        try {
            File sf = new File(ctx.getCacheDir(), "pickup_selftest.sql");
            writeText(sf, "create table if not exists t(a);\n"
                    + "insert into t values(1);\n"
                    + "select count(*) from t;\n");
            sqlPath = sf.getAbsolutePath();
        } catch (Throwable ignored) { }
        if (sqlPath == null) {
            st.detail = "无法写入自检 SQL 文件";
            return st;
        }
        String[] t = Diagnostics.suExecPublic(
                "rm -f " + db + "; LD_LIBRARY_PATH=" + LIBS + " " + SQLITE + " " + db
                        + " < " + sqlPath + " 2>&1; rm -f " + db
                        + "; echo " + Diagnostics.PROBE_MARK, 20);
        String tout = Diagnostics.suRan(t) ? Diagnostics.suStdout(t) : "";
        // 期望输出：1（表里一行）。sqlite3 会把 count(*) 的结果打在 stdout。
        st.dbWorks = tout.contains("1");
        if (!st.dbWorks) {
            st.detail = "无法读写数据库：" + clip(tout, 100);
        } else if (!st.libsOk) {
            st.detail = "动态库目录为空或缺失（" + LIBS + "）";
        }
        return st;
    }

    /**
     * 自检 + 自动修复。
     *
     * <p>坏掉时先把当前二进制备份成 sqlite3.bak 再重新部署；
     * 部署后仍不通过就把备份改回去——宁可保持原状，也不要留一个更坏的环境。
     */
    public static String repairSqlite3(Context ctx) {
        SqliteStatus before = checkSqlite3Deep(ctx);
        if (before.ok()) {
            return "sqlite3 自检通过：" + clip(before.version, 60);
        }
        String backup = SQLITE + ".bak";
        Diagnostics.suExecPublic(
                "cp -f " + SQLITE + " " + backup + " 2>/dev/null; echo " + Diagnostics.PROBE_MARK, 15);
        String deploy = deploySqlite3(ctx);
        SqliteStatus after = checkSqlite3Deep(ctx);
        if (after.ok()) {
            return "已修复并通过自检：" + clip(after.version, 60)
                    + "（原因为：" + clip(before.detail, 80) + "）";
        }
        // 回滚
        Diagnostics.suExecPublic(
                "cp -f " + backup + " " + SQLITE + " 2>/dev/null; echo " + Diagnostics.PROBE_MARK, 15);
        return "修复失败，已回滚到原文件。\n部署输出：" + clip(deploy, 90)
                + "\n自检原因：" + clip(after.detail, 90);
    }

    /** 写文本文件（UTF-8），供自检 SQL 用 */
    private static void writeText(File f, String text) throws Exception {
        File parent = f.getParentFile();
        if (parent != null) parent.mkdirs();
        java.io.OutputStream os = new java.io.FileOutputStream(f);
        try {
            os.write(text.getBytes("UTF-8"));
        } finally {
            try { os.close(); } catch (Throwable ignored) { }
        }
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
