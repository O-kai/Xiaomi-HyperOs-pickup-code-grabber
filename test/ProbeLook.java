import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 诊断 lookLikePickupSms 为何返回 false */
public class ProbeLook {

    static final String[] NEED = {
            "ExtractorRules.java", "PickupExtractor.java", "UserRules.java",
            "TodoWriter.java", "MissedSmsStore.java", "NotesBackend.java",
            "Repair.java", "Diagnostics.java"
    };

    public static void main(String[] args) throws Exception {
        String body = args.length > 0 ? args[0]
                : "包裹待签收【取件码-6-4-3009】，手机号已加密，享号码保护服务，点击查看详情>>";
        String repo = "D:\\project\\Xiaomi-HyperOs-pickup-code-grabber";
        Path srcDir = Paths.get(repo, "app", "src", "io", "github", "okaidev", "pickupcode");
        Path tmp = Files.createTempDirectory("pl");
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

        Object r = k.getMethod("activeRules").invoke(null);
        Class<?> rc = r.getClass();

        Pattern pFeat = (Pattern) rc.getField("patternFeature").get(r);
        Pattern pAny = (Pattern) rc.getField("patternAnyToken").get(r);

        System.out.println("body = " + body);
        System.out.println();
        System.out.println("patternFeature 命中 = " + pFeat.matcher(body).find());
        System.out.println("patternAnyToken 命中 = " + pAny.matcher(body).find());
        System.out.println();
        System.out.println("patternAnyToken 的全部候选：");
        Matcher m = pAny.matcher(body);
        while (m.find()) {
            System.out.println("   [" + m.group(1) + "]  位置 " + m.start() + "-" + m.end());
        }
        System.out.println();
        Method valid = k.getMethod("validCode", rc, String.class);
        m = pAny.matcher(body);
        while (m.find()) {
            System.out.println("   " + m.group(1) + "  validCode=" + valid.invoke(null, r, m.group(1)));
        }
    }
}
