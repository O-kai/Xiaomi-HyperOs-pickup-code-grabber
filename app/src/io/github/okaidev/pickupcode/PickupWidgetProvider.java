package io.github.okaidev.pickupcode;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 4.0 桌面小组件。
 *
 * 设计要点：
 *  - 不做滚动列表：RemoteViewsService + ListView 在部分桌面/系统版本上刷新不可靠，
 *    固定行数 + 事件驱动更稳。
 *  - <b>行高固定</b>（v4.0.1）：每行一个固定高度，内容从上往下排。
 *    此前每行 layout_weight=1，五行会把组件高度均分——条数一变行高就跟着变，
 *    看上去像在缩放。现在一条就是一行，与条数无关；放不下的部分留白。
 *  - <b>行数由组件高度决定</b>（v4.0.2）：组件可以被用户拉到很大，
 *    所以不再设固定条数上限——用户原话「最多可显示条数不应该有太明显的限制框架，
 *    我们只需要根据小组件的实际大小来展示就可以了」。
 *  - <b>动作不跳转</b>（v4.0.1）：打勾 / 复制走广播（BroadcastReceiver），
 *    不再启动 Activity——在小组件上操作就不该把 App 拉到前台。
 *    点击其它区域（标题、件数、查看全部、空白）才打开 App。
 *  - <b>补位动画</b>（v4.0.1）：取完一条后，下面的内容要「顶上来」。
 *    RemoteViews 没有真正的列表 item 动画，这里用 setFloat 驱动
 *    alpha + translationY 做位移淡入——效果接近，且不依赖 ListView。
 *  - <b>显示内容可选</b>（v4.0.2）：只显示待取件（默认）或显示全部信息
 *    （待取在上、已取在下，已取的划掉）。
 *  - 数据只来自本地取件库，不读短信库、不存正文。
 */
public class PickupWidgetProvider extends AppWidgetProvider {

    static final String ACTION_COPY = "io.github.okaidev.pickupcode.widget.COPY";
    static final String ACTION_DONE = "io.github.okaidev.pickupcode.widget.DONE";
    static final String EXTRA_ID = "record_id";

    private static final String TAG = "PICKUPDEBUG";
    private static final long SECOND_PASS_DELAY = 600L;
    /** 补位动画的时长（ms）：先落在「下移 + 半透明」，再落回原位。 */
    private static final long SHIFT_SETTLE_DELAY = 90L;

    /** 每行的固定高度（dp）。与布局里的 minHeight 保持一致，用于补位动画的位移量。 */
    static final float ROW_HEIGHT_DP = 40f;

    /** 布局里的最大行数。组件拉到最大时按高度取用。 */
    static final int MAX_ROWS = 12;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile Context appContext;

    /** 最近一次渲染的快照：补位动画的「第二次更新」要用它重建同样的内容。 */
    private static volatile PickupRepository.PendingSnapshot lastSnapshot;
    /** 上一次显示出来的记录 id（按行序）：用来判断哪些行是「顶上来的」。 */
    private static volatile List<Long> lastShownIds = Collections.emptyList();

    private static final Runnable SECOND_PASS = () -> {
        Context ctx = appContext;
        if (ctx != null) {
            doRefresh(ctx, false);
        }
    };

    private static final int[] ROW_IDS = {
            R.id.widget_row_1, R.id.widget_row_2, R.id.widget_row_3,
            R.id.widget_row_4, R.id.widget_row_5, R.id.widget_row_6,
            R.id.widget_row_7, R.id.widget_row_8, R.id.widget_row_9,
            R.id.widget_row_10, R.id.widget_row_11, R.id.widget_row_12
    };
    private static final int[] CODE_IDS = {
            R.id.widget_code_1, R.id.widget_code_2, R.id.widget_code_3,
            R.id.widget_code_4, R.id.widget_code_5, R.id.widget_code_6,
            R.id.widget_code_7, R.id.widget_code_8, R.id.widget_code_9,
            R.id.widget_code_10, R.id.widget_code_11, R.id.widget_code_12
    };
    private static final int[] PLACE_IDS = {
            R.id.widget_place_1, R.id.widget_place_2, R.id.widget_place_3,
            R.id.widget_place_4, R.id.widget_place_5, R.id.widget_place_6,
            R.id.widget_place_7, R.id.widget_place_8, R.id.widget_place_9,
            R.id.widget_place_10, R.id.widget_place_11, R.id.widget_place_12
    };
    private static final int[] DONE_IDS = {
            R.id.widget_done_1, R.id.widget_done_2, R.id.widget_done_3,
            R.id.widget_done_4, R.id.widget_done_5, R.id.widget_done_6,
            R.id.widget_done_7, R.id.widget_done_8, R.id.widget_done_9,
            R.id.widget_done_10, R.id.widget_done_11, R.id.widget_done_12
    };

