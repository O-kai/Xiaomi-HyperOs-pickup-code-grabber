package io.github.okaidev.pickupcode;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;

/**
 * 桌面小组件在屏幕上的实际矩形（v4.1.0）。
 *
 * <p><b>为什么需要它</b>：外观页的预览要 1:1，就必须让预览框「站」在组件
 * 在桌面上的那个位置。壁纸是系统按屏幕坐标铺的 —— 位置差一截，底下露出来的
 * 就是另一块壁纸（上下明暗交错时，效果完全不同）。
 *
 * <p><b>位置从哪来</b>：桌面的布局数据库。{@code favorites} 表里躺着
 * {@code cellX / cellY / spanX / spanY}，配 {@code appWidgetId} 就能定位到我们那个组件。
 *
 * <p>Android 没有公开 API 能给这个：{@link AppWidgetManager} 只给宽高、不给位置，
 * 位置被当作 Launcher 的私有信息。所以这里要走 root 读库。
 *
 * <p><b>哪些来自系统、哪些是比例</b>：
 * <ul>
 *   <li>占哪几格（cellX/cellY/spanX/spanY）—— Launcher 的数据，来自桌面库或
 *       {@code dumpsys activity top} 的 model dump，设备无关；</li>
 *   <li>网格几行几列 —— 由「桌面上用到的最右/最下边界」推断，设备无关；</li>
 *   <li>横向像素 —— 屏幕宽 ÷ 列数，设备无关（实测 400dp ÷ 5 = 80dp/列）；</li>
 *   <li>纵向上下边界 —— <b>只能用比例</b>：Android 没有公开 API 给 workspace 的
 *       上下留白（状态栏到 dock 之间），Launcher 的 DeviceProfile 也不落盘。
 *       {@link #WS_TOP}/{@link #WS_BOTTOM} 是比例而不是像素，换分辨率会等比缩放。</li>
 * </ul>
 *
 * <p>试过但拿不到的：{@code getAppWidgetOptions()} 返回 257×120dp，
 * 是 Launcher 按我们声明的 minWidth 填的，不是格子尺寸；
 * {@code dumpsys activity top} 只有 model 没有 View 树；
 * MiuiHome 的 resources.arsc 里没有 dimen（网格是代码算的）。
 */
public final class WidgetPlacement {

    private static final String PREFS = "widget_placement";
    private static final String K_TOP = "top";
    private static final String K_BOTTOM = "bottom";
    private static final String K_LEFT = "left";
    private static final String K_RIGHT = "right";
    private static final String K_ROWS = "rows";
    private static final String K_COLS = "cols";
    private static final String K_CELL_Y = "cellY";
    private static final String K_SPAN_Y = "spanY";
    private static final String K_CELL_X = "cellX";
    private static final String K_SPAN_X = "spanX";
    private static final String K_WIDGET_ID = "widgetId";

    /**
     * 网格顶部的留白，占屏幕高度的比例（v4.1.0 第三版）。
     *
     * <p>桌面的格子是<b>正方形</b>：每格边长 = 屏幕宽 ÷ 列数。
     * 实测（K90，400dp 宽 5 列）：1200 ÷ 5 = 240px = 80dp，
     * 而「尺寸调节」蓝框恰好是 240px 一格（4×2 的框高 479px，2 格）。
     *
     * <p>所以横向、纵向的格子尺寸都不用猜，只有「网格第一行从哪里开始」需要基准。
     * 它等于屏幕高减去「行数 × 格高」和 dock 之后剩下的顶部那一截，
     * 在小米桌面上是 17dp（约屏高 2%）。这个比例只在同一 Launcher 家族内稳定，
     * 所以另外用 {@link #cellPx} 夹一道，避免行数多、格子大时算出屏幕外的位置。
     */
    private static final float WS_TOP_RATIO = 0.02f;

    /** 网格最多占屏幕高度的多少（剩下的是 dock 与导航栏）；防止格子算得过高压出屏幕 */
    private static final float WS_MAX_RATIO = 0.86f;

