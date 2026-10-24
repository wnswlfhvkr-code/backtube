package org.schabi.newpipe.info_list.holder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.FrameLayout;
import androidx.preference.PreferenceManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.R;
import org.schabi.newpipe.MainActivity;
import org.schabi.newpipe.ktx.ViewUtils;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import org.schabi.newpipe.database.stream.model.StreamStateEntity;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.info_list.InfoItemBuilder;
import org.schabi.newpipe.info_list.InfoListAdapter;
import org.schabi.newpipe.local.history.HistoryRecordManager;
import org.schabi.newpipe.views.AnimatedProgressBar;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.subjects.SingleSubject;

/** Real Android Views and Rx sources; no remote media or production history reads. */
@RunWith(AndroidJUnit4.class)
public class StreamProgressBindingTest {
    private Context context;
    private MainActivity activity;
    private FrameLayout parent;
    private StreamInfoItem recyclerItem;
    private StreamMiniInfoItemHolder holder;
    private InfoListAdapter adapter;
    private DelayedHistory history;
    private SharedPreferences prefs;
    private final List<Boolean> original = new ArrayList<>();
    private final int[] keys = {R.string.enable_watch_history_key,
            R.string.enable_playback_state_lists_key};

    @Before public void setUp() throws Exception {
        context = new ContextThemeWrapper(InstrumentationRegistry.getInstrumentation()
                .getTargetContext(), R.style.LightTheme);
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        for (final int k : keys) {
            final String key = context.getString(k);
            original.add(prefs.contains(key) ? prefs.getBoolean(key, false) : null);
            prefs.edit().putBoolean(key, true).commit();
        }
        activity = (MainActivity) InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        main(() -> {
            parent = new FrameLayout(activity);
            activity.setContentView(parent);
            history = new DelayedHistory(context);
            adapter = new InfoListAdapter(context);
            holder = new StreamMiniInfoItemHolder(new InfoItemBuilder(context),
                    parent);
            parent.addView(holder.itemView);
            adapter.onViewAttachedToWindow(holder);
        });
    }

    @After public void tearDown() throws Exception {
        history.releaseAll();
        main(() -> {
            adapter.onViewRecycled(holder);
            activity.finish();
        });
        for (int i = 0; i < keys.length; i++) {
            final SharedPreferences.Editor edit = prefs.edit();
            if (original.get(i) == null) {
                edit.remove(context.getString(keys[i]));
            } else {
                edit.putBoolean(context.getString(keys[i]), original.get(i));
            }
            edit.commit();
        }
    }

