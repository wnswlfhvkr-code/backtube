package org.schabi.newpipe.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.text.TextUtils;
import android.util.Log;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.schabi.newpipe.database.AppDatabase;
import org.schabi.newpipe.database.LocalItem;
import org.schabi.newpipe.database.playlist.PlaylistMetadataEntry;
import org.schabi.newpipe.database.playlist.PlaylistStreamEntry;
import org.schabi.newpipe.database.playlist.dao.PlaylistDAO;
import org.schabi.newpipe.database.playlist.dao.PlaylistRemoteDAO;
import org.schabi.newpipe.database.playlist.dao.PlaylistStreamDAO;
import org.schabi.newpipe.database.playlist.model.PlaylistEntity;
import org.schabi.newpipe.database.playlist.model.PlaylistRemoteEntity;
import org.schabi.newpipe.database.playlist.model.PlaylistStreamEntity;
import org.schabi.newpipe.database.stream.dao.StreamDAO;
import org.schabi.newpipe.database.stream.model.StreamEntity;
import org.schabi.newpipe.error.ErrorPanelHelper;
import org.schabi.newpipe.local.bookmark.BookmarkFragment;
import org.schabi.newpipe.local.playlist.LocalPlaylistFragment;
import org.schabi.newpipe.local.playlist.LocalPlaylistManager;
import org.schabi.newpipe.local.playlist.RemotePlaylistManager;
import org.schabi.newpipe.util.debounce.DebounceSavable;
import org.schabi.newpipe.util.debounce.DebounceSaver;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.schedulers.TestScheduler;

/**
 * Real save/destruction methods and manager transaction bodies with paused IO.
 * Adapter contents/loaded state stand in for UI; Room infrastructure is explicitly mocked.
 */
public class PlaylistSaveLifecycleTest {
    @Test
    public void contentOrderCommitsWhileViewLives() throws Exception {
        checkContentOrder(false, false);
    }

    @Test
    public void contentOrderStillCommitsAfterViewDestructionBeforeIo() throws Exception {
        checkContentOrder(true, false);
    }

    @Test
    public void bookmarkDisplayOrderCommitsWhileViewLives() throws Exception {
        checkBookmarkOrder(false, false);
    }

    @Test
    public void bookmarkDisplayOrderStillCommitsAfterViewDestructionBeforeIo() throws Exception {
        checkBookmarkOrder(true, false);
    }

    @Test
    public void failedContentSaveAfterViewDestructionKeepsDirtyStateWithoutRxError()
            throws Exception {
        checkContentOrder(true, true);
    }

    @Test
    public void failedBookmarkSaveAfterViewDestructionKeepsDirtyStateWithoutRxError()
            throws Exception {
        checkBookmarkOrder(true, true);
    }

    @Test
    public void oldContentSaveCannotClearRecreatedViewsNewOrder() throws Exception {
        checkRecreatedContent(false);
    }

    @Test
    public void oldContentFailureCannotShowErrorOnRecreatedView() throws Exception {
        checkRecreatedContent(true);
    }

    private void checkRecreatedContent(final boolean failWrite) throws Exception {
        withPausedIo((io, errors) -> {
            final Tables tables = new Tables();
            final LocalPlaylistFragment fragment = LocalPlaylistFragment.getInstance(17, "Mine");
            set(fragment, "playlistManager", new LocalPlaylistManager(tables.database));
            final PlaylistStreamEntry first = new PlaylistStreamEntry(
                    mock(StreamEntity.class), 0, 101, 0);
            final PlaylistStreamEntry second = new PlaylistStreamEntry(
                    mock(StreamEntity.class), 0, 102, 1);
            final DebounceSaver saver = prepareLoadedModified(fragment, fragment,
                    new ArrayList<>(List.of(second, first)));
            fragment.saveImmediate();
            fragment.onDestroyView();
            replaceLoadedAdapter(fragment, new ArrayList<>(List.of(first, second)));
            final ErrorPanelHelper replacementPanel = mock(ErrorPanelHelper.class);
            set(fragment, "errorPanelHelper", replacementPanel);
            tables.failWrites = failWrite;
            // onDestroyView keeps the same saver; only the recreated adapter is replaced.
            saver.setHasChangesToSave();
            io.triggerActions();
            assertTrue("unexpected Rx errors: " + errors, errors.isEmpty());
            verify(replacementPanel, never()).showError(any());
            assertEquals(1, tables.transactions);
            if (failWrite) {
                assertTrue(saver.getIsModified());
                return;
            }
            assertEquals(102L, tables.insertedJoins.get(0).getStreamUid());
            assertTrue("old completion cannot mark the recreated view saved",
                    saver.getIsModified());
            fragment.saveImmediate();
            io.triggerActions();
            assertEquals(2, tables.transactions);
            assertEquals(101L, tables.insertedJoins.get(2).getStreamUid());
            assertFalse(saver.getIsModified());
        });
    }

