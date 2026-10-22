package org.schabi.newpipe.offline;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.schabi.newpipe.database.playlist.PlaylistStreamEntry;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.local.playlist.LocalPlaylistFragment;
import org.schabi.newpipe.local.playlist.LocalPlaylistManager;
import org.schabi.newpipe.R;
import org.schabi.newpipe.database.stream.model.StreamEntity;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.schedulers.TestScheduler;

/** Actual owned store, queue and database-entity conversion; Android codec boundary is mocked. */
public class OfflineDurationMetadataTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private OfflineLibrary library;
    private OfflineStore.Entry entry;

    @Before public void setUp() throws Exception {
        resetLibrary();
        final Context context = mock(Context.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getFilesDir()).thenReturn(temporary.getRoot());
        when(context.getString(R.string.offline_saved_copy)).thenReturn("Saved copy");
        library = OfflineLibrary.get(context);
        entry = library.store().create("Generated audio", "https://example.invalid/generated",
                0, "file:///generated-audio", "audio/wav");
        library.store().copy(entry.id, new ByteArrayInputStream(new byte[]{1, 2, 3}));
    }

    @After public void tearDown() throws Exception {
        resetLibrary();
    }

    @Test public void savedQueuePreservesLocalMediaSecondsThroughPlaylistEntity() {
        try (MockedConstruction<MediaMetadataRetriever> codecs = metadata("287900")) {
            final StreamEntity entity = new StreamEntity(library.queue(entry).getItem());
            assertEquals("04:47 must survive the real queue-to-entity save path",
                    287, entity.getDuration());
            assertEquals(entry.localUrl(), entity.getUrl());
        }
    }

    @Test public void playAllAlsoSuppliesLocalDuration() throws Exception {
        try (MockedConstruction<MediaMetadataRetriever> codecs = metadata("287900")) {
            assertEquals(287, library.queueAll().getItem().getDuration());
        }
    }

    @Test public void selectedPlaybackPreservesDurationAndOwnedIdentityThroughPlaylistEntity()
            throws Exception {
        try (MockedConstruction<MediaMetadataRetriever> codecs = metadata("287900")) {
            final StreamEntity entity = new StreamEntity(library.queueSelected(
                    List.of(entry.id, entry.id)).getItem());
            assertEquals(287, entity.getDuration());
            assertEquals(entry.localUrl(), entity.getUrl());
            assertEquals(1, codecs.constructed().size());
        }
    }

    @Test public void unreadableDurationRemainsUnknownWithoutLosingPlayableEntry() {
        try (MockedConstruction<MediaMetadataRetriever> codecs = metadata(null)) {
            assertEquals(-1, library.queue(entry).getItem().getDuration());
            assertEquals(entry.localUrl(), library.queue(entry).getItem().getUrl());
        }
    }

    @Test public void openingExistingSparsePlaylistRecoversOwnedDurationWithoutDatabaseWrites()
            throws Exception {
        final TestScheduler io = new TestScheduler();
        RxJavaPlugins.setIoSchedulerHandler(scheduler -> io);
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(supplier -> Schedulers.trampoline());
        RxAndroidPlugins.setMainThreadSchedulerHandler(scheduler -> Schedulers.trampoline());
        try (MockedConstruction<MediaMetadataRetriever> codecs = metadata("287900");
             MockedStatic<Log> logs = org.mockito.Mockito.mockStatic(Log.class)) {
            final StreamInfoItem item = new StreamInfoItem(0, entry.localUrl(),
                    "Generated audio", StreamType.AUDIO_STREAM);
            item.setUploaderName("Saved copy");
            final PlaylistStreamEntry sparse = new PlaylistStreamEntry(
                    new StreamEntity(item), 0, 1, 0);
            final LocalPlaylistManager manager = mock(LocalPlaylistManager.class);
            when(manager.getPlaylistStreams(17)).thenReturn(Flowable.just(List.of(sparse)));
            final CapturingPlaylist fragment = new CapturingPlaylist();
            final AppCompatActivity activity = mock(AppCompatActivity.class);
            when(activity.getApplicationContext()).thenReturn(mock(Context.class));
            set(fragment, "activity", activity);
            set(fragment, "playlistId", 17L);
            set(fragment, "playlistManager", manager);
            set(fragment, "isLoadingComplete", new AtomicBoolean());
            fragment.startLoading(false);
            org.junit.Assert.assertNull(fragment.result);
            org.junit.Assert.assertTrue(codecs.constructed().isEmpty());
            io.triggerActions();
            assertEquals(287, fragment.result.get(0).getStreamEntity().getDuration());
            assertEquals(entry.localUrl(), fragment.result.get(0).getStreamEntity().getUrl());
            org.mockito.Mockito.verify(manager, org.mockito.Mockito.only()).getPlaylistStreams(17);
        } finally {
            RxJavaPlugins.reset();
            RxAndroidPlugins.reset();
        }
    }

    @Test public void invalidAndZeroLocalDurationsStayUnknown() {
        for (final String millis : new String[]{"invalid", "-1000", "0", "999"}) {
            try (MockedConstruction<MediaMetadataRetriever> codecs = metadata(millis)) {
                assertEquals(-1, library.queue(entry).getItem().getDuration());
            }
        }
    }

    @Test public void missingOwnedFileCannotSupplyDurationOrUseOrigin() {
        org.junit.Assert.assertTrue(library.store().file(entry).delete());
        try (MockedConstruction<MediaMetadataRetriever> codecs = metadata("287900")) {
            assertEquals(-1, library.queue(entry).getItem().getDuration());
            org.junit.Assert.assertTrue(codecs.constructed().isEmpty());
            assertEquals(-1, library.durationSeconds(0, entry.origin));
        }
    }

    private static final class CapturingPlaylist extends LocalPlaylistFragment {
        private List<PlaylistStreamEntry> result;

        @Override public void showLoading() { }

        @Override public void handleResult(final List<PlaylistStreamEntry> streams) {
            result = streams;
        }
    }

    private static void set(final Object target, final String name, final Object value)
            throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                final Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (final NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static MockedConstruction<MediaMetadataRetriever> metadata(final String millis) {
        return mockConstruction(MediaMetadataRetriever.class, (codec, context) ->
                when(codec.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION))
                        .thenReturn(millis));
    }

    private static void resetLibrary() throws Exception {
        final Field field = OfflineLibrary.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, null);
    }
}
