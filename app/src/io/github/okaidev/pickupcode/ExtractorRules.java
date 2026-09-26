package io.github.okaidev.pickupcode;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 提取规则模型（v2.8.0 规则热更新）：
 * 支持从 JSON 序列化/反序列化，并编译生成所有正则模式对象。
 */
public class ExtractorRules {

    public int version = 1;
    public int minAppVersionCode = 280;
    public String description = "内置默认规则集";

    public List<String> featureWords = new ArrayList<>();
    public List<String> actionWords = new ArrayList<>();
    public List<String> keywordAnchors = new ArrayList<>();
    public List<String> byWords = new ArrayList<>();
    // v4 码形状：横线簇【必须含至少一个横线】——杜绝裸数字被当成取件码（6折/189/1064）
    // 首段形态：字母-数字（M-2）｜字母+数字（D4）｜纯数字（109）
    public String dashPatternStr = "(?:[A-Za-z]-\\d{1,6}|[A-Za-z]?\\d{1,6})(?:-[A-Za-z]?\\d{1,6}){1,3}";
    public String alnumPatternStr = "[A-Za-z]?\\d{3,9}";
    public List<String> placePrefixes = new ArrayList<>();
    public List<String> placeSuffixes = new ArrayList<>();
    /**
     * 强负向词：命中即判「非取件短信」——但仅在【无强锚点】时生效
     * （强锚点 = 出现取件码/取货码… 或 凭/出示+合法码形）。
     * 这样「超时收0.50元3.00元封顶」（柜机超时收费说明）不会误杀真正的取件短信。
     */
    public List<String> negativeKeywords = new ArrayList<>();
    /** 排除后缀（数字后紧跟这些字 → 不是取件码）：号/栋/楼/元/折/天/小时/格… */
    public List<String> excludeTailWords = new ArrayList<>();
    /** 排除前缀（这些词后紧跟的数字 → 不是取件码）：超/满/单号/订单/运单… */
    public List<String> excludeHeadWords = new ArrayList<>();
    /** 「凭/出示+码」之后允许出现的引导字（决定 B 层是否命中，如「至同兴…取件」） */
    public List<String> byLookaheadWords = new ArrayList<>();
    /** 追加特征词（不替换内置词表，供热更新扩充新驿站品牌） */
    public List<String> extraFeatureWords = new ArrayList<>();

    // 编译后的 Pattern 缓存
    public transient Pattern patternKeyword;
    public transient Pattern patternBy;
    public transient Pattern patternAnyToken;
    public transient Pattern patternFeature;
    public transient Pattern patternAction;
    public transient Pattern patternDashShape;
    public transient Pattern patternAlnumShape;
    public transient Pattern patternPlace;
    /** v2：地点回退策略（前缀+后缀词表匹配） */
    public transient Pattern patternPlaceFallback;
    /** v3/v4：负向排除正则（无强锚点时命中即整体阻断） */
    public transient Pattern patternNegative;
    /** v4：URL 区间（其中的数字一律不是取件码） */
    public transient Pattern patternUrl;
    /** v4：掩码手机号（如 159****6739） */
    public transient Pattern patternMaskedPhone;
    /** v4：「码…到/至…取」夹取型（地点+动作双确认） */
    public transient Pattern patternCodeToTake;

