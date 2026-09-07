package io.github.okaidev.pickupcode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 取件码提取引擎 v3（2026-09-07 语料驱动重构）
 *
 * 设计转变：v2 是"关键词表+固定结构"（从单一设备语料倒推，换文案就漏）；
 * v3 改为"码形状优先 + 上下文排除"——形状是稳定锚点，上下文只做排除不做事前要求，
 * 从根上解决"新品牌/新措辞/新码形"漏抓问题。
 *
 * 提取策略（按优先级，全部命中后合并去重）：
 *  A. 关键词锚定（最准）：取件码/取货码/提取码/自提码/凭码/码后 0-3 个标点/引号/空格 + 码簇（含多码）
 *  B. 凭/出示/输入 后跟码（允许引号、书名号、冒号等标点包裹）
 *  C. 全文候选扫描：所有形状合法的 token，按上下文评分（加分：靠近特征词/句内有取件动作词；
 *     减分：时间形(23:59)、地址门牌形(132号/31号楼)、手机号形、单号形(顺丰*72778包裹处已知排除)）
 *
 * 码形状：横线簇 \d{1,6}(-\d{1,4}){1,3}、纯数字 4-9 位（可带 1 位字母前缀）、
 *         字母数字混合 ≥4 位（如 A88123）。全由 validCode 校验。
 */
public class PickupExtractor {

    private static final String DASH = "\\d{1,6}(?:-\\d{1,6}){1,3}";
    private static final String ALNUM = "[A-Za-z]?\\d{4,9}";

    // ---- A: 关键词锚定（码前允许引号/冒号/空格等包裹符）----
    private static final Pattern P_KEYWORD = Pattern.compile(
            "(?:取件码|取货码|提取码|自提码|取件码|凭码|动态取件码|取件号)[为是：:\\s]*"
                    + "[\"'“”「」《》【】\\[\\]\\s]*"
                    + "([A-Za-z0-9-]{3,20}(?:\\s*[,，、;；]\\s*[A-Za-z0-9-]{3,20})*)");

    // ---- B: 凭/出示 + 码（标点包裹容忍；不用"输入"——验证码短信高频误触）----
    private static final Pattern P_BY = Pattern.compile(
            "(?:凭|出示)[\\s\"'“”「」《》【】\\[\\]]*"
                    + "(" + DASH + "|" + ALNUM + ")"
                    + "(?=[\\s\"'“”「」《》【】\\[\\]，。,到去取领取票]|$)");

    // ---- C: 全文候选（形状合法的 token）----
    private static final Pattern P_ANY_TOKEN = Pattern.compile(
            "(?<![A-Za-z0-9-])(" + DASH + "|" + ALNUM + ")(?![A-Za-z0-9-])");

    // 上下文特征
    private static final Pattern P_FEATURE = Pattern.compile(
            "驿站|快递柜|丰巢|菜鸟|包裹|取件|取货|自提|到站|已到|送达|兔喜|妈妈驿站|中邮|熊猫快收|收发室|快递|代收");
    private static final Pattern P_ACTION = Pattern.compile(
            "领取|取出|凭|取件|取货|及时|尽快|速取|凭码|出示");
    // 排除形态
    private static final Pattern P_TIME = Pattern.compile("\\d{1,2}:\\d{2}");
    private static final Pattern P_ADDR = Pattern.compile("\\d+号(楼|栋|室|店|站)?$|\\d+[栋号楼]");
    private static final Pattern P_PHONE = Pattern.compile("1[3-9]\\d{9}");

    /**
     * 快速预检：含特征词或凭字，且文中存在至少一个形状合法 token。
     * v3：预检与提取同源（都会用 validCode），杜绝"预检过、提取空"的静默漏。
     */
    public static boolean lookLikePickupSms(String body) {
        if (body == null || body.isEmpty()) return false;
        if (!P_FEATURE.matcher(body).find()) return false;
        return P_ANY_TOKEN.matcher(body).find();
    }

