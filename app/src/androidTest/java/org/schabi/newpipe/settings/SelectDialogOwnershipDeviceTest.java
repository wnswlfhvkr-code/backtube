package org.schabi.newpipe.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.fragment.app.DialogFragment;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.schabi.newpipe.NewPipeDatabase;
import org.schabi.newpipe.database.feed.model.FeedGroupEntity;
import org.schabi.newpipe.database.playlist.PlaylistMetadataEntry;
import org.schabi.newpipe.database.playlist.model.PlaylistRemoteEntity;
import org.schabi.newpipe.database.subscription.SubscriptionEntity;
import org.schabi.newpipe.fragments.list.playlist.Batch10TestActivity;
import org.schabi.newpipe.local.subscription.FeedGroupIcon;
import org.schabi.newpipe.testUtil.TestDatabase;

import io.reactivex.rxjava3.core.Observer;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.disposables.SerialDisposable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Real Android rows and in-memory Room; all entries are synthetic. */
public class SelectDialogOwnershipDeviceTest {
    private Batch10TestActivity activity;

    @Before
    public void setUp() {
        TestDatabase.Companion.createReplacingNewPipeDatabase();
        final var instrumentation = InstrumentationRegistry.getInstrumentation();
        final Intent intent = new Intent(instrumentation.getTargetContext(),
                Batch10TestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity = (Batch10TestActivity) instrumentation.startActivitySync(intent);
    }

    @After
    public void tearDown() throws Exception {
        onMain(() -> {
            activity.finish();
            return null;
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        NewPipeDatabase.close();
    }

    @Test
    public void channelOldRowCannotSelectReplacementButReboundRowCan() throws Exception {
        final SelectChannelFragment fragment = new SelectChannelFragment();
        final AtomicReference<String> selected = new AtomicReference<>();
        fragment.setOnSelectedListener((serviceId, url, name) -> selected.set(url));
        show(fragment);
        onMain(() -> {
            final RecyclerView list = field(fragment, "recyclerView");
            display(fragment, "displayChannels", List.of(channel("A")));
            final RecyclerView.ViewHolder row = boundRow(list);
            display(fragment, "displayChannels", List.of(channel("B")));
            row.itemView.performClick();
            assertNull(selected.get());
            rebind(list, row);
            row.itemView.performClick();
            assertEquals("https://fixture.invalid/B", selected.get());
            return null;
        });
    }

    @Test
    public void feedGroupOldRowCannotSelectReplacementButReboundRowCan() throws Exception {
        final SelectFeedGroupFragment fragment = new SelectFeedGroupFragment();
        final AtomicReference<Long> selected = new AtomicReference<>();
        fragment.setOnSelectedListener((id, name, icon) -> selected.set(id));
        show(fragment);
        onMain(() -> {
            final RecyclerView list = field(fragment, "recyclerView");
            display(fragment, "displayFeedGroups", List.of(group(1, "A")));
            final RecyclerView.ViewHolder row = boundRow(list);
            display(fragment, "displayFeedGroups", List.of(group(2, "B")));
            row.itemView.performClick();
            assertNull(selected.get());
            rebind(list, row);
            row.itemView.performClick();
            assertEquals(Long.valueOf(2), selected.get());
            return null;
        });
    }

    @Test
    public void playlistOldLocalRowCannotSelectNewRemoteRow() throws Exception {
        final SelectPlaylistFragment fragment = new SelectPlaylistFragment();
        final AtomicReference<String> selected = new AtomicReference<>();
        fragment.setOnSelectedListener(new SelectPlaylistFragment.OnSelectedListener() {
            @Override
            public void onLocalPlaylistSelected(final long id, final String name) {
                selected.set("local:" + id);
            }

            @Override
            public void onRemotePlaylistSelected(final int serviceId, final String url,
                                                 final String name) {
                selected.set("remote:" + url);
            }
        });
        show(fragment);
        onMain(() -> {
            final RecyclerView list = field(fragment, "recyclerView");
            display(fragment, "displayPlaylists", List.of(local(1, "A")));
            final RecyclerView.ViewHolder row = boundRow(list);
            display(fragment, "displayPlaylists", List.of(remote(2, "B")));
            row.itemView.performClick();
            assertNull(selected.get());
            rebind(list, row);
            row.itemView.performClick();
            assertEquals("remote:https://fixture.invalid/B", selected.get());
            return null;
        });
    }

    @Test
    public void channelAndGroupEmptyUpdatesHideRowsAndNotifyAdapters() throws Exception {
        final SelectChannelFragment channel = new SelectChannelFragment();
        show(channel);
        onMain(() -> {
            final RecyclerView list = field(channel, "recyclerView");
            final AtomicInteger updates = countUpdates(list);
            display(channel, "displayChannels", List.of(channel("A")));
            display(channel, "displayChannels", List.of());
            assertTrue(updates.get() >= 2);
            assertEquals(View.GONE, list.getVisibility());
            assertEquals(View.VISIBLE, ((TextView) field(channel, "emptyView")).getVisibility());
            return null;
        });

        final SelectFeedGroupFragment group = new SelectFeedGroupFragment();
        show(group);
        onMain(() -> {
            final RecyclerView list = field(group, "recyclerView");
            final AtomicInteger updates = countUpdates(list);
            display(group, "displayFeedGroups", List.of(group(1, "A")));
            display(group, "displayFeedGroups", List.of());
            assertTrue(updates.get() >= 2);
            assertEquals(View.GONE, list.getVisibility());
            assertEquals(View.VISIBLE, ((TextView) field(group, "emptyView")).getVisibility());
            return null;
        });
    }

    @Test
    public void playlistListUpdateNotifiesItsAdapter() throws Exception {
        final SelectPlaylistFragment fragment = new SelectPlaylistFragment();
        show(fragment);
        onMain(() -> {
            final RecyclerView list = field(fragment, "recyclerView");
            final AtomicInteger updates = countUpdates(list);
            display(fragment, "displayPlaylists", List.of(local(1, "A")));
            display(fragment, "displayPlaylists", List.of());
            assertTrue(updates.get() >= 2);
            assertEquals(View.GONE, list.getVisibility());
            return null;
        });
    }

    @Test
    public void channelOldObserverIsDisposedAndCannotUpdateRecreatedView() throws Exception {
        final SelectChannelFragment fragment = new SelectChannelFragment();
        show(fragment);
        onMain(() -> {
            final Observer<List<SubscriptionEntity>> old = observer(fragment,
                    "getSubscriptionObserver");
            final Disposable subscription = Disposable.empty();
            old.onSubscribe(subscription);
            activity.getSupportFragmentManager().beginTransaction().detach(fragment).commitNow();
            installReplacementViews(fragment);
            setField(fragment, "subscriptions", List.of());
            old.onNext(List.of(channel("late")));
            assertTrue(subscription.isDisposed());
            assertTrue(((List<?>) field(fragment, "subscriptions")).isEmpty());
            return null;
        });
    }

    @Test
    public void groupOldObserverIsDisposedAndCannotUpdateRecreatedView() throws Exception {
        final SelectFeedGroupFragment fragment = new SelectFeedGroupFragment();
        show(fragment);
        onMain(() -> {
            final Observer<List<FeedGroupEntity>> old = observer(fragment,
                    "getFeedGroupObserver");
            final Disposable subscription = Disposable.empty();
            old.onSubscribe(subscription);
            activity.getSupportFragmentManager().beginTransaction().detach(fragment).commitNow();
            installReplacementViews(fragment);
            setField(fragment, "feedGroups", List.of());
            old.onNext(List.of(group(9, "late")));
            assertTrue(subscription.isDisposed());
            assertTrue(((List<?>) field(fragment, "feedGroups")).isEmpty());
            return null;
        });
    }

    @Test
    public void playlistQueryIsDisposedWhenOnlyItsViewIsDestroyed() throws Exception {
        final SelectPlaylistFragment fragment = new SelectPlaylistFragment();
        show(fragment);
        onMain(() -> {
            final Disposable query = activePlaylistQuery(fragment);
            activity.getSupportFragmentManager().beginTransaction().detach(fragment).commitNow();
            assertTrue(query.isDisposed());
            return null;
        });
    }

    private void show(final DialogFragment fragment) throws Exception {
        onMain(() -> {
            fragment.showNow(activity.getSupportFragmentManager(), "synthetic-selector");
            return null;
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private <T> T onMain(final Callable<T> action) throws Exception {
        final FutureTask<T> task = new FutureTask<>(action);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(task);
        return task.get();
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(final Object owner, final String name) throws Exception {
        final Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(owner);
    }

    private static void display(final Object fragment, final String name, final List<?> items)
            throws Exception {
        final Method method = fragment.getClass().getDeclaredMethod(name, List.class);
        method.setAccessible(true);
        method.invoke(fragment, items);
    }

    private static void setField(final Object owner, final String name, final Object value)
            throws Exception {
        final Field target = owner.getClass().getDeclaredField(name);
        target.setAccessible(true);
        target.set(owner, value);
    }

    @SuppressWarnings("unchecked")
    private static <T> Observer<List<T>> observer(final Object fragment, final String name)
            throws Exception {
        for (final Method method : fragment.getClass().getDeclaredMethods()) {
            if (!method.getName().equals(name)) {
                continue;
            }
            method.setAccessible(true);
            if (method.getParameterCount() == 0) {
                return (Observer<List<T>>) method.invoke(fragment);
            }
            return (Observer<List<T>>) method.invoke(fragment,
                    (long) field(fragment, "selectionGeneration"));
        }
        throw new NoSuchMethodException(name);
    }

    private void installReplacementViews(final Object fragment) throws Exception {
        setField(fragment, "recyclerView", new RecyclerView(activity));
        setField(fragment, "progressBar", new ProgressBar(activity));
        setField(fragment, "emptyView", new TextView(activity));
    }

    private static Disposable activePlaylistQuery(final SelectPlaylistFragment fragment)
            throws Exception {
        try {
            return field(fragment, "disposable");
        } catch (final NoSuchFieldException ignored) {
            final SerialDisposable slot = field(fragment, "selectionRequest");
            return slot.get();
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static RecyclerView.ViewHolder boundRow(final RecyclerView list) {
        final RecyclerView.Adapter adapter = list.getAdapter();
        final RecyclerView.ViewHolder row = adapter.createViewHolder(list, 0);
        adapter.bindViewHolder(row, 0);
        return row;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void rebind(final RecyclerView list, final RecyclerView.ViewHolder row) {
        ((RecyclerView.Adapter) list.getAdapter()).bindViewHolder(row, 0);
    }

    private static AtomicInteger countUpdates(final RecyclerView list) {
        final AtomicInteger count = new AtomicInteger();
        list.getAdapter().registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onChanged() {
                count.incrementAndGet();
            }
        });
        return count;
    }

    private static SubscriptionEntity channel(final String name) {
        final SubscriptionEntity entity = new SubscriptionEntity();
        entity.setServiceId(7);
        entity.setUrl("https://fixture.invalid/" + name);
        entity.setName(name);
        return entity;
    }

    private static FeedGroupEntity group(final long id, final String name) {
        return new FeedGroupEntity(id, name, FeedGroupIcon.MUSIC, id);
    }

    private static PlaylistMetadataEntry local(final long id, final String name) {
        return new PlaylistMetadataEntry(id, name, null, 0L, false, null, 0L);
    }

    private static PlaylistRemoteEntity remote(final long id, final String name) {
        return new PlaylistRemoteEntity(id, 7, name,
                "https://fixture.invalid/" + name, null, "synthetic", 0L, 0L);
    }
}