    public static ExtractorRules createDefault() {
        ExtractorRules r = new ExtractorRules();
        r.version = 4;
        r.minAppVersionCode = 293;
        r.description = "内置默认提取规则集 v4（严格码形 + 正反双向闸门 + 三层判定 + 排除语义上下文）";

        // ===== 快递特征词（Tier0 门禁：短信必须命中其一才可能是取件短信）=====
        r.featureWords.add("驿站"); r.featureWords.add("快递柜"); r.featureWords.add("丰巢");
        r.featureWords.add("菜鸟"); r.featureWords.add("包裹"); r.featureWords.add("取件");
        r.featureWords.add("取货"); r.featureWords.add("自提"); r.featureWords.add("到站");
        r.featureWords.add("送达"); r.featureWords.add("兔喜");
        r.featureWords.add("妈妈驿站"); r.featureWords.add("中邮"); r.featureWords.add("熊猫快收");
        r.featureWords.add("收发室"); r.featureWords.add("快递"); r.featureWords.add("代收");
        r.featureWords.add("欢猫驿站");
        // 注：「已到」已从特征词移除——它会误命中「已到账/已到您账户」等营销短信

        // ===== 取件动作词（TierD 弱兜底评分用）=====
        r.actionWords.add("领取"); r.actionWords.add("取出"); r.actionWords.add("凭");
        r.actionWords.add("取件"); r.actionWords.add("取货"); r.actionWords.add("及时");
        r.actionWords.add("尽快"); r.actionWords.add("速取"); r.actionWords.add("凭码");
        r.actionWords.add("出示");

        // ===== TierA 强锚点（关键词后紧跟码，最高置信）=====
        r.keywordAnchors.add("取件码"); r.keywordAnchors.add("取货码"); r.keywordAnchors.add("提取码");
        r.keywordAnchors.add("自提码"); r.keywordAnchors.add("凭码"); r.keywordAnchors.add("动态取件码");
        r.keywordAnchors.add("取件号"); r.keywordAnchors.add("凭取件码");

        // ===== TierB 凭/出示 + 码 =====
        r.byWords.add("凭"); r.byWords.add("出示");
        r.byLookaheadWords.add("到"); r.byLookaheadWords.add("至"); r.byLookaheadWords.add("去");
        r.byLookaheadWords.add("取"); r.byLookaheadWords.add("领"); r.byLookaheadWords.add("票");

        // ===== 地点 =====
        r.placePrefixes.add("已到站[，,包到至\\s]*"); r.placePrefixes.add("已到达");
        r.placePrefixes.add("到达"); r.placePrefixes.add("已到"); r.placePrefixes.add("到");
        r.placePrefixes.add("在"); r.placePrefixes.add("至");

        r.placeSuffixes.add("店"); r.placeSuffixes.add("站"); r.placeSuffixes.add("柜");
        r.placeSuffixes.add("点"); r.placeSuffixes.add("自提"); r.placeSuffixes.add("快递");
        r.placeSuffixes.add("门面房"); r.placeSuffixes.add("小平房"); r.placeSuffixes.add("中心");
        r.placeSuffixes.add("驿站"); r.placeSuffixes.add("服务"); r.placeSuffixes.add("广场");
        r.placeSuffixes.add("速递"); r.placeSuffixes.add("格口"); r.placeSuffixes.add("门店");

        // ===== 强负向词（仅在无强锚点时一票否决）=====
        r.negativeKeywords.add("卡券"); r.negativeKeywords.add("话费券"); r.negativeKeywords.add("红包");
        r.negativeKeywords.add("积分"); r.negativeKeywords.add("商城"); r.negativeKeywords.add("兑换");
        r.negativeKeywords.add("充值"); r.negativeKeywords.add("话费"); r.negativeKeywords.add("流量");
        r.negativeKeywords.add("账户"); r.negativeKeywords.add("余额"); r.negativeKeywords.add("验证码");
        r.negativeKeywords.add("银行"); r.negativeKeywords.add("信用卡"); r.negativeKeywords.add("贷款");
        r.negativeKeywords.add("骑手"); r.negativeKeywords.add("外卖"); r.negativeKeywords.add("闪购");
        r.negativeKeywords.add("免单"); r.negativeKeywords.add("客服"); r.negativeKeywords.add("退订");
        r.negativeKeywords.add("实验室"); r.negativeKeywords.add("激光共聚焦"); r.negativeKeywords.add("生物");
        r.negativeKeywords.add("已超"); r.negativeKeywords.add("滞留");
        r.negativeKeywords.add("损坏"); r.negativeKeywords.add("延期"); r.negativeKeywords.add("保管费");

        // ===== 数字排除：尾缀（量词/单位/时间）=====
        r.excludeTailWords.add("号"); r.excludeTailWords.add("栋"); r.excludeTailWords.add("楼");
        r.excludeTailWords.add("室"); r.excludeTailWords.add("层"); r.excludeTailWords.add("格");
        r.excludeTailWords.add("元"); r.excludeTailWords.add("折"); r.excludeTailWords.add("天");
        r.excludeTailWords.add("小时"); r.excludeTailWords.add("分钟"); r.excludeTailWords.add("秒");
        r.excludeTailWords.add("岁"); r.excludeTailWords.add("次"); r.excludeTailWords.add("个");
        r.excludeTailWords.add("件"); r.excludeTailWords.add("单"); r.excludeTailWords.add("米");
        r.excludeTailWords.add("折券");

        // ===== 数字排除：前缀（修饰语/单号类）=====
        r.excludeHeadWords.add("超"); r.excludeHeadWords.add("满"); r.excludeHeadWords.add("过");
        r.excludeHeadWords.add("低至"); r.excludeHeadWords.add("高达"); r.excludeHeadWords.add("第");
        r.excludeHeadWords.add("共"); r.excludeHeadWords.add("计"); r.excludeHeadWords.add("约");
        r.excludeHeadWords.add("近"); r.excludeHeadWords.add("余"); r.excludeHeadWords.add("剩");
        r.excludeHeadWords.add("单号"); r.excludeHeadWords.add("订单号"); r.excludeHeadWords.add("订单");
        r.excludeHeadWords.add("运单"); r.excludeHeadWords.add("快递单"); r.excludeHeadWords.add("尾号");

        r.compile();
        return r;
    }

