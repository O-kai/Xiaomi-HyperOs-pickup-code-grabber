package io.github.okaidev.pickupcode;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowBinder;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Real parser -> authorized Provider -> Room -> notification/RemoteViews, synthetic data only. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class AppParcelFlowTest {
    private static final int PDD_UID = 10456;

    @Test public void onlyCodedPendingParcelsSurviveReloadAndCompletionAcrossSurfaces() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        PackageInfo pkg = new PackageInfo();
        pkg.packageName = AppParcelData.PINDUODUO;
        pkg.applicationInfo = new ApplicationInfo();
        pkg.applicationInfo.packageName = pkg.packageName;
        pkg.applicationInfo.uid = PDD_UID;
        shadowOf(context.getPackageManager()).installPackage(pkg);
        NotesBackend.saveSelection(context, Collections.singleton(NotesBackend.TARGET_LOCAL));
        context.getSharedPreferences("dedup", 0).edit()
                .putBoolean(TodoProvider.APP_PARCEL_PREF, true).commit();
        Settings.System.putInt(context.getContentResolver(), "notification_focus_protocol", 4);
        WidgetPrefs.setShowMode(context, WidgetPrefs.SHOW_PENDING);
        TodoProvider provider = Robolectric.buildContentProvider(TodoProvider.class).create().get();
        ExecutorService binder = Executors.newSingleThreadExecutor();
        try {
            JSONObject response = new JSONObject().put("success", true).put("result", new JSONObject()
                    .put("track_info_list", new JSONArray()
                            .put(row(400, "取件码 12-3-4567"))
                            .put(row(400, "取件码：58-2-9041"))
                            .put(row(400, "取件手机号 1234"))
                            .put(row(400, "176****1234"))
                            .put(row(400, JSONObject.NULL))
                            .put(row(400, ""))
                            .put(row(400, 1234))
                            .put(row(200, "取件码 11-2-3456"))
                            .put(row(500, "取件码 77-8-9012"))));
            List<AppParcelData.Event> events = AppParcelData.pinduoduoList(response.toString());
            assertEquals(2, events.size());
            // Simulate three independent App reloads, not the Hook's short in-memory dedup.
            for (int reload = 0; reload < 3; reload++) {
                for (AppParcelData.Event event : events) {
                    Bundle result = binder.submit(() -> {
                        ShadowBinder.setCallingUid(PDD_UID);
                        Bundle data = new Bundle();
                        data.putString("code", event.code);
                        data.putString("place", event.place);
                        return provider.call("onAppParcel", AppParcelData.PINDUODUO, data);
                    }).get(5, TimeUnit.SECONDS);
                    assertEquals(result.toString(), 0, result.getInt("rc", -1));
                }
            }
            PickupRepository repository = PickupRepository.get(context);
            PickupRepository.PendingSnapshot snapshot = settle(repository);
            assertEquals(2, snapshot.pendingTotal);
            assertEquals(2, (int) binder.submit(repository::countAllSync).get(5, TimeUnit.SECONDS));
            for (PickupRecord record : snapshot.items) {
                assertTrue(record.code.equals("12-3-4567") || record.code.equals("58-2-9041"));
                assertEquals("拼多多", record.source);
                assertEquals("—", record.place);
            }
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            assertEquals(1, manager.getActiveNotifications().length);
            Notification notification = manager.getActiveNotifications()[0].getNotification();
            JSONObject focus = new JSONObject(notification.extras.getString(HyperIsland.PARAM))
                    .getJSONObject("param_v2");
            assertEquals("2 件待取", focus.getString("aodTitle"));
            View card = PickupWidgetProvider.build(context, snapshot, 320, 244, true)
                    .apply(context, new FrameLayout(context));
            assertEquals(snapshot.items.get(0).code,
                    ((TextView) card.findViewById(R.id.widget_code_1)).getText().toString());

            String completedCode = notification.extras.getString(Notification.EXTRA_TITLE);
            String remainingCode = "12-3-4567".equals(completedCode) ? "58-2-9041" : "12-3-4567";
            assertTrue(shadowOf(notification.actions[0].actionIntent).isBroadcast());
            notification.actions[0].actionIntent.send();
            awaitPending(repository, 1);
            assertNull(shadowOf((android.app.Application) context).getNextStartedActivity());
            Notification remaining = manager.getActiveNotifications()[0].getNotification();
            assertEquals(remainingCode, remaining.extras.getString(Notification.EXTRA_TITLE));
            assertTrue((remaining.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0);

            // An App reload with the same code must not reopen a completed local record.
            Bundle reload = binder.submit(() -> {
                ShadowBinder.setCallingUid(PDD_UID);
                Bundle data = new Bundle();
                data.putString("code", completedCode);
                data.putString("place", "—");
                return provider.call("onAppParcel", AppParcelData.PINDUODUO, data);
            }).get(5, TimeUnit.SECONDS);
            assertEquals(0, reload.getInt("rc", -1));
            assertEquals("completed_replay", reload.getString("status"));
            assertEquals(1, settle(repository).pendingTotal);
            assertEquals(2, (int) binder.submit(repository::countAllSync).get(5, TimeUnit.SECONDS));

            remaining.actions[0].actionIntent.send();
            awaitPending(repository, 0);
            assertEquals(0, manager.getActiveNotifications().length);
        } finally {
            binder.shutdownNow();
        }
    }

    private static void awaitPending(PickupRepository repository, int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (settle(repository).pendingTotal == expected) return;
            Thread.sleep(10);
        }
        assertEquals(expected, settle(repository).pendingTotal);
    }

    private static JSONObject row(int status, Object code) throws Exception {
        return new JSONObject().put("status_type", status).put("pick_code", code)
                .put("phone", "1234").put("tracking_number", "654321")
                .put("description", "测试物流描述，取件码 6-7-8901");
    }

    private static PickupRepository.PendingSnapshot settle(PickupRepository repository) throws Exception {
        CompletableFuture<PickupRepository.PendingSnapshot> result = new CompletableFuture<>();
        repository.pendingSnapshotAsync(12, result::complete);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!result.isDone() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle();
            if (!result.isDone()) Thread.sleep(10);
        }
        return result.get(1, TimeUnit.SECONDS);
    }
}
