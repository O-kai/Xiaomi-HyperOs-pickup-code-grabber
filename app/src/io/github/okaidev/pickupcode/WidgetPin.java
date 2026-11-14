package io.github.okaidev.pickupcode;

import android.app.Activity;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/**
 * 桌面小组件的添加 / 重新添加入口（v4.2.1 重写）。
 *
 * <p><b>为什么需要「重新添加」</b>：真机结论（K90 Pro Max / HyperOS）——
 * App 更新后，桌面<b>不会重建</b>已有小组件的视图树，新的 initialLayout 不生效，
 * 表现为「行距被压扁、标题不见」这类明显错乱。系统层面没有让 App 主动重建的 API，
 * 唯一的办法是移除旧的、重新添加一次。
 *
 * <p><b>为什么入口要自己造</b>：HyperOS 长按图标里那条「添加小部件」是桌面自己加的，
 * 只对它服务端清单里的合作应用显示（飞书 / Spotify 那种），标准 AppWidget 的
 * 第三方应用不会出现，也没有任何 manifest 字段能让它出现。所以本模块走两条自己的路：
 * <ul>
 *   <li>静态快捷方式 {@code res/xml/shortcuts.xml}：长按图标就能看到「添加小部件」；</li>
 *   <li>{@link AppWidgetManager#requestPinAppWidget} + 固定结果回调：点一下请求系统落到桌面。</li>
 * </ul>
 *
 * <p><b>HyperOS 的坑</b>：{@code requestPinAppWidget} 会返回 true，但桌面的
 * AddItemActivity 30ms 内自己结束 —— 不弹确认框、也不创建实例，等于被静默吞掉。
 * 所以这里带一个 {@code successCallback}：2.5 秒内收不到「已固定」回调就认为失败，
 * 退回「打开小部件列表 / 看手动步骤」。
 */
public final class WidgetPin {

    /** 长按图标那条快捷方式带过来的 action */
    public static final String ACTION_ADD_WIDGET = "io.github.okaidev.pickupcode.action.ADD_WIDGET";

    /** 小组件设置页的参数：进来后立刻发起一次「放到桌面」请求 */
    public static final String EXTRA_AUTO_PIN = "auto_pin";

    /** 等系统「已固定」回调的上限（毫秒）。HyperOS 静默失败时不会有任何回调 */
    private static final long PIN_TIMEOUT_MS = 2500L;

    /**
     * 桌面可能的小部件列表页（尽力而为：命中就用，没命中就退回文字步骤）。
     *
     * <p>HyperOS 没有公开这个入口，组件名随版本变。这里只做「碰巧存在就省一步」，
     * 不存在也不会出错 —— 真正的可靠路径永远是「双指捏合 → 添加小部件」。
     */
    private static final String[][] LAUNCHER_WIDGET_PAGES = {
            {"com.miui.home", "com.miui.home.launcher.widget.WidgetsActivity"},
            {"com.miui.home", "com.miui.home.launcher.widgets.WidgetsActivity"},
    };

    /** 系统回调置 true：说明组件真的落到桌面了 */
    private static volatile boolean pinned;
    /** 每次请求递增，用来判断回调是不是这一次的 */
    private static volatile long requestSeq;

    private WidgetPin() {
    }

    // ==================== 对外 ====================

    /**
     * 一键添加：先请求系统直接落到桌面，失败再给可执行的退路。
     *
     * <p>由「添加到桌面」按钮和长按图标那条快捷方式调用。
     */
    public static void quickPin(Activity a) {
        if (a == null) return;
        AppWidgetManager mgr = AppWidgetManager.getInstance(a);
        ComponentName provider = new ComponentName(a, PickupWidgetProvider.class);

        if (!mgr.isRequestPinAppWidgetSupported()) {
            // 明确不支持（个别 ROM / 桌面），别装作成功
            if (!openWidgetPicker(a)) showGuide(a, "这个系统不支持一键添加");
            return;
        }

        final long seq = ++requestSeq;
        pinned = false;
        try {
            Intent cb = new Intent(a, WidgetPinReceiver.class)
                    .setAction(WidgetPinReceiver.ACTION_PINNED);
            PendingIntent pi = PendingIntent.getBroadcast(a, 1001, cb,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            mgr.requestPinAppWidget(provider, null, pi);
        } catch (Throwable t) {
            showGuide(a, "发起添加请求失败");
            return;
        }

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (pinned || seq != requestSeq) return;              // 加上了 / 又有新请求
            if (a.isFinishing() || a.isDestroyed()) return;
            // 系统要弹确认框时本页会先失去焦点 —— 那属于「用户正在操作」，别抢镜头。
            // HyperOS 静默吞掉请求时，焦点一直在本页。
            if (!a.hasWindowFocus()) return;
            if (openWidgetPicker(a)) return;
            showGuide(a, "系统没有响应这次添加");
        }, PIN_TIMEOUT_MS);

