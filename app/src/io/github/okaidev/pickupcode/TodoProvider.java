package io.github.okaidev.pickupcode;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.util.Log;

/**
 * 跨进程事实入口（ContentProvider 唤醒机制）：
 * Hook 进程（短信/电话/system_server）通过 ContentResolver.call 把事件交给模块自身进程，
 * 此处完成：校验调用方 → 提取 → 去重 → 写待办 → 通知。
 *
 * 选择 Provider 而非广播：Provider 调用不受 MIUI 后台冻结/进程冷启动限制。
 *
 * 安全边界（4.0 Stage 0）：
 *  - Provider 必须 exported=true，否则系统进程无法唤醒 App；
 *  - 但 exported=true 不等于任意 App 可调用：onSms/onMissed 只接受系统/电话/短信/
 *    telephony provider/自身 UID；markDone 只接受自身 UID；
 *  - 历史 SmsEventReceiver 广播入口已移除，避免任意 App 发广播伪造短信。
 */
public class TodoProvider extends ContentProvider {

    private static final String TAG = "PICKUPDEBUG";
    private static final String METHOD_ON_SMS = "onSms";
    private static final String METHOD_MARK_DONE = "markDone";

    private static final int MAX_BODY_CHARS = 4000;
    private static final int MAX_SENDER_CHARS = 128;
    private static final long MAX_FUTURE_SKEW_MS = 24L * 60L * 60L * 1000L;

