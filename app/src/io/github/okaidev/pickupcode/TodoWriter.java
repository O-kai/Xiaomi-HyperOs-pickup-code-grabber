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

/**
 * 待办写入器（P2）：在模块自身进程运行。
 * 流程：提取取件码 → 去重（本地指纹 On）→ su sqlite3 直写目标库（堆栈置顶）→ 日志
 * v2.7.0：目标库由 NotesBackend 决定（小米笔记 todo.db / ColorOS 日历 tasks.db / ColorOS 便签
 * nearme_note.db），置顶与完成算法随后端自动切换（多 ROM 支持）。
 */
public class TodoWriter {

    private static final String TAG = "PICKUPDEBUG";
    // sqlite3 部署在【应用私有目录】（卸载时随应用数据自动删除），依赖库同目录
    private static final String LIBS = RuntimePaths.LIBS;
    private static final String SQLITE = RuntimePaths.SQLITE;
    private static final String PREFS = "dedup";
    private static final int DEDUP_MAX = 500;

    /** 最近一次 su/sqlite 执行记录（诊断报告用）：含失败原因与命令输出 */
    private static volatile String lastWriteDiag =
            "尚未执行过写库操作（还没有收到取件短信/跑过一键测试）";

    public static String getLastWriteDiag() { return lastWriteDiag; }

    /**
     * v3.0.0：最近一次处理中被去重拦截的指纹（多条以 ; 分隔）
     * 用途：区分「写入失败」与「命中去重（本就写过了）」——
     * 两者返回给调用方的 wrote 列表都是空的，若不区分会被误报成失败。
     */
    private static volatile String lastDedupHits = "";

    public static String getLastDedupHits() { return lastDedupHits; }

    /**
     * 黑名单默认关键词（唯一来源）。
     *
     * <p>v4.0 收口：这段词表原先在四个地方各抄一份——写入判断、跨进程快照、
     * 系统进程兜底、界面上的「恢复默认」。抄四份的代价是改了一处另三处不跟着改，
     * 于是「界面里显示的默认」和「真正生效的默认」可能对不上。
     */
    public static final String DEFAULT_BLACKLIST = "12306,验证码,余额,充值,账单,银行,优惠券,退订";

    /** 模板模式：0=极简（仅取件码） 1=完整（默认） 2=自定义模板 */
    public static final int MODE_MIN = 0;
    public static final int MODE_FULL = 1;
    public static final int MODE_CUSTOM = 2;

    /**
     * 自定义模板的默认内容（也是「完整」模式的实际写法）。
     *
     * <p>v4.0 收口：这段字符串原先在三个地方各抄一份（写入默认值、
     * 空模板兜底、界面里的模板编辑初值）。抄三份的代价是改了一处
     * 另两处不会跟着改——正是本项目在规则集上踩过的坑，这里不再重复。
     */
    public static final String TPL_DEFAULT = "取件码 {code}｜{source}｜{place}｜{time}";

    /**
     * 单条事件的写入结果（v4.0.2 第八轮，来自 PR #14）。
     *
     * <p>为什么需要它：原来 Provider 只能用全局的 {@code lastWriteDiag} /
     * {@code lastDedupHits} 判断「这条事件到底写成功没有」。这两份状态是
     * <b>进程级共享</b>的，两条短信并发时，后一条的结果会覆盖前一条——
     * 于是把 A 的失败报到 B 头上，或者反过来把真失败当成去重吞掉。
     *
     * <p>现在每条事件带着自己的结果返回，跨进程回执用它，不再看全局变量。
     */
    public static final class HandleResult {
        /** 真正写进待办的取件码 */
        public final List<String> written;
        /** 本条事件解析出的取件码个数 */
        public final int codeCount;
        /** 命中去重（之前写过）的个数 */
        public final int dedupCount;
        /** 写入失败的个数 */
        public final int failedCount;
        /** 黑名单 / 无码 / 全部去重 / 全写失败 / 部分成功 / 全成功 */
        public final String status;
        /** 最近一次失败原因（供诊断，不含短信正文） */
        public final String diagnostic;

