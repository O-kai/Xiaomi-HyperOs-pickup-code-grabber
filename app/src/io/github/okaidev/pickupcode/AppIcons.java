package io.github.okaidev.pickupcode;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.ImageView;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 应用图标（v4.0.0）
 *
 * 加载链（逐级回退）：
 *   ① assets/appicons/&lt;slug&gt;.png          —— 官方位图（App Store 抓取，见 prototype/icons/）
 *   ② res/drawable/app_&lt;slug&gt;.xml          —— 真实图标矢量版（由 prototype/icons/&lt;slug&gt;.svg 生成，v4.0.1）
 *   ③ res/drawable/app_fallback_&lt;slug&gt;.xml —— 自绘兜底（不含第三方素材）
 * 去掉①的 PNG 与②的矢量，界面即回到纯自绘版本。
 */
public final class AppIcons {

    private AppIcons() { }

    /** 通知取件内置白名单的包名（顺序与 NotiHook.BUILTIN_PKGS 一致，用于列表展示） */
    public static final String[] PKGS = NotiHook.BUILTIN_PKGS;

    private static final Map<String, String> SLUG = new HashMap<>();
    private static final Map<String, String> NAME = new HashMap<>();
    private static final Map<String, Integer> FALLBACK = new HashMap<>();
    /** 真实图标的矢量版（prototype/icons/<slug>.svg → app_<slug>.xml） */
    private static final Map<String, Integer> REAL = new HashMap<>();
    private static final Map<String, Bitmap> CACHE = new HashMap<>();

    static {
        put("com.cainiao.wireless", "cainiao", "菜鸟", R.drawable.app_fallback_cainiao);
        put("com.cainiao.guoguo", "cainiao-guoguo", "菜鸟裹裹", R.drawable.app_fallback_cainiao_guoguo);
        put("com.jingdong.app.mall", "jd", "京东", R.drawable.app_fallback_jd);
        put("com.taobao.taobao", "taobao", "淘宝", R.drawable.app_fallback_taobao);
        put("com.xunmeng.pinduoduo", "pinduoduo", "拼多多", R.drawable.app_fallback_pinduoduo);
        put("com.sf.cdsibm", "sf", "顺丰速运", R.drawable.app_fallback_sf);
        // v4.0.2：丰巢（取件码大户）——图标换成从用户真机上取的实际应用图标
        // （黄色六边形 + 黑色蜂巢纹样），此前是自绘兜底，与真实图标差别明显。
        put("com.fcbox.hiveconsumer", "fengchao", "丰巢", R.drawable.app_fallback_fengchao);
        // v4.0.2：设备互联——图标换成从用户真机上取的实际应用图标
        // （蓝底 #439DF6 + 白色 N 形箭头），此前是自绘兜底。
        put("com.xiaomi.mi_connect_service", "mi_connect", "设备互联",
                R.drawable.app_fallback_mi_connect);
        // v4.0.1：菜鸟裹裹没有可公开抓取的位图，用用户提供的矢量图标（SVG → 矢量图）
        REAL.put("cainiao-guoguo", R.drawable.app_cainiao_guoguo);
    }

    private static void put(String pkg, String slug, String name, int fallback) {
        SLUG.put(pkg, slug);
        NAME.put(pkg, name);
        FALLBACK.put(pkg, fallback);
    }

    public static String nameOf(String pkg) {
        String n = NAME.get(pkg);
        return n != null ? n : pkg;
    }

    /** 把图标装进 ImageView：官方 PNG 优先，失败回退自绘矢量 */
    public static void bind(ImageView iv, android.content.Context ctx, String pkg) {
        Bitmap bmp = load(ctx, SLUG.get(pkg));
        if (bmp != null) {
            iv.clearColorFilter();
            iv.setImageBitmap(bmp);
            return;
        }
        Integer real = REAL.get(SLUG.get(pkg));
        if (real != null) {
            iv.setImageResource(real);
            return;
        }
        Integer f = FALLBACK.get(pkg);
        if (f != null) {
            iv.setImageResource(f);
            return;
        }
        // v4.8：非内置包名（用户手动添加的任意 App）→ 取系统里该应用的真实图标。
        // 之前这里直接落到 app_fallback_cainiao，导致手动添加的每个 App
        // 都显示成菜鸟图标。现在先试系统图标，真取不到才用通用兜底。
        if (bindSystemIcon(iv, ctx, pkg)) return;
        iv.setImageResource(R.drawable.app_fallback_cainiao);
    }

    /**
     * v4.8：取系统里某个包的真实应用图标。
     *
     * @return true=已成功设置图标；false=该包未安装或取图标失败（调用方继续兜底）
     */
    public static boolean bindSystemIcon(ImageView iv, android.content.Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        try {
            android.graphics.drawable.Drawable d =
                    ctx.getPackageManager().getApplicationIcon(pkg);
            if (d == null) return false;
            iv.clearColorFilter();
            iv.setImageDrawable(d);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Bitmap load(android.content.Context ctx, String slug) {
        if (slug == null) return null;
        synchronized (CACHE) {
            if (CACHE.containsKey(slug)) return CACHE.get(slug);
        }
        Bitmap bmp = null;
        try (InputStream in = ctx.getAssets().open("appicons/" + slug + ".png")) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            bmp = BitmapFactory.decodeStream(in, null, o);
        } catch (Throwable ignored) {
            // 文件不存在 / 解码失败 → 走矢量兜底
        }
        synchronized (CACHE) {
            CACHE.put(slug, bmp);
        }
        return bmp;
    }
}