    @Test
    public void oldBookmarkSaveCannotClearRecreatedViewsNewOrder() throws Exception {
        checkRecreatedBookmark(false);
    }

    @Test
    public void oldBookmarkFailureCannotShowErrorOnRecreatedView() throws Exception {
        checkRecreatedBookmark(true);
    }

    private void checkRecreatedBookmark(final boolean failWrite) throws Exception {
        withPausedIo((io, errors) -> {
            final Tables tables = new Tables();
            final BookmarkFragment fragment = new BookmarkFragment();
            set(fragment, "localPlaylistManager", new LocalPlaylistManager(tables.database));
            set(fragment, "remotePlaylistManager", new RemotePlaylistManager(tables.database));
            set(fragment, "deletedItems", new ArrayList<>());
            final PlaylistMetadataEntry local = new PlaylistMetadataEntry(
                    17, "Mine", null, 9L, false, 101L, 2);
            final PlaylistRemoteEntity remote = new PlaylistRemoteEntity(
                    23, 0, "Remote", "https://example.invalid/list", null, null, 9, 1L);
            final DebounceSaver saver = prepareLoadedModified(fragment, fragment,
                    new ArrayList<>(List.of(local, remote)));
            fragment.saveImmediate();
            fragment.onDestroyView();
            replaceLoadedAdapter(fragment, new ArrayList<>(List.of(remote, local)));
            final ErrorPanelHelper replacementPanel = mock(ErrorPanelHelper.class);
            set(fragment, "errorPanelHelper", replacementPanel);
            tables.failWrites = failWrite;
            saver.setHasChangesToSave();
            io.triggerActions();
            assertTrue("unexpected Rx errors: " + errors, errors.isEmpty());
            verify(replacementPanel, never()).showError(any());
            if (failWrite) {
                assertTrue(tables.transactions > 0);
                assertTrue(saver.getIsModified());
                return;
            }
            assertEquals(2, tables.transactions);
            assertTrue("old completion cannot mark the recreated view saved",
                    saver.getIsModified());
            fragment.saveImmediate();
            io.triggerActions();
            assertEquals(4, tables.transactions);
            assertEquals(1L, tables.localUpdates.get(1).getDisplayIndex());
            assertEquals(0L, tables.remoteUpdates.get(1).getDisplayIndex().longValue());
            assertFalse(saver.getIsModified());
        });
    }

    private static void replaceLoadedAdapter(final Object fragment,
                                              final ArrayList<LocalItem> items) throws Exception {
        final LocalItemListAdapter adapter = mock(LocalItemListAdapter.class);
        when(adapter.getItemsList()).thenReturn(items);
        set(fragment, "itemListAdapter", adapter);
    }

