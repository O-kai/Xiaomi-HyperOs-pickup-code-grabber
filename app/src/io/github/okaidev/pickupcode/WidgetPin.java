package io.github.okaidev.pickupcode;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.widget.Toast;

/**
 * 桌面小组件的添加 / 重新添加入口。
 *
 * <p>为什么需要「重新添加」：真机结论（K90 Pro Max / HyperOS 4.0）——
 * App 更新后，桌面<b>不会重建</b>已有小组件的视图树，新的 initialLayout 不生效，
 * 表现为「行距被压扁、标题不见」这类明显错乱。
 * 系统层面没有让 App 主动重建的 API（改根 id、改布局文件名都不管用，
 * 桌面只认首次添加时 inflate 出来的那棵树），
 * 唯一的办法是移除旧的、重新添加一次。
 *
 * <p>所以这个入口不是可有可无的装饰——没有它，用户升级后会看到一个坏掉的组件，
 * 却找不到原因。
 */
public final class WidgetPin {

    private WidgetPin() {
    }

    /** 弹出「添加到桌面」的引导（UiKit 风格，与 App 内部视觉一致）。 */
    public static void prompt(android.app.Activity ctx) {
        UiKit.Palette p = UiKit.palette(ctx);
        // 说明文字按真机实测写：
        // HyperOS 的「添加小部件 → 全部应用」是系统内置的应用清单，
        // 第三方应用（不在清单里的）根本不会出现在那一页，
        // 必须先在「应用设置」里给本 App 打开「桌面小组件」开关。
        String steps = "若桌面上已有旧组件，请先长按它 →「移除」，再添加新的。\n\n"
                + "手动添加步骤：\n"
                + "第 1 步：设置 → 应用设置 → 应用管理 → 取件码助手 → 桌面小组件 → 打开\n"
                + "第 2 步：回到桌面，长按空白处 → 点「小部件」\n"
                + "第 3 步：在列表里找到「取件码助手」，拖到桌面\n"
                + "（若列表里仍没有，说明系统还没同步，稍后再试或重启桌面）";
        UiKit.dialog(ctx, p, R.drawable.ic_home, "添加到桌面",
                steps, null,
                UiKit.primary("立即尝试", () -> tryPin(ctx)),
                UiKit.ghost("知道了", null));
    }

    private static void tryPin(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) {
            Toast.makeText(ctx, "系统版本过低，请长按桌面手动添加", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(ctx);
            ComponentName provider = new ComponentName(ctx, PickupWidgetProvider.class);
            if (!manager.isRequestPinAppWidgetSupported()) {
                Toast.makeText(ctx, "请长按桌面 → 小部件 → 取件码助手", Toast.LENGTH_LONG).show();
                return;
            }
            manager.requestPinAppWidget(provider, null, null);
            // HyperOS 上 requestPinAppWidget 会「返回成功但什么都不做」
            // （桌面 AddItemActivity 30ms 内自行结束、不显示界面、也不创建实例）。
            // 所以这里不报「成功」，只给一句可执行的提示。
            Toast.makeText(ctx, "已请求添加；若桌面没反应，请按弹窗里的步骤手动添加",
                    Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Toast.makeText(ctx, "添加失败，请长按桌面手动添加", Toast.LENGTH_LONG).show();
        }
    }
}
