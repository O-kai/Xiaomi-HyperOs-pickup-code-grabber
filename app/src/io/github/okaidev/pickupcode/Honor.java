package io.github.okaidev.pickupcode;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * 荣耀（HONOR / MagicOS）机型识别与目标定位（v4.1.0）。
 *
 * <p><b>为什么单独一个类</b>：和 {@link ColorOs} 一样的理由——判断「我是不是荣耀」
 * 要在模块 App 进程里做（界面文案、写入后端选择、体检项），而
 * {@link HonorWriter} 里引用了 CalendarProvider，两者拆开才不会互相拖累。
 *
 * <p><b>识别依据</b>（三条独立证据，任一条命中就算）：
 * <ol>
 *   <li>{@code Build.MANUFACTURER} / {@code Build.BRAND} 含 honor——
 *       荣耀独立后的机型这两个字段都是 HONOR；</li>
 *   <li>{@code ro.build.version.magic} 非空——MagicOS 7 起才有的版本属性；</li>
 *   <li>{@code ro.build.version.emui} 非空且厂商是 huawei/honor——
 *       覆盖 2021 年前后仍挂 EMUI 版本号的老荣耀机型。</li>
 * </ol>
 * 只看厂商字段不够：华为机型的 EMUI 与荣耀的 MagicOS 同源，
 * 待办库结构可能一致，但「荣耀账号」这类文案不能写到华为机上。
 *
 * <p><b>目标定位</b>：荣耀的「待办」在<b>日历</b>里（设置路径：日历 → 待办），
 * 备忘录的待办早已迁移过去（与 ColorOS 16 的迁移是同一个产品决策）。
 * 所以写入目标 = 系统 CalendarProvider 的 {@code Tasks} 表，
 * 库里没有 Tasks 时按 {@link HonorSql} 的自适应逻辑降级。
 */
public final class Honor {

    private Honor() { }

    /** 荣耀「信息」（短信）App —— 独立后的包名，AOSP 的 com.android.mms 在这代机器上不存在 */
    public static final String PKG_MMS = "com.hihonor.mms";
    /** 荣耀日历（APKMirror 上架名 Agenda） */
    public static final String PKG_CALENDAR = "com.hihonor.calendar";
    /** 荣耀备忘录（笔记）—— 只用于包可见性声明与诊断，不作为写入目标 */
    public static final String PKG_NOTEPAD = "com.hihonor.notepad";
    /** 系统日历 Provider（AOSP 组件，荣耀沿用） */
    public static final String PKG_CALENDAR_PROVIDER = "com.android.providers.calendar";

    /** 待办库路径（真机侦察确认：待办在独立的 task.db，不是 calendar.db） */
    public static final String DB_TASK =
            "/data/user/0/" + PKG_CALENDAR_PROVIDER + "/databases/task.db";

    /** 日历主库（AOSP 把待办也存在这里；荣耀留作第二候选） */
    public static final String DB_CALENDAR =
            "/data/user/0/" + PKG_CALENDAR_PROVIDER + "/databases/calendar.db";

    /**
     * 待办库的候选顺序。
     *
     * <p>HONOR HONOR 机型 / MagicOS 11 的侦察日志里，{@code databases/} 下同时有
     * {@code calendar.db}（192KB）和 {@code task.db}（90KB，且今天还在写），
     * 而 {@code content://com.android.calendar/tasks} 直接报 Unknown URL——
     * 说明荣耀把待办单独拆进了 task.db，且没有暴露 Provider 入口。
     * 所以先试 task.db，再回落 calendar.db（万一某些版本又合回去了）。
     */
    public static String[] todoDbCandidates() {
        return new String[]{DB_TASK, DB_CALENDAR};
    }

    /** 诊断报告里展示的「待办库」描述（两个候选一起列，用户一眼能对上） */
    public static String dbHint() {
        return DB_TASK + " 或 " + DB_CALENDAR;
    }

    /**
     * 短信接收侧的候选类名。
     *
     * <p>荣耀的「信息」是 EMUI 那套 Mms 的延续，类名大概率仍是
     * {@code com.android.mms.transaction.*}；但独立后也出现过
     * {@code com.hihonor.mms.*} 的重命名。两类都试，命中即挂，找不到就跳过——
     * 抓不到也不影响主通道（S8 是 AOSP 的 SmsProvider.insert，荣耀同样存在）。
     */
    public static final String[] MMS_RECEIVER_CANDIDATES = {
            "com.android.mms.transaction.SmsReceiver",
            "com.android.mms.transaction.PrivilegedSmsReceiver",
            "com.android.mms.receiver.SmsReceiver",
            "com.hihonor.mms.transaction.SmsReceiver",
            "com.hihonor.mms.receiver.SmsReceiver",
            "com.hihonor.mms.receiver.SmsDeliverReceiver",
            "com.hihonor.mms.transaction.SmsReceiverService",
    };

    /** 是否荣耀 / MagicOS 设备 */
    public static boolean isHonor() {
        String mf = lower(Build.MANUFACTURER);
        String br = lower(Build.BRAND);
        if (mf.contains("honor") || br.contains("honor")) return true;
        boolean huaweiFamily = mf.contains("huawei") || br.contains("huawei");
        if (!huaweiFamily) return false;
        // 老荣耀机型：厂商字段仍是 HUAWEI，但系统已经是 MagicUI/MagicOS
        String magic = magicVersion();
        return magic != null && !magic.isEmpty();
    }

    /** MagicOS 版本号；取不到回落 EMUI 版本号；都没有返回空串 */
    public static String magicVersion() {
        String magic = prop("ro.build.version.magic");
        if (magic != null && !magic.isEmpty()) return magic;
        String emui = prop("ro.build.version.emui");
        return emui == null ? "" : emui;
    }

    /** 诊断报告用的一行系统标识 */
    public static String describe() {
        String v = magicVersion();
        return v.isEmpty() ? "MagicOS（版本号未暴露）" : "MagicOS " + v;
    }

    // ==================== 日历读写权限 ====================
    //
    // 荣耀侧的写入优先走 CalendarProvider（ContentResolver），这条路径不需要 root，
    // 但需要 READ/WRITE_CALENDAR。没授权时 HonorWriter 会回落到 root 直写，
    // 所以权限只是「少一次 su」的优化，不是硬前提——不弹窗也能用。

    public static boolean hasCalendarPermission(Context ctx) {
        if (ctx == null) return false;
        try {
            return ctx.checkSelfPermission(Manifest.permission.WRITE_CALENDAR)
                    == PackageManager.PERMISSION_GRANTED
                    && ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 首次在荣耀上打开 App 时请求一次；用户拒绝也不影响 root 直写路径 */
    public static void requestCalendarPermission(Activity a, int requestCode) {
        if (a == null || hasCalendarPermission(a)) return;
        try {
            a.requestPermissions(new String[]{
                    Manifest.permission.READ_CALENDAR,
                    Manifest.permission.WRITE_CALENDAR}, requestCode);
        } catch (Throwable ignored) { }
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase();
    }

    private static String prop(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object v = sp.getMethod("get", String.class).invoke(null, key);
            return v == null ? "" : v.toString();
        } catch (Throwable t) {
            return "";
        }
    }
}
