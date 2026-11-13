package io.github.okaidev.pickupcode;

import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import java.util.Collections;
import java.util.List;

/** A local, scrollable list of parcels; data changes trigger reloads, never network polling. */
public final class WidgetCollectionService extends RemoteViewsService {
    static final String WIDTH = "widget_width_dp";
    static final String HEIGHT = "widget_height_dp";

    @Override public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new Factory(getApplicationContext(), intent.getIntExtra(WIDTH, 300),
                intent.getIntExtra(HEIGHT, 180));
    }

    static final class Factory implements RemoteViewsFactory {
        private final Context context;
        private final int widthDp;
        private final int heightDp;
        private volatile List<PickupRecord> items = Collections.emptyList();

        Factory(Context context, int widthDp, int heightDp) {
            this.context = context;
            this.widthDp = widthDp;
            this.heightDp = heightDp;
        }

        @Override public void onCreate() { }

        @Override public void onDataSetChanged() {
            // Runs on the host's binder worker. Use our own identity for local storage.
            long identity = Binder.clearCallingIdentity();
            try {
                if (!NotesBackend.localSelected(context) || !WidgetPrefs.showCode(context)) {
                    items = Collections.emptyList();
                    return;
                }
                PickupRepository repository = PickupRepository.get(context);
                items = WidgetPrefs.showAll(context) ? repository.allForWidgetSync(Integer.MAX_VALUE)
                        : repository.pendingSync(Integer.MAX_VALUE);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override public void onDestroy() { items = Collections.emptyList(); }
        @Override public int getCount() { return items.size(); }
        @Override public int getViewTypeCount() { return 1; }
        @Override public boolean hasStableIds() { return true; }
        @Override public RemoteViews getLoadingView() { return null; }

        @Override public long getItemId(int position) {
            List<PickupRecord> current = items;
            return position >= 0 && position < current.size() ? current.get(position).id : -1;
        }

        @Override public RemoteViews getViewAt(int position) {
            List<PickupRecord> current = items;
            if (position < 0 || position >= current.size() || !WidgetPrefs.showCode(context)) return null;
            return PickupWidgetProvider.collectionRow(context, current.get(position), widthDp, heightDp, false);
        }
    }
}
