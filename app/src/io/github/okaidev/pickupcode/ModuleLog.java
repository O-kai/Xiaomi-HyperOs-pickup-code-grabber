package io.github.okaidev.pickupcode;

import android.util.Log;
import java.util.function.Consumer;

/** Safe in both the ordinary application and injected processes. */
final class ModuleLog {
    static volatile Consumer<String> framework;
    static void log(String message) {
        try { if (framework != null) framework.accept(message); } catch (Throwable ignored) { }
        try { Log.i("PICKUPDEBUG", message); } catch (Throwable ignored) { }
    }
}
