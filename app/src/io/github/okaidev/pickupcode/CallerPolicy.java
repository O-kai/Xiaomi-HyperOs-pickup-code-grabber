package io.github.okaidev.pickupcode;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Process;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 跨进程入口的调用方策略。
 *
 * 背景：TodoProvider 必须 exported=true，否则 LSPosed 注入到 system_server /
 * com.android.phone / com.android.providers.telephony 的 Hook 无法唤醒模块 App。
 * 但 exported=true 且不校验调用方时，任意普通 App 都能伪造取件短信、触发写库/通知，
 * 或把某个取件码标记为已取件。
 *
 * 因此：
 *  - onSms / onMissed 只接受系统、电话、短信、telephony provider、模块自身；
 *  - markDone 只接受模块自身（通知动作/链路测试都在 App 进程）；
 *  - 其他 UID 一律拒绝并记录（不打印短信正文）。
 *
 * 说明：这里用 UID + 包名白名单，而不是 android:permission。
 * 系统进程不持有本 App 的签名权限，纯 permission 会阻断核心唤醒链路。
 */
final class CallerPolicy {

    static final int SYSTEM_UID = 1000;
    static final int PHONE_UID = 1001;

    /**
     * 允许上报短信/通知事件的包名。
     * 不同 ROM 的短信/电话实现包名不同，这里覆盖 AOSP、MIUI/HyperOS、ColorOS。
     */
    private static final Set<String> TRUSTED_SMS_PACKAGES = new HashSet<>(Arrays.asList(
            "com.android.mms",
            "com.miui.mms",
            "com.oplus.mms",
            "com.coloros.mms",
            "com.hihonor.mms",      // v4.1.0：荣耀「信息」（独立后的包名）
            "com.huawei.mms",       // 同源 EMUI 机型，一并放行
            "com.android.phone",
            "com.android.providers.telephony"
    ));

    private CallerPolicy() {
    }

    static boolean isSelf(int uid) {
        return uid == Process.myUid();
    }

    static boolean isTrustedSmsUid(Context ctx, int uid) {
        if (isSelf(uid) || uid == SYSTEM_UID || uid == PHONE_UID) {
            return true;
        }
        if (ctx == null) {
            return false;
        }
        try {
            PackageManager pm = ctx.getPackageManager();
            String[] packages = pm.getPackagesForUid(uid);
            if (packages != null) {
                for (String pkg : packages) {
                    if (isTrustedSmsPackage(pkg)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    static boolean isTrustedSmsPackage(String pkg) {
        return pkg != null && TRUSTED_SMS_PACKAGES.contains(pkg);
    }

    /** 仅用于日志：UID + 包名，不包含短信内容。 */
    static String describe(Context ctx, int uid) {
        if (ctx == null) {
            return "uid=" + uid;
        }
        try {
            String[] packages = ctx.getPackageManager().getPackagesForUid(uid);
            if (packages != null && packages.length > 0) {
                return "uid=" + uid + " pkg=" + String.join(",", packages);
            }
        } catch (Throwable ignored) {
        }
        return "uid=" + uid;
    }
}
