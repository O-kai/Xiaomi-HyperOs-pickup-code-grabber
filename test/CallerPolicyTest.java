package io.github.okaidev.pickupcode;

/**
 * 纯 JVM 测试：CallerPolicy 的包名白名单。
 * 不依赖 Android 运行时（android.jar 仅提供类型声明）。
 *
 * 运行：
 *   javac -encoding UTF-8 -cp %ANDROID_JAR% -d test/out \
 *     app/src/io/github/okaidev/pickupcode/CallerPolicy.java test/CallerPolicyTest.java
 *   java -cp test/out io.github.okaidev.pickupcode.CallerPolicyTest
 */
public class CallerPolicyTest {

    public static void main(String[] args) {
        int pass = 0;
        int fail = 0;

        String[] trusted = {
                "com.android.mms",
                "com.miui.mms",
                "com.oplus.mms",
                "com.coloros.mms",
                "com.android.phone",
                "com.android.providers.telephony"
        };
        for (String pkg : trusted) {
            if (CallerPolicy.isTrustedSmsPackage(pkg)) {
                pass++;
                System.out.println("PASS trusted " + pkg);
            } else {
                fail++;
                System.out.println("FAIL trusted " + pkg);
            }
        }

        String[] untrusted = {
                "com.evil",
                "com.android.mms.fake",
                "com.tencent.mm",
                "",
                null
        };
        for (String pkg : untrusted) {
            if (!CallerPolicy.isTrustedSmsPackage(pkg)) {
                pass++;
                System.out.println("PASS rejected " + pkg);
            } else {
                fail++;
                System.out.println("FAIL rejected " + pkg);
            }
        }

        System.out.println("TOTAL: PASS=" + pass + " FAIL=" + fail);
        if (fail != 0) {
            System.exit(1);
        }
    }
}
