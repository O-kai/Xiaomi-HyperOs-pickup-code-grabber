package io.github.okaidev.pickupcode;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.android.controller.ActivityController;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Real Android View dispatch in Robolectric, never changes device settings or local records. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, qualifiers = "mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class SwitchInteractionTest {
    private ActivityController<Activity> controller;
    private Activity activity;
    private UiKit.Switch sw;
    private int changes;

    @Before public void setUp() {
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        sw = new UiKit.Switch(activity, UiKit.MIST, false);
        sw.setOnToggle(on -> changes++);
        LinearLayout root = new LinearLayout(activity);
        root.addView(sw, new LinearLayout.LayoutParams(52, 31));
        activity.setContentView(root);
        root.measure(exact(320), exact(200));
        root.layout(0, 0, 320, 200);
    }

    @org.junit.After public void tearDown() {
        controller.pause().stop().destroy();
    }

    private static int exact(int size) { return View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY); }
    private static int atMost(int size) { return View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.AT_MOST); }
    private static void event(View v, int action, float x, float y) {
        long now = android.os.SystemClock.uptimeMillis();
        MotionEvent e = MotionEvent.obtain(now, now, action, x, y, 0);
        try { v.dispatchTouchEvent(e); } finally { e.recycle(); }
    }
    private static void tap(View v, float x, float y) {
        event(v, MotionEvent.ACTION_DOWN, x, y);
        event(v, MotionEvent.ACTION_UP, x, y);
        shadowOf(Looper.getMainLooper()).idle();
    }
    private static AccessibilityNodeInfo node(UiKit.Switch v) {
        AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
        v.onInitializeAccessibilityNodeInfo(info);
        return info;
    }

    @Test public void performClickTogglesExactlyOnceInBothDirections() {
        assertTrue(sw.performClick()); assertTrue(sw.isChecked()); assertEquals(1, changes);
        assertTrue(sw.performClick()); assertFalse(sw.isChecked()); assertEquals(2, changes);
    }

    @Test public void touchTapTogglesExactlyOnce() {
        tap(sw, 20, 15);
        assertTrue(sw.isChecked()); assertEquals(1, changes);
    }

    @Test public void tinyFingerMovementStillCountsAsTap() {
        event(sw, MotionEvent.ACTION_DOWN, 20, 15);
        event(sw, MotionEvent.ACTION_MOVE, 21, 16);
        event(sw, MotionEvent.ACTION_UP, 21, 16);
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(sw.isChecked()); assertEquals(1, changes);
    }

    @Test public void movingOutsideTouchSlopCancelsClick() {
        event(sw, MotionEvent.ACTION_DOWN, 20, 15);
        event(sw, MotionEvent.ACTION_MOVE, sw.getWidth() + ViewConfiguration.get(activity).getScaledTouchSlop() + 10, 15);
        event(sw, MotionEvent.ACTION_UP, 20, 15);
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse(sw.isChecked()); assertEquals(0, changes);
    }

    @Test public void cancellationDoesNotToggle() {
        event(sw, MotionEvent.ACTION_DOWN, 20, 15);
        event(sw, MotionEvent.ACTION_CANCEL, 20, 15);
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse(sw.isChecked()); assertEquals(0, changes);
    }

    @Test public void accessibilityClickChangesStateAndPublishesCheckableState() {
        assertTrue(sw.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
        assertTrue(sw.isChecked()); assertEquals(1, changes);
        AccessibilityNodeInfo info = node(sw);
        assertEquals("android.widget.Switch", info.getClassName());
        assertTrue(info.isCheckable()); assertTrue(info.isChecked());
        info.recycle();
    }

    @Test public void keyboardDpadClickUsesTheSameToggleEntryPoint() {
        sw.requestFocus();
        sw.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER));
        sw.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER));
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(sw.isChecked()); assertEquals(1, changes);
    }

    @Test public void disabledSwitchRejectsDirectTouchAndAccessibilityClicks() {
        sw.setEnabled(false);
        assertFalse(sw.performClick());
        assertFalse(sw.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
        tap(sw, 20, 15);
        assertFalse(sw.isChecked()); assertEquals(0, changes);
    }

    @Test public void silentlyRestoringStateDoesNotCallToggleListener() {
        sw.setChecked(true, false); assertEquals(0, changes);
        AccessibilityNodeInfo info = node(sw);
        assertTrue(info.isChecked()); info.recycle();
        sw.setChecked(false, false); assertEquals(0, changes);
    }

    @Test public void listenerMayRejectLastTargetAndRestoreTheSwitch() {
        sw.setChecked(true, false);
        sw.setOnToggle(on -> { changes++; if (!on) sw.setChecked(true, false); });
        sw.performClick();
        assertTrue(sw.isChecked()); assertEquals(1, changes);
        AccessibilityNodeInfo info = node(sw); assertTrue(info.isChecked()); info.recycle();
    }

    @Test public void measureHonoursExactParentSize() {
        sw.measure(exact(46), exact(28));
        assertEquals(46, sw.getMeasuredWidth()); assertEquals(28, sw.getMeasuredHeight());
    }

    @Test public void measureHonoursMaximumParentSize() {
        sw.measure(atMost(30), atMost(20));
        assertEquals(30, sw.getMeasuredWidth()); assertEquals(20, sw.getMeasuredHeight());
    }

    private LinearLayout makeTargetRow() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 12, 0, 12);
        LinearLayout labels = new LinearLayout(activity);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        TextView title = new TextView(activity); title.setText("ColorOS 日历 待办");
        TextView subtitle = new TextView(activity); subtitle.setText("写进日历的待办");
        labels.addView(title); labels.addView(subtitle);
        row.addView(labels, new LinearLayout.LayoutParams(0, 64, 1f));
        ((android.view.ViewGroup) sw.getParent()).removeView(sw);
        row.addView(sw, new LinearLayout.LayoutParams(52, 31));
        UiKit.bindSwitchRow(row, sw, "ColorOS 日历 待办，写进日历的待办");
        activity.setContentView(row);
        row.measure(exact(320), exact(88)); row.layout(0, 0, 320, 88);
        return row;
    }

    @Test public void tappingRowLabelTogglesOnce() {
        LinearLayout row = makeTargetRow(); tap(row, 20, 20);
        assertTrue(sw.isChecked()); assertEquals(1, changes);
    }

    @Test public void tappingChildSwitchDoesNotAlsoTriggerParentRow() {
        LinearLayout row = makeTargetRow();
        tap(row, sw.getLeft() + 20, sw.getTop() + 15);
        assertTrue(sw.isChecked()); assertEquals(1, changes);
    }

    @Test public void rowCannotBypassDisabledSwitchOrCauseDeletionWhileConfirming() {
        LinearLayout row = makeTargetRow(); sw.setEnabled(false);
        row.performClick(); tap(row, 20, 20);
        assertFalse(sw.isChecked()); assertEquals(0, changes);
    }

    @Test public void rowHasOneLabelledCheckableAccessibilityTarget() {
        LinearLayout row = makeTargetRow();
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, row.getImportantForAccessibility());
        assertFalse(row.isFocusable());
        assertEquals("ColorOS 日历 待办，写进日历的待办", sw.getContentDescription());
        assertTrue(sw.isFocusable());
    }

    @Test public void targetToggleListenerPersistsAndReopensTheSelection() {
        SharedPreferences prefs = activity.getSharedPreferences("dedup", Context.MODE_PRIVATE);
        Set<String> initial = new LinkedHashSet<>();
        initial.add(NotesBackend.TARGET_LOCAL); initial.add(NotesBackend.BACKEND_COLOROS_TODO);
        prefs.edit().putStringSet("backend_targets", initial).putBoolean("local_target_initialized", true).commit();
        sw.setChecked(true, false);
        sw.setOnToggle(on -> {
            Set<String> selected = NotesBackend.selection(activity);
            if (on) selected.add(NotesBackend.BACKEND_COLOROS_TODO);
            else selected.remove(NotesBackend.BACKEND_COLOROS_TODO);
            NotesBackend.saveSelection(activity, selected);
        });
        sw.performClick();
        assertFalse(NotesBackend.selection(activity).contains(NotesBackend.BACKEND_COLOROS_TODO));
        assertTrue(NotesBackend.selection(activity).contains(NotesBackend.TARGET_LOCAL));
        UiKit.Switch reopened = new UiKit.Switch(activity, UiKit.MIST,
                NotesBackend.selection(activity).contains(NotesBackend.BACKEND_COLOROS_TODO));
        assertFalse(reopened.isChecked());
        sw.performClick();
        assertTrue(NotesBackend.selection(activity).contains(NotesBackend.BACKEND_COLOROS_TODO));
    }
}
