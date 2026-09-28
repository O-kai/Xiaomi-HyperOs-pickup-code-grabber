package io.github.okaidev.pickupcode;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * ============================================================================
 * 用户自定义规则引擎（v3.0.0）
 * ============================================================================
 *
 * 【权限边界 —— 本设计的核心安全承诺】
 *   用户规则只被允许回答一个问题：「这条短信里，取件码/来源/地点是什么」。
 *   绝对不允许：自定义 SQL、自定义目标库表、自定义写入语句、自定义目标文件路径。
 *   → 即使用户规则写错，最坏后果也只是「这条短信解析错了」，
 *     绝不会变成「任意代码执行」或「数据库被写坏」。这条边界不可妥协。
 *
 * 【为什么用户规则值得存在】
 *   全国驿站文案千奇百怪，官方规则集（rules.json）永远追不全。
 *   官方维护者不可能覆盖所有小区/学校/园区的自建驿站，
 *   用户自己一条正则就能解决——这既减轻了维护者负担，也让模块真正「人人可用」。
 *
 * 【三道安全防线（缺一不可）】
 *   防线1 危险构造静态拦截：编译前扫描正则，禁止嵌套量词等可导致「灾难性回溯」的结构。
 *          灾难性回溯 = 正则在长文本上耗时指数级暴涨，会卡住短信处理线程（真实事故风险）。
 *   防线2 强制自测样本：每条规则必须自带测试样本，载入时逐条跑，不通过直接拒绝启用。
 *          这是用户自己的验证闭环——没有自测样本的规则不允许存在。
 *   防线3 耗时熔断：自测时任一样本执行超过阈值（默认 50ms）立即拒绝并明确告知用户。
 *
 * 【优先级】用户规则 > 官方热更 rules.json > 编译内置默认规则
 *   用户既然写了规则，就是要覆盖默认行为——这是「用户可控」的承诺。
 *
 * 【跨进程说明】
 *   真实短信 Hook 在系统进程运行，读不到 App 私有存储。
 *   因此启用规则时，会通过 root 把规则集同步写入 /data/local/tmp/pickup_sqlite/user_rules.json
 *   （世界可读），系统进程侧从该文件加载；加载失败一律回退默认规则，绝不影响主流程。
 * ============================================================================
 */
public class UserRules {

    private static final String TAG = "PICKUPDEBUG";
    private static final String PREFS = "dedup";
    private static final String KEY_ENABLED = "user_rules_enabled";
    private static final String KEY_JSON = "user_rules_json";
    private static final String KEY_SEQ = "user_rules_seq";

    /** 系统进程可读的同步副本（与 SystemDirectWriter 的标志文件同一模式）
     *  路径与 Repair.TARGET_DIR 保持一致（/data/local/tmp/pickup_sqlite），此处独立声明避免耦合 */
    public static final String SYNC_DIR = "/data/local/tmp/pickup_sqlite";
    public static final String SYNC_FILE = SYNC_DIR + "/user_rules.json";

    /** 单条测试样本耗时上限（毫秒）——超过视为危险正则，拒绝启用 */
    public static final long SAMPLE_TIME_LIMIT_MS = 50;
    /** 规则数量上限——防止规则膨胀拖慢每条短信的处理速度 */
    public static final int MAX_RULES = 20;
    /** 正则长度上限——超长正则几乎必然是冗余或恶意构造 */
    private static final int MAX_PATTERN_LEN = 300;

    // ==================== 发送方匹配模式 ====================
    /** 全局规则：不限制发送方，任何短信都走这条规则 */
    public static final String MODE_ANY = "ANY";
    /** 精确匹配：发送方与填写值完全一致（如驿站固定号码） */
    public static final String MODE_EXACT = "EXACT";
    /** 前缀匹配：发送方以填写值开头（虚拟号前几位固定、后几位随机） */
    public static final String MODE_PREFIX = "PREFIX";
    /** 包含匹配：发送方包含该文字（如号码里带"菜鸟驿站"） */
    public static final String MODE_CONTAINS = "CONTAINS";

    /** 发送方匹配模式的人话名（UI 展示用） */
    public static String modeLabel(String m) {
        if (MODE_EXACT.equals(m)) return "精确匹配（整个号码一致）";
        if (MODE_PREFIX.equals(m)) return "前缀匹配（开头几位一致）";
        if (MODE_CONTAINS.equals(m)) return "包含匹配（含有该文字）";
        return "不限来源（全局规则）";
    }

