package org.schabi.newpipe.download;

import android.content.Context;
import android.net.Uri;
import android.text.Editable;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.collection.SparseArrayCompat;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.schabi.newpipe.R;
import org.schabi.newpipe.databinding.DownloadDialogBinding;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.offline.OfflineLibrary;
import org.schabi.newpipe.offline.OfflineLibraryActivity;
import org.schabi.newpipe.offline.OfflineStore;
import org.schabi.newpipe.streams.io.StoredFileHelper;
import org.schabi.newpipe.util.FilenameUtils;
import org.schabi.newpipe.util.StreamItemAdapter;
import org.schabi.newpipe.views.NewPipeEditText;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import us.shandian.giga.service.DownloadManager;
import us.shandian.giga.service.DownloadManagerService;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyChar;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** Actual selector, registration, writable boundary and dispatch; generated metadata only. */
public class OfflineDownloadRetryHandoffTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private static final String ORIGINAL = "https://www.youtube.com/watch?v=abcdefghijk";

    @After public void reset() throws Exception {
        final Field field = OfflineLibrary.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, null);
    }

    @Test public void failedImportCanUseSelectedNetworkStreamOnTheSameId() throws Exception {
        assertHandoff(OfflineStore.State.FAILED);
    }

    @Test public void interruptedImportCanUseSelectedNetworkStreamOnTheSameId() throws Exception {
        assertHandoff(OfflineStore.State.INTERRUPTED);
    }

    @Test public void queuedCopyOwnerBlocksHandoffUntilItReleasesTheEntry() throws Exception {
        assertHandoff(OfflineStore.State.INTERRUPTED, "queued");
    }

    @Test public void runningCopyOwnerBlocksHandoffUntilItReleasesTheEntry() throws Exception {
        assertHandoff(OfflineStore.State.INTERRUPTED, "running");
    }

    private void assertHandoff(final OfflineStore.State state) throws Exception {
        assertHandoff(state, "none");
    }

    private void assertHandoff(final OfflineStore.State state, final String copyOwner)
            throws Exception {
        reset();
        final Context context = mockContext();
        final OfflineLibrary library = OfflineLibrary.get(context);
        final var entry = library.store().create("Generated", ORIGINAL, 0,
                "file:///generated/unavailable.mp4", "video/mp4");
        if (state == OfflineStore.State.FAILED) {
            library.store().fail(entry.id);
        } else {
            library.store().interrupt(entry.id);
        }
        final Field copier = OfflineLibrary.class.getDeclaredField("copier");
        copier.setAccessible(true);
        ((ExecutorService) copier.get(library)).shutdown();
        final ExecutorService executor = mock(ExecutorService.class);
        copier.set(library, executor);
        final List<Runnable> localJobs = new ArrayList<>();
        doAnswer(call -> {
            localJobs.add(call.getArgument(0));
            return null;
        }).when(executor).execute(any(Runnable.class));
        final Uri uri = mock(Uri.class);
        when(uri.getScheme()).thenReturn("file");
        final VideoStream stream = generatedStream();
        final DownloadDialog dialog = mock(DownloadDialog.class, CALLS_REAL_METHODS);
        when(dialog.getContext()).thenReturn(context);
        doNothing().when(dialog).dismiss();
        set(dialog, "context", context);
        set(dialog, "saveOffline", true);
        set(dialog, "downloadManager", mock(DownloadManager.class));
        final StreamInfo info = mock(StreamInfo.class);
        when(info.getName()).thenReturn("Generated");
        when(info.getUrl()).thenReturn(ORIGINAL);
        when(info.getServiceId()).thenReturn(0);
        set(dialog, "currentInfo", info);
        final DownloadDialogBinding binding = mock(DownloadDialogBinding.class);
        final NewPipeEditText name = mock(NewPipeEditText.class);
        final Editable text = mock(Editable.class);
        when(text.toString()).thenReturn("Generated");
        when(name.getText()).thenReturn(text);
        set(binding, "fileName", name);
        set(binding, "threads", mock(SeekBar.class));
        final RadioGroup group = mock(RadioGroup.class);
        when(group.getCheckedRadioButtonId()).thenReturn(R.id.video_button);
        set(binding, "videoAudioGroup", group);
        set(dialog, "dialogBinding", binding);
        final StreamItemAdapter<VideoStream, AudioStream> adapter = mock(StreamItemAdapter.class);
        when(adapter.getItem(0)).thenReturn(stream);
        when(adapter.getAllSecondary()).thenReturn(new SparseArrayCompat<>());
        set(dialog, "videoStreamsAdapter", adapter);
        final StreamItemAdapter.StreamInfoWrapper<VideoStream> wrapper =
                mock(StreamItemAdapter.StreamInfoWrapper.class);
        when(wrapper.getStreamsList()).thenReturn(List.of(stream));
        set(dialog, "wrappedVideoStreams", wrapper);
        final AtomicInteger dispatched = new AtomicInteger();
        try (MockedStatic<FilenameUtils> names = mockStatic(FilenameUtils.class);
             MockedStatic<Uri> uris = mockStatic(Uri.class);
             MockedStatic<Toast> toasts = mockStatic(Toast.class);
             MockedStatic<OfflineLibraryActivity> shelves =
                     mockStatic(OfflineLibraryActivity.class);
             MockedStatic<DownloadManagerService> downloads =
                     mockStatic(DownloadManagerService.class);
             MockedStatic<DownloadNetworkPolicy> network =
                     mockStatic(DownloadNetworkPolicy.class);
             MockedConstruction<StoredFileHelper> files = mockConstruction(StoredFileHelper.class,
                     (file, setup) -> {
                         when(file.getTag()).thenReturn("offline:" + entry.id);
                         when(file.canWrite()).thenReturn(true);
                     })) {
            network.when(() -> DownloadNetworkPolicy.current(context))
                    .thenReturn(DownloadNetworkPolicy.Type.WIFI);
            names.when(() -> FilenameUtils.createFilename(context, "Generated"))
                    .thenReturn("Generated");
            uris.when(() -> Uri.parse(entry.source)).thenReturn(uri);
            uris.when(() -> Uri.fromFile(any(java.io.File.class))).thenReturn(uri);
            toasts.when(() -> Toast.makeText(any(Context.class), any(CharSequence.class), anyInt()))
                    .thenReturn(mock(Toast.class));
            downloads.when(() -> DownloadManagerService.startMission(any(), any(), any(), anyChar(),
                    anyInt(), any(), any(), any(), anyLong(), any(), anyBoolean()))
                    .thenAnswer(call -> {
                        dispatched.incrementAndGet();
                        return null;
                    });
            final var prepare = DownloadDialog.class.getDeclaredMethod("prepareSelectedDownload");
            prepare.setAccessible(true);
            if (!copyOwner.equals("none")) {
                final var entered = new java.util.concurrent.CountDownLatch(1);
                final var release = new java.util.concurrent.CountDownLatch(1);
                final android.content.ContentResolver resolver =
                        mock(android.content.ContentResolver.class);
                when(context.getContentResolver()).thenReturn(resolver);
                when(resolver.openInputStream(uri)).thenReturn(new java.io.InputStream() {
                    @Override public int read() {
                        return -1;
                    }

                    @Override public int read(final byte[] data, final int offset,
                                              final int length) {
                        entered.countDown();
                        boolean released = false;
                        while (!released) {
                            try {
                                release.await();
                                released = true;
                            } catch (final InterruptedException ignored) {
                                // Keep ownership until the generated source finishes reading.
                            }
                        }
                        data[offset] = 1;
                        return 1;
                    }
                });
                library.retry(entry.id);
                assertEquals(1, localJobs.size());
                final Thread worker = copyOwner.equals("running")
                        ? new Thread(localJobs.get(0)) : null;
                if (worker != null) {
                    worker.start();
                    org.junit.Assert.assertTrue(entered.await(2,
                            java.util.concurrent.TimeUnit.SECONDS));
                }
                library.store().interrupt(entry.id);
                try {
                    prepare.invoke(dialog);
                    assertEquals("live local owner must exclude network dispatch", 0,
                            dispatched.get());
                    assertEquals(entry.source, library.store().get(entry.id).source);
                } finally {
                    release.countDown();
                    if (worker != null) {
                        worker.join(2000);
                        org.junit.Assert.assertFalse(worker.isAlive());
                    } else {
                        localJobs.get(0).run();
                    }
                }
                localJobs.clear();
            }
            prepare.invoke(dialog);
            assertEquals("the selected network stream must dispatch", 1, dispatched.get());
            assertEquals(0, localJobs.size());
            assertEquals(entry.id, library.store().list().get(0).id);
            assertEquals(1, library.store().list().size());
            assertEquals("giga", library.store().get(entry.id).source);
            assertEquals(OfflineStore.State.DOWNLOADING, library.store().get(entry.id).state);
        }
    }

    private Context mockContext() {
        final Context context = mock(Context.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getFilesDir()).thenReturn(temporary.getRoot());
        when(context.getString(anyInt())).thenReturn("Generated");
        final android.content.res.Resources resources = mock(android.content.res.Resources.class);
        when(context.getResources()).thenReturn(resources);
        when(resources.getString(anyInt())).thenReturn("Generated");
        return context;
    }

    private static VideoStream generatedStream() {
        return new VideoStream.Builder().setId("generated")
                .setContent("https://example.invalid/generated.mp4", true)
                .setMediaFormat(MediaFormat.MPEG_4).setResolution("720p")
                .setIsVideoOnly(false).build();
    }

    private static void set(final Object object, final String name, final Object value)
            throws Exception {
        final Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }
}
