import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 规则探针：验证单条短信在四层引擎里的实际走向。
 * 用法：java ProbeRule "<短信原文>" [仓库路径]
 */
public class ProbeRule {

    static final String[] NEED = {
            "ExtractorRules.java", "PickupExtractor.java", "UserRules.java",
            "TodoWriter.java", "MissedSmsStore.java", "NotesBackend.java",
            "Repair.java", "Diagnostics.java"
    };

    public static void main(String[] args) throws Exception {
        String body = args.length > 0 ? args[0]
                : "【驿小哥】您的快递圆通，已到江南绿城西门北50米顺丰速运驿收发，凭4-3-5120免费取，如有疑问可到店咨询，谢谢。";
        String repo = args.length > 1 ? args[1] : "D:\\project\\Xiaomi-HyperOs-pickup-code-grabber";

        Path srcDir = Paths.get(repo, "app", "src", "io", "github", "okaidev", "pickupcode");
        Path tmp = Files.createTempDirectory("probe");
        for (String f : NEED) Files.copy(srcDir.resolve(f), tmp.resolve(f));

        String androidJar = System.getenv().getOrDefault("ANDROID_JAR",
                "D:\\develop\\android-sdk\\platforms\\android-34\\android.jar");

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
        Class<?> ctxCls = cl.loadClass("android.content.Context");
        k.getMethod("init", ctxCls).invoke(null, new Object[]{null});

        Method lookLike = k.getMethod("lookLikePickupSms", String.class);
        Method hasFeat = k.getMethod("hasFeatureWords", String.class);
        Method extract = k.getMethod("extract", String.class);
        Method extractPlace = k.getMethod("extractPlace", String.class);
        Method getRules = k.getMethod("activeRules");

        System.out.println("=== 输入 ===");
        System.out.println(body);
        System.out.println();

        boolean feat = (Boolean) hasFeat.invoke(null, body);
        boolean look = (Boolean) lookLike.invoke(null, body);
        System.out.println("特征词门禁 hasFeatureWords : " + feat);
        System.out.println("形状门禁   lookLikePickupSms: " + look);

        @SuppressWarnings("unchecked")
        List<String> codes = (List<String>) extract.invoke(null, body);
        System.out.println("提取码     extract          : " + codes);
        System.out.println("提取地点   extractPlace     : " + extractPlace.invoke(null, body));

        // 反射出规则对象，逐层手工验证
        Object r = getRules.invoke(null);
        if (r != null) {
            System.out.println();
            System.out.println("=== 分层诊断 ===");
            java.util.regex.Pattern pFeat = (java.util.regex.Pattern) r.getClass().getField("patternFeature").get(r);
            java.util.regex.Pattern pKw = (java.util.regex.Pattern) r.getClass().getField("patternKeyword").get(r);
            java.util.regex.Pattern pBy = (java.util.regex.Pattern) r.getClass().getField("patternBy").get(r);
            java.util.regex.Pattern pC = (java.util.regex.Pattern) r.getClass().getField("patternCodeToTake").get(r);
            java.util.regex.Pattern pAny = (java.util.regex.Pattern) r.getClass().getField("patternAnyToken").get(r);
            java.util.regex.Pattern pNeg = (java.util.regex.Pattern) r.getClass().getField("patternNegative").get(r);

            System.out.println("Tier0 特征词命中 : " + (pFeat != null && pFeat.matcher(body).find()));
            System.out.println("TierA 锚点命中   : " + (pKw != null && pKw.matcher(body).find()));
            if (pKw != null) {
                java.util.regex.Matcher m = pKw.matcher(body);
                while (m.find()) System.out.println("        → group1=" + m.group(1));
            }
            System.out.println("TierB 凭/出示命中: " + (pBy != null && pBy.matcher(body).find()));
            if (pBy != null) {
                java.util.regex.Matcher m = pBy.matcher(body);
                while (m.find()) System.out.println("        → group1=" + m.group(1));
            }
            System.out.println("TierC 夹取命中   : " + (pC != null && pC.matcher(body).find()));
            System.out.println("TierD 负向命中   : " + (pNeg != null && pNeg.matcher(body).find()));
            System.out.println("TierD 候选 token :");
            if (pAny != null) {
                java.util.regex.Matcher m = pAny.matcher(body);
                while (m.find()) System.out.println("        → " + m.group(1));
            }
        }
    }
}