    @Test public void bindReturnsBeforeDatabaseCompletion() throws Exception {
        final FutureTask<Void> bind = new FutureTask<>(() -> {
            holder.updateFromItem(item("A", 100), history); return null;
        });
        new Handler(Looper.getMainLooper()).post(bind);
        boolean returned = false;
        try {
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (history.requests.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(1, history.requests.size());
            try {
                bind.get(2, TimeUnit.SECONDS);
                returned = true;
            } catch (final java.util.concurrent.TimeoutException expected) {
                // Expected RED on the synchronous baseline.
            }
        } finally {
            history.complete(0, 20000); bind.get(10, TimeUnit.SECONDS);
        }
        assertTrue("bind waited for the DB result on main", returned);
    }

    @Test public void rapidRebindAndSameItemUpdatesAreLatestWins() throws Exception {
        main(() -> holder.updateFromItem(item("A", 100), history));
        main(() -> holder.updateFromItem(item("B", 100), history));
        history.complete(1, 30000); awaitProgress(30);
        history.complete(0, 70000); main(() -> assertEquals(30, bar().getProgress()));
        main(() -> holder.updateState(item("B", 200), history));
        main(() -> holder.updateState(item("B", 300), history));
        assertEquals("same-manager update must refresh history", 4, history.requests.size());
        history.complete(3, 50000);
        awaitProgress(50);
        main(() -> {
            assertEquals(300, bar().getMax());
            assertEquals(50, bar().getProgress());
        });
        history.complete(2, 90000); Thread.sleep(600);
        main(() -> assertEquals(50, bar().getProgress()));
    }

    @Test public void bindAttachDoesNotDuplicateAndDetachedUpdateDoesNotQuery() throws Exception {
        main(() -> {
            holder.updateFromItem(item("A", 100), history);
            adapter.onViewAttachedToWindow(holder);
        });
        assertEquals(1, history.requests.size());
        main(() -> {
            adapter.onViewDetachedFromWindow(holder);
            holder.updateState(item("A", 200), history);
        });
        assertEquals("detached update restarted DB work", 1, history.requests.size());
        history.complete(0, 20000);
        main(() -> assertEquals(View.GONE, bar().getVisibility()));
        main(() -> adapter.onViewAttachedToWindow(holder));
        assertEquals(2, history.requests.size());
        history.complete(1, 40000); awaitProgress(40);
        main(() -> assertEquals(200, bar().getMax()));
        main(() -> {
            adapter.onViewRecycled(holder);
            holder.updateState(item("A", 300), history);
            adapter.onViewAttachedToWindow(holder);
        });
        assertEquals(2, history.requests.size());
        main(() -> {
            adapter.onViewRecycled(holder);
            holder.updateFromItem(item("B", 300), history);
            assertEquals(2, history.requests.size());
            adapter.onViewAttachedToWindow(holder);
        });
        assertEquals(3, history.requests.size());
        history.complete(2, 60000);
        awaitProgress(60);
        main(() -> assertEquals(300, bar().getMax()));
    }

    @Test public void updatePreservesVisibleProgressUntilItsResult() throws Exception {
        main(() -> holder.updateFromItem(item("A", 100), history));
        history.complete(0, 20000); awaitProgress(20);
        main(() -> {
            holder.updateState(item("A", 100), history);
            assertEquals(View.VISIBLE, bar().getVisibility());
            assertEquals(20, bar().getProgress());
        });
        history.complete(1, 30000);
        awaitProgress(30);
    }

    @Test public void emptyErrorAndPreferenceOffClearState() throws Exception {
        main(() -> holder.updateFromItem(item("A", 100), history));
        history.complete(0, 20000);
        awaitProgress(20);
        main(() -> holder.updateState(item("A", 100), history));
        history.requests.get(1).onSuccess(new StreamStateEntity[0]);
        assertCleared();
        main(() -> holder.updateState(item("A", 100), history));
        history.complete(2, 25000);
        awaitProgress(25);
        main(() -> holder.updateState(item("A", 100), history));
        history.requests.get(3).onError(new IllegalStateException("Generated DB failure"));
        assertCleared();
        main(() -> holder.updateState(item("A", 100), history));
        history.complete(4, 30000);
        awaitProgress(30);
        main(() -> holder.updateState(item("A", 100), history));
        prefs.edit().putBoolean(context.getString(keys[1]), false).commit();
        history.complete(5, 80000);
        assertCleared();
    }

    private void assertCleared() throws Exception {
        await(() -> bar().getVisibility() == View.GONE);
        main(() -> {
            assertEquals(0, bar().getProgress());
            assertEquals(1f, bar().getAlpha(), 0f);
        });
    }

    @Test public void recyclingActualRecyclerViewRowsRejectsLateResult() throws Exception {
        final RecyclerView recycler = attachRecycler();
        await(() -> history.requests.size() == 1
                && recycler.findViewHolderForAdapterPosition(0) != null);
        main(() -> {
            holder = (StreamMiniInfoItemHolder) recycler.findViewHolderForAdapterPosition(0);
            recycler.setAdapter(null);
            assertFalse(history.requests.get(0).hasObservers());
        });
        history.complete(0, 80000);
        main(() -> {
            assertEquals(View.GONE, bar().getVisibility());
            recycler.setAdapter(adapter);
        });
        await(() -> history.requests.size() == 2
                && recycler.findViewHolderForAdapterPosition(0) != null);
        main(() -> holder = (StreamMiniInfoItemHolder)
                recycler.findViewHolderForAdapterPosition(0));
        history.complete(1, 30000);
        awaitProgress(30);
        main(() -> recycler.setAdapter(null));
    }

    @Test public void actualWindowDetachDefersExternalUpdateUntilReattach() throws Exception {
        final RecyclerView recycler = attachRecycler();
        await(() -> history.requests.size() == 1
                && recycler.findViewHolderForAdapterPosition(0) != null);
        main(() -> {
            holder = (StreamMiniInfoItemHolder) recycler.findViewHolderForAdapterPosition(0);
            parent.removeView(recycler);
            assertFalse("window detach kept history observer",
                    history.requests.get(0).hasObservers());
            recyclerItem.setDuration(200);
            holder.updateState(recyclerItem, history);
            assertEquals(1, history.requests.size());
        });
        history.complete(0, 80000);
        main(() -> parent.addView(recycler));
        await(() -> history.requests.size() >= 2
                && recycler.findViewHolderForAdapterPosition(0) != null);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        main(() -> holder = (StreamMiniInfoItemHolder)
                recycler.findViewHolderForAdapterPosition(0));
        final int latest = history.requests.size() - 1;
        for (int i = 0; i < latest; i++) {
            assertFalse(history.requests.get(i).hasObservers());
        }
        history.complete(latest, 30000);
        awaitProgress(30);
        main(() -> {
            assertEquals(200, bar().getMax());
            recycler.setAdapter(null);
        });
    }

    private RecyclerView attachRecycler() throws Exception {
        final RecyclerView[] recycler = {null};
        main(() -> {
            recycler[0] = new RecyclerView(activity);
            recycler[0].setLayoutManager(new LinearLayoutManager(activity));
            try {
                final java.lang.reflect.Field f = InfoListAdapter.class
                        .getDeclaredField("recordManager");
                f.setAccessible(true);
                f.set(adapter, history);
            } catch (final ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
            adapter.setUseMiniVariant(false);
            recyclerItem = item("A", 100);
            adapter.addInfoItemList(List.of(recyclerItem));
            parent.removeAllViews();
            parent.addView(recycler[0]);
            recycler[0].setAdapter(adapter);
        });
        return recycler[0];
    }

    @Test public void oldPropertyAndProgressAnimationsCannotHideReboundRow() throws Exception {
        main(() -> holder.updateFromItem(item("A", 100), history));
        history.complete(0, 20000);
        awaitProgress(20);
        main(() -> {
            bar().setProgressAnimated(80);
            ViewUtils.animate(bar(), false, 500);
            holder.updateFromItem(item("B", 100), history);
        });
        history.complete(1, 30000);
        awaitProgress(30);
        Thread.sleep(750);
        main(() -> {
            assertEquals(View.VISIBLE, bar().getVisibility());
            assertEquals(30, bar().getProgress());
            assertEquals(1f, bar().getAlpha(), 0f);
        });
    }

    @Test public void alreadyQueuedOldResultCannotOverwriteNewBinding() throws Exception {
        main(() -> holder.updateFromItem(item("A", 100), history));
        main(() -> {
            // observeOn has queued A's UI callback, but it cannot run before this rebind.
            history.complete(0, 80000);
            holder.updateFromItem(item("B", 200), history);
        });
        history.complete(1, 40000);
        awaitProgress(40);
        main(() -> assertEquals(200, bar().getMax()));
    }

    @Test public void differentServiceUpdateIsIgnoredAndCurrentDurationIsRead() throws Exception {
        final StreamInfoItem current = item("A", 100);
        final StreamInfoItem other = new StreamInfoItem(2, current.getUrl(), "Other service",
                StreamType.VIDEO_STREAM);
        other.setDuration(900);
        main(() -> {
            holder.updateFromItem(current, history);
            holder.updateState(other, history);
            current.setDuration(200);
        });
        assertEquals(1, history.requests.size());
        history.complete(0, 30000);
        awaitProgress(30);
        main(() -> assertEquals(200, bar().getMax()));
    }

    @Test public void zeroDurationAndLiveDisplayRulesRemainUnchanged() throws Exception {
        final StreamInfoItem live = new StreamInfoItem(1, "https://example.invalid/live",
                "Generated live", StreamType.LIVE_STREAM);
        live.setDuration(-1);
        main(() -> {
            holder.updateFromItem(item("zero", 0), history);
            assertEquals(View.GONE, bar().getVisibility());
            holder.updateFromItem(live, history);
            assertEquals(View.VISIBLE, holder.itemDurationView.getVisibility());
            assertEquals(View.GONE, bar().getVisibility());
        });
        assertEquals(0, history.requests.size());
        // Preserve the existing full-bind versus updateState distinction
        // for positive live duration.
        main(() -> {
            live.setDuration(20);
            holder.updateFromItem(live, history);
        });
        history.complete(0, 10000);
        awaitProgress(10);
        main(() -> {
            holder.updateState(live, history);
            assertEquals(View.GONE, bar().getVisibility());
        });
        assertEquals(1, history.requests.size());
        main(() -> {
            adapter.onViewDetachedFromWindow(holder);
            adapter.onViewAttachedToWindow(holder);
            assertEquals("reattach changed the latest live-update display rule",
                    1, history.requests.size());
            assertEquals(View.GONE, bar().getVisibility());
        });
    }

    private StreamInfoItem item(final String id, final long duration) {
        final StreamInfoItem x = new StreamInfoItem(1, "https://example.invalid/" + id,
                "Generated " + id, StreamType.VIDEO_STREAM);
        x.setDuration(duration); x.setUploaderName("Generated fixture"); return x;
    }
    private AnimatedProgressBar bar() {
        return holder.itemView.findViewById(R.id.itemProgressView);
    }
    private void awaitProgress(final int progress) throws Exception {
        await(() -> bar().getVisibility() == View.VISIBLE && bar().getProgress() == progress);
    }
    private void await(final BooleanSupplier check) throws Exception {
        final long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < end) {
            final boolean[] ok = {false}; main(() -> ok[0] = check.getAsBoolean());
            if (ok[0]) {
                return;
            }
            Thread.sleep(25);
        }
        fail("holder condition timed out");
    }
    private void main(final Runnable action) throws Exception {
        final FutureTask<Void> f = new FutureTask<>(() -> {
            action.run();
            return null;
        });
        new Handler(Looper.getMainLooper()).post(f); f.get(10, TimeUnit.SECONDS);
    }
    private static final class DelayedHistory extends HistoryRecordManager {
        private final List<SingleSubject<StreamStateEntity[]>> requests =
                new CopyOnWriteArrayList<>();
        DelayedHistory(final Context context) {
            super(context);
        }
        @Override public Single<StreamStateEntity[]> loadStreamState(final InfoItem item) {
            final SingleSubject<StreamStateEntity[]> s = SingleSubject.create();
            requests.add(s); return s;
        }
        void complete(final int index, final long millis) {
            requests.get(index).onSuccess(
                    new StreamStateEntity[]{new StreamStateEntity(1, millis)});
        }
        void releaseAll() {
            for (final SingleSubject<StreamStateEntity[]> s : requests) {
                if (!s.hasValue() && !s.hasThrowable()) {
                    s.onSuccess(new StreamStateEntity[]{null});
                }
            }
        }
    }
}