        HandleResult(List<String> written, int codeCount, int dedupCount,
                     int failedCount, String status, String diagnostic) {
            this.written = written;
            this.codeCount = codeCount;
            this.dedupCount = dedupCount;
            this.failedCount = failedCount;
            this.status = status;
            this.diagnostic = diagnostic;
        }
    }

    /** 入口：模块进程收到短信事件后处理一条短信（只要写入成功的码） */
    public static List<String> handle(Context ctx, String sender, String body) {
        return handleDetailed(ctx, sender, body).written;
    }

    /**
     * 入口（带结果）：处理一条事件，返回这条事件自己的结果。
     * 需要跨进程回执或区分「去重 / 失败」时用这个。
     */
    public static HandleResult handleDetailed(Context ctx, String sender, String body) {
        // v3.0.0：记录发送方，供用户规则的 senderContains 过滤使用
        // v3.0.0：发送方以显式参数一路传递到提取引擎（避免并发串号）
        // 黑名单预检（12306/银行/广告等）
        if (isBlacklisted(ctx, body)) {
            Log.i(TAG, "blacklist skip: len=" + body.length());
            return new HandleResult(new ArrayList<>(), 0, 0, 0, "blacklisted", "");
        }
        List<String> codes = PickupExtractor.extract(sender, body);
        if (codes.isEmpty()) {
            // v2.8.0 错题本捕获：含快递特征词但提取为空 → 记为疑似漏抓样本
            // （判定用特征词门禁而非 lookLikePickupSms——后者额外要求形状 token 存在，
            //   会漏掉"有特征词但文案里根本没有数字码"这类真漏抓）
            if (PickupExtractor.hasFeatureWords(sender, body)) {
                MissedSmsStore.record(ctx, sender, body);
            }
            return new HandleResult(codes, 0, 0, 0, "no_code", "");
        }

        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(new Date());
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        // v4.0.3 第二轮：写入目标 = 系统待办 + 软件内部记录。
        // 这里拿到的是「要写的待办库」列表；空 = 用户把系统待办全关了，
        // 只留内部记录（App 与桌面小组件里仍然看得到这条码）。
        final java.util.List<String> todoTargets = NotesBackend.todoTargets(ctx);
        final boolean noTodo = todoTargets.isEmpty();

        List<String> wrote = new ArrayList<>();
        String place = extractPlace(sender, body);
        String source = resolveSource(sender, body);
        StringBuilder dedupBuf = new StringBuilder();
        int dedupCount = 0;
        int failedCount = 0;
        String failureDiagnostic = "";

        // v3.1.0：整个去重集合的读-改-写必须在同一个锁内。
        // 此前只把「改+写」放进锁里，而 `prefs.getStringSet("seen")` 的「读」留在锁外 ——
        // 两条通道真并发时（T1/T2 都在对方落盘前读到同一份旧集合），
        // 后写的会把先写的指纹整个抹掉，同一个取件码被判「未见过」而写两条待办。
        // 这是唯一一道跨进程去重防线（短信通道 / 通知通道 / 兜底通道都会汇合到这里），
        // 必须读改写全在同一临界区。
        synchronized (DEDUP_LOCK) {
        Set<String> seen = new LinkedHashSet<>(prefs.getStringSet("seen", new LinkedHashSet<>()));
        int mode = prefs.getInt("todo_mode", MODE_FULL);
        String customTpl = prefs.getString("todo_custom", TPL_DEFAULT);
        // v4.0.6：系统待办的指纹只认「码 + 当天」。
        // 原来是「码 + 地点」——短信与通知给出的地点不一致时，
        // 同一个码会在待办里出现两条。按天留一条缝：
        // 同一个码隔天再来，可能是新包裹（驿站会复用短码），不该永久挡掉。
        String day = time.length() >= 10 ? time.substring(0, 10) : time;
        for (String code : codes) {
            String fingerprint = code + "|" + day;
            if (seen.contains(fingerprint)) {
                // v4.1.0（真机反馈）：指纹是永久保留的，但用户可能在日历里把那条待办
                // 删掉了、或者已经取件完成。这时驿站把同一条短信再发一遍（重发很常见），
                // 旧逻辑会永远跳过写入——表现就是「取件记录里有、待办里没有」。
                // 所以命中指纹后先确认目标库里还有没有**未完成**的待办，没有就照常写。
                boolean stillThere = noTodo || activeTodoExists(ctx, todoTargets, code);
                if (stillThere) {
                    Log.i(TAG, "dedup skip: " + fingerprint);
                    if (dedupBuf.length() > 0) dedupBuf.append("; ");
                    dedupBuf.append(fingerprint);
                    dedupCount++;
                    continue;
                }
                Log.i(TAG, "dedup hit but no active todo, rewrite: " + fingerprint);
            }
            seen.add(fingerprint);
            String content = buildContent(mode, customTpl, code, source, place, time);
            boolean ok;
            if (noTodo) {
                // 一个系统待办都没勾：本地库已由 Provider 写好（内部记录开着的话），
                // 这里不再碰系统库。仍走一遍去重集合，
                // 保证同一条短信重发不会重复提醒。
                ok = true;
                Log.i(TAG, "TODO SKIP (no todo target): " + code);
            } else {
                // 多目标：ColorOS 上可能同时勾了「日历待办」和「便签」，
                // 两个库各写一次；任何一个失败都算这次写入不完全成功。
                ok = true;
                for (String target : todoTargets) {
                    if (!writeTodoFor(ctx, target, content, buildTitle(code, content))) {
                        ok = false;
                    }
                }
            }
            if (ok) {
                wrote.add(code);
                Log.i(TAG, "TODO OK: code=" + code);
            } else {
                failedCount++;
                failureDiagnostic = lastWriteDiag;
                Log.e(TAG, "TODO FAIL: code=" + code);
                // v3.1.0 修复：写入失败必须把指纹撤掉。
                // 否则一次 su 授权超时 / sqlite3 异常，就会让这个取件码被去重永久挡掉——
                // 用户再也收不到这条提醒，且界面上看不出任何异常。
                seen.remove(fingerprint);
                if (lastWriteDiag == null || lastWriteDiag.isEmpty()) {
                    lastWriteDiag = "写入失败（原因未记录）";
                }
            }
        }

        lastDedupHits = dedupBuf.toString();

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
        } // end synchronized
        String status = failedCount > 0
                ? (wrote.isEmpty() ? "write_failed" : "partial")
                : (wrote.isEmpty() ? "deduplicated"
                        : (noTodo ? "local_only" : "written"));
        return new HandleResult(wrote, codes.size(), dedupCount, failedCount,
                status, failureDiagnostic == null ? "" : failureDiagnostic);
    }