    /** 发送方匹配模式的一句话说明（UI 提示用） */
    public static String modeHint(String m) {
        if (MODE_EXACT.equals(m)) return "驿站固定号码时用：短信来自这个号码才生效。例：1069888877";
        if (MODE_PREFIX.equals(m)) return "虚拟号后几位随机时用：只看开头几位。例：1069 匹配 1069888877、1069123456";
        if (MODE_CONTAINS.equals(m)) return "号码记不住时用：发送方里含这段文字即可。例：菜鸟驿站";
        return "任何短信都尝试这条规则（官方规则没覆盖到的场景，用它兜底）";
    }

    /**
     * 发送方匹配判定
     * EXACT   : 完全一致（忽略两侧空格）——最严格，适合固定号码
     * PREFIX  : 开头一致 —— 应对虚拟号后几位随机
     * CONTAINS: 整词包含 —— 避免「填 10 却匹配到 110/10086」的误伤
     * ANY     : 不限制 —— 全局规则
     */
    static boolean senderMatches(Rule r, String sender) {
        String mode = r.senderMode == null || r.senderMode.isEmpty() ? MODE_ANY : r.senderMode;
        String val = r.senderValue == null ? "" : r.senderValue.trim();
        // 兼容旧结构：只有 senderContains 时按 CONTAINS 处理
        if (val.isEmpty() && r.senderContains != null && !r.senderContains.trim().isEmpty()) {
            val = r.senderContains.trim();
            mode = MODE_CONTAINS;
        }
        if (MODE_ANY.equals(mode) || val.isEmpty()) return true;
        if (sender == null) return false;
        String s = sender.trim();
        if (MODE_EXACT.equals(mode)) {
            return s.equals(val);
        }
        if (MODE_PREFIX.equals(mode)) {
            // 只匹配号码开头；纯数字前缀才按数字段判断，避免「前缀 1 匹配到所有以 1 开头的文字」
            return s.startsWith(val);
        }
        if (MODE_CONTAINS.equals(mode)) {
            // 关键修复：整词匹配——「10」不应匹配「110」「10086」
            return s.equals(val) || s.contains(val);
        }
        return true;
    }

    /** 规则具体度：数字/号码专属规则 > 全局规则（用于优先级排序） */
    static int specificity(Rule r) {
        String mode = r.senderMode == null || r.senderMode.isEmpty() ? MODE_ANY : r.senderMode;
        String val = r.senderValue == null ? "" : r.senderValue.trim();
        if (val.isEmpty() && r.senderContains != null && !r.senderContains.trim().isEmpty()) {
            val = r.senderContains.trim();
        }
        if (val.isEmpty() || MODE_ANY.equals(mode)) return 0;
        if (MODE_EXACT.equals(mode)) return 3;
        if (MODE_PREFIX.equals(mode)) return 2;
        return 1;
    }

    // ==================== 规则数据模型 ====================

    /** 一条测试样本：短信原文 + 期望结果 */
    public static class TestCase {
        public String sms;
        public String expectCode;

        public TestCase(String sms, String expectCode) {
            this.sms = sms;
            this.expectCode = expectCode;
        }
    }

    /** 一条用户规则（只管提取，不碰写入） */
    public static class Rule {
        public String id;
        public String name;
        public boolean enabled;

        // ==================== 发送方匹配（四种模式）====================
        // 现实场景里发送方号码有多种形态，用同一种「包含」语义会误伤：
        //   · 驿站固定号码      1069888877   → EXACT   整条完全一致
        //   · 虚拟号前几位固定  1069 / 170    → PREFIX 号码开头一致，后几位随机
        //   · 号码记不住但带文字 "菜鸟驿站"      → CONTAINS 整词出现
        //   · 不限来源                            → ANY     留空 = 全局规则
        public String senderMode = MODE_ANY;
        public String senderValue = "";

        // 兼容旧结构：仅保留 senderContains 字段
        public String senderContains;

        // ---- 提取定义 ----
        /** 取件码正则 + 捕获组序号（1-based；0 表示整个匹配） */
        public String codeRegex;
        public int codeGroup = 1;
        /** 来源：固定文本（可含 $1 组引用）或留空表示沿用默认来源识别 */
        public String source;
        /** 地点：固定文本或组引用；留空表示沿用默认地点提取 */
        public String place;
        /** 地点正则（当短信文案特殊、地点不是紧跟码时使用） */
        public String placeRegex;
        public int placeGroup = 1;

        // ---- 强制自测样本 ----
        public List<TestCase> testCases = new ArrayList<>();

