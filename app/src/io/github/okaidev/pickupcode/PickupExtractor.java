package io.github.okaidev.pickupcode;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 取件码提取引擎 v4（v2.8.0 规则热更新架构）
 *
 * 核心设计：
 *  - 引擎架构（多层判定、评分、上下文排除）保留在代码中；
 *  - 规则数据（关键词表、特征词、形状正则、地点前后缀）由 ExtractorRules 承载；
 *  - 支持热更新运行时原子替换（volatile rules）；
 *  - 启动时自动从本地存储或 assets 加载最新生效规则；
 *  - 内置冒烟沙箱测试集（10 条黄金测试例），新规则热拉取后必须全量通过冒烟测试，
 *    否则一票否决拒绝加载并自动回退到内置安全规则。
 */
public class PickupExtractor {

    private static final String TAG = "PICKUPDEBUG";
    private static final String RULES_FILE_NAME = "rules_active.json";

    // 当前活跃规则对象（原子替换）
    private static volatile ExtractorRules activeRules = ExtractorRules.createDefault();

    // 排除形态固定正则
    private static final Pattern P_TIME = Pattern.compile("\\d{1,2}:\\d{2}");
    private static final Pattern P_PHONE = Pattern.compile("1[3-9]\\d{9}");

    // 内置冒烟沙箱样本（用于在热更新应用前在端侧本地回归）
    private static final String[][] SMOKE_TEST_CASES = {
            {"【菜鸟驿站】您有3个包裹到站，取件码为16-4-9626, 15-3-2194, 16-3-0906，凭码取件", "16-4-9626;15-3-2194;16-3-0906"},
            {"【丰巢】凭22-2-3579到华润万家旁丰巢柜取件", "22-2-3579"},
            {"【妈妈驿站】取货码2-2-7508，您有极兔快递包裹，已到新创村沙咀里132号", "2-2-7508"},
            {"【兔喜生活】您有包裹已到达兴龙湾30栋兔喜店，取件码为2-3-05025，地址:兴龙湾30号楼102", "2-3-05025"},
            {"【菜鸟驿站】请23:59前到站凭\"72778\"到佛山桂城南约六区185号店站点领取您的顺丰*72778包裹", "72778"},
            {"【中邮驿站】您的邮政包裹已到，取件码A88123，请及时领取", "A88123"},
            {"【妈妈驿站】取货码5-5-9-13，您有包裹YT764306505052已到苍山下坡圆通快递", "5-5-9-13"},
            {"【申通快递】请凭9-7-0993到苍山老菜场斜对面申通快递取您的快递，详询代收驿站", "9-7-0993"},
            {"【银行】您的验证码 858822，请勿泄露", "-"}, // 反例：验证码不可误提取
            {"【某App】动态验证码 33-3-0444 请在5分钟内输入", "-"}, // 反例
            {"【拼多多】您的商品在代收点已超6小时未取，为避免鲜活商品损坏，请尽快取件。", "-"}, // 反例：催取通知不可误提取
            // ===== v4 新增：真实用户反馈语料（正例 + 反例），构成端侧热更安全阀 =====
            {"【圆通快递】凭1-2-21003到森泰首府快递驿站取尾号0236包裹", "1-2-21003"},
            {"【中通快递】凭9-1-21006到森泰首府快递驿站取尾号0107包裹", "9-1-21006"},
            {"【妈妈驿站】3个包裹，取货码2-5-7077，2-5-7075，2-3-4346，中峰乡信用社斜对面圆通速递妈妈驿站", "2-5-7077;2-5-7075;2-3-4346"},
            {"[兔喜生活]请凭552577至同兴冠寓3号楼1号兔喜快递柜取件，免费存24小时，超时收0.50元3.00元封顶", "552577"},
            {"【南京百米需】您的中通包裹已到x镇x村xx号菜鸟驿站3号柜284号格口，取件码4869-9777", "4869-9777"},
            {"【邮侠】凭取件码1681-5191到x镇x村xx号菜鸟驿站2号柜365号格口,询。", "1681-5191"},
            {"尊敬的客户，您在积分商城兑换的订单1064己发货，请注意查收。物流小可邮政快递包裹，单号123123123也可登录积分商城在“我的订单”处查看物流信息：http://if.189.cn/AFXQLl【中国电信积分商城】", "-"}, // 反例：物流查询非取件
            {"【中国移动卡券提醒】（卡券到账提醒）尊敬的159****6739客户，您的一张“话费券”已到账，请前往中国移动APP-我的-卡券查看使用，或戳我直达：https://dx.10086.cn/A/3Oa0Hw（拒收请回复R）", "-"}, // 反例：卡券营销
            {"【蜂鸟配送】您的订单已经送达已送达，宿舍楼下外卖架位置，淘宝闪购搜索719987，领学生专属免单卡，再搜94666还可领叠加红包，如有问题请去订单详情页联系骑手.谢谢～", "-"}, // 反例：外卖配送
            {"[e测试]双节特惠:生物SEM、激光共聚焦、Tunel荧光等低至6折券已到您账户，节前提前锁优惠，拒收请回复R", "-"}, // 反例：营销短信
    };

