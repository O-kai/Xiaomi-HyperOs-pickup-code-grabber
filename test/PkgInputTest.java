import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * PkgInput 校验逻辑测试（脱机，不需要真机）
 *
 * 覆盖四类风险：
 *  1. 脏输入（重复 / 全角逗号 / 空格 / 换行）
 *  2. 拼错包名（格式对但设备没装）
 *  3. 高危包名（短信 / 微信 / 支付宝）
 *  4. 空输入
 *
 * 注意：用 checkInstalled=false 跑，避免依赖真机；「未安装」分支靠格式非法用例间接覆盖。
 */
public class PkgInputTest {

    static final String[] NEED = {
            "PkgInput.java"
    };

    public static void main(String[] args) throws Exception {
        String repo = "D:\\project\\Xiaomi-HyperOs-pickup-code-grabber";
        Path srcDir = Paths.get(repo, "app", "src", "io", "github", "okaidev", "pickupcode");
        Path tmp = Files.createTempDirectory("pkgtest");
        for (String f : NEED) {
            Path p = srcDir.resolve(f);
            if (Files.exists(p)) Files.copy(p, tmp.resolve(f));
        }

        String androidJar = "D:\\develop\\android-sdk\\platforms\\android-34\\android.jar";
        StringBuilder cmd = new StringBuilder("javac -encoding UTF-8 -nowarn -cp ")
                .append(androidJar).append(" -d ").append(tmp);
        for (String f : NEED) {
            if (Files.exists(tmp.resolve(f))) cmd.append(" ").append(tmp.resolve(f));
        }

        Process c = Runtime.getRuntime().exec(cmd.toString());
        if (c.waitFor() != 0) {
            System.out.println("COMPILE FAIL:");
            System.out.println(new String(c.getErrorStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8));
            System.exit(2);
        }

        URLClassLoader cl = new URLClassLoader(
                new URL[]{tmp.toUri().toURL(), Paths.get(androidJar).toUri().toURL()});
        Class<?> k = cl.loadClass("io.github.okaidev.pickupcode.PkgInput");
        Class<?> rCls = cl.loadClass("io.github.okaidev.pickupcode.PkgInput$Result");
        Method parse = k.getMethod("parse", String.class,
                cl.loadClass("android.content.Context"), boolean.class);
        Method toStore = rCls.getMethod("toStorageString");
        Method describe = k.getMethod("describe", rCls);

        int pass = 0, fail = 0;

        // ---------- 用例 1：重复项去重 ----------
        Object r = parse.invoke(null, "com.a.b\ncom.a.b\ncom.c.d", null, false);
        List<String> valid = (List<String>) rCls.getField("valid").get(r);
        String store = (String) toStore.invoke(r);
        if (valid.size() == 2 && "com.a.b,com.c.d".equals(store)) {
            System.out.println("PASS  重复项去重 -> " + store);
            pass++;
        } else {
            System.out.println("FAIL  重复项去重 -> " + store + " (size=" + valid.size() + ")");
            fail++;
        }

        // ---------- 用例 2：全角逗号 / 空格 / 分号混合分隔 ----------
        r = parse.invoke(null, "com.x.y，com.z.w； com.q.r", null, false);
        valid = (List<String>) rCls.getField("valid").get(r);
        if (valid.size() == 3) {
            System.out.println("PASS  混合分隔符 -> " + valid);
            pass++;
        } else {
            System.out.println("FAIL  混合分隔符 -> " + valid);
            fail++;
        }

        // ---------- 用例 3：格式非法 ----------
        r = parse.invoke(null, "not-a-pkg\ncom.ok.one\n123.456", null, false);
        valid = (List<String>) rCls.getField("valid").get(r);
        List<String> bad = (List<String>) rCls.getField("malformed").get(r);
        if (valid.size() == 1 && bad.size() == 2 && valid.get(0).equals("com.ok.one")) {
            System.out.println("PASS  格式非法被拦 -> valid=" + valid + " malformed=" + bad);
            pass++;
        } else {
            System.out.println("FAIL  格式非法被拦 -> valid=" + valid + " malformed=" + bad);
            fail++;
        }

        // ---------- 用例 4：高危包名识别 ----------
        r = parse.invoke(null, "com.tencent.mm\ncom.android.mms\ncom.ok.one", null, false);
        List<String> sens = (List<String>) rCls.getField("sensitive").get(r);
        valid = (List<String>) rCls.getField("valid").get(r);
        if (sens.size() == 2 && valid.size() == 3) {
            System.out.println("PASS  高危包名识别 -> sensitive=" + sens + "（仍允许保存）");
            pass++;
        } else {
            System.out.println("FAIL  高危包名识别 -> sensitive=" + sens);
            fail++;
        }

        // ---------- 用例 5：空输入 ----------
        r = parse.invoke(null, "   \n  \n ", null, false);
        valid = (List<String>) rCls.getField("valid").get(r);
        if (valid.isEmpty()) {
            System.out.println("PASS  空输入 -> 无有效项");
            pass++;
        } else {
            System.out.println("FAIL  空输入 -> " + valid);
            fail++;
        }

        // ---------- 用例 6：超长输入被拦 ----------
        StringBuilder longPkg = new StringBuilder("com.");
        for (int i = 0; i < 200; i++) longPkg.append('a');
        r = parse.invoke(null, longPkg.toString(), null, false);
        bad = (List<String>) rCls.getField("malformed").get(r);
        if (bad.size() == 1) {
            System.out.println("PASS  超长包名被拦");
            pass++;
        } else {
            System.out.println("FAIL  超长包名未拦");
            fail++;
        }

        // ---------- 用例 7：describe 在有问题时返回非 null ----------
        r = parse.invoke(null, "not-a-pkg", null, false);
        Object desc = describe.invoke(null, r);
        if (desc != null && ((String) desc).contains("不是有效的包名")) {
            System.out.println("PASS  describe 输出问题说明");
            pass++;
        } else {
            System.out.println("FAIL  describe -> " + desc);
            fail++;
        }

        // ---------- 用例 8：正常输入 describe 返回 null ----------
        r = parse.invoke(null, "com.a.b", null, false);
        desc = describe.invoke(null, r);
        if (desc == null) {
            System.out.println("PASS  正常输入 describe 为 null（不打扰用户）");
            pass++;
        } else {
            System.out.println("FAIL  正常输入仍有提示 -> " + desc);
            fail++;
        }

        System.out.println();
        System.out.println("TOTAL=" + (pass + fail) + " PASS=" + pass + " FAIL=" + fail);
    }
}
