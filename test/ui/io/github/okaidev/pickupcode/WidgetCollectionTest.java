package io.github.okaidev.pickupcode;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.TextView;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Real collection scrolling, compact/single-item geometry, and local factory refreshes. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "mdpi")
public class WidgetCollectionTest {
    private Context context;

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("pickup_widget", 0).edit().clear().commit();
        NotesBackend.saveSelection(context, Collections.singleton(NotesBackend.TARGET_LOCAL));
    }

    private PickupRecord record(int id) {
        PickupRecord record = new PickupRecord();
        record.id = id;
        record.code = "12-3-" + (4500 + id);
        record.place = "示例路驿站 " + id;
        record.source = "菜鸟";
        return record;
    }

    private View card(int count, int height) {
        List<PickupRecord> records = new ArrayList<>();
        for (int i = 1; i <= count; i++) records.add(record(i));
        View card = PickupWidgetProvider.build(context,
                new PickupRepository.PendingSnapshot(records, count, count, false), 300, height, true)
                .apply(context, new android.appwidget.AppWidgetHostView(context));
        layout(card, height);
        return card;
    }

    private void layout(View view, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 300, height);
    }

    @Test public void smallWidgetKeepsAllTwentyRowsAndCanScrollToLastParcel() {
        View card = card(20, 110);
        ListView list = card.findViewById(R.id.widget_list);
        assertEquals(20, list.getAdapter().getCount());
        assertEquals(0, list.getFirstVisiblePosition());
        assertEquals(0, list.getChildAt(0).getTop());
        list.setSelection(19);
        layout(card, 110);
        assertEquals(19, list.getLastVisiblePosition());
        TextView code = list.getChildAt(list.getChildCount() - 1).findViewById(R.id.widget_code_1);
        assertEquals(record(20).code, code.getText().toString());
    }

    @Test public void singleParcelStaysUnderHeaderWithoutStretchingOrCentering() {
        View card = card(1, 320);
        ListView list = card.findViewById(R.id.widget_list);
        View row = list.getChildAt(0);
        assertFalse(list.isStackFromBottom());
        assertEquals(0, row.getTop());
        assertEquals(WidgetPrefs.ROW_DP, row.getHeight());
        assertTrue(list.getTop() + row.getBottom() < 120);
    }

    @Test public void defaultSizedWidgetShowsTwoCompleteRows() {
        View card = card(3, 180);
        ListView list = card.findViewById(R.id.widget_list);
        assertTrue(list.getChildCount() >= 2);
        assertTrue(list.getChildAt(1).getBottom() <= list.getHeight());
        assertEquals(3, list.getAdapter().getCount());
    }

    @Test public void hideCodesRemovesCollectionDataAndLocation() {
        WidgetPrefs.setShowCode(context, false);
        View card = card(3, 180);
        ListView list = card.findViewById(R.id.widget_list);
        assertEquals(View.GONE, list.getVisibility());
        assertEquals(0, list.getAdapter().getCount());
        assertEquals("3 个待取", ((TextView) card.findViewById(R.id.widget_empty)).getText().toString());
    }

    @Test public void collectionRowKeepsAccessibleActionAndPreviewDoesNotSendIt() {
        View row = PickupWidgetProvider.collectionRow(context, record(1), 300, 110, true)
                .apply(context, new FrameLayout(context));
        layout(row, WidgetPrefs.ROW_DP);
        View done = row.findViewById(R.id.widget_done_1);
        assertEquals(44, done.getWidth());
        assertEquals(44, done.getHeight());
        assertFalse(done.hasOnClickListeners());
        assertEquals("标记已取件", done.getContentDescription());
        assertEquals("菜鸟 · 示例路驿站 1", ((TextView) row.findViewById(R.id.widget_place_1)).getText().toString());
    }

    @Test public void mutableCollectionTemplateOnlyTargetsOwnNonExportedReceiver() {
        android.app.PendingIntent template = PickupWidgetProvider.collectionActions(context, 7);
        assertFalse(template.isImmutable());
        android.content.Intent intent = shadowOf(template).getSavedIntent();
        assertEquals(PickupWidgetProvider.WidgetActionReceiver.class.getName(), intent.getComponent().getClassName());
        assertEquals(context.getPackageName(), intent.getComponent().getPackageName());
        assertTrue(shadowOf(template).isBroadcast());
    }

    @Test public void collectionButtonRoutesTheClickedRecordThroughTheTemplate() {
        android.widget.RemoteViews views = new android.widget.RemoteViews(context.getPackageName(),
                R.layout.widget_pickup_list);
        PickupRecord record = record(77);
        views.setRemoteAdapter(R.id.widget_list, new android.widget.RemoteViews.RemoteCollectionItems.Builder()
                .setHasStableIds(true).setViewTypeCount(1)
                .addItem(record.id, PickupWidgetProvider.collectionRow(context, record, 300, 180, false)).build());
        views.setPendingIntentTemplate(R.id.widget_list, PickupWidgetProvider.collectionActions(context, 7));
        View card = views.apply(context, new android.appwidget.AppWidgetHostView(context));
        layout(card, 180);
        ListView list = card.findViewById(R.id.widget_list);
        assertTrue(list.getChildAt(0).findViewById(R.id.widget_done_1).performClick());
        android.content.Intent received = shadowOf((android.app.Application) context).getBroadcastIntents()
                .stream().filter(intent -> PickupWidgetProvider.ACTION_DONE.equals(intent.getAction()))
                .findFirst().orElseThrow(() -> new AssertionError("No completion broadcast"));
        assertEquals(record.id, received.getLongExtra(PickupWidgetProvider.EXTRA_ID, -1));
        assertEquals(PickupWidgetProvider.WidgetActionReceiver.class.getName(), received.getComponent().getClassName());
        assertNull(shadowOf((android.app.Application) context).getNextStartedActivity());
    }

    @Test public void factoryRefreshesAllLocalRowsAndCompletionWithoutLaunchingActivity() throws Exception {
        PickupRepository repository = PickupRepository.get(context);
        WidgetCollectionService.Factory factory = new WidgetCollectionService.Factory(context, 300, 110);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            worker.submit(() -> {
                for (int i = 1; i <= 15; i++) {
                    PickupRecord record = record(i);
                    assertTrue(repository.insertImported("菜鸟", record.code, record.place, i, false, "widget-test:" + i));
                }
                factory.onDataSetChanged();
            }).get();
            assertEquals(15, factory.getCount());
            long firstId = factory.getItemId(0);
            assertTrue(firstId > 0);
            assertNotNull(factory.getViewAt(14));
            assertNull(factory.getViewAt(15));
            worker.submit(() -> {
                assertEquals(1, repository.markDoneSync(firstId));
                factory.onDataSetChanged();
            }).get();
            assertEquals(14, factory.getCount());
            assertNotEquals(firstId, factory.getItemId(0));
            WidgetPrefs.setShowCode(context, false);
            worker.submit(factory::onDataSetChanged).get();
            assertEquals(0, factory.getCount());
            assertNull(factory.getViewAt(0));
            WidgetPrefs.setShowCode(context, true);
            WidgetPrefs.setShowMode(context, WidgetPrefs.SHOW_ALL);
            worker.submit(factory::onDataSetChanged).get();
            assertEquals(15, factory.getCount());
        } finally { worker.shutdownNow(); }
    }
}
