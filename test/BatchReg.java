import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 批量回归：逐条跑 sms-cases.txt + 新用例，输出 PASS/FAIL。
 * 不依赖 RulesRegression 的交互逻辑，纯反射直调，避免卡死。
 */
public class BatchReg {

    static final String[] NEED = {
            "ExtractorRules.java", "PickupExtractor.java", "UserRules.java",
            "TodoWriter.java", "MissedSmsStore.java", "NotesBackend.java",
            "Repair.java", "Diagnostics.java"
    };

    static class Case {
        String body, expectCode, expectSource, expectPlace;
        Case(String b, String c) { body = b; expectCode = c; }
        Case(String b, String c, String s, String p) { body = b; expectCode = c; expectSource = s; expectPlace = p; }
    }

    public static void main(String[] args) throws Exception {
        String repo = "D:\\project\\Xiaomi-HyperOs-pickup-code-grabber";
        Path srcDir = Paths.get(repo, "app", "src", "io", "github", "okaidev", "pickupcode");
        Path tmp = Files.createTempDirectory("batch");
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

        List<Case> cases = new ArrayList<>();
        // ===== 本轮新增：驿小哥 =====
        cases.add(new Case("【驿小哥】您的快递圆通，已到江南绿城西门北50米顺丰速运驿收发，凭4-3-5120免费取，如有疑问可到店咨询，谢谢。", "4-3-5120"));
        // ===== 回归：原有各种码形 =====
        cases.add(new Case("【菜鸟驿站】您的包裹已到站，取件码 22-2-3579，请到 XX 小区东门菜鸟驿站取件", "22-2-3579"));
        cases.add(new Case("【递管家】您的包裹已到店，请用提货码7652-5845取包裹，咨询快递员", "7652-5845"));
        cases.add(new Case("【丰巢】您的包裹已放入丰巢智能柜，取件码 8-2-4437，请及时取件", "8-2-4437"));
        cases.add(new Case("【妈妈驿站】取货码 5-0-1123，请到店领取", "5-0-1123"));
        cases.add(new Case("【菜鸟】您的包裹已到站，凭552577至同兴路菜鸟驿站取件", "552577"));
        cases.add(new Case("【京东】取件码为 123456，请到京东快递柜取件", "123456"));
        cases.add(new Case("【中通】您的快件已到站，取件码 A1B2C3，请凭此码取件", "A1B2C3"));
        cases.add(new Case("【顺丰】您的快件已放入丰巢柜，取件码 6-2-8877，请尽快取件", "6-2-8877"));
        // ===== 反例：不应提取 =====
        cases.add(new Case("【某银行】您的验证码是 123456，5分钟内有效，请勿泄露", null));
        cases.add(new Case("【某商城】恭喜获得取件码优惠券，满100减20", null));
        cases.add(new Case("【某运营商】您的话费余额为 88.50 元，点击充值", null));
        cases.add(new Case("【某停车】您的停车码为 223344，缴费后离场", null));

        int pass = 0, fail = 0;
        for (Case cs : cases) {
            @SuppressWarnings("unchecked")
            List<String> got = (List<String>) extract.invoke(null, cs.body);
            String g = got.isEmpty() ? null : got.get(0);
            boolean ok;
            if (cs.expectCode == null) ok = got.isEmpty();
            else ok = cs.expectCode.equals(g);
            if (ok) pass++; else fail++;
            System.out.println((ok ? "PASS" : "FAIL") + "  期望=" + cs.expectCode + "  实际=" + got);
            if (!ok) System.out.println("       输入: " + cs.body);
        }
        System.out.println();
        System.out.println("TOTAL=" + cases.size() + " PASS=" + pass + " FAIL=" + fail);
    }
}
