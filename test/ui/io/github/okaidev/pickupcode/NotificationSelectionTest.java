package io.github.okaidev.pickupcode;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Looper;
import android.provider.Settings;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class NotificationSelectionTest {
    private Context context;
    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        NotificationSelection.automatic(context);
        NotesBackend.saveSelection(context, Collections.singleton(NotesBackend.TARGET_LOCAL));
        Settings.System.putInt(context.getContentResolver(), "notification_focus_protocol", 4);
        PackageInfo host = new PackageInfo();
        host.packageName = RearHostHook.HOST;
        host.applicationInfo = new ApplicationInfo();
        host.applicationInfo.packageName = host.packageName;
        shadowOf(context.getPackageManager()).installPackage(host);
        context.getSystemService(NotificationManager.class).cancelAll();
    }

    @Test public void selectedIslandAndAllPendingRearHaveIndependentLifecycles() throws Exception {
        PickupRecord one = record(1, "12-3-4567"), two = record(2, "58-2-9041");
        NotificationSelection.save(context, Collections.singleton("2"));
        snapshot(one, two);
        assertEquals(two.code, notification(Notifier.PICKUP_ID).extras.getString(Notification.EXTRA_TITLE));
        assertEquals("2 件待取", notification(Notifier.REAR_ID).extras.getString(Notification.EXTRA_TITLE));
        assertFalse(notification(Notifier.PICKUP_ID).extras.containsKey(RearScreen.PARAM));
        assertFalse(notification(Notifier.REAR_ID).extras.containsKey(HyperIsland.PARAM));
        assertTrue(notification(Notifier.REAR_ID).extras.containsKey(RearScreen.VIEW));
        assertTrue((notification(Notifier.REAR_ID).flags & Notification.FLAG_ONGOING_EVENT) != 0);
        org.json.JSONObject rearParams = new org.json.JSONObject(notification(Notifier.REAR_ID)
                .extras.getString(RearScreen.PARAM)).getJSONObject("rear_param_v1");
        assertTrue(rearParams.toString(), rearParams.getBoolean("enableFloat"));
        assertFalse(rearParams.getBoolean("disable_popup"));
        // Completing the selected parcel must not silently substitute an unselected one on the island.
        snapshot(one);
        assertNull(notification(Notifier.PICKUP_ID));
        assertNotNull(notification(Notifier.REAR_ID));
        rearParams = new org.json.JSONObject(notification(Notifier.REAR_ID)
                .extras.getString(RearScreen.PARAM)).getJSONObject("rear_param_v1");
        assertFalse(rearParams.getBoolean("enableFloat"));
        assertFalse(rearParams.getBoolean("disable_popup"));
        // Explicitly selecting nothing also keeps the rear reminder until the last parcel is taken.
        NotificationSelection.save(context, Collections.emptySet());
        snapshot(one);
        assertNull(notification(Notifier.PICKUP_ID));
        snapshot();
        assertNull(notification(Notifier.REAR_ID));
    }

    @Test public void automaticSelectionResumesAndIgnoresCompletedOrTestRows() {
        PickupRecord one = record(1, "12-3-4567"), two = record(2, "58-2-9041");
        two.isTest = 1;
        assertEquals(1, NotificationSelection.filter(context, Arrays.asList(one, two)).size());
        NotificationSelection.save(context, Collections.singleton("2"));
        assertTrue(NotificationSelection.filter(context, Arrays.asList(one, two)).isEmpty());
        NotificationSelection.automatic(context);
        snapshot(one);
        assertNotNull(notification(Notifier.PICKUP_ID));
    }

    @Test public void addressEnrichmentUpdatesSameRecordAndRefreshesRear() throws Exception {
        PickupRepository repository = PickupRepository.get(context);
        ExecutorService io = Executors.newSingleThreadExecutor();
        try {
            io.submit(() -> repository.ingest("App:PDD", "取件码 12-3-4567", Collections.singletonList("12-3-4567"), "拼多多", "—", false)).get();
            io.submit(() -> repository.ingest("App:PDD", "取件码 12-3-4567", Collections.singletonList("12-3-4567"), "拼多多", "示例驿站 · 测试路", false)).get();
            CompletableFuture<PickupRepository.PendingSnapshot> result = new CompletableFuture<>();
            repository.pendingSnapshotAsync(12, result::complete);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!result.isDone() && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle();
                Thread.sleep(10);
            }
            PickupRepository.PendingSnapshot current = result.get(1, TimeUnit.SECONDS);
            assertEquals(1, current.pendingTotal);
            assertEquals("示例驿站 · 测试路", current.items.get(0).place);
            shadowOf(Looper.getMainLooper()).idle();
            assertNotNull(notification(Notifier.REAR_ID));
        } finally { io.shutdownNow(); }
    }

    @Test public void rearGateOnlyShortCircuitsOwnPackageAndPreservesOthers() throws Throwable {
        for (String pkg : new String[]{RuntimePaths.PKG, RuntimePaths.PKG + ".other", "com.xunmeng.pinduoduo", null}) {
            java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
            io.github.libxposed.api.XposedInterface.Chain chain = (io.github.libxposed.api.XposedInterface.Chain)
                    java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                            new Class<?>[]{io.github.libxposed.api.XposedInterface.Chain.class}, (proxy, method, args) -> {
                                if ("getArg".equals(method.getName())) return pkg;
                                if ("proceed".equals(method.getName())) { calls.incrementAndGet(); return false; }
                                throw new AssertionError(method.getName());
                            });
            boolean own = RuntimePaths.PKG.equals(pkg);
            assertEquals(own, RearHostHook.hooker().intercept(chain));
            assertEquals(own ? 0 : 1, calls.get());
        }
    }

    @Test @Config(sdk = 34) public void rearActivityClickUsesMainDisplayAndBroadcastKeepsItsOptions() throws Throwable {
        for (int kind : new int[]{0, 1, 2}) {
            boolean activity = kind != 1;
            android.app.PendingIntent pending = activity ? RearScreen.openList(context)
                    : android.app.PendingIntent.getBroadcast(context, 42,
                            new android.content.Intent(context, RearReminderReceiver.class),
                            android.app.PendingIntent.FLAG_IMMUTABLE);
            shadowOf(pending).setCreatorPackage(kind == 2 ? "com.example.other" : RuntimePaths.PKG);
            android.app.ActivityOptions options = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(1);
            android.util.Pair<android.content.Intent, android.app.ActivityOptions> launch =
                    android.util.Pair.create(new android.content.Intent(), options);
            java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
            io.github.libxposed.api.XposedInterface.Chain chain = (io.github.libxposed.api.XposedInterface.Chain)
                    java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                            new Class<?>[]{io.github.libxposed.api.XposedInterface.Chain.class}, (proxy, method, args) -> {
                                if ("getArg".equals(method.getName())) return ((Integer) args[0]) == 1 ? pending : launch;
                                if ("proceed".equals(method.getName())) { calls.incrementAndGet(); return true; }
                                throw new AssertionError(method.getName());
                            });
            assertEquals(true, RearHostHook.openOnMainScreen().intercept(chain));
            assertEquals(1, calls.get());
            assertEquals(kind == 0 ? 0 : 1, options.getLaunchDisplayId());
        }
    }

    @Test public void restoringRearDoesNotRestoreDismissedIslandAndCancelsAtZero() throws Exception {
        PickupRepository repository = PickupRepository.get(context);
        ExecutorService io = Executors.newSingleThreadExecutor();
        try {
            io.submit(() -> repository.ingest("App:PDD", "测试取件", Collections.singletonList("12-3-4567"), "拼多多", "测试驿站", false)).get();
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            manager.cancel(Notifier.PICKUP_ID);
            manager.cancel(Notifier.REAR_ID);
            awaitRefresh();
            assertNull(notification(Notifier.PICKUP_ID));
            Notification rear = notification(Notifier.REAR_ID);
            assertNotNull(rear);
            assertEquals(1, (int) io.submit(() -> repository.markDoneByCodeSync("12-3-4567")).get());
            assertEquals(0, (int) io.submit(repository::countPendingSync).get());
            // A delayed dismissal callback after completion must not resurrect the rear card.
            rear.deleteIntent.send();
            awaitRefresh();
            // The broadcast is dispatched on the main looper and may supersede awaitRefresh.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (notification(Notifier.REAR_ID) != null && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10);
            }
            assertNull(notification(Notifier.REAR_ID));
        } finally { io.shutdownNow(); }
    }

    private void awaitRefresh() throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();
        Notifier.refreshExisting(context, () -> done.complete(null));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!done.isDone() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10);
        }
        done.get(1, TimeUnit.SECONDS);
    }

    private void snapshot(PickupRecord... records) {
        Notifier.postSnapshot(context, new PickupRepository.PendingSnapshot(Arrays.asList(records), records.length, records.length, false), true);
    }
    private Notification notification(int id) {
        for (android.service.notification.StatusBarNotification value : context.getSystemService(NotificationManager.class).getActiveNotifications())
            if (value.getId() == id) return value.getNotification();
        return null;
    }
    private PickupRecord record(long id, String code) {
        PickupRecord value = new PickupRecord(); value.id = id; value.code = code;
        value.place = "示例驿站"; return value;
    }
}
