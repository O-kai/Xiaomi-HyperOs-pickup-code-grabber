package io.github.okaidev.pickupcode;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 系统进程直写兜底（ColorOS 冻结免疫，v2.7.0 默认关闭）：
 * 短信 Hook 运行在系统进程（com.android.providers.telephony）里，正常情况下它把短信事件转发给
 * 模块自身进程处理。但当用户把模块 App 从最近任务划掉、被 ColorOS 冻结后，模块 App 的 Provider
 * 无法被唤醒（Unknown authority），转发必然失败——此时在本进程内直接 su + sqlite3 把取件码写入
 * 目标待办库，保证「划掉后台也能写」。
 *
 * 约束与设计（上游整合版默认【不启用】，需在设置页手动开启）：
 *  - 开关状态由 Hook 进程读模块 APK 内 assets 标志文件决定（系统进程读不到模块 SharedPreferences，
 *    而 assets 是只读打包产物，su/root 均可读）：assets/enable_sys_direct_write 存在且内容为 "1" 才生效；
 *    设置页开关通过「su 写 /data/local/tmp/pickup_sqlite/enable_sys_direct_write」同步状态，
 *    本类启动时优先读 /data/local/tmp 副本（可变），不存在再回落 assets（只读默认关）；
 *  - 仅在模块 App 转发失败时由 SmsBridge 调用（正常时不会走到这里）；
 *  - 写库前先查目标表是否已有同一取件码的未完成条目（进程外去重，防与模块 App 双写/重复事件）；
 *  - 后端按 ROM 属性判定（无 Context 依赖）：xiaomi / coloros_todo；
 *  - 需要给本系统进程授权 root（Magisk/KernelSU 弹窗选允许），否则自动跳过并记录日志；
 *  - 兜底只负责「写入」，不弹模块通知（冻结态无法用模块身份通知，避免误导归属）。
 *
 * 风险提示（设置页开关旁有同款文案）：给常驻系统进程授权 root 会扩大攻击面，
 * 请自行评估；不开启时本类代码路径完全不执行。
 */
public class SystemDirectWriter {

    private static final String TAG = "PICKUPDEBUG";
    private static final String LIBS = "/data/local/tmp/pickup_sqlite/lib";
    private static final String SQLITE = "/data/local/tmp/pickup_sqlite/sqlite3";

    /** 开关标志文件（可变副本，设置页写入）：/data/local/tmp/pickup_sqlite/enable_sys_direct_write */
    private static final String FLAG_TMP = "/data/local/tmp/pickup_sqlite/enable_sys_direct_write";
    /** APK 内只读默认值文件（构建产物，内容恒为 "0" 或不存在 = 关） */
    private static final String FLAG_ASSET = "enable_sys_direct_write";

    /** 开关探测缓存（每进程一次；探测要跑 su，避免每条短信都开销） */
    private static volatile Boolean enabledCache = null;

    /** 黑名单默认词（无模块 App 偏好可用时按此过滤） */
    private static final String[] DEFAULT_BLACKLIST = {
            "12306", "验证码", "余额", "充值", "账单", "银行", "优惠券", "退订"
    };

    /** 单进程节流：同指纹 10 秒内只直写一次（防多 Hook 点并发刷写） */
    private static volatile String lastFingerprint = "";
    private static volatile long lastWriteAt = 0L;

    /**
     * v3.1.0：跨进程配置快照（官方规则 / 黑名单 / 模板 / 写入目标）的进程内缓存。
     * 兜底通道每条短信都会走到本方法，若每次都读文件 + 重新解析 + 重跑 21 条冒烟用例会明显拖慢，
     * 故做缓存。但【不能永久缓存】——com.android.providers.telephony 是长驻进程，
     * 可存活数天，用户改了设置后不重启就永远读不到新值。
     * 因此改为：按 TTL 过期（30 分钟）后重读，既避免每次开销，又不会长期吃陈旧配置。
     */
    private static volatile ProcessSync.State stateCache = null;
    private static volatile long stateCacheAt = 0L;
    private static final long STATE_CACHE_TTL_MS = 30L * 60 * 1000;

    /** v3.1.0：读取并缓存跨进程配置快照（按 TTL 过期，见 stateCache 注释） */
    private static ProcessSync.State loadStateOnce() {
        ProcessSync.State c = stateCache;
        if (c != null && System.currentTimeMillis() - stateCacheAt < STATE_CACHE_TTL_MS) {
            return c;
        }
        synchronized (SystemDirectWriter.class) {
            if (stateCache == null
                    || System.currentTimeMillis() - stateCacheAt >= STATE_CACHE_TTL_MS) {
                stateCache = ProcessSync.load();
                stateCacheAt = System.currentTimeMillis();
            }
        }
        return stateCache;
    }