    /** 常见的桌面包名。命中一个就够了，全部找不到才放弃。 */
    private static final String[] LAUNCHER_PKGS = {
            "com.miui.home",                        // 小米 / HyperOS
            "com.hihonor.home",                     // 荣耀
            "com.android.launcher3",                // AOSP
            "com.google.android.apps.nexuslauncher",// Pixel
            "com.coloros.launcher",                 // ColorOS
            "com.oppo.launcher",
            "com.bbk.launcher2",                    // vivo
            "com.sec.android.app.launcher"          // 三星
    };

    /** 相对屏幕的比例，0~1 */
    public float top, bottom, left, right;
    public int cellX, cellY, spanX, spanY, rows = 9, cols = 5;
    /** 这条记录属于哪个组件实例。换了组件（id 变了）缓存就该作废。 */
    public int widgetId = -1;
    /** true = 从桌面数据库读到的；false = 估的默认位置 */
    public boolean fromDb;
    /** true = 用桌面进程给的真实 View 矩形（比格子换算更准） */
    public boolean fromLauncher;
    /** v4.2：矩形来自无障碍服务（不需要 root） */
    public boolean fromA11y;
    /** 无障碍测到的单格像素尺寸（0 = 没测到） */
    public int cellWpx, cellHpx;
    /** 这份数据是什么时候拿到的（毫秒），用于和无障碍文件比新旧 */
    public long savedAt;

    // ==================== 对外 ====================

    /**
     * 拿组件的位置。优先用缓存，没有就去读桌面数据库。
     *
     * <p>会走 su，所以调用方必须是「用户主动点了某个按钮」的时机，
     * 不能在打开页面时自动跑（那会弹 Magisk 授权）。
     */
    public static WidgetPlacement detect(Context ctx) {
        WidgetPlacement cached = load(ctx);
        if (cached != null) return cached;
        // 无障碍写出的矩形最便宜：不碰 su，也不依赖桌面库
        WidgetPlacement a11y = readA11yRect(ctx);
        if (a11y != null) {
            save(ctx, a11y);
            return a11y;
        }
        WidgetPlacement p = readFromDb(ctx);
        if (p != null) {
            save(ctx, p);
            return p;
        }
        return null;
    }

    /**
     * 强制重测（v4.1.0）：跳过缓存，直接读一次桌面数据库。
     *
     * <p>用户把组件拉大拉小、或者换了位置之后，点「重新定位」走这里。
     * 读到了会顺带刷新缓存。
     */
    public static WidgetPlacement remeasure(Context ctx) {
        WidgetPlacement a11y = readA11yRect(ctx);
        if (a11y != null) {
            save(ctx, a11y);
            return a11y;
        }
        WidgetPlacement p = readFromDb(ctx);
        if (p != null) save(ctx, p);
        return p;
    }

    /** 只读缓存，不碰 su。页面初始化时用这个。 */
    public static WidgetPlacement cached(Context ctx) {
        WidgetPlacement p = load(ctx);
        // 无障碍矩形是像素级的，只要文件在就优先用它。
        // 用户要挪组件必然在桌面上，服务那时已经刷新过文件，不存在"用旧位置"的情况。
        WidgetPlacement a11y = readA11yRect(ctx);
        if (a11y != null) {
            save(ctx, a11y);
            return a11y;
        }
        if (p == null) return null;
        // 缓存属于另一个组件实例（用户删了重加、或换了尺寸）→ 作废。
        // 否则会把上一个组件的形状套到新组件上（真机上出现过竖长条预览）。
        int cur = firstWidgetId(ctx);
        if (p.widgetId > 0 && cur > 0 && p.widgetId != cur) return null;
        return p;
    }

    /**
     * 读不到真实位置时的兜底：假设组件横跨整宽、垂直居中偏上。
     *
     * <p>大多数人的小组件都在这个范围，误差可接受；比「完全不预览」好得多。
     */
    public static WidgetPlacement fallback(Context ctx) {
        WidgetPlacement p = new WidgetPlacement();
        // 按「4 列 × 2 行、居中偏上」估：这是最常见的添加尺寸，
        // 也是示例预览该有的样子 —— 横条，不是竖长条。
        p.cols = 5;
        p.rows = 9;
        p.cellX = 0;
        p.cellY = 2;
        p.spanX = 4;
        p.spanY = 2;
        p.fromDb = false;
        int[] size = screenSize(ctx);
        p.toRatios(size[0], size[1]);
        return p;
    }

