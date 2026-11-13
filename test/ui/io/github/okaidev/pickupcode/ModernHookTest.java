package io.github.okaidev.pickupcode;

import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class ModernHookTest {
    private interface Original { Object call() throws Throwable; }
    private static XposedInterface.Chain chain(AtomicInteger calls, Original original) {
        return (XposedInterface.Chain) Proxy.newProxyInstance(XposedInterface.Chain.class.getClassLoader(),
                new Class<?>[]{XposedInterface.Chain.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "proceed": calls.incrementAndGet(); return original.call();
                        case "getArgs": return Arrays.asList("payload", 2);
                        case "getThisObject": return "receiver";
                        default: throw new AssertionError(method.getName());
                    }
                });
    }
    @Test public void failingAfterObserverPreservesOriginalResultAndCallsOnce() throws Throwable {
        AtomicInteger calls = new AtomicInteger();
        Object result = new Object();
        assertSame(result, HookRuntime.after((receiver, args, value) -> {
            assertEquals("receiver", receiver);
            assertEquals("payload", args[0]);
            assertSame(result, value);
            throw new IllegalStateException("observer");
        }).intercept(chain(calls, () -> result)));
        assertEquals(1, calls.get());
    }
    @Test public void originalExceptionIsNotRetriedOrSwallowed() throws Throwable {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger observed = new AtomicInteger();
        RuntimeException original = new RuntimeException("original");
        try {
            HookRuntime.after((receiver, args, value) -> observed.incrementAndGet())
                    .intercept(chain(calls, () -> { throw original; }));
            fail("must propagate original failure");
        } catch (RuntimeException e) { assertSame(original, e); }
        assertEquals(1, calls.get());
        assertEquals(0, observed.get());
    }
    @Test public void failingBeforeObserverStillCallsOriginalOnce() throws Throwable {
        AtomicInteger calls = new AtomicInteger();
        assertEquals(7, HookRuntime.before((receiver, args, value) -> { throw new IllegalArgumentException(); })
                .intercept(chain(calls, () -> 7)));
        assertEquals(1, calls.get());
    }
    private static boolean providerGate(android.content.pm.ProviderInfo provider, String caller, int uid) {
        return true;
    }
    @Test public void colorOsShortCircuitOnlyAllowsOurProviderFromSystemIdentity() throws Throwable {
        java.lang.reflect.Field field = ColorOsProviderWake.class.getDeclaredField("GATE_HOOK");
        field.setAccessible(true);
        XposedInterface.Hooker gate = (XposedInterface.Hooker) field.get(null);
        java.lang.reflect.Method executable = ModernHookTest.class.getDeclaredMethod("providerGate",
                android.content.pm.ProviderInfo.class, String.class, int.class);
        for (String pkg : new String[]{"io.github.okaidev.pickupcode", "another.provider"}) {
            for (int uid : new int[]{1001, 11001}) {
                android.content.pm.ProviderInfo provider = new android.content.pm.ProviderInfo();
                provider.packageName = pkg;
                AtomicInteger originalCalls = new AtomicInteger();
                XposedInterface.Chain request = (XposedInterface.Chain) Proxy.newProxyInstance(
                        XposedInterface.Chain.class.getClassLoader(), new Class<?>[]{XposedInterface.Chain.class},
                        (proxy, method, args) -> {
                            switch (method.getName()) {
                                case "getExecutable": return executable;
                                case "getArgs": return Arrays.asList(provider, "com.android.phone", uid);
                                case "proceed": originalCalls.incrementAndGet(); return true;
                                default: throw new AssertionError(method.getName());
                            }
                        });
                boolean allow = pkg.equals("io.github.okaidev.pickupcode") && uid == 1001;
                assertEquals(!allow, gate.intercept(request));
                assertEquals(allow ? 0 : 1, originalCalls.get());
            }
        }
    }
    @Test public void callbackAssociationUsesIdentityAndClearsReusedRequests() {
        WeakIdentityMap<String> endpoints = new WeakIdentityMap<>(2);
        Object first = new String("equal");
        Object second = new String("equal");
        endpoints.put(first, "logistics");
        assertNull(endpoints.get(second));
        endpoints.put(second, "cabinet");
        assertEquals("logistics", endpoints.get(first));
        assertEquals("cabinet", endpoints.get(second));
        endpoints.put(first, null);
        assertNull(endpoints.get(first));
        endpoints.put(first, "new request");
        Object third = new Object();
        endpoints.put(third, "third");
        assertNull(endpoints.get(second)); // Bound memory even if callbacks remain alive.
        assertEquals("new request", endpoints.get(first));
    }
}