    /** 初始化规则引擎：优先读持久化目录最新规则，失败则回退 assets 或编译内默认 */
    public static synchronized void init(Context ctx) {
        if (ctx == null) return;
        // v3.0.0：无论官方规则加载成功与否，用户规则都必须加载（两套规则相互独立）
        try {
            loadUserRulesInternal(ctx);
        } catch (Throwable t) {
            Log.w(TAG, "init user rules err: " + t);
        }
        try {
            File localRules = new File(ctx.getFilesDir(), RULES_FILE_NAME);
            if (localRules.exists()) {
                String json = readFileToString(localRules);
                ExtractorRules candidate = ExtractorRules.fromJson(json);
                if (runSmokeTest(candidate)) {
                    activeRules = candidate;
                    Log.i(TAG, "PickupExtractor: loaded custom rules v" + candidate.version);
                    return;
                } else {
                    Log.w(TAG, "PickupExtractor: cached rules failed smoke test, fallback to default");
                    localRules.delete();
                }
            }

            // 尝试读取 assets/rules/rules.json
            try (InputStream is = ctx.getAssets().open("rules/rules.json")) {
                String json = readStreamToString(is);
                ExtractorRules candidate = ExtractorRules.fromJson(json);
                if (runSmokeTest(candidate)) {
                    activeRules = candidate;
                    Log.i(TAG, "PickupExtractor: loaded asset rules v" + candidate.version);
                    return;
                }
            } catch (Throwable ignored) { }

        } catch (Throwable t) {
            Log.e(TAG, "PickupExtractor init rules err: " + t);
        }
        activeRules = ExtractorRules.createDefault();
    }

    /**
     * 载入用户规则：App 进程读私有存储；系统进程（Hook）读 root 同步文件
     * 这是 init() 的第一件事——保证短信冷启动路径也能用上用户规则
     */
    private static void loadUserRulesInternal(Context ctx) {
        try {
            List<UserRules.Rule> rules = null;
            if (UserRules.isEnabled(ctx)) {
                rules = UserRules.loadAll(ctx);
            } else {
                // App 进程里未启用时，也尝试读同步文件（可能刚被其他进程更新过）
                rules = UserRules.loadForSystemProcess();
            }
            userRuleCache = (rules == null) ? new ArrayList<>() : rules;
            if (!userRuleCache.isEmpty()) {
                Log.i(TAG, "PickupExtractor: loaded " + userRuleCache.size() + " user rule(s)");
            }
        } catch (Throwable t) {
            userRuleCache = new ArrayList<>();
            Log.w(TAG, "loadUserRules failed: " + t);
        }
    }

