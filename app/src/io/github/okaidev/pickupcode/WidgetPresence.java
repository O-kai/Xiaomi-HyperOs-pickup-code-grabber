package io.github.okaidev.pickupcode;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;

/**
 * 「桌面上有没有本模块的小组件」——只依赖系统 API 的轻量判断（v4.0.3）。
 *
 * <p><b>为什么要单独一个类</b>：{@link NotesBackend} 要用这个判断
 * （「仅桌面小组件」这个写入模式要随组件存在性联动），而 {@code NotesBackend}
 * 会被离线测试链（`tools/run_all_tests.sh` 的规则回归）用 {@code -sourcepath}
 * 自动推导编译。如果直接调 {@code PickupWidgetProvider.hasWidget}，就会把
 * {@code PickupWidgetProvider} → {@code PickupRepository} → Room / AndroidX
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
     * <p>拿不到 AppWidgetManager（极少数受限场景）时返回 false——
     * 宁可让「仅小组件」模式不可选，也不能让用户选了之后什么都看不到。
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
}
