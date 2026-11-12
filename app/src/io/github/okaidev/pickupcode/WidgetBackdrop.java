package io.github.okaidev.pickupcode;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 组件背后那块壁纸的「快照」（v4.1.0）。
 *
 * <p><b>为什么要它</b>：早先的做法是让窗口透明、由系统把壁纸画在窗口后面。
 * 那要求预览框必须站在组件在桌面上的坐标上 —— 布局被钉死，页面没法正常排。
 *
 * <p>现在改成：把这整块区域截下来存成一张图。有了图，预览就能放在页面任何位置，
 * 也能按宽度等比缩放，跟桌面上的实际效果仍然一致。
 *
 * <p><b>怎么截</b>：先打开一个全屏透明页（{@link BackdropCaptureActivity}），
 * 此时屏幕上就是真实壁纸，然后 root 跑一次 {@code screencap}，
 * 按 {@link WidgetPlacement} 给出的矩形裁出来。全程不跳桌面、不弹权限框。
 *
 * <p><b>什么时候失效</b>：用户换壁纸。用 {@code getWallpaperId()} 比对即可，
 * 这个 API 不需要任何权限。
 */
public final class WidgetBackdrop {

    private static final String FILE = "widget_backdrop.png";
    private static final String TMP = "/data/local/tmp/pickup_bd.png";
    /**
     * 裁切时四周各外扩多少 dp（v4.1.0）。
     *
     * <p>组件本身填满格子，贴着格子边界。如果严格按矩形裁，上下就顶到边，
     * 看着很挤。桌面上格子之间本来就有空隙，把这一圈一起带进来才像真的。
     */
    private static final int PAD_DP = 12;
    private static final String PREFS = "widget_backdrop";
    private static final String K_WP_ID = "wallpaper_id";

    private WidgetBackdrop() { }

    /** 裁切时四周外扩的像素数（按当前屏幕密度换算） */
    public static int PAD_DP_PX(Context ctx) {
        try {
            return (int) (PAD_DP * ctx.getResources().getDisplayMetrics().density);
        } catch (Throwable t) {
            return 33;
        }
    }

    public static File file(Context ctx) {
        return new File(ctx.getFilesDir(), FILE);
    }

    public static boolean exists(Context ctx) {
        try {
            File f = file(ctx);
            return f.exists() && f.length() > 1024;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 读缓存的图；没有就返回 null */
    public static Bitmap load(Context ctx) {
        try {
            File f = file(ctx);
            if (!f.exists() || f.length() <= 1024) return null;
            return BitmapFactory.decodeFile(f.getAbsolutePath());
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 当前壁纸是不是和快照时不一样了。
     *
     * <p>{@code getWallpaperId} 不需要权限，每次设置壁纸都会变，
     * 正好当「该重新截一张」的信号。
     */
    public static boolean isStale(Context ctx) {
        try {
            android.app.WallpaperManager wm =
                    android.app.WallpaperManager.getInstance(ctx);
            int now = wm.getWallpaperId(android.app.WallpaperManager.FLAG_SYSTEM);
            int old = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getInt(K_WP_ID, -1);
            return old != now;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void markFresh(Context ctx) {
        try {
            android.app.WallpaperManager wm =
                    android.app.WallpaperManager.getInstance(ctx);
            int id = wm.getWallpaperId(android.app.WallpaperManager.FLAG_SYSTEM);
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putInt(K_WP_ID, id).apply();
        } catch (Throwable ignored) { }
    }

    /**
     * 截一张并存下来。**必须在透壁纸的全屏页里调用**，否则截到的是设置页自己。
     *
     * @return true = 成功
     */
    public static boolean capture(Context ctx) {
        WidgetPlacement pl = WidgetPlacement.cached(ctx);
        if (pl == null) pl = WidgetPlacement.fallback();

        // 1) 系统截屏到临时文件（root 通道，不切桌面）
        String out;
        try {
            out = RootShell.exec(
                    "rm -f " + TMP + "; "
                            + "screencap -p " + TMP + " >/dev/null 2>&1; "
                            + "chmod 644 " + TMP + " 2>/dev/null; "
                            + "ls -l " + TMP + " 2>/dev/null | wc -c", 20000);
        } catch (Throwable t) {
            return false;
        }
        if (out == null || out.trim().isEmpty() || "0".equals(out.trim())) return false;

        // 2) 解码 + 按组件矩形裁剪
        Bitmap full = null;
        Bitmap crop = null;
        try {
            full = BitmapFactory.decodeFile(TMP);
            if (full == null) return false;

            int w = full.getWidth();
            int h = full.getHeight();
            int pad = (int) (PAD_DP * ctx.getResources().getDisplayMetrics().density);
            int left = clamp((int) (w * pl.left) - pad, 0, w - 1);
            int right = clamp((int) (w * pl.right) + pad, left + 1, w);
            int top = clamp((int) (h * pl.top) - pad, 0, h - 1);
            int bottom = clamp((int) (h * pl.bottom) + pad, top + 1, h);
            crop = Bitmap.createBitmap(full, left, top, right - left, bottom - top);

            // 3) 压一下再存：预览用不上原分辨率，省内存
            int outW = Math.min(720, crop.getWidth());
            int outH = Math.max(1, (int) (crop.getHeight() * (outW / (float) crop.getWidth())));
            Bitmap scaled = Bitmap.createScaledBitmap(crop, outW, outH, true);

            FileOutputStream fos = new FileOutputStream(file(ctx));
            scaled.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.close();
            if (scaled != crop) scaled.recycle();

            markFresh(ctx);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (crop != null && crop != full) crop.recycle();
            if (full != null) full.recycle();
            try {
                RootShell.exec("rm -f " + TMP, 5000);
            } catch (Throwable ignored) { }
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
