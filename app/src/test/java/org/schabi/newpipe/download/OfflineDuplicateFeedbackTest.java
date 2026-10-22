package org.schabi.newpipe.download;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.Uri;
import android.text.Editable;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.schabi.newpipe.R;
import org.schabi.newpipe.databinding.DownloadDialogBinding;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.offline.OfflineLibrary;
import org.schabi.newpipe.offline.OfflineLibraryActivity;
import org.schabi.newpipe.offline.OfflineStore;
import org.schabi.newpipe.offline.OfflineThumbnails;
import org.schabi.newpipe.streams.io.StoredFileHelper;
import org.schabi.newpipe.util.AudioTrackAdapter.AudioTracksWrapper;
import org.schabi.newpipe.util.FilenameUtils;
import org.schabi.newpipe.util.StreamItemAdapter;
import org.schabi.newpipe.util.StreamItemAdapter.StreamInfoWrapper;
import org.schabi.newpipe.views.NewPipeEditText;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.schedulers.TestScheduler;
import us.shandian.giga.service.DownloadManager;

/** Real registration and selected-save boundary; views, transport and navigation are boundaries. */
public class OfflineDuplicateFeedbackTest {
    static final String ORIGIN = "https://www.youtube.com/watch?v=abcdefghijk";
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private final TestScheduler io = new TestScheduler();
    private Context context;
    private OfflineLibrary library;
    private DownloadDialog dialog;
    private StreamInfoWrapper<VideoStream> videos;
    private StreamInfoWrapper<AudioStream> audios;
    private StreamItemAdapter<VideoStream, AudioStream> videoAdapter;

    @Before public void setup() throws Exception {
        reset();
        context = mock(AppCompatActivity.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getFilesDir()).thenReturn(temporary.getRoot());
        when(context.getString(anyInt())).thenReturn("Generated");
        library = OfflineLibrary.get(context);
        RxJavaPlugins.setIoSchedulerHandler(scheduler -> io);
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(scheduler -> Schedulers.trampoline());
        RxAndroidPlugins.setMainThreadSchedulerHandler(scheduler -> Schedulers.trampoline());
        selectedVideo();
    }

    @After public void cleanup() throws Exception {
        RxJavaPlugins.reset();
        RxAndroidPlugins.reset();
        reset();
    }

    @Test public void readySelectionOnlyShowsShortFeedbackWithoutFilesOrShelf() throws Exception {
        assertOnlyFeedback(save("video/webm"), false);
    }

