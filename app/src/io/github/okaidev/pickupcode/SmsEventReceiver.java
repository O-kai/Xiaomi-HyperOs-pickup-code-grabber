package io.github.okaidev.pickupcode;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.util.List;

/**
 * 短信事件接收器（模块自身进程）：
 * Hook 进程抓到短信后发广播到这里，这里完成：提取 → 去重 → 写待办 → 通知。
 */
public class SmsEventReceiver extends BroadcastReceiver {

    public static final String ACTION = "io.github.okaidev.pickupcode.action.ON_SMS";
    private static final String TAG = "PICKUPDEBUG";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;

        // v3.0.0 架构修复：通知通道广播同样可能在模块进程冷启动时到达，
        // 与 TodoProvider 一致——进程入口先初始化规则引擎（热更规则 + 用户规则）
        try {
            PickupExtractor.init(ctx);
        } catch (Throwable t) {
            Log.w(TAG, "Receiver init rules skipped: " + t);
        }

        String sender = intent.getStringExtra("sender");
        String body = intent.getStringExtra("body");
        long ts = intent.getLongExtra("ts", System.currentTimeMillis());
        boolean miss = intent.getBooleanExtra("miss", false);
        Log.i(TAG, "EVENT: sender=" + sender + " ts=" + ts + " body=" + (body == null ? "" : body.substring(0, Math.min(body.length(), 120))));

        if (body == null || body.trim().isEmpty()) return;

        // v2.9.0：通知通道转发的「漏抓嫌疑」样本 → 直接记错题本（App 进程有 Context）
        if (miss) {
            MissedSmsStore.record(ctx, sender == null ? "" : sender, body);
            Log.i(TAG, "EVENT: 通知漏抓样本已记录错题本");
            return;
        }

        // v3.1.0：门禁强度与 TodoProvider 对齐。
        // 此前这里用 lookLikePickupSms（特征词「且」存在形状 token），比 TodoProvider 的
        // hasFeatureWords（只要特征词）更严一层，导致同一条短信在两条通道上的结果不一致：
        // 「有快递特征词但文案里根本没有数字码」的短信，在这条通道被直接丢弃，
        // 而在另一条通道会记进错题本。统一为 hasFeatureWords，
        // 既保证两通道一致，也让漏抓样本能被错题本收集到（丢进去提取不出码 → 记错题本）。
        if (!PickupExtractor.hasFeatureWords(sender, body)) {
            Log.i(TAG, "EVENT: 非取件短信，跳过");
            return;
        }

        // v3.1.0：BroadcastReceiver.onReceive 在【主线程】执行，而 TodoWriter.handle 内部要跑
        // su + sqlite3（最多 5 条 SQL × 5 个 su 路径），耗时可达数秒 → 直接 ANR。
        // 改用 goAsync() 把处理挪到后台线程，并在 onReceive 返回前调用 finish()。
        // （另一条主通道 TodoProvider 跑在 Binder 线程池，本身就不阻塞主线程。）
        final PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                List<String> wrote = TodoWriter.handle(ctx, sender, body);
                if (!wrote.isEmpty()) {
                    Log.i(TAG, "EVENT: 已写入待办 " + wrote);
                } else {
                    String dedup = TodoWriter.getLastDedupHits();
                    if (dedup != null && !dedup.trim().isEmpty()) {
                        Log.i(TAG, "EVENT: 全部命中去重 " + dedup);
                    } else {
                        String diag = TodoWriter.getLastWriteDiag();
                        Log.e(TAG, "EVENT: 写入失败: " + diag);
                        Notifier.notifyWriteFailed(ctx,
                                diag == null || diag.trim().isEmpty() ? "原因未知" : diag);
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "EVENT handle err: " + t);
            } finally {
                pending.finish();
            }
        }).start();
    }
}
