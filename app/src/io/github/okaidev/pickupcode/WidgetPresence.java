package io.github.okaidev.pickupcode;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;

/**
 * 小组件的「在不在 / 开不开」——只依赖系统 API 的轻量工具（v4.0.3）。
 *
 * <p><b>为什么要单独一个类</b>：{@link NotesBackend} 需要判断组件状态，
 * 而 {@code NotesBackend} 会被离线测试链（`tools/run_all_tests.sh` 的规则回归）
 * 用 {@code -sourcepath} 自动推导编译。如果它直接调 {@code PickupWidgetProvider}，
 * 就会把 {@code PickupWidgetProvider} → {@code PickupRepository} → Room / AndroidX
 * 一整串拖进那条链，而离线环境没有 AndroidX 的 jar，
 * 结果就是「规则依赖链编译失败」（这轮真踩了一次）。
 *
 * <p>所以这里<b>只用字符串形式的类名</b>构造 {@link ComponentName}
 * （{@code ComponentName(String, String)} 不会加载那个类），
 * 整个类只依赖 {@code android.jar} 里就有的东西。
 */
public final class WidgetPresence {

    private WidgetPresence() { }

    /** 本模块小组件 Provider 的全限定类名（与 AndroidManifest 一致） */
    public static final String PROVIDER_CLASS =
            "io.github.okaidev.pickupcode.PickupWidgetProvider";

    /**
     * 桌面上还有没有本模块的小组件。
     *
     * <p>只用于文案（「桌面上已有小组件」）和「停用后有没有被系统摘干净」的复查。
     * 拿不到 AppWidgetManager 时按「没有」处理，不能因此影响写入逻辑。
     */
    public static boolean has(Context context) {
        if (context == null) return false;
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(context);
            int[] ids = mgr.getAppWidgetIds(
                    new ComponentName(context.getPackageName(), PROVIDER_CLASS));
            return ids != null && ids.length > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 启用 / 停用小组件 Provider（v4.0.3 第二轮）。
     *
     * <p>「软件内部记录」关掉之后小组件没有数据可显示，停用 Provider 会让系统
     * 把桌面上的实例一并摘掉——这是 App 端唯一能真正「移除」别人桌面组件的
     * 手段（App 无权直接删 launcher 里的实例，只有 host 有这个权力）。
     *
     * <p>重新打开记录时再启用，用户需要到设置页重新添加一次组件。
     *
     * @return true 表示设置成功
     */
    public static boolean setEnabled(Context context, boolean enabled) {
        if (context == null) return false;
        try {
            ComponentName cn = new ComponentName(context.getPackageName(), PROVIDER_CLASS);
            context.getPackageManager().setComponentEnabledSetting(cn,
                    enabled ? android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                            : android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Provider 组件当前是否处于可用状态 */
    public static boolean isEnabled(Context context) {
        if (context == null) return false;
        try {
            ComponentName cn = new ComponentName(context.getPackageName(), PROVIDER_CLASS);
            int state = context.getPackageManager().getComponentEnabledSetting(cn);
            return state != android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        } catch (Throwable t) {
            return true;
        }
    }
}