    /** 兜底写入总开关（见类注释）：读 /data/local/tmp 标志，回落 APK assets 默认值 */
    public static boolean enabled(Context ctx) {
        Boolean c = enabledCache;
        if (c != null) return c;
        synchronized (SystemDirectWriter.class) {
            if (enabledCache != null) return enabledCache;
            enabledCache = readFlag(ctx);
            Log.i(TAG, "SYS-WRITE enabled=" + enabledCache);
            return enabledCache;
        }
    }

    private static boolean readFlag(Context ctx) {
        // 1) 可变副本（设置页 su 写入）优先
        try {
            String[] r = runShortCmd(ctx, "cat " + FLAG_TMP, 8);
            if (r != null && r[0] != null && r[0].trim().equals("1")) return true;
            if (r != null && r[0] != null && r[0].trim().equals("0")) return false;
        } catch (Throwable ignored) { }
        // 2) 回落 APK assets（只读默认 = 0，构建产物恒为关闭）
        return false;
    }

    public static void fallback(Context ctx, String sender, String body) {
        try {
            if (!enabled(ctx)) return;
            if (ctx == null || body == null || body.trim().isEmpty()) return;
            // v3.0.0：本方法运行在系统进程（providers.telephony），需从 root 同步文件加载用户规则，
            // 否则冷启动兜底路径会静默使用编译内置规则
            try {
                if (PickupExtractor.userRuleCount() == 0) {
                    PickupExtractor.loadUserRules(null, UserRules.loadForSystemProcess());
                }
            } catch (Throwable ignored) { }
            // v3.1.0 P1/P3：官方规则与用户偏好（黑名单/模板）此前在本进程是编译内置/硬编码的，
            // 与用户在 App 里的设置不一致。这里一次性拉齐：
            //   · 官方规则 → 用 ProcessSync 同步快照（短信通道热更到哪一版，这里就是哪一版）
            //   · 黑名单   → 用用户实际配置（读不到才回落默认 8 词）
            //   · 模板     → 用用户选的模式与自定义模板（此前这里写死 MODE_FULL）
            ProcessSync.State st = loadStateOnce();
            PickupExtractor.initForSystemProcess(st != null ? st.rulesJson : null);
            String[] blacklist = DEFAULT_BLACKLIST;
            int mode = TodoWriter.MODE_FULL;
            String customTpl = null;
            if (st != null) {
                // v3.1.0：分隔符与 TodoWriter.isBlacklisted 保持一致（原先此处少了 、 和 ；，
                // 用户用「、」或「;」分隔的黑名单词在兜底通道会被当成一个整词而静默失效）。
                // v3.1.0：快照里只要有 blacklist 字段就照用，哪怕它是空串。
                // 此前「空串 → 回落默认 8 词」，与用户在设置页看到的承诺矛盾——
                // 清空黑名单时 App 明确 Toast「所有短信不再被关键词过滤」，
                // 而兜底通道仍在按 8 词过滤。只有快照字段【缺失】（旧版本文件）才回落默认。
                if (st.blacklist != null) {
                    blacklist = st.blacklist.trim().isEmpty()
                            ? new String[0]
                            : st.blacklist.split("[,，、;；\\s]+");
                }
                mode = st.todoMode;
                if (st.customTpl != null && !st.customTpl.trim().isEmpty()) {
                    customTpl = st.customTpl;
                }
            }
            for (String kw : blacklist) {
                if (kw == null || kw.trim().isEmpty()) continue;
                if (body.contains(kw.trim())) return;
            }
            if (!PickupExtractor.lookLikePickupSms(sender, body)) return;
            List<String> codes = PickupExtractor.extract(sender, body);
            if (codes.isEmpty()) return;

            String fp = sender + "|" + body.hashCode();
            long now = System.currentTimeMillis();
            if (fp.equals(lastFingerprint) && now - lastWriteAt < 10000L) {
                XposedEntry.log("SYS-WRITE throttle skip: " + fp);
                return;
            }

            // v3.1.0：优先用用户在设置页手动指定的写入目标（来自 ProcessSync 快照）。
            // 没有指定时才按 ROM 属性推断——否则用户把目标改成与推断不同的那个时，
            // 主通道和兜底通道会写进两个不同的库，用户在其中一个 App 里看不到。
            String backend = (st != null && st.backend != null && !st.backend.trim().isEmpty())
                    ? st.backend.trim()
                    : NotesBackend.propsBackend();
            String db = NotesBackend.dbPathOf(backend);
            String source = TodoWriter.resolveSource(sender, body);
            // v3.1.0 P3：此处原先调用 extractPlace(body) 不传 sender，
            // 导致「带发送方范围的用户规则」在兜底通道取不到地点 → 与短信通道不一致
            String place = TodoWriter.extractPlace(sender, body);
            String time = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(new Date());

            StringBuilder sql = new StringBuilder(".timeout 5000\n");
            int wrote = 0;
            for (String code : codes) {
                String content = TodoWriter.buildContent(mode, customTpl, code, source, place, time);
                String title = TodoWriter.buildTitle(code, content);
                if (exists(ctx, backend, db, code)) {
                    XposedEntry.log("SYS-WRITE dedup skip: " + code);
                    continue;
                }
                sql.append(NotesBackend.insertSqlOf(backend, content, title));
                wrote++;
            }
            if (wrote == 0) return;

            lastFingerprint = fp;
            lastWriteAt = now;
            int rc = runSqlFile(ctx, sql.toString(), backend);
            XposedEntry.log("SYS-WRITE backend=" + backend + " codes=" + wrote + " rc=" + rc);
        } catch (Throwable t) {
            XposedEntry.log("SYS-WRITE err: " + t);
        }
    }

