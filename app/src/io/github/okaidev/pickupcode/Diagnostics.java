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
 *  3) sanitize：报告自动脱敏（手机号 / 身份证 / 取件码 / 短信里的短信号）
 *  4) export：保存到 Download/pickup_diag_*.txt 并拉起系统分享
 *
 * 隐私约束（v3.2.0 更正）：本模块自己打的 PICKUPDEBUG 日志里【确实含短信正文片段】
 *   （SmsEventReceiver 的 "EVENT: … body=…"、TodoWriter 的 "blacklist skip: …"），
 *   报告整段引用这些日志，所以必须假设「短信正文会出现在报告里」，靠 sanitize 做兜底脱敏。
 *   待办库/笔记库本身只做结构级探针（count / 表名），不读内容。
 */
public class Diagnostics {

    private static final String TAG = "PICKUPDEBUG";
    private static final String LIBS = "/data/local/tmp/pickup_sqlite/lib";
    private static final String SQLITE = "/data/local/tmp/pickup_sqlite/sqlite3";

    // ==================== 脱敏规则 ====================
    // v3.2.0 重做的理由（踩坑记录，两个方向的毛病都有）：
    //   ① 漏：旧版只有一条 \b\d{1,4}-\d{1,2}-\d{1,5}\b，实际支持的码形远不止这一种——
    //      裸数字码（267961 / 72778）、字母码（A88123）、两段码（4869-9777）
    //      在导出报告里全是明文。
    //   ② 误伤：同一条规则也命中报告自己的「生成时间 2026-10-04」，把日期打成 XX-X-XXXX，
    //      读者没法判断报告是哪天导出的。
    //   设计取舍：宁可漏脱敏，也绝不把版本号 / 时间戳 / 文件路径 / 行号 / PID 搞乱。
    //   因此分两类处理：
    //     · 形状独特的（多段横线码、字母码）→ 不需要锚定，认形状就脱敏；
    //     · 形状烂大街的（3~9 位裸数字）→ 必须有语境锚点（关键词/竖线分隔位）才脱敏，
    //       否则 logcat 的 PID、"近 400 行"、堆栈行号全会被打成 ***。
    private static final Pattern P_PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    /** 身份证：18 位，末位可为 X；前后不许再接字母/数字，避免误伤哈希串或纳秒时间戳 */
    private static final Pattern P_IDCARD = Pattern.compile("(?<![0-9A-Za-z])\\d{17}[\\dXx](?![0-9A-Za-z])");
    /**
     * 码 token 本体：可选字母前缀 + 3~9 位数字 + 可选 1~3 段横线尾巴。
     * 覆盖 267961 / 72778 / A88123 / 4869-9777 / 16-4-9626 / 12-34-56-7890。
     * 尾部 (?![\\dA-Za-z*]) 是关键：13 位时间戳 ts=1750000000000 只按 9 位切一刀时
     * 会被这条断言挡掉；同时挡住已经被 P_PHONE 打成 138****8888 的号码残骸。
     */
    private static final String CODE_TOKEN = "([A-Za-z]?\\d{3,9}(?:-\\d{1,5}){0,3})(?![\\dA-Za-z*])";
    /** 取件语境关键词（左侧锚点）。顺序有意义：长的写前面，Java 正则按序择先 */
    private static final String CTX_HEAD = "(?:取件码|取货码|提货码|取件凭证|取件单|取件号|"
            + "凭此码|凭码|验证码|校验码|取件|提货|取货|口令|code|CODE|Code|📦)";
    /** 取件语境关键词（右侧锚点，如「267961，请到 XX 小区驿站取件」） */
    private static final String CTX_TAIL = "(?:凭此码|凭码|凭短信码|码取件|为您取件|请取件|取件|提货|取货|到期|有效期)";
    /** ① 多段横线码（3~4 段）：16-4-9626 / 2-3-05025。
     *  前后不能再接数字或横线（否则会从长 token 上切一段下来误脱敏）；
     *  并显式排除「年-月-日」——报告头部的生成时间必须原样保留；
     *  只收 3 段及以上，两段的时间戳 10-04（logcat 行首）不碰。 */
    private static final Pattern P_CODE = Pattern.compile(
            "(?<![0-9/\\-])(?!(?:19|20)\\d{2}-)(?:\\d{1,4}-){2,3}\\d{1,5}(?![0-9\\-])");
    /** ② 字母码：A88123。必须整段只有「一个字母 + 4~6 位数字」，否则 SHA/十六进制串会被误伤 */
    private static final Pattern P_CODE_ALPHA = Pattern.compile("(?<![0-9A-Za-z])[A-Za-z]\\d{4,6}(?![\\dA-Za-z*])");
    /** ③ 去重指纹位："<码>|<地点>"（dedup skip / 📦…｜… 这类模板行），竖线是强结构信号 */
    private static final Pattern P_CODE_SEP = Pattern.compile("(?<![0-9A-Za-z])" + CODE_TOKEN + "(?=[|｜])");
    /** ④ 左语境：「取件码为 267961」「code=A88123」「📦 267961」 */
    private static final Pattern P_CODE_CTX = Pattern.compile(
            CTX_HEAD + "\\s*(?:为|是|:|：|=|#|no|is|号|码)?\\s*" + CODE_TOKEN);
    /** ⑤ 右语境：码后面隔 0~12 个非数字字符内出现取件类词。限行内（[^\\d\\n]）以免跨行误伤 */
    private static final Pattern P_CODE_TAIL = Pattern.compile(
            CODE_TOKEN + "[^\\d\\n]{0,12}?" + CTX_TAIL);
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