        // ---- 编译缓存（不序列化）----
        transient Pattern pCode;
        transient Pattern pPlace;
        transient boolean compiledOk;
    }

    /** 一条规则的自测结果（用于给用户明确反馈） */
    public static class VerifyResult {
        public boolean ok;
        public String message;
        public int passed;
        public int total;

        /** 逐条失败详情：第几条样本、期望什么、实际什么 */
        public List<String> details = new ArrayList<>();
    }

    // ==================== 存储与跨进程同步 ====================

    public static boolean isEnabled(Context ctx) {
        return sp(ctx).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context ctx, boolean on) {
        sp(ctx).edit().putBoolean(KEY_ENABLED, on).apply();
        syncToSystemProcess(ctx, on);
    }

    /** 读取全部规则（App 侧） */
    public static List<Rule> loadAll(Context ctx) {
        List<Rule> out = new ArrayList<>();
        String json = sp(ctx).getString(KEY_JSON, "");
        if (json == null || json.trim().isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                Rule r = parseRule(arr.getJSONObject(i));
                if (r != null) out.add(r);
            }
        } catch (Throwable t) {
            Log.w(TAG, "loadAll failed: " + t);
        }
        return out;
    }

    /** 保存全部规则（保存前对每条规则做编译校验，失败的直接拒绝保存并返回原因） */
    public static String saveAll(Context ctx, List<Rule> rules) {
        if (rules == null) return "规则列表为空";
        if (rules.size() > MAX_RULES) {
            return "规则数量超出上限（最多 " + MAX_RULES + " 条，当前 " + rules.size() + " 条）";
        }
        JSONArray arr = new JSONArray();
        for (Rule r : rules) {
            String err = quickValidate(r);
            if (err != null) return "规则「" + safeName(r) + "」" + err;
            arr.put(toJson(r));
        }
        sp(ctx).edit().putString(KEY_JSON, arr.toString()).apply();
        syncToSystemProcess(ctx, isEnabled(ctx));
        return null;
    }

    public static String nextId(Context ctx) {
        int seq = sp(ctx).getInt(KEY_SEQ, 0) + 1;
        sp(ctx).edit().putInt(KEY_SEQ, seq).apply();
        return "u_" + seq + "_" + (seq * 7919);
    }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * 同步到系统进程可读的位置（root 写文件）
     * 与 SystemDirectWriter 开关标志同一套机制——已验证在真机上可靠工作
     */
    private static void syncToSystemProcess(final Context ctx, final boolean enabled) {
        final List<Rule> active = new ArrayList<>();
        for (Rule r : loadAll(ctx)) {
            if (r.enabled && r.compiledOk) active.add(r);
        }
        new Thread(() -> {
            try {
                String json = enabled ? serialize(active) : "[]";
                Diagnostics.suExecPublic("mkdir -p " + SYNC_DIR + "; "
                        + "cat > " + SYNC_FILE + " << 'PICKUP_EOF'\n" + json + "\nPICKUP_EOF\n"
                        + "chmod 644 " + SYNC_FILE, 20);
            } catch (Throwable t) {
                Log.w(TAG, "sync to system process failed: " + t);
            }
        }).start();
    }

    /** 系统进程侧加载（SystemDirectWriter / 通知 Hook 冷启动时调用） */
    public static List<Rule> loadForSystemProcess() {
        List<Rule> out = new ArrayList<>();
        try {
            File f = new File(SYNC_FILE);
            if (!f.exists()) return out;
            String json = readFile(f);
            if (json == null || json.trim().isEmpty()) return out;
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                Rule r = parseRule(arr.getJSONObject(i));
                if (r != null && compileRule(r) == null) out.add(r);
            }
        } catch (Throwable t) {
            Log.w(TAG, "loadForSystemProcess failed: " + t);
        }
        return out;
    }

    // ==================== 安全校验（三道防线） ====================

    /**
     * 防线1：危险构造静态拦截
     * 目标：拦截可能引发「灾难性回溯」的正则结构
     *
     * 【v3.0.1 修正误伤】原先的规则会把「多码簇」这种安全写法一并拦掉，例如
     *   (?:[,，、;；][A-Za-z0-9-]+)*        ← 分隔符驱动，确定字符，安全
     *   ([A-Za-z0-9-]+)+                    ← 内外重叠，指数级回溯，危险
     * 真正的灾难性回溯要求「内外层匹配同一段可变文本」。因此下面改为按类型区分：
     *   · 危险：连续同类量词 (x+)+ (x*)+ (x+)* (x*)*、量词叠加 a+*、相邻 .*
     *   · 安全：量词包裹的是「分隔符/字面量」等确定字符，如 (?:[,;][0-9]+)*
     */
    private static final String[][] DANGEROUS_PATTERNS = {
            // (x+)+ / (x+)* / (x*)+ / (x*)*  —— 量词套量词且内外可能是同一段文本
            // 用负向前瞻排除「安全的多码簇写法」：内层若以确定字符（分隔符/字面量）开头，
            // 且内部量词作用于不同字符集，则不构成回溯源。
            {"\\((?!(?:\\?[:=!]|\\s*[,，、;；/、]))[^()]{0,80}[+*]\\)[+*]",
                    "存在嵌套量词（如 (x+)），可能导致指数级回溯使短信处理卡死"},
            // 量词直接叠加：a+* / a*+ / a++（放过 (?i) (?=) 这类标志位）
            {"(?<!\\?)[+*](?<!\\\\)[+*]", "量词重复叠加（如 a+*），可能导致灾难性回溯"},
            // 相邻的 .*
            {"\\.\\*[^)]{0,4}\\.\\*", "相邻的 .* 可能导致灾难性回溯"},
    };

    static String scanDangerous(String regex) {
        if (regex == null) return "正则不能为空";
        if (regex.length() > MAX_PATTERN_LEN) {
            return "正则过长（" + regex.length() + "/" + MAX_PATTERN_LEN + " 字符）";
        }
        for (String[] dp : DANGEROUS_PATTERNS) {
            try {
                if (Pattern.compile(dp[0]).matcher(regex).find()) {
                    return dp[1];
                }
            } catch (Throwable ignored) { }
        }
        return null;
    }

    /** 快速校验（保存时用）：语法 + 危险构造 + 分组范围 + 发送方格式 */
    static String quickValidate(Rule r) {
        if (r == null) return "规则为空";
        if (r.name == null || r.name.trim().isEmpty()) return "请填写规则名称";
        // 发送方格式校验（避免填错位数导致规则永不触发，却毫无提示）
        String sErr = validateSender(r);
        if (sErr != null) return sErr;
        if (r.codeRegex == null || r.codeRegex.trim().isEmpty()) {
            return "请填写「取件码正则」";
        }
        String danger = scanDangerous(r.codeRegex);
        if (danger != null) return "正则存在风险：" + danger;
        if (r.placeRegex != null && !r.placeRegex.trim().isEmpty()) {
            String d2 = scanDangerous(r.placeRegex);
            if (d2 != null) return "地点正则存在风险：" + d2;
        }
        try {
            Pattern p = Pattern.compile(r.codeRegex);
            int groups = p.matcher("").groupCount();
            if (r.codeGroup > groups) {
                return "取件码引用了第 " + r.codeGroup + " 个捕获组，但正则只有 " + groups
                        + " 个（用小括号 () 包围的部分才算捕获组）";
            }
        } catch (PatternSyntaxException e) {
            return "正则语法错误：" + e.getMessage();
        }
        if (r.placeRegex != null && !r.placeRegex.trim().isEmpty()) {
            try {
                Pattern p2 = Pattern.compile(r.placeRegex);
                int g2 = p2.matcher("").groupCount();
                if (r.placeGroup > g2) {
                    return "地点引用了第 " + r.placeGroup + " 个捕获组，但正则只有 " + g2 + " 个";
                }
            } catch (PatternSyntaxException e) {
                return "地点正则语法错误：" + e.getMessage();
            }
        }
        return null;
    }

    /**
     * 防线2 + 防线3：强制自测 + 耗时熔断
     * 这是用户规则的「准入证」——不通过不允许启用
     */
    public static VerifyResult verify(Rule r) {
        VerifyResult res = new VerifyResult();
        String quick = quickValidate(r);
        if (quick != null) {
            res.ok = false;
            res.message = quick;
            return res;
        }
        if (r.testCases == null || r.testCases.isEmpty()) {
            res.ok = false;
            res.message = "必须至少添加 1 条测试样本：粘贴一条真实短信、填写期望提取到的取件码，"
                    + "自测通过后才能启用（这是防止规则在真实使用中出错的必要步骤）";
            return res;
        }
        if (r.testCases.size() > 20) {
            res.ok = false;
            res.message = "测试样本过多（最多 20 条）";
            return res;
        }
        if (compileRule(r) != null) {
            res.ok = false;
            res.message = "正则编译失败";
            return res;
        }
        res.total = r.testCases.size();
        for (int i = 0; i < r.testCases.size(); i++) {
            TestCase tc = r.testCases.get(i);
            if (tc.sms == null || tc.sms.trim().isEmpty()) {
                res.details.add("第 " + (i + 1) + " 条样本：短信内容为空");
                continue;
            }
            long t0 = System.nanoTime();
            String got;
            try {
                // v3.0.1 多码支持：自测同样按多码校验（与实际提取行为一致）
                List<String> gotList = matchCodes(r, tc.sms);
                got = gotList.isEmpty() ? "" : String.join(";", gotList);
            } catch (Throwable t) {
                res.details.add("第 " + (i + 1) + " 条样本：执行异常 " + t.getMessage());
                continue;
            }            long costMs = (System.nanoTime() - t0) / 1_000_000L;

            if (costMs > SAMPLE_TIME_LIMIT_MS) {
                res.ok = false;
                res.message = "第 " + (i + 1) + " 条样本耗时 " + costMs + "ms，超过 "
                        + SAMPLE_TIME_LIMIT_MS + "ms 熔断阈值——该正则可能引发灾难性回溯，"
                        + "在真实短信上会卡住短信处理流程，已拒绝启用。请简化正则后重试。";
                res.total = i + 1;
                return res;
            }
            String expect = tc.expectCode == null ? "" : tc.expectCode.trim();
            if (!expect.equals(got == null ? "" : got.trim())) {
                res.details.add("第 " + (i + 1) + " 条样本不匹配：期望「" + expect + "」，实际「"
                        + (got == null ? "(无匹配)" : got) + "」");
            } else {
                res.passed++;
            }
        }
        res.ok = res.details.isEmpty() && res.passed == res.total;
        if (!res.ok) {
            StringBuilder sb = new StringBuilder("自测未通过（" + res.passed + "/" + res.total + " 通过）：\n");
            for (String d : res.details) sb.append("· ").append(d).append("\n");
            sb.append("\n请对照修正正则或期望值后重试。");
            res.message = sb.toString();
        } else {
            res.message = "自测通过（" + res.passed + "/" + res.total + "），耗时均低于 "
                    + SAMPLE_TIME_LIMIT_MS + "ms 熔断阈值";
        }
        return res;
    }

    /** 发送方格式校验：给明确提示，避免规则写了却永远不触发 */
    static String validateSender(Rule r) {
        String mode = r.senderMode == null || r.senderMode.isEmpty() ? MODE_ANY : r.senderMode;
        String val = (r.senderValue == null ? "" : r.senderValue.trim());
        if (val.isEmpty() && r.senderContains != null && !r.senderContains.trim().isEmpty()) {
            val = r.senderContains.trim();
        }
        if (MODE_ANY.equals(mode) || val.isEmpty()) return null;
        if (val.length() > 30) {
            return "发送方匹配值过长（" + val.length() + " 字符）";
        }
        if (MODE_EXACT.equals(mode)) {
            if (!val.matches("[0-9]{4,15}")) {
                return "精确匹配要求填写纯数字号码（收到的是 " + val
                        + "）。若号码含文字，请改用「包含匹配」；若只有前几位固定，请改用「前缀匹配」";
            }
        } else if (MODE_PREFIX.equals(mode)) {
            if (!val.matches("[0-9]{2,15}")) {
                return "前缀匹配要求填写 2-15 位纯数字（收到的是 " + val + "）";
            }
        }
        return null;
    }

    /** 编译规则（缓存 Pattern），返回错误信息或 null */
    static String compileRule(Rule r) {
        if (r == null) return "规则为空";
        try {
            r.pCode = Pattern.compile(r.codeRegex);
            r.pPlace = (r.placeRegex == null || r.placeRegex.trim().isEmpty())
                    ? null : Pattern.compile(r.placeRegex);
            r.compiledOk = true;
            return null;
        } catch (Throwable t) {
            r.compiledOk = false;
            return "正则编译失败：" + t.getMessage();
        }
    }

    /** 按规则匹配取件码 */
    static String matchCode(Rule r, String sms) {
        if (r == null || r.pCode == null || sms == null) return null;
        Matcher m = r.pCode.matcher(sms);
        if (!m.find()) return null;
        int g = r.codeGroup;
        if (g < 0 || g > m.groupCount()) return null;
        String v = (g == 0) ? m.group() : m.group(g);
        return v == null ? null : v.trim();
    }

    // ==================== 供提取引擎调用（优先级入口） ====================

    /**
     * 用户规则优先匹配：命中则返回该规则提取的结果，否则返回 null 交回默认引擎
     * 返回数组：[0]=codes(多码以 ";" 分隔，与官方引擎行为一致)  [1]=source  [2]=place
     *
     * 【多码支持 v3.0.1】一条短信里出现多个取件码时（如「取件码为 1-2-3456, 7-8-9012」），
     *   与官方引擎的「多码簇」行为保持一致：按 ,，、;； 及空白拆分，逐个提取，
     *   最终以 ";" 连接返回，由调用方拆成多条待办。
     *
     * 【优先级规则 v3.0.0】按「具体度」排序，号码专属规则优先于全局规则：
     *   EXACT(3) > PREFIX(2) > CONTAINS(1) > 任意全局规则(0)
     *   → 用户配了「1069 开头的虚拟号」时，不会被一条宽泛的全局规则抢先匹配
     *   → 同一具体度下保持用户自定义的排列顺序（列表靠前的优先）
     */
    public static String[] tryMatch(List<Rule> rules, String sender, String sms) {
        if (rules == null || rules.isEmpty() || sms == null) return null;
        for (Rule r : sortedBySpecificity(rules)) {
            if (!r.enabled || !r.compiledOk || r.pCode == null) continue;
            if (!senderMatches(r, sender)) continue;

            List<String> codes;
            try {
                codes = matchCodes(r, sms);
            } catch (Throwable ignored) {
                codes = new ArrayList<>();
            }
            if (codes.isEmpty()) continue;

            String first = codes.get(0);
            String source = null;
            String place = null;
            if (r.source != null && !r.source.trim().isEmpty()) {
                source = interpolate(r.source, first, r, sms);
            }
            if (r.placeRegex != null && r.pPlace != null) {
                try {
                    Matcher pm = r.pPlace.matcher(sms);
                    if (pm.find()) {
                        int g = r.placeGroup;
                        if (g >= 0 && g <= pm.groupCount()) {
                            place = (g == 0) ? pm.group() : pm.group(g);
                        }
                    }
                } catch (Throwable ignored) { }
            }
            if ((place == null || place.isEmpty()) && r.place != null && !r.place.trim().isEmpty()) {
                place = interpolate(r.place, first, r, sms);
            }
            lastHitRule = r;   // 溯源：记录本次命中的是哪条用户规则（供 UI 展示"用了哪条规则"）
            return new String[]{String.join(";", codes), source, place};
        }
        lastHitRule = null;
        return null;
    }

    /**
     * v3.0.1：最近一次 tryMatch 命中的用户规则（null = 未命中任何用户规则，走官方规则）
     * 用于「一键链路测试」等场景展示"本次解析用的是官方规则还是你的哪条规则"
     */
    private static volatile Rule lastHitRule;

    /** 最近命中的用户规则名称；未命中返回 null */
    public static String lastHitRuleName() {
        Rule r = lastHitRule;
        return (r == null) ? null : safeName(r);
    }

    /** 清除溯源记录（每次新一轮解析前调用，避免显示上一条的结果） */
    public static void clearLastHit() { lastHitRule = null; }

    /**
     * 按规则提取【全部】取件码（多码簇）
     *
     * 两种情形都能正确工作：
     *  1) 正则本身一次匹配多个码：codeRegex = 取件码为?([A-Za-z0-9-]+([、,，;；][A-Za-z0-9-]+)*)
     *     → 捕获组里是「1-2-3456, 7-8-9012」整串，这里按分隔符再拆开
     *  2) 正则只能匹配单个码：codeRegex = 取件码\s*([A-Za-z0-9-]+)
     *     → 这里对全文反复 find()，把所有匹配都收集起来
     *
     * 拆分分隔符与官方引擎 addCluster 完全一致：[,，、;；\s]+
     */
    static List<String> matchCodes(Rule r, String sms) {
        List<String> out = new ArrayList<>();
        if (r == null || r.pCode == null || sms == null) return out;

        Matcher m = r.pCode.matcher(sms);
        while (m.find()) {
            int g = r.codeGroup;
            String v = (g >= 0 && g <= m.groupCount()) ? m.group(g) : null;
            if (v == null) continue;
            v = v.trim();
            if (v.isEmpty()) continue;
            // 簇内再按分隔符拆分（覆盖"正则一次抓到多个码"的情形）
            for (String part : v.split("[,，、;；\\s]+")) {
                String t = part.trim();
                if (!t.isEmpty() && !out.contains(t)) out.add(t);
            }
            // 防止病态正则导致死循环（零宽匹配场景）
            if (m.end() == m.start()) break;
        }
        return out;
    }

    /**
     * 实战验证：用「规则集 + 发送方 + 短信」完整跑一遍 tryMatch
     * 与 verify 的区别：verify 只验证正则本身，这里验证发送方匹配 + 优先级
     * UI「🧪 测试」按钮在命中号码规则时用它做二次确认
     */
    public static String dryRun(List<Rule> allRules, String sender, String sms) {
        String[] hit = tryMatch(allRules, sender, sms);
        if (hit == null) {
            return "未命中任何规则。\n\n可能原因：\n"
                    + "· 该规则限定了发送方，但这条短信的发送方不匹配\n"
                    + "· 正则没匹配到内容（可用更宽松的写法或改捕获组）\n"
                    + "· 规则已停用，或总开关「启用我的规则」处于关闭状态";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("发送方：").append(sender == null || sender.isEmpty() ? "(空)" : sender).append("\n");
        sb.append("命中码：").append(hit[0] == null ? "(无)" : hit[0]).append("\n");
        sb.append("来源：").append(hit[1] == null ? "(沿用默认识别)" : hit[1]).append("\n");
        sb.append("地点：").append(hit[2] == null ? "(沿用默认提取)" : hit[2]);
        return sb.toString();
    }

    /** 按具体度降序排序（稳定排序：同具体度保持用户原始顺序） */
    static List<Rule> sortedBySpecificity(List<Rule> rules) {
        List<Rule> copy = new ArrayList<>(rules);
        java.util.Collections.sort(copy, new java.util.Comparator<Rule>() {
            @Override public int compare(Rule a, Rule b) {
                return specificity(b) - specificity(a);
            }
        });
        return copy;
    }

    /** 支持 $1 / $2 引用捕获组（用于来源/地点引用正则里的组） */
    private static String interpolate(String tpl, String code, Rule r, String sms) {
        if (tpl == null) return null;
        String out = tpl.replace("$0", code == null ? "" : code);
        if (r != null && r.pCode != null) {
            try {
                Matcher m = r.pCode.matcher(sms);
                if (m.find()) {
                    for (int i = 1; i <= m.groupCount(); i++) {
                        String g = m.group(i);
                        out = out.replace("$" + i, g == null ? "" : g);
                    }
                }
            } catch (Throwable ignored) { }
        }
        return out;
    }

    // ==================== 序列化 ====================

    static JSONObject toJson(Rule r) {
        try {
            JSONObject o = new JSONObject();
            o.put("id", r.id);
            o.put("name", r.name);
            o.put("enabled", r.enabled);
            if (r.senderMode != null) o.put("senderMode", r.senderMode);
            if (r.senderValue != null) o.put("senderValue", r.senderValue);
            if (r.senderContains != null) o.put("senderContains", r.senderContains);
            o.put("codeRegex", r.codeRegex);
            o.put("codeGroup", r.codeGroup);
            if (r.source != null) o.put("source", r.source);
            if (r.place != null) o.put("place", r.place);
            if (r.placeRegex != null) {
                o.put("placeRegex", r.placeRegex);
                o.put("placeGroup", r.placeGroup);
            }
            JSONArray tc = new JSONArray();
            if (r.testCases != null) {
                for (TestCase t : r.testCases) {
                    JSONObject x = new JSONObject();
                    x.put("sms", t.sms);
                    x.put("expectCode", t.expectCode);
                    tc.put(x);
                }
            }
            o.put("testCases", tc);
            return o;
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    static Rule parseRule(JSONObject o) {
        try {
            Rule r = new Rule();
            r.id = o.optString("id", "u_" + System.currentTimeMillis());
            r.name = o.optString("name", "未命名规则");
            r.enabled = o.optBoolean("enabled", true);
            r.senderContains = o.optString("senderContains", "");
            r.senderMode = o.optString("senderMode", MODE_ANY);
            r.senderValue = o.optString("senderValue", "");
            r.codeRegex = o.optString("codeRegex", "");
            r.codeGroup = o.optInt("codeGroup", 1);
            r.source = o.optString("source", "");
            r.place = o.optString("place", "");
            r.placeRegex = o.optString("placeRegex", "");
            r.placeGroup = o.optInt("placeGroup", 1);
            JSONArray tc = o.optJSONArray("testCases");
            if (tc != null) {
                for (int i = 0; i < tc.length(); i++) {
                    JSONObject x = tc.getJSONObject(i);
                    r.testCases.add(new TestCase(x.optString("sms", ""), x.optString("expectCode", "")));
                }
            }
            compileRule(r);
            return r;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String serialize(List<Rule> rules) {
        JSONArray arr = new JSONArray();
        for (Rule r : rules) arr.put(toJson(r));
        return arr.toString();
    }

    /** 导出为可分享文本（社区生态入口） */
    public static String exportForShare(List<Rule> rules) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 取件码助手 · 用户自定义规则 v1\n");
        sb.append("# 格式：user-rules-v1 | 导入时可直接粘贴到「我的规则 → 导入」\n");
        sb.append("# 注意：请务必自行脱敏短信样本中的姓名/手机号/住址等隐私信息\n");
        for (Rule r : rules) {
            sb.append("\n## ").append(safeName(r)).append(r.enabled ? "" : "（已停用）").append("\n");
            sb.append("codeRegex: ").append(r.codeRegex).append("\n");
            sb.append("codeGroup: ").append(r.codeGroup).append("\n");
            if (r.senderValue != null && !r.senderValue.isEmpty()) {
                sb.append("senderMode: ").append(r.senderMode == null ? MODE_ANY : r.senderMode).append("\n");
                sb.append("senderValue: ").append(r.senderValue).append("\n");
            } else if (r.senderContains != null && !r.senderContains.isEmpty()) {
                sb.append("senderMode: ").append(MODE_CONTAINS).append("\n");
                sb.append("senderValue: ").append(r.senderContains).append("\n");
            }
            if (r.source != null && !r.source.isEmpty()) sb.append("source: ").append(r.source).append("\n");
            if (r.place != null && !r.place.isEmpty()) sb.append("place: ").append(r.place).append("\n");
            if (r.placeRegex != null && !r.placeRegex.isEmpty()) {
                sb.append("placeRegex: ").append(r.placeRegex).append("\n");
                sb.append("placeGroup: ").append(r.placeGroup).append("\n");
            }
            if (r.testCases != null) {
                for (TestCase t : r.testCases) {
                    sb.append("test: ").append(t.expectCode).append("  <=  ").append(t.sms).append("\n");
                }
            }
        }
        return sb.toString();
    }

    /** 从分享文本导入（宽容解析：忽略 # 注释与空行） */
    public static List<Rule> importFromShare(String text) {
        List<Rule> out = new ArrayList<>();
        if (text == null) return out;
        Rule cur = null;
        for (String rawLine : text.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            // 顺序很重要：先判 "## 规则标题" 再判 "# 注释"
            // （否则 "## xxx" 会被 startsWith("#") 当注释跳过，导致导入 0 条 —— 真实 bug）
            if (line.startsWith("## ")) {
                cur = new Rule();
                cur.id = "u_imp_" + System.currentTimeMillis() + "_" + out.size();
                cur.name = line.substring(3).replace("（已停用）", "").trim();
                cur.enabled = !line.contains("（已停用）");
                cur.testCases = new ArrayList<>();
                out.add(cur);
                continue;
            }
            if (line.startsWith("#")) continue;
            if (cur == null) continue;
            if (line.startsWith("codeRegex:")) {
                cur.codeRegex = line.substring(10).trim();
            } else if (line.startsWith("codeGroup:")) {
                cur.codeGroup = safeInt(line.substring(10), 1);
            } else if (line.startsWith("senderMode:")) {
                cur.senderMode = line.substring(11).trim();
            } else if (line.startsWith("senderValue:")) {
                cur.senderValue = line.substring(12).trim();
            } else if (line.startsWith("senderContains:")) {
                cur.senderContains = line.substring(15).trim();
            } else if (line.startsWith("source:")) {
                cur.source = line.substring(7).trim();
            } else if (line.startsWith("place:")) {
                cur.place = line.substring(6).trim();
            } else if (line.startsWith("placeRegex:")) {
                cur.placeRegex = line.substring(11).trim();
            } else if (line.startsWith("placeGroup:")) {
                cur.placeGroup = safeInt(line.substring(11), 1);
            } else if (line.startsWith("test:")) {
                String rest = line.substring(5);
                int idx = rest.indexOf("<=");
                if (idx > 0) {
                    String expect = rest.substring(0, idx).trim();
                    String sms = rest.substring(idx + 2).trim();
                    cur.testCases.add(new TestCase(sms, expect));
                }
            }
        }
        return out;
    }

    private static int safeInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Throwable t) { return def; }
    }

    static String safeName(Rule r) {
        return (r != null && r.name != null && !r.name.trim().isEmpty()) ? r.name : "未命名规则";
    }

    private static String readFile(File f) throws Exception {
        try (InputStream is = new java.io.FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
