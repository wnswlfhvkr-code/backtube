package org.schabi.newpipe.local.dialog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Dialog;
import android.content.Context;
import android.widget.Toast;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.schabi.newpipe.R;
import org.schabi.newpipe.database.AppDatabase;
import org.schabi.newpipe.database.playlist.PlaylistDuplicatesEntry;
import org.schabi.newpipe.database.playlist.dao.PlaylistDAO;
import org.schabi.newpipe.database.playlist.dao.PlaylistStreamDAO;
import org.schabi.newpipe.database.playlist.model.PlaylistStreamEntity;
import org.schabi.newpipe.database.playlist.model.PlaylistEntity;
import org.schabi.newpipe.database.stream.dao.StreamDAO;
import org.schabi.newpipe.database.stream.model.StreamEntity;
import org.schabi.newpipe.local.playlist.LocalPlaylistManager;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.schedulers.TestScheduler;

/**
 * Real dialog handler, destroy-view method, manager and Rx chain.
 * Android presentation and Room DAO/transaction infrastructure are explicit boundary mocks.
 * This is not real Room persistence, ViewGroup input dispatch or Android execution.
 */
public class PlaylistAppendLifecycleTest {
    @Test
    public void selectedAppendCommitsWhenViewIsNotDestroyedBeforeIoRuns() throws Exception {
        checkSelection(false, false, false);
    }

    @Test
    public void selectedAppendStillCommitsAfterDismissAndDestroyBeforeIoRuns() throws Exception {
        checkSelection(true, false, false);
    }

    @Test
    public void firstTrackUpdatesEmptyPlaylistThumbnailAfterTheViewIsDestroyed() throws Exception {
        checkSelection(true, true, false);
    }

    @Test
    public void failedConfirmedAppendReportsFailureWithoutSuccess() throws Exception {
        checkSelection(false, false, true);
    }

