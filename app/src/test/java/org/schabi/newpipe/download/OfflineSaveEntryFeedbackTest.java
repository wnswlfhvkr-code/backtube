package org.schabi.newpipe.download;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.fragments.detail.VideoDetailFragment;
import org.schabi.newpipe.offline.OfflineLibrary;
import org.schabi.newpipe.offline.OfflineStore;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.util.List;
import java.util.ArrayList;

import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.schedulers.TestScheduler;

/** Actual button handler and owned store. Only Android dialog/toast boundaries are replaced. */
public class OfflineSaveEntryFeedbackTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private final TestScheduler io = new TestScheduler();
    private OfflineLibrary library;
    private VideoDetailFragment fragment;
    private AppCompatActivity context;

    @Before public void setup() throws Exception {
        reset();
        RxJavaPlugins.setIoSchedulerHandler(scheduler -> io);
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(scheduler -> Schedulers.trampoline());
        RxAndroidPlugins.setMainThreadSchedulerHandler(scheduler -> Schedulers.trampoline());
        context = mock(AppCompatActivity.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getFilesDir()).thenReturn(temporary.getRoot());
        library = OfflineLibrary.get(context);
        fragment = mock(VideoDetailFragment.class, CALLS_REAL_METHODS);
        OfflineDuplicateFeedbackTest.set(fragment, "activity", context);
        when(fragment.isAdded()).thenReturn(true);
        when(fragment.getView()).thenReturn(mock(View.class));
        doReturn(mock(FragmentManager.class)).when(fragment).getParentFragmentManager();
    }

    @After public void cleanup() throws Exception {
        RxJavaPlugins.reset();
        RxAndroidPlugins.reset();
        reset();
    }

    @Test public void completeSingleKindStopsBeforeCreatingSelector() throws Exception {
        save("video/webm");
        assertEntry(false, true, 1);
    }

    @Test public void allKindsCompleteStopsBeforeCreatingSelector() throws Exception {
        save("video/webm");
        save("audio/mp4");
        assertEntry(true, true, 1);
    }

    @Test public void rapidDuplicateTapsCoalesceBeforeAnySelectorOrServiceCanStart()
            throws Exception {
        save("video/webm");
        assertEntry(false, true, 20);
    }

    @Test public void audioOnlySaveDoesNotBlockNewVideoKind() throws Exception {
        save("audio/mp4");
        assertEntry(true, false, 1);
    }

    @Test public void videoOnlySaveDoesNotBlockNewAudioKind() throws Exception {
        save("video/webm");
        assertEntry(true, false, 1);
    }

    @Test public void missingFileIsNotReportedAsAlreadySaved() throws Exception {
        final OfflineStore.Entry entry = save("video/webm");
        org.junit.Assert.assertTrue(library.store().file(entry).delete());
        assertEntry(false, false, 1);
        assertEquals(OfflineStore.State.FAILED, library.store().get(entry.id).state);
    }

    @Test public void failedAndImportingRecordsAreNotReportedAsComplete() throws Exception {
        final OfflineStore.Entry entry = library.store().create("Generated",
                OfflineDuplicateFeedbackTest.ORIGIN, 0, "file:///generated/pending", "video/webm");
        assertEntry(false, false, 1);
        library.store().fail(entry.id);
        assertEntry(false, false, 1);
    }

    private void assertEntry(final boolean withAudio, final boolean complete, final int taps)
            throws Exception {
        final StreamInfo info = new StreamInfo(0, OfflineDuplicateFeedbackTest.ORIGIN,
                OfflineDuplicateFeedbackTest.ORIGIN, StreamType.VIDEO_STREAM,
                OfflineDuplicateFeedbackTest.ORIGIN, "Generated", 0);
        info.setVideoStreams(List.of(new VideoStream.Builder().setId("generated-video")
                .setContent("https://example.invalid/generated.webm", true)
                .setMediaFormat(MediaFormat.WEBM).setResolution("180p")
                .setIsVideoOnly(false).build()));
        if (withAudio) {
            info.setAudioStreams(List.of(new AudioStream.Builder().setId("generated-audio")
                    .setContent("https://example.invalid/generated.m4a", true)
                    .setMediaFormat(MediaFormat.M4A).setAverageBitrate(96).build()));
        }
        OfflineDuplicateFeedbackTest.set(fragment, "currentInfo", info);
        final Toast toast = mock(Toast.class);
        final List<DownloadDialog> created = new ArrayList<>();
        try (MockedStatic<DownloadDialog> selectors = mockStatic(DownloadDialog.class, call -> {
            if (call.getMethod().getName().equals("forOffline")) {
                final DownloadDialog selector = mock(DownloadDialog.class);
                created.add(selector);
                return selector;
            }
            return call.callRealMethod();
        });
             MockedStatic<org.schabi.newpipe.error.ErrorUtil> errors =
                     mockStatic(org.schabi.newpipe.error.ErrorUtil.class);
             MockedStatic<Toast> toasts = mockStatic(Toast.class)) {
            toasts.when(() -> Toast.makeText(any(Context.class), anyInt(), eq(Toast.LENGTH_SHORT)))
                    .thenReturn(toast);
            for (int i = 0; i < taps; i++) {
                OfflineDuplicateFeedbackTest.call(fragment, "openOfflineSaveDialog");
            }
            io.triggerActions();
            verify(toast, complete ? times(1) : never()).show();
            errors.verifyNoInteractions();
            assertEquals(complete ? 0 : 1, created.size());
        }
    }

    private OfflineStore.Entry save(final String mime) throws Exception {
        final OfflineStore.Entry entry = library.store().create("Generated",
                OfflineDuplicateFeedbackTest.ORIGIN, 0, "file:///generated/" + mime, mime);
        library.store().copy(entry.id, new ByteArrayInputStream(new byte[]{1, 2, 3}));
        return library.store().get(entry.id);
    }

    private static void reset() throws Exception {
        final Field field = OfflineLibrary.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, null);
    }
}
