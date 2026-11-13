package io.github.okaidev.pickupcode;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

/**
 * 一次性的「背景抓取页」（v4.1.0）。
 *
 * <p>它只干一件事：把窗口做成全屏透明，让系统把真实壁纸画在下面，
 * 然后让 {@link WidgetBackdrop} 截一张、裁出组件那块、存好，随即关闭。
 *
 * <p>为什么不用「跳回桌面再截」：那要切前台、等图标加载、再切回来，
 * 又慢又容易被系统拦。这里屏幕内容和桌面壁纸是同一份，截下来完全等价。
 *
 * <p>用户看到的是闪一下（约半秒），没有按钮、没有内容。
 */
public class BackdropCaptureActivity extends Activity {

    private static final long SETTLE_MS = 450;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        UiKit.applyTheme(this, true);
        super.onCreate(savedInstanceState);

        try {
            getWindow().addFlags(
                    android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
        } catch (Throwable ignored) { }

        // 状态栏那一条（48dp）默认被窗口底色（页面灰）盖住，截出来的壁纸顶部
        // 会带一条 25px 的灰带 —— 预览里就成了「顶部几乎没有留白」。
        // 把系统栏也让给壁纸，并在下面用 LAYOUT_FULLSCREEN 铺满。
        try {
            getWindow().setStatusBarColor(0x00000000);
            getWindow().setNavigationBarColor(0x00000000);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        } catch (Throwable ignored) { }

        // 纯透明：不放任何可见内容，壁纸才是主角
        View blank = new View(this);
        blank.setBackgroundColor(0x00000000);
        setContentView(blank);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            new Thread(() -> {
                boolean ok = false;
                try {
                    ok = WidgetBackdrop.capture(BackdropCaptureActivity.this);
                } catch (Throwable ignored) { }
                final boolean done = ok;
                runOnUiThread(() -> {
                    try {
                        setResult(done ? RESULT_OK : RESULT_CANCELED);
                        finish();
                        overridePendingTransition(0, 0);
                    } catch (Throwable ignored) { }
                });
            }, "backdrop-capture").start();
        }, SETTLE_MS);
    }

    @Override
    public void finish() {
        super.finish();
        // 进出都不要动画，避免「闪一下」变成「滑一下」
        overridePendingTransition(0, 0);
    }
}
