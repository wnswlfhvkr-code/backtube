package org.schabi.newpipe.info_list.dialog;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
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
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;
import org.schabi.newpipe.download.DownloadDialog;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.fragments.detail.VideoDetailFragment;
import org.schabi.newpipe.offline.OfflineLibrary;
import org.schabi.newpipe.offline.OfflineStore;
import org.schabi.newpipe.util.SparseItemUtil;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;

import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.schedulers.TestScheduler;

/** The menu uses the selected stream and the real completed-save lookup. */
public class OnlineVideoOfflineSaveActionTest {
    private static final String URL = "https://example.invalid/watch?v=selected-video";

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private final TestScheduler io = new TestScheduler();
    private AppCompatActivity activity;
    private Fragment fragment;
    private FragmentManager manager;
    private StreamInfo info;

    @Before public void setup() throws Exception {
        resetLibrary();
        RxJavaPlugins.setIoSchedulerHandler(scheduler -> io);
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(scheduler -> Schedulers.trampoline());
        RxAndroidPlugins.setMainThreadSchedulerHandler(scheduler -> Schedulers.trampoline());
        activity = mock(AppCompatActivity.class);
        when(activity.getApplicationContext()).thenReturn(activity);
        when(activity.getFilesDir()).thenReturn(temporary.getRoot());
        fragment = mock(Fragment.class);
        manager = mock(FragmentManager.class);
        when(fragment.getContext()).thenReturn(activity);
        when(fragment.requireContext()).thenReturn(activity);
        when(fragment.requireActivity()).thenReturn(activity);
        when(fragment.getView()).thenReturn(mock(View.class));
        when(fragment.getChildFragmentManager()).thenReturn(manager);
        when(fragment.isAdded()).thenReturn(true);
        info = new StreamInfo(0, URL, URL, StreamType.VIDEO_STREAM,
                URL, "Selected video", 0);
        info.setVideoStreams(List.of(new VideoStream.Builder().setId("selected-video")
                .setContent("https://example.invalid/video.webm", true)
                .setMediaFormat(MediaFormat.WEBM).setResolution("180p")
                .setIsVideoOnly(false).build()));
    }

    @After public void cleanup() throws Exception {
        RxJavaPlugins.reset();
        RxAndroidPlugins.reset();
        resetLibrary();
    }

    @Test public void selectedVideoOpensOfflineSelectorWithItsMetadata() {
        final DownloadDialog dialog = mock(DownloadDialog.class);
        try (MockedStatic<SparseItemUtil> metadata = metadata();
             MockedStatic<DownloadDialog> downloads = mockStatic(DownloadDialog.class,
                     CALLS_REAL_METHODS)) {
            downloads.when(() -> DownloadDialog.forOffline(activity, info)).thenReturn(dialog);

            action().onClick(fragment, item());
            metadata.verify(() -> SparseItemUtil.fetchStreamInfoAndSaveToDatabase(
                    eq(activity), eq(0), eq(URL), any()));
            io.triggerActions();

            downloads.verify(() -> DownloadDialog.forOffline(activity, info));
            verify(dialog).showNow(manager, "offline_save");
        }
    }

    @Test public void completedVideoShowsExistingToastWithoutSecondSave() throws Exception {
        final OfflineStore store = OfflineLibrary.get(activity).store();
        final OfflineStore.Entry saved = store.create("Selected video", URL, 0,
                "file:///generated/video.webm", "video/webm");
        store.copy(saved.id, new ByteArrayInputStream(new byte[]{1, 2, 3}));
        final Toast toast = mock(Toast.class);
        try (MockedStatic<SparseItemUtil> metadata = metadata();
             MockedStatic<DownloadDialog> downloads = mockStatic(DownloadDialog.class,
                     CALLS_REAL_METHODS);
             MockedStatic<Toast> toasts = mockStatic(Toast.class)) {
            toasts.when(() -> Toast.makeText(any(Context.class), anyInt(), eq(Toast.LENGTH_SHORT)))
                    .thenReturn(toast);

            action().onClick(fragment, item());
            io.triggerActions();

            verify(toast).show();
            downloads.verify(() -> DownloadDialog.forOffline(any(), any()), never());
            assertEquals(1, store.list().size());
            assertEquals(OfflineStore.State.READY, store.get(saved.id).state);
        }
    }

    @Test public void savedAudioDoesNotBlockSelectedVideoKind() throws Exception {
        info.setAudioStreams(List.of(new AudioStream.Builder().setId("selected-audio")
                .setContent("https://example.invalid/audio.m4a", true)
                .setMediaFormat(MediaFormat.M4A).setAverageBitrate(96).build()));
        final OfflineStore store = OfflineLibrary.get(activity).store();
        final OfflineStore.Entry saved = store.create("Selected video", URL, 0,
                "file:///generated/audio.m4a", "audio/mp4");
        store.copy(saved.id, new ByteArrayInputStream(new byte[]{1, 2, 3}));
        final DownloadDialog dialog = mock(DownloadDialog.class);
        try (MockedStatic<SparseItemUtil> metadata = metadata();
             MockedStatic<DownloadDialog> downloads = mockStatic(DownloadDialog.class,
                     CALLS_REAL_METHODS)) {
            downloads.when(() -> DownloadDialog.forOffline(activity, info)).thenReturn(dialog);
            action().onClick(fragment, item());
            io.triggerActions();
            verify(dialog).showNow(manager, "offline_save");
            assertEquals(1, store.list().size());
        }
    }

