package io.github.okaidev.pickupcode;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 4.0 双模式：简洁模式 / 进阶模式。
 *
 * 产品约定（2026-10-30 与用户确认）：
 *  - 首次启动让用户二选一，数据完全共享；
 *  - 简洁模式只保留「待取件 → 复制 → 已取」，其余入口收进入阶模式；
 *  - 设置里可随时切换，切换不重建数据。
 */
public final class UiMode {

    public static final String SIMPLE = "simple";
    public static final String ADVANCED = "advanced";

    private static final String PREFS = "pickup_ui";
    private static final String KEY_MODE = "ui_mode";

    private UiMode() {
    }

    /** 未选择过时返回 null（用于首启引导）。 */
    public static String get(Context ctx) {
        String v = prefs(ctx).getString(KEY_MODE, null);
        return SIMPLE.equals(v) || ADVANCED.equals(v) ? v : null;
    }

    public static boolean isSimple(Context ctx) {
        return SIMPLE.equals(get(ctx));
    }

    public static void set(Context ctx, String mode) {
        prefs(ctx).edit().putString(KEY_MODE, SIMPLE.equals(mode) ? SIMPLE : ADVANCED).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
