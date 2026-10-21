import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 深度诊断：复刻 TierD 评分逻辑，逐步打印每一步。
 */
public class ProbeTierD {

    static final String[] NEED = {
            "ExtractorRules.java", "PickupExtractor.java", "UserRules.java",
            "TodoWriter.java", "MissedSmsStore.java", "NotesBackend.java",
            "Repair.java", "Diagnostics.java"
    };

    public static void main(String[] args) throws Exception {
        String body = args.length > 0 ? args[0]
                : "【驿小哥】您的快递圆通，已到江南绿城西门北50米顺丰速运驿收发，凭4-3-5120免费取，如有疑问可到店咨询，谢谢。";
        String repo = "D:\\project\\Xiaomi-HyperOs-pickup-code-grabber";

        Path srcDir = Paths.get(repo, "app", "src", "io", "github", "okaidev", "pickupcode");
        Path tmp = Files.createTempDirectory("ptd");
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

        Object r = k.getMethod("activeRules").invoke(null);
        Class<?> rc = r.getClass();

        Pattern pAny = (Pattern) rc.getField("patternAnyToken").get(r);
        Pattern pFeat = (Pattern) rc.getField("patternFeature").get(r);
        Pattern pAct = (Pattern) rc.getField("patternAction").get(r);
        Pattern pDash = (Pattern) rc.getField("patternDashShape").get(r);
        Pattern pNeg = (Pattern) rc.getField("patternNegative").get(r);

        Method validCode = k.getMethod("validCode", rc, String.class);
        Method isExcluded = k.getDeclaredMethod("isExcludedToken", rc, String.class, int.class, int.class, String.class);
        isExcluded.setAccessible(true);
        Method window = k.getDeclaredMethod("window", String.class, int.class, int.class, int.class);
        window.setAccessible(true);

        System.out.println("=== TierD 评分复刻 ===");
        System.out.println("负向闸门命中 = " + pNeg.matcher(body).find());
        System.out.println();

        Matcher m = pAny.matcher(body);
        while (m.find()) {
            String tok = m.group(1);
            boolean valid = (Boolean) validCode.invoke(null, r, tok);
            if (!valid) continue;
            boolean excl = (Boolean) isExcluded.invoke(null, r, tok, m.start(), m.end(), body);
            String win = (String) window.invoke(null, body, m.start(), m.end(), 24);
            int score = 0;
            boolean f = pFeat.matcher(win).find();
            boolean a = pAct.matcher(win).find();
            boolean d = pDash.matcher(tok).matches();
            boolean longNum = tok.matches("\\d{6,9}");
            if (f) score += 2;
            if (a) score += 2;
            if (d) score += 1;
            if (longNum) score += 1;

            System.out.println("token = " + tok);
            System.out.println("  validCode      = " + valid);
            System.out.println("  isExcluded     = " + excl);
            System.out.println("  window(24)     = [" + win + "]");
            System.out.println("  特征词命中     = " + f + " (+2)");
            System.out.println("  动作词命中     = " + a + " (+2)");
            System.out.println("  横线码形       = " + d + " (+1)");
            System.out.println("  长数字         = " + longNum + " (+1)");
            System.out.println("  TOTAL SCORE    = " + score + "  (需要 >=4)");
            System.out.println();
        }
    }
}
