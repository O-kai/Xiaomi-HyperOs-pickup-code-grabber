package io.github.okaidev.pickupcode;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 联网策略总开关（v2.8.0 离线优先设计）：
 *
 * 核心原则：网络只是增益，不是依赖——
 *  - 规则引擎内置编译默认规则 + APK assets 内置档案 + 本地缓存三级回退，
 *    断网/禁网时引擎照常全功能运行；
 *  - 「允许联网」总开关（默认开）控制两类【自动】联网行为：
 *      1) 软件版本自动检查（Updater）
 *      2) 规则集自动热更新（RulesUpdater）
 *  - 总开关关闭 = 完全离线模式：所有自动联网停止；菜单里的手动检查仍可用
 *    （用户主动点按钮属于用户意图，不受开关限制），无网络时手动检查自然失败并提示，
 *    不影响任何核心功能（提取/写入/通知/体检）。
 */
public class NetPolicy {

    private static final String PREFS = "dedup";
    private static final String KEY_AUTO_NET = "auto_net_allowed";

    /** 自动联网是否被允许（默认 true；关闭后所有自动检查停止） */
    public static boolean autoNetAllowed(Context ctx) {
        if (ctx == null) return true;
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_AUTO_NET, true);
    }

    /** 设置自动联网开关 */
    public static void setAutoNetAllowed(Context ctx, boolean allowed) {
        if (ctx == null) return;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_AUTO_NET, allowed).apply();
    }

    /** 当前是否处于完全离线模式（供 UI 展示） */
    public static boolean isOfflineMode(Context ctx) {
        return !autoNetAllowed(ctx);
    }
}
