package io.github.okaidev.pickupcode;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.io.File;
import java.io.FileWriter;
import java.util.List;

/**
 * 读取桌面小组件的屏幕矩形（4.2 验证版）。
 *
 * <p><b>为什么要有它</b>：小组件的位置只在桌面进程的 View 树里，LSPosed 在本机
 * （HyperOS + LSPosed 2.2.1）不向 {@code com.miui.home} 加载模块，拿不到。
 * 无障碍树是同一个视图层级的另一条读法：桌面会把 AppWidgetHostView 及其子 View
 * 暴露成节点，{@link AccessibilityNodeInfo#getBoundsInScreen} 就是真实像素。
 *
 * <p><b>时机</b>：{@code getWindows()} 只返回「用户能交互的窗口」，本 App 盖住桌面时
 * 读不到桌面。所以在窗口状态变化、且变化的是桌面时读一次，缓存到文件。
 *
 * <p><b>边界</b>：只读节点结构和我们自己的组件 bounds，不落盘其它内容；不执行任何操作。
 */
public class WidgetA11yService extends AccessibilityService {

    private static final String TAG = "PICKUPDEBUG";
    static final String FILE_NAME = "a11y_widget_rect.txt";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable probeTask = this::probeSafely;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "a11y service connected");
        handler.postDelayed(probeTask, 800L);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            return;
        }
        CharSequence pkg = event.getPackageName();
        // TYPE_WINDOWS_CHANGED 的 package 经常是 null，不能据此提前返回
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED || pkg == null) {
            scheduleProbe();
            return;
        }
        String p = pkg.toString();
        Log.i(TAG, "a11y event type=" + type + " pkg=" + p);
        if (!isLauncher(p)) return;
        scheduleProbe();
    }

    private void scheduleProbe() {
        // 等布局稳定
        handler.removeCallbacks(probeTask);
        handler.postDelayed(probeTask, 600L);
    }

    @Override
    public void onInterrupt() {
    }

    private void probeSafely() {
        try {
            Rect rect = probe();
            if (rect != null) write(rect);
            else Log.i(TAG, "a11y probe: no widget node");
        } catch (Throwable t) {
            Log.w(TAG, "a11y probe err: " + t);
        }
    }

    private Rect probe() {
        List<AccessibilityWindowInfo> windows = getWindows();
        Log.i(TAG, "a11y windows=" + (windows == null ? 0 : windows.size()));
        if (windows == null) return null;
        for (AccessibilityWindowInfo w : windows) {
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null) continue;
            CharSequence p = root.getPackageName();
            Rect wb = new Rect();
            w.getBoundsInScreen(wb);
            Log.i(TAG, "  win pkg=" + p + " bounds=" + wb + " children=" + root.getChildCount());
            if (p == null || !isLauncher(p.toString())) continue;
            Rect r = findCard(root, 0);
            if (r != null) return r;
        }
        return null;
    }

    private Rect findCard(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 40) return null;

        Rect b = new Rect();
        node.getBoundsInScreen(b);
        CharSequence cd = node.getContentDescription();
        CharSequence text = node.getText();

        boolean labeled = cd != null && getString(R.string.app_name).contentEquals(cd);
        boolean title = text != null && text.toString().contains("待取件");

        if (labeled && plausible(b)) {
            Rect inner = largestChild(node, b);
            Log.i(TAG, "a11y card by label: host=" + b + " inner=" + inner);
            return inner != null ? inner : new Rect(b);
        }
        if (title) {
            Rect up = walkUp(node, 0);
            if (up != null) {
                Log.i(TAG, "a11y card by title: " + up);
                return up;
            }
        }

        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            if (c == null) continue;
            Rect r = findCard(c, depth + 1);
            if (r != null) return r;
        }
        return null;
    }

    /** 容器里最大的子节点：MIUI 的 host 比可见卡片大一圈，卡片是它的第一个子 View */
    private Rect largestChild(AccessibilityNodeInfo host, Rect hostRect) {
        Rect best = null;
        int n = host.getChildCount();
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo c = host.getChild(i);
            if (c == null) continue;
            Rect b = new Rect();
            c.getBoundsInScreen(b);
            if (b.width() <= 0 || b.height() <= 0) continue;
            if (b.width() > hostRect.width() || b.height() > hostRect.height()) continue;
            if (b.width() >= hostRect.width() && b.height() >= hostRect.height()) continue;
            if (best == null || b.width() * b.height() > best.width() * best.height()) best = new Rect(b);
        }
        return best;
    }

    /** 从文字节点往上找组件的容器：宽度 ≥ 2 格、高度 ≥ 1 格、且不是整屏 */
    private Rect walkUp(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 8) return null;
        Rect b = new Rect();
        node.getBoundsInScreen(b);
        if (plausible(b)) return b;
        return walkUp(node.getParent(), depth + 1);
    }

    private boolean plausible(Rect b) {
        int w = b.width(), h = b.height();
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        return w >= 400 && w <= sw && h >= 200 && h <= sh * 0.75f;
    }

    private void write(Rect r) {
        try {
            File f = new File(getFilesDir(), FILE_NAME);
            FileWriter w = new FileWriter(f, false);
            int[] size = screenSize();
            w.write("RECT|0|" + r.left + "|" + r.top + "|" + r.right + "|" + r.bottom
                    + "|" + size[0] + "|" + size[1]);
            w.close();
            Log.i(TAG, "a11y rect written: " + r);
        } catch (Throwable t) {
            Log.w(TAG, "a11y write err: " + t);
        }
    }

    /** 真实屏幕尺寸（含系统栏）；getDisplayMetrics 会少掉状态栏和导航栏 */
    private int[] screenSize() {
        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (wm != null && Build.VERSION.SDK_INT >= 30) {
                Rect b = wm.getMaximumWindowMetrics().getBounds();
                return new int[]{b.width(), b.height()};
            }
        } catch (Throwable ignored) { }
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return new int[]{dm.widthPixels, dm.heightPixels};
    }

    private static boolean isLauncher(String pkg) {
        for (String p : new String[]{
                "com.miui.home", "com.hihonor.home", "com.android.launcher3",
                "com.google.android.apps.nexuslauncher", "com.coloros.launcher",
                "com.oppo.launcher", "com.bbk.launcher2", "com.sec.android.app.launcher"}) {
            if (p.equals(pkg)) return true;
        }
        return false;
    }
}