    @Test public void repeatedSelectionShowsOnlyOneOfflineDialog() {
        final DownloadDialog dialog = mock(DownloadDialog.class);
        final AtomicBoolean dialogOpen = new AtomicBoolean();
        when(manager.findFragmentByTag("offline_save")).thenAnswer(call ->
                dialogOpen.get() ? mock(Fragment.class) : null);
        doAnswer(call -> {
            dialogOpen.set(true);
            return null;
        }).when(dialog).showNow(manager, "offline_save");
        try (MockedStatic<SparseItemUtil> metadata = metadata();
             MockedStatic<DownloadDialog> downloads = mockStatic(DownloadDialog.class,
                     CALLS_REAL_METHODS)) {
            downloads.when(() -> DownloadDialog.forOffline(activity, info)).thenReturn(dialog);
            action().onClick(fragment, item());
            action().onClick(fragment, item());
            io.triggerActions();
            verify(dialog, times(1)).showNow(manager, "offline_save");
        }
    }

    @Test public void detachedFragmentDoesNotOpenOfflineSelectorAfterMetadataLoad() {
        try (MockedStatic<SparseItemUtil> metadata = metadata();
             MockedStatic<DownloadDialog> downloads = mockStatic(DownloadDialog.class,
                     CALLS_REAL_METHODS)) {
            action().onClick(fragment, item());
            when(fragment.isAdded()).thenReturn(false);
            io.triggerActions();
            downloads.verify(() -> DownloadDialog.forOffline(any(), any()), never());
        }
    }

    @Test public void selectingAnotherVideoDropsLateSaveDialogOnSameView() throws Exception {
        final VideoDetailFragment detail = detail();
        final Field generation = VideoDetailFragment.class.getDeclaredField(
                "infoUpdateGeneration");
        generation.setAccessible(true);
        generation.setLong(detail, 1);
        try (MockedStatic<SparseItemUtil> metadata = metadata();
             MockedStatic<DownloadDialog> downloads = mockStatic(DownloadDialog.class,
                     CALLS_REAL_METHODS)) {
            action().onClick(detail, item());
            // selectAndLoadVideo increments this while retaining the same Fragment view.
            generation.setLong(detail, 2);
            io.triggerActions();
            downloads.verify(() -> DownloadDialog.forOffline(any(), any()), never());
        }
    }

    @Test public void playerNextVideoDropsLateSaveDialogWithoutViewRecreation() throws Exception {
        final VideoDetailFragment detail = detail();
        final Field url = VideoDetailFragment.class.getDeclaredField("url");
        url.setAccessible(true);
        url.set(detail, "https://example.invalid/watch?v=first");
        try (MockedStatic<SparseItemUtil> metadata = metadata();
             MockedStatic<DownloadDialog> downloads = mockStatic(DownloadDialog.class,
                     CALLS_REAL_METHODS)) {
            action().onClick(detail, item());
            // onMetadataUpdate sets a new URL for next/previous within this same View.
            url.set(detail, "https://example.invalid/watch?v=next");
            io.triggerActions();
            downloads.verify(() -> DownloadDialog.forOffline(any(), any()), never());
        }
    }

    private VideoDetailFragment detail() {
        final VideoDetailFragment detail = mock(VideoDetailFragment.class, CALLS_REAL_METHODS);
        doReturn(activity).when(detail).getContext();
        doReturn(activity).when(detail).requireContext();
        doReturn(mock(View.class)).when(detail).getView();
        doReturn(manager).when(detail).getChildFragmentManager();
        doReturn(true).when(detail).isAdded();
        return detail;
    }

    private MockedStatic<SparseItemUtil> metadata() {
        final MockedStatic<SparseItemUtil> metadata = mockStatic(SparseItemUtil.class);
        metadata.when(() -> SparseItemUtil.fetchStreamInfoAndSaveToDatabase(
                eq(activity), eq(0), eq(URL), any())).thenAnswer(call -> {
                    final Consumer<StreamInfo> callback = call.getArgument(3);
                    callback.accept(info);
                    return null;
                });
        return metadata;
    }

    private static StreamInfoItem item() {
        return new StreamInfoItem(0, URL, "Selected video", StreamType.VIDEO_STREAM);
    }

    private static StreamDialogEntry.StreamDialogEntryAction action() {
        return StreamDialogDefaultEntry.valueOf("OFFLINE_SAVE").action;
    }

    private static void resetLibrary() throws Exception {
        final Field field = OfflineLibrary.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, null);
    }
}
