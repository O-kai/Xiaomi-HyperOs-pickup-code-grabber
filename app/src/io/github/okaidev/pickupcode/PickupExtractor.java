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
            {"【某App】动态验证码 33-3-0444 请在5分钟内输入", "-"} // 反例
    };

    /** 初始化规则引擎：优先读持久化目录最新规则，失败则回退 assets 或编译内默认 */
    public static synchronized void init(Context ctx) {
        if (ctx == null) return;
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

    /** 快速预检：含特征词或凭字，且文中存在至少一个形状合法 token */
    public static boolean lookLikePickupSms(String body) {
        if (body == null || body.isEmpty()) return false;
        ExtractorRules r = activeRules;
        if (r == null || r.patternFeature == null || r.patternAnyToken == null) return false;
        if (!r.patternFeature.matcher(body).find()) return false;
        return r.patternAnyToken.matcher(body).find();
    }

    /** v2.8.0：仅特征词门禁（错题本捕获用——含快递特征词即有漏抓嫌疑） */
    public static boolean hasFeatureWords(String body) {
        if (body == null || body.isEmpty()) return false;
        ExtractorRules r = activeRules;
        return r != null && r.patternFeature != null && r.patternFeature.matcher(body).find();
    }

    /** 提取全部取件码（使用当前活跃规则） */
    public static List<String> extract(String body) {
        return extractWithRules(activeRules, body);
    }

    /** 提取地点（v2 双策略）：
     *  策略1：动词夹取——"到/至……取/领取"之间即地点（任意结尾都能匹配）；
     *  策略2：前缀+后缀词表回退（旧逻辑）；
     *  均未命中 → "—" */
    public static String extractPlace(String body) {
        if (body == null || body.isEmpty()) return "—";
        ExtractorRules r = activeRules;
        if (r == null) return "—";
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
        return "—";
    }

    /** 核心提取算法：可指定任意规则集（供热更新沙箱验证调用） */
    public static List<String> extractWithRules(ExtractorRules r, String body) {
        Set<String> result = new LinkedHashSet<>();
        if (body == null || body.isEmpty() || r == null) return new ArrayList<>(result);

        // 特征门禁
        if (r.patternFeature != null && !r.patternFeature.matcher(body).find()) {
            return new ArrayList<>(result);
        }

        // A: 关键词锚定
        if (r.patternKeyword != null) {
            Matcher a = r.patternKeyword.matcher(body);
            while (a.find()) {
                addCluster(result, r, a.group(1));
            }
        }

        // B: 凭/出示
        if (r.patternBy != null) {
            Matcher b = r.patternBy.matcher(body);
            while (b.find()) {
                addIfValid(result, r, b.group(1));
            }
        }

        // C: 上下文评分兜底（仅当 A/B 都没抓到时启用）
        if (result.isEmpty() && r.patternAnyToken != null) {
            List<String[]> scored = new ArrayList<>();
            Matcher c = r.patternAnyToken.matcher(body);
            while (c.find()) {
                String tok = c.group(1);
                if (!validCode(r, tok) || isExcludedShape(tok, c.start(), body)) continue;
                int score = 0;
                String win = window(body, c.start(), c.end(), 60);
                if (r.patternFeature != null && r.patternFeature.matcher(win).find()) score += 2;
                if (r.patternAction != null && r.patternAction.matcher(win).find()) score += 2;
                if (r.patternDashShape != null && r.patternDashShape.matcher(tok).matches()) score += 1;
                if (tok.matches("\\d{6,9}")) score += 1;
                if (score >= 3) scored.add(new String[]{tok, String.valueOf(score)});
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

    private static boolean isExcludedShape(String tok, int start, String body) {
        int after = start + tok.length();
        if (after < body.length() && body.charAt(after) == '号') return true;
        if (start > 0 && body.charAt(start - 1) == '*') return true;
        if (after + 1 < body.length() && body.charAt(after) == ':') return true;
        if (P_PHONE.matcher(tok).matches() && tok.length() == 11) return true;
        // v2：「尾号」上下文排除（"取尾号9100包裹"——尾号不是取件码）
        String win = window(body, start, start + tok.length(), 8);
        if (win.contains("尾号")) return true;
        return false;
    }

    private static String window(String s, int start, int end, int w) {
        int from = Math.max(0, start - w);
        int to = Math.min(s.length(), end + w);
        return s.substring(from, to);
    }

    public static boolean validCode(ExtractorRules r, String tok) {
        if (tok == null || tok.isEmpty() || tok.length() > 20 || r == null) return false;
        if (r.patternDashShape != null && r.patternDashShape.matcher(tok).matches()) return true;
        if (r.patternAlnumShape != null && r.patternAlnumShape.matcher(tok).matches()) return true;
        return false;
    }

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
