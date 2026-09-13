import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class TestPddFixed {
    public static void main(String[] args) throws Exception {
        Path srcDir = Paths.get("D:\\project\\Xiaomi-HyperOs-pickup-code-grabber\\app\\src\\io\\github\\okaidev\\pickupcode");
        Path tmp = Files.createTempDirectory("fixcheck");
        Files.copy(srcDir.resolve("ExtractorRules.java"), tmp.resolve("ExtractorRules.java"));
        Files.copy(srcDir.resolve("PickupExtractor.java"), tmp.resolve("PickupExtractor.java"));
        String aj = "D:\\project\\_build-tools\\sdk\\platforms\\android-34\\android.jar";
        
        Process c = Runtime.getRuntime().exec(new String[]{
            "javac", "-encoding", "UTF-8", "-cp", aj, "-d", tmp.toString(),
            tmp.resolve("ExtractorRules.java").toString(), tmp.resolve("PickupExtractor.java").toString()
        });
        if (c.waitFor() != 0) {
            System.out.println("Compile failed: " + new String(c.getErrorStream().readAllBytes()));
            System.exit(1);
        }

        URLClassLoader cl = new URLClassLoader(new URL[]{tmp.toUri().toURL(), Paths.get(aj).toUri().toURL()});
        Class<?> k = cl.loadClass("io.github.okaidev.pickupcode.PickupExtractor");
        k.getMethod("init", cl.loadClass("android.content.Context")).invoke(null, new Object[]{null});
        Method extract = k.getMethod("extract", String.class);

        String text = "【拼多多】您的商品在代收点已超6小时未取，为避免鲜活商品损坏，请尽快取件。";
        List<?> res = (List<?>) extract.invoke(null, text);
        System.out.println("测试拼多多催取短信提取结果（期望为空）: " + res);
        if (res.isEmpty()) {
            System.out.println("SUCCESS: 成功拦截并排除催取短信误抓！");
        } else {
            System.out.println("FAIL: 仍然误抓了 " + res);
        }
    }
}
