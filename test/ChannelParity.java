import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 通道一致性验证：同一条文本，用「短信 sender」与「通知 sender」分别跑，
 * 结果必须完全一致。这是「规则集统一」的核心验收。
 */
public class ChannelParity {

    static final String[] NEED = {
            "ExtractorRules.java", "PickupExtractor.java", "UserRules.java",
            "TodoWriter.java", "MissedSmsStore.java", "NotesBackend.java",
            "Repair.java", "Diagnostics.java"
    };

    public static void main(String[] args) throws Exception {
        String repo = "D:\\project\\Xiaomi-HyperOs-pickup-code-grabber";
        Path srcDir = Paths.get(repo, "app", "src", "io", "github", "okaidev", "pickupcode");
        Path tmp = Files.createTempDirectory("par");
        for (String f : NEED) Files.copy(srcDir.resolve(f), tmp.resolve(f));

        String androidJar = "D:\\develop\\android-sdk\\platforms\\android-34\\android.jar";
        StringBuilder cmd = new StringBuilder("javac -encoding UTF-8 -nowarn -cp ")
                .append(androidJar).append(" -d ").append(tmp);
        for (String f : NEED) cmd.append(" ").append(tmp.resolve(f));
        Process c = Runtime.getRuntime().exec(cmd.toString());
        if (c.waitFor() != 0) {
            System.out.println(new String(c.getErrorStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8));
            System.exit(2);
        }

        URLClassLoader cl = new URLClassLoader(
                new URL[]{tmp.toUri().toURL(), Paths.get(androidJar).toUri().toURL()});
        Class<?> k = cl.loadClass("io.github.okaidev.pickupcode.PickupExtractor");
        k.getMethod("init", cl.loadClass("android.content.Context")).invoke(null, new Object[]{null});

        Method extractS = k.getMethod("extract", String.class, String.class);
        Method placeS = k.getMethod("extractPlace", String.class, String.class);

        String[][] texts = {
            {"短信", "【驿小哥】您的快递圆通，已到江南绿城西门北50米顺丰速运驿收发，凭4-3-5120免费取，如有疑问可到店咨询，谢谢。"},
            {"通知", "您的商品已存放至菜鸟驿站代收点，请凭取件码（26-3-1020）及时取件，今日共1个快递待取。"},
            {"短信", "包裹待签收【取件码-6-4-3009】，手机号已加密，享号码保护服务，点击查看详情>>"},
            {"短信", "【丰巢】凭22-2-3579到华润万家旁丰巢柜取件"},
            {"通知", "【菜鸟驿站】您的包裹已到站，取件码 8-2-4437，请及时取件"},
            {"短信", "【某银行】您的验证码是 123456，5分钟内有效，请勿泄露"},
            {"通知", "【某商城】恭喜获得取件码优惠券，满100减20"},
        };

        String[] senders = {"1069888877", "通知:com.xunmeng.pinduoduo"};
        int pass = 0, fail = 0;
        for (String[] t : texts) {
            for (String sd : senders) {
                @SuppressWarnings("unchecked")
                List<String> codes = (List<String>) extractS.invoke(null, sd, t[1]);
                String place = (String) placeS.invoke(null, sd, t[1]);
                System.out.println("sender=" + sd);
                System.out.println("  codes=" + codes + "  place=" + place);
            }
            // 两个 sender 的结果必须一致
            @SuppressWarnings("unchecked")
            List<String> a = (List<String>) extractS.invoke(null, senders[0], t[1]);
            @SuppressWarnings("unchecked")
            List<String> b = (List<String>) extractS.invoke(null, senders[1], t[1]);
            String pa = (String) placeS.invoke(null, senders[0], t[1]);
            String pb = (String) placeS.invoke(null, senders[1], t[1]);
            boolean ok = a.equals(b) && pa.equals(pb);
            if (ok) pass++; else fail++;
            System.out.println((ok ? "  ✓ 两通道一致" : "  ✗ 两通道不一致!"));
            System.out.println();
        }
        System.out.println("一致性 TOTAL=" + texts.length + " PASS=" + pass + " FAIL=" + fail);
    }
}