    public static ExtractorRules fromJson(String jsonStr) throws Exception {
        JSONObject obj = new JSONObject(jsonStr);
        ExtractorRules r = new ExtractorRules();
        r.version = obj.optInt("version", 1);
        r.minAppVersionCode = obj.optInt("minAppVersionCode", 280);
        r.description = obj.optString("description", "");

        r.featureWords = jsonArrayToList(obj.optJSONArray("featureWords"));
        r.actionWords = jsonArrayToList(obj.optJSONArray("actionWords"));
        r.keywordAnchors = jsonArrayToList(obj.optJSONArray("keywordAnchors"));
        r.byWords = jsonArrayToList(obj.optJSONArray("byWords"));

        r.dashPatternStr = obj.optString("dashPattern", r.dashPatternStr);
        r.alnumPatternStr = obj.optString("alnumPattern", r.alnumPatternStr);

        r.placePrefixes = jsonArrayToList(obj.optJSONArray("placePrefixes"));
        r.placeSuffixes = jsonArrayToList(obj.optJSONArray("placeSuffixes"));
        r.negativeKeywords = jsonArrayToList(obj.optJSONArray("negativeKeywords"));

        // ===== v4 新增字段：缺失时回落到内置默认（保证老 rules.json 也能安全加载）=====
        List<String> tail = jsonArrayToList(obj.optJSONArray("excludeTailWords"));
        r.excludeTailWords = tail.isEmpty() ? createDefault().excludeTailWords : tail;
        List<String> head = jsonArrayToList(obj.optJSONArray("excludeHeadWords"));
        r.excludeHeadWords = head.isEmpty() ? createDefault().excludeHeadWords : head;
        List<String> byLook = jsonArrayToList(obj.optJSONArray("byLookaheadWords"));
        r.byLookaheadWords = byLook.isEmpty() ? createDefault().byLookaheadWords : byLook;
        r.extraFeatureWords = jsonArrayToList(obj.optJSONArray("extraFeatureWords"));

        r.compile();
        return r;
    }

    public String toJson() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("version", version);
            obj.put("minAppVersionCode", minAppVersionCode);
            obj.put("description", description);

            obj.put("featureWords", listToJsonArray(featureWords));
            obj.put("actionWords", listToJsonArray(actionWords));
            obj.put("keywordAnchors", listToJsonArray(keywordAnchors));
            obj.put("byWords", listToJsonArray(byWords));

            obj.put("dashPattern", dashPatternStr);
            obj.put("alnumPattern", alnumPatternStr);

            obj.put("placePrefixes", listToJsonArray(placePrefixes));
            obj.put("placeSuffixes", listToJsonArray(placeSuffixes));
            obj.put("negativeKeywords", listToJsonArray(negativeKeywords));
            obj.put("excludeTailWords", listToJsonArray(excludeTailWords));
            obj.put("excludeHeadWords", listToJsonArray(excludeHeadWords));
            obj.put("byLookaheadWords", listToJsonArray(byLookaheadWords));
            obj.put("extraFeatureWords", listToJsonArray(extraFeatureWords));