    /** 热更新接入点：验证新规则，通过冒烟测试后原子生效并持久化 */
    public static synchronized boolean applyNewRules(Context ctx, String jsonStr) {
        try {
            ExtractorRules candidate = ExtractorRules.fromJson(jsonStr);
            if (!runSmokeTest(candidate)) {
                Log.w(TAG, "applyNewRules: smoke test failed for candidate v" + candidate.version);
                return false;
            }

            // 冒烟测试全过，写入私有文件
            if (ctx != null) {
                File localRules = new File(ctx.getFilesDir(), RULES_FILE_NAME);
                try (FileOutputStream fos = new FileOutputStream(localRules)) {
                    fos.write(jsonStr.getBytes(StandardCharsets.UTF_8));
                }
            }

            // 运行时原子替换
            activeRules = candidate;
            Log.i(TAG, "applyNewRules: successfully applied and persisted rules v" + candidate.version);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "applyNewRules failed: " + t);
            return false;
        }
    }

    /** 获取当前生效规则版本号 */
    public static int getActiveRulesVersion() {
        return activeRules != null ? activeRules.version : 1;
    }

    /** 获取当前生效规则摘要描述 */
    public static String getActiveRulesDescription() {
        return activeRules != null ? activeRules.description : "默认内置规则";
    }

    /** 本地端侧沙箱冒烟门禁：对候选规则执行严苛回归检验 */
    public static boolean runSmokeTest(ExtractorRules rules) {
        if (rules == null) return false;
        try {
            for (String[] tc : SMOKE_TEST_CASES) {
                String body = tc[0];
                String expected = tc[1];

                List<String> actual = extractWithRules(rules, body);
                if ("-".equals(expected)) {
                    if (!actual.isEmpty()) return false; // 出现误报，阻断
                } else {
                    List<String> expList = Arrays.asList(expected.split(";"));
                    if (!actual.equals(expList)) return false; // 提取结果不完全匹配，阻断
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 快速预检：含特征词或凭字，且文中存在至少一个形状合法 token
     *
     * 【v3.0.0】若「用户规则」能直接命中（发送方匹配 + 正则能提取），则同样放行——
     * 因为用户写这条规则的目的就是覆盖官方特征词覆盖不到的场景
     * （例：学校自提柜短信写「物品编号」，官方词表里根本没有「校园/物品编号」）。
     * 若没有这一步，用户规则会被前置门禁拦死，功能形同虚设。
     */
    public static boolean lookLikePickupSms(String body) {
        return lookLikePickupSms(null, body);
    }

    /** 【推荐】显式带发送方版本：避免并发场景下号码与短信错配 */
    public static boolean lookLikePickupSms(String sender, String body) {
        if (body == null || body.isEmpty()) return false;
        // 用户规则优先：它命中就放行（由 extract() 再做一次实际提取确认）
        if (UserRules.tryMatch(userRuleCache, sender, body) != null) return true;
        ExtractorRules r = activeRules;
        if (r == null || r.patternFeature == null || r.patternAnyToken == null) return false;
        if (!r.patternFeature.matcher(body).find()) return false;
        return r.patternAnyToken.matcher(body).find();
    }

    /**
     * v2.8.0：仅特征词门禁（错题本捕获用——含快递特征词即有漏抓嫌疑）
     *
     * 【v3.0.0】用户规则命中时同样返回 true：
     * 用户写规则的目的正是覆盖官方词表之外的说法（学校「物品编号」、
     * 物业「存取码」等），若这里不放行，用户规则会被前置门禁拦死而形同虚设。
     */
    public static boolean hasFeatureWords(String body) {
        return hasFeatureWords(null, body);
    }

    /** 【推荐】显式带发送方版本：避免并发场景下号码与短信错配 */
    public static boolean hasFeatureWords(String sender, String body) {
        if (body == null || body.isEmpty()) return false;
        if (UserRules.tryMatch(userRuleCache, sender, body) != null) return true;
        ExtractorRules r = activeRules;
        return r != null && r.patternFeature != null && r.patternFeature.matcher(body).find();
    }

    /** 提取全部取件码（使用当前活跃规则，无发送方上下文） */
    public static List<String> extract(String body) {
        return extractWithRules(activeRules, null, body);
    }

    /** 【推荐】提取全部取件码，显式携带发送方供用户规则做号码匹配 */
    public static List<String> extract(String sender, String body) {
        return extractWithRules(activeRules, sender, body);
    }

    /**
     * 提取地点（v4 三策略）：
     *  策略1：动词夹取——"到/至……取/领取"之间即地点（任意结尾都能匹配）
     *  策略2：前缀+后缀词表回退
     *  策略3（v4 新增）：码簇尾段——"取货码2-5-7077，2-3-4346，中峰乡…妈妈驿站"
     *          最后一个码之后的文本若含地点特征词，即为地点（无"到"字句的批量码场景）
     *  均未命中 → "—"
     */
    public static String extractPlace(String body) {
        return extractPlace(null, body);
    }

    /** 【推荐】显式带发送方版本：地点若由用户规则指定，需用同一套号码匹配判定 */
    public static String extractPlace(String sender, String body) {
        if (body == null || body.isEmpty()) return "—";
        ExtractorRules r = activeRules;
        if (r == null) return "—";

        // v3.0.0：用户规则若指定了地点，优先采用
        String[] userHit = UserRules.tryMatch(userRuleCache, sender, body);
        if (userHit != null && userHit[2] != null && !userHit[2].trim().isEmpty()) {
            return userHit[2].trim();
        }

        // 策略1：夹取
        if (r.patternPlace != null) {
            Matcher m = r.patternPlace.matcher(body);
            if (m.find() && m.group(1) != null && m.group(1).length() >= 2) {
                return m.group(1);
            }
        }
        // 策略2：后缀词表
        if (r.patternPlaceFallback != null) {
            Matcher m2 = r.patternPlaceFallback.matcher(body);
            if (m2.find() && m2.group(1) != null) {
                return m2.group(1);
            }
        }
        // 策略3：码簇尾段（找最后一个合法码，其后文本含地点特征词则取之）
        if (r.patternAnyToken != null) {
            Matcher m3 = r.patternAnyToken.matcher(body);
            int lastEnd = -1;
            while (m3.find()) {
                if (validCode(r, m3.group(1))) lastEnd = m3.end();
            }
            if (lastEnd > 0 && lastEnd < body.length()) {
                String tail = body.substring(lastEnd).replaceFirst("^[，,。；;、\\s]+", "").trim();
                if (tail.length() >= 2 && tail.length() <= 40 && looksLikePlace(r, tail)) {
                    return tail;
                }
            }
        }
        return "—";
    }

    /** 尾段是否像地点：含地点特征后缀词，且不含明显非地点噪声 */
    private static boolean looksLikePlace(ExtractorRules r, String tail) {
        if (r == null || r.placeSuffixes == null) return false;
        for (String suf : r.placeSuffixes) {
            if (!suf.isEmpty() && tail.contains(suf)) {
                // 排除含营销/催单噪声的尾段
                if (tail.contains("红包") || tail.contains("免单") || tail.contains("券")
                        || tail.contains("详情页") || tail.contains("客服")) {
                    return false;
                }
                return true;
            }
        }
        return false;
    }

    /**
     * 核心提取算法 v4：四层判定 + 严格语义排除
     *
     * 【v3.0.0 优先级】用户自定义规则 > 官方 rules.json > 编译内置默认
     *   用户规则在 extract() / extractPlace() 入口处优先匹配，
     *   命中即返回用户结果（不再走默认四层）——用户写了规则就是要覆盖默认行为。
     *
     * Tier0 门禁：必须命中快递特征词；且若命中强负向词（卡券/红包/骑手/已超…）则一票否决
     * TierA 强锚点：取件码/取货码/凭取件码… 紧跟码（含多码簇）→ 最高置信
     * TierB 凭/出示 + 码
     * TierC 夹取型：码…到/至…取（码与取件动作同时确认）
     * TierD 弱兜底：严格评分（窗口收紧至 24 字，需 特征词+动作词 共现，分数≥4）
     *
     * 贯穿全程的语义排除：URL 内数字 / 掩码手机号 / 单号订单号 / 量词单位 / 时间 / 尾号
     */
    public static List<String> extractWithRules(ExtractorRules r, String body) {
        return extractWithRules(r, null, body);
    }

    /**
     * 【推荐】显式携带发送方：避免并发时号码与短信错配
     * （system_server 通知回调是多线程的，任何跨线程共享的 sender 都不可靠）
     */
    public static List<String> extractWithRules(ExtractorRules r, String sender, String body) {
        Set<String> result = new LinkedHashSet<>();
        if (body == null || body.isEmpty() || r == null) return new ArrayList<>(result);

        // ===== v3.0.0：用户自定义规则优先（仅提取层，权限边界见 UserRules 类头注释）=====
        // v3.0.1：tryMatch 返回的 [0] 可能是多个码（";" 分隔），这里逐个加入 → 每个码写一条待办
        String[] userHit = UserRules.tryMatch(userRuleCache, sender, body);
        if (userHit != null && userHit[0] != null && !userHit[0].trim().isEmpty()) {
            for (String c : userHit[0].split(";")) {
                String t = c.trim();
                if (!t.isEmpty()) result.add(t);
            }
            if (!result.isEmpty()) return new ArrayList<>(result);
        }

        // ===== Tier0-A：快递特征门禁 =====
        if (r.patternFeature == null || !r.patternFeature.matcher(body).find()) {
            return new ArrayList<>(result);
        }

        // ===== TierA：关键词锚定（取件码/取货码…）=====
        if (r.patternKeyword != null) {
            Matcher a = r.patternKeyword.matcher(body);
            while (a.find()) {
                addCluster(result, r, a.group(1));
            }
        }

        // ===== TierB：凭/出示 + 码 =====
        if (r.patternBy != null) {
            Matcher b = r.patternBy.matcher(body);
            while (b.find()) {
                addIfValid(result, r, b.group(1));
            }
        }

        // ===== TierC：夹取型（码…到/至…取）=====
        if (result.isEmpty() && r.patternCodeToTake != null) {
            Matcher c3 = r.patternCodeToTake.matcher(body);
            while (c3.find()) {
                addIfValid(result, r, c3.group(1));
            }
        }

        // ===== TierD：弱兜底（严格评分）=====
        if (result.isEmpty() && r.patternAnyToken != null) {
            // 强负向闸门：仅在无强锚点时生效
            // （有 TierA/B 命中时不做负向否决——「超时收0.50元」等柜机说明不应误杀真取件短信）
            if (r.patternNegative != null && r.patternNegative.matcher(body).find()) {
                return new ArrayList<>(result);
            }
            List<String[]> scored = new ArrayList<>();
            Matcher c = r.patternAnyToken.matcher(body);
            while (c.find()) {
                String tok = c.group(1);
                if (!validCode(r, tok)) continue;
                if (isExcludedToken(r, tok, c.start(), c.end(), body)) continue;
                int score = 0;
                // 窗口从 60 收紧到 24：避免跨句误关联
                String win = window(body, c.start(), c.end(), 24);
                if (r.patternFeature != null && r.patternFeature.matcher(win).find()) score += 2;
                if (r.patternAction != null && r.patternAction.matcher(win).find()) score += 2;
                if (r.patternDashShape != null && r.patternDashShape.matcher(tok).matches()) score += 1;
                if (tok.matches("\\d{6,9}")) score += 1;
                // 阈值 3→4：必须有「特征词 + 动作词」共现，或特征+横线码+长数字
                if (score >= 4) scored.add(new String[]{tok, String.valueOf(score)});
            }
            for (String[] s : scored) result.add(s[0]);
        }

        return new ArrayList<>(result);
    }

    private static void addCluster(Set<String> out, ExtractorRules r, String cluster) {
        if (cluster == null) return;
        for (String tok : cluster.split("[,，、;；\\s]+")) {
            addIfValid(out, r, tok);
        }
    }

    private static void addIfValid(Set<String> out, ExtractorRules r, String tok) {
        if (tok != null && validCode(r, tok)) out.add(tok);
    }

    /**
     * v4 语义排除：判断一个「形状合法」的 token 是否其实是别的数字
     * （URL 片段 / 掩码手机号 / 订单号单号 / 量词单位 / 时间 / 尾号 / 手机号）
     * 规则词表由 rules.json 驱动，可热更新扩充
     */
    private static boolean isExcludedToken(ExtractorRules r, String tok, int start, int end, String body) {
        // 1) URL 区间内的数字一律排除（http://if.189.cn → 189 不是取件码）
        if (r != null && r.patternUrl != null) {
            Matcher um = r.patternUrl.matcher(body);
            while (um.find()) {
                if (start >= um.start() && start < um.end()) return true;
            }
        }
        // 2) 掩码手机号附近（159****6739 → 159 / 6739 都不是）
        if (r != null && r.patternMaskedPhone != null) {
            Matcher pm = r.patternMaskedPhone.matcher(body);
            while (pm.find()) {
                if (start >= pm.start() - 2 && start <= pm.end() + 2) return true;
            }
        }
        // 3) 后接量词/单位/时间（6折/132号/284号格口/3天/0.50元）
        String tail = body.substring(end, Math.min(body.length(), end + 4));
        if (r != null && r.excludeTailWords != null) {
            for (String w : r.excludeTailWords) {
                if (!w.isEmpty() && tail.startsWith(w)) return true;
            }
        }
        // 4) 前接修饰语/单号类（已超6/低至6折/单号123123123/订单1064）
        String head = body.substring(Math.max(0, start - 6), start);
        if (r != null && r.excludeHeadWords != null) {
            for (String w : r.excludeHeadWords) {
                if (!w.isEmpty() && head.endsWith(w)) return true;
            }
        }
        // 5) 时间形（23:59）
        if (end < body.length() && body.charAt(end) == ':') return true;
        // 6) 完整手机号
        if (P_PHONE.matcher(tok).matches() && tok.length() == 11) return true;
        // 7) 紧邻 * 号（顺丰*72778）
        if (start > 0 && body.charAt(start - 1) == '*') return true;
        return false;
    }

    /**
     * 码形合法性校验：token 是否符合当前规则集定义的取件码形状
     * 横线簇走 dashPattern（含字母段 M-2 / D4-7048 等），纯码走 alnumPattern
     */
    public static boolean validCode(ExtractorRules r, String tok) {
        if (tok == null || tok.isEmpty() || tok.length() > 20 || r == null) return false;
        if (r.patternDashShape != null && r.patternDashShape.matcher(tok).matches()) return true;
        if (r.patternAlnumShape != null && r.patternAlnumShape.matcher(tok).matches()) return true;
        return false;
    }

    private static String window(String s, int start, int end, int w) {
        int from = Math.max(0, start - w);
        int to = Math.min(s.length(), end + w);
        return s.substring(from, to);
    }

    /** 当前生效规则集（供外部查询当前规则版本/内容） */
    public static ExtractorRules activeRules() { return activeRules; }

    /** v3.0.0：用户自定义规则缓存（init 时从 App 私有存储或系统进程同步文件加载） */
    private static volatile java.util.List<UserRules.Rule> userRuleCache = new ArrayList<>();

    /**
     * 载入用户规则缓存
     * App 进程传私有存储读出的规则；系统进程（NotiHook / SystemDirectWriter）
     * 传从 /data/local/tmp 同步文件读出的规则。
     */
    public static void loadUserRules(Context ctx, java.util.List<UserRules.Rule> rules) {
        userRuleCache = (rules == null) ? new ArrayList<>() : rules;
    }

    /** 已载入的用户规则数量（供诊断/日志展示） */
    public static int userRuleCount() { return userRuleCache == null ? 0 : userRuleCache.size(); }

    /**
     * v3.0.1：本次提取是否命中了「用户自定义规则」（而非官方规则集）
     * 命中时可用 getLastHitUserRuleName() 取到具体规则名，用于向用户明示"用了哪条规则"
     */
    public static boolean hitUserRule() { return UserRules.lastHitRuleName() != null; }

    /** v3.0.1：最近命中的用户规则名（null 表示走的是官方规则） */
    public static String getLastHitUserRuleName() { return UserRules.lastHitRuleName(); }

    private static String readFileToString(File f) throws Exception {
        try (FileInputStream fis = new FileInputStream(f)) {
            return readStreamToString(fis);
        }
    }

    private static String readStreamToString(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[2048];
        int n;
        while ((n = is.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