    @Test public void anotherRequestCompletingAfterCheckStillSkipsFilesAndShelf() throws Exception {
        final OfflineStore store = library.store();
        final OfflineStore.Entry pending = store.beginDownload(
                "Generated", ORIGIN, 0, "video/webm");
        java.nio.file.Files.write(store.file(pending).toPath(), new byte[]{1, 2, 3});
        library = spy(library);
        set(OfflineLibrary.class, "instance", library);
        doAnswer(invocation -> {
            final java.util.concurrent.ExecutorService worker =
                    java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                worker.submit(() -> {
                    store.finishDownload(pending.id);
                    return null;
                }).get(5, java.util.concurrent.TimeUnit.SECONDS);
            } finally {
                worker.shutdownNow();
            }
            return invocation.callRealMethod();
        }).when(library).beginDownload(any(), any(), anyInt(), any(),
                org.mockito.ArgumentMatchers.anyLong());
        assertOnlyFeedback(pending, true);
    }

    private void assertOnlyFeedback(final OfflineStore.Entry ready, final boolean raced)
            throws Exception {
        final byte[] before = java.nio.file.Files.readAllBytes(
                library.store().file(ready).toPath());
        final Toast toast = mock(Toast.class);
        try (MockedStatic<Toast> toasts = mockStatic(Toast.class);
             MockedStatic<OfflineLibraryActivity> shelves =
                     mockStatic(OfflineLibraryActivity.class);
             MockedStatic<OfflineThumbnails> covers = mockStatic(OfflineThumbnails.class);
             MockedStatic<FilenameUtils> names = mockStatic(FilenameUtils.class);
             MockedStatic<Uri> uris = mockStatic(Uri.class);
             MockedConstruction<StoredFileHelper> files = mockConstruction(StoredFileHelper.class,
                     (file, setup) -> when(file.getTag()).thenReturn("offline:" + ready.id))) {
            names.when(() -> FilenameUtils.createFilename(context, "Generated"))
                    .thenReturn("Generated");
            uris.when(() -> Uri.fromFile(any(java.io.File.class))).thenReturn(mock(Uri.class));
            toasts.when(() -> Toast.makeText(any(Context.class), anyInt(), eq(Toast.LENGTH_SHORT)))
                    .thenReturn(toast);
            call(dialog, "prepareSelectedDownload");
            verify(toast).show();
            shelves.verifyNoInteractions();
            if (!raced) {
                names.verifyNoInteractions();
            }
            covers.verifyNoInteractions();
            assertEquals(0, files.constructed().size());
            assertEquals(1, library.store().list().size());
            org.junit.Assert.assertArrayEquals(before,
                    java.nio.file.Files.readAllBytes(library.store().file(ready).toPath()));
        }
    }

    @Test public void readySelectedKindStartsNoHeaderLookupIncludingOtherKind() throws Exception {
        save("video/webm");
        final List<Object> fetched = new ArrayList<>();
        try (MockedStatic<StreamInfoWrapper> transport = mockStatic(StreamInfoWrapper.class)) {
            transport.when(() -> StreamInfoWrapper.fetchMoreInfoForWrapper(any()))
                    .thenAnswer(call -> {
                        fetched.add(call.getArgument(0));
                        return Single.never();
                    });
            call(dialog, "fetchStreamsSize");
            io.triggerActions();
            assertEquals("completed video must not query video or unselected audio headers",
                    List.of(), fetched);
        }
    }

    @Test public void unsavedVideoWithSavedAudioOnlyFetchesTheRequestedVideo() throws Exception {
        save("audio/mp4");
        final List<Object> fetched = new ArrayList<>();
        try (MockedStatic<StreamInfoWrapper> transport = mockStatic(StreamInfoWrapper.class)) {
            transport.when(() -> StreamInfoWrapper.fetchMoreInfoForWrapper(any()))
                    .thenAnswer(call -> {
                        fetched.add(call.getArgument(0));
                        return Single.never();
                    });
            call(dialog, "fetchStreamsSize");
            io.triggerActions();
            assertEquals(List.of(videos), fetched);
        }
    }

    @Test public void unsavedVideoOnlyAlsoFetchesRequiredAudioDespiteSavedAudio() throws Exception {
        save("audio/mp4");
        final androidx.collection.SparseArrayCompat<
                org.schabi.newpipe.util.SecondaryStreamHelper<AudioStream>> secondary =
                new androidx.collection.SparseArrayCompat<>();
        secondary.append(0, mock(org.schabi.newpipe.util.SecondaryStreamHelper.class));
        when(videoAdapter.getAllSecondary()).thenReturn(secondary);
        final List<Object> fetched = new ArrayList<>();
        try (MockedStatic<StreamInfoWrapper> transport = mockStatic(StreamInfoWrapper.class)) {
            transport.when(() -> StreamInfoWrapper.fetchMoreInfoForWrapper(any()))
                    .thenAnswer(call -> {
                        fetched.add(call.getArgument(0));
                        return call.getArgument(0) == videos ? Single.just(true) : Single.never();
                    });
            call(dialog, "fetchStreamsSize");
            io.triggerActions();
            assertEquals(List.of(videos, audios), fetched);
        }
    }

    private OfflineStore.Entry save(final String mime) throws Exception {
        final OfflineStore.Entry entry = library.store().create("Generated", ORIGIN, 0,
                "file:///generated/" + mime.replace('/', '-'), mime);
        library.store().copy(entry.id, new ByteArrayInputStream(new byte[]{1, 2, 3}));
        return library.store().get(entry.id);
    }

    @SuppressWarnings("unchecked")
    private void selectedVideo() throws Exception {
        dialog = mock(DownloadDialog.class, CALLS_REAL_METHODS);
        doReturn("Generated").when(dialog).getString(anyInt());
        doNothing().when(dialog).dismiss();
        set(dialog, "disposables", new io.reactivex.rxjava3.disposables.CompositeDisposable());
        set(dialog, "context", context);
        set(dialog, "saveOffline", true);
        set(dialog, "downloadManager", mock(DownloadManager.class));
        final android.os.Bundle arguments = mock(android.os.Bundle.class);
        when(arguments.getBoolean("offline_save_only")).thenReturn(true);
        when(dialog.getArguments()).thenReturn(arguments);
        final StreamInfo info = new StreamInfo(0, ORIGIN, ORIGIN, StreamType.VIDEO_STREAM,
                ORIGIN, "Generated", 0);
        set(dialog, "currentInfo", info);
        final DownloadDialogBinding binding = mock(DownloadDialogBinding.class);
        final NewPipeEditText name = mock(NewPipeEditText.class);
        final Editable text = mock(Editable.class);
        when(text.toString()).thenReturn("Generated");
        when(name.getText()).thenReturn(text);
        set(binding, "fileName", name);
        final RadioGroup group = mock(RadioGroup.class);
        when(group.getCheckedRadioButtonId()).thenReturn(R.id.video_button);
        set(binding, "videoAudioGroup", group);
        set(dialog, "dialogBinding", binding);
        final VideoStream video = new VideoStream.Builder().setId("generated-video")
                .setContent("https://example.invalid/generated.webm", true)
                .setMediaFormat(MediaFormat.WEBM).setResolution("180p")
                .setIsVideoOnly(false).build();
        final StreamItemAdapter<VideoStream, AudioStream> adapter = mock(StreamItemAdapter.class);
        when(adapter.getItem(0)).thenReturn(video);
        set(dialog, "videoStreamsAdapter", adapter);
        videoAdapter = adapter;
        videos = mock(StreamInfoWrapper.class);
        when(videos.getStreamsList()).thenReturn(List.of(video));
        set(dialog, "wrappedVideoStreams", videos);
        audios = mock(StreamInfoWrapper.class);
        set(dialog, "wrappedSubtitleStreams", mock(StreamInfoWrapper.class));
        final AudioTracksWrapper tracks = mock(AudioTracksWrapper.class);
        when(tracks.size()).thenReturn(1);
        when(tracks.getTracksList()).thenReturn(List.of(audios));
        set(dialog, "wrappedAudioTracks", tracks);
        final StreamItemAdapter<AudioStream, Stream> audioAdapter = mock(StreamItemAdapter.class);
        set(dialog, "audioStreamsAdapter", audioAdapter);
    }

    static void call(final Object target, final String name) throws Exception {
        final Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(target);
    }

    static void set(final Object target, final String name, final Object value) throws Exception {
        Class<?> type = target instanceof Class ? (Class<?>) target : target.getClass();
        while (type != null) {
            try {
                final Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target instanceof Class ? null : target, value);
                return;
            } catch (final NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void reset() throws Exception {
        final Field field = OfflineLibrary.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, null);
    }
}
