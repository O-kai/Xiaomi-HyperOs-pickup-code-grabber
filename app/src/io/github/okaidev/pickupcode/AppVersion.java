package io.github.okaidev.pickupcode;

import android.content.Context;
import android.content.pm.PackageInfo;

/**
 * 版本号的唯一出口。
 *
 * <p>v4.0.2 起，全项目只有一处知道「版本号长什么样」——就是这里，
 * 加上构建期 {@code version.properties} 里那一个点分数字。
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
 * <p><b>为什么不再有两套数字</b>：原先界面显示构建号（V400）、
 * 更新检查比版本名（4.0.0）、规则兼容性又比另一个构建号（293），
 * 三个地方三套标尺。现在只维护一个点分版本号，
 * versionCode 由构建脚本按 {@code major*10000 + minor*100 + patch} 推导，
 * 规则集的 minAppVersionCode 用同一套数字——不存在「改一处忘另一处」。
 *
 * <p><b>关于后缀</b>：本类不再做 {@code -internal / -rc2} 的容错。
 * 那不是需求，是历史包袱：早期版本名带后缀，导致版本比较抛异常被吞掉、
 * 「自动检查更新」永远认为已是最新。现在构建期就强制
 * {@code X.Y.Z} 纯数字格式，带后缀直接构建失败。
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
     * VERSION_NAME，是全项目唯一事实源（构建脚本已禁止 manifest 写死版本号，
     * 也已禁止带任何后缀）。
     */
    public static String dotted(Context ctx) {
        if (ctx == null) return FALLBACK;
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            String v = pi.versionName;
            if (v == null || v.trim().isEmpty()) return FALLBACK;
            return v.trim();
        } catch (Throwable t) {
            return FALLBACK;
        }
    }

    /**
     * versionCode，如 {@code 40002}。
     *
     * <p>只用于 Android 内部的升级判定与规则集的兼容性闸门，<b>不用于展示</b>。
     * 它由构建脚本从点分版本号推导，所以两者永远一致。
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

    /** 按点分段拆分；非数字段按 0 处理（不抛异常） */
    private static String[] split(String v) {
        if (v == null) return new String[0];
        return v.trim().split("\\.");
    }

    private static int toInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Throwable t) {
            return 0;
        }
    }
}
