package io.github.okaidev.pickupcode;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/**
 * 小组件动作入口：exported=false，只允许本 App 的 PendingIntent 启动。
 * copy：前台复制取件码；done：本地标记已取件（后续阶段同步系统待办）。
 */
public class WidgetActionActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        if (intent == null) {
            finish();
            return;
        }
        long id = intent.getLongExtra(PickupWidgetProvider.EXTRA_ID, -1L);
        if (id <= 0) {
            finish();
            return;
        }
        final String action = intent.getAction();
        PickupRepository.get(this).getByIdAsync(id, record -> {
            if (record == null) {
                Toast.makeText(this, "取件记录不存在", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            if (PickupWidgetProvider.ACTION_COPY.equals(action)) {
                copy(record.code);
                finish();
                return;
            }
            if (PickupWidgetProvider.ACTION_DONE.equals(action)) {
                markDone(record);
                return;
            }
            finish();
        });
    }

    private void copy(String code) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("pickupcode", code));
            Toast.makeText(this, "已复制取件码：" + code, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "复制失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void markDone(PickupRecord record) {
        PickupActions.markDone(this, record, (localChanged, backendRc) -> {
            String msg;
            if (localChanged <= 0) {
                msg = "标记失败";
            } else if (backendRc == 0) {
                msg = "已标记已取件：" + record.code;
            } else {
                msg = "已在本机标记，系统待办同步失败";
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
            finish();
        });
    }
}