    /**
     * 提取全部取件码（去重、保序）。
     * 返回空列表 = 判定不是取件短信（或确实无码）。
     */
    public static List<String> extract(String body) {
        Set<String> result = new LinkedHashSet<>();
        if (body == null || body.isEmpty()) return new ArrayList<>(result);

        // v3 门禁：无快递特征词的短信（验证码/银行/支付等）整体不进入提取，
        // 从根上消除"验证码 33-3-0444"类误抓
        if (!P_FEATURE.matcher(body).find()) return new ArrayList<>(result);

        // A: 关键词锚定
        Matcher a = P_KEYWORD.matcher(body);
        while (a.find()) {
            addCluster(result, a.group(1));
        }

        // B: 凭/出示/输入
        Matcher b = P_BY.matcher(body);
        while (b.find()) {
            addIfValid(result, b.group(1));
        }

        // C: 上下文评分兜底（仅当 A/B 都没抓到时启用，避免把门牌号等抓进来）
        if (result.isEmpty()) {
            List<String[]> scored = new ArrayList<>(); // [token, score]
            Matcher c = P_ANY_TOKEN.matcher(body);
            while (c.find()) {
                String tok = c.group(1);
                if (!validCode(tok) || isExcludedShape(tok, c.start(), body)) continue;
                int score = 0;
                // token 前后 60 字符窗口含特征词 +2
                String win = window(body, c.start(), c.end(), 60);
                if (P_FEATURE.matcher(win).find()) score += 2;
                if (P_ACTION.matcher(win).find()) score += 2;
                // 码簇形状（带横线）+1：真实取件码横线形居多
                if (P_DASH_LIKE.matcher(tok).matches()) score += 1;
                // 纯数字长码（≥6位）+1：门牌号通常 ≤4 位
                if (tok.matches("\\d{6,9}")) score += 1;
                if (score >= 3) scored.add(new String[]{tok, String.valueOf(score)});
            }
            // 同分按位置序，全部入选（宁多抓可删，不漏抓）
            for (String[] s : scored) result.add(s[0]);
        }

        return new ArrayList<>(result);
    }

    private static final Pattern P_DASH_LIKE = Pattern.compile(DASH);

    /** A 层码簇拆分 + 逐个校验 */
    private static void addCluster(Set<String> out, String cluster) {
        if (cluster == null) return;
        for (String tok : cluster.split("[,，、;；\\s]+")) {
            addIfValid(out, tok);
        }
    }

    private static void addIfValid(Set<String> out, String tok) {
        if (tok != null && validCode(tok)) out.add(tok);
    }

    /** 排除时间/门牌/手机号/单号形态的候选 */
    private static boolean isExcludedShape(String tok, int start, String body) {
        // 紧跟 "号" 的数字（门牌/楼栋）排除
        int after = start + tok.length();
        if (after < body.length() && body.charAt(after) == '号') return true;
        // 前面紧贴 "*"（顺丰*72778 单号形）排除
        if (start > 0 && body.charAt(start - 1) == '*') return true;
        // 时间形态（只含冒号分隔数字的上下文）
        if (after + 1 < body.length() && body.charAt(after) == ':') return true;
        // 手机号
        if (P_PHONE.matcher(tok).matches() && tok.length() == 11) return true;
        return false;
    }

    /** token 前后各 window 字符的上下文窗口 */
    private static String window(String s, int start, int end, int w) {
        int from = Math.max(0, start - w);
        int to = Math.min(s.length(), end + w);
        return s.substring(from, to);
    }

    /** 码形状校验：横线簇（1-6 位起头，1-3 段尾）或 纯数字 4-9 位（可带 1 位字母前缀） */
    static boolean validCode(String tok) {
        if (tok == null || tok.isEmpty() || tok.length() > 20) return false;
        if (P_DASH_LIKE.matcher(tok).matches()) return true;
        if (tok.matches("[A-Za-z]?\\d{4,9}")) return true;
        return false;
    }
}
