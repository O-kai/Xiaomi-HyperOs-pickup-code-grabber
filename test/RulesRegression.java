import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 动态规则引擎脱机回归（v3.0.0）
 *
 * 运行方式（需要 JDK + Android SDK 的 android.jar）：
 *   javac -encoding UTF-8 RulesRegression.java
 *   java RulesRegression
 *
 * 说明：
 *  - 用 android.jar 作 classpath 编译（android.jar 提供 org.json 与 Android API 声明），
 *    纯逻辑路径（提取/校验）在 JVM 下可直接运行，不需真机；
 *  - 依赖链：PickupExtractor → UserRules → {Repair, Diagnostics, TodoWriter, ...}
 *    所以必须把整条依赖链一起编译，否则会 COMPILE FAIL；
 *  - 结果写入 reg-result.txt。
 */
public class RulesRegression {
    /**
     * 参与编译的源文件（新增依赖时记得同步这里）。
     *
     * <p>v4.0.1：补上 AppVersion.java —— Diagnostics 现在用它取版本号
     * （原先 Diagnostics 自己读 PackageManager，各写各的）。
     */
    static final String[] NEED = {
            "ExtractorRules.java", "PickupExtractor.java", "UserRules.java",
            "TodoWriter.java", "MissedSmsStore.java", "NotesBackend.java",
            "Repair.java", "Diagnostics.java", "AppVersion.java"
    };

