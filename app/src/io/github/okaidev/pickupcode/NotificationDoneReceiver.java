package io.github.okaidev.pickupcode;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/** Non-exported completion entry; a rear-screen action does not need to launch an Activity. */
public final class NotificationDoneReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String code = intent == null ? null : intent.getStringExtra("done");
        if (code == null || code.length() < 3 || code.length() > 20
                || !code.matches("[A-Za-z0-9-]+")) return;
        PendingResult pending = goAsync();
        Context app = context.getApplicationContext();
        PickupActions.markDoneByCode(app, code, (local, backend) -> {
            try {
                Toast.makeText(app, local > 0 || backend == 0 ? "已标记已取件" : "未能标记已取件",
                        Toast.LENGTH_SHORT).show();
            } finally {
                if (pending != null) pending.finish();
            }
        });
    }
}
