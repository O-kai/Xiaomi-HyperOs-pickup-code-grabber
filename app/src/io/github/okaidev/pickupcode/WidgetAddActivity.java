package io.github.okaidev.pickupcode;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * 长按图标的「添加小部件」中转页（v4.2.1）。
 *
 * <p><b>它解决什么</b>：以前这条快捷方式指向 {@code LauncherActivity}，
 * 于是用户看到的是「点快捷方式 → 闪一下主界面 → 再进小组件设置页 → 再被弹回桌面」，
 * 一共三次跳转。这个页面是全透明的（主题里 windowIsTranslucent + 无动画），
 * 点完直接把「添加到桌面」的请求发出去，有权限时用户只会看到桌面上的系统确认框。
 *
 * <p><b>它不做什么（重要）</b>：这个页面**不弹任何对话框**。
 * 真机实测：在全透明、没有自绘界面、又没拿到输入焦点的窗口上弹 Dialog，
 * 会出现「按钮点不动、也不会自己消失」的僵尸窗口 ——
 * 用户会以为「明明开了权限还让我去开权限」，其实就是那个没死掉的旧弹窗。
 * 所以判定失败时它只做一件事：启动正常的小组件设置页，由那边弹引导，然后自己退出。
 */
public class WidgetAddActivity extends Activity {

    /** 传给小组件设置页：打开后弹一次「去打开桌面快捷方式」的引导 */
    public static final String EXTRA_SHOW_GUIDE = "show_guide";

    private boolean fired;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 被系统重建（极少见）：直接退出，别重复发请求
        if (savedInstanceState != null) {
            finish();
        }
    }

    /**
     * <b>必须在 onResume 之后发请求，不能在 onCreate 里发。</b>
     *
     * <p>真机日志（2026-11-18）：
     * <pre>
     * PIN request failed: java.lang.IllegalStateException:
     *     Calling application must have a foreground activity or a foreground service
     * </pre>
     * 从桌面快捷方式冷启动时，onCreate 里本页还没 resume，系统不认为 App 在前台，
     * 于是 {@code requestPinAppWidget} 直接抛异常 —— 表现就是「有权限也只弹引导」。
     * 放到 onResume + 下一帧再发，就稳了。
     */
    @Override
    protected void onResume() {
        super.onResume();
        if (fired) return;
        fired = true;
        getWindow().getDecorView().post(() ->
                WidgetPin.quickPin(this, this::finish, this::gotoSettingsWithGuide));
    }

    /** 没权限 / 系统没响应：把用户送到正常页面，由那里弹引导 */
    private void gotoSettingsWithGuide() {
        try {
            startActivity(new Intent(this, WidgetSettingsActivity.class)
                    .putExtra(EXTRA_SHOW_GUIDE, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable ignored) { }
        finish();
    }

    @Override
    public void onBackPressed() {
        finish();
    }
}
