import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class RulesReg2 {
    /** 生成 xposed api 编译桩（仅回归编译用） */
    private static void writeStub(Path dir) throws Exception {
        String xbridge = "package de.robv.android.xposed;\n"
                + "public class XposedBridge { public static void log(String s){} public static void hookAllMethods(Class<?> c, String n, Object h){} }\n";
        String xhelpers = "package de.robv.android.xposed;\n"
                + "public class XposedHelpers { public static Class<?> findClass(String n, ClassLoader cl){ return null; } public static Object getObjectField(Object o, String f){ return null; } }\n";
        String callback = "package de.robv.android.xposed.callbacks;\n"
                + "public class XC_LoadPackage {}\n";
        String xc = "package de.robv.android.xposed;\n"
                + "public abstract class XC_MethodHook { public static class MethodHookParam { public Object[] args; public Object thisObject; } }\n";
        String entry = "package de.robv.android.xposed;\n"
                + "public interface IXposedHookLoadPackage { void handleLoadPackage(Object p) throws Throwable; }\n";
        String lp = "package de.robv.android.xposed.callbacks;\n"
                + "public class XC_LoadPackageParam {}\n";
        Files.write(dir.resolve("XposedBridge.java"), xbridge.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.write(dir.resolve("XposedHelpers.java"), xhelpers.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.write(dir.resolve("XC_MethodHook.java"), xc.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.write(dir.resolve("IXposedHookLoadPackage.java"), entry.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.write(dir.resolve("XC_LoadPackage.java"), callback.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.write(dir.resolve("XC_LoadPackageParam.java"), lp.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static void main(String[] a) throws Exception {
        Path srcDir = Paths.get("D:\\project\\Xiaomi-HyperOs-pickup-code-grabber\\app\\src\\io\\github\\okaidev\\pickupcode");
        Path tmp = Files.createTempDirectory("reg2");
        String aj = "D:\\project\\_build-tools\\sdk\\platforms\\android-34\\android.jar";
        // 全量编译 src 目录（带 xposed api stub —— NotiHook/NotiRelay/XposedEntry 需要）
        Path xposedStub = Paths.get("D:\\project\\Xiaomi-HyperOs-pickup-code-grabber\\test\\xposed-stub");
        if (!Files.exists(xposedStub)) { Files.createDirectories(xposedStub); }
        writeStub(xposedStub);
        StringBuilder cmdB = new StringBuilder("javac -encoding UTF-8 -cp ").append(aj)
                .append(";").append(xposedStub)
                .append(" -d ").append(tmp);
        try (java.util.stream.Stream<Path> s = Files.list(srcDir)) {
            s.filter(p -> p.toString().endsWith(".java")).forEach(p -> cmdB.append(" ").append(p));
        }
        Process c = Runtime.getRuntime().exec(cmdB.toString());
        if (c.waitFor() != 0) {
            Files.write(Paths.get("reg2-err.txt"), c.getErrorStream().readAllBytes());
            System.out.println("COMPILE FAIL -> reg2-err.txt"); System.exit(2);
        }
        if (c.waitFor() != 0) {
            Files.write(Paths.get("reg2-err.txt"), c.getErrorStream().readAllBytes());
            System.out.println("COMPILE FAIL -> reg2-err.txt"); System.exit(2);
        }
        URLClassLoader cl = new URLClassLoader(new URL[]{tmp.toUri().toURL(), Paths.get(aj).toUri().toURL()});
        Class<?> kx = cl.loadClass("io.github.okaidev.pickupcode.PickupExtractor");
        Method init = kx.getMethod("init", cl.loadClass("android.content.Context"));
        init.invoke(null, new Object[]{null});
        Method extract = kx.getMethod("extract", String.class);
        Method place = kx.getMethod("extractPlace", String.class);
        Class<?> tw = cl.loadClass("io.github.okaidev.pickupcode.TodoWriter");
        Method source = tw.getMethod("resolveSource", String.class, String.class);
        Method smoke = kx.getMethod("runSmokeTest", cl.loadClass("io.github.okaidev.pickupcode.ExtractorRules"));

        Object def = cl.loadClass("io.github.okaidev.pickupcode.ExtractorRules").getMethod("createDefault").invoke(null);
        boolean smokeOk = (boolean) smoke.invoke(null, def);
        StringBuilder sb = new StringBuilder("SMOKE: " + (smokeOk ? "PASS" : "FAIL") + "\n\n");

        // 26 条码回归
        java.util.List<String> lines = Files.readAllLines(Paths.get("D:\\project\\Xiaomi-HyperOs-pickup-code-grabber\\test\\sms-cases.txt"), java.nio.charset.StandardCharsets.UTF_8);
        int cp = 0, cf = 0;
        sb.append("== CODES ==\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] p = line.split("\\|", 3);
            if (p.length < 3) continue;
            String exp = p[1].trim();
            java.util.List<String> got = (java.util.List<String>) extract.invoke(null, p[2].trim());
            String gs = String.join(";", got);
            boolean ok = ("-".equals(exp) && got.isEmpty()) || gs.equals("-".equals(exp) ? "" : exp);
            if (ok) { cp++; sb.append("PASS ").append(p[0].trim()).append(" [").append(gs).append("]\n"); }
            else { cf++; sb.append("FAIL ").append(p[0].trim()).append(" 期望[").append(exp).append("] 实得[").append(gs).append("]\n"); }
        }

        // 13 条地点 + 13 条来源
        String[][] places = {
            {"【菜鸟驿站】测试包裹：取件码为99-9-1234，请到XX小区快递驿站取件（测试条目，可删除）", "XX小区快递驿站"},
            {"【妈妈驿站】取货码5-5-9-13，您有包裹YT764306505052已到苍山下坡圆通快递", "苍山下坡圆通快递"},
            {"【申通快递】请凭9-7-0993到苍山老菜场斜对面申通快递取您的快递，详询代收驿站", "苍山老菜场斜对面申通快递"},
            {"【菜鸟驿站】您的包裹已到站，凭4-6-7602到新郑龙湖富田兴龙湾31号楼店取件。", "新郑龙湖富田兴龙湾31号楼店"},
            {"【兔喜生活】您有包裹已到达兴龙湾30栋兔喜店，取件码为2-3-05025，地址:兴龙湾30号楼102", "兴龙湾30栋兔喜店"},
            {"【中通快递】凭M-2-5341到福源折扣店取尾号9100包裹", "福源折扣店"},
            {"【妈妈驿站】取货码D4-7048，您有圆通快递包裹，已到天和人家快递点", "天和人家快递点"},
            {"【欢猫驿站】您的韵达快递包裹已到天和人家小区门面房，请凭D3-7508取件", "天和人家小区门面房"},
            {"【兔喜生活】您有包裹已到达天和人家快递店，取件码为S3-2043，地址:天和人家门口门面房", "天和人家快递店"},
            {"【兔喜生活】您有包裹已到达天和人家快递店，取件码为D2-7762，地址:天和人家门口门面房", "天和人家快递店"},
            {"【中通快递】凭4-2-02015到森泰首府快递驿站取尾号6963包裹。", "森泰首府快递驿站"},
            {"【中通快递】请凭109-6-4006到理工东苑大门左转快递服务中心二楼取件，地址：理工东苑大门左转快递服务中心二楼。", "理工东苑大门左转快递服务中心二楼"},
            {"【妈妈驿站】凭取件码267961至化工镇云水村委文化广场东侧小平房取件。有疑问联系快递员。", "化工镇云水村委文化广场东侧小平房"},
        };
        String[][] sources = {
            {"【中通快递】凭M-2-5341到福源折扣店取尾号9100包裹", "中通快递"},
            {"【妈妈驿站】取货码D4-7048，您有圆通快递包裹，已到天和人家快递点", "妈妈驿站"},
            {"【欢猫驿站】您的韵达快递包裹已到天和人家小区门面房，请凭D3-7508取件", "欢猫驿站"},
            {"【兔喜生活】您有包裹已到达天和人家快递店，取件码为S3-2043，地址:天和人家门口门面房", "兔喜生活"},
            {"【兔喜生活】您有包裹已到达天和人家快递店，取件码为D2-7762，地址:天和人家门口门面房", "兔喜生活"},
            {"【中通快递】凭4-2-02015到森泰首府快递驿站取尾号6963包裹。", "中通快递"},
            {"【中通快递】请凭109-6-4006到理工东苑大门左转快递服务中心二楼取件，地址：理工东苑大门左转快递服务中心二楼。", "中通快递"},
            {"【妈妈驿站】凭取件码267961至化工镇云水村委文化广场东侧小平房取件。有疑问联系快递员。", "妈妈驿站"},
            {"【菜鸟驿站】您的包裹已到站，凭4-6-7602到新郑龙湖富田兴龙湾31号楼店取件。", "菜鸟驿站"},
            {"【申通快递】请凭9-7-0993到苍山老菜场斜对面申通快递取您的快递，详询代收驿站", "申通快递"},
        };
        int pp = 0, pf = 0, sp = 0, sf = 0;
        sb.append("\n== PLACE ==\n");
        for (String[] tc : places) {
            String got = (String) place.invoke(null, tc[0]);
            if (got.equals(tc[1])) { pp++; sb.append("PASS [").append(got).append("]\n"); }
            else { pf++; sb.append("FAIL 期望[").append(tc[1]).append("] 实得[").append(got).append("]\n"); }
        }
        sb.append("\n== SOURCE ==\n");
        for (String[] tc : sources) {
            String got = (String) source.invoke(null, "10086", tc[0]);
            if (got.equals(tc[1])) { sp++; sb.append("PASS [").append(got).append("]\n"); }
            else { sf++; sb.append("FAIL 期望[").append(tc[1]).append("] 实得[").append(got).append("]\n"); }
        }
        sb.append("\nSMOKE=").append(smokeOk).append("\nCODES ").append(cp).append("/").append(cp + cf)
          .append(" PLACE ").append(pp).append("/").append(pp + pf)
          .append(" SOURCE ").append(sp).append("/").append(sp + sf).append("\n");
        Files.write(Paths.get("reg2-result.txt"), sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        System.out.println("done");
        System.exit((cf == 0 && pf == 0 && sf == 0 && smokeOk) ? 0 : 1);
    }
}
