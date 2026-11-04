package io.github.okaidev.pickupcode;

import android.os.Build;
import android.util.Log;

import java.io.File;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

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

    /**
     * 找到装着门禁类的那个 jar。
     *
     * <p>为什么要「找 jar」而不是直接反射类：App 进程里没有 system_server 的
     * ClassLoader，反射一定失败。而 jar 是普通文件，用 {@link ZipFile} 只读
     * 条目名就能判断类在不在——这是不需要 root 也能做的确定性检查。
     *
     * @return jar 路径；没找到返回 null
     */
    public static String findGateJar() {
        for (String p : CANDIDATE_JARS) {
            if (containsGate(p)) return p;
        }
        // 候选都没命中：退一步扫目录，只认名字里带 oplus/services 的 jar，
        // 避免把几十个无关 jar 全打开（真机上 framework 目录能有一两百个文件）。
        File[] dirs = {new File("/system_ext/framework"), new File("/my_product/framework")};
        for (File d : dirs) {
            File[] fs = d.listFiles();
            if (fs == null) continue;
            for (File f : fs) {
                String n = f.getName();
                if (!n.endsWith(".jar")) continue;
                if (!n.contains("oplus") && !n.contains("services")) continue;
                if (containsGate(f.getAbsolutePath())) return f.getAbsolutePath();
            }
        }
        return null;
    }

    /** jar 里有没有 {@code ContentProviderHelperExtImpl.class} 这个条目 */
    private static boolean containsGate(String jarPath) {
        File f = new File(jarPath);
        if (!f.isFile() || !f.canRead()) return false;
        ZipFile zf = null;
        try {
            zf = new ZipFile(f);
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                String name = en.nextElement().getName();
                if (name.endsWith(GATE_CLASS_SIMPLE + ".class")) return true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "scan jar failed: " + jarPath + " " + t);
        } finally {
            if (zf != null) {
                try { zf.close(); } catch (Throwable ignored) { }
            }
        }
        return false;
    }

    private static String safeLower(String s) {
        return s == null ? "" : s.toLowerCase();
    }
}
