import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** 全量回归：码 + 地点，覆盖本轮所有改动 */
public class FullReg {

    static final String[] NEED = {
            "ExtractorRules.java", "PickupExtractor.java", "UserRules.java",
            "TodoWriter.java", "MissedSmsStore.java", "NotesBackend.java",
            "Repair.java", "Diagnostics.java"
    };

    static class C {
        String code, place, body;
        C(String code, String place, String body) { this.code = code; this.place = place; this.body = body; }
    }

    public static void main(String[] args) throws Exception {
        String repo = "D:\\project\\Xiaomi-HyperOs-pickup-code-grabber";
        Path srcDir = Paths.get(repo, "app", "src", "io", "github", "okaidev", "pickupcode");
        Path tmp = Files.createTempDirectory("full");
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
        Method extract = k.getMethod("extract", String.class);
        Method place = k.getMethod("extractPlace", String.class);

        List<C> cases = new ArrayList<>();
        // ---- 本轮三条新短信 ----
        cases.add(new C("6-4-3009", "—", "包裹待签收【取件码-6-4-3009】，手机号已加密，享号码保护服务，点击查看详情>>"));
        cases.add(new C("4-3-5120", null, "【驿小哥】您的快递圆通，已到江南绿城西门北50米顺丰速运驿收发，凭4-3-5120免费取，如有疑问可到店咨询，谢谢。"));
        cases.add(new C("6-1-2233", null, "【驿收发】您的圆通快递已到站，凭6-1-2233免费取件，地址：幸福小区南门驿收发"));
        // ---- 既有语料（码 + 地点）----
        cases.add(new C("22-2-3579", null, "【丰巢】凭22-2-3579到华润万家旁丰巢柜取件"));
        cases.add(new C("2-2-7508", null, "【妈妈驿站】取货码2-2-7508，您有极兔快递包裹，已到新创村沙咀里132号"));
        cases.add(new C("4-6-7602", null, "【菜鸟驿站】您的包裹已到站，凭4-6-7602到新郑龙湖富田兴龙湾31号楼店取件。"));
        cases.add(new C("2-3-05025", null, "【兔喜生活】您有包裹已到达兴龙湾30栋兔喜店，取件码为2-3-05025，地址:兴龙湾30号楼102"));
        cases.add(new C("72778", null, "【菜鸟驿站】请23:59前到站凭\"72778\"到佛山桂城南约六区185号店站点领取您的顺丰*72778包裹"));
        cases.add(new C("8582", null, "【丰巢】您有包裹到巢，取件码 8582，地址某某路"));
        cases.add(new C("A88123", null, "【中邮驿站】您的邮政包裹已到，取件码A88123，请及时领取"));
        cases.add(new C("4-2-02015", null, "【中通快递】凭4-2-02015到森泰首府快递驿站取尾号6963包裹。"));
        cases.add(new C("109-6-4006", null, "【中通快递】请凭109-6-4006到理工东苑大门左转快递服务中心二楼取件"));
        cases.add(new C("267961", null, "【妈妈驿站】凭取件码267961至化工镇云水村委文化广场东侧小平房取件。有疑问联系快递员。"));
        cases.add(new C("7652-5845", null, "【递管家】您的快递:*37240已到悦陇府洋房门口快递柜，请用提货码7652-5845取包裹，咨询快递员"));
        cases.add(new C("9-2-7788", null, "【快宝驿站】您的包裹已到快宝驿站，取件码9-2-7788，可免费取件，请及时领取"));
        // ---- 反例：必须为空 ----
        cases.add(new C(null, null, "【某银行】您的验证码是 123456，5分钟内有效，请勿泄露"));
        cases.add(new C(null, null, "【某商城】恭喜获得取件码优惠券，满100减20"));
        cases.add(new C(null, null, "【某运营商】您的话费余额为 88.50 元，点击充值"));
        cases.add(new C(null, null, "【某停车】您的停车码为 223344，缴费后离场"));
        cases.add(new C(null, null, "【拼多多】您的商品在代收点已超6小时未取，为避免鲜活商品损坏，请尽快取件。"));

        int pass = 0, fail = 0;
        for (C cs : cases) {
            @SuppressWarnings("unchecked")
            List<String> got = (List<String>) extract.invoke(null, cs.body);
            String g = got.isEmpty() ? null : got.get(0);
            String p = (String) place.invoke(null, cs.body);

            boolean codeOk = cs.code == null ? got.isEmpty() : cs.code.equals(g);
            boolean placeOk = cs.place == null || cs.place.equals(p);
            boolean ok = codeOk && placeOk;
            if (ok) pass++; else fail++;
            System.out.println((ok ? "PASS" : "FAIL")
                    + "  码期望=" + cs.code + " 实际=" + got
                    + "  | 地点期望=" + cs.place + " 实际=" + p);
            if (!ok) System.out.println("       " + cs.body);
        }
        System.out.println();
        System.out.println("TOTAL=" + cases.size() + " PASS=" + pass + " FAIL=" + fail);
    }
}
