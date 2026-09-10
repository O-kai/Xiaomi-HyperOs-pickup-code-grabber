import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * v2.8.0 动态规则引擎脱机回归：
 * 动态编译 PickupExtractor + ExtractorRules（无 Android 依赖问题——两者都不 import android.*，
 * 检查：PickupExtractor 引了 android.util.Log 与 android.content.Context！
 * 因此用源码替换法：把 Log/Context 相关调用打桩后再编译。
 */
public class RulesRegression {
    public static void main(String[] args) throws Exception {
        Path srcDir = Paths.get("D:\\project\\Xiaomi-HyperOs-pickup-code-grabber\\app\\src\\io\\github\\okaidev\\pickupcode");
        Path tmp = Files.createTempDirectory("v280reg");

        // 1) ExtractorRules + PickupExtractor：直接用 android.jar（org.json + 完整 API）编译，零打桩
        Files.copy(srcDir.resolve("ExtractorRules.java"), tmp.resolve("ExtractorRules.java"));
        Files.copy(srcDir.resolve("PickupExtractor.java"), tmp.resolve("PickupExtractor.java"));

        String androidJar = "D:\\project\\_build-tools\\sdk\\platforms\\android-34\\android.jar";
        Process c = Runtime.getRuntime().exec(new String[]{"javac", "-encoding", "UTF-8",
                "-cp", androidJar,
                "-d", tmp.toString(), tmp.resolve("ExtractorRules.java").toString(), tmp.resolve("PickupExtractor.java").toString()});
        if (c.waitFor() != 0) {
            System.out.println("COMPILE FAIL:");
            byte[] err = c.getErrorStream().readAllBytes();
            Files.write(Paths.get("reg-err.txt"), err);
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

        // 18 条语料回归
        List<String> lines = Files.readAllLines(Paths.get("D:\\project\\Xiaomi-HyperOs-pickup-code-grabber\\test\\sms-cases.txt"), java.nio.charset.StandardCharsets.UTF_8);
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

        // 地点提取回归（新增 5 条）
        report.append("\n== PLACE ==\n");
        String[][] places = {
            {"【菜鸟驿站】测试包裹：取件码为99-9-1234，请到XX小区快递驿站取件（测试条目，可删除）", "XX小区快递"},
            {"【妈妈驿站】取货码5-5-9-13，您有包裹YT764306505052已到苍山下坡圆通快递", "苍山下坡圆通快递"},
            {"【申通快递】请凭9-7-0993到苍山老菜场斜对面申通快递取您的快递，详询代收驿站", "苍山老菜场斜对面申通快递"},
            {"【菜鸟驿站】您的包裹已到站，凭4-6-7602到新郑龙湖富田兴龙湾31号楼店取件。", "新郑龙湖富田兴龙湾31号楼店"},
            {"【兔喜生活】您有包裹已到达兴龙湾30栋兔喜店，取件码为2-3-05025，地址:兴龙湾30号楼102", "兴龙湾30栋兔喜店"},
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