    /**
     * 准备 GeneratedRules.java 到临时目录。
     *
     * <p>优先直接复用 Gradle 任务 generateRules 的产物（app/build/generated/rules/java/...），
     * 这样测的就是真正打进 APK 的那份；产物不存在时（例如贡献者没先跑构建），
     * 再按同一套格式从 rules/rules.json 现场生成一份。
     */
    static void prepareGeneratedRules(String repo, Path tmp) throws Exception {
        Path gradleOut = Paths.get(repo, "app", "build", "generated", "rules", "java",
                "io", "github", "okaidev", "pickupcode", "GeneratedRules.java");
        if (Files.isRegularFile(gradleOut)) {
            Files.copy(gradleOut, tmp.resolve("GeneratedRules.java"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        Path rulesJson = Paths.get(repo, "rules", "rules.json");
        if (!Files.isRegularFile(rulesJson)) {
            System.out.println("RULES SOURCE MISS: " + rulesJson);
            System.exit(2);
        }
        String raw = new String(Files.readAllBytes(rulesJson),
                java.nio.charset.StandardCharsets.UTF_8);
        Files.write(tmp.resolve("GeneratedRules.java"),
                buildGeneratedRules(raw).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 从扁平 JSON 取一个字符串/数字字段（够用即可，避免为测试引入 JSON 依赖） */
    static String extract(String json, String key) {
        String needle = "\"" + key + "\"";
        int i = json.indexOf(needle);
        if (i < 0) return "";
        int c = json.indexOf(':', i + needle.length());
        int q1 = json.indexOf('"', c + 1);
        if (q1 < 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int j = q1 + 1; j < json.length(); j++) {
            char ch = json.charAt(j);
            if (ch == '\\') {
                char nx = json.charAt(j + 1);
                if (nx == 'n') { sb.append('\n'); j++; continue; }
                if (nx == 't') { sb.append('\t'); j++; continue; }
                if (nx == 'r') { sb.append('\r'); j++; continue; }
                sb.append('\\').append(nx); j++; continue;
            }
            if (ch == '"') break;
            sb.append(ch);
        }
        return sb.toString();
    }

    /** 从扁平 JSON 取一个字符串数组字段 */
    static java.util.List<String> extractArray(String json, String key) {
        java.util.List<String> out = new java.util.ArrayList<>();
        String needle = "\"" + key + "\"";
        int i = json.indexOf(needle);
        if (i < 0) return out;
        int lb = json.indexOf('[', i);
        if (lb < 0) return out;
        int rb = json.indexOf(']', lb);
        if (rb < 0) return out;
        String body = json.substring(lb + 1, rb);
        int p = 0;
        while (true) {
            int q1 = body.indexOf('"', p);
            if (q1 < 0) break;
            StringBuilder sb = new StringBuilder();
            int j = q1 + 1;
            while (j < body.length()) {
                char ch = body.charAt(j);
                if (ch == '\\') {
                    char nx = body.charAt(j + 1);
                    if (nx == 'n') { sb.append('\n'); j += 2; continue; }
                    if (nx == 't') { sb.append('\t'); j += 2; continue; }
                    if (nx == 'r') { sb.append('\r'); j += 2; continue; }
                    sb.append('\\').append(nx); j += 2; continue;
                }
                if (ch == '"') break;
                sb.append(ch);
                j++;
            }
            out.add(sb.toString());
            p = body.indexOf('"', j);
            if (p < 0) break;
        }
        return out;
    }

    static String jstr(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t") + "\"";
    }

    static String jarr(java.util.List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(jstr(list.get(i)));
        }
        return sb.toString();
    }

    /** 由 rules/rules.json 生成 GeneratedRules.java（与 Gradle 的 generateRules 同格式） */
    static String buildGeneratedRules(String raw) {
        String[][] arrays = {
                {"FEATURE_WORDS", "featureWords"},
                {"ACTION_WORDS", "actionWords"},
                {"KEYWORD_ANCHORS", "keywordAnchors"},
                {"BY_WORDS", "byWords"},
                {"BY_LOOKAHEAD_WORDS", "byLookaheadWords"},
                {"PLACE_PREFIXES", "placePrefixes"},
                {"PLACE_SUFFIXES", "placeSuffixes"},
                {"NEGATIVE_KEYWORDS", "negativeKeywords"},
                {"EXCLUDE_TAIL_WORDS", "excludeTailWords"},
                {"EXCLUDE_HEAD_WORDS", "excludeHeadWords"},
                {"EXTRA_FEATURE_WORDS", "extraFeatureWords"},
        };
        StringBuilder sb = new StringBuilder();
        sb.append("package io.github.okaidev.pickupcode;\n\n");
        sb.append("public final class GeneratedRules {\n");
        sb.append("    private GeneratedRules() { }\n\n");
        sb.append("    public static final int VERSION = ")
          .append(extract(raw, "version").trim()).append(";\n");
        sb.append("    public static final int MIN_APP_VERSION_CODE = ")
          .append(extract(raw, "minAppVersionCode").trim()).append(";\n");
        sb.append("    public static final String DESCRIPTION = ")
          .append(jstr(extract(raw, "description"))).append(";\n");
        sb.append("    public static final String DASH_PATTERN = ")
          .append(jstr(extract(raw, "dashPattern"))).append(";\n");
        sb.append("    public static final String ALNUM_PATTERN = ")
          .append(jstr(extract(raw, "alnumPattern"))).append(";\n\n");
        for (String[] pair : arrays) {
            sb.append("    public static final String[] ").append(pair[0]).append(" = { ")
              .append(jarr(extractArray(raw, pair[1]))).append(" };\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        // 允许用环境变量/参数覆盖仓库路径，便于其他贡献者在自己的机器上跑
        String repo = (args.length > 0) ? args[0]
                : (System.getenv("PICKUP_REPO") != null ? System.getenv("PICKUP_REPO")
                : "D:\\project\\Xiaomi-HyperOs-pickup-code-grabber");
        Path srcDir = Paths.get(repo, "app", "src", "io", "github", "okaidev", "pickupcode");
        Path tmp = Files.createTempDirectory("v300reg");

        for (String f : NEED) {
            Files.copy(srcDir.resolve(f), tmp.resolve(f));
        }

        // v4.0：内置默认规则不再是手写常量，而是构建期从 rules/rules.json 生成的
        // GeneratedRules.java。优先复用 Gradle 产物；没有就现场生成一份同格式的。
        prepareGeneratedRules(repo, tmp);

        String androidJar = (args.length > 1) ? args[1]
                : System.getenv().getOrDefault("ANDROID_JAR",
                "D:\\project\\_build-tools\\sdk\\platforms\\android-34\\android.jar");

        StringBuilder cmd = new StringBuilder("javac -encoding UTF-8 -nowarn -cp ")
                .append(androidJar).append(" -d ").append(tmp);
        for (String f : NEED) cmd.append(" ").append(tmp.resolve(f));
        // v4.0：内置默认规则来自构建生成的 GeneratedRules.java，必须一起编译
        cmd.append(" ").append(tmp.resolve("GeneratedRules.java"));

        Process c = Runtime.getRuntime().exec(cmd.toString());
        if (c.waitFor() != 0) {
            System.out.println("COMPILE FAIL:");
            byte[] err = c.getErrorStream().readAllBytes();
            System.out.println(new String(err, java.nio.charset.StandardCharsets.UTF_8));
            System.exit(2);
        }

        URLClassLoader cl = new URLClassLoader(new URL[]{tmp.toUri().toURL(), Paths.get(androidJar).toUri().toURL()});
        Class<?> k = cl.loadClass("io.github.okaidev.pickupcode.PickupExtractor");
        Method init = k.getMethod("init", cl.loadClass("android.content.Context"));
        Method extract = k.getMethod("extract", String.class);
        Method lookLike = k.getMethod("lookLikePickupSms", String.class);
        Method place = k.getMethod("extractPlace", String.class);
        Method smoke = k.getMethod("runSmokeTest", cl.loadClass("io.github.okaidev.pickupcode.ExtractorRules"));

        // init(null) 不做任何事（回退默认规则）
        init.invoke(null, new Object[]{null});

        java.io.PrintStream out = new java.io.PrintStream(new java.io.FileOutputStream("reg-result.txt"), true, "UTF-8");

        System.out.println("== SMOKE TEST (built-in default rules) ==");
        Object defaultRules = cl.loadClass("io.github.okaidev.pickupcode.ExtractorRules")
                .getMethod("createDefault").invoke(null);
        boolean smokeOk = (boolean) smoke.invoke(null, defaultRules);
        System.out.println("SmokeTest on defaults: " + (smokeOk ? "PASS" : "FAIL"));

        // 28 条用例回归
        // v3.1.0：此前这里硬编码了绝对路径，换机器/换目录就跑不了（repo 参数形同虚设）。
        // 改为优先用命令行传入的 repo 参数，其次退回本文件所在目录的上一级。
        Path repoPath = Paths.get(repo);
        Path cases = repoPath.resolve("test").resolve("sms-cases.txt");
        if (!Files.exists(cases)) {
            cases = Paths.get("sms-cases.txt");
        }
        System.out.println("用例文件: " + cases);
        List<String> lines = Files.readAllLines(cases, java.nio.charset.StandardCharsets.UTF_8);
        int pass = 0, fail = 0, total = 0;
        StringBuilder report = new StringBuilder();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\\|", 3);
            if (parts.length < 3) continue;
            total++;
            String id = parts[0].trim();
            String expected = parts[1].trim();
            String body = parts[2].trim();

            List<String> got = (List<String>) extract.invoke(null, body);
            String gotStr = String.join(";", got);
            String expStr = "-".equals(expected) ? "" : expected;
            boolean ok = ("-".equals(expected) && got.isEmpty()) || gotStr.equals(expStr);
            if (ok) { pass++; report.append("PASS ").append(id).append("  [").append(gotStr).append("]\n"); }
            else { fail++; report.append("FAIL ").append(id).append("  期望[").append(expected).append("] 实得[").append(gotStr).append("]\n"); }
        }

        // 地点提取回归（新增 13 条：原 5 + 新样本 8）
        report.append("\n== PLACE ==\n");
        String[][] places = {
            {"【菜鸟驿站】测试包裹：取件码为99-9-1234，请到XX小区快递驿站取件（测试条目，可删除）", "XX小区快递驿站"},
            {"【妈妈驿站】取货码5-5-9-13，您有包裹YT764306505052已到苍山下坡圆通快递", "苍山下坡圆通快递"},
            {"【申通快递】请凭9-7-0993到苍山老菜场斜对面申通快递取您的快递，详询代收驿站", "苍山老菜场斜对面申通快递"},
            {"【菜鸟驿站】您的包裹已到站，凭4-6-7602到新郑龙湖富田兴龙湾31号楼店取件。", "新郑龙湖富田兴龙湾31号楼店"},
            {"【兔喜生活】您有包裹已到达兴龙湾30栋兔喜店，取件码为2-3-05025，地址:兴龙湾30号楼102", "兴龙湾30栋兔喜店"},
            {"【中通快递】凭M-2-5341到福源折扣店取尾号9100包裹", "福源折扣店"},
            {"【妈妈驿站】取货码D4-7048，您有圆通快递包裹，已到天和人家快递点", "天和人家快递点"},
            {"【欢猫驿站】您的韵达快递包裹已到天和人家小区门面房，请凭D3-7508取件", "天和人家小区门面房"},
            {"【兔喜生活】您有包裹已到达天和人家快递店，取件码为S3-2043，地址:天和人家门口门面房", "天和人家快递店"},
            {"【兔喜生活】您有包裹已到达天和人家快递店，取件码为D2-7762，地址:天和人家门口门面房", "天和人家快递店"},
            {"【中通快递】凭4-2-02015到森泰首府快递驿站取尾号6963包裹。", "森泰首府快递驿站"},
            {"【中通快递】请凭109-6-4006到理工东苑大门左转快递服务中心二楼取件，地址：理工东苑大门左转快递服务中心二楼。", "理工东苑大门左转快递服务中心二楼"},
            {"【妈妈驿站】凭取件码267961至化工镇云水村委文化广场东侧小平房取件。有疑问联系快递员。", "化工镇云水村委文化广场东侧小平房"},
        };
        int pPass = 0, pFail = 0;
        for (String[] tc : places) {
            String got = (String) place.invoke(null, tc[0]);
            if (got.equals(tc[1])) { pPass++; report.append("PASS place [").append(got).append("]\n"); }
            else { pFail++; report.append("FAIL place 期望[").append(tc[1]).append("] 实得[").append(got).append("]\n"); }
        }

        report.append("\nTOTAL CODES: ").append(total).append(" PASS=").append(pass).append(" FAIL=").append(fail).append("\n");
        report.append("TOTAL PLACE: ").append(places.length).append(" PASS=").append(pPass).append(" FAIL=").append(pFail).append("\n");
        Files.write(Paths.get("reg-result.txt"), report.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        System.out.println("Done.");
        System.exit((fail == 0 && pFail == 0 && smokeOk) ? 0 : 1);
    }
}
