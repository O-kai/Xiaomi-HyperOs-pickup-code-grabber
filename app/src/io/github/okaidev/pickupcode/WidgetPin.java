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

    /**
     * MIUI 的「应用权限」页。
     *
     * <p>真机实测：`am start -a miui.intent.action.APP_PERM_EDITOR --es extra_pkgname <pkg>`
     * 直达「取件码助手」的权限列表 →「其他权限」→「设置相关」里就有「桌面快捷方式」。
     * 比让用户自己从设置里翻快得多。
     */
    private static final String ACTION_MIUI_PERM_EDITOR = "miui.intent.action.APP_PERM_EDITOR";

    /**
     * 等系统「已固定」回调的上限。
     *
     * <p>v4.2.1 从「一次性 2.5 秒判定」改成「每 500ms 探一次、最多 8 次（4 秒）」：
     * 一次性判定太急 —— 真机上系统确认框有时 3 秒后才出来，
     * 那时我们早就把「没权限，去打开桌面快捷方式」的引导弹出来盖在上面了，
     * 用户看到的就是「明明有权限，却只让我去开权限」。
     */
    private static final int PIN_POLL_MS = 500;
    private static final int PIN_POLLS = 8;

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
    /**
     * 「刚从权限页回来就自动再试一次」的标记。
     *
     * <p>置位的唯一时机是引导弹窗把用户送去「桌面快捷方式」页；
     * 由 {@code WidgetSettingsActivity.onResume} 取走（取一次就清）。
     * 不加这个的话，用户授权回来还得自己再点一遍按钮。
     */
    private static volatile boolean retryAfterPermission;

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
        final int before = countWidgets(a);
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

        Toast.makeText(a, "正在请求系统放到桌面…", Toast.LENGTH_SHORT).show();
        watchPin(a, seq, before, 0);
    }

    /**
     * 盯着这次请求的结果（v4.2.1）。
     *
     * <p>三种「成功/正在成功」的信号，命中任一条就闭嘴、不再打扰用户：
     * <ol>
     *   <li>收到系统「已固定」回调；</li>
     *   <li>桌面上真的多了一个本 App 的组件（部分 ROM 不回回调，但组件确实加上了）；</li>
     *   <li>本页失去焦点 —— 说明系统界面（确认框 / 桌面）已经接管，用户在操作了。</li>
     * </ol>
     * 只有「4 秒里一直握着焦点、一个组件也没多出来」才判定为被系统吞掉，
     * 这时才弹「去打开桌面快捷方式」的引导。
     */
    private static void watchPin(Activity a, long seq, int before, int step) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (pinned || seq != requestSeq) return;      // 已经加上了 / 又发起了新请求
            if (a.isFinishing() || a.isDestroyed()) return;
            if (countWidgets(a) > before) return;         // 桌面上真的多了一个
            if (!a.hasWindowFocus()) return;              // 系统界面接管了，别插嘴
            if (step < PIN_POLLS) {
                watchPin(a, seq, before, step + 1);
                return;
            }
            if (openWidgetPicker(a)) return;
            showGuide(a, "系统没有响应这次添加");
        }, PIN_POLL_MS);
    }

    /** 本 App 当前有几个组件实例（用来判断「这次到底加上没有」） */
    private static int countWidgets(android.content.Context ctx) {
        try {
            return AppWidgetManager.getInstance(ctx).getAppWidgetIds(
                    new ComponentName(ctx, PickupWidgetProvider.class)).length;
        } catch (Throwable t) {
            return 0;
        }
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
     *
     * <p>「桌面快捷方式」是这一整套里最关键的一道开关：真机实测，关着时
     * requestPinAppWidget 会返回 true 却什么都不发生（桌面 AddItemActivity
     * 立刻自己结束）。打开后同样的调用会正常弹出系统确认框。
     */
    private static void showGuide(Activity a, String reason) {
        UiKit.Palette p = UiKit.palette(a);
        String steps = (reason == null ? "" : reason + "。\n\n")
                + "手动添加步骤：\n"
                + "第 1 步：点下面的按钮直达权限页 →「其他权限」→「桌面快捷方式」→ 打开\n"
                + "　　　　（HyperOS 默认关闭；关着时系统会静默丢弃添加请求）\n"
                + "第 2 步：回到桌面，双指捏合 → 点「添加小部件」\n"
                + "第 3 步：点右上角搜索，输入「安卓小部件」；或下拉到列表底部点它 →\n"
                + "　　　　找到「取件码助手」，长按拖到桌面\n\n"
                + "负一屏只接受小米官方小部件，安卓小部件放不进去（系统限制）；\n"
                + "放到桌面第 1 页最接近。";

        boolean perm = canOpenPermissionEditor(a);
        boolean picker = !perm && hasWidgetPicker(a);
        String primaryLabel = perm ? "去打开「桌面快捷方式」"
                : (picker ? "打开小部件列表" : "立即重试");
        UiKit.dialog(a, p, R.drawable.ic_grid, "添加到桌面", steps, null,
                UiKit.primary(primaryLabel, () -> {
                    if (perm) {
                        if (!openPermissionEditor(a)) openAppDetails(a);
                    } else if (!openWidgetPicker(a)) {
                        quickPin(a);
                    }
                }),
                UiKit.ghost("知道了", null));
    }

    /**
     * 打开 MIUI 的「应用权限」页（真机实测可直达本 App 的权限列表）。
     *
     * @return true = 已跳转
     */
    private static boolean openPermissionEditor(Activity a) {
        try {
            Intent it = new Intent(ACTION_MIUI_PERM_EDITOR)
                    .putExtra("extra_pkgname", a.getPackageName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (it.resolveActivity(a.getPackageManager()) == null) return false;
            a.startActivity(it);
            retryAfterPermission = true;   // 用户回来时自动再试一次
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 取走「授权回来要自动重试」的标记（取一次即清） */
    public static boolean consumePermissionRetry() {
        boolean r = retryAfterPermission;
        retryAfterPermission = false;
        return r;
    }

    private static boolean canOpenPermissionEditor(Activity a) {
        try {
            Intent it = new Intent(ACTION_MIUI_PERM_EDITOR);
            return it.resolveActivity(a.getPackageManager()) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 兜底：系统「应用信息」页（任何 ROM 都有） */
    private static boolean openAppDetails(Activity a) {
        try {
            Intent it = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + a.getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(it);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
