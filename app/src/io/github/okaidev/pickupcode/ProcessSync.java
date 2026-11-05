package io.github.okaidev.pickupcode;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;

/**
 * 跨进程状态同步（v3.1.0）：
 *
 * 【要解决的组合债】
 * 本模块有三条写入通道跑在**不同的进程**里：
 *   1. 短信通道   —— 模块 App 进程（TodoProvider）
 *   2. 通知通道   —— system_server（NotiHook）
 *   3. 冻结兜底   —— com.android.providers.telephony（SystemDirectWriter）
 * 系统进程读不到模块 App 的 SharedPreferences，所以它们此前只能用「编译内置默认值」跑，
 * 于是出现「单独看没问题、组合起来不一致」的场景：
 *   · 官方规则热更到 v6，短信通道正常，通知通道还是旧内置规则继续漏抓（用户无感知）
 *   · 用户在设置页改过的黑名单 / 待办模板，在兜底通道里不生效
 *
 * 【方案】
 * 沿用项目里已验证可靠的「root 写 /data/local/tmp + 世界可读」机制
 * （与 UserRules.syncToSystemProcess、SystemDirectWriter 开关标志同一套），
 * 由 App 进程把一份只读快照写到 /data/local/tmp/pickup_sqlite/app_state.json，
 * 系统进程启动时直接读文件（无需 su，文件 chmod 644）。
 *
 * 【边界】
 * 只同步「怎么提取、怎么写文本」这类只读配置；
 * 绝不同步任何短信正文、取件码、用户隐私数据。
 */
public class ProcessSync {

    private static final String TAG = "PICKUPDEBUG";
    private static final String PREFS = "dedup";

    /** 同步目录与文件（与 Repair.TARGET_DIR / UserRules.SYNC_DIR 一致） */
    /** 跨进程同步目录（NotiHook 用它做 mtime 变更检测，故同包可见） */
    static final String SYNC_DIR = RuntimePaths.SYNC_DIR;
    /** 配置快照文件（同上） */
    static final String SYNC_FILE = RuntimePaths.SYNC_FILE;

    /** 黑名单默认值（App 偏好读不到时的兜底；唯一来源在 TodoWriter） */
    public static final String DEFAULT_BLACKLIST = TodoWriter.DEFAULT_BLACKLIST;

    /** 系统进程侧只读快照 */
    public static class State {
        public String rulesJson = "";      // 当前生效的官方规则集 JSON（空 = 用编译内置）
        public String blacklist = "";      // 逗号分隔（空 = 用默认）
        public int todoMode = 1;           // 0=极简 1=完整 2=自定义
        public String customTpl = "";      // 自定义模板
        public String notiPkgs = "";       // 逗号分隔的通知白名单（含内置 + 用户追加）
        /**
         * v3.1.0：写入目标后端。
         * 此前漏了这个字段，冻结兜底通道只能用 NotesBackend.propsBackend() 按 ROM 属性猜后端——
         * 用户手动把写入目标改成与 ROM 推断不同的那个（如 ColorOS 上选「ColorOS 便签」）时，
         * 主通道写 nearme_note.db、兜底通道却写 tasks.db，数据分裂在两个 App 里。
         * 空 = 让兜底通道按原逻辑推断。
         */
        public String backend = "";
        /**
         * v4.0.3：多选之后的待办目标，逗号分隔（可能 0~2 个）。
         *
         * <p>为什么不能只留 {@link #backend}：ColorOS 上用户可以同时勾「日历待办」
         * 和「便签笔记」，兜底通道只写一个就会漏掉另一个。
         * 空串 = 没有任何待办目标（详见 {@link #noTodo}）。
         */
        public String backends = "";
        /**
         * v4.0.3 第二轮：用户把系统待办全关了，只留「软件内部记录」。
         *
         * <p><b>必须有这个独立标记</b>：{@code backends} 空串既可能是
         * 「用户一个待办都没勾」，也可能是「老版本快照里没有这个字段」。
         * 只看空串会让兜底通道把前者当成后者，回落到 ROM 推断目标，
         * 于是用户明明关掉了待办，冻结兜底却在偷偷往里写。
         */
        public boolean noTodo;
    }

