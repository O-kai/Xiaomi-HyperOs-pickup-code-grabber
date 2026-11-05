package io.github.okaidev.pickupcode;

import android.content.Context;
import android.util.Log;

/**
 * 「软件内部记录」这一侧的开关（v4.0.3 第二轮）。
 *
 * <p>它不是一个写入后端，而是本地库的存在性开关：
 * 打开时取件码落进 Room，首页出现「待取件」、桌面小组件有内容；
 * 关掉时本地库清空、首页整块消失、桌面小组件被系统摘除。
 *
 * <p>关掉要连带做两件事，用户的原话是「只要调整开关，记录就清除」：
 * <ol>
 *   <li>清空本地库——这是他明确要求的，但界面上必须先二次确认并报出条数；</li>
 *   <li>让桌面小组件消失——App 无权直接删 launcher 里的实例，
 *       只能停用 Provider 请系统摘除（见 {@link #retireWidget}）。</li>
 * </ol>
 *
 * <p><b>为什么不放进 {@link NotesBackend}</b>：那个类会被离线规则回归用
 * {@code -sourcepath} 自动推导编译，多引一个 {@link PickupRepository}
 * 就会把 Room / AndroidX 拖进那条没有 AndroidX jar 的编译链。
 */
public final class LocalRecord {

    private static final String TAG = "PICKUPDEBUG";

    private LocalRecord() { }

    /** 内部记录开着吗 */
    public static boolean isOn(Context ctx) {
        return NotesBackend.localSelected(ctx);
    }

    /**
     * 清空本地库（异步）。调用方必须已经拿到用户的二次确认。
     *
     * <p><b>必须异步</b>：Room 默认拒绝主线程访问，同步调 {@code clearAllSync()}
     * 会抛 {@code IllegalStateException} 并被内部 catch 吞成「删除 0 条」——
     * 界面提示「已关闭」，数据其实一条没少。这个坑本项目在「界面条数」
     * 上踩过一次，这次又踩在了「清库」上。
     *
     * @param callback 在主线程回调实际删除条数
     */
    public static void clearAllAsync(Context ctx, PickupRepository.Callback<Integer> callback) {
        if (ctx == null) {
            if (callback != null) callback.onResult(0);
            return;
        }
        final Context app = ctx.getApplicationContext();
        PickupRepository.get(app).clearAllAsync(n -> {
            Log.i(TAG, "local record cleared: " + n);
            if (callback != null) callback.onResult(n);
        });
    }

    /**
     * 让桌面小组件退场。
     *
     * <p>两步，顺序不能反：
     * <ol>
     *   <li>同步刷成「取件记录已关闭」。停用之后系统会摘掉实例，
     *       万一某个 ROM 不摘，桌面上留下的就是这版说明文字，而不是旧取件码；</li>
     *   <li>停用 Provider。AOSP 会在组件被停用后删除它的全部实例并通知 launcher，
     *       HyperOS 实测同样生效（本机验证：停用后 {@code getAppWidgetIds} 立刻归零，
     *       桌面上的组件也随之消失）。这是 App 端唯一能真正移除别人桌面组件的途径。</li>
     * </ol>
     *
     * <p>代价是：重新打开内部记录时组件不会自己回来，需要到设置页重新添加。
     * 这是「关掉就彻底消失」的必然结果——用户明确要的是后者。
     */
    public static void retireWidget(Context ctx) {
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext();

        // 1) 同步渲染关闭态（必须同步：停用之后就没机会再更新了）
        PickupWidgetProvider.showClosedState(app);

        // 2) 停用组件，让系统摘掉实例
        if (!WidgetPresence.setEnabled(app, false)) {
            Log.e(TAG, "retire widget: disable provider failed");
            return;
        }
        Log.i(TAG, "retire widget: provider disabled");
    }

    /**
     * 重新打开内部记录时，把 Provider 放回来。
     *
     * <p>桌面上的旧实例已经被系统摘掉了，用户需要到「设置 → 桌面小组件」
     * 重新添加一次；添加后读的就是本地库里的真实记录。
     */
    public static void restoreWidget(Context ctx) {
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext();
        if (WidgetPresence.isEnabled(app)) return;
        if (!WidgetPresence.setEnabled(app, true)) {
            Log.e(TAG, "restore widget: enable provider failed");
            return;
        }
        Log.i(TAG, "restore widget: provider re-enabled");
    }
}
