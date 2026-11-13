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
 *  2) lsposedStatus：通过现代框架服务读取激活状态与作用域，
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
    /**
     * 作用域必需目标（v2.7.0 按 ROM 收敛）：
     *  - 通用（Hook 目标，两家 ROM 都要）：android、com.android.phone、com.android.mms；
     *  - 小米笔记不再是必需项（v4.1.0）：写入走 root 直写，不经过 Hook；
     *  - com.android.providers.telephony 为可选（S8 主通道所在；实测 HyperOS 将其合并进
     *    phone 进程，勾上更稳，未勾也不报缺）。
     *  写入目标 App（笔记/日历）不是 Hook 目标（模块靠 su 直写其库），无需勾选。
     */
    public static String[] scopeRequired(Context ctx) {
        java.util.List<String> need = new java.util.ArrayList<>();
        need.add("system");                       // 框架：system/android 任一即可
        need.add("com.android.phone");             // 电话进程
        need.add(smsAppPackage(ctx));              // 短信 App（按机型取实际安装的那个）
        // v4.1.0：从「建议」升级为「必须」——它是短信库主通道，
        // 少勾时短信仍可能抓到（走电话进程），但会少一层兜底，行为不稳定。
        // 只在本机确实装了这个包时才要求，避免某些 ROM 上永远「缺一项」。
        if (isInstalled(ctx, SCOPE_OPTIONAL)) need.add(SCOPE_OPTIONAL);
        if (RearScreen.available(ctx)) need.add(RearHostHook.HOST);
        if (ctx.getSharedPreferences("dedup", Context.MODE_PRIVATE).getBoolean("app_parcel_enabled", false)) {
            for (String pkg : new String[]{AppParcelData.CAINIAO, AppParcelData.PINDUODUO})
                if (isInstalled(ctx, pkg)) need.add(pkg);
        }
        return need.toArray(new String[0]);
    }

    /** 各 ROM 的短信 App 包名，按优先级；返回本机真正装着的那个 */
    private static String smsAppPackage(Context ctx) {
        String[] cands = Honor.isHonor()
                ? new String[]{Honor.PKG_MMS, "com.android.mms", "com.coloros.mms", "com.oplus.mms"}
                : new String[]{"com.android.mms", "com.coloros.mms", "com.oplus.mms", Honor.PKG_MMS};
        for (String p : cands) {
            if (isInstalled(ctx, p)) return p;
        }
        return "com.android.mms";   // 都查不到（极少见）→ 回落 AOSP 包名，至少不误伤老逻辑
    }

    private static boolean isInstalled(Context ctx, String pkg) {
        try {
            ctx.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
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

    /**
     * 彻底清空跨进程同步目录（v4.0.3，「卸载前清理」用）。
     *
     * <p>和 {@link #cleanupLegacy} 的区别：那个只删旧版本的二进制、保留配置，
     * 因为还在用的用户需要它们；这个是把目录整个清空，面向「马上就卸载」的场景。
     *
     * <p>只删已知的三个文件名再 {@code rmdir}（不存在的就跳过），
     * 不用 {@code rm -rf} —— 万一常量写错，也不会连带删掉别的目录。
     *
     * <p>删完会记一个「待同步」标记：如果用户其实还想继续用，
     * 下次任何需要 root 的动作都会把配置重新写回来（自愈）。
     *
     * @return 人话结果，可直接展示
     */
    public static String purgeSyncDir(Context ctx) {
        StringBuilder sb = new StringBuilder();
        for (String f : RuntimePaths.CONFIG_FILES) {
            sb.append("rm -f ").append(f).append("; ");
        }
        sb.append("rmdir ").append(RuntimePaths.SYNC_DIR).append(" 2>/dev/null; ");
        sb.append("ls ").append(RuntimePaths.SYNC_DIR).append(" 2>&1 | head -5; ");
        sb.append("echo PURGE_DONE");

        String[] r = Diagnostics.suExecPublic(sb.toString(), 20);
        String out = r == null ? "" : (r[0] == null ? "" : r[0]);
        if (!out.contains("PURGE_DONE")) {
            return "清理失败（需要 root）：" + clip(out, 120);
        }
        Log.i("PICKUPDEBUG", "purgeSyncDir done: " + clip(out, 200));
        // 万一用户之后还想继续用：标记待同步，下次用到 root 时会自动重建配置
        ProcessSync.markPending(ctx);
        return "已清空同步目录。\n卸载 App 后不会在 /data/local/tmp 留下任何文件。"
                + "\n\n如果之后还要继续用：随便做一次需要 root 的操作"
                + "（比如标记已取），配置会自动重建。";
    }

    // ================================================================
    //  配置快照的可读性（SELinux 标签）
    // ================================================================

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
        public boolean scopeReadable;   // 框架服务作用域查询是否成功
        public boolean moduleEnabled;   // 模块在 LSPosed 中已启用
        public String missingScope;     // 缺失的必需作用域（逗号分隔；空=齐全）
        public String scopeList;        // 当前作用域（逗号分隔）
        public String detail;           // 附加上下文（失败原因等）
        /** 框架服务返回的作用域摘要，供诊断报告使用。 */
        public String rawOut = "";
    }

    /** Framework service is the authority for activation and per-user scope. No private DB access. */
    public static LsposedStatus lsposedStatus(Context ctx) {
        LsposedStatus st = new LsposedStatus();
        st.rawOut = "";
        st.scopeList = "";
        st.detail = FrameworkClient.description();
        st.moduleEnabled = FrameworkClient.connected();
        if (!st.moduleEnabled) return st;
        try {
            java.util.List<String> scope = FrameworkClient.scope();
            st.scopeReadable = true; // The framework scope query succeeded.
            st.scopeList = join(scope, ", ");
            java.util.List<String> missing = new java.util.ArrayList<>();
            for (String need : scopeRequired(ctx)) if (!scope.contains(need)) missing.add(need);
            st.missingScope = missing.isEmpty() ? null : join(missing, "、");
            st.rawOut = "API102 scope=" + st.scopeList;
        } catch (RuntimeException e) { st.detail = "框架作用域查询失败，请重新打开应用"; }
        return st;
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
