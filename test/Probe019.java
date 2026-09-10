import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Probe019 {
    public static void main(String[] a) throws Exception {
        Path srcDir = Paths.get("D:\\project\\Xiaomi-HyperOs-pickup-code-grabber\\app\\src\\io\\github\\okaidev\\pickupcode");
        Path tmp = Files.createTempDirectory("p019");
        Files.copy(srcDir.resolve("ExtractorRules.java"), tmp.resolve("ExtractorRules.java"));
        Files.copy(srcDir.resolve("PickupExtractor.java"), tmp.resolve("PickupExtractor.java"));
        String aj = "D:\\project\\_build-tools\\sdk\\platforms\\android-34\\android.jar";
        Process c = Runtime.getRuntime().exec("javac -encoding UTF-8 -cp " + aj + " -d " + tmp + " "
                + tmp.resolve("ExtractorRules.java") + " " + tmp.resolve("PickupExtractor.java"));
        if (c.waitFor()!=0) { Files.write(Paths.get("p019-err.txt"), c.getErrorStream().readAllBytes()); System.out.println("CF"); System.exit(2); }
        URLClassLoader cl = new URLClassLoader(new URL[]{tmp.toUri().toURL(), Paths.get(aj).toUri().toURL()});
        Class<?> k = cl.loadClass("io.github.okaidev.pickupcode.PickupExtractor");
        k.getMethod("init", cl.loadClass("android.content.Context")).invoke(null, new Object[]{null});
        Method by = null, kw = null;
        try { by = k.getMethod("extractWithRules", cl.loadClass("io.github.okaidev.pickupcode.ExtractorRules"), String.class); } catch (Throwable ignored) {}
        // 逐层探测 019
        String body = "【中通快递】凭M-2-5341到福源折扣店取尾号9100包裹";
        Method lookLike = k.getMethod("lookLikePickupSms", String.class);
        Method extract = k.getMethod("extract", String.class);
        StringBuilder sb = new StringBuilder();
        sb.append("lookLike=").append(lookLike.invoke(null, body)).append("\n");
        sb.append("extract=").append(extract.invoke(null, body)).append("\n");
        // A 层关键词锚测试（"凭码"不该命中，"凭取件码"不该命中此句——此句是"凭M-2-5341"）
        // B 层凭码测试：凭 + M-2-5341 —— B 层 lookahead 要求 [到去取领取票]，M-2-5341 后是"到" ✓ 应命中
        // 但注意 B 层正则是 (?:" + byWords + ")[包裹符]*(" + dash + "|" + alnum + ")  —— dash=[A-Za-z]?\d{1,6}(-…)
        // M-2-5341: M 后是 "-"，dash 要求字母后跟数字！M- 不匹配 [A-Za-z]?\d{1,6}（M 后必须 \d）
        // → 这就是根因：dash 形状不支持"字母-数字"开头的段
        Object rules = cl.loadClass("io.github.okaidev.pickupcode.ExtractorRules").getMethod("createDefault").invoke(null);
        java.lang.reflect.Field f = cl.loadClass("io.github.okaidev.pickupcode.ExtractorRules").getField("dashPatternStr");
        sb.append("dashPattern=").append(f.get(rules)).append("\n");
        Files.write(Paths.get("p019-out.txt"), sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        System.out.println("done");
    }
}
