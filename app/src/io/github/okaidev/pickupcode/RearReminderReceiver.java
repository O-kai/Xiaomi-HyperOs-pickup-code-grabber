package io.github.okaidev.pickupcode;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The rear card follows pending records even if its quiet carrier notification is dismissed. */
public final class RearReminderReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        PendingResult pending = goAsync();
        Notifier.refreshExisting(context.getApplicationContext(), () -> {
            if (pending != null) pending.finish();
        });
    }
}
