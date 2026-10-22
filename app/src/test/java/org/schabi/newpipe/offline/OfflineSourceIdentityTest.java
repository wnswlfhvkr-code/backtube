package org.schabi.newpipe.offline;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.upstream.FileDataSource;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;
import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.player.mediaitem.MediaItemTag;
import org.schabi.newpipe.player.Player;
import org.schabi.newpipe.player.event.PlayerServiceEventListener;
import org.schabi.newpipe.player.ui.PlayerUiList;
import org.schabi.newpipe.player.mediaitem.StreamInfoTag;
import org.schabi.newpipe.player.mediasource.LoadedMediaSource;
import org.schabi.newpipe.player.mediasource.ManagedMediaSource;
import org.schabi.newpipe.player.playback.MediaSourceManager;
import org.schabi.newpipe.player.playback.PlaybackListener;
import org.schabi.newpipe.player.playqueue.PlayQueueItem;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.player.playqueue.SinglePlayQueue;
import org.schabi.newpipe.util.InfoCache;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.ArrayList;

import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;

/** Actual owned store/source selection; Android URI and logging are JVM boundaries. */
public class OfflineSourceIdentityTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private static final String ORIGINAL = "https://example.invalid/original-video";
    private OfflineLibrary library;
    private OfflineStore.Entry saved;
    private PlayQueueItem online;
    private StreamInfo onlineInfo;
    private MediaSourceManager manager;
    private MockedStatic<Uri> uris;
    private MockedStatic<Log> logs;

    @Before public void setUp() throws Exception {
        resetLibrary();
        logs = mockStatic(Log.class);
        RxJavaPlugins.setIoSchedulerHandler(scheduler -> Schedulers.trampoline());
        uris = mockStatic(Uri.class);
        uris.when(() -> Uri.parse(anyString())).thenAnswer(invocation ->
                uri(invocation.getArgument(0)));
        uris.when(() -> Uri.fromFile(any(File.class))).thenAnswer(invocation ->
                uri(((File) invocation.getArgument(0)).toURI().toString()));
        final Context context = mock(Context.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getFilesDir()).thenReturn(temporary.getRoot());
        when(context.getString(R.string.offline_saved_copy)).thenReturn("Saved copy");
        library = OfflineLibrary.get(context);
        saved = library.store().create("Saved audio", ORIGINAL, 0,
                "file:///generated-audio", "audio/mpeg");
        onlineInfo = new StreamInfo(0, ORIGINAL, ORIGINAL, StreamType.VIDEO_STREAM,
                ORIGINAL, "Original video", 0);
        onlineInfo.setVideoStreams(List.of(new VideoStream.Builder().setId("video")
                .setContent("https://example.invalid/video.mp4", true)
                .setMediaFormat(MediaFormat.MPEG_4).setResolution("720p")
                .setIsVideoOnly(false).build()));
        onlineInfo.setAudioStreams(List.of(new AudioStream.Builder().setId("audio")
                .setContent("https://example.invalid/audio.m4a", true)
                .setMediaFormat(MediaFormat.M4A).setAverageBitrate(128).build()));
        onlineInfo.setRelatedItems(List.of(new StreamInfoItem(0,
                "https://example.invalid/related", "Related original", StreamType.VIDEO_STREAM)));
        online = new PlayQueueItem(onlineInfo);
        InfoCache.getInstance().clearCache();
        InfoCache.getInstance().putInfo(0, ORIGINAL, onlineInfo, InfoCache.Type.STREAM);
        final PlaybackListener listener = mock(PlaybackListener.class);
        when(listener.localSourceOf(any(PlayQueueItem.class))).thenAnswer(invocation ->
                library.source(invocation.getArgument(0)));
        when(listener.sourceOf(any(PlayQueueItem.class), any(StreamInfo.class)))
                .thenAnswer(invocation -> new ProgressiveMediaSource.Factory(FileDataSource::new)
                        .createMediaSource(StreamInfoTag.of(invocation.getArgument(1))
                                .asMediaItem()));
        // Resolve real manager sources without starting its Android handler/playback event loop.
        manager = mock(MediaSourceManager.class, CALLS_REAL_METHODS);
        final Field playback = MediaSourceManager.class.getDeclaredField("playbackListener");
        playback.setAccessible(true);
        playback.set(manager, listener);
    }

    @After public void tearDown() throws Exception {
        InfoCache.getInstance().clearCache();
        RxJavaPlugins.reset();
        uris.close();
        logs.close();
        resetLibrary();
    }

    @Test public void originalOnlineVideoNeverSelectsSavedAudioByOrigin() throws Exception {
        saveAudio();
        assertNull(library.source(online));
    }

    @Test public void explicitSavedIdentityStillSelectsOwnedSource() throws Exception {
        saveAudio();
        final var source = library.source(library.queue(saved).getItem());
        assertNotNull(source);
        final MediaItemTag tag = MediaItemTag.from(source.getMediaItem()).orElseThrow();
        assertEquals(saved.localUrl(), tag.getStreamUrl());
        assertEquals(saved.localUrl(), tag.getMaybeStreamInfo().orElseThrow().getUrl());
        assertEquals(StreamType.AUDIO_STREAM, tag.getStreamType());
    }

    @Test public void onlineThenSaveThenSameOnlineItemKeepsAllOriginalMetadata() throws Exception {
        assertFullOriginal(load(online));
        saveAudio();
        assertFullOriginal(load(online));
        assertFullOriginal(load(online));
    }

    @Test public void savedPlaybackThenOriginalNeverTransfersSparseMetadata() throws Exception {
        saveAudio();
        final PlayQueueItem local = library.queue(saved).getItem();
        final StreamInfo localInfo = load(local);
        assertEquals(saved.localUrl(), localInfo.getUrl());
        assertEquals(StreamType.AUDIO_STREAM, localInfo.getStreamType());
        localInfo.setVideoStreams(List.of());
        localInfo.setRelatedItems(List.of());
        assertFalse(online.isSameItem(local));
        assertFullOriginal(load(online));
        assertFullOriginal(load(online));
    }

    @Test public void originalThenSavedThenOriginalKeepsMetadataAndCache() throws Exception {
        saveAudio();
        assertFullOriginal(load(online));
        final StreamInfo localInfo = load(library.queue(saved).getItem());
        assertEquals(saved.localUrl(), localInfo.getUrl());
        assertFullOriginal(load(online));
    }

    @Test public void localMetadataMutationNeverChangesOriginalCacheOrQueue() throws Exception {
        saveAudio();
        final PlayQueueItem local = library.queue(saved).getItem();
        final StreamInfo localInfo = load(local);
        localInfo.setAudioStreams(List.of());
        localInfo.setVideoStreams(List.of());
        localInfo.setRelatedItems(List.of());
        assertFullOriginal((StreamInfo) InfoCache.getInstance().getFromKey(0, ORIGINAL,
                InfoCache.Type.STREAM));
        assertNull(InfoCache.getInstance().getFromKey(0, saved.localUrl(), InfoCache.Type.STREAM));
        assertFalse(online.isSameItem(local));
        assertEquals(saved.localUrl(), local.getUrl());
    }

    @Test public void retainedOnlineSourceRebindsFullMetadataToNewListener() throws Exception {
        saveAudio();
        final LoadedMediaSource retained = (LoadedMediaSource) resolve(online);
        assertFalse(retained.shouldBeReplacedWith(online, false));
        final Player player = mock(Player.class, CALLS_REAL_METHODS);
        final PlayQueue queue = new SinglePlayQueue(online);
        setPlayer(player, "playQueue", queue);
        setPlayer(player, "currentMetadata",
                MediaItemTag.from(retained.getMediaItem()).orElseThrow());
        setPlayer(player, "UIs", new PlayerUiList());
        final List<StreamInfo> delivered = new ArrayList<>();
        final PlayerServiceEventListener first = listener(delivered, queue);
        player.setFragmentListener(first);
        player.removeFragmentListener(first);
        player.setFragmentListener(listener(delivered, queue));
        assertEquals(2, delivered.size());
        for (final StreamInfo info : delivered) {
            assertFullOriginal(info);
        }
    }

    private static PlayerServiceEventListener listener(final List<StreamInfo> received,
                                                        final PlayQueue expectedQueue) {
        final PlayerServiceEventListener listener = mock(PlayerServiceEventListener.class);
        doAnswer(invocation -> {
            assertSame(expectedQueue, invocation.getArgument(1));
            received.add(invocation.getArgument(0));
            return null;
        }).when(listener).onMetadataUpdate(any(StreamInfo.class), any(PlayQueue.class));
        return listener;
    }

    private static void setPlayer(final Player player, final String name, final Object value)
            throws Exception {
        final Field field = Player.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(player, value);
    }

    private void saveAudio() throws Exception {
        library.store().copy(saved.id, new ByteArrayInputStream(new byte[]{1, 2, 3}));
    }

    private StreamInfo load(final PlayQueueItem item) throws Exception {
        return MediaItemTag.from(resolve(item).getMediaItem()).orElseThrow()
                .getMaybeStreamInfo().orElseThrow();
    }

    private ManagedMediaSource resolve(final PlayQueueItem item) throws Exception {
        final Method resolve = MediaSourceManager.class.getDeclaredMethod(
                "getLoadedMediaSource", PlayQueueItem.class);
        resolve.setAccessible(true);
        @SuppressWarnings("unchecked") final Single<ManagedMediaSource> result =
                (Single<ManagedMediaSource>) resolve.invoke(manager, item);
        return result.blockingGet();
    }

    private void assertFullOriginal(final StreamInfo selected) {
        assertEquals(ORIGINAL, selected.getUrl());
        assertEquals(StreamType.VIDEO_STREAM, selected.getStreamType());
        assertEquals(1, selected.getVideoStreams().size());
        assertEquals(1, selected.getAudioStreams().size());
        assertEquals(1, selected.getRelatedItems().size());
        assertSame("original playback metadata was replaced", onlineInfo, selected);
        assertSame(onlineInfo, InfoCache.getInstance().getFromKey(0, ORIGINAL,
                InfoCache.Type.STREAM));
        assertEquals(ORIGINAL, online.getUrl());
    }

    private static Uri uri(final String value) {
        final Uri uri = mock(Uri.class);
        when(uri.toString()).thenReturn(value);
        final int separator = value.indexOf(':');
        when(uri.getScheme()).thenReturn(separator > 0 ? value.substring(0, separator) : null);
        return uri;
    }

    private static void resetLibrary() throws Exception {
        final Field singleton = OfflineLibrary.class.getDeclaredField("instance");
        singleton.setAccessible(true);
        singleton.set(null, null);
    }
}