    /**
     * 让 system_server 读得到配置文件的收尾命令（v4.0.2 第八轮，来自 PR #14）。
     *
     * <p><b>为什么需要</b>：App 用自己的权限把文件写进 /data/local/tmp 之后，
     * 文件属主是 App 的 uid、SELinux 标签是 <b>App 私有数据</b>。
     * system_server 读它时会同时被两道门挡住：
     * DAC（目录权限）和 SELinux（标签不允许 system_server 读 app_data_file），
     * 表现就是「App 里明明写了配置，通知通道却一直用编译内置的旧规则」。
     *
     * <p>这里补两件事：
     * <ul>
     *   <li>{@code chmod 755} 目录 —— 解决 DAC；</li>
     *   <li>{@code chcon system_data_file} —— 解决 SELinux。</li>
     * </ul>
     *
     * <p><b>只处理配置文件本身</b>，绝不碰 sqlite3 及其依赖库：
     * 那些是可执行文件，改了标签反而可能执行不了。
     *
     * <p>命令末尾带 {@code 2>/dev/null || true}：某些 ROM 没有这个 SELinux 上下文，
     * chcon 会失败。<b>失败不能影响配置写入</b>（文件本身已经就位，只是可能读不到），
     * 所以让它静默失败。
     */
    public static String readableConfigCommand(String file) {
        // 路径都是内部常量，不拼接任何用户输入
        return "; chmod 755 " + SYNC_DIR
                + "; chcon u:object_r:system_data_file:s0 " + SYNC_DIR + " " + file
                + " 2>/dev/null || true";
    }

    // ==================== App 进程：写入快照 ====================

    /** 节流推送的最小间隔（30 分钟） */
    private static final long PUSH_THROTTLE_MS = 30L * 60 * 1000;
    private static final String KEY_LAST_PUSH = "state_last_push";
    /** 有改动但还没同步出去的标记 */
    private static final String KEY_PENDING = "state_pending";

    /**
     * 启动时兜底同步（v4.0.2 第八轮重做）。
     *
     * <p><b>为什么改成「只在已有 root 会话时同步」</b>：
     * 旧实现在每次冷启动（30 分钟节流）时都跑一次 {@code su} 去写快照文件，
     * 于是用户每次打开 App 都可能看到 Magisk 的「已被授予超级用户权限」弹窗。
     * 用户明确要求：「在已经授予所有权限的情况下，这种弹窗就不应该再出现了；
     * 但又不能影响没有正常激活 / 无法使用的用户场景」。
     *
     * <p>所以现在：<b>绝不为了同步去唤 su</b>。
     * 进程里已经有常驻 root 通道（说明用户刚刚做过需要 root 的事）才顺手同步，
     * 否则只记一个「待同步」标记，等下一次真正需要 root 的动作一起带出去。
     * 没有 root 的用户完全不受影响——系统进程会回落到编译内置的默认配置。
     */
    public static void pushThrottled(final Context ctx) {
        if (ctx == null) return;
        try {
            long now = System.currentTimeMillis();
            long last = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getLong(KEY_LAST_PUSH, 0L);
            boolean pending = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getBoolean(KEY_PENDING, false);
            if (!pending && now - last < PUSH_THROTTLE_MS) return;
        } catch (Throwable t) {
            // 读不到偏好就不做节流判断，继续往下走
        }
        if (!RootShell.isReady()) {
            // 没现成的 root 会话就只记账，绝不主动唤 su
            markPending(ctx);
            return;
        }
        push(ctx);
    }