    /**
     * 桌面上有没有放我们的组件（v4.1.0）。
     *
     * <p>没有组件时，位置、背景、实时预览全都没有意义 —— 页面要换个样子，
     * 否则用户看到一块空白的「预览」会很困惑。
     */
    public static boolean hasWidget(Context ctx) {
        return firstWidgetId(ctx) >= 0;
    }

    public static void forget(Context ctx) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
        } catch (Throwable ignored) { }
    }

    /**
     * 无障碍服务写下的矩形（v4.2）。
     *
     * <p>格式：{@code RECT|id|left|top|right|bottom|screenW|screenH|cellW|cellH|spanX|spanY}，
     * 后四项可能为 0（服务没测到图标）。这是唯一不需要 root、也不需要 LSPosed 的来源。
     */
    private static WidgetPlacement readA11yRect(Context ctx) {
        try {
            java.io.File f = new java.io.File(ctx.getFilesDir(), "a11y_widget_rect.txt");
            if (!f.exists()) return null;
            String line = null;
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) {
                line = r.readLine();
            }
            if (line == null || !line.startsWith("RECT|")) return null;
            String[] v = line.split("\\|");
            if (v.length < 8) return null;
            int l = Integer.parseInt(v[2].trim());
            int t = Integer.parseInt(v[3].trim());
            int rr = Integer.parseInt(v[4].trim());
            int b = Integer.parseInt(v[5].trim());
            int sw = Integer.parseInt(v[6].trim());
            int sh = Integer.parseInt(v[7].trim());
            if (sw <= 0 || sh <= 0 || rr <= l || b <= t || rr > sw || b > sh) return null;

            WidgetPlacement p = new WidgetPlacement();
            p.widgetId = firstWidgetId(ctx);
            p.left = l / (float) sw;
            p.right = rr / (float) sw;
            p.top = t / (float) sh;
            p.bottom = b / (float) sh;
            p.fromDb = true;
            p.fromLauncher = true;
            p.fromA11y = true;
            p.savedAt = f.lastModified();
            if (v.length >= 12) {
                p.cellWpx = parseIntOr(v[8], 0);
                p.cellHpx = parseIntOr(v[9], 0);
                p.spanX = parseIntOr(v[10], 0);
                p.spanY = parseIntOr(v[11], 0);
            }
            if (p.spanX <= 0 || p.spanY <= 0) {
                p.spanX = 0;
                p.spanY = 0;
            }
            android.util.Log.d("PICKUPDEBUG", "使用无障碍矩形: " + l + "," + t + " - " + rr + "," + b
                    + " cell=" + p.cellWpx + "x" + p.cellHpx + " span=" + p.spanX + "x" + p.spanY);
            return p;
        } catch (Throwable t) {
            android.util.Log.w("PICKUPDEBUG", "读无障碍矩形失败: " + t);
            return null;
        }
    }

    // ==================== 读桌面数据库 ====================

    private static WidgetPlacement readFromDb(Context ctx) {
        final int widgetId = firstWidgetId(ctx);
        if (widgetId < 0) return null;

        // 一次 su 里把所有候选包走完。
        //
        // 注意：**不能用 exit** —— RootShell 是常驻 shell，
        // exit 会把整个通道杀掉，后面的结束标记读不到，调用方只能等超时
        // （实测踩过：20 秒后 read timeout，输出永远是 null）。
        // 所以用 RES 标志位层层 break。
        StringBuilder sb = new StringBuilder();
        sb.append("RES=''; for P in");
        for (String pkg : LAUNCHER_PKGS) sb.append(' ').append(pkg);
        sb.append("; do ");
        sb.append("[ -n \"$RES\" ] && break; ");
        sb.append("for B in /data/user_de/0 /data/data; do ");
        sb.append("[ -n \"$RES\" ] && break; ");
        sb.append("for F in $B/$P/databases/launcher*.db; do ");
        sb.append("[ -f \"$F\" ] || continue; ");
        // 一条 SQL 同时取：组件占哪几格 + 整个桌面用到的最右/最下边界。
        // 后者就是网格的行列数 —— 只要桌面上有图标贴到边（几乎必然），推断就准。
        // 不依赖「文件名里写了 5x9」这种小米特有的命名，别的 ROM 也能用。
        sb.append("R=$(").append(RuntimePaths.SQLITE).append(" \"$F\" ")
          // 用逗号拼成一个字段：sqlite3 默认的列分隔符是竖线，
          // 和外面 HIT|路径| 的竖线会撞车（踩过）
          .append("\"select cellX||','||cellY||','||spanX||','||spanY||','||")
          .append("ifnull((select max(cellX+spanX) from favorites where container=-100),0)||','||")
          .append("ifnull((select max(cellY+spanY) from favorites where container=-100),0) ")
          .append("from favorites where appWidgetId=").append(widgetId)
          .append(" and itemType=4 limit 1\" 2>/dev/null); ");
        sb.append("[ -n \"$R\" ] && { RES=\"HIT|$F|$R\"; break; }; ");
        sb.append("done; done; done; ");
        sb.append("[ -n \"$RES\" ] && echo \"$RES\" || echo NONE; ");
        // v4.1.0：桌面进程（LauncherProbe）会把组件的真实像素矩形写到它自己的
        // files/ 下。这里顺带读一次 —— 有它就用真实边界，没有就退回格子换算。
        sb.append("RB=''; for Q in");
        for (String pkg : LAUNCHER_PKGS) sb.append(' ').append(pkg);
        sb.append("; do ");
        sb.append("F2=/data/data/$Q/files/").append(LauncherProbe.FILE_NAME).append("; ");
        sb.append("[ -f \"$F2\" ] || continue; ");
        sb.append("RB=$(grep -m1 \"^").append(widgetId).append("|\" \"$F2\" 2>/dev/null); ");
        sb.append("[ -n \"$RB\" ] && break; ");
        sb.append("done; ");
        sb.append("echo \"RECT|$RB\"");

        String out;
        try {
            out = RootShell.exec(sb.toString(), 20000);
        } catch (Throwable t) {
            android.util.Log.w("PICKUPDEBUG", "placement exec 抛错: " + t);
            return null;
        }
        android.util.Log.d("PICKUPDEBUG", "placement id=" + widgetId + " 输出=" + out);
        if (out == null) return null;
        out = out.trim();
        int hit = out.indexOf("HIT|");
        if (hit < 0) return null;

        // 输出现在有两段：HIT|路径|数据 和 RECT|id|left|top|right|bottom|w|h
        String rectLine = null;
        String hitLine = null;
        for (String l : out.split("\n")) {
            String t = l.trim();
            if (t.startsWith("HIT|")) hitLine = t;
            else if (t.startsWith("RECT|")) rectLine = t;
        }
        if (hitLine == null) return null;

        try {
            String[] seg = hitLine.split("\\|");
            if (seg.length < 3) return null;
            String dbPath = seg[1];
            String[] v = seg[2].trim().split(",");
            if (v.length < 4) return null;

            WidgetPlacement p = new WidgetPlacement();
            p.cellX = Integer.parseInt(v[0].trim());
            p.cellY = Integer.parseInt(v[1].trim());
            p.spanX = Integer.parseInt(v[2].trim());
            p.spanY = Integer.parseInt(v[3].trim());
            p.widgetId = widgetId;
            p.fromDb = true;

            // 网格行列：优先用「桌面用到的最右/最下边界」推断。
            // 只有当推断值不比组件自身大（说明桌面上没有别的图标贴到边，
            // 这种情况极罕见）时，才退回文件名里的规格。
            int inferredCols = v.length > 4 ? parseIntOr(v[4], 0) : 0;
            int inferredRows = v.length > 5 ? parseIntOr(v[5], 0) : 0;
            int[] fromName = gridFromName(dbPath);
            p.cols = (inferredCols > p.spanX && inferredCols <= 12) ? inferredCols : fromName[0];
            p.rows = (inferredRows > p.spanY && inferredRows <= 14) ? inferredRows : fromName[1];

            int[] size = screenSize(ctx);
            p.toRatios(size[0], size[1]);

            // 桌面进程给的矩形优先：它是 View 的真实边界（等同「显示布局边界」那个框），
            // 不需要任何比例常量。
            applyLauncherRect(p, rectLine);
            return p;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 用桌面写下的真实矩形覆盖换算结果（v4.1.0）。
     *
     * <p>格式：{@code RECT|id|left|top|right|bottom|screenW|screenH}，
     * 由 {@link LauncherProbe} 在桌面进程里从 {@code AppWidgetHostView}
     * 的 {@code getLocationOnScreen()} 与宽高直接读出。
     *
     * <p>读不到（没勾桌面作用域、桌面还没重启、或数据是空的）就保持格子换算的结果。
     */
    private static void applyLauncherRect(WidgetPlacement p, String rectLine) {
        if (rectLine == null) return;
        try {
            String[] r = rectLine.split("\\|");
            if (r.length < 8) return;
            int left = Integer.parseInt(r[2].trim());
            int top = Integer.parseInt(r[3].trim());
            int right = Integer.parseInt(r[4].trim());
            int bottom = Integer.parseInt(r[5].trim());
            int sw = Integer.parseInt(r[6].trim());
            int sh = Integer.parseInt(r[7].trim());
            if (sw <= 0 || sh <= 0 || right <= left || bottom <= top) return;

            p.left = left / (float) sw;
            p.right = right / (float) sw;
            p.top = top / (float) sh;
            p.bottom = bottom / (float) sh;
            p.fromDb = true;
            p.fromLauncher = true;
            android.util.Log.d("PICKUPDEBUG", "使用桌面真实矩形: "
                    + left + "," + top + " - " + right + "," + bottom);
        } catch (Throwable t) {
            android.util.Log.w("PICKUPDEBUG", "解析桌面矩形失败: " + t);
        }
    }

    private static int parseIntOr(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    /**
     * 从 {@code launcher5x9.db} 这种文件名里读出列×行。
     *
     * <p>这只是<b>第二选择</b>：绝大多数情况下网格已经从数据库边界推断出来了，
     * 这里只在推断不可用时兜底。最后的 {@code 5×9} 是「连文件名都读不出」时的
     * 保底值，仅仅为了让预览框有个初始尺寸，不是假设用户都长这样。
     */
    private static int[] gridFromName(String path) {
        try {
            String name = path.substring(path.lastIndexOf('/') + 1);
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("(\\d+)[xX](\\d+)").matcher(name);
            if (m.find()) {
                int c = Integer.parseInt(m.group(1));
                int r = Integer.parseInt(m.group(2));
                if (c >= 3 && c <= 8 && r >= 4 && r <= 12) return new int[]{c, r};
            }
        } catch (Throwable ignored) { }
        return new int[]{5, 9};
    }

    /**
     * 把格子坐标换算成屏幕比例（v4.1.0 第三版）。
     *
     * <p>格子是正方形，边长 = 屏幕宽 ÷ 列数。这个值不需要任何校准：
     * 桌面的横向就是「列数等分屏幕宽」，实测 1200 ÷ 5 = 240px 与调整框完全一致。
     *
     * <p>纵向：网格顶部有一小段留白（状态栏下方那一截），
     * 取屏高的固定比例；再用 {@link #WS_MAX_RATIO} 夹住，
     * 避免列数少、格子大时算出屏幕外。
     */
    private void toRatios(int screenW, int screenH) {
        if (screenW <= 0 || screenH <= 0) {
            // 拿不到屏幕尺寸时退回旧的固定比例，至少不是 NaN
            top = 0.33f;
            bottom = 0.62f;
            left = 0f;
            right = 1f;
            return;
        }
        float cell = (float) screenW / Math.max(1, cols);
        float maxCell = screenH * WS_MAX_RATIO / Math.max(1, rows);
        if (cell > maxCell) cell = maxCell;

        float wsTop = screenH * WS_TOP_RATIO;
        top = (wsTop + cellY * cell) / screenH;
        bottom = (wsTop + (cellY + spanY) * cell) / screenH;
        left = (cellX * cell) / screenW;
        right = ((cellX + spanX) * cell) / screenW;

        // 夹一下，防止脏数据把预览推到屏幕外
        top = Math.max(0f, Math.min(0.95f, top));
        bottom = Math.max(top + 0.05f, Math.min(1f, bottom));
        left = Math.max(0f, Math.min(0.95f, left));
        right = Math.max(left + 0.05f, Math.min(1f, right));
    }

    /** 真实屏幕尺寸（含系统栏），换算格子必须用这个 */
    private static int[] screenSize(Context ctx) {
        try {
            android.view.WindowManager wm =
                    (android.view.WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Rect b = wm.getMaximumWindowMetrics().getBounds();
                    return new int[]{b.width(), b.height()};
                }
                android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
                wm.getDefaultDisplay().getRealMetrics(dm);
                return new int[]{dm.widthPixels, dm.heightPixels};
            }
        } catch (Throwable ignored) { }
        android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        return new int[]{dm.widthPixels, dm.heightPixels};
    }

    private static int firstWidgetId(Context ctx) {
        try {
            AppWidgetManager awm = AppWidgetManager.getInstance(ctx);
            // 用字符串形式的 ComponentName：不触发 PickupWidgetProvider 的类加载，
            // 与 WidgetPresence 保持同一套写法。
            int[] ids = awm.getAppWidgetIds(
                    new ComponentName(ctx.getPackageName(), WidgetPresence.PROVIDER_CLASS));
            if (ids != null && ids.length > 0) return ids[0];
        } catch (Throwable ignored) { }
        return -1;
    }

    // ==================== 缓存 ====================

    private static WidgetPlacement load(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (!sp.contains(K_TOP)) return null;
            WidgetPlacement p = new WidgetPlacement();
            p.top = sp.getFloat(K_TOP, 0.33f);
            p.bottom = sp.getFloat(K_BOTTOM, 0.62f);
            p.left = sp.getFloat(K_LEFT, 0f);
            p.right = sp.getFloat(K_RIGHT, 1f);
            p.rows = sp.getInt(K_ROWS, 9);
            p.cols = sp.getInt(K_COLS, 5);
            p.cellX = sp.getInt(K_CELL_X, 0);
            p.cellY = sp.getInt(K_CELL_Y, 0);
            p.spanX = sp.getInt(K_SPAN_X, 1);
            p.spanY = sp.getInt(K_SPAN_Y, 1);
            p.widgetId = sp.getInt(K_WIDGET_ID, -1);
            p.fromLauncher = sp.getBoolean("from_launcher", false);
            p.fromA11y = sp.getBoolean("from_a11y", false);
            p.savedAt = sp.getLong("saved_at", 0);
            p.fromDb = true;
            return p;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void save(Context ctx, WidgetPlacement p) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putFloat(K_TOP, p.top)
                    .putFloat(K_BOTTOM, p.bottom)
                    .putFloat(K_LEFT, p.left)
                    .putFloat(K_RIGHT, p.right)
                    .putInt(K_ROWS, p.rows)
                    .putInt(K_COLS, p.cols)
                    .putInt(K_CELL_X, p.cellX)
                    .putInt(K_CELL_Y, p.cellY)
                    .putInt(K_SPAN_X, p.spanX)
                    .putInt(K_SPAN_Y, p.spanY)
                    .putInt(K_WIDGET_ID, p.widgetId)
                    .putBoolean("from_launcher", p.fromLauncher)
                    .putBoolean("from_a11y", p.fromA11y)
                    .putLong("saved_at", System.currentTimeMillis())
                    .apply();
            p.savedAt = System.currentTimeMillis();
        } catch (Throwable ignored) { }
    }
}
