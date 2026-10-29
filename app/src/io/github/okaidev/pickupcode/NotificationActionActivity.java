package io.github.okaidev.pickupcode;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/**
 * 通知动作的受控入口（4.0 Stage 0）。
 *
 * 旧实现把 "copy" / "done" 两个 extra 直接交给 exported 的 LauncherActivity：
 * 任意 App 都能 startActivity 并传 done=<code>，触发 root SQL 把用户待办标记完成。
 *
 * 现在：
 *  - 本 Activity exported=false，只能由本 App 的 PendingIntent 启动；
 *  - copy：前台剪贴板 + 打开目标待办 App；
 *  - done：校验码形 → 后台执行标记 → 提示结果 → 打开目标 App；
 *  - LauncherActivity 不再读取这两个 extra。
 */
public class NotificationActionActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        if (intent == null) {
            finish();
            return;
        }
        String copy = intent.getStringExtra("copy");
        if (copy != null && !copy.trim().isEmpty()) {
            handleCopy(copy.trim());
            return;
        }
        String done = intent.getStringExtra("done");
        if (done != null && !done.trim().isEmpty()) {
            handleDone(done.trim());
            return;
        }
        finish();
    }

    private void handleCopy(String codes) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("pickupcode", codes));
            Toast.makeText(this, "已复制取件码：" + codes, Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Toast.makeText(this, "复制失败：" + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
        openTarget();
        finish();
    }

    private void handleDone(String code) {
        boolean valid = code.length() <= 20
                && code.indexOf('-') > 0
                && code.matches("[A-Za-z0-9-]+");
        if (!valid) {
            Toast.makeText(this, "无效的取件码", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        final String sql = NotesBackend.markDoneSql(this, code);
        new Thread(() -> {
            final int rc = TodoWriter.runSql(this, sql);
            runOnUiThread(() -> {
                Toast.makeText(this,
                        rc == 0 ? "已标记已取件：" + code : "标记失败（rc=" + rc + "）",
                        Toast.LENGTH_LONG).show();
                if (rc == 0) {
                    openTarget();
                }
                finish();
            });
        }, "pickup-mark-done").start();
    }

    private void openTarget() {
        try {
            Intent notes = getPackageManager().getLaunchIntentForPackage(NotesBackend.targetPkg(this));
            if (notes != null) {
                notes.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(notes);
            }
        } catch (Throwable ignored) {
        }
    }
}
