package com.pickupcode.grabber;

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
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (METHOD_MARK_DONE.equals(method) && arg != null && !arg.isEmpty()) {
            String sql = "UPDATE todo SET is_finish=1, mark_finish_time=strftime('%s','now')*1000 "
                    + "WHERE is_finish=0 AND content LIKE '%" + TodoWriter.escape(arg) + "%';";
            int rc = TodoWriter.runSql(getContext(), sql);
            Log.i(TAG, "MARK_DONE(call): code=" + arg + " rc=" + rc);
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
            if (!PickupExtractor.lookLikePickupSms(body)) {
                Log.i(TAG, "PROVIDER-CALL: 非取件短信，跳过");
                return null;
            }
            java.util.List<String> wrote = TodoWriter.handle(getContext(), sender, body);
            if (!wrote.isEmpty()) {
                Log.i(TAG, "PROVIDER-CALL: 已写入待办 " + wrote);
                // 通知（点击复制 + 打开便签）
                Notifier.notifyCodes(getContext(), wrote, null);
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