    private void checkContentOrder(final boolean destroyBeforeIo, final boolean failWrite)
            throws Exception {
        withPausedIo((io, errors) -> {
            final Tables tables = new Tables();
            tables.failWrites = failWrite;
            final LocalPlaylistFragment fragment = LocalPlaylistFragment.getInstance(17, "Mine");
            set(fragment, "playlistManager", new LocalPlaylistManager(tables.database));
            final PlaylistStreamEntry first = new PlaylistStreamEntry(
                    mock(StreamEntity.class), 0, 101, 0);
            final PlaylistStreamEntry second = new PlaylistStreamEntry(
                    mock(StreamEntity.class), 0, 102, 1);
            final DebounceSaver saver = prepareLoadedModified(fragment, fragment,
                    new ArrayList<>(List.of(second, first)));

            fragment.saveImmediate();
            assertEquals("IO is deliberately queued", 0, tables.transactions);
            if (destroyBeforeIo) {
                fragment.onDestroyView();
            }
            io.triggerActions();
            assertTrue("unexpected Rx errors before commit assertions: " + errors,
                    errors.isEmpty());
            assertEquals("confirmed content order transaction must survive view teardown",
                    1, tables.transactions);
            if (failWrite) {
                assertTrue("failed save retains changes", saver.getIsModified());
                assertTrue(tables.insertedJoins.isEmpty());
                return;
            }
            assertEquals(List.of(17L), tables.deletedJoins);
            assertEquals(2, tables.insertedJoins.size());
            assertEquals(102L, tables.insertedJoins.get(0).getStreamUid());
            assertEquals(0, tables.insertedJoins.get(0).getIndex());
            assertEquals(101L, tables.insertedJoins.get(1).getStreamUid());
            assertEquals(1, tables.insertedJoins.get(1).getIndex());
            assertEquals(17L, tables.insertedJoins.get(0).getPlaylistUid());
            assertFalse("successful save clears modified state", saver.getIsModified());
        });
    }

    private void checkBookmarkOrder(final boolean destroyBeforeIo, final boolean failWrite)
            throws Exception {
        withPausedIo((io, errors) -> {
            final Tables tables = new Tables();
            tables.failWrites = failWrite;
            final BookmarkFragment fragment = new BookmarkFragment();
            set(fragment, "localPlaylistManager", new LocalPlaylistManager(tables.database));
            set(fragment, "remotePlaylistManager", new RemotePlaylistManager(tables.database));
            set(fragment, "deletedItems", new ArrayList<>());
            final PlaylistMetadataEntry local = new PlaylistMetadataEntry(
                    17, "Mine", null, 9L, false, 101L, 2);
            final PlaylistRemoteEntity remote = new PlaylistRemoteEntity(
                    23, 0, "Remote", "https://example.invalid/list", null, null, 9, 1L);
            final DebounceSaver saver = prepareLoadedModified(fragment, fragment,
                    new ArrayList<>(List.of(local, remote)));

            fragment.saveImmediate();
            assertEquals("IO is deliberately queued", 0, tables.transactions);
            if (destroyBeforeIo) {
                fragment.onDestroyView();
            }
            io.triggerActions();
            assertTrue("unexpected Rx errors before commit assertions: " + errors,
                    errors.isEmpty());
            if (failWrite) {
                assertTrue("the confirmed save must still be attempted", tables.transactions > 0);
                assertTrue("failed save retains changes", saver.getIsModified());
                assertTrue(tables.localUpdates.isEmpty());
                assertTrue(tables.remoteUpdates.isEmpty());
                return;
            }
            // The real merged path executes one local and one remote transaction.
            assertEquals("local display-order write must survive view teardown",
                    1, tables.localUpdates.size());
            assertEquals("remote display-order write must survive view teardown",
                    1, tables.remoteUpdates.size());
            assertEquals(2, tables.transactions);
            assertEquals(17L, tables.localUpdates.get(0).getUid());
            assertEquals(0L, tables.localUpdates.get(0).getDisplayIndex());
            assertEquals(23L, tables.remoteUpdates.get(0).getUid());
            assertEquals(1L, tables.remoteUpdates.get(0).getDisplayIndex().longValue());
            assertFalse("successful merged save clears modified state", saver.getIsModified());
        });
    }

    private static DebounceSaver prepareLoadedModified(final Object fragment,
                                                       final DebounceSavable savable,
                                                       final ArrayList<LocalItem> items)
            throws Exception {
        final LocalItemListAdapter adapter = mock(LocalItemListAdapter.class);
        when(adapter.getItemsList()).thenReturn(items);
        set(fragment, "itemListAdapter", adapter);
        set(fragment, "disposables", new CompositeDisposable());
        set(fragment, "isLoadingComplete", new AtomicBoolean(true));
        final DebounceSaver saver = new DebounceSaver(savable);
        saver.setHasChangesToSave();
        set(fragment, "debounceSaver", saver);
        return saver;
    }

