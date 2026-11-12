package io.github.okaidev.pickupcode;

import android.app.Activity;
import android.appwidget.AppWidgetHostView;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;

import java.io.File;
import java.io.FileWriter;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 在「系统桌面」进程里读出小组件真实占的像素矩形（v4.1.0）。
 *
 * <p><b>为什么需要它</b>：Android 没有公开 API 能给小组件在桌面上的位置。
 * 之前只能读桌面数据库拿「第几行第几列」，再按「格子 = 屏幕宽 ÷ 列数」换算 ——
 * 格子尺寸算得准，但「第一行从哪里开始」拿不到，只能按屏幕高度估一个比例。
 *
 * <p>而桌面进程自己的 View 树里就有答案：{@link AppWidgetHostView} 是承载小组件的
 * 那个 View，{@code getLocationOnScreen()} + {@code getWidth()/getHeight()}
 * 就是它在屏幕上的真实矩形，和「显示布局边界」画出来的框完全一致。
 *
 * <p><b>做法</b>：在桌面进程 hook {@code Activity.onResume}，等布局稳定后遍历一次
 * View 树，把所有 {@link AppWidgetHostView} 的 id 与矩形写进桌面自己的私有目录。
 * App 那边用 root 读一次，读到就用真实像素，读不到退回格子换算。
 *
 * <p><b>边界</b>：只读 View 的属性，不改任何东西；每个进程只在 resume 时跑一次，
 * 遍历深度有上限，不会拖慢桌面。
 */
public final class LauncherProbe {

    private static final String TAG = "PICKUPDEBUG";

    /** 写进桌面的私有目录：App 用 root 读，不涉及跨进程写权限 */
    static final String FILE_NAME = "pickup_widget_rect.txt";

    private LauncherProbe() { }

    /**
     * 在桌面进程安装探针。
     *
     * @param cl      桌面进程的 ClassLoader
     * @param dataDir 桌面的 dataDir（{@code lpparam.appInfo.dataDir}）
     */
    public static void install(ClassLoader cl, final String dataDir, final String pkg) {
        try {
            Class<?> activity = XposedHelpers.findClass("android.app.Activity", cl);
            XposedBridge.hookAllMethods(activity, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object self = param.thisObject;
                    if (!(self instanceof Activity)) return;
                    Activity a = (Activity) self;
                    // 只认桌面自己的 Activity：包名匹配即可，不写死类名
                    // （小米是 com.miui.home.launcher.Launcher，其他 ROM 各不相同）
                    if (!pkg.equals(a.getPackageName())) return;
                    // 布局还没完成，等一帧多一点再量
                    new Handler(Looper.getMainLooper()).postDelayed(
                            () -> probe(a, dataDir, pkg), 800L);
                }
            });
            log("LauncherProbe installed @ " + pkg);
        } catch (Throwable t) {
            log("LauncherProbe install err: " + t);
        }
    }

    private static void probe(Activity a, String dataDir, String pkg) {
        try {
            View root = a.getWindow() == null ? null : a.getWindow().getDecorView();
            if (root == null) return;

            StringBuilder sb = new StringBuilder();
            int screenW = a.getResources().getDisplayMetrics().widthPixels;
            int screenH = a.getResources().getDisplayMetrics().heightPixels;
            collect(root, sb, 0, screenW, screenH);

            if (sb.length() == 0) return;   // 桌面还没有组件，不覆盖旧数据

            File dir = new File(dataDir, "files");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, FILE_NAME);
            FileWriter w = new FileWriter(f, false);
            w.write(sb.toString());
            w.close();
            try {
                // 让 App 侧（root 读）一定能读到
                f.setReadable(true, false);
            } catch (Throwable ignored) { }
            log("LauncherProbe wrote " + sb.toString().trim());
        } catch (Throwable t) {
            log("LauncherProbe probe err: " + t);
        }
    }

    /**
     * 递归找 {@link AppWidgetHostView}。
     *
     * <p>深度上限 40 层：桌面 View 树实际不到 20 层，留一倍余量，
     * 避免某些 ROM 的自定义容器形成环（虽然 View 树理论上不会有环）。
     */
    private static void collect(View v, StringBuilder sb, int depth,
                                int screenW, int screenH) {
        if (v == null || depth > 40) return;

        if (v instanceof AppWidgetHostView) {
            AppWidgetHostView host = (AppWidgetHostView) v;
            if (v.getWidth() > 0 && v.getHeight() > 0) {
                int[] loc = new int[2];
                v.getLocationOnScreen(loc);
                int id;
                try {
                    id = host.getAppWidgetId();
                } catch (Throwable t) {
                    id = -1;
                }
                // id|left|top|right|bottom|screenW|screenH
                sb.append(id).append('|')
                        .append(loc[0]).append('|')
                        .append(loc[1]).append('|')
                        .append(loc[0] + v.getWidth()).append('|')
                        .append(loc[1] + v.getHeight()).append('|')
                        .append(screenW).append('|')
                        .append(screenH).append('\n');
            }
        }

        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collect(g.getChildAt(i), sb, depth + 1, screenW, screenH);
            }
        }
    }

    private static void log(String msg) {
        try {
            android.util.Log.d(TAG, msg);
        } catch (Throwable ignored) { }
        try {
            XposedBridge.log("PickupCode/" + msg);
        } catch (Throwable ignored) { }
    }
}