    private void checkSelection(final boolean destroyBeforeIo, final boolean emptyThumbnail,
                                final boolean failAppend) throws Exception {
        final TestScheduler io = new TestScheduler();
        final List<Throwable> rxErrors = new ArrayList<>();
        final var oldIo = RxJavaPlugins.getIoSchedulerHandler();
        final var oldErrors = RxJavaPlugins.getErrorHandler();
        final var oldMainInit = RxAndroidPlugins.getInitMainThreadSchedulerHandler();
        final var oldMain = RxAndroidPlugins.getOnMainThreadSchedulerHandler();
        RxJavaPlugins.setIoSchedulerHandler(ignored -> io);
        RxJavaPlugins.setErrorHandler(rxErrors::add);
        // Handle both cold and previously initialized AndroidSchedulers without a real Looper.
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(ignored -> Schedulers.trampoline());
        RxAndroidPlugins.setMainThreadSchedulerHandler(ignored -> Schedulers.trampoline());
        try {
            final Context context = mock(Context.class);
            when(context.getApplicationContext()).thenReturn(context);
            final Dialog presentation = mock(Dialog.class);
            final Toast success = mock(Toast.class);
            final Toast failure = mock(Toast.class);
            final StreamEntity stream = mock(StreamEntity.class);
            when(stream.getUid()).thenReturn(101L);
            final List<StreamEntity> streams = List.of(stream);
            final PlaylistDuplicatesEntry selected = mock(PlaylistDuplicatesEntry.class);
            when(selected.getUid()).thenReturn(17L);
            when(selected.getThumbnailStreamId()).thenReturn(emptyThumbnail ? -1L : 101L);

            final AppDatabase database = mock(AppDatabase.class);
            final StreamDAO streamTable = mock(StreamDAO.class);
            final PlaylistStreamDAO joinTable = mock(PlaylistStreamDAO.class);
            when(database.streamDAO()).thenReturn(streamTable);
            final PlaylistDAO playlists = mock(PlaylistDAO.class);
            when(database.playlistDAO()).thenReturn(playlists);
            final PlaylistEntity metadata = new PlaylistEntity("Owned fixture", false,
                    emptyThumbnail ? -1 : 101, -1);
            metadata.setUid(17L);
            when(playlists.getPlaylist(17L)).thenReturn(Flowable.just(List.of(metadata)));
            final int[] thumbnailWrites = {0};
            doAnswer(invocation -> {
                thumbnailWrites[0]++;
                assertEquals(101L, invocation.getArgument(0, PlaylistEntity.class)
                        .getThumbnailStreamId());
                return 1;
            }).when(playlists).update(any(PlaylistEntity.class));
            when(database.playlistStreamDAO()).thenReturn(joinTable);
            when(joinTable.getMaximumIndexOf(17L)).thenReturn(Flowable.just(-1));
            when(streamTable.upsertAll(streams)).thenReturn(List.of(101L));
            final List<PlaylistStreamEntity> inserted = new ArrayList<>();
            doAnswer(invocation -> {
                if (failAppend) {
                    throw new IllegalStateException("Owned fixture rejects join write");
                }
                inserted.addAll(invocation.getArgument(0));
                return List.of(201L);
            }).when(joinTable).insertAll(any());
            final int[] transactions = {0};
            doAnswer(invocation -> {
                transactions[0]++;
                return invocation.getArgument(0, Callable.class).call();
            }).when(database).runInTransaction(any(Callable.class));
            final LocalPlaylistManager manager = new LocalPlaylistManager(database);

            // Use the real factory/constructor and spy only presentation/context access.
            final PlaylistAppendDialog dialog = spy(PlaylistAppendDialog.newInstance(streams));
            doReturn(context).when(dialog).getContext();
            doReturn(context).when(dialog).requireContext();
            doReturn(presentation).when(dialog).requireDialog();
            doReturn("Added").when(dialog).getString(R.string.playlist_add_stream_success);
            try (MockedStatic<Toast> toast = mockStatic(Toast.class)) {
                toast.when(() -> Toast.makeText(context, "Added", Toast.LENGTH_SHORT))
                        .thenReturn(success);
                toast.when(() -> Toast.makeText(context, R.string.general_error, Toast.LENGTH_LONG))
                        .thenReturn(failure);
                invokeSelection(dialog, manager, selected, streams);
                verify(presentation).dismiss();
                assertEquals("the IO queue must still be paused", 0, transactions[0]);
                assertTrue("unexpected Rx setup/error before IO", rxErrors.isEmpty());

                if (destroyBeforeIo) {
                    // Explicitly deliver the real lifecycle after real handler requested dismiss.
                    // No test-local disposal code or copied append implementation is used.
                    dialog.onDestroyView();
                }
                io.triggerActions();
                assertTrue("unexpected Rx setup/error after IO: " + rxErrors,
                        rxErrors.isEmpty());
                assertEquals("confirmed append must run once after queued IO is released",
                        1, transactions[0]);
                verify(streamTable).upsertAll(streams);
                verify(joinTable).insertAll(any());
                if (failAppend) {
                    assertTrue(inserted.isEmpty());
                    assertEquals(0, thumbnailWrites[0]);
                    verify(success, never()).show();
                    verify(failure).show();
                } else {
                    assertEquals(1, inserted.size());
                    assertEquals(17L, inserted.get(0).getPlaylistUid());
                    assertEquals(101L, inserted.get(0).getStreamUid());
                    assertEquals(0, inserted.get(0).getIndex());
                    assertEquals(emptyThumbnail ? 1 : 0, thumbnailWrites[0]);
                    verify(success, atLeastOnce()).show();
                    verify(failure, never()).show();
                }
            }
        } finally {
            RxJavaPlugins.setIoSchedulerHandler(oldIo);
            RxJavaPlugins.setErrorHandler(oldErrors);
            RxAndroidPlugins.setInitMainThreadSchedulerHandler(oldMainInit);
            RxAndroidPlugins.setMainThreadSchedulerHandler(oldMain);
        }
    }

    private static void invokeSelection(final PlaylistAppendDialog dialog,
                                        final LocalPlaylistManager manager,
                                        final PlaylistDuplicatesEntry selected,
                                        final List<StreamEntity> streams) throws Exception {
        final Method method = PlaylistAppendDialog.class.getDeclaredMethod("onPlaylistSelected",
                LocalPlaylistManager.class, PlaylistDuplicatesEntry.class, List.class);
        method.setAccessible(true);
        try {
            method.invoke(dialog, manager, selected, streams);
        } catch (final InvocationTargetException exception) {
            // Report the original setup/handler cause; do not obscure it as semantic RED.
            final Throwable cause = exception.getCause();
            if (cause instanceof Exception original) {
                throw original;
            }
            if (cause instanceof Error original) {
                throw original;
            }
            throw exception;
        }
    }
}
