import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * 语料回归测试：java ExtractorTest <PickupExtractor.java目录> <sms-cases.txt>
 * 逐条比对期望结果，输出 PASS/FAIL 明细。
 */
public class ExtractorTest {
    public static void main(String[] args) throws Exception {
        Path srcDir = Paths.get(args[0]);
        Path cases = Paths.get(args[1]);

        // 动态编译 PickupExtractor（无 Android 依赖，纯 JVM）
        String src = new String(Files.readAllBytes(srcDir.resolve("PickupExtractor.java")), StandardCharsets.UTF_8);
        Path tmp = Files.createTempDirectory("ext");
        Path javaFile = tmp.resolve("PickupExtractor.java");
        Files.write(javaFile, src.getBytes(StandardCharsets.UTF_8));
        Process c = Runtime.getRuntime().exec(new String[]{
                "javac", "-encoding", "UTF-8", "-d", tmp.toString(), javaFile.toString()});
        if (c.waitFor() != 0) {
            System.out.println("COMPILE FAIL:");
            System.out.println(new String(c.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
            System.exit(2);
        }
        // 反射调用（同 JVM 类加载）
        java.net.URLClassLoader cl = new java.net.URLClassLoader(new java.net.URL[]{tmp.toUri().toURL()});
        Class<?> k = cl.loadClass("io.github.okaidev.pickupcode.PickupExtractor");
        var extract = k.getMethod("extract", String.class);
        var lookLike = k.getMethod("lookLikePickupSms", String.class);

        List<String> lines = Files.readAllLines(cases, StandardCharsets.UTF_8);
        int pass = 0, fail = 0, total = 0;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\\|", 3);
            if (parts.length < 3) continue;
            total++;
            String id = parts[0].trim();
            String expected = parts[1].trim();
            String body = parts[2].trim();

            @SuppressWarnings("unchecked")
            List<String> got = (List<String>) extract.invoke(null, body);
            boolean wantEmpty = "-".equals(expected);
            boolean ok;
            String gotStr;
            if (wantEmpty) {
                ok = got.isEmpty();
                gotStr = String.join(";", got);
            } else {
                List<String> exp = new ArrayList<>(Arrays.asList(expected.split(";")));
                ok = got.equals(exp);
                gotStr = String.join(";", got);
            }
            if (ok) { pass++; System.out.println("PASS " + id + "  [" + gotStr + "]"); }
            else { fail++; System.out.println("FAIL " + id + "  期望[" + expected + "] 实得[" + gotStr + "]"); }
        }
        System.out.println("--------");
        System.out.println("总计 " + total + " 条：PASS=" + pass + " FAIL=" + fail);
        System.exit(fail == 0 ? 0 : 1);
    }
}
