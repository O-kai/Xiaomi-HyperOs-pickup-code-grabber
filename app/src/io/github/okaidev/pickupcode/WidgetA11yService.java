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

    private int cellW, cellH, spanX, spanY;
    private Rect lastRect;

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
                && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
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
        // 桌面切换有动画，单次 600ms 容易扑空；退避重试三次
        handler.removeCallbacks(probeTask);
        handler.postDelayed(probeTask, 600L);
        handler.postDelayed(probeTask, 1600L);
        handler.postDelayed(probeTask, 3200L);
    }

    @Override
    public void onInterrupt() {
    }

    private void probeSafely() {
        try {
            Rect rect = probe();
            if (rect != null) {
                if (!rect.equals(lastRect)) {
                    lastRect = new Rect(rect);
                    write(rect);
                }
            } else {
                Log.i(TAG, "a11y probe: no widget node");
            }
        } catch (Throwable t) {
            Log.w(TAG, "a11y probe err: " + t);
        }
    }

    private Rect probe() {
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows == null) return null;
        for (AccessibilityWindowInfo w : windows) {
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null) continue;
            CharSequence p = root.getPackageName();
            if (p == null || !isLauncher(p.toString())) continue;
            Rect host = findHost(root, 0);
            if (host == null) continue;

            Rect card = largestChildOfHost(root, host);
            if (card == null) card = host;

            // 用桌面上任意 1x1 图标的像素尺寸反推格子数（不读桌面私有库）
            int[] icon = measureIcon(root, host);
            if (icon != null) {
                cellW = icon[0];
                cellH = icon[1];
                spanX = Math.max(1, Math.min(12, Math.round(host.width() / (float) cellW)));
                spanY = Math.max(1, Math.min(14, Math.round(host.height() / (float) cellH)));
            } else {
                cellW = cellH = spanX = spanY = 0;
            }
            Log.i(TAG, "a11y card=" + card + " host=" + host
                    + " cell=" + cellW + "x" + cellH + " span=" + spanX + "x" + spanY);
            return card;
        }
        return null;
    }

    /** 找到 host 节点（组件标签 + 尺寸合理） */
    private Rect findHost(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 40) return null;
        Rect b = new Rect();
        node.getBoundsInScreen(b);
        CharSequence cd = node.getContentDescription();
        CharSequence text = node.getText();
        boolean labeled = cd != null && getString(R.string.app_name).contentEquals(cd);
        boolean title = text != null && text.toString().contains("待取件");
        if (labeled && plausible(b)) return new Rect(b);
        if (title) {
            Rect up = walkUp(node, 0);
            if (up != null) return up;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            Rect r = findHost(node.getChild(i), depth + 1);
            if (r != null) return r;
        }
        return null;
    }

    /** host 的子节点里最大的那个 = 可见卡片 */
    private Rect largestChildOfHost(AccessibilityNodeInfo node, Rect host) {
        AccessibilityNodeInfo found = findLabeledNode(node, 0);
        if (found == null) return null;
        return largestChild(found, host);
    }

    private AccessibilityNodeInfo findLabeledNode(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 40) return null;
        CharSequence cd = node.getContentDescription();
        Rect b = new Rect();
        node.getBoundsInScreen(b);
        if (cd != null && getString(R.string.app_name).contentEquals(cd) && plausible(b)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo r = findLabeledNode(node.getChild(i), depth + 1);
            if (r != null) return r;
        }
        return null;
    }

    /**
     * 桌面上的 1x1 图标：近似正方形、带标签、子节点不超过 1 个。
     *
     * <p>实测 MIUI/HyperOS 的桌面不给图标节点暴露标签（icons=0），这条只在
     * 其它 ROM 上可能生效；拿不到时写 0，调用方回退到桌面库/AppWidgetOptions。
     */
    private int[] measureIcon(AccessibilityNodeInfo node, Rect host) {
        java.util.List<Rect> squares = new java.util.ArrayList<>();
        collectIcons(node, squares, 0, host);
        Log.i(TAG, "a11y icons=" + squares.size());
        if (squares.size() < 3) return null;
        java.util.Collections.sort(squares, (a, b) -> a.width() - b.width());
        Rect m = squares.get(squares.size() / 2);
        if (m.width() < 80 || m.height() < 80) return null;
        return new int[]{m.width(), m.height()};
    }

    private void collectIcons(AccessibilityNodeInfo node, java.util.List<Rect> out, int depth, Rect host) {
        if (node == null || depth > 40 || out.size() > 40) return;
        Rect b = new Rect();
        node.getBoundsInScreen(b);
        CharSequence cd = node.getContentDescription();
        CharSequence tx = node.getText();
        boolean hasLabel = (cd != null && cd.length() > 0) || (tx != null && tx.length() > 0);
        if (hasLabel && node.getChildCount() <= 1
                && Math.abs(b.width() - b.height()) <= 8
                && b.width() >= 120 && b.width() <= 320
                && !b.contains(host)) {
            out.add(new Rect(b));
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectIcons(node.getChild(i), out, depth + 1, host);
        }
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
                    + "|" + size[0] + "|" + size[1]
                    + "|" + cellW + "|" + cellH + "|" + spanX + "|" + spanY);
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