    // ---------- suExecPublic 的返回值约定（改这 4 处前务必读完这段）----------
    // 返回 {stdout, stderr, 第三个元素}，其中：
    //   · stdout 有内容 → 采用「第一个跑通的 su 路径」的那次结果（正常情况）；
    //   · stdout 全空   → 循环会【试完 5 个 su 路径】，返回的是【最后一次】尝试的结果，
    //     也就是「最后一个 su（/su/bin/su）根本不存在」的假错误，和真实故障没关系。
    // 命令天然没有 stdout 时（logcat 没日志 / tail 找不到文件 / sqlite 报错只写 stderr），
    // 后半句就会把真正的报错吃掉，只剩一句莫名其妙的 "sh: su: not found"。
    // 解决办法（本类所有改走 suExecPublic 的探针都这么写）：
    //   命令尾部统一加 "2>&1; echo __PICKUP_PROBE_END__"：
    //     · 2>&1   → 把命令自己的 stderr 也并进 stdout，信息不丢；
    //     · echo 哨 → 只要 su 真的跑起来了，stdout 就【必然】非空，
    //                 于是 suExecPublic 会在第一个可用路径上正确停下；
    //                 反过来「stdout 里没有哨兵」就等于「5 个 su 路径一个都没跑起来」。
    private static final String PROBE_MARK = "__PICKUP_PROBE_END__";

    /** 是否拿到了哨兵行：true = su 至少有一条路径真的跑起来了 */
    private static boolean hasMark(String[] r) {
        return r != null && r[0] != null && r[0].contains(PROBE_MARK);
    }

    /** 摘掉哨兵行后的干净 stdout */
    private static String stdoutOf(String[] r) {
        if (r == null || r[0] == null) return "";
        return r[0].replace(PROBE_MARK + "\n", "").replace(PROBE_MARK, "").trim();
    }

    /** su 没跑起来时的摘要（此时 suExecPublic 给的是最后一次尝试，典型就是 "sh: su: not found"） */
    private static String suErr(String[] r) {
        if (r == null) return "(无返回值)";
        String e = (nz(r[1]) + " " + nz(r[2])).trim();
        if (e.isEmpty()) e = "(无 stderr)";
        if (e.contains("not found") || e.contains("No such file")) {
            return e + "（本应用 PATH 里通常没有 su；Magisk 的 su 多在 /product/bin/su，"
                    + "请确认 Magisk 已安装并已对本应用授权）";
        }
        return e;
    }

