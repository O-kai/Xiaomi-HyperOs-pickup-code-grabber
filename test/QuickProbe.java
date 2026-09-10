import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class QuickProbe {
    public static void main(String[] a) throws Exception {
        Path srcDir = Paths.get("D:\\project\\Xiaomi-HyperOs-pickup-code-grabber\\app\\src\\io\\github\\okaidev\\pickupcode");
        Path tmp = Files.createTempDirectory("probe");
        Files.copy(srcDir.resolve("ExtractorRules.java"), tmp.resolve("ExtractorRules.java"));
        Files.copy(srcDir.resolve("PickupExtractor.java"), tmp.resolve("PickupExtractor.java"));
        String aj = "D:\\project\\_build-tools\\sdk\\platforms\\android-34\\android.jar";
        Process c = Runtime.getRuntime().exec(new String[]{"javac","-encoding","UTF-8","-cp",aj,"-d",tmp.toString(),
                tmp.resolve("ExtractorRules.java").toString(), tmp.resolve("PickupExtractor.java").toString()});
        if (c.waitFor()!=0) { System.out.println("COMPILE FAIL"); System.exit(2); }
        URLClassLoader cl = new URLClassLoader(new URL[]{tmp.toUri().toURL(), Paths.get(aj).toUri().toURL()});
        Class<?> k = cl.loadClass("io.github.okaidev.pickupcode.PickupExtractor");
        Method init = k.getMethod("init", cl.loadClass("android.content.Context"));
        init.invoke(null, new Object[]{null});
        Method extract = k.getMethod("extract", String.class);
        Method place = k.getMethod("extractPlace", String.class);
        String[] cases = {
            "【中通快递】凭M-2-5341到福源折扣店取尾号9100包裹",
            "【妈妈驿站】取货码D4-7048，您有圆通快递包裹，已到天和人家快递点",
            "【欢猫驿站】您的韵达快递包裹已到天和人家小区门面房，请凭D3-7508取件",
            "【兔喜生活】您有包裹已到达天和人家快递店，取件码为S3-2043，地址:天和人家门口门面房",
            "【兔喜生活】您有包裹已到达天和人家快递店，取件码为D2-7762，地址:天和人家门口门面房"
        };
        StringBuilder sb = new StringBuilder();
        for (String s : cases) {
            List<String> codes = (List<String>) extract.invoke(null, s);
            String pl = (String) place.invoke(null, s);
            sb.append("CODES=").append(codes).append(" PLACE=").append(pl).append("\n");
        }
        Files.write(Paths.get("probe-out.txt"), sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        System.out.println("done");
    }
}