            return obj.toString(2);
        } catch (Throwable t) {
            return "{}";
        }
    }

    public void compile() {
        String dash = dashPatternStr;
        String alnum = alnumPatternStr;

        // A: 关键词锚定正则
        String kwOr = String.join("|", keywordAnchors);
        String pKw = "(?:" + kwOr + ")[为是：:\\s]*[\"'“”「」《》【】\\[\\]\\s]*([A-Za-z0-9-]{3,20}(?:\\s*[,，、;；]\\s*[A-Za-z0-9-]{3,20})*)";
        patternKeyword = Pattern.compile(pKw);

        // B: 凭/出示 + 码（后接引导字由 byLookaheadWords 驱动，如「凭552577至…取件」）
        String byOr = String.join("|", byWords);
        String lookOr = byLookaheadWords == null || byLookaheadWords.isEmpty()
                ? "到去取领取票至"
                : String.join("", byLookaheadWords);
        String pBy = "(?:" + byOr + ")[\\s\"'“”「」《》【】\\[\\]]*(" + dash + "|" + alnum + ")(?=[\\s\"'“”「」《》【】\\[\\]，。,]|["
                + lookOr + "]|$)";
        patternBy = Pattern.compile(pBy);

        // C: 全文候选 token
        String pAny = "(?<![A-Za-z0-9-])(" + dash + "|" + alnum + ")(?![A-Za-z0-9-])";
        patternAnyToken = Pattern.compile(pAny);

        // 特征词 = 内置词表 + 热更新追加词
        StringBuilder feat = new StringBuilder(String.join("|", featureWords));
        if (extraFeatureWords != null && !extraFeatureWords.isEmpty()) {
            feat.append("|").append(String.join("|", extraFeatureWords));
        }
        patternFeature = Pattern.compile(feat.toString());
        patternAction = Pattern.compile(String.join("|", actionWords));

        patternDashShape = Pattern.compile(dash);
        patternAlnumShape = Pattern.compile("^" + alnum + "$");

        // v4：URL 区间 / 掩码手机号 / 「码…到…取」夹取型
        patternUrl = Pattern.compile("https?://\\S+");
        patternMaskedPhone = Pattern.compile("1[3-9]\\d\\*{2,}\\d{2,4}");
        patternCodeToTake = Pattern.compile(
                "(?<![A-Za-z0-9-])(" + dash + "|" + alnum + ")(?![A-Za-z0-9-])[^，。；]{0,4}?(?:到|至)[^，。；]{2,30}?取");

        // 地点提取正则（v2 双策略）：
        // 策略1（首选，来自用户洞察）："到/至……取" 动词夹取——地点天然被两个动词包住，
        //   不依赖结尾词表，任意结尾（门面房/小平房/文化广场东侧…）都能吃进来；
        // 策略2（回退）：旧"前缀+后缀词"匹配，策略1 未命中时兜底。
        String preOr = String.join("|", placePrefixes);
        String sufOr = String.join("|", placeSuffixes);
        String pPlaceSqueeze = "(?:已到达|到达|已到|到|至)([\\u4e00-\\u9fa5A-Za-z0-9]{2,30}?)(?=取|领取|取件|取货|，|。|,|$)";
        String pPlaceSuffix = "(?:" + preOr + ")([\\u4e00-\\u9fa5A-Za-z0-9]{2,30}?(?:" + sufOr + "))";
        patternPlace = Pattern.compile(pPlaceSqueeze);
        patternPlaceFallback = Pattern.compile(pPlaceSuffix);

        if (negativeKeywords != null && !negativeKeywords.isEmpty()) {
            patternNegative = Pattern.compile(String.join("|", negativeKeywords));
        } else {
            patternNegative = null;
        }
    }

    private static List<String> jsonArrayToList(JSONArray arr) {
        List<String> list = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i);
                if (s != null && !s.isEmpty()) list.add(s);
            }
        }
        return list;
    }

    private static JSONArray listToJsonArray(List<String> list) {
        JSONArray arr = new JSONArray();
        if (list != null) {
            for (String s : list) arr.put(s);
        }
        return arr;
    }
}