    // v4.0.3 第二轮：原来的 hasWidget() 只剩转调 WidgetPresence，
    // 而且调用方已改用 WidgetPresence.has()，这里删掉避免两套入口。

    /**
     * 最后一个组件被移除时什么都不用做（v4.0.3 第二轮）。
     *
     * <p>旧模型里「仅桌面小组件」是一个写入目标，组件被删就得回落到待办；
     * 新模型下写入目标只有「待办」和「软件内部记录」，小组件只是本地记录的
     * 展示投影——它被删掉不影响任何写入行为，App 内的一切照旧。
     */
    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        super.onDeleted(context, appWidgetIds);
        Log.i(TAG, "WIDGET deleted: n=" + (appWidgetIds == null ? 0 : appWidgetIds.length));
    }

    @Override
    public void onDisabled(Context context) {
        super.onDisabled(context);
        Log.i(TAG, "WIDGET disabled (last instance removed)");
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        refreshAsync(context);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager appWidgetManager,
                                          int appWidgetId, Bundle newOptions) {
        // 尺寸变化后按新高度重算行数
        refreshAsync(context);
    }

    public static void refreshAsync(Context context) {
        refresh(context, false);
    }

    /**
     * 刷新小组件。
     *
     * @param animate true 时启用「补位动画」：把刚刚顶上来的行做成从下方淡入。
     */
    static void refresh(Context context, boolean animate) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        appContext = app;
        doRefresh(app, animate);
        // 二次补刷：系统在添加/改尺寸后可能先渲染旧 RemoteViews，再补一次保证最终一致。
        // 动画那次不参与二次补刷——否则会覆盖掉动画中间态。
        if (!animate) {
            MAIN.removeCallbacks(SECOND_PASS);
            MAIN.postDelayed(SECOND_PASS, SECOND_PASS_DELAY);
        }
    }

    private static void doRefresh(final Context app, final boolean animate) {
        try {
            final boolean showAll = WidgetPrefs.showAll(app);
            // 行数上限不再由设置决定，取布局允许的最大值；
            // 实际显示几行由组件高度算出来（见 rowsFor）。
            PickupRepository.get(app).pendingSnapshotAsync(MAX_ROWS, showAll, snap -> {
                lastSnapshot = snap;
                AppWidgetManager manager = AppWidgetManager.getInstance(app);
                ComponentName component = new ComponentName(app, PickupWidgetProvider.class);
                int[] ids;
                try {
                    ids = manager.getAppWidgetIds(component);
                } catch (Throwable t) {
                    Log.w(TAG, "widget ids failed: " + t);
                    return;
                }

                // 找出「顶上来的行」：某条记录上一轮显示在更靠下的位置，这一轮往上挪了。
                List<Integer> shifted = animate ? detectShiftedRows(snap) : Collections.emptyList();

                for (int id : ids) {
                    try {
                        manager.updateAppWidget(id, build(app, id, snap, shifted));
                    } catch (Throwable t) {
                        Log.w(TAG, "widget update failed id=" + id + ": " + t);
                    }
                }
                lastShownIds = shownIds(snap);

                if (!shifted.isEmpty()) {
                    // 动画的「第二次更新」：用同一份快照重建内容，只是把动画参数归位。
                    // 注意必须是完整重建——updateAppWidget 是整体替换，
                    // 传一个空的 RemoteViews 会把小组件内容清空。
                    MAIN.postDelayed(() -> settle(app, ids, snap), SHIFT_SETTLE_DELAY);
                }

                Log.i(TAG, "WIDGET refresh: n=" + ids.length
                        + " pending=" + (snap == null ? 0 : snap.pendingTotal)
                        + (snap != null && snap.showAll ? " all" : "")
                        + (shifted.isEmpty() ? "" : " shifted=" + shifted));
            });
        } catch (Throwable t) {
            Log.w(TAG, "widget refresh failed: " + t);
        }
    }

    /** 归位：把动画中的行恢复成正常透明度与位置。 */
    private static void settle(Context app, int[] ids, PickupRepository.PendingSnapshot snap) {
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(app);
            for (int id : ids) {
                try {
                    manager.updateAppWidget(id, build(app, id, snap, Collections.emptyList()));
                } catch (Throwable t) {
                    Log.w(TAG, "widget settle failed id=" + id + ": " + t);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "widget settle failed: " + t);
        }
    }

    /** 当前显示出来的记录 id（按行序）。 */
    private static List<Long> shownIds(PickupRepository.PendingSnapshot snap) {
        List<Long> out = new ArrayList<>();
        if (snap == null || snap.items == null) return out;
        for (PickupRecord r : snap.items) out.add(r.id);
        return out;
    }

    /**
     * 找出「顶上来的行」。
     *
     * <p>判据：这一轮显示的第 i 条记录，上一轮显示在第 j 行（j &gt; i）——
     * 说明中间有一条被取走了，它往上挪了位，需要做补位动画。
     */
    private static List<Integer> detectShiftedRows(PickupRepository.PendingSnapshot snap) {
        List<Integer> shifted = new ArrayList<>();
        List<Long> now = shownIds(snap);
        List<Long> before = lastShownIds;
        if (before.isEmpty() || now.isEmpty()) return shifted;
        for (int i = 0; i < now.size(); i++) {
            int j = before.indexOf(now.get(i));
            if (j > i) shifted.add(i);
        }
        return shifted;
    }

    /**
     * 诊断用：构造一份「带全部 action」的 RemoteViews。
     *
     * <p>供 {@link WidgetDiagActivity} 在本进程内 {@code RemoteViews.apply()}——
     * 桌面添加小组件报「载入窗口小部件时出现问题」时，用它能当场看到
     * 到底是哪个 action 不被系统接受。
     *
     * <p>snap 传 null 时会用【示例数据】填充，这样自检页能看到真实的行布局
     * （行高是否均匀、内容是否填满），而不是只有一个空状态。
     */
    static RemoteViews buildForDiag(Context ctx, PickupRepository.PendingSnapshot snap) {
        return build(ctx, -1, snap == null ? sampleSnapshot() : snap,
                Collections.emptyList(), true);
    }

    /**
     * 「取件记录已关闭」的极简态。
     *
     * <p>正常情况下面临的是「停用 Provider 后系统摘除实例」，这一版只在
     * 「组件被停用前的最后一次同步渲染」和「某些 ROM 停用后不摘实例」时可见。
     * 点击会打开 App，用户可以在「取件码写入目标」里重新打开——文案必须
     * 直说这一点，否则他只会看到一个不知道该怎么办的方块。
     */
    /**
     * 同步把所有实例刷成「已关闭」态（不查库、不异步）。
     *
     * <p>停用 Provider 之前必须先把这一步做完：停用后系统会摘掉实例，
     * 万一某个 ROM 不摘，桌面上留下的就是这一版说明文字，而不是旧取件码。
     */
    static void showClosedState(Context context) {
        if (context == null) return;
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(context);
            int[] ids = mgr.getAppWidgetIds(
                    new ComponentName(context, PickupWidgetProvider.class));
            if (ids == null || ids.length == 0) return;
            RemoteViews rv = buildClosed(context);
            for (int id : ids) {
                try {
                    mgr.updateAppWidget(id, rv);
                } catch (Throwable t) {
                    Log.w(TAG, "widget closed-state update failed id=" + id + ": " + t);
                }
            }
            Log.i(TAG, "WIDGET closed state applied: n=" + ids.length);
        } catch (Throwable t) {
            Log.w(TAG, "widget closed state failed: " + t);
        }
    }

    // ==================== 外观（v4.1.0） ====================

    /** 外观主题：dark / light / system */
    static final String KEY_THEME = "widget_theme";
    /** 透明度：solid / half / clear */
    static final String KEY_ALPHA = "widget_alpha";

    /** 跟随系统时，在这里解析成实际主题（组件进程读到的是当前配置） */
    static boolean isLightTheme(Context ctx) {
        String t = WidgetPrefs.theme(ctx);
        if ("light".equals(t)) return true;
        if ("dark".equals(t)) return false;
        try {
            int mode = ctx.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return mode != android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable e) {
            return false;
        }
    }

    static String alphaKey(Context ctx) {
        return WidgetPrefs.alpha(ctx);
    }

    static int layoutId(Context ctx) {
        return isLightTheme(ctx) ? R.layout.widget_pickup_v5_light : R.layout.widget_pickup_v4;
    }

    /** 背景：深/浅 × 实底/半透明/全透明 */
    static int backgroundRes(Context ctx) {
        boolean light = isLightTheme(ctx);
        String a = alphaKey(ctx);
        if ("clear".equals(a)) return light ? R.drawable.widget_bg_light_clear : R.drawable.widget_bg_dark_clear;
        if ("half".equals(a)) return light ? R.drawable.widget_bg_light_half : R.drawable.widget_bg_dark_half;
        return light ? R.drawable.widget_bg_light_solid : R.drawable.widget_bg_dark_solid;
    }

    /** 文字色：深色主题给白字，浅色主题给深字；alpha 沿用原来的层级 */
    static int fg(Context ctx, int alpha) {
        return isLightTheme(ctx) ? (alpha << 24) | 0x1C1C1E : (alpha << 24) | 0xFFFFFF;
    }

    /**
     * 删除线（v4.1.0）。
     *
     * <p>试过用组合字符 U+0336 逐字叠加做「双横线」，结果在中文上翻车：
     * 中文字体没有对应的组合字形，地址整段变成豆腐块，取件码也被压成一条糊线。
     * 所以回到 Html 的 {@code <s>}：单线，但长度天然跟着文字走，
     * 取件码和地址都能用，跨进程也稳定。
     */
    static CharSequence strikeText(String text) {
        if (text == null || text.isEmpty()) return text;
        return Html.fromHtml("<s>" + escapeHtml(text) + "</s>");
    }

    private static RemoteViews buildClosed(Context context) {
        RemoteViews rv = new RemoteViews(context.getPackageName(), layoutId(context));
        // 背景按「主题 × 透明度」切换；layout 里的默认背景只是兜底
        rv.setInt(R.id.widget_root_v2, "setBackgroundResource", backgroundRes(context));
        rv.setTextViewText(R.id.widget_title, "取件记录已关闭");
        rv.setTextViewTextSize(R.id.widget_title, TypedValue.COMPLEX_UNIT_SP, 18f);
        rv.setTextViewText(R.id.widget_count, "");
        for (int i = 0; i < MAX_ROWS; i++) {
            rv.setViewVisibility(ROW_IDS[i], View.GONE);
        }
        rv.setViewVisibility(R.id.widget_empty, View.VISIBLE);
        rv.setTextViewText(R.id.widget_empty, "点这里重新打开「软件内部记录」");
        rv.setTextViewTextSize(R.id.widget_empty, TypedValue.COMPLEX_UNIT_SP, 13f);
        PendingIntent open = openApp(context);
        rv.setOnClickPendingIntent(R.id.widget_title, open);
        rv.setOnClickPendingIntent(R.id.widget_empty, open);
        return rv;
    }

    /** 自检用的示例快照（2 条待取 + 1 条已取，故意少于行数，好看出空行的效果） */
    private static PickupRepository.PendingSnapshot sampleSnapshot() {
        long now = System.currentTimeMillis();
        java.util.List<PickupRecord> items = new java.util.ArrayList<>();
        items.add(sample(1, "12-3-4567", "菜鸟驿站", "江南绿城西门", now - 60000, true));
        items.add(sample(2, "58-2-9041", "顺丰速运", "望江路驿站", now - 120000, true));
        items.add(sample(3, "09-8-7654", "中通快递", "锦绣路 18 号", now - 240000, false));
        return new PickupRepository.PendingSnapshot(items, 3, 2, true);
    }

    private static PickupRecord sample(long id, String code, String source, String place,
                                       long at, boolean pending) {
        PickupRecord r = new PickupRecord();
        r.id = id;
        r.code = code;
        r.source = source;
        r.place = place;
        r.receivedAt = at;
        r.createdAt = at;
        r.lastSeenAt = at;
        r.status = pending ? PickupRecord.STATUS_PENDING : PickupRecord.STATUS_PICKED_UP;
        r.pickedAt = pending ? 0L : at;
        return r;
    }

    private static RemoteViews build(Context context, int widgetId,
                                     PickupRepository.PendingSnapshot snap,
                                     List<Integer> shiftedRows) {
        return build(context, widgetId, snap, shiftedRows, false);
    }

    /**
     * @param forceNormal true = 不管「内部记录」开关，一律按正常布局渲染
     *                    （自检页要用它验证真实布局，不能被关闭态挡住）
     */
    private static RemoteViews build(Context context, int widgetId,
                                     PickupRepository.PendingSnapshot snap,
                                     List<Integer> shiftedRows, boolean forceNormal) {
        // v4.0.3 第二轮：「软件内部记录」关掉之后，小组件没有任何数据来源。
        // 正常情况下 Provider 已经被停用、实例也随系统摘掉了；这里兜的是
        // 「停用还没生效」或「个别 ROM 没摘」的极短窗口——此时必须给出准确说明，
        // 显示「暂无待取件」会让人以为功能还在正常工作。
        if (!forceNormal && !NotesBackend.localSelected(context)) {
            return buildClosed(context);
        }

        // v4.0.2 第八轮：布局换到 v3（新 layout 资源 id）。
        // 桌面不会重建已存在组件的视图树，换资源 id 是唯一能让
        // AppWidgetHostView 重新 inflate 的手段。
        RemoteViews rv = new RemoteViews(context.getPackageName(), layoutId(context));
        // 背景按「主题 × 透明度」切换；layout 里的默认背景只是兜底
        rv.setInt(R.id.widget_root_v2, "setBackgroundResource", backgroundRes(context));

        // v4.0.2 第三轮：一次算好行数与实际行高（补位动画的位移量要用真实行高）
        int[] metrics = widgetMetrics(context, widgetId);
        int rows = metrics[0];
        // v4.0.3 第二轮：「显示取件码」是上级开关，「显示取件地点」是它的下级——
        // 码都不显示了，地点无所依附，所以地点真正生效要两个都开。
        boolean showCode = WidgetPrefs.showCode(context);
        boolean showPlace = showCode && WidgetPrefs.showPlace(context);
        boolean showAll = snap != null && snap.showAll;
        float scale = WidgetPrefs.fontScale(context);
        int pendingTotal = snap == null ? 0 : snap.pendingTotal;
        List<PickupRecord> items = snap == null ? null : snap.items;

        // 标题与件数：
        //  - 只显示待取件：标题「待取件」，件数是待取总数
        //  - 显示全部信息：标题「取件记录」，件数是「N 待取 · M 已取」
        // v4.0.2 第四轮：件数文案改成直白表述（「N 个待取」而不是「N 个」），
        // 用户原话：「直接在展示区展示取件码的位置直白显示有几个待取、几个已取」。
        int totalCount = snap == null ? 0 : snap.total;
        int pickedTotal = Math.max(0, totalCount - pendingTotal);
        if (showAll) {
            rv.setTextViewText(R.id.widget_title, "取件记录");
            int shownPending = pendingShown(items, items == null ? 0 : items.size());
            int shownPicked = (items == null ? 0 : items.size()) - shownPending;
            rv.setTextViewText(R.id.widget_count, pendingTotal + " 待取 · " + shownPicked + " 已取");
        } else {
            rv.setTextViewText(R.id.widget_title, "待取件");
            rv.setTextViewText(R.id.widget_count, pendingTotal + " 个待取");
        }
        rv.setTextViewTextSize(R.id.widget_title, TypedValue.COMPLEX_UNIT_SP, 18f);
        rv.setTextViewTextSize(R.id.widget_count, TypedValue.COMPLEX_UNIT_SP, 12f);

        boolean empty = items == null || items.isEmpty();

        // v4.0.2 第四轮：隐私模式（关掉「显示取件码」）——不逐条显示，只给汇总。
        //
        // 用户原话：「当开启『隐藏取件码』时，目前桌面上仍会保留取件码后面的
        // 『勾勾』，这个勾勾已经没有意义了，建议将其拿掉。也就是说，
        // 当隐藏取件码时，下面这些内容都取消掉。」
        // 以及「直接在展示区展示取件码的位置直白显示有几个待取、几个已取。
        // 当然，这个信息也跟显示内容的选项关联，如果是只看待取件的话，
        // 就显示有几个待取。」
        //
        // 所以：所有数据行整行隐藏（码、地点、✓ 一并消失），
        // 展示区（原本逐条列码的位置）改成直白的汇总数字。
        if (!showCode) {
            for (int i = 0; i < MAX_ROWS; i++) {
                rv.setViewVisibility(ROW_IDS[i], View.GONE);
            }
            String summary = showAll
                    ? (pendingTotal + " 个待取 · " + pickedTotal + " 个已取")
                    : (pendingTotal + " 个待取");
            rv.setViewVisibility(R.id.widget_empty, View.VISIBLE);
            rv.setTextViewText(R.id.widget_empty, summary);
            rv.setTextViewTextSize(R.id.widget_empty, TypedValue.COMPLEX_UNIT_SP, 16f);
            // 标题右侧不再重复数字——展示区已经直说了
            rv.setTextViewText(R.id.widget_count, "");
            PendingIntent openPrivacy = openApp(context);
            rv.setOnClickPendingIntent(R.id.widget_title, openPrivacy);
            rv.setOnClickPendingIntent(R.id.widget_empty, openPrivacy);
            return rv;
        }

        rv.setViewVisibility(R.id.widget_empty, empty ? View.VISIBLE : View.GONE);
        rv.setTextViewTextSize(R.id.widget_empty, TypedValue.COMPLEX_UNIT_SP, 12f * scale);

        // v4.0.2 第三轮：行高改成按权重均分，位移量要用【实际行高】，
        // 否则补位动画会错位。
        float rowPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, metrics[2],
                context.getResources().getDisplayMetrics());

        int shown = 0;
        for (int i = 0; i < MAX_ROWS; i++) {
            boolean has = !empty && i < rows && items != null && i < items.size();
            if (!has) {
                // 放不下的整行直接不显示；内容不够时下面的空白是合理的。
                //
                // 不做半行截断——半个取件码会被误读成完整的一条。
                // 也不做「空行占位」：行高固定之后，
                // 列表容器内部自然就会在内容下面留白，不需要额外的空行。
                rv.setViewVisibility(ROW_IDS[i], View.GONE);
                continue;
            }
            PickupRecord record = items.get(i);
            shown++;
            boolean pending = record.isPending();
            rv.setViewVisibility(ROW_IDS[i], View.VISIBLE);
            // 隐私模式：不显示取件码，也不显示地点——组件上只剩件数，
            // 想看码得点进 App。
            //
            // v4.0.2：「显示全部信息」模式下，已取的取件码加删除线。
            // RemoteViews 没有设 PaintFlags 的接口，但 setTextViewText 接受
            // Spanned——用 Html.fromHtml("<s>…</s>") 就能拿到带删除线的文本。
            // v4.1.0：已取的整行都要划掉，而且是**双横线**、长度跟着文字走。
            // RemoteViews 里能用 Html 的 <s>（单横线、只作用在传入的那段文字），
            // 但做不到「双线」也做不到「跨两个 TextView」。
            // 这里用组合字符 U+0336（长删除线叠加）逐字叠加：
            //   · 线长天然跟着字数走，不会划过头；
            //   · 叠两次就是两条，看起来更明确；
            //   · 纯字符，能跨进程传输，不需要自定义 Span。
            boolean strike = showAll && !pending;
            if (!showCode) {
                rv.setTextViewText(CODE_IDS[i], "");
            } else if (strike) {
                rv.setTextViewText(CODE_IDS[i], strikeText(record.code));
            } else {
                rv.setTextViewText(CODE_IDS[i], record.code);
            }
            rv.setTextViewTextSize(CODE_IDS[i], TypedValue.COMPLEX_UNIT_SP, 17f * scale);
            if (showAll && !pending) {
                rv.setTextColor(CODE_IDS[i], fg(context, 0x99));
            } else {
                rv.setTextColor(CODE_IDS[i], fg(context, 0xFF));
            }

            // 关闭地点时不能 GONE：这一列是带 weight 的占位视图，
            // 一旦隐藏，右侧 ✓ 会贴到取件码旁边、右边留一大块空白。
            rv.setViewVisibility(PLACE_IDS[i], View.VISIBLE);
            String placeText = !showPlace
                    ? ""
                    : (record.place == null || record.place.trim().isEmpty() ? "—" : record.place);
            // 已取的地址也要划掉，跟取件码保持一致
            rv.setTextViewText(PLACE_IDS[i], strike ? strikeText(placeText) : placeText);
            rv.setTextViewTextSize(PLACE_IDS[i], TypedValue.COMPLEX_UNIT_SP, 11f * scale);
            if (showAll && !pending) {
                rv.setTextColor(PLACE_IDS[i], fg(context, 0x80));
            } else {
                rv.setTextColor(PLACE_IDS[i], fg(context, 0xB3));
            }

            // 补位动画：刚顶上来的行先落在「下移一格 + 半透明」，再归位。
            // 位移加在整行上（不是只加在文字上），这样取件码、地点、打勾一起动。
            if (shiftedRows.contains(i)) {
                rv.setFloat(ROW_IDS[i], "setAlpha", 0.4f);
                rv.setFloat(ROW_IDS[i], "setTranslationY", rowPx);
            } else {
                rv.setFloat(ROW_IDS[i], "setAlpha", 1f);
                rv.setFloat(ROW_IDS[i], "setTranslationY", 0f);
            }

            // 打勾：标记已取件，【不跳转 App】——在小组件上操作就不该把 App 拉到前台。
            // 走广播而不是 Activity：Activity 一定会带起界面（哪怕是透明主题，
            // 用户仍会看到任务切换一闪）。
            // 已取的记录不再显示 ✓（没什么可点的了）。
            if (pending) {
                rv.setViewVisibility(DONE_IDS[i], View.VISIBLE);
                rv.setOnClickPendingIntent(DONE_IDS[i], doneAction(context, record.id));
            } else {
                rv.setViewVisibility(DONE_IDS[i], View.INVISIBLE);
                rv.setOnClickPendingIntent(DONE_IDS[i], null);
            }
            // 点击取件码 / 地点：复制到剪贴板，同样不跳转。
            rv.setOnClickPendingIntent(CODE_IDS[i], copyAction(context, record.id));
            rv.setOnClickPendingIntent(PLACE_IDS[i], copyAction(context, record.id));
        }

        // v4.0.2 第三轮：底部「查看全部」已去掉。
        // 用户原话：「最右下角的『查看全部』我觉得完全可以拿掉。
        // 因为点击上方『取件记录』最顶上的标题时，就可以直接跳转过去，
        // 这已经足够完成跳转功能。这种重复的工作没有必要在两边同时展开。」
        //
        // 现在点标题（标题行 / 件数 / 空状态）都能跳到取件记录页，
        // 组件里只剩一个跳转入口，不再让人犹豫该点哪个。

        // 其余位置（标题、件数、空白）点击才打开 App——这才是「正常点击小组件」。
        PendingIntent open = openApp(context);
        rv.setOnClickPendingIntent(R.id.widget_title, open);
        rv.setOnClickPendingIntent(R.id.widget_count, open);
        rv.setOnClickPendingIntent(R.id.widget_empty, open);
        return rv;
    }

    /** items 里待取件的条数 */
    private static int pendingShown(List<PickupRecord> items, int limit) {
        if (items == null) return 0;
        int n = 0;
        int lim = Math.min(limit, items.size());
        for (int i = 0; i < lim; i++) if (items.get(i).isPending()) n++;
        return n;
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * 按组件高度推算能放几行。
     *
     * <p>实现放在 {@link WidgetPrefs#rowsForHeight}——那是纯逻辑、可离线单测的地方。
     * 这里只负责把 Android 的 options 读出来传进去。
     *
     * <p>v4.0.2：不再有「最多 5 条」这类硬上限——
     * 组件拉得越大，这里算出来的行数就越多，最多到布局允许的 MAX_ROWS。
     */
    private static int rowsFor(Context context, int widgetId) {
        return widgetMetrics(context, widgetId)[0];
    }

    /**
     * 组件的尺寸与行数。
     *
     * <p>v4.0.2 第八轮：行高固定 36dp（与布局 v3 一致），
     * 补位动画的位移量也按这个固定值算，不会错位。
     *
     * @return {行数, 组件高度dp, 行高dp}
     */
    private static int[] widgetMetrics(Context context, int widgetId) {
        int heightDp = 0;
        int widthDp = 0;
        try {
            Bundle options = AppWidgetManager.getInstance(context).getAppWidgetOptions(widgetId);
            if (options != null) {
                heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0);
                widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
            }
        } catch (Throwable t) {
            Log.w(TAG, "widget options failed: " + t);
        }
        int rows = WidgetPrefs.rowsForHeight(heightDp, widthDp, MAX_ROWS);
        int rowDp = WidgetPrefs.ROW_DP;
        Log.i(TAG, "WIDGET size: h=" + heightDp + "dp w=" + widthDp
                + "dp -> rows=" + rows + " rowH=" + rowDp + "dp");
        // v4.0.2：把启动器给的完整 options 打进日志。
        //
        // Android 12+ 的「可缩放小组件」会同时给出尺寸【区间】：
        //   MIN_* = 当前占位，MAX_* = 内容应当按多大来渲染（启动器再缩放到 MIN）。
        // 只读 MIN 会把内容渲染得太挤——行数算少，桌面上就空一大截。
        // 这个日志能直接看出启动器到底给了什么。
        try {
            Bundle opt = AppWidgetManager.getInstance(context).getAppWidgetOptions(widgetId);
            if (opt != null) {
                StringBuilder sb = new StringBuilder("WIDGET opts: ");
                for (String k : opt.keySet()) {
                    Object v = opt.get(k);
                    sb.append(k).append("=").append(v).append("  ");
                }
                Log.i(TAG, sb.toString());
            }
        } catch (Throwable t) {
            Log.w(TAG, "widget options dump failed: " + t);
        }
        return new int[]{rows, heightDp, rowDp};
    }

    private static PendingIntent openApp(Context context) {
        Intent open = new Intent(context, PickupListActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(context, 9001, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 打勾：标记已取件。广播（不启动界面）。 */
    private static PendingIntent doneAction(Context context, long recordId) {
        return broadcast(context, ACTION_DONE, recordId, 2);
    }

    /** 点击取件码：复制。广播（不启动界面）。 */
    private static PendingIntent copyAction(Context context, long recordId) {
        return broadcast(context, ACTION_COPY, recordId, 1);
    }

    private static PendingIntent broadcast(Context context, String action, long recordId, int kind) {
        Intent intent = new Intent(context, WidgetActionReceiver.class);
        intent.setAction(action);
        intent.putExtra(EXTRA_ID, recordId);
        intent.setData(Uri.parse("pickup://widget/" + recordId + "/" + kind));
        int requestCode = (int) (recordId % 100000L) * 10 + kind;
        return PendingIntent.getBroadcast(context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * 小组件动作接收端。
     *
     * <p>v4.0.1：原先用 Activity 承接动作（WidgetActionActivity），
     * 点一下打勾就会把 App 拉到前台——用户明确要求「在小组件上操作不该跳转」。
     * 现在改为广播：动作在后台完成，只更新小组件与本地库，不带起任何界面。
     */
    public static class WidgetActionReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (context == null || intent == null) return;
            final String action = intent.getAction();
            final long id = intent.getLongExtra(EXTRA_ID, -1L);
            if (id <= 0) return;
            final Context app = context.getApplicationContext();

            PickupRepository.get(app).getByIdAsync(id, record -> {
                if (record == null) return;
                if (ACTION_COPY.equals(action)) {
                    copy(app, record.code);
                    return;
                }
                if (ACTION_DONE.equals(action)) {
                    markDone(app, record);
                }
            });
        }

        private void copy(Context app, String code) {
            try {
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager)
                                app.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("pickupcode", code));
                }
                // 后台广播里弹 Toast 需要主线程 Handler
                MAIN.post(() -> android.widget.Toast.makeText(app,
                        "已复制取件码：" + code,
                        android.widget.Toast.LENGTH_SHORT).show());
            } catch (Throwable t) {
                Log.w(TAG, "widget copy failed: " + t);
            }
        }

        private void markDone(Context app, PickupRecord record) {
            PickupActions.markDone(app, record, (localChanged, backendRc) -> {
                if (localChanged <= 0) return;
                // 标记后刷新小组件，并启用「补位动画」——下面的内容顶上来。
            }, true);
        }
    }
}