    @Override
    public boolean onCreate() {
        // v3.0.0 架构修复：Provider 是模块进程被短信 Hook 唤起的冷启动入口，
        // 此处必须初始化规则引擎——否则用户从未手动打开 App 时，
        // 热更新 rules.json 与用户自定义规则都不会生效（会静默回落到编译内置规则）
        try {
            PickupExtractor.init(getContext());
        } catch (Throwable t) {
            Log.w(TAG, "Provider init rules skipped: " + t);
        }
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        final Context ctx = getContext();
        final int callingUid = Binder.getCallingUid();

        if (METHOD_MARK_DONE.equals(method)) {
            // 标记已取件只能由模块自身发起（通知动作/链路测试都在 App 进程）。
            // 旧实现允许任意 App 通过 Provider 传一个合法码把用户待办标记完成。
            if (!CallerPolicy.isSelf(callingUid)) {
                return denied(ctx, callingUid, method);
            }
            if (arg == null || arg.trim().isEmpty()) {
                return error("empty_code");
            }
            String code = arg.trim();
            boolean valid = code.length() <= 20
                    && code.indexOf('-') > 0
                    && code.matches("[A-Za-z0-9-]+");
            if (!valid) {
                Log.w(TAG, "MARK_DONE(call) 拒绝非法入参: " + code);
                return error("invalid_code");
            }
            if (ctx == null) {
                return error("no_context");
            }
            String sql = NotesBackend.markDoneSql(ctx, code);
            int rc = TodoWriter.runSql(ctx, sql);
            Log.i(TAG, "MARK_DONE(call): code=" + code + " rc=" + rc);
            Bundle r = new Bundle();
            r.putInt("rc", rc);
            return r;
        }

        if (METHOD_ON_SMS.equals(method)) {
            if (!CallerPolicy.isTrustedSmsUid(ctx, callingUid)) {
                return denied(ctx, callingUid, method);
            }
            if (extras == null || ctx == null) {
                return error("empty_extras");
            }

            String sender = extras.getString("sender");
            String body = extras.getString("body");
            long ts = extras.getLong("ts", System.currentTimeMillis());
            boolean miss = extras.getBoolean("miss", false);

            if (body == null || body.trim().isEmpty()) {
                Log.i(TAG, "PROVIDER-CALL: 空正文，跳过");
                return error("empty_body");
            }
            if (body.length() > MAX_BODY_CHARS) {
                // 只记录长度，不打印正文；避免日志/诊断泄露。
                Log.w(TAG, "PROVIDER-CALL: body too long len=" + body.length());
                return error("body_too_long");
            }
            if (sender != null && sender.length() > MAX_SENDER_CHARS) {
                sender = sender.substring(0, MAX_SENDER_CHARS);
            }
            long now = System.currentTimeMillis();
            if (ts <= 0 || ts > now + MAX_FUTURE_SKEW_MS) {
                ts = now;
            }

            // 漏抓样本：通知通道在模块 App 进程记录（有 Context），不需要再走广播。
            if (miss) {
                try {
                    MissedSmsStore.record(ctx, sender == null ? "" : sender, body);
                    Log.i(TAG, "PROVIDER-CALL: missed sample recorded");
                } catch (Throwable t) {
                    Log.w(TAG, "PROVIDER-CALL: missed sample failed: " + t);
                }
                Bundle r = new Bundle();
                r.putInt("rc", 0);
                return r;
            }

            Log.i(TAG, "PROVIDER-CALL: sender=" + sender + " ts=" + ts);
            // v3.0.0：发送方以显式参数传入（避免并发时号码与短信错配）
            // v2.8.0：预检放宽为「含快递特征词」——提取与漏抓错题本判定都在 handle 内完成
            if (!PickupExtractor.hasFeatureWords(sender, body)) {
                Log.i(TAG, "PROVIDER-CALL: 非取件短信，跳过");
                Bundle r = new Bundle();
                r.putInt("rc", 0);
                r.putString("status", "not_pickup");
                r.putInt("code_count", 0);
                r.putString("result", "[]");
                return r;
            }

            // 4.0 Stage 2：先落本地取件库，再走系统待办写穿。
            // 本地库只在 App 进程使用，Hook/SystemDirectWriter 不引用 Room。
            boolean blacklisted = TodoWriter.isBlacklisted(ctx, body);
            java.util.List<String> parsed = blacklisted
                    ? java.util.Collections.emptyList()
                    : PickupExtractor.extract(sender, body);
            PickupRepository.IngestResult local = null;
            if (!blacklisted && !parsed.isEmpty()) {
                try {
                    local = PickupRepository.get(ctx).ingest(
                            sender, body, parsed,
                            TodoWriter.resolveSource(sender, body),
                            TodoWriter.extractPlace(sender, body),
                            extras.getBoolean("test", false));
                } catch (Throwable t) {
                    Log.w(TAG, "LOCAL DB ingest failed: " + t);
                }
            }

            // v4.0.2 第八轮（来自 PR #14）：用「这条事件自己的结果」判断，
            // 不再读进程级的 lastWriteDiag / lastDedupHits——
            // 两条短信并发时那份全局状态会被后一条覆盖，导致误报/漏报。
            TodoWriter.HandleResult outcome = TodoWriter.handleDetailed(ctx, sender, body);
            java.util.List<String> wrote = outcome.written;
            boolean localNew = local != null && local.hasNew();
            if (!wrote.isEmpty()) {
                Log.i(TAG, "PROVIDER-CALL: 已写入待办 " + wrote);
                Notifier.notifyCodes(ctx, wrote, null);
            } else if (localNew) {
                // 系统待办已存在（或写入失败），但本地库首次记录：仍然提醒，
                // 因为本地取件库是 4.0 的事实源。
                Log.i(TAG, "PROVIDER-CALL: 本地首次记录 " + parsed);
                Notifier.notifyCodes(ctx, parsed, null);
            } else if (outcome.failedCount > 0 && !blacklisted) {
                String diag = outcome.diagnostic;
                Log.e(TAG, "PROVIDER-CALL: 提取到取件码但写入失败: " + diag);
                Notifier.notifyWriteFailed(ctx,
                        diag == null || diag.trim().isEmpty() ? "原因未知" : diag);
            } else if (outcome.dedupCount > 0) {
                Log.i(TAG, "PROVIDER-CALL: 全部命中去重 n=" + outcome.dedupCount);
            }
            Log.i(TAG, "PROVIDER-CALL: status=" + outcome.status
                    + " codes=" + outcome.codeCount + " written=" + wrote.size()
                    + " dedup=" + outcome.dedupCount + " failed=" + outcome.failedCount);
            Bundle r = new Bundle();
            r.putInt("rc", 0);
            r.putString("status", outcome.status);
            r.putInt("code_count", outcome.codeCount);
            r.putInt("written_count", wrote.size());
            r.putInt("dedup_count", outcome.dedupCount);
            r.putInt("failed_count", outcome.failedCount);
            r.putString("result", wrote.toString());   // 兼容旧调用方
            return r;
        }
        return null;
    }

    private Bundle denied(Context ctx, int uid, String method) {
        Log.w(TAG, "IPC denied: method=" + method + " " + CallerPolicy.describe(ctx, uid));
        Bundle b = new Bundle();
        b.putInt("rc", -3);
        b.putString("error", "unauthorized");
        return b;
    }

    private Bundle error(String reason) {
        Bundle b = new Bundle();
        b.putInt("rc", -2);
        b.putString("error", reason == null ? "unknown" : reason);
        return b;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }
}
