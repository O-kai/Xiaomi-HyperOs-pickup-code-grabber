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
    // v2 规则升级：码形状支持单字母前缀段（M-2-5341 / D4-7048 / S3-2043 / 109-6-4006 等）
    // 首段形态：字母-数字（M-2）｜字母+数字（D4）｜纯数字（109）
    public String dashPatternStr = "(?:[A-Za-z]-\\d{1,6}|[A-Za-z]?\\d{1,6})(?:-[A-Za-z]?\\d{1,6}){0,3}";
    public String alnumPatternStr = "[A-Za-z]?\\d{3,9}";
    public List<String> placePrefixes = new ArrayList<>();
    public List<String> placeSuffixes = new ArrayList<>();

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

    public static ExtractorRules createDefault() {
        ExtractorRules r = new ExtractorRules();
        r.version = 2;
        r.minAppVersionCode = 280;
        r.description = "内置默认提取规则集 v2（字母前缀码形 + 门面房/中心等地点后缀 + 尾号排除）";

        r.featureWords.add("驿站"); r.featureWords.add("快递柜"); r.featureWords.add("丰巢");
        r.featureWords.add("菜鸟"); r.featureWords.add("包裹"); r.featureWords.add("取件");
        r.featureWords.add("取货"); r.featureWords.add("自提"); r.featureWords.add("到站");
        r.featureWords.add("已到"); r.featureWords.add("送达"); r.featureWords.add("兔喜");
        r.featureWords.add("妈妈驿站"); r.featureWords.add("中邮"); r.featureWords.add("熊猫快收");
        r.featureWords.add("收发室"); r.featureWords.add("快递"); r.featureWords.add("代收");
        r.featureWords.add("欢猫驿站");

        r.actionWords.add("领取"); r.actionWords.add("取出"); r.actionWords.add("凭");
        r.actionWords.add("取件"); r.actionWords.add("取货"); r.actionWords.add("及时");
        r.actionWords.add("尽快"); r.actionWords.add("速取"); r.actionWords.add("凭码");
        r.actionWords.add("出示");

        r.keywordAnchors.add("取件码"); r.keywordAnchors.add("取货码"); r.keywordAnchors.add("提取码");
        r.keywordAnchors.add("自提码"); r.keywordAnchors.add("凭码"); r.keywordAnchors.add("动态取件码");
        r.keywordAnchors.add("取件号"); r.keywordAnchors.add("凭取件码");

        r.byWords.add("凭"); r.byWords.add("出示");

        r.placePrefixes.add("已到站[，,包到至\\s]*"); r.placePrefixes.add("已到达");
        r.placePrefixes.add("到达"); r.placePrefixes.add("已到"); r.placePrefixes.add("到");
        r.placePrefixes.add("在"); r.placePrefixes.add("至");

        r.placeSuffixes.add("店"); r.placeSuffixes.add("站"); r.placeSuffixes.add("柜");
        r.placeSuffixes.add("点"); r.placeSuffixes.add("自提"); r.placeSuffixes.add("快递");
        r.placeSuffixes.add("门面房"); r.placeSuffixes.add("小平房"); r.placeSuffixes.add("中心");
        r.placeSuffixes.add("驿站"); r.placeSuffixes.add("服务"); r.placeSuffixes.add("广场");

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

        // B: 凭/出示
        String byOr = String.join("|", byWords);
        String pBy = "(?:" + byOr + ")[\\s\"'“”「」《》【】\\[\\]]*(" + dash + "|" + alnum + ")(?=[\\s\"'“”「」《》【】\\[\\]，。,到去取领取票]|$)";
        patternBy = Pattern.compile(pBy);

        // C: 全文候选 token
        String pAny = "(?<![A-Za-z0-9-])(" + dash + "|" + alnum + ")(?![A-Za-z0-9-])";
        patternAnyToken = Pattern.compile(pAny);

        patternFeature = Pattern.compile(String.join("|", featureWords));
        patternAction = Pattern.compile(String.join("|", actionWords));

        patternDashShape = Pattern.compile(dash);
        patternAlnumShape = Pattern.compile("^" + alnum + "$");

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
