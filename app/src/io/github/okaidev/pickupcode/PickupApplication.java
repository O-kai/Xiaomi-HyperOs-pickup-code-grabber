package io.github.okaidev.pickupcode;

import android.app.Application;

/** The framework service can start this process during Direct Boot; defer private data access. */
public final class PickupApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        if ("io.github.okaidev.pickupcode".equals(getPackageName())) {
            FrameworkClient.init(this);
            android.os.UserManager users = getSystemService(android.os.UserManager.class);
            if (users != null && users.isUserUnlocked()) Notifier.refreshExisting(this);
        }
    }
}
