package io.github.okaidev.pickupcode;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

/**
 * 跨进程事实入口（ContentProvider 唤醒机制）：
 * Hook 进程（短信/电话进程）通过 ContentResolver.call 把短信事件交给模块自身进程，
 * 此处完成：提取 → 去重 → 写待办。
 * 选择 Provider 而非广播：Provider 调用不受 MIUI 后台冻结/进程冷启动限制。
 */
public class TodoProvider extends ContentProvider {

    private static final String TAG = "PICKUPDEBUG";
    private static final String METHOD_ON_SMS = "onSms";
    private static final String METHOD_MARK_DONE = "markDone";

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
        if (METHOD_MARK_DONE.equals(method) && arg != null && !arg.isEmpty()) {
            // v3.1.0 安全加固：本 Provider 为 exported=true 且无 android:permission
            // （加权限会阻断 LSPosed 从系统进程唤醒，那是核心功能，不能动），只能靠入参校验自保。
            // 否则任意 App 传 arg="%" 会得到 LIKE '%%'，批量标记用户全部未完成待办。
            String code = arg.trim();
            boolean valid = code.length() <= 20 && code.indexOf('-') > 0
                    && code.matches("[A-Za-z0-9-]+");
            if (!valid) {
                Log.w(TAG, "MARK_DONE(call) 拒绝非法入参: " + code);
                Bundle bad = new Bundle();
                bad.putInt("rc", -2);
                return bad;
            }
            String sql = NotesBackend.markDoneSql(getContext(), code);
            int rc = TodoWriter.runSql(getContext(), sql);
            Log.i(TAG, "MARK_DONE(call): code=" + code + " rc=" + rc);
            Bundle r = new Bundle();
            r.putInt("rc", rc);
            return r;
        }
        if (METHOD_ON_SMS.equals(method) && extras != null) {
            String sender = extras.getString("sender");
            String body = extras.getString("body");
            long ts = extras.getLong("ts", System.currentTimeMillis());
            Log.i(TAG, "PROVIDER-CALL: sender=" + sender + " ts=" + ts);
            if (body == null || body.trim().isEmpty()) {
                Log.i(TAG, "PROVIDER-CALL: 空正文，跳过");
                return null;
            }
            // v3.0.0：发送方以显式参数传入（避免并发时号码与短信错配）
            // v2.8.0：预检放宽为「含快递特征词」——提取与漏抓错题本判定都在 handle 内完成
            // （原 lookLikePickupSms 要求形状 token 存在，会把"有特征词但文案无数字码"的
            //   真漏抓样本提前短路，导致错题本永远收不到这类记录）
            if (!PickupExtractor.hasFeatureWords(sender, body)) {
                Log.i(TAG, "PROVIDER-CALL: 非取件短信，跳过");
                return null;
            }
            java.util.List<String> wrote = TodoWriter.handle(getContext(), sender, body);
            if (!wrote.isEmpty()) {
                Log.i(TAG, "PROVIDER-CALL: 已写入待办 " + wrote);
                // 通知（点击复制 + 打开便签）
                Notifier.notifyCodes(getContext(), wrote, null);
            } else {
                // v3.1.0：写入失败/全部命中去重时给出反馈。
                //   此前两条路径都静默——用户只看到「待办里什么都没有」，
                //   既不知道是没抓到还是写失败，也无从排查（目标表不存在、
                //   root 未授权、sqlite3 缺失…）。这里区分两种情况告知用户。
                String dedup = TodoWriter.getLastDedupHits();
                if (dedup != null && !dedup.trim().isEmpty()) {
                    // 命中去重：本来就写过，不是故障，不打扰
                    Log.i(TAG, "PROVIDER-CALL: 全部命中去重 " + dedup);
                } else {
                    String diag = TodoWriter.getLastWriteDiag();
                    Log.e(TAG, "PROVIDER-CALL: 提取到取件码但写入失败: " + diag);
                    Notifier.notifyWriteFailed(getContext(),
                            diag == null || diag.trim().isEmpty() ? "原因未知" : diag);
                }
            }
            Bundle r = new Bundle();
            r.putString("result", wrote.toString());
            return r;
        }
        return null;
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
