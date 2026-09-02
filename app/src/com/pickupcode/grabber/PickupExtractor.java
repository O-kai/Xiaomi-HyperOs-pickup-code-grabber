package com.pickupcode.grabber;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 取件码提取引擎（v2 定稿版）
 *
 * 规则优先级：
 *  1. 关键词锚定（取件码/提取码/自提码[为是：:\s]*）+ 多码簇（逗号/顿号/空格分隔）
 *  2. "凭XXX(到|去|取|领)" 无关键词模式（样例1）
 *  3. 快递特征词句内独立横线码（无关键词兜底）
 * 码形状校验：横线 \d{1,6}(-\d{1,4}){1,2} 或 字母数字 [A-Za-z]?\d{4,9}
 */
public class PickupExtractor {

    private static final String CODE_TOKEN = "[A-Za-z0-9-]{3,20}";
    private static final String DASH = "\\d{1,6}(?:-\\d{1,4}){1,2}";
    private static final String ALNUM = "[A-Za-z]?\\d{4,9}";

    // 规则1：关键词锚定（支持"取件码为16-4-9626, 15-3-2194, 16-3-0906"这种多码）
    private static final Pattern P_KEYWORD_MULTI = Pattern.compile(
            "(?:取件码|提取码|自提码|取货码)[为是：:\\s]*([A-Za-z0-9-]{3,20}"
                    + "(?:\\s*[,，、;；]\\s*[A-Za-z0-9-]{3,20})*)");
    // 规则2：凭…到/去/取/领
    private static final Pattern P_BY = Pattern.compile(
            "凭\\s*(" + DASH + "|" + ALNUM + ")(?=到|去|取|领|[，。,\\s]|$)");
    // 规则3：快递特征词句内横线码（无关键词兜底）
    private static final Pattern P_BARE = Pattern.compile(
            "(?:驿站|快递柜|丰巢|菜鸟|包裹到|已到站|待取件|请及时取|取件)[^。\\n]{0,30}?(" + DASH + ")");

    private static final Pattern P_DASH_SHAPE = Pattern.compile(DASH);
    private static final Pattern P_ALNUM_SHAPE = Pattern.compile("^" + ALNUM + "$");

    /**
     * 快速预检（疑似筛选，非最终判定）
     */
    public static boolean lookLikePickupSms(String body) {
        if (body == null || body.isEmpty()) return false;
        return P_KEYWORD_MULTI.matcher(body).find()
                || P_BY.matcher(body).find()
                || (body.contains("驿站") || body.contains("快递柜") || body.contains("丰巢")
                || body.contains("菜鸟") || body.contains("包裹") || body.contains("取件"))
                && P_BARE.matcher(body).find();
    }

    /**
     * 提取全部取件码（去重，保持出现顺序）
     */
    public static List<String> extract(String body) {
        Set<String> result = new LinkedHashSet<>();
        if (body == null || body.isEmpty()) return new ArrayList<>(result);

        // 层1：关键词锚定（多码簇）
        Matcher m1 = P_KEYWORD_MULTI.matcher(body);
        while (m1.find()) {
            String cluster = m1.group(1);
            String[] tokens = cluster.split("[,，、;；\\s]+");
            for (String tok : tokens) {
                if (validCode(tok)) result.add(tok);
            }
        }

        // 层2：凭…（每条短信通常一个）
        Matcher m2 = P_BY.matcher(body);
        while (m2.find()) {
            String tok = m2.group(1);
            if (validCode(tok)) result.add(tok);
        }

        // 层3：特征词内横线码
        Matcher m3 = P_BARE.matcher(body);
        while (m3.find()) {
            String tok = m3.group(1);
            if (validCode(tok)) result.add(tok);
        }

        return new ArrayList<>(result);
    }

    private static boolean validCode(String tok) {
        if (tok == null || tok.isEmpty() || tok.length() > 20) return false;
        if (P_DASH_SHAPE.matcher(tok).matches()) return true;
        if (P_ALNUM_SHAPE.matcher(tok).matches()) return true;
        return false;
    }
}