    /**
     * 1) LSPosed 注入状态
     *
     * v3.0.1 重写（修「升级后误报未注入、提示重启手机」的老问题）：
     *   旧实现只查 files/pickup_injected.flag 这个「历史标记文件」，
     *   而该文件会在【升级安装 / App 清理数据】时被系统删掉，
     *   导致明明 Hook 正常却报「未注入，请重启手机」。
     *
     * v3.0.1 最终方案（按可靠度）：
     *   ① 内存实时标记：XposedEntry 在【本进程】被注入时置位。
     *      同一进程同一 ClassLoader，必然读得到——这是最硬的证据，不依赖任何文件。
     *   ② 标记文件：仅作「LSPosed 曾经加载过本模块」的辅助佐证。
     *   ③ 兜底：直接问 LSPosed 配置库（root 可用时），看模块是否处于启用状态。
     *   以上都拿不到才提示可能需要复检，且不再断言「未注入」。
     */
    private static Check checkLsposedInject(Context ctx) {
        // ① 内存实时标记：最可靠
        if (injectedLive) {
            return new Check("LSPosed 注入", true, "模块已被 LSPosed 加载（实时检测）");
        }
        // ② 标记文件佐证
        File flag = new File(ctx.getFilesDir(), "pickup_injected.flag");
        if (flag.exists()) {
            long age = System.currentTimeMillis() - flag.lastModified();
            String when = age < 60000 ? "刚刚" : (age / 60000) + " 分钟前";
            return new Check("LSPosed 注入", true, "模块已被 LSPosed 加载（标记于 " + when + "）");
        }
        return new Check("LSPosed 注入", false,
                "未检测到加载痕迹。若取件码能正常抓取可忽略；"
                        + "若抓不到：确认 LSPosed 已勾选本模块，重启手机后打开本 App 复检");
    }

    /**
     * 实时注入标记：由 XposedEntry 在【本进程】被 LSPosed 加载时置位。
     * 同一进程内读取必然一致，不受升级/清理/文件权限影响。
     */
    private static volatile boolean injectedLive = false;

