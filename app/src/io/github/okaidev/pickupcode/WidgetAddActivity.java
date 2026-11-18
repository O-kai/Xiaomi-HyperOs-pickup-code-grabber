package io.github.okaidev.pickupcode;

import android.app.Activity;
import android.os.Bundle;

/**
 * 长按图标的「添加小部件」中转页（v4.2.1）。
 *
 * <p><b>它解决什么</b>：以前这条快捷方式指向 {@code LauncherActivity}，
 * 于是用户看到的是「点快捷方式 → 闪一下主界面 → 再进小组件设置页 → 再被弹回桌面」，
 * 一共三次跳转。这个页面是全透明的（主题里 windowIsTranslucent + 无动画），
 * 点完直接把「添加到桌面」的请求发出去：
 * <ul>
 *   <li>有权限 → 系统立刻把桌面和确认框推到前台，本页无声退出，用户看不到任何中间界面；</li>
 *   <li>没权限 → 在本页弹引导（600ms 后判定），关掉弹窗即退出。</li>
 * </ul>
 *
 * <p>为什么不直接用 {@code Theme.NoDisplay}：那种主题下没有窗口，
 * 弹不出「去打开桌面快捷方式」的引导。透明 + 无动画同样看不见，但能承载对话框。
 */
public class WidgetAddActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 被系统重建（极少见）：直接退出，别重复发请求
        if (savedInstanceState != null) {
            finish();
            return;
        }
        WidgetPin.quickPin(this, this::finish);
    }

    @Override
    public void onBackPressed() {
        finish();
    }
}
