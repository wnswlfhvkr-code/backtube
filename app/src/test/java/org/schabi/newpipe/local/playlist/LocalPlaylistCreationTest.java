package org.schabi.newpipe.local.playlist;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.Before;
import org.junit.Test;
import org.schabi.newpipe.database.AppDatabase;
import org.schabi.newpipe.database.playlist.dao.PlaylistDAO;
import org.schabi.newpipe.database.playlist.dao.PlaylistStreamDAO;
import org.schabi.newpipe.database.playlist.model.PlaylistEntity;
import org.schabi.newpipe.database.playlist.model.PlaylistStreamEntity;
import org.schabi.newpipe.database.stream.dao.StreamDAO;
import org.schabi.newpipe.database.stream.model.StreamEntity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.reactivex.rxjava3.core.Flowable;

/** Real manager/transaction body with Room DAO execution replaced at the Android boundary. */
public class LocalPlaylistCreationTest {
    private static final long PLAYLIST_ID = 73;
    private final AtomicReference<PlaylistEntity> saved = new AtomicReference<>();
    private final AtomicReference<List<PlaylistStreamEntity>> joins = new AtomicReference<>();
    private final AtomicInteger transactions = new AtomicInteger();
    private LocalPlaylistManager manager;

    @Before public void setUp() {
        final AppDatabase database = mock(AppDatabase.class);
        final StreamDAO streams = mock(StreamDAO.class);
        final PlaylistDAO playlists = mock(PlaylistDAO.class);
        final PlaylistStreamDAO playlistStreams = mock(PlaylistStreamDAO.class);
        when(database.streamDAO()).thenReturn(streams);
        when(database.playlistDAO()).thenReturn(playlists);
        when(database.playlistStreamDAO()).thenReturn(playlistStreams);
        // Execute the actual submitted transaction body, not a copied manager algorithm.
        doAnswer(invocation -> {
            transactions.incrementAndGet();
            return ((Callable<?>) invocation.getArgument(0)).call();
        }).when(database).runInTransaction(any(Callable.class));
        when(streams.upsertAll(any())).thenAnswer(invocation -> {
            final Collection<?> entries = invocation.getArgument(0);
            final List<Long> ids = new ArrayList<>();
            for (int index = 0; index < entries.size(); index++) {
                ids.add(201L + index);
            }
            return ids;
        });
        when(playlists.insert(any())).thenAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return PLAYLIST_ID;
        });
        when(playlistStreams.insertAll(any())).thenAnswer(invocation -> {
            final Collection<PlaylistStreamEntity> entries = invocation.getArgument(0);
            joins.set(new ArrayList<>(entries));
            final List<Long> ids = new ArrayList<>();
            for (int index = 0; index < entries.size(); index++) {
                ids.add(501L + index);
            }
            return ids;
        });
        when(playlistStreams.getMaximumIndexOf(PLAYLIST_ID)).thenReturn(Flowable.just(-1));
        manager = new LocalPlaylistManager(database);
    }

    @Test public void standaloneEmptyPlaylistEmitsSuccessAndStoresNamedLocalMetadata() {
        manager.createPlaylist("My recordings", List.of()).test()
                .awaitDone(5, TimeUnit.SECONDS).assertNoErrors().assertComplete()
                .assertValue(List.of());
        assertEquals(1, transactions.get());
        assertNotNull(saved.get());
        assertEquals("My recordings", saved.get().getName());
        assertEquals(PlaylistEntity.DEFAULT_THUMBNAIL_ID, saved.get().getThumbnailStreamId());
        assertEquals(-1, saved.get().getDisplayIndex());
        assertTrue(joins.get().isEmpty());
    }

    @Test public void seededCreationKeepsThumbnailOrderAndJoinIdReturnContract() {
        manager.createPlaylist("Existing seeded path", tracks()).test()
                .awaitDone(5, TimeUnit.SECONDS).assertNoErrors().assertComplete()
                .assertValue(List.of(501L, 502L));
        assertEquals(1, transactions.get());
        assertEquals(201L, saved.get().getThumbnailStreamId());
        assertOrderedJoins();
    }

    @Test public void firstTracksAppendToAnEmptyPlaylistAtIndexZero() {
        manager.appendToPlaylist(PLAYLIST_ID, tracks()).test()
                .awaitDone(5, TimeUnit.SECONDS).assertNoErrors().assertComplete()
                .assertValue(List.of(501L, 502L));
        assertEquals(1, transactions.get());
        assertOrderedJoins();
    }

    private void assertOrderedJoins() {
        assertEquals(2, joins.get().size());
        for (int index = 0; index < joins.get().size(); index++) {
            final PlaylistStreamEntity entry = joins.get().get(index);
            assertEquals(PLAYLIST_ID, entry.getPlaylistUid());
            assertEquals(201L + index, entry.getStreamUid());
            assertEquals(index, entry.getIndex());
        }
    }

    private static List<StreamEntity> tracks() {
        return List.of(mock(StreamEntity.class), mock(StreamEntity.class));
    }
}
