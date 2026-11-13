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
    /** 矩形是「桌面库格子 + 网格标定」算出来的，等同精确 */
    public boolean fromCalib;
    /** 无障碍测到的单格像素尺寸（0 = 没测到） */
    public int cellWpx, cellHpx;
    /** 这份数据是什么时候拿到的（毫秒），用于和无障碍文件比新旧 */
    public long savedAt;
    /**
     * 数据源本身的时间（毫秒）。
     *
     * <p>a11y 文件 = 文件 mtime；桌面库 = db（含 -wal）的 mtime。
     * 用它判断哪一边更新：桌面库更新说明用户刚挪过组件，服务还没来得及读。
     */
    public long sourceAt;
    /**
     * 格子边界：无障碍服务给的 AppWidgetHostView 矩形，**不含卡片 margin**。
     *
     * <p>它是反解网格参数的唯一依据（格子宽 = 宽 / spanX）。
     * 全 0 表示没有。
     */
    public int hostL, hostT, hostR, hostB;

    public boolean hasHost() {
        return hostR > hostL && hostB > hostT;
    }

    /** 无障碍直接读到的像素矩形（0 = 没有）。标定时用它算卡片 margin */
    public int pxL, pxT, pxR, pxB;

    /** 这条数据是在多大的屏幕上量的（host 是像素，换算成比例要用） */
    public int screenW, screenH;

    public boolean hasPx() {
        return pxR > pxL && pxB > pxT;
    }

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
        // 和「重新校准」走同一套判断：库和 a11y 文件谁新用谁
        return remeasure(ctx);
    }

    /**
     * 强制重测（v4.1.0）：跳过缓存，直接读一次桌面数据库。
     *
     * <p>用户把组件拉大拉小、或者换了位置之后，点「重新定位」走这里。
     * 读到了会顺带刷新缓存。
     */
    public static WidgetPlacement remeasure(Context ctx) {
        // 两路并行拿数据，再比谁新：
        //   cells —— 桌面库里的格子（走 su）。用户挪完组件，桌面会先写库。
        //   a11y  —— 服务在桌面上读到的像素矩形（不碰 su）。服务要等窗口事件才读。
        // 谁的时间新就用谁。之前是无条件优先 a11y 文件，于是用户挪完组件立刻
        // 点「重新对齐」，拿到的还是上一次的旧矩形（真机复现：组件在 cellX=1,
        // cellY=5，文件里还是 cellX=0, cellY=3 的坐标）。
        WidgetPlacement cells = readFromDb(ctx);
        if (cells == null && !RootShell.isReady()) {
            // 第一次进这个页面时 root 通道可能还在建（Magisk 授权刚弹），
            // 直接读会拿到 null，标定就建不起来 —— 真机上表现为「第一次点校准没反应，
            // 再点一次才对」。等它一下再读一次。
            try { Thread.sleep(800L); } catch (Throwable ignored) { }
            cells = readFromDb(ctx);
        }
        WidgetPlacement a11y = readA11yRect(ctx);

        boolean a11yFresh = a11y != null
                && (cells == null || a11y.sourceAt >= cells.sourceAt);

        if (a11yFresh) {
            // 服务读到的是最新布局：矩形信它，格子信库（服务不写格子）
            calibrate(ctx, a11y, cells);
            if (cells != null) {
                a11y.cellX = cells.cellX;
                a11y.cellY = cells.cellY;
                a11y.spanX = cells.spanX;
                a11y.spanY = cells.spanY;
                a11y.cols = cells.cols;
                a11y.rows = cells.rows;
            }
            save(ctx, a11y);
            return a11y;
        }

        if (cells != null) {
            // 库比服务新：用户刚挪过，服务还没读到。按标定把格子换算成像素。
            fromCalib(ctx, cells);
            save(ctx, cells);
            return cells;
        }

        if (a11y != null) {
            save(ctx, a11y);
            return a11y;
        }
        return null;
    }

    /** 只读缓存，不碰 su。页面初始化时用这个。 */
    public static WidgetPlacement cached(Context ctx) {
        WidgetPlacement p = load(ctx);
        // 只有服务读到的比缓存新才覆盖。缓存里可能已经是「标定换算 + 新格子」的结果，
        // 而无障碍文件还停在上一次读数上——无条件覆盖会把位置退回旧值（真机踩过）。
        WidgetPlacement a11y = readA11yRect(ctx);
        if (a11y != null && (p == null || a11y.sourceAt > p.savedAt)) {
            save(ctx, a11y);
            p = a11y;
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
            String hostLine = null;
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) {
                line = r.readLine();
                // 第二行是格子边界，老版本没有，读到 null 属正常
                hostLine = r.readLine();
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
            p.sourceAt = f.lastModified();
            p.pxL = l;
            p.pxT = t;
            p.pxR = rr;
            p.pxB = b;
            p.screenW = sw;
            p.screenH = sh;
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
            // HOST|l|t|r|b —— 格子边界，没有 margin
            if (hostLine != null && hostLine.startsWith("HOST|")) {
                String[] h = hostLine.split("\\|");
                if (h.length >= 5) {
                    int hl = parseIntOr(h[1], 0);
                    int ht = parseIntOr(h[2], 0);
                    int hr = parseIntOr(h[3], 0);
                    int hb = parseIntOr(h[4], 0);
                    if (hr > hl && hb > ht) {
                        p.hostL = hl;
                        p.hostT = ht;
                        p.hostR = hr;
                        p.hostB = hb;
                    }
                }
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
        // 顺带取库文件的修改时间：它比无障碍文件新，说明用户刚挪过组件、
        // 服务还没读到，此时不能拿服务那份旧矩形去用（踩过：预览停在旧位置）。
        sb.append("[ -n \"$R\" ] && { MT=$(stat -c %Y \"$F\" 2>/dev/null || echo 0); ");
        sb.append("for X in \"$F-wal\" \"$F-journal\" \"$F-shm\"; do ");
        sb.append("M2=$(stat -c %Y \"$X\" 2>/dev/null || echo 0); ");
        sb.append("[ \"$M2\" -gt \"$MT\" ] && MT=$M2; done; ");
        sb.append("RES=\"HIT|$F|$MT|$R\"; break; }; ");
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
            if (seg.length < 4) return null;
            String dbPath = seg[1];
            String[] v = seg[3].trim().split(",");
            if (v.length < 4) return null;

            WidgetPlacement p = new WidgetPlacement();
            p.cellX = Integer.parseInt(v[0].trim());
            p.cellY = Integer.parseInt(v[1].trim());
            p.spanX = Integer.parseInt(v[2].trim());
            p.spanY = Integer.parseInt(v[3].trim());
            p.widgetId = widgetId;
            p.fromDb = true;
            // 秒级时间戳 → 毫秒
            p.sourceAt = (long) (parseIntOr(seg[2], 0)) * 1000L;

            // 网格行列：优先用「桌面用到的最右/最下边界」推断。
            // 只有当推断值不比组件自身大（说明桌面上没有别的图标贴到边，
            // 这种情况极罕见）时，才退回文件名里的规格。
            int inferredCols = v.length > 4 ? parseIntOr(v[4], 0) : 0;
            int inferredRows = v.length > 5 ? parseIntOr(v[5], 0) : 0;
            int[] fromName = gridFromName(dbPath);
            p.cols = (inferredCols > p.spanX && inferredCols <= 12) ? inferredCols : fromName[0];
            p.rows = (inferredRows > p.spanY && inferredRows <= 14) ? inferredRows : fromName[1];

            int[] size = screenSize(ctx);
            p.screenW = size[0];
            p.screenH = size[1];
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

    /**
     * 「格子框」（桌面上尺寸调节时那个蓝框）在屏幕上的比例，{left, top, right, bottom}。
     *
     * <p>预览要展示的是**整块格子**，不是卡片本身：卡片在格子里还有留白，
     * 只裁卡片会把圆角和四周的壁纸切掉，看起来像贴纸而不是桌面上的样子。
     *
     * <p>三级来源：无障碍直读的 host 矩形 → 格子 + 标定 → 拿不到（返回 null）。
     */
    public float[] frameRatios(Context ctx) {
        int sw = screenW, sh = screenH;
        if (sw <= 0 || sh <= 0) {
            int[] s = screenSize(ctx);
            sw = s[0];
            sh = s[1];
        }
        if (sw <= 0 || sh <= 0) return null;

        if (hasHost()) {
            return new float[]{hostL / (float) sw, hostT / (float) sh,
                    hostR / (float) sw, hostB / (float) sh};
        }
        Calib c = calib(ctx);
        if (c != null && c.valid() && spanX > 0 && spanY > 0) {
            float hl = c.originX + cellX * c.cellW;
            float ht = c.originY + cellY * c.cellH;
            return new float[]{hl / sw, ht / sh,
                    (hl + spanX * c.cellW) / sw, (ht + spanY * c.cellH) / sh};
        }
        return null;
    }

    /**
     * 卡片相对格子框的内缩比例，{left, top, right, bottom}，0~1。
     *
     * <p>拿不到的返回全 0（卡片铺满框）——宁可铺满，也别凭空猜一个留白。
     */
    public float[] cardInset(Context ctx) {
        float[] zero = {0f, 0f, 0f, 0f};
        if (hasPx() && hasHost()) {
            float hw = Math.max(1, hostR - hostL);
            float hh = Math.max(1, hostB - hostT);
            return new float[]{
                    (pxL - hostL) / hw, (pxT - hostT) / hh,
                    (hostR - pxR) / hw, (hostB - pxB) / hh};
        }
        Calib c = calib(ctx);
        if (c != null && c.valid() && spanX > 0 && spanY > 0) {
            float hw = spanX * c.cellW;
            float hh = spanY * c.cellH;
            if (hw <= 0 || hh <= 0) return zero;
            return new float[]{c.marginL / hw, c.marginT / hh,
                    c.marginR / hw, c.marginB / hh};
        }
        return zero;
    }

    // ==================== 网格标定 ====================

    /**
     * 网格参数：把「第几格、占几格」换算成像素矩形。
     *
     * <p>这些值不能写死 —— 换主题、改网格、换机器都会变。只能从一次真实读数里反解：
     * <pre>
     *   cellW   = 格子宽 / spanX
     *   originX = 格子左边界 − cellX × cellW
     *   marginX = (格子宽 − 卡片宽) / 2
     * </pre>
     *
     * <p>有了它，用户把组件挪到别的格子，不必再回桌面读一次 ——
     * 桌面库里的格子是实时的，套上参数就是像素矩形。
     */
    public static final class Calib {
        public float cellW, cellH, originX, originY;
        /**
         * 卡片相对格子四边各让出多少。
         *
         * <p>**不能当成四边相等**：实测 K90 上横向左右各 40.5，
         * 纵向顶部 0、底部 44 —— 卡片是贴着格子顶边的。
         * 用平均值会让整张卡片上移半格。
         */
        public float marginL, marginT, marginR, marginB;
        public long at;

        public boolean valid() {
            return cellW > 0 && cellH > 0;
        }
    }

    private static final String PREF_CALIB = "widget_calib";

    public static Calib calib(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREF_CALIB, Context.MODE_PRIVATE);
            if (!sp.contains("cell_w")) return null;
            Calib c = new Calib();
            c.cellW = sp.getFloat("cell_w", 0);
            c.cellH = sp.getFloat("cell_h", 0);
            c.originX = sp.getFloat("origin_x", 0);
            c.originY = sp.getFloat("origin_y", 0);
            // 老版本存的是对称值，读回来时四边同值
            c.marginL = sp.getFloat("margin_l", sp.getFloat("margin_x", 0));
            c.marginT = sp.getFloat("margin_t", sp.getFloat("margin_y", 0));
            c.marginR = sp.getFloat("margin_r", sp.getFloat("margin_x", 0));
            c.marginB = sp.getFloat("margin_b", sp.getFloat("margin_y", 0));
            c.at = sp.getLong("at", 0);
            return c.valid() ? c : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 用一次「像素矩形 + 同一次读到的格子」反解网格参数。
     *
     * <p>必须用 host（格子）而不是卡片：卡片四周有 margin，用它算会把 margin
     * 摊进格子宽，挪一格就偏十几像素。
     */
    private static void calibrate(Context ctx, WidgetPlacement a11y, WidgetPlacement cells) {
        try {
            if (a11y == null || cells == null) return;
            if (!a11y.hasHost() || !a11y.hasPx()) return;
            if (cells.spanX <= 0 || cells.spanY <= 0) return;

            float cellW = (a11y.hostR - a11y.hostL) / (float) cells.spanX;
            float cellH = (a11y.hostB - a11y.hostT) / (float) cells.spanY;
            if (cellW < 40 || cellH < 40 || cellW > 1000 || cellH > 1000) return;

            Calib c = new Calib();
            c.cellW = cellW;
            c.cellH = cellH;
            c.originX = a11y.hostL - cells.cellX * cellW;
            c.originY = a11y.hostT - cells.cellY * cellH;
            c.marginL = Math.max(0, a11y.pxL - a11y.hostL);
            c.marginT = Math.max(0, a11y.pxT - a11y.hostT);
            c.marginR = Math.max(0, a11y.hostR - a11y.pxR);
            c.marginB = Math.max(0, a11y.hostB - a11y.pxB);
            c.at = System.currentTimeMillis();

            // 反解出负数原点说明这两份数据本来就不是同一次布局，丢掉
            if (c.originX < -400 || c.originY < -800
                    || c.originX > 1000 || c.originY > 2600) return;

            ctx.getSharedPreferences(PREF_CALIB, Context.MODE_PRIVATE).edit()
                    .putFloat("cell_w", c.cellW)
                    .putFloat("cell_h", c.cellH)
                    .putFloat("origin_x", c.originX)
                    .putFloat("origin_y", c.originY)
                    .putFloat("margin_l", c.marginL)
                    .putFloat("margin_t", c.marginT)
                    .putFloat("margin_r", c.marginR)
                    .putFloat("margin_b", c.marginB)
                    .putLong("at", c.at)
                    .apply();
            android.util.Log.d("PICKUPDEBUG", "网格标定: cell=" + c.cellW + "x" + c.cellH
                    + " origin=" + c.originX + "," + c.originY
                    + " margin=" + c.marginL + "/" + c.marginT
                    + "/" + c.marginR + "/" + c.marginB);
        } catch (Throwable t) {
            android.util.Log.w("PICKUPDEBUG", "标定失败: " + t);
        }
    }

    /**
     * 用网格参数把格子换算成比例，直接覆盖到 p 上。
     *
     * @return true = 换算成功（p 的位置已更新）
     */
    private static boolean fromCalib(Context ctx, WidgetPlacement p) {
        try {
            Calib c = calib(ctx);
            if (c == null || !c.valid()) return false;
            if (p.spanX <= 0 || p.spanY <= 0) return false;

            int[] size = screenSize(ctx);
            int sw = size[0], sh = size[1];
            if (sw <= 0 || sh <= 0) return false;

            float hostL = c.originX + p.cellX * c.cellW;
            float hostT = c.originY + p.cellY * c.cellH;
            float cardL = hostL + c.marginL;
            float cardT = hostT + c.marginT;
            float cardR = hostL + p.spanX * c.cellW - c.marginR;
            float cardB = hostT + p.spanY * c.cellH - c.marginB;

            // 换过主题 / 网格设置，参数就失效了：算出来的框会跑到屏幕外或小得离谱
            if (cardL < -8 || cardT < -8 || cardR > sw + 8 || cardB > sh + 8) return false;
            if (cardR - cardL < c.cellW * 0.5f) return false;
            if (cardB - cardT < c.cellH * 0.5f) return false;

            p.left = cardL / sw;
            p.right = cardR / sw;
            p.top = cardT / sh;
            p.bottom = cardB / sh;
            p.fromDb = true;
            p.fromCalib = true;
            android.util.Log.d("PICKUPDEBUG", "按标定换算: 格=" + p.cellX + "," + p.cellY
                    + " 跨=" + p.spanX + "x" + p.spanY + " → "
                    + (int) cardL + "," + (int) cardT + " - " + (int) cardR + "," + (int) cardB);
            return true;
        } catch (Throwable t) {
            return false;
        }
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
            p.fromCalib = sp.getBoolean("from_calib", false);
            p.savedAt = sp.getLong("saved_at", 0);
            p.sourceAt = sp.getLong("source_at", 0);
            p.screenW = sp.getInt("screen_w", 0);
            p.screenH = sp.getInt("screen_h", 0);
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
                    .putBoolean("from_calib", p.fromCalib)
                    .putLong("saved_at", System.currentTimeMillis())
                    .putLong("source_at", p.sourceAt)
                    .putInt("screen_w", p.screenW)
                    .putInt("screen_h", p.screenH)
                    .apply();
            p.savedAt = System.currentTimeMillis();
        } catch (Throwable ignored) { }
    }
}