    private static void set(final Object target, final String name, final Object value)
            throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                final Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (final NoSuchFieldException ignored) {
                // UI boundary fields may be declared on a fragment's base class.
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void withPausedIo(final Probe probe) throws Exception {
        final TestScheduler io = new TestScheduler();
        final List<Throwable> errors = new ArrayList<>();
        final var oldIo = RxJavaPlugins.getIoSchedulerHandler();
        final var oldErrors = RxJavaPlugins.getErrorHandler();
        final var oldMainInit = RxAndroidPlugins.getInitMainThreadSchedulerHandler();
        final var oldMain = RxAndroidPlugins.getOnMainThreadSchedulerHandler();
        RxJavaPlugins.setIoSchedulerHandler(ignored -> io);
        RxJavaPlugins.setErrorHandler(errors::add);
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(ignored -> Schedulers.trampoline());
        RxAndroidPlugins.setMainThreadSchedulerHandler(ignored -> Schedulers.trampoline());
        try (MockedStatic<TextUtils> text = mockStatic(TextUtils.class);
             MockedStatic<Log> log = mockStatic(Log.class)) {
            text.when(() -> TextUtils.isEmpty(any())).thenAnswer(invocation -> {
                final CharSequence value = invocation.getArgument(0);
                return value == null || value.length() == 0;
            });
            probe.run(io, errors);
            assertTrue("unexpected Rx errors are setup failures: " + errors, errors.isEmpty());
        } finally {
            RxJavaPlugins.setIoSchedulerHandler(oldIo);
            RxJavaPlugins.setErrorHandler(oldErrors);
            RxAndroidPlugins.setInitMainThreadSchedulerHandler(oldMainInit);
            RxAndroidPlugins.setMainThreadSchedulerHandler(oldMain);
        }
    }

    private interface Probe {
        void run(TestScheduler io, List<Throwable> errors) throws Exception;
    }

    private static final class Tables {
        private final AppDatabase database = mock(AppDatabase.class);
        private final List<Long> deletedJoins = new ArrayList<>();
        private final List<PlaylistStreamEntity> insertedJoins = new ArrayList<>();
        private final List<PlaylistEntity> localUpdates = new ArrayList<>();
        private final List<PlaylistRemoteEntity> remoteUpdates = new ArrayList<>();
        private int transactions;
        private boolean failWrites;

        Tables() {
            final PlaylistDAO local = mock(PlaylistDAO.class);
            final PlaylistRemoteDAO remote = mock(PlaylistRemoteDAO.class);
            final PlaylistStreamDAO joins = mock(PlaylistStreamDAO.class);
            when(database.streamDAO()).thenReturn(mock(StreamDAO.class));
            when(database.playlistDAO()).thenReturn(local);
            when(database.playlistRemoteDAO()).thenReturn(remote);
            when(database.playlistStreamDAO()).thenReturn(joins);
            doAnswer(invocation -> {
                transactions++;
                if (failWrites) {
                    throw new IllegalStateException("owned fixture write failure");
                }
                invocation.getArgument(0, Runnable.class).run();
                return null;
            }).when(database).runInTransaction(any(Runnable.class));
            doAnswer(invocation -> {
                transactions++;
                if (failWrites) {
                    throw new IllegalStateException("owned fixture write failure");
                }
                return invocation.getArgument(0, Callable.class).call();
            }).when(database).runInTransaction(any(Callable.class));
            doAnswer(invocation -> {
                deletedJoins.add(invocation.getArgument(0));
                return null;
            }).when(joins).deleteBatch(anyLong());
            doAnswer(invocation -> {
                insertedJoins.addAll(invocation.getArgument(0));
                return List.of(201L, 202L);
            }).when(joins).insertAll(any());
            doAnswer(invocation -> {
                final PlaylistEntity entity = invocation.getArgument(0);
                localUpdates.add(entity);
                return entity.getUid();
            }).when(local).upsertPlaylist(any());
            doAnswer(invocation -> {
                final PlaylistRemoteEntity entity = invocation.getArgument(0);
                remoteUpdates.add(entity);
                return entity.getUid();
            }).when(remote).upsert(any());
        }
    }
}
