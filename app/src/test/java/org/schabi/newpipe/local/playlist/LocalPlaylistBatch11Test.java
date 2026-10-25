package org.schabi.newpipe.local.playlist;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.MockedConstruction;
import org.schabi.newpipe.database.playlist.PlaylistStreamEntry;
import org.schabi.newpipe.error.ErrorPanelHelper;
import org.schabi.newpipe.fragments.BaseStateFragment;
import org.schabi.newpipe.local.history.HistoryRecordManager;
import org.schabi.newpipe.util.external_communication.ShareUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.processors.PublishProcessor;
import io.reactivex.rxjava3.schedulers.Schedulers;

/** Exercises the fragment's actual subscriptions with synthetic database emissions. */
public class LocalPlaylistBatch11Test {
    @Before
    public void useSynchronousSchedulers() {
        RxJavaPlugins.setIoSchedulerHandler(scheduler -> Schedulers.trampoline());
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(scheduler -> Schedulers.trampoline());
        RxAndroidPlugins.setMainThreadSchedulerHandler(scheduler -> Schedulers.trampoline());
    }

    @After
    public void resetSchedulers() {
        RxJavaPlugins.reset();
        RxAndroidPlugins.reset();
    }

    @Test
    public void oneShareClickUsesOnlyFirstDatabaseSnapshot() throws Exception {
        final LocalPlaylistFragment fragment = fragment();
        final Context context = mock(Context.class);
        doReturn(context).when(fragment).requireContext();
        final LocalPlaylistManager manager = mock(LocalPlaylistManager.class);
        final PublishProcessor<List<PlaylistStreamEntry>> snapshots = PublishProcessor.create();
        when(manager.getPlaylistStreams(31)).thenReturn(snapshots);
        set(fragment, "playlistManager", manager);
        set(fragment, "disposables", new CompositeDisposable());

        try (MockedStatic<ShareUtils> shares = mockStatic(ShareUtils.class)) {
            invoke(fragment, "sharePlaylist", PlayListShareMode.class, PlayListShareMode.JUST_URLS);
            snapshots.onNext(List.of());
            snapshots.onNext(List.of());
            shares.verify(() -> ShareUtils.shareText(context, "Mine", ""), times(1));
            assertFalse(snapshots.hasSubscribers());
        }
    }

    @Test
    public void emptyRewriteCompletionReleasesGuardForRetry() throws Exception {
        final LocalPlaylistFragment fragment = fragment();
        doNothing().when(fragment).showLoading();
        final LocalPlaylistManager manager = mock(LocalPlaylistManager.class);
        when(manager.getDistinctPlaylistStreams(31)).thenReturn(Flowable.empty());
        set(fragment, "playlistManager", manager);
        set(fragment, "disposables", new CompositeDisposable());

        invoke(fragment, "removeDuplicatesInPlaylist");
        assertFalse(getGuard(fragment));
        invoke(fragment, "removeDuplicatesInPlaylist");
        verify(manager, times(2)).getDistinctPlaylistStreams(31);
    }

    @Test
    public void canceledRewriteReleasesGuard() throws Exception {
        final LocalPlaylistFragment fragment = fragment();
        doNothing().when(fragment).showLoading();
        final LocalPlaylistManager manager = mock(LocalPlaylistManager.class);
        final PublishProcessor<List<PlaylistStreamEntry>> pending = PublishProcessor.create();
        when(manager.getDistinctPlaylistStreams(31)).thenReturn(pending);
        set(fragment, "playlistManager", manager);
        final CompositeDisposable disposables = new CompositeDisposable();
        set(fragment, "disposables", disposables);

        invoke(fragment, "removeDuplicatesInPlaylist");
        assertTrue(getGuard(fragment));
        disposables.clear();
        assertFalse(getGuard(fragment));
        assertFalse(pending.hasSubscribers());
    }

    @Test
    public void failedRewriteReleasesGuardForRetry() throws Exception {
        final LocalPlaylistFragment fragment = fragment();
        doNothing().when(fragment).showLoading();
        final LocalPlaylistManager manager = mock(LocalPlaylistManager.class);
        when(manager.getDistinctPlaylistStreams(31))
                .thenReturn(Flowable.error(new IllegalStateException("synthetic failure")));
        set(fragment, "playlistManager", manager);
        set(fragment, "disposables", new CompositeDisposable());
        final Field errorPanel = BaseStateFragment.class.getDeclaredField("errorPanelHelper");
        errorPanel.setAccessible(true);
        errorPanel.set(fragment, mock(ErrorPanelHelper.class));

        invoke(fragment, "removeDuplicatesInPlaylist");
        assertFalse(getGuard(fragment));
        invoke(fragment, "removeDuplicatesInPlaylist");
        verify(manager, times(2)).getDistinctPlaylistStreams(31);
    }

    @Test
    public void emptyWatchedRewriteReleasesGuardForRetry() throws Exception {
        final LocalPlaylistFragment fragment = fragment();
        doNothing().when(fragment).showLoading();
        final LocalPlaylistManager manager = mock(LocalPlaylistManager.class);
        when(manager.getPlaylistStreams(31)).thenReturn(Flowable.empty());
        set(fragment, "playlistManager", manager);
        set(fragment, "disposables", new CompositeDisposable());

        try (MockedConstruction<HistoryRecordManager> history =
                     mockConstruction(HistoryRecordManager.class, (record, construction) ->
                             when(record.getStreamHistorySortedById())
                                     .thenReturn(Flowable.empty()))) {
            invoke(fragment, "removeWatchedStreams", boolean.class, false);
            assertFalse(getGuard(fragment));
            invoke(fragment, "removeWatchedStreams", boolean.class, false);
            verify(manager, times(2)).getPlaylistStreams(31);
        }
    }

    private static boolean getGuard(final LocalPlaylistFragment fragment) throws Exception {
        final Field field = LocalPlaylistFragment.class.getDeclaredField("isRewritingPlaylist");
        field.setAccessible(true);
        return field.getBoolean(fragment);
    }

    private static LocalPlaylistFragment fragment() throws Exception {
        final LocalPlaylistFragment fragment = spy(new LocalPlaylistFragment());
        set(fragment, "playlistId", 31L);
        set(fragment, "name", "Mine");
        return fragment;
    }

    private static void set(final Object target, final String name, final Object value)
            throws Exception {
        final Field field = LocalPlaylistFragment.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void invoke(final Object target, final String name) throws Exception {
        invoke(target, name, null, null);
    }

    private static void invoke(final Object target, final String name, final Class<?> parameter,
                               final Object value) throws Exception {
        final Method method = parameter == null
                ? LocalPlaylistFragment.class.getDeclaredMethod(name)
                : LocalPlaylistFragment.class.getDeclaredMethod(name, parameter);
        method.setAccessible(true);
        if (parameter == null) {
            method.invoke(target);
        } else {
            method.invoke(target, value);
        }
    }
}
