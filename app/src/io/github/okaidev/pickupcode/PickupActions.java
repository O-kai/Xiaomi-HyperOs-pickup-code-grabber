package io.github.okaidev.pickupcode;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * 取件动作统一入口：本地库 + 系统待办一起更新。
 * 避免「列表/小组件标记已取件，但小米笔记里还是未完成」的状态分裂。
 */
public final class PickupActions {

    private static final String TAG = "PICKUPDEBUG";

    private PickupActions() {
    }

    public interface Callback {
        void onResult(int localChanged, int backendRc);
    }

    public static void markDone(Context context, PickupRecord record, Callback callback) {
        markDone(context, record, callback, false);
    }

    /**
     * 标记已取件。
     *
     * @param animateWidget true 时小组件带「补位动画」刷新——
     *        从小组件上操作时，下面的内容要往上顶；App 内操作则不需要。
     */
    public static void markDone(Context context, PickupRecord record, Callback callback,
                                boolean animateWidget) {
        if (record == null) {
            callback.onResult(0, -1);
            return;
        }
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            int local = PickupRepository.get(app).markDoneSync(record.id);
            int backend = markBackend(app, record.code);
            new Handler(Looper.getMainLooper()).post(() -> {
                if (local > 0) {
                    // 小组件刷新放在这里：仓库层不再自动刷，
                    // 否则会先出一版没有动画的内容，把补位动画顶掉。
                    try {
                        PickupWidgetProvider.refresh(app, animateWidget);
                    } catch (Throwable ignored) { }
                }
                callback.onResult(local, backend);
            });
        }, "pickup-mark-done").start();
    }

    public static void markDoneByCode(Context context, String code, Callback callback) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            int local = PickupRepository.get(app).markDoneByCodeSync(code);
            int backend = markBackend(app, code);
            new Handler(Looper.getMainLooper()).post(() -> callback.onResult(local, backend));
        }, "pickup-mark-done-code").start();
    }

    private static int markBackend(Context context, String code) {
        try {
            String sql = NotesBackend.markDoneSql(context, code);
            return TodoWriter.runSql(context, sql);
        } catch (Throwable t) {
            Log.w(TAG, "mark backend failed: " + t);
            return -1;
        }
    }
}
