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
    /**
     * 默认码形正则（v4.0 起由构建脚本从 rules/rules.json 生成）。
     *
     * <p>一段 = 可带 1-2 字母前缀的数字字段（CC3 / M-2 / 1023），也允许纯字母段（a）。
     * 纯字母段只能出现在中间，且后面必须跟数字段——这样 e-mail、订单号一类的文本
     * 不会因为「字母-字母」被当成取件码（回归用例 036-038 锁住这一点）。
     */
    public static final String DEFAULT_DASH_PATTERN = GeneratedRules.DASH_PATTERN;
    /** 默认「字母+数字」码形（无横线簇时的兜底），同样来自 rules/rules.json。 */
    public static final String DEFAULT_ALNUM_PATTERN = GeneratedRules.ALNUM_PATTERN;

    public String dashPatternStr = DEFAULT_DASH_PATTERN;
    public String alnumPatternStr = DEFAULT_ALNUM_PATTERN;
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
    /**
     * 可分隔的尾词短语：码与它之间允许有空格/逗号等分隔符。
     *
     * <p>v4.0 v8 规则集新增。原先 excludeTailWords 要求紧邻（码后必须直接跟该词），
     * 于是「取件码 33-2-1188 暂未生成」这种带空格的写法漏过闸门——
     * 而这条短信里的码其实还没生效，抓进去只会给用户添一条假待办。
     * 这类语义词单独成表，避免把既有的「紧邻尾词」语义改宽。
     */
    public List<String> excludeTailPhrases = new ArrayList<>();
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

    /**
     * 内置默认规则集。
     *
     * <p>v4.0 起：默认规则的唯一事实源是 <code>rules/rules.json</code>，
     * 构建时由 <code>generateRules</code> 任务生成 <code>GeneratedRules</code>。
     * 本类不再手工维护词表副本——之前手工写死的 createDefault() 是三副本中最容易漏改的一处，
     * 漏改后系统进程（system_server / telephony）会一直跑旧规则，而界面完全看不出异常。
     *
     * <p>这里不经过 JSON 解析：JVM 单元测试用的 android.jar 里 org.json 只是空壳，
     * 内置默认走 JSON 会让离线回归直接失效。
     */
    public static ExtractorRules createDefault() {
        ExtractorRules r = new ExtractorRules();
        r.version = GeneratedRules.VERSION;
        r.minAppVersionCode = GeneratedRules.MIN_APP_VERSION_CODE;
        r.description = GeneratedRules.DESCRIPTION;
        r.dashPatternStr = GeneratedRules.DASH_PATTERN;
        r.alnumPatternStr = GeneratedRules.ALNUM_PATTERN;
        r.featureWords = asList(GeneratedRules.FEATURE_WORDS);
        r.actionWords = asList(GeneratedRules.ACTION_WORDS);
        r.keywordAnchors = asList(GeneratedRules.KEYWORD_ANCHORS);
        r.byWords = asList(GeneratedRules.BY_WORDS);
        r.byLookaheadWords = asList(GeneratedRules.BY_LOOKAHEAD_WORDS);
        r.placePrefixes = asList(GeneratedRules.PLACE_PREFIXES);
        r.placeSuffixes = asList(GeneratedRules.PLACE_SUFFIXES);
        r.negativeKeywords = asList(GeneratedRules.NEGATIVE_KEYWORDS);
        r.excludeTailWords = asList(GeneratedRules.EXCLUDE_TAIL_WORDS);
        r.excludeTailPhrases = asList(GeneratedRules.EXCLUDE_TAIL_PHRASES);
        r.excludeHeadWords = asList(GeneratedRules.EXCLUDE_HEAD_WORDS);
        r.extraFeatureWords = asList(GeneratedRules.EXTRA_FEATURE_WORDS);
        r.compile();
        return r;
    }

    /** 数组常量 -> 可变列表（词表在 compile() 后不再改动） */
    private static List<String> asList(String[] arr) {
        List<String> list = new ArrayList<>();
        if (arr != null) {
            for (String s : arr) {
                if (s != null && !s.isEmpty()) list.add(s);
            }
        }
        return list;
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

        // v3.1.0：三个「不能为空」的词表在此做兜底，与下面 excludeTail/Head 的处理方式一致。
        // compile() 里已改为对空表抛异常（防止规则损坏后门禁失效），
        // 这里先回落到内置默认，避免一个手滑的空数组就让整份热更规则被拒。
        ExtractorRules fallback = createDefault();
        if (r.featureWords == null || r.featureWords.isEmpty()) {
            r.featureWords = fallback.featureWords;
        }
        if (r.placeSuffixes == null || r.placeSuffixes.isEmpty()) {
            r.placeSuffixes = fallback.placeSuffixes;
        }
        if (r.placePrefixes == null || r.placePrefixes.isEmpty()) {
            r.placePrefixes = fallback.placePrefixes;
        }

        // ===== v4 新增字段：缺失时回落到内置默认（保证老 rules.json 也能安全加载）=====
        List<String> tail = jsonArrayToList(obj.optJSONArray("excludeTailWords"));
        r.excludeTailWords = tail.isEmpty() ? fallback.excludeTailWords : tail;
        List<String> tailPhrases = jsonArrayToList(obj.optJSONArray("excludeTailPhrases"));
        r.excludeTailPhrases = tailPhrases.isEmpty() ? fallback.excludeTailPhrases : tailPhrases;
        List<String> head = jsonArrayToList(obj.optJSONArray("excludeHeadWords"));
        r.excludeHeadWords = head.isEmpty() ? fallback.excludeHeadWords : head;
        List<String> byLook = jsonArrayToList(obj.optJSONArray("byLookaheadWords"));
        r.byLookaheadWords = byLook.isEmpty() ? fallback.byLookaheadWords : byLook;
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
            obj.put("excludeTailPhrases", listToJsonArray(excludeTailPhrases));
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
        // v6.1：锚点与码之间的分隔符补上「-」。
        // 实测【包裹待签收【取件码-6-4-3009】…】——横线直接连在「取件码」后面，
        // 原分隔符类不含「-」，于是把横线一起抓进捕获组得到 "-6-4-3009"，
        // 形状校验判为非法而丢弃，整条短信静默漏抓。
        String kwOr = String.join("|", keywordAnchors);
        String pKw = "(?:" + kwOr + ")[为是：:\\s]*[\"'“”「」《》【】\\[\\]\\s\\-—–]*"
                + "([A-Za-z0-9-]{3,20}(?:\\s*[,，、;；]\\s*[A-Za-z0-9-]{3,20})*)";
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
        // v6.1：前向断言原本是 (?<![A-Za-z0-9-])，把「取件码-6-4-3009」里的 6-4-3009
        // 也排除了——因为它前面紧邻分隔用的横线。现改为「前面不能是字母数字」，
        // 横线只作为码内分隔符看待；后向保持原样（后面不能再接码字符）。
        // 安全性：URL/单号/手机号等仍由 isExcludedToken 做语义排除。
        String pAny = "(?<![A-Za-z0-9])(" + dash + "|" + alnum + ")(?![A-Za-z0-9-])";
        patternAnyToken = Pattern.compile(pAny);

        // 特征词 = 内置词表 + 热更新追加词
        StringBuilder feat = new StringBuilder(String.join("|", featureWords));
        if (extraFeatureWords != null && !extraFeatureWords.isEmpty()) {
            feat.append("|").append(String.join("|", extraFeatureWords));
        }
        // v3.1.0：特征词表为空时不能放行。
        // Pattern.compile("") 会变成「匹配一切」，Tier0 门禁就此消失——
        // 实测此时「您的收货码为654321」「您的停车码为223344」「缴费凭证号882311」
        // 这类完全无关的短信都会被提出来，且 hasFeatureWords 对每条短信都返回 true，
        // 把错题本灌满无关短信。更糟的是冒烟测试只跑 extractWithRules、不测门禁语义，
        // 实测 runSmokeTest 在这种情况下仍返回 true（拦不住）。
        // 因此这里直接抛异常：fromJson 会失败 → 调用方回退到内置默认规则（安全方向）。
        if (feat.length() == 0) {
            throw new IllegalArgumentException("featureWords 与 extraFeatureWords 不能同时为空"
                    + "（否则 Tier0 特征门禁会失效，退化成匹配一切）");
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
        // v3.1.0：前后缀词表任一为空时，策略2 会退化成 (?:)(...)(?:)，
        // 即「匹配任意 2~30 个汉字」——短信里随便一段中文都会被当成地点。
        // 与 featureWords 同理，这里直接拒绝加载，让规则回落到内置默认。
        if (placeSuffixes == null || placeSuffixes.isEmpty()) {
            throw new IllegalArgumentException("placeSuffixes 不能为空"
                    + "（否则地点回退策略会退化成匹配任意中文）");
        }
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
