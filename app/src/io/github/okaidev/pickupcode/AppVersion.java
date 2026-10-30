package io.github.okaidev.pickupcode;

import android.content.Context;
import android.content.pm.PackageInfo;

/**
 * 版本号的唯一出口。
 *
 * <p>全项目只有这一处知道「版本号长什么样」。版本本身有两个数字，
 * 各管一件事、互不推导（见 {@code version.properties}）：
 * <ul>
 *   <li><b>点分版本号</b>（如 {@code 4.0.2}）——给人看的，界面上一律显示 {@code V4.0.2}。</li>
 *   <li><b>versionCode</b>（如 {@code 402}）——Android 内部的整数，单调递增，
 *       只用于升级判定与规则集的 {@code minAppVersionCode} 兼容性闸门，<b>不参与展示</b>。</li>
 * </ul>
 *
 * <p><b>版本格式（用户定义，后续一律照此）</b>：
 * <pre>
 *   V&lt;大版本&gt;.&lt;小版本&gt;.&lt;修复版本&gt;
 *
 *   第一段 = 大版本：不兼容的架构/功能变更时 +1，例 3 → 4
 *   第二段 = 小版本：新增功能时 +1，例 4.0.0 → 4.1.0
 *   第三段 = 修复版本：修 bug 时 +1，例 4.1.0 → 4.1.1
 * </pre>
 *
 * <p><b>关于历史后缀</b>：早期版本名带过 {@code -internal} / {@code -rc2}。
 * 现在不再产生这种后缀，但解析与比较仍保留一层容错——
 * 那些历史 APK 已经装在用户手机上，解析时崩掉或比较结果恒为 0
 * （「自动检查更新」永远认为已是最新）是不能接受的。
 */
public final class AppVersion {

    private AppVersion() {
    }

    /** 读不到 PackageManager 时的兜底版本号 */
    private static final String FALLBACK = "4.0.2";

    /**
     * 点分版本号，如 {@code 4.0.2}。
     *
     * <p>取自 PackageManager 的 versionName——那正是构建时 version.properties 里的
     * VERSION_NAME，是全项目唯一事实源（构建脚本已禁止 manifest 写死版本号）。
     */
    public static String dotted(Context ctx) {
        if (ctx == null) return FALLBACK;
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            String v = pi.versionName;
            if (v == null || v.trim().isEmpty()) return FALLBACK;
            return stripSuffix(v.trim());
        } catch (Throwable t) {
            return FALLBACK;
        }
    }

    /**
     * versionCode，如 {@code 402}。
     *
     * <p>只用于 Android 内部的升级判定与规则集的兼容性闸门，<b>不用于展示</b>。
     */
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
     * 界面用的版本号标签，如 {@code V4.0.2}。
     *
     * <p>大写 V —— 项目历史上一贯用大写（V3.1.0 / V4.0.2）。
     */
    public static String label(Context ctx) {
        return "V" + dotted(ctx);
    }

    /**
     * 比较两个点分版本号。
     *
     * <p>返回 &gt;0 表示 a 更新，&lt;0 表示 a 更旧，0 表示相同。
     *
     * <p>实现放在这里而不是调用方，是为了与版本号解析同源——
     * 原先 UpdateCenter 里自己手写了一份比较逻辑，与展示用的解析各写各的，
     * 改版本格式时容易只改一边。
     */
    public static int compare(String a, String b) {
        try {
            String[] sa = split(a);
            String[] sb = split(b);
            int n = Math.max(sa.length, sb.length);
            for (int i = 0; i < n; i++) {
                int va = i < sa.length ? leadingNumber(sa[i]) : 0;
                int vb = i < sb.length ? leadingNumber(sb[i]) : 0;
                if (va != vb) return va - vb;
            }
        } catch (Throwable ignored) { }
        return 0;
    }

    /** a 是否比 b 新。 */
    public static boolean isNewer(String a, String b) {
        return compare(a, b) > 0;
    }

    /**
     * 去掉版本号里的历史后缀（{@code -internal} / {@code -rc2}）。
     *
     * <p>只取点分数字部分：{@code "4.0.0-rc2" → "4.0.0"}。
     */
    public static String stripSuffix(String v) {
        if (v == null) return FALLBACK;
        String s = v.trim();
        if (s.isEmpty()) return FALLBACK;
        // 尾部多余的点先清掉
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        int cut = s.length();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean keep = (c >= '0' && c <= '9') || c == '.';
            if (!keep) { cut = i; break; }
        }
        String head = s.substring(0, cut);
        while (head.endsWith(".")) head = head.substring(0, head.length() - 1);
        return head.isEmpty() ? FALLBACK : head;
    }

    /** 按点分段拆分；null 视为空 */
    private static String[] split(String v) {
        if (v == null) return new String[0];
        return v.trim().split("\\.");
    }

    /**
     * 取一段里的开头连续数字。
     *
     * <p>容忍后缀：{@code "4-internal" → 4}、{@code "rc2" → 0}。
     * 早期版本比较用的是 {@code Integer.parseInt}，遇到 {@code "4-internal"}
     * 直接抛异常被吞掉、永远返回 0，结果是「自动检查更新」始终认为已是最新。
     */
    private static int leadingNumber(String part) {
        if (part == null) return 0;
        String seg = part.trim();
        int end = 0;
        while (end < seg.length() && Character.isDigit(seg.charAt(end))) end++;
        if (end == 0) return 0;
        try {
            return Integer.parseInt(seg.substring(0, end));
        } catch (Throwable ignored) {
            return 0;
        }
    }
}