        Toast.makeText(a, "正在请求系统放到桌面…", Toast.LENGTH_SHORT).show();
    }

    /** 系统回调：组件已经落到桌面 */
    public static void onPinned(android.content.Context ctx) {
        pinned = true;
        try {
            PickupWidgetProvider.refreshAsync(ctx);
        } catch (Throwable ignored) { }
        Toast.makeText(ctx, "已添加到桌面", Toast.LENGTH_SHORT).show();
    }

    /**
     * 旧的入口（「重新添加小组件」按钮）：只给引导，不直接发请求。
     *
     * <p>这个场景是「桌面上那个坏了，先删再加」，用户本来就要去桌面操作；
     * 直接发 pin 请求反而可能在他还没移除旧组件时弹一次添加。
     */
    public static void prompt(Activity a) {
        showGuide(a, null);
    }

    // ==================== 内部 ====================

    /**
     * 试着打开桌面的「小部件列表」页。
     *
     * @return true = 已经打开了；false = 这个 ROM 上没有已知入口
     */
    private static boolean openWidgetPicker(Activity a) {
        for (String[] page : LAUNCHER_WIDGET_PAGES) {
            try {
                Intent it = new Intent();
                it.setComponent(new ComponentName(page[0], page[1]));
                if (it.resolveActivity(a.getPackageManager()) == null) continue;
                a.startActivity(it);
                return true;
            } catch (Throwable ignored) { }
        }
        return false;
    }

    private static boolean hasWidgetPicker(Activity a) {
        for (String[] page : LAUNCHER_WIDGET_PAGES) {
            try {
                Intent it = new Intent();
                it.setComponent(new ComponentName(page[0], page[1]));
                if (it.resolveActivity(a.getPackageManager()) != null) return true;
            } catch (Throwable ignored) { }
        }
        return false;
    }

    /**
     * 引导：路径按 HyperOS 实测写。
     *
     * <p>别再写「长按空白 → 小部件」这种通用说法 —— HyperOS 上标准安卓小部件
     * 藏在「小部件中心 → 搜索 → 安卓小部件」里，不点进去根本看不到本 App。
     */
    private static void showGuide(Activity a, String reason) {
        UiKit.Palette p = UiKit.palette(a);
        String steps = (reason == null ? "" : reason + "。\n\n")
                + "手动添加步骤：\n"
                + "第 1 步：设置 → 应用设置 → 应用管理 → 取件码助手 → 权限管理 →\n"
                + "　　　　打开「桌面快捷方式」\n"
                + "　　　　（HyperOS 默认关闭；关着时系统会静默丢弃添加请求）\n"
                + "第 2 步：回到桌面，双指捏合 → 点「添加小部件」\n"
                + "第 3 步：点右上角搜索，输入「安卓小部件」；或下拉到列表底部点它 →\n"
                + "　　　　找到「取件码助手」，长按拖到桌面\n\n"
                + "负一屏只接受小米官方小部件，安卓小部件放不进去（系统限制）；\n"
                + "放到桌面第 1 页最接近。";
        boolean picker = hasWidgetPicker(a);
        UiKit.dialog(a, p, R.drawable.ic_grid, "添加到桌面", steps, null,
                UiKit.primary(picker ? "打开小部件列表" : "立即重试", () -> {
                    if (!openWidgetPicker(a)) quickPin(a);
                }),
                UiKit.ghost("知道了", null));
    }
}
