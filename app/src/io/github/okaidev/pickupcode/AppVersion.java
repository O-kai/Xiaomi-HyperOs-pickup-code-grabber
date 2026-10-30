package io.github.okaidev.pickupcode;

import android.content.Context;
import android.content.pm.PackageInfo;

/**
 * 版本号的唯一出口。
 *
 * <p>v4.0.1 收口：此前版本信息散落在四个地方各取各的——
 * 界面头部用 versionCode 拼「V400」，诊断报告用 versionName 拼「4.0.0 (code 400)」，
 * 更新检查用 versionName 比大小，规则兼容性又用 versionCode 比大小。
 * 两套数字体系并存的直接后果是：界面显示的「V400」和用户心里想的「4.0.0」对不上，
 * 报告里两套数字混排，维护时也不知道该改哪个。
 *
 * <p>现在的约定（后续一律照此，不再各写各的）：
 * <ul>
 *   <li><b>点分版本号</b>（如 {@code 4.0.0}）是唯一的事实源，展示给人看的就是它。</li>
 *   <li><b>versionCode</b>（如 {@code 400}）只留给 Android 内部的升级判定与
 *       规则集的 minAppVersionCode 兼容性闸门——它必须单调递增，不能参与展示。</li>
 * </ul>
 *
 * <p>因此界面上一律显示 {@code V4.0.0}，而不是 {@code V400}。
 */
public final class AppVersion {

    private AppVersion() {
    }

    /** 兜底版本号：读不到 PackageManager 时用，不至于显示成空白或 "null"。 */
    private static final String FALLBACK = "4.0.0";

    /**
     * 点分版本号，如 {@code 4.0.0}。
     *
     * <p>取自 PackageManager 的 versionName——那正是构建时 version.properties 里
     * 的 VERSION_NAME，是全项目唯一事实源（构建脚本已禁止 manifest 写死版本号）。
     */
    public static String dotted(Context ctx) {
        if (ctx == null) return FALLBACK;
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            String v = pi.versionName;
            if (v == null || v.trim().isEmpty()) return FALLBACK;
            v = v.trim();
            // 兼容历史版本名里可能带的后缀（"4.0.0-internal" / "4.0.0-rc2"）：
            // 展示时只取点分数字部分，后缀是内部发布节奏的标记，对用户没有意义。
            return stripSuffix(v);
        } catch (Throwable t) {
            return FALLBACK;
        }
    }

    /** versionCode，如 {@code 400}。只用于升级判定与兼容性闸门，不用于展示。 */
    public static int code(Context ctx) {
        if (ctx == null) return 0;
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return android.os.Build.VERSION.SDK_INT >= 28
                    ? (int) pi.getLongVersionCode() : pi.versionCode;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 界面用的版本号标签，如 {@code V4.0.0}。
     *
     * <p>统一大写 V —— 项目历史上一贯用大写（V3.1.0 / V400）。
     */
    public static String label(Context ctx) {
        return "V" + dotted(ctx);
    }

    /**
     * 去掉版本号里的非数字后缀，只保留点分段。
     *
     * <p>"4.0.0-internal" → "4.0.0"；"4.0.0" → "4.0.0"。
     * 逐段处理，所以 "4.0.0-rc2" 这种也不会把 rc2 混进段里。
     *
     * <p>public：版本号的解析规则本身就是需要被测试锁住的东西，
     * 放在同一个包里会让离线回归没法直接调用。
     */
    public static String stripSuffix(String raw) {
        if (raw == null) return FALLBACK;
        String[] parts = raw.trim().split("\\.");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append('.');
            sb.append(leadingDigits(parts[i]));
        }
        String s = sb.toString();
        // 去掉尾部的空段（"4.0." → "4.0"）
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s.isEmpty() ? FALLBACK : s;
    }

    /** 取一段里的开头连续数字："4-internal" → 4，"rc2" → 0。 */
    private static String leadingDigits(String part) {
        if (part == null) return "0";
        String seg = part.trim();
        int end = 0;
        while (end < seg.length() && Character.isDigit(seg.charAt(end))) end++;
        return end == 0 ? "0" : seg.substring(0, end);
    }

    /**
     * 比较两个点分版本号。
     *
     * <p>返回 &gt;0 表示 a 更新，&lt;0 表示 a 更旧，0 表示相同。
     *
     * <p>v4.0.1：从 UpdateCenter 搬到这里，与 {@link #stripSuffix} 同源。
     * 原先「展示用的解析」和「比较用的解析」是两套代码，
     * 改版本号格式时很容易只改一边——那正是版本号出问题的常见来源。
     */
    public static int compare(String a, String b) {
        try {
            String[] sa = stripSuffix(a).split("\\.");
            String[] sb = stripSuffix(b).split("\\.");
            int n = Math.max(sa.length, sb.length);
            for (int i = 0; i < n; i++) {
                int va = i < sa.length ? toInt(sa[i]) : 0;
                int vb = i < sb.length ? toInt(sb[i]) : 0;
                if (va != vb) return va - vb;
            }
        } catch (Throwable ignored) { }
        return 0;
    }

    /** a 是否比 b 新。 */
    public static boolean isNewer(String a, String b) {
        return compare(a, b) > 0;
    }

    private static int toInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Throwable t) {
            return 0;
        }
    }
}