    /**
     * 目标库里是否还有这条取件码的**未完成**待办（v4.1.0）。
     *
     * <p>去重指纹只记「写过」，不记「还在不在」：用户在日历里把待办删掉或划掉之后，
     * 指纹仍然会挡掉后续相同的短信——驿站重发短信时就表现为
     * 「取件记录里有、待办里没有」（真机反馈 2026-11-10）。
     *
     * <p>这里只在命中指纹时多问一句，代价出现在重复短信上（一天一两条），
     * 正常新取件码完全不经过这条路径。荣耀侧走 Provider 查询（不需要 su），
     * 其他 ROM 回落到原来那条只读 count 探针。
     */
    private static boolean activeTodoExists(Context ctx, java.util.List<String> targets, String code) {
        for (String t : targets) {
            try {
                if (NotesBackend.BACKEND_HONOR_TODO.equals(t)) {
                    if (HonorWriter.existsAlive(ctx, t, code)) return true;
                } else {
                    String out = runSqlForOutput(ctx, t, NotesBackend.existsSqlOf(t, code));
                    if (out != null && out.trim().matches("\\d+") && !out.trim().equals("0")) return true;
                }
            } catch (Throwable e) {
                Log.w(TAG, "activeTodoExists(" + t + ") failed: " + e);
            }
        }
        return false;
    }

    /** v3.1.0：去重集合读-改-写的锁（跨通道并发写入的唯一防线） */
    private static final Object DEDUP_LOCK = new Object();
    /**
     * v3.1.0：su + sqlite3 单次执行的超时上限。
     * 没有它，一旦 su 卡在授权弹窗就会永久挂死调用线程；而写库发生在 DEDUP_LOCK 内，
     * 挂死还会连带锁死后续所有短信处理。
     */
    private static final long SU_TIMEOUT_MS = 15_000L;

