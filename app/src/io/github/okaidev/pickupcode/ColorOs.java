package io.github.okaidev.pickupcode;

import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * ColorOS / OPPO 系机型的识别与定位（v4.0.3）。
 *
 * <p><b>为什么单独一个类</b>：{@link ColorOsProviderWake} 里引用了 LSPosed 的
 * {@code XposedHelpers}，那个类只在注入环境里存在。App 自己的进程要判断
 * 「我是不是 ColorOS」时不能碰它，否则 App 一启动就 {@code NoClassDefFoundError}。
 * 所以把「纯判断」抽到这里，谁都能用。
 *
 * <p><b>为什么要判断</b>：ColorOS 的 Provider 启动门禁是它独有的实现，
 * 其他系统根本没有这个类。体检里如果对所有人都加一项「ColorOS 唤醒」，
 * HyperOS 用户会看到一条与自己无关的红字——所以只在 ColorOS 上显示。
 */
public final class ColorOs {

    private ColorOs() { }

    private static final String TAG = "PICKUPDEBUG";

    /** Provider 门禁类名（ColorOS 的 {@code ContentProviderHelperExtImpl} 内部类/方法都挂它） */
    public static final String GATE_CLASS_SIMPLE = "ContentProviderHelperExtImpl";
    public static final String GATE_CLASS_NAME = "com.android.server.am." + GATE_CLASS_SIMPLE;

    /**
     * 可能装着门禁类的 jar。
     *
     * <p>ColorOS 把系统扩展放在 {@code /system_ext} 或 {@code /my_product}，
     * 不同版本路径不一样，所以列几个候选依次试。
     */
    private static final String[] CANDIDATE_JARS = {
            "/system_ext/framework/oplus-services.jar",
            "/system/framework/oplus-services.jar",
            "/my_product/framework/oplus-services.jar",
            "/system_ext/framework/oplus-framework.jar",
            "/system/framework/oplus-framework.jar",
            "/my_product/framework/oplus-framework.jar",
            "/system_ext/framework/oplus-telephony.jar"
    };

    /**
     * 是不是 ColorOS / OPPO 系（含 OnePlus、realme，它们共用 ColorOS 内核）。
     *
     * <p>只看厂商字段不够：OPPO 也有跑原生 Android 的机型；
     * 反过来有些realme机型的 MANUFACTURER 是 "realme"、BRAND 是 "OPPO"。
     * 所以「厂商命中」+「系统里真有 oplus 的 framework 目录」两条一起判。
     */
    public static boolean isColorOs() {
        String mf = safeLower(Build.MANUFACTURER);
        String br = safeLower(Build.BRAND);
        boolean vendorHit = mf.contains("oppo") || mf.contains("oneplus")
                || mf.contains("realme") || br.contains("oppo")
                || br.contains("oneplus") || br.contains("realme");
        if (!vendorHit) return false;
        return new File("/system_ext/framework").isDirectory()
                || new File("/my_product/framework").isDirectory()
                || new File("/system_ext/framework/oplus-services.jar").exists();
    }

    /** Static archive evidence only; it does not prove hook installation or a successful wake. */
    public static final class GateScan {
        public final String jarPath;
        public final boolean complete;

        private GateScan(String jarPath, boolean complete) {
            this.jarPath = jarPath;
            this.complete = complete;
        }
    }

    /** Compatibility wrapper for callers which only need the located JAR path. */
    public static String findGateJar() {
        return scanGateJars().jarPath;
    }

    /**
     * App processes cannot use the system_server ClassLoader. Inspect exact class definitions
     * in .class entries or classes*.dex instead; a descriptor reference alone is not evidence.
     * Unreadable, malformed, optimized-only or unsupported archives are not a proof of absence.
     */
    public static GateScan scanGateJars() {
        Set<String> seen = new HashSet<>();
        boolean complete = true;
        int inspected = 0;
        for (String path : CANDIDATE_JARS) {
            File file = new File(path);
            seen.add(file.getAbsolutePath());
            if (!file.isFile()) continue;
            inspected++;
            try {
                if (containsGate(path)) return new GateScan(path, true);
            } catch (IOException | SecurityException e) {
                complete = false;
                Log.w(TAG, "scan jar incomplete: " + path + " " + e);
            }
        }
        File[] dirs = {new File("/system/framework"), new File("/system_ext/framework"),
                new File("/my_product/framework")};
        for (File dir : dirs) {
            File[] files = dir.listFiles();
            if (files == null) {
                if (dir.isDirectory()) complete = false;
                continue;
            }
            for (File file : files) {
                String name = file.getName();
                if (!name.endsWith(".jar") || (!name.contains("oplus") && !name.contains("services"))) continue;
                String path = file.getAbsolutePath();
                if (!file.isFile() || !seen.add(path)) continue;
                inspected++;
                try {
                    if (containsGate(path)) return new GateScan(path, true);
                } catch (IOException | SecurityException e) {
                    complete = false;
                    Log.w(TAG, "scan jar incomplete: " + path + " " + e);
                }
            }
        }
        return new GateScan(null, complete && inspected > 0);
    }

    /** Package-private so offline tests can use generated archives, not proprietary ROM files. */
    static boolean containsGate(String jarPath) throws IOException {
        return ArchiveClassProbe.containsClass(new File(jarPath), GATE_CLASS_NAME);
    }

    private static String safeLower(String s) {
        return s == null ? "" : s.toLowerCase();
    }
}
