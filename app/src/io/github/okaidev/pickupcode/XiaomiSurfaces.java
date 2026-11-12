package io.github.okaidev.pickupcode;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.widget.TextView;
import android.widget.Toast;

/** Setup help for the system-rendered island and rear-screen notification. */
final class XiaomiSurfaces {
    private XiaomiSurfaces() { }

    static void prompt(Activity activity) {
        boolean supported = HyperIsland.protocol(activity) >= 2;
        String status = supported
                ? "已检测到焦点通知协议；实际展示还取决于通知权限、超级岛开关与系统鉴权。"
                : "当前系统未报告焦点通知能力，将使用普通取件通知。";
        String help = status + "\n\n"
                        + "收到取件码后，通知显示取件码与待取件数；标记已取后同步更新。"
                        + "按系统能力适配 HyperOS 2 / 3 / 4，背屏机型由小米系统转换展示。"
                        + "锁屏公开通知和息屏文案只显示件数。\n\n"
                        + "如果普通通知正常但没有上岛：\n"
                        + "先在系统通知设置中允许本应用通知，并打开可用的焦点通知 / 超级岛选项。\n\n"
                        + "已使用 HyperCeiler 的设备，还可检查：\n"
                        + "• 系统界面 → 控制中心 → 移除焦点通知白名单，再在「配置焦点通知列表」中选择「取件码助手」。\n"
                        + "• 小米服务框架 → 解锁焦点通知白名单签名验证。\n"
                        + "这两项分别处理展示白名单与鉴权；修改后按 HyperCeiler 提示重启作用域。"
                        + "不同版本的入口可能有变化。\n\n"
                        + "17 Pro / Pro Max 背屏若仍不显示，还需检查背屏通知开关与系统模板。"
                        + "REAREye 可放开背屏白名单，但仍需匹配的模板；仅解锁白名单不保证显示。";
        UiKit.Palette palette = UiKit.palette(activity);
        TextView body = UiKit.text(activity, help, 13.5f, UiKit.INK2);
        body.setLineSpacing(UiKit.dp(activity, 4), 1f);
        UiKit.dialog(activity, palette, R.drawable.ic_bell, "超级岛与背屏", null, body, 50,
                UiKit.primary("通知设置", () -> openNotificationSettings(activity)),
                UiKit.ghost("HyperCeiler 项目", () -> openHyperCeiler(activity)));
    }

    private static void openNotificationSettings(Activity activity) {
        try {
            activity.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName()));
        } catch (RuntimeException ignored) {
            Toast.makeText(activity, "请在系统设置中打开本应用的通知设置", Toast.LENGTH_LONG).show();
        }
    }

    private static void openHyperCeiler(Activity activity) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/ReChronoRain/HyperCeiler")));
        } catch (RuntimeException ignored) {
            Toast.makeText(activity, "请用浏览器查看 HyperCeiler 项目", Toast.LENGTH_LONG).show();
        }
    }
}