    /** 超时销毁进程后读取残余输出（此时流随时会 EOF，不能再阻塞等） */
    private static void drainQuietly(java.io.InputStream in, StringBuilder sb) {
        try {
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(in, "UTF-8"));
            String line;
            int n = 0;
            while ((line = br.readLine()) != null && n++ < 40) sb.append(line).append("\n");
        } catch (Throwable ignored) { }
    }

    /** 按模板构建待办内容 */
    public static String buildContent(int mode, String customTpl, String code, String source, String place, String time) {
        switch (mode) {
            case MODE_MIN:
                return code;
            case MODE_CUSTOM:
                String tpl = customTpl == null || customTpl.isEmpty() ? TPL_DEFAULT : customTpl;
                return tpl.replace("{code}", code)
                        .replace("{source}", source)
                        .replace("{place}", place)
                        .replace("{time}", time);
            case MODE_FULL:
            default:
                return "取件码 " + code + "｜" + source + "｜" + place + "｜" + time;
        }
    }

    /** 黑名单：正文含任一关键词则跳过（默认常见误报词） */
    public static boolean isBlacklisted(Context ctx, String body) {
        if (body == null || body.isEmpty()) return false;
        String list = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("blacklist", DEFAULT_BLACKLIST);
        if (list == null || list.isEmpty()) return false;
        for (String kw : list.split("[,，、;；\\s]+")) {
            if (kw.isEmpty()) continue;
            if (body.contains(kw)) return true;
        }
        return false;
    }

    /** 统一 SQL 执行器（一键测试 / 已取件 共用，走主目标） */
    public static int runSql(Context ctx, String sql) {
        String out = runSqlForOutput(ctx, sql);
        return out == null ? -1 : 0;
    }

    /** 指定后端执行（多目标写入 / 标记已取用） */
    public static int runSql(Context ctx, String backend, String sql) {
        String out = runSqlForOutput(ctx, backend, sql);
        return out == null ? -1 : 0;
    }

    /** 统一 SQL 执行器（返回输出；多路径 su 尝试），走主目标 */
    public static String runSqlForOutput(Context ctx, String sql) {
        return runSqlForOutput(ctx, NotesBackend.detect(ctx), sql);
    }

    /**
     * 统一 SQL 执行器（指定后端）。
     *
     * <p>v4.0.3：写入目标改成多选之后，同一段 SQL 要对多个库各跑一次
     * （小米只有一个库；ColorOS 可以有日历待办 + 便签两个），
     * 所以「用哪个库」必须由调用方显式传进来，不能再在内部读全局状态。
     */
    public static String runSqlForOutput(Context ctx, String backend, String sql) {
        return runSqlForPath(ctx, backend, NotesBackend.dbPathOf(backend), sql);
    }

    /**
     * 统一 SQL 执行器（指定后端 + 指定库文件）。
     *
     * <p>v4.1.0：荣耀侧有多个候选库（{@code task.db} / {@code calendar.db}），
     * 同一个后端要对不同文件各跑一次探测，所以库路径必须能单独传进来。
     */
    public static String runSqlForPath(Context ctx, String backend, String dbPath, String sql) {
        // v3.1.0：临时文件名唯一化。
        //   此前所有路径共用固定名 todo_sql.sql，而写入本方法的两条调用方互不排斥：
        //     · writeTodo（static synchronized，锁 TodoWriter.class）
        //     · runSql/runSqlForOutput（无锁，来自 TodoProvider 的 Binder 线程与 LauncherActivity 主线程）
        //   new FileOutputStream 是 O_TRUNC，两边会互相抹掉对方刚写的 SQL。
        //   最坏情况：handle() 的 INSERT 脚本被 markDone 的脚本顶掉 → sqlite 返回 0 →
        //   writeTodo 判定成功 → 去重指纹照常落盘 → 该待办永远不会被写入、也永远不重试。
        //   改为「每次唯一文件名 + 用完即删」，彻底消除共享文件。
        File sqlFile = null;
        try {
            File dir = ctx.getFilesDir();
            sqlFile = new File(dir, "todo_sql_" + System.nanoTime() + ".sql");
            try (FileOutputStream fos = new FileOutputStream(sqlFile)) {
                fos.write(sql.getBytes("UTF-8"));
            }
            // v4.0.3：优先走【常驻 root 通道】。
            //
            // Magisk 的 su 每调一次就弹一次「取件码助手已被授予超级用户权限」。
            // 本模块每写一条待办就要调一次 su，于是用户看到的就是「时不时弹一次」
            // （取件短信本身不常有，所以显得随机）。
            //
            // 常驻通道把 su 的调用次数从「每条待办一次」降到「每进程一次」：
            // 第一次建 shell 时弹一次（用户打开 App 时就在建，有心理预期），
            // 之后全程静默。
            String viaShell = runViaRootShell(ctx, backend, dbPath, sqlFile);
            if (viaShell != null) return viaShell;

            // HyperOS 的 Magisk su 位于 /product/bin/su（应用 PATH 不含该目录）→ 多路径尝试
            String[] suBins = {"su", "/product/bin/su", "/system/bin/su", "/sbin/su", "/su/bin/su"};
            String trace = "";
            for (String suBin : suBins) {
                String cmd = suBin + " -M -c \"id; " + buildSuCmd(ctx, backend, dbPath, sqlFile) + "\"";
                Log.i(TAG, "su attempt(" + suBin + ")");
                Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
                StringBuilder out = new StringBuilder();
                StringBuilder err = new StringBuilder();
                // v3.1.0：这里必须超时。
                //  ① 无超时且 drain() 在 waitFor() 之前 —— su 卡在 Magisk/KernelSU 授权弹窗时
                //     流不关闭、drain 不返回、waitFor 永远等不到，调用线程会被永久挂死。
                //  ② 写库发生在 DEDUP_LOCK 内，一旦挂死锁永不释放，
                //     后续所有短信全部阻塞（Receiver 在主线程 → App ANR；Provider 在 Binder 线程 → 短信入库卡住）。
                final Process proc = p;
                final boolean[] done = {false};
                Thread killer = new Thread(() -> {
                    try { proc.waitFor(); } catch (Throwable ignored) { }
                    done[0] = true;
                });
                killer.start();
                killer.join(SU_TIMEOUT_MS);
                if (!done[0]) {
                    try { p.destroyForcibly(); } catch (Throwable ignored) { }
                    drainQuietly(p.getInputStream(), out);
                    drainQuietly(p.getErrorStream(), err);
                    lastWriteDiag = "写入超时（" + (SU_TIMEOUT_MS / 1000) + "秒）：可能是 root 未授权或授权弹窗未处理";
                    Log.e(TAG, "su attempt(" + suBin + ") timed out " + SU_TIMEOUT_MS + "ms");
                    continue;
                }
                int code = p.exitValue();
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
        } finally {
            // v3.1.0：唯一化临时文件必须用完即删，否则 filesDir 会越积越多
            if (sqlFile != null) {
                try { sqlFile.delete(); } catch (Throwable ignored) { }
            }
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

    /** 地点提取（委托 PickupExtractor 动态规则引擎处理） */
    public static String extractPlace(String body) {
        return PickupExtractor.extractPlace(null, body);
    }

    /** 【推荐】地点提取，显式携带发送方（用户规则指定地点时需同一套号码匹配） */
    public static String extractPlace(String sender, String body) {
        return PickupExtractor.extractPlace(sender, body);
    }

    /** 笔记标题（ColorOS rich_notes 的 summary_title 展示用）：取内容首个「｜」前的短句 */
    public static String buildTitle(String code, String content) {
        if (content == null) return code;
        int cut = content.indexOf('｜');
        String t = cut > 0 ? content.substring(0, cut) : content;
        return t.length() > 40 ? t.substring(0, 40) : t;
    }

    /** 取短信中的括号品牌名（限 12 字以内防误吃正文括号）；无则 null */
    private static String bracketBrand(String text, char open, char close) {
        int l = text.indexOf(open);
        if (l < 0) return null;
        int r = text.indexOf(close, l + 1);
        if (r <= l) return null;
        String inner = text.substring(l + 1, r).trim();
        if (inner.isEmpty() || inner.length() > 12) return null;
        // 排除明显不是品牌名的内容（含数字/网址/标点的多半是正文）
        if (inner.contains("http") || inner.contains("www.")) return null;
        // v6.1：括号里含数字 → 是取件码/编号之类的正文，不是品牌名。
        // 实测「包裹待签收【取件码-7-7-6622】，手机号已加密…」——
        // 原逻辑把「取件码-7-7-6622」整个当成来源写进待办。
        // 品牌名（菜鸟驿站/兔喜生活/驿小哥…）不会含数字，这条判据足够安全。
        for (int i = 0; i < inner.length(); i++) {
            if (Character.isDigit(inner.charAt(i))) return null;
        }
        return inner;
    }

    /** 来源识别（v2.9.1 重构 / v4 增强，来自用户洞察）：
     *  策略1（首选）：取短信开头的括号品牌名——真实来源就是它
     *               （同时支持全角【】与半角[]，欢猫驿站/兔喜生活等新品牌零规则自动支持）
     *  策略2（回退）：无括号时按正文关键字判定 */
    public static String resolveSource(String sender, String body) {
        String t = (body == null ? "" : body);
        // 策略1a：全角【】品牌名（限 12 字以内防误吃正文括号）
        String brand = bracketBrand(t, '【', '】');
        if (brand != null) return brand;
        // 策略1b：半角 [] 品牌名（部分驿站短信用 [兔喜生活] 形式）
        brand = bracketBrand(t, '[', ']');
        if (brand != null) return brand;
        // 策略2：关键字回退
        if (t.contains("妈妈驿站")) return "妈妈驿站";
        if (t.contains("兔喜")) return "兔喜生活";
        if (t.contains("申通")) return "申通快递";
        if (t.contains("圆通")) return "圆通速递";
        if (t.contains("中通")) return "中通快递";
        if (t.contains("顺丰") || t.contains("SF")) return "顺丰速运";
        if (t.contains("京东")) return "京东快递";
        if (t.contains("极兔")) return "极兔速递";
        if (t.contains("韵达")) return "韵达快递";
        if (t.contains("邮政") || t.contains("EMS")) return "中国邮政";
        if (t.contains("丰巢") || t.contains("柜")) return "丰巢快递柜";
        if (t.contains("菜鸟") || t.contains("驿站")) return "菜鸟驿站";
        // v2.9.5：通知通道传回的 sender 形如「通知:com.cainiao.wireless」，
        // 直接展示很丑——清洗成可读来源，仍无法识别时统一显示「—」
        if (sender != null && sender.startsWith("通知:")) {
            String pkg = sender.substring(3);
            int dot = pkg.lastIndexOf('.');
            String guess = dot >= 0 ? pkg.substring(dot + 1) : pkg;
            return guess.isEmpty() ? "—" : guess;
        }
        // v6.1：识别不出来源时不再回显发送方号码。
        // 号码（1069888877 之类）对用户没有任何意义，写进待办只是噪音；
        // 按「识别不到就不显示」的原则统一显示「—」。
        return "—";
    }

    /** 简单 SQL 单引号转义（供 NotesBackend 组 SQL 用） */
    public static String sqlEscapePub(String s) {
        return sqlEscape(s);
    }

    /**
     * 直写待办库：内容转义后写入模块私有 SQL 文件，su -M sqlite3 执行。
     * v2.7.0：目标库与置顶算法由 NotesBackend 决定
     * （小米 todo 堆栈置顶 / ColorOS Tasks 直插 / ColorOS rich_notes top_time 置顶）
     */
    private static synchronized boolean writeTodo(Context ctx, String content, String title) {
        String backend = NotesBackend.detect(ctx);
        if (NotesBackend.BACKEND_HONOR_TODO.equals(backend)) {
            return HonorWriter.write(ctx, backend, content, title);
        }
        String sql = ".timeout 5000\n" + NotesBackend.insertSql(ctx, content, title);
        return runSql(ctx, sql) == 0;
    }

    /**
     * 在所有勾选的待办目标里把某条标记为已取（v4.0.3）。
     *
     * <p>多选之后不能只改一个库：用户勾了两个（ColorOS 日历 + 便签），
     * 只把日历标完成、便签还留着，会留下一条永远清不掉的旧待办。
     *
     * @return 成功写入的目标数；0 表示没有待办目标或全部失败
     */
    public static int markDoneAll(Context ctx, String code) {
        java.util.List<String> targets = NotesBackend.todoTargets(ctx);
        int ok = 0;
        for (String t : targets) {
            try {
                // v4.1.0：荣耀后端的 SQL 要按实际表结构现场拼，不能取静态模板
                if (NotesBackend.BACKEND_HONOR_TODO.equals(t)) {
                    if (HonorWriter.markDone(ctx, t, code)) ok++;
                    continue;
                }
                if (runSql(ctx, t, NotesBackend.markDoneSqlOf(t, code)) == 0) ok++;
            } catch (Throwable e) {
                Log.w(TAG, "markDone(" + t + ") failed: " + e);
            }
        }
        return ok;
    }

    /**
     * 写入指定后端（v4.0.3 多选目标用）。
     *
     * <p>与 {@link #writeTodo} 的区别只有一处：用哪张表由 {@code backend} 决定，
     * 不再读全局的「当前后端」。
     */
    private static synchronized boolean writeTodoFor(Context ctx, String backend,
                                                     String content, String title) {
        // v4.1.0：荣耀侧先走 CalendarProvider、失败再按表结构直写，逻辑在 HonorWriter 里
        if (NotesBackend.BACKEND_HONOR_TODO.equals(backend)) {
            boolean ok = HonorWriter.write(ctx, backend, content, title);
            if (ok) lastWriteDiag = "OK（荣耀）" + HonorWriter.getLastDiag();
            else lastWriteDiag = "荣耀写入失败：" + HonorWriter.getLastDiag();
            return ok;
        }
        String sql = ".timeout 5000\n" + NotesBackend.insertSqlOf(backend, content, title);
        return runSql(ctx, backend, sql) == 0;
    }

    /**
     * 走常驻 root 通道执行 SQL（v4.0.3）。
     *
     * @return 输出文本；通道不可用时返回 null，调用方退回一次性 su
     */
    private static String runViaRootShell(Context ctx, String backend, String dbPath, File sqlFile) {
        try {
            // 常驻 shell 本身就是 root，不需要再跑一遍 id 探针。
            // 错误信息并入 stdout：sqlite3 的报错本来也走 stdout，混在一起便于诊断。
            String script = buildSuCmd(ctx, backend, dbPath, sqlFile) + " 2>&1";
            RootShell.Result r = RootShell.execResult(script, SU_TIMEOUT_MS);
            if (r == null) {
                Log.w(TAG, "root shell unavailable, fallback to one-shot su");
                return null;
            }
            Log.i(TAG, "root shell exec: exit=" + r.code
                    + " out=" + r.output.trim());
            if (r.ok()) {
                lastWriteDiag = "OK（常驻 root 通道）";
                // 已经付过一次 root 的代价了：顺手把跨进程配置快照同步出去、
                // 并把旧版本留在 /data/local/tmp 的 sqlite3 + 依赖库清掉。
                // 两件事复用同一个常驻通道，不会再多弹一次授权框。
                try { ProcessSync.flushIfReady(ctx); } catch (Throwable ignored) { }
                try { Repair.autoCleanupLegacyOnce(ctx); } catch (Throwable ignored) { }
                return r.output;
            }
            lastWriteDiag = "常驻通道执行失败 exit=" + r.code
                    + " err=" + tail(r.output, 160);
            return null;    // 失败也回退：一次性 su 可能走别的路径成功
        } catch (Throwable t) {
            Log.w(TAG, "root shell exec error: " + t);
            return null;
        }
    }

    private static String buildSuCmd(Context ctx, String backend, String dbPath, File sqlFile) {
        // 自愈式：每次写入前确保目录/文件可访问（App su 子进程缺 DAC 特权，需要放开；仅小米后端需要）
        // LD_LIBRARY_PATH 必须写在命令串内（su 会保留命令行内的环境变量赋值）
        return NotesBackend.chmodCmdOf(backend)
                + "LD_LIBRARY_PATH=" + LIBS + " " + SQLITE + " "
                + dbPath + " < " + sqlFile.getAbsolutePath();
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