    /** 目标表是否已有包含该取件码的未完成条目 */
    private static boolean exists(Context ctx, String backend, String db, String code) {
        String out = runSqlForOutput(ctx, db, NotesBackend.existsSqlOf(backend, code));
        return out != null && out.trim().matches("\\d+") && !out.trim().equals("0");
    }

    /**
     * 写 SQL 文件并 su sqlite3 执行；返回 0=成功，-1=失败
     *
     * v3.1.0：backend 改为显式传入。
     * 此前本方法内部用 NotesBackend.propsBackend() 再算一次库路径，
     * 而调用方 fallback() 用的是 ProcessSync 快照里的用户手选目标——
     * 两者不一致时会「查重查 A 库、INSERT 打进 B 库」，用户手动改过写入目标就必现。
     */
    private static int runSqlFile(Context ctx, String sql, String backend) {
        try {
            File dir = ctx.getFilesDir();
            File sqlFile = new File(dir, "sys_direct.sql");
            try (FileOutputStream fos = new FileOutputStream(sqlFile)) {
                fos.write(sql.getBytes("UTF-8"));
            }
            String[] suBins = {"su", "/product/bin/su", "/system/bin/su", "/sbin/su", "/su/bin/su"};
            String effBackend = (backend == null || backend.trim().isEmpty())
                    ? NotesBackend.propsBackend() : backend.trim();
            String db = NotesBackend.dbPathOf(effBackend);
            XposedEntry.log("SYS-WRITE backend=" + effBackend + " db=" + db);
            for (String suBin : suBins) {
                String cmd = suBin + " -M -c \"id; " + NotesBackend.chmodCmdOf(effBackend)
                        + "LD_LIBRARY_PATH=" + LIBS + " " + SQLITE + " " + db
                        + " < " + sqlFile.getAbsolutePath() + "\"";
                Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
                StringBuilder out = new StringBuilder();
                StringBuilder err = new StringBuilder();
                drain(p.getInputStream(), out);
                drain(p.getErrorStream(), err);
                int code = p.waitFor();
                if (code == 0) return 0;
                Log.i(TAG, "SYS-WRITE su attempt(" + suBin + ") exit=" + code
                        + " err=" + err.toString().trim());
            }
            return -1;
        } catch (Throwable t) {
            Log.e(TAG, "SYS-WRITE runSql err: " + t);
            return -1;
        }
    }

    /** 执行只读查询（exists 探针）；返回输出文本 */
    private static String runSqlForOutput(Context ctx, String db, String query) {
        try {
            File dir = ctx.getFilesDir();
            File sqlFile = new File(dir, "sys_probe.sql");
            try (FileOutputStream fos = new FileOutputStream(sqlFile)) {
                fos.write((".timeout 5000\n" + query).getBytes("UTF-8"));
            }
            String[] suBins = {"su", "/product/bin/su", "/system/bin/su", "/sbin/su", "/su/bin/su"};
            for (String suBin : suBins) {
                String cmd = suBin + " -M -c \"LD_LIBRARY_PATH=" + LIBS + " " + SQLITE + " " + db
                        + " < " + sqlFile.getAbsolutePath() + "\"";
                Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
                StringBuilder out = new StringBuilder();
                StringBuilder err = new StringBuilder();
                drain(p.getInputStream(), out);
                drain(p.getErrorStream(), err);
                if (p.waitFor() == 0) return out.toString();
            }
            return null;
        } catch (Throwable t) {
            Log.e(TAG, "SYS-WRITE probe err: " + t);
            return null;
        }
    }

    /** 短命令执行（开关探测用；root 读文件一行输出） */
    private static String[] runShortCmd(Context ctx, String cmd, int timeoutSec) {
        try {
            String[] suBins = {"su", "/product/bin/su", "/system/bin/su", "/sbin/su", "/su/bin/su"};
            for (String suBin : suBins) {
                Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c",
                        suBin + " -c \"" + cmd + "\""});
                StringBuilder out = new StringBuilder();
                StringBuilder err = new StringBuilder();
                drain(p.getInputStream(), out);
                drain(p.getErrorStream(), err);
                if (p.waitFor() == 0) return new String[]{out.toString(), err.toString()};
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
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
}
