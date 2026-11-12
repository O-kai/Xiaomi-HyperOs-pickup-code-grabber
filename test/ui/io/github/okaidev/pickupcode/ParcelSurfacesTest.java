package io.github.okaidev.pickupcode;

import android.app.Notification;
import android.content.Context;
import android.provider.Settings;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Real RemoteViews inflation and notification payload checks, using synthetic parcels only. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "mdpi")
public class ParcelSurfacesTest {
    private Context context;

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("pickup_widget", 0).edit().clear().commit();
        Settings.System.putInt(context.getContentResolver(), "notification_focus_protocol", 3);
        WidgetPrefs.setShowMode(context, WidgetPrefs.SHOW_ALL);
    }

    private Notification notification(String code) {
        return Notifier.build(context, Arrays.asList(code, "58-2-9041"), "示例驿站", 2, true);
    }

    private View widget() {
        FrameLayout host = new FrameLayout(context);
        View view = PickupWidgetProvider.buildForDiag(context, null).apply(context, host);
        view.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(244, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 300, 244);
        return view;
    }

    @Test public void realRemoteViewsInflatesWithCodeAbovePlaceAndAccessibleDoneTarget() {
        View view = widget();
        TextView code = view.findViewById(R.id.widget_code_1);
        TextView place = view.findViewById(R.id.widget_place_1);
        View done = view.findViewById(R.id.widget_done_1);
        assertEquals("12-3-4567", code.getText().toString());
        assertTrue(place.getTop() >= code.getBottom());
        assertTrue(done.getWidth() >= 44 && done.getHeight() >= 44);
        assertEquals("标记已取件", done.getContentDescription());
        assertTrue(view.findViewById(R.id.widget_row_2).getBottom() <= 244);
    }

    @Test public void privacyModeHidesWholeRowsIncludingLocationAndDoneButton() {
        WidgetPrefs.setShowCode(context, false);
        View view = widget();
        assertEquals(View.GONE, view.findViewById(R.id.widget_row_1).getVisibility());
        assertEquals("2 个待取 · 1 个已取",
                ((TextView) view.findViewById(R.id.widget_empty)).getText().toString());
    }

    @Test public void pickedUpRowHasNoCompletionActionAndIsVisuallyMuted() {
        View view = widget();
        assertEquals(View.INVISIBLE, view.findViewById(R.id.widget_done_3).getVisibility());
        assertFalse(view.findViewById(R.id.widget_code_3).isEnabled());
        assertTrue(((TextView) view.findViewById(R.id.widget_code_3)).getText()
                instanceof android.text.Spanned);
    }

    @Test public void fontPreferenceChangesActualCodeSize() {
        WidgetPrefs.setFontLevel(context, WidgetPrefs.FONT_SMALL);
        float small = ((TextView) widget().findViewById(R.id.widget_code_1)).getTextSize();
        WidgetPrefs.setFontLevel(context, WidgetPrefs.FONT_LARGE);
        float large = ((TextView) widget().findViewById(R.id.widget_code_1)).getTextSize();
        assertTrue(large > small);
    }

    @Test public void unsupportedSystemKeepsOrdinaryNotificationWithoutXiaomiExtras() {
        Settings.System.putInt(context.getContentResolver(), "notification_focus_protocol", 0);
        Notification notification = notification("12-3-4567");
        assertFalse(notification.extras.containsKey(HyperIsland.PARAM));
        assertNotNull(notification.contentIntent);
        assertEquals(2, notification.actions.length);
    }

    @Test public void islandPayloadReferencesOnlyBundledIconsAndOwnPendingIntents() throws Exception {
        Notification notification = notification("12-3-4567");
        JSONObject params = new JSONObject(notification.extras.getString(HyperIsland.PARAM))
                .getJSONObject("param_v2");
        String picture = params.getJSONObject("param_island").getJSONObject("smallIslandArea")
                .getJSONObject("picInfo").getString("pic");
        assertNotNull(notification.extras.getBundle("miui.focus.pics").getParcelable(picture));
        for (int i = 0; i < params.getJSONArray("actions").length(); i++) {
            String key = params.getJSONArray("actions").getJSONObject(i).getString("action");
            Notification.Action action = notification.extras.getBundle("miui.focus.actions").getParcelable(key);
            assertNotNull(action);
            assertEquals(context.getPackageName(), action.actionIntent.getCreatorPackage());
        }
        assertFalse(params.getBoolean("filterWhenNoPermission"));
    }

    @Test public void newerSystemProtocolsKeepTheStandardTemplateAndNotificationActions() throws Exception {
        // HyperOS marketing versions and notification protocol versions are independent.
        for (int protocol : new int[]{2, 3, 4, 5}) {
            Settings.System.putInt(context.getContentResolver(), "notification_focus_protocol", protocol);
            Notification notification = notification("12-3-4567");
            JSONObject params = new JSONObject(notification.extras.getString(HyperIsland.PARAM))
                    .getJSONObject("param_v2");
            assertEquals("express", params.getString("business"));
            assertEquals("12-3-4567", params.getJSONObject("baseInfo").getString("title"));
            assertNotNull(notification.contentIntent);
            assertEquals(2, notification.actions.length);
        }
    }

    @Test public void lockscreenAndAodContainOnlyCount() throws Exception {
        Notification notification = notification("12-3-4567");
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility);
        assertNotNull(notification.publicVersion);
        assertFalse(notification.publicVersion.extras.toString().contains("12-3-4567"));
        assertFalse(notification.publicVersion.extras.containsKey(HyperIsland.PARAM));
        JSONObject params = new JSONObject(notification.extras.getString(HyperIsland.PARAM))
                .getJSONObject("param_v2");
        assertEquals("2 件待取", params.getString("aodTitle"));
    }

    @Test public void rearScreenGetsDedicatedCardAndCodeFreeAodOnlyWhenHostExists() throws Exception {
        assertFalse(notification("12-3-4567").extras.containsKey(RearScreen.VIEW));
        android.content.pm.PackageInfo host = new android.content.pm.PackageInfo();
        host.packageName = "com.xiaomi.subscreencenter";
        host.applicationInfo = new android.content.pm.ApplicationInfo();
        host.applicationInfo.packageName = host.packageName;
        shadowOf(context.getPackageManager()).installPackage(host);
        Notification notification = notification("12-3-4567");
        JSONObject rear = new JSONObject(notification.extras.getString(RearScreen.PARAM))
                .getJSONObject("rear_param_v1");
        assertEquals("express", rear.getString("business"));
        assertTrue(rear.getBoolean("enableFloat"));
        android.widget.RemoteViews card = notification.extras.getParcelable(RearScreen.VIEW);
        View visible = card.apply(context, new FrameLayout(context));
        assertEquals("12-3-4567", ((TextView) visible.findViewById(R.id.rear_code)).getText().toString());
        assertTrue(visible.findViewById(R.id.rear_done).hasOnClickListeners());
        android.widget.RemoteViews aod = notification.extras.getParcelable(RearScreen.AOD);
        View sleeping = aod.apply(context, new FrameLayout(context));
        assertEquals("2 件待取", ((TextView) sleeping.findViewById(R.id.rear_count)).getText().toString());
        assertNull(sleeping.findViewById(R.id.rear_code));
        assertNull(sleeping.findViewById(R.id.rear_place));
        assertNull(sleeping.findViewById(R.id.rear_done));
        assertFalse(notification.publicVersion.extras.containsKey(RearScreen.VIEW));
        assertFalse(notification.publicVersion.extras.containsKey(RearScreen.AOD));
    }

    @Test public void rearCardFitsLongCodesAndKeepsUpdatesSilent() throws Exception {
        Notification base = notification("12-3-4567");
        android.os.Bundle extras = RearScreen.extras(context, "AB12-CD34-EF56-GH7890", "示例驿站", 2,
                base.actions[1], base.actions[0], false);
        assertFalse(new JSONObject(extras.getString(RearScreen.PARAM))
                .getJSONObject("rear_param_v1").getBoolean("enableFloat"));
        assertTrue(new JSONObject(extras.getString(RearScreen.PARAM))
                .getJSONObject("rear_param_v1").getBoolean("disable_popup"));
        android.widget.RemoteViews remote = extras.getParcelable(RearScreen.VIEW);
        View card = remote.apply(context, new FrameLayout(context));
        card.measure(View.MeasureSpec.makeMeasureSpec(224, 1073741824),
                View.MeasureSpec.makeMeasureSpec(196, 1073741824));
        card.layout(0, 0, 224, 196);
        TextView code = card.findViewById(R.id.rear_code);
        assertTrue(code.getPaint().measureText(code.getText().toString()) <= code.getWidth() + 1);
        assertTrue(card.findViewById(R.id.rear_done).getBottom() <= 196);
    }

    @Test @Config(sdk = 34) public void rearCardAndAodAvoid17ProMaxCameraCutout() throws Exception {
        org.robolectric.shadows.ShadowBuild.setDevice("popsicle");
        Notification base = notification("12-3-4567");
        android.os.Bundle extras = RearScreen.extras(context, "AB12-CD34-EF56-GH7890", "示例驿站", 2,
                base.actions[1], base.actions[0], true);
        for (String key : Arrays.asList(RearScreen.VIEW, RearScreen.AOD)) {
            android.widget.RemoteViews remote = extras.getParcelable(key);
            View card = remote.apply(context, new FrameLayout(context));
            card.measure(View.MeasureSpec.makeMeasureSpec(347, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(212, View.MeasureSpec.EXACTLY));
            card.layout(0, 0, 347, 212);
            View content = card.findViewById(R.id.rear_content);
            assertTrue(content.getLeft() >= 106);
            assertTrue(content.getRight() <= 347);
            TextView code = card.findViewById(R.id.rear_code);
            if (code != null) {
                assertTrue(code.getPaint().measureText(code.getText().toString()) <= code.getWidth() + 1);
                assertTrue(card.findViewById(R.id.rear_done).getBottom() <= 212);
            }
        }
    }

    @Test public void updatingNotificationDoesNotRetargetAnOldCodePendingIntent() {
        Notification old = notification("12-3-4567");
        notification("99-1-2345");
        assertEquals("12-3-4567", shadowOf(old.contentIntent).getSavedIntent().getStringExtra("copy"));
        assertFalse(shadowOf(old.contentIntent).getSavedIntent().getComponent().getClassName()
                .endsWith("LauncherActivity"));
    }

    @Test public void silentStateUpdateDoesNotExpandIslandAgain() throws Exception {
        Notification notification = Notifier.build(context, Arrays.asList("12-3-4567"), "示例驿站", 1, false);
        JSONObject params = new JSONObject(notification.extras.getString(HyperIsland.PARAM)).getJSONObject("param_v2");
        assertFalse(params.getBoolean("enableFloat"));
        assertTrue((notification.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0);
    }

    @Test public void smallestSupportedCardShowsOneCompleteRowWithLargeFontPreference() {
        WidgetPrefs.setFontLevel(context, WidgetPrefs.FONT_LARGE);
        View view = PickupWidgetProvider.build(context, snapshot("12-3-4567"), 250, 110, true)
                .apply(context, new FrameLayout(context));
        view.measure(View.MeasureSpec.makeMeasureSpec(250, 1073741824),
                View.MeasureSpec.makeMeasureSpec(110, 1073741824));
        view.layout(0, 0, 250, 110);
        View row = view.findViewById(R.id.widget_row_1);
        View list = view.findViewById(R.id.widget_list);
        assertTrue(list.getTop() + row.getBottom() <= 110 - WidgetPrefs.PAD_DP / 2);
        assertEquals(View.GONE, view.findViewById(R.id.widget_row_2).getVisibility());
    }

    @Test public void longCodeFitsTheNarrowCardWithoutEllipsis() {
        View view = PickupWidgetProvider.build(context, snapshot("AB12-CD34-EF56-GH7890"), 200, 180, true)
                .apply(context, new FrameLayout(context));
        view.measure(View.MeasureSpec.makeMeasureSpec(200, 1073741824),
                View.MeasureSpec.makeMeasureSpec(180, 1073741824));
        view.layout(0, 0, 200, 180);
        TextView code = view.findViewById(R.id.widget_code_1);
        assertTrue(code.getPaint().measureText(code.getText().toString()) <= code.getWidth() + 1);
        assertEquals("AB12-CD34-EF56-GH7890", code.getText().toString());
    }

    @Test public void samplePreviewHasNoActionsThatCouldAffectRealRecords() {
        View view = widget();
        assertFalse(view.findViewById(R.id.widget_done_1).hasOnClickListeners());
        assertFalse(view.findViewById(R.id.widget_code_1).hasOnClickListeners());
    }

    @Test public void cachedRemoteViewsResolvesColorsAgainWhenHostChangesToDarkMode() {
        android.widget.RemoteViews cached = PickupWidgetProvider.buildForDiag(context, null);
        View light = cached.apply(context, new FrameLayout(context));
        android.content.res.Configuration config = new android.content.res.Configuration(context.getResources().getConfiguration());
        config.uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES;
        Context dark = context.createConfigurationContext(config);
        View night = cached.apply(dark, new FrameLayout(dark));
        assertNotEquals(((TextView) light.findViewById(R.id.widget_code_1)).getCurrentTextColor(),
                ((TextView) night.findViewById(R.id.widget_code_1)).getCurrentTextColor());
    }

    private PickupRepository.PendingSnapshot snapshot(String code) {
        PickupRecord record = new PickupRecord();
        record.id = 1; record.code = code; record.place = "示例驿站";
        record.status = PickupRecord.STATUS_PENDING;
        return new PickupRepository.PendingSnapshot(Arrays.asList(record), 1, 1, false);
    }

    @Test public void dismissWhileDatabaseReadIsPendingDoesNotResurrectReminder() {
        android.app.NotificationManager manager = context.getSystemService(android.app.NotificationManager.class);
        manager.notify(Notifier.PICKUP_ID, notification("12-3-4567"));
        manager.cancel(Notifier.PICKUP_ID);
        Notifier.postSnapshot(context, snapshot("12-3-4567"), false);
        assertEquals(0, manager.getActiveNotifications().length);
    }

    @Test public void completingLastParcelClearsExistingReminder() {
        android.app.NotificationManager manager = context.getSystemService(android.app.NotificationManager.class);
        manager.notify(Notifier.PICKUP_ID, notification("12-3-4567"));
        Notifier.postSnapshot(context, new PickupRepository.PendingSnapshot(
                java.util.Collections.emptyList(), 0, 0, false), false);
        assertEquals(0, manager.getActiveNotifications().length);
    }

    @Test public void completingOneParcelReplacesCodeWithoutAlertingAgain() {
        android.app.NotificationManager manager = context.getSystemService(android.app.NotificationManager.class);
        manager.notify(Notifier.PICKUP_ID, notification("12-3-4567"));
        Notifier.postSnapshot(context, snapshot("58-2-9041"), false);
        Notification updated = manager.getActiveNotifications()[0].getNotification();
        assertEquals("58-2-9041", updated.extras.getString(Notification.EXTRA_TITLE));
        assertTrue((updated.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0);
    }
}