    /** 记一个「待同步」标记（下次有 root 会话时一起带出去） */
    public static void markPending(Context ctx) {
        if (ctx == null) return;
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean(KEY_PENDING, true).apply();
        } catch (Throwable ignored) { }
    }

    /**
     * 如果当前进程已经有常驻 root 通道，就顺手把快照同步出去（不额外唤 su）。
     * 调用时机：任何已经成功用过 root 的动作之后（写待办 / 完整体检 / 部署）。
     */
    public static void flushIfReady(final Context ctx) {
        if (ctx == null || !RootShell.isReady()) return;
        boolean pending;
        try {
            pending = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getBoolean(KEY_PENDING, false);
        } catch (Throwable t) {
            pending = true;
        }
        if (!pending) return;
        push(ctx);
    }

    /**
     * 把当前配置快照推到系统进程可读的位置。
     * 调用时机：官方规则热更成功 / 黑名单保存 / 模板保存 / 模式切换 / 启动兜底（节流）。
     * 内部走后台线程（su 执行不能阻塞主线程）。
     */
    public static void push(final Context ctx) {
        if (ctx == null) return;
        if (!RootShell.isReady()) {
            // 不为了同步去唤 su（那会让用户在「只是改了个开关」时也看到授权弹窗）
            markPending(ctx);
            return;
        }
        new Thread(() -> {
            try {
                String json = buildJson(ctx);
                if (json == null) return;
                // v3.1.0 修正：整个快照做 Base64 后再写文件。
                //
                // 【为什么必须 Base64】写入命令最终走
                //   Diagnostics.suExecPublic → sh -c 'su -M -c "<cmd>"'
                // 而 suExec 里有 cmd.replace("\"", "'")：命令行里的所有双引号会被替换成单引号。
                // 官方规则集是「嵌套 JSON」，内部含大量 \" 以及 \d \s \/ 等正则转义，
                // 直接内联进 shell 命令会被破坏（真机上实测文件变成了 {'rulesJson':'{\'version\'...
                // 的坏数据），导致通知通道读不到规则、悄悄回落到编译内置规则——修复等于没生效。
                // Base64 字符集只有 A-Za-z0-9+/=，不含引号/反斜杠/空格，可安全穿过整条命令链。
                String b64 = android.util.Base64.encodeToString(
                        json.getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
                // v3.1.0：先写临时文件再原子改名。
                //   `cat > file` 是 open(O_TRUNC) + 顺序写，读方完全可能在这个窗口里
                //   读到空串或半截内容；而读方（system_server / telephony）读到坏数据会
                //   静默回落到编译内置规则 + 默认黑名单 + 错误的写入目标 ——
                //   正是本类要消灭的「组合债」。同目录 rename 是原子的。
                // 走常驻 root 通道：不再单独起一次 su（那又会弹一次授权框）
                String script = "mkdir -p " + SYNC_DIR + "; chmod 755 " + SYNC_DIR + "; "
                        + "cat > " + SYNC_FILE + ".tmp << 'PICKUP_EOF'\n" + b64 + "\nPICKUP_EOF\n"
                        + "chmod 644 " + SYNC_FILE + ".tmp; "
                        + "mv -f " + SYNC_FILE + ".tmp " + SYNC_FILE
                        + readableConfigCommand(SYNC_FILE);
                String out = RootShell.exec(script, 20000);
                if (out == null) {
                    markPending(ctx);
                    Log.w(TAG, "ProcessSync: push skipped (root shell unavailable)");
                    return;
                }
                try {
                    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                            .putBoolean(KEY_PENDING, false)
                            .putLong(KEY_LAST_PUSH, System.currentTimeMillis()).apply();
                } catch (Throwable ignored) { }
                Log.i(TAG, "ProcessSync: state pushed (" + json.length() + " bytes json)");
            } catch (Throwable t) {
                Log.w(TAG, "ProcessSync: push failed: " + t);
            }
        }).start();
    }

    private static String buildJson(Context ctx) {
        try {
            JSONObject o = new JSONObject();
            // 官方规则：取当前内存里生效的那份（可能是热更来的，也可能是 assets/内置）
            try {
                java.lang.reflect.Field f = PickupExtractor.class
                        .getDeclaredField("activeRules");
                f.setAccessible(true);
                Object r = f.get(null);
                if (r != null) {
                    java.lang.reflect.Method m = r.getClass().getMethod("toJson");
                    Object tj = m.invoke(r);
                    o.put("rulesJson", tj != null ? tj.toString() : "");
                }
            } catch (Throwable t) {
                Log.w(TAG, "ProcessSync: read activeRules failed: " + t);
            }
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            o.put("blacklist", sp.getString("blacklist", ""));
            o.put("todoMode", sp.getInt("todo_mode", 1));
            o.put("customTpl", sp.getString("todo_custom", ""));
            o.put("notiPkgs", notiPkgsOf(ctx));
            // v3.1.0：写入目标也进快照，保证主通道与冻结兜底通道写同一个库
            o.put("backend", sp.getString("backend_override", ""));
            // v4.0.3：多选目标（0~N 个）。空串表示「只写小组件」。
            StringBuilder bs = new StringBuilder();
            boolean noTodo = false;
            if (ctx != null) {
                for (String t : NotesBackend.todoTargets(ctx)) {
                    if (bs.length() > 0) bs.append(',');
                    bs.append(t);
                }
                noTodo = bs.length() == 0;
            }
            o.put("backends", bs.toString());
            // 空串有两种含义，必须单独标出来（见 State.noTodo 的说明）
            o.put("noTodo", noTodo);
            return o.toString();
        } catch (Throwable t) {
            Log.w(TAG, "ProcessSync: buildJson failed: " + t);
            return null;
        }
    }

    /** 通知白名单 = 内置固定项 + 用户追加项（v3.1.0 P4：让用户规则/自定义能覆盖更多 App） */
    public static String notiPkgsOf(Context ctx) {
        StringBuilder sb = new StringBuilder();
        for (String p : NotiHook.BUILTIN_PKGS) {
            if (sb.length() > 0) sb.append(",");
            sb.append(p);
        }
        if (ctx != null) {
            String extra = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString("noti_extra_pkgs", "");
            if (extra != null && !extra.trim().isEmpty()) {
                for (String p : extra.split("[,，\\s]+")) {
                    String t = p.trim();
                    if (t.isEmpty()) continue;
                    if (sb.length() > 0) sb.append(",");
                    sb.append(t);
                }
            }
        }
        return sb.toString();
    }

    /** 供设置页保存用户追加的通知包名 */
    public static void setExtraNotiPkgs(Context ctx, String raw) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("noti_extra_pkgs", raw == null ? "" : raw.trim()).apply();
        push(ctx);
    }

    public static String getExtraNotiPkgs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("noti_extra_pkgs", "");
    }

    // ==================== 系统进程：读取快照 ====================

    /** 系统进程侧加载（同步阻塞，只在冷启动调用一次；文件很小且本地读） */
    public static State load() {
        State st = new State();
        try {
            File f = new File(SYNC_FILE);
            if (!f.exists()) return st;
            String b64 = readFile(f);
            if (b64 == null || b64.trim().isEmpty()) return st;
            // v3.1.0：写入端整份 Base64，这里先解码再解析（见 push() 的注释）
            byte[] raw;
            try {
                raw = android.util.Base64.decode(b64.trim(), android.util.Base64.DEFAULT);
            } catch (Throwable e) {
                Log.w(TAG, "ProcessSync: base64 decode failed" + e);
                return st;
            }
            String json = new String(raw, StandardCharsets.UTF_8);
            JSONObject o = new JSONObject(json);
            st.rulesJson = o.optString("rulesJson", "");
            st.blacklist = o.optString("blacklist", "");
            st.todoMode = o.optInt("todoMode", 1);
            st.customTpl = o.optString("customTpl", "");
            st.notiPkgs = o.optString("notiPkgs", "");
            st.backend = o.optString("backend", "");
            st.backends = o.optString("backends", "");
            st.noTodo = o.optBoolean("noTodo", false);
        } catch (Throwable t) {
            Log.w(TAG, "ProcessSync: load failed: " + t);
        }
        return st;
    }

    private static String readFile(File f) {
        // v3.1.0：改用 ByteArrayOutputStream 累积。
        //   此前按 f.length() 预分配再读，最后却用 b.length（而非实读的 off）构造字符串——
        //   文件在 length() 与 read() 之间被替换/截断时，尾部会残留 NUL 字节，
        //   导致 Base64 解码抛异常 → 系统进程静默回落编译内置规则。
        try (java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
             java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }
}
