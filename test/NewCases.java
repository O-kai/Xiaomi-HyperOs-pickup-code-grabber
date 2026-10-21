import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** 本轮新增用例专项验证 */
public class NewCases {

    static final String[] NEED = {
            "ExtractorRules.java", "PickupExtractor.java", "UserRules.java",
            "TodoWriter.java", "MissedSmsStore.java", "NotesBackend.java",
            "Repair.java", "Diagnostics.java"
    };

    public static void main(String[] args) throws Exception {
        String repo = "D:\\project\\Xiaomi-HyperOs-pickup-code-grabber";
        Path srcDir = Paths.get(repo, "app", "src", "io", "github", "okaidev", "pickupcode");
        Path tmp = Files.createTempDirectory("newc");
        for (String f : NEED) Files.copy(srcDir.resolve(f), tmp.resolve(f));

        String androidJar = "D:\\develop\\android-sdk\\platforms\\android-34\\android.jar";
        StringBuilder cmd = new StringBuilder("javac -encoding UTF-8 -nowarn -cp ")
                .append(androidJar).append(" -d ").append(tmp);
        for (String f : NEED) cmd.append(" ").append(tmp.resolve(f));
        Process c = Runtime.getRuntime().exec(cmd.toString());
        if (c.waitFor() != 0) {
            System.out.println("COMPILE FAIL:");
            System.out.println(new String(c.getErrorStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8));
            System.exit(2);
        }

        URLClassLoader cl = new URLClassLoader(
                new URL[]{tmp.toUri().toURL(), Paths.get(androidJar).toUri().toURL()});
        Class<?> k = cl.loadClass("io.github.okaidev.pickupcode.PickupExtractor");
        k.getMethod("init", cl.loadClass("android.content.Context")).invoke(null, new Object[]{null});
        Method extract = k.getMethod("extract", String.class);
        Method place = k.getMethod("extractPlace", String.class);

        String[][] cases = {
            {"4-3-5120", "【驿小哥】您的快递圆通，已到江南绿城西门北50米顺丰速运驿收发，凭4-3-5120免费取，如有疑问可到店咨询，谢谢。"},
            {"6-1-2233", "【驿收发】您的圆通快递已到站，凭6-1-2233免费取件，地址：幸福小区南门驿收发"},
            {"9-2-7788", "【快宝驿站】您的包裹已到快宝驿站，取件码9-2-7788，可免费取件，请及时领取"},
            // 「免费取」句式变体
            {"5-5-1234", "【某驿站】凭5-5-1234可免费取件"},
            {"5-5-5678", "【某驿站】凭5-5-5678即可取件"},
            {"5-5-9012", "【某驿站】凭5-5-9012自取"},
            // 反例必须仍被拦截
            {null, "【某银行】您的验证码是 123456，5分钟内有效，请勿泄露"},
            {null, "【某商城】恭喜获得取件码优惠券，满100减20"},
            {null, "【某停车】您的停车码为 223344，缴费后离场"},
        };

        int pass = 0, fail = 0;
        for (String[] cs : cases) {
            @SuppressWarnings("unchecked")
            List<String> got = (List<String>) extract.invoke(null, cs[1]);
            String g = got.isEmpty() ? null : got.get(0);
            boolean ok = cs[0] == null ? got.isEmpty() : cs[0].equals(g);
            if (ok) pass++; else fail++;
            System.out.println((ok ? "PASS" : "FAIL") + "  期望=" + cs[0] + "  实际=" + got);
            if (!ok) System.out.println("       " + cs[1]);
        }
        System.out.println();
        System.out.println("TOTAL=" + cases.length + " PASS=" + pass + " FAIL=" + fail);
    }
}