    /** 供 XposedEntry 调用：标记「本进程已被注入」 */
    public static void markInjectedLive() { injectedLive = true; }

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
        // v3.2.0：原来硬编码 suExec("su", …) 只试一条路径。HyperOS 上 Magisk 的 su 在
        // /product/bin/su、不在应用 PATH，于是体检出现自相矛盾的两行：
        //   [✓] root 授权（/product/bin/su）: uid=0 ✓ root      ← checkRoot 走了多路径
        //   [✗] sqlite3 部署: 探测失败：sh: su: not found       ← 这里只试了 "su"
        // 用户完全不知道该干什么。改成 suExecPublic 多路径 + 哨兵（理由见文件上方约定）。
        String[] r = suExecPublic("ls -l " + SQLITE + " 2>&1; echo " + PROBE_MARK, 15);
        if (!hasMark(r)) {
            return new Check("sqlite3 部署", false, "root 命令没跑起来：" + tail(suErr(r), 140));
        }
        String all = stdoutOf(r);
        // 判据顺序有讲究：文件不存在时 ls 打的错误里也带着 "sqlite3" 这个路径，
        // 必须先判 "No such file"，否则会把「没部署」误报成「已部署 ✓」。
        if (all.contains("No such file") || all.contains("Not a directory")) {
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
        // v3.2.0：同 checkSqlite3，改走 suExecPublic 多路径 + 2>&1/哨兵。
        // 这里对 stdout 的依赖是【必须的】（靠纯数字判断 count 成功），
        // 所以更依赖哨兵：没有哨兵就说明 5 条 su 路径全没跑起来，
        // 此时若还去读 stdout，只会读到空串并误判成「库打不开」。
        String[] r = suExecPublic(LD() + " " + SQLITE + " " + db
                + " '" + NotesBackend.countProbeSql(ctx) + "' 2>&1; echo " + PROBE_MARK, 15);
        if (!hasMark(r)) {
            return new Check("待办库访问", false, "root 命令没跑起来：" + tail(suErr(r), 140));
        }
        // 摘掉哨兵后再判 count：命令成功时 stdout 就是那个纯数字，
        // 失败时 stdout 是 sqlite 的报错文本（2>&1 收过来的），不会误判成 count。
        String out = stdoutOf(r);
        if (out.matches("\\d+")) {
            return new Check("待办库访问", true,
                    table + " 表可读，当前 " + out + " 条 ✓");
        }
        String e = out + " " + suErr(r);
        if (e.contains("unable to open")) {
            return new Check("待办库访问", false,
                    "打不开 " + dbFile + "（权限不足）→ 正常情况模块写入时会自动处理；"
                            + "可先跑一次「一键测试」让它自愈，再导出报告");
        }
        if (e.contains("no such table")) {
            return new Check("待办库访问", false,
                    table + " 表不存在 → 请先" + needHint + "，或系统版本过旧");
        }
        if (e.contains(PROBE_MARK)) {   // 理论上到不了，留着防以后有人改命令把哨兵吃掉
            e = e.replace(PROBE_MARK, "").trim();
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
        // v3.2.0：改走 suExecPublic 多路径 + 2>&1/哨兵。
        // 原来只调 "su" 时，HyperOS 上这段整块日志会被换成 "sh: su: not found"，
        // 用户看到的是「无 PICKUPDEBUG 日志——说明 Hook 从未触发」——一个彻头彻尾的假结论，
        // 反而把排查方向带偏（真因是 su 路径，日志其实根本没抓到）。
        String[] lg = suExecPublic("logcat -d -s PICKUPDEBUG:* 2>&1 | tail -n 400; echo " + PROBE_MARK, 25);
        boolean logOk = hasMark(lg);
        String logcat = logOk ? stdoutOf(lg) : "";
        if (!logOk) {
            sb.append("(提取失败：root 命令没跑起来 —— ").append(tail(suErr(lg), 160)).append(")\n\n");
        } else if (logcat.isEmpty()) {
            sb.append("(无 PICKUPDEBUG 日志——说明 Hook 从未触发，先检查作用域)\n\n");
        } else {
            sb.append(logcat).append("\n\n");
        }

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
            // v3.0.1：日志只反映"进程启动那一刻"的注入情况，
            // 刚重启/刚升级后可能尚未刷新到最新，因此措辞不再断言"未注入"，
            // 改为给出线索 + 指向实时检测结论，避免误判。
            sb.append("日志线索（仅供参考，最终以体检首项的实时检测为准）：")
              .append("模块自身").append(self ? "出现过 ✓" : "本次未见 ✗")
              .append("；Hook 目标进程").append(anyTarget ? "有 ✓" : "本次未见 ✗（可能作用域未勾选或进程尚未重启）")
              .append("\n");
        }
        sb.append("\n");

        sb.append("---- LSPosed modules.log 尾部（已脱敏）----\n");
        String ml = readModulesLog();
        sb.append(ml.isEmpty() ? "(读取失败——可手动从 LSPosed 管理器导出模块日志)" : ml).append("\n\n");

        sb.append("===== 报告结束（内容已脱敏：手机号 / 身份证 / 取件码）=====\n");
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

    /** 收流上限：单条流最多留 20 万字符（≈ 日志 400 行的量级），防止异常输出把内存吃穿 */
    private static final int DRAIN_MAX_CHARS = 200_000;
    /** 收流线程收尾等待上限：给 1s，够正常 EOF 用不完；到点就走，绝不无限等 */
    private static final long DRAIN_JOIN_MS = 1000L;

    /**
     * 执行 su 命令；返回 {stdout, stderr, 原始err消息}，异常时 null
     *
     * v3.2.0 重排了执行顺序（重要，踩过的坑）：
     *   旧顺序 = 阻塞读完 stdout → 阻塞读完 stderr → waitFor(timeout)。
     *   BufferedReader.readLine() 要到 EOF 才返回，而 EOF 只有子进程退出时才发生，
     *   所以 waitFor(timeout, SECONDS) 那一行【永远轮不到执行】，超时分支是死代码：
     *     · 子进程正常退出：drain 先返回，waitFor 立刻就是 true，超时形同虚设；
     *     · 子进程卡住（Magisk/KernelSU 的授权弹窗没人点）：drain 永久阻塞，
     *       调用线程被永久挂起，体检界面一直转圈，用户只能强杀 App。
     *   新顺序 = 后台线程持续收流 + 主线程 waitFor(timeout) → 超时则 destroyForcibly
     *             → 最后取已经收下来的残余内容。三条约束缺一不可：
     *     · 收流必须放后台线程：否则管道缓冲写满（Linux 约 64KB）会把子进程堵死，
     *       制造出「本来能跑完，却被我们自己的等待逻辑憋死」的假超时；
     *     · 超时必须 destroyForcibly：su 卡在授权框时 destroy() 只是发 SIGTERM，不保证它死；
     *     · 收流与 join 都必须有上限：绝不允许在修完超时之后再造出第二次无限期阻塞。
     */
    private static String[] suExec(String suBin, String cmd, int timeoutSec) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"sh", "-c",
                    suBin + " -M -c \"" + cmd.replace("\"", "'") + "\""});
            final StringBuilder out = new StringBuilder();
            final StringBuilder err = new StringBuilder();
            Thread tOut = drainThread(p.getInputStream(), out, DRAIN_MAX_CHARS);
            Thread tErr = drainThread(p.getErrorStream(), err, DRAIN_MAX_CHARS);

            boolean done;
            try {
                done = p.waitFor(timeoutSec, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                done = false;
            }
            if (!done) {
                p.destroyForcibly();
                try {
                    p.waitFor(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            // 进程已经死了，两条流必然很快 EOF；join 也只等 DRAIN_JOIN_MS，留最后一道保险
            joinBounded(tOut);
            joinBounded(tErr);

            String third = done ? err.toString()
                    : "(超时 " + timeoutSec + "s 未返回，已强制结束；"
                    + "通常是 root 授权弹窗没处理或命令本身卡住)";
            return new String[]{out.toString(), err.toString(), third};
        } catch (Throwable t) {
            if (p != null) {
                try { p.destroyForcibly(); } catch (Throwable ignored) { }
            }
            return new String[]{null, null, String.valueOf(t)};
        }
    }

    /** 收流线程：daemon + 有上限，保证它挂住也拖不死调用方 */
    private static Thread drainThread(final java.io.InputStream in,
                                      final StringBuilder sb, final int max) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() { drain(in, sb, max); }
        }, "pickup-su-drain");
        t.setDaemon(true);
        t.start();
        return t;
    }

    /**
     * 读干一条流（带字符上限）。
     * 旧版没有上限：对端不关流时 readLine 永久阻塞，这正是上一任 suExec 死锁的元凶。
     * 超过上限后【继续读、只是不再追加】——直接 break 会让管道写满，把子进程堵死。
     */
    private static void drain(java.io.InputStream in, StringBuilder sb, int max) {
        if (in == null) return;
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                if (sb.length() < max) sb.append(line).append('\n');
            }
        } catch (Throwable ignored) { }
    }

    private static void joinBounded(Thread t) {
        if (t == null) return;
        try { t.join(DRAIN_JOIN_MS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
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
            // v3.2.0：改走 suExecPublic 多路径 + 2>&1/哨兵（理由同 checkSqlite3）。
            // 不加 2>&1 的话，文件不存在时 tail 的报错只进 stderr → stdout 空 →
            // suExecPublic 会一路试完 5 个 su 并返回「/su/bin/su: not found」，
            // 于是这里永远拿不到真正的日志路径，整节报告恒为空。
            String[] r = suExecPublic("tail -n 200 " + p + " 2>&1; echo " + PROBE_MARK, 15);
            if (!hasMark(r)) break;                       // su 一个路径都没跑起来：换路径也没用，直接放弃
            String out = stdoutOf(r);
            if (out.isEmpty()) continue;                   // 文件存在但是空的 → 试下一个路径
            if (out.contains("No such file") || out.contains("Not a directory")
                    || out.contains("Permission denied") || out.contains("cannot open")) {
                continue;                                  // 路径不对 / 读不到 → 试下一个
            }
            return "(" + p + ")\n" + out;
        }
        return "";
    }

    /**
     * 脱敏：手机号 → 138****8888；身份证 → 6+8+4 分段掩码；取件码 → 保留形状的 * 掩码。
     *
     * v3.2.0 重做要点（详见文件头的「脱敏规则」注释）：
     *   · 码形从 1 种扩到 5 条规则（多段横线 / 字母 / 竖线指纹位 / 左语境 / 右语境）；
     *   · 短信正文片段确实会随 PICKUPDEBUG 日志进报告（EVENT: … body=…），
     *     所以除取件码外还要盖手机号与身份证；
     *   · 刻意【不做】姓名/住址脱敏：要可靠就得带姓名词典和行政区划词典，
     *     APK 体积、误伤率和维护成本都不划算，而报告本就是用户主动导出给自己看的。
     *     需要时用户可在导出前自行删掉正文——这属于产品策略，不在脱敏层硬凑。
     *   顺序有讲究：先手机号/身份证，再取件码。反过来的话「取件码 13812345678」会被
     *   取件码规则先吃掉一段数字，剩下的残骸再也匹配不上手机号规则。
     */
    public static String sanitize(String s) {
        if (s == null) return "";
        s = P_PHONE.matcher(s).replaceAll(PHONE_MASK);
        s = maskIdCards(s);
        s = maskToken(s, P_CODE);          // 多段横线码
        s = maskToken(s, P_CODE_ALPHA);    // 字母码 A88123
        s = maskToken(s, P_CODE_SEP);      // 指纹位 <码>|<地点>
        s = maskToken(s, P_CODE_CTX);      // 左语境
        s = maskToken(s, P_CODE_TAIL);     // 右语境
        return s;
    }

    /** 身份证：保留前 6 位（地区）与后 4 位（校验线索），中间 8 位全掩码 */
    private static String maskIdCards(String s) {
        Matcher m = P_IDCARD.matcher(s);
        StringBuffer sbf = new StringBuffer();
        while (m.find()) {
            String id = m.group();
            String masked = id.length() >= 10
                    ? id.substring(0, 6) + "********" + id.substring(id.length() - 4)
                    : maskCode(id);
            m.appendReplacement(sbf, Matcher.quoteReplacement(masked));
        }
        m.appendTail(sbf);
        return sbf.toString();
    }

    /**
     * 有捕获组就只把捕获组（码本身）换成掩码。
     * 注意不能简单 appendReplacement(mask(group1))——那会把【整段命中】替换掉，
     * 连带把语境关键词、连接符、分隔符（"取件码为"、"，请凭此码"）一起吃掉，
     * 报告文本会被啃得莫名其妙、也没法再人工核对。
     * 所以按 start(1)/end(1) 在原命中里就地替换，前后原样保留。
     */
    private static String maskToken(String s, Pattern p) {
        Matcher m = p.matcher(s);
        StringBuffer sbf = new StringBuffer();
        while (m.find()) {
            String whole = m.group();
            String masked = whole;
            if (m.groupCount() >= 1 && m.group(1) != null) {
                int a = m.start(1) - m.start();
                int b = m.end(1) - m.start();
                masked = whole.substring(0, a) + maskCode(m.group(1)) + whole.substring(b);
            } else {
                masked = maskCode(whole);
            }
            m.appendReplacement(sbf, Matcher.quoteReplacement(masked));
        }
        m.appendTail(sbf);
        return sbf.toString();
    }

    /** 码掩码：字母保留、数字全换 *——既看不出真值，又保留长度/分段形状便于排查 */
    private static String maskCode(String token) {
        if (token == null || token.isEmpty()) return "***";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            sb.append(Character.isDigit(c) ? '*' : c);
        }
        return sb.toString();
    }

    private static String tail(String s, int max) {
        if (s == null) return "";
        s = s.trim();
        return s.length() <= max ? s : s.substring(s.length() - max);
    }
}
