import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.okaidev.pickupcode.ExtractorRules;
import io.github.okaidev.pickupcode.PickupExtractor;

/**
 * 规则实验台（脱机）。
 *
 * 用途：直接加载仓库里的 rules/rules.json 跑语料，改规则时不用编译装机。
 * 跑法（仓库根目录）：
 *   javac -encoding UTF-8 -cp <android.jar> -d test/out-lab \
 *        test/RuleLab.java <源码目录下的依赖>.java
 *   java -cp test/out-lab;<android.jar> RuleLab [仓库根目录]
 *
 * 依赖链与 UserRulesTest 相同：PickupExtractor → UserRules → {Repair, Diagnostics, TodoWriter…}
 */
public class RuleLab {

    // ---------- 极简 JSON 解析（只支持本规则集用到的结构）----------
    static final class Json {
        final String s;
        int i;

        Json(String s) { this.s = s; }

        void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        Object parse() {
            ws();
            char c = s.charAt(i);
            if (c == '{') return obj();
            if (c == '[') return arr();
            if (c == '"') return str();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int st = i;
            while (i < s.length() && "-+.eE0123456789".indexOf(s.charAt(i)) >= 0) i++;
            return Double.parseDouble(s.substring(st, i));
        }

        Map<String, Object> obj() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                String k = str();
                ws(); i++; // ':'
                m.put(k, parse());
                ws();
                char c = s.charAt(i++);
                if (c == '}') return m;
            }
        }

        List<Object> arr() {
            List<Object> l = new ArrayList<>();
            i++; ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
            while (true) {
                l.add(parse());
                ws();
                char c = s.charAt(i++);
                if (c == ']') return l;
            }
        }

        String str() {
            StringBuilder b = new StringBuilder();
            i++; // opening quote
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n': b.append('\n'); break;
                        case 't': b.append('\t'); break;
                        case 'r': b.append('\r'); break;
                        case 'u':
                            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default: b.append(e);
                    }
                } else {
                    b.append(c);
                }
            }
        }
    }

    /**
     * 加载规则集。
     *
     * <p>v4.0 收口：这里原先手写了一份「把 rules.json 填进 ExtractorRules」的手抄本，
     * 与 App 里的 {@link ExtractorRules#fromJson} 各写各的——新增字段时
     * 很容易只改一边（v8 的 excludeTailPhrases 就是这么漏的）。
     * 现在直接复用 App 的加载器，实验台跑的就是 App 真正会跑的那份规则。
     */
    static ExtractorRules rulesFromJson(String path) throws Exception {
        String raw = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        return ExtractorRules.fromJson(raw);
    }

    @SuppressWarnings("unchecked")
    static List<String> strList(Object o) {
        List<String> out = new ArrayList<>();
        if (o == null) return out;
        for (Object x : (List<Object>) o) out.add((String) x);
        return out;
    }

    /**
     * 定位仓库根：优先用命令行参数；参数里的路径可能在 Git Bash → JVM 之间被改码，
     * 所以再退一步——从当前工作目录向上找带 rules/rules.json 的那一层。
     */
    static String findRepo(String[] args) {
        if (args.length > 0) {
            Path p = Paths.get(args[0]);
            if (Files.isRegularFile(p.resolve("rules").resolve("rules.json"))) {
                return args[0];
            }
        }
        Path cur = Paths.get("").toAbsolutePath();
        while (cur != null) {
            if (Files.isRegularFile(cur.resolve("rules").resolve("rules.json"))) {
                return cur.toString();
            }
            cur = cur.getParent();
        }
        throw new IllegalStateException("找不到仓库根（需要 rules/rules.json）");
    }

    public static void main(String[] args) throws Exception {
        String repo = findRepo(args);
        String rulesPath = repo + "/rules/rules.json";
        String casesPath = repo + "/test/sms-cases.txt";
        System.out.println("repo = " + repo);

        ExtractorRules rules = rulesFromJson(rulesPath);
        // 地点提取走的是全局活跃规则，反射替换，方便用同一份 JSON 校验
        Field active = PickupExtractor.class.getDeclaredField("activeRules");
        active.setAccessible(true);
        active.set(null, rules);

        System.out.println("规则集 v" + rules.version
                + " | dash=" + rules.dashPatternStr);
        System.out.println("--------------------------------------------------");

        List<String> lines = Files.readAllLines(Paths.get(casesPath), StandardCharsets.UTF_8);
        int pass = 0, fail = 0;
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String[] parts = t.split("\\|", 3);
            if (parts.length < 3) continue;
            String id = parts[0].trim();
            String expect = parts[1].trim();
            String sms = parts[2].trim();

            List<String> got = PickupExtractor.extractWithRules(rules, null, sms);
            List<String> want = new ArrayList<>();
            if (!"-".equals(expect)) {
                for (String w : expect.split(";")) want.add(w.trim());
            }
            boolean ok = got.size() == want.size() && got.containsAll(want);
            if (ok) {
                pass++;
            } else {
                fail++;
                System.out.println("FAIL " + id + " 期望=" + want + " 实得=" + got);
                System.out.println("     " + sms);
                System.out.println("     地点=" + PickupExtractor.extractPlace(sms));
            }
        }
        System.out.println("--------------------------------------------------");
        System.out.println("TOTAL=" + (pass + fail) + " PASS=" + pass + " FAIL=" + fail);
        if (fail > 0) System.exit(1);
    }
}
