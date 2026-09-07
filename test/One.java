import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.net.*;
import java.util.List;

public class One {
    public static void main(String[] a) throws Exception {
        Path tmp = Files.createTempDirectory("one");
        Files.copy(Paths.get("D:\\project\\Xiaomi-HyperOs-pickup-code-grabber\\app\\src\\io\\github\\okaidev\\pickupcode\\PickupExtractor.java"),
                tmp.resolve("PickupExtractor.java"), StandardCopyOption.REPLACE_EXISTING);
        Process c = Runtime.getRuntime().exec(new String[]{"javac", "-encoding", "UTF-8",
                "-d", tmp.toString(), tmp.resolve("PickupExtractor.java").toString()});
        c.waitFor();
        System.out.println("compile exit=" + c.waitFor());
        URLClassLoader cl = new URLClassLoader(new URL[]{tmp.toUri().toURL()});
        Class<?> k = cl.loadClass("io.github.okaidev.pickupcode.PickupExtractor");
        System.out.println("loaded from: " + k.getProtectionDomain().getCodeSource().getLocation());
        Method m = k.getMethod("extract", String.class);
        List<?> r = (List<?>) m.invoke(null, "【某App】动态验证码 33-3-0444 请在5分钟内输入（验证码类，无快递特征词）");
        System.out.println("012 result = " + r);
        Method ll = k.getMethod("lookLikePickupSms", String.class);
        System.out.println("012 lookLike = " + ll.invoke(null, "【某App】动态验证码 33-3-0444 请在5分钟内输入（验证码类，无快递特征词）"));
    }
}
