package org.schabi.newpipe.download;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.collection.SparseArrayCompat;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.schabi.newpipe.R;
import org.schabi.newpipe.databinding.DownloadDialogBinding;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.streams.io.StoredFileHelper;
import org.schabi.newpipe.util.SecondaryStreamHelper;
import org.schabi.newpipe.util.StreamItemAdapter;
import org.schabi.newpipe.util.StreamItemAdapter.StreamInfoWrapper;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import us.shandian.giga.postprocessing.Postprocessing;
import us.shandian.giga.service.DownloadManagerService;

/** Exercises the real dispatch boundary without opening a network or writable user file. */
public class OfflineVideoDownloadTest {
    @Test
    public void videoOnlySelectionDispatchesVideoAndAudioToMp4Muxer() throws Exception {
        assertDispatch(true);
    }

    @Test
    public void audioSelectionDispatchesOnlyAudioToM4aProcessor() throws Exception {
        assertDispatch(false);
    }

    @SuppressWarnings("unchecked")
    private static void assertDispatch(final boolean videoSelected) throws Exception {
        final DownloadDialog dialog = mock(DownloadDialog.class, CALLS_REAL_METHODS);
        final Context context = mock(Context.class);
        set(dialog, "context", context);
        doNothing().when(dialog).dismiss();
        doReturn("Started").when(dialog).getString(R.string.download_has_started);
        final DownloadDialogBinding binding = mock(DownloadDialogBinding.class);
        set(binding, "threads", mock(SeekBar.class));
        final RadioGroup group = mock(RadioGroup.class);
        when(group.getCheckedRadioButtonId()).thenReturn(videoSelected
                ? R.id.video_button : R.id.audio_button);
        set(binding, "videoAudioGroup", group);
        set(dialog, "dialogBinding", binding);
        final AudioStream audio = new AudioStream.Builder().setId("audio")
                .setContent("https://example.invalid/generated-audio.m4a", true)
                .setMediaFormat(MediaFormat.M4A).setAverageBitrate(96).build();
        final VideoStream video = new VideoStream.Builder().setId("video")
                .setContent("https://example.invalid/generated-video.mp4", true)
                .setMediaFormat(MediaFormat.MPEG_4).setResolution("180p")
                .setIsVideoOnly(true).build();
        final StreamItemAdapter<AudioStream, Stream> audioAdapter =
                mock(StreamItemAdapter.class);
        when(audioAdapter.getItem(0)).thenReturn(audio);
        set(dialog, "audioStreamsAdapter", audioAdapter);
        final StreamItemAdapter<VideoStream, AudioStream> videoAdapter =
                mock(StreamItemAdapter.class);
        when(videoAdapter.getItem(0)).thenReturn(video);
        final StreamInfoWrapper<VideoStream> wrapper = mock(StreamInfoWrapper.class);
        when(wrapper.getStreamsList()).thenReturn(List.of(video));
        set(dialog, "wrappedVideoStreams", wrapper);
        final SecondaryStreamHelper<AudioStream> secondary = mock(SecondaryStreamHelper.class);
        when(secondary.getStream()).thenReturn(audio);
        final SparseArrayCompat<SecondaryStreamHelper<AudioStream>> paired =
                new SparseArrayCompat<>();
        paired.put(0, secondary);
        when(videoAdapter.getAllSecondary()).thenReturn(paired);
        set(dialog, "videoStreamsAdapter", videoAdapter);
        final StoredFileHelper storage = mock(StoredFileHelper.class);
        when(storage.canWrite()).thenReturn(true);
        final List<Object[]> dispatched = new ArrayList<>();
        try (MockedStatic<DownloadManagerService> missions = mockStatic(
                DownloadManagerService.class,
                call -> {
                    if (call.getMethod().getName().equals("startMission")) {
                        dispatched.add(call.getArguments());
                    }
                    return null;
                }); MockedStatic<Toast> toasts = mockStatic(Toast.class);
             MockedStatic<DownloadNetworkPolicy> network =
                     mockStatic(DownloadNetworkPolicy.class)) {
            network.when(() -> DownloadNetworkPolicy.current(context))
                    .thenReturn(DownloadNetworkPolicy.Type.WIFI);
            toasts.when(() -> Toast.makeText(org.mockito.ArgumentMatchers.any(Context.class),
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyInt()))
                    .thenReturn(mock(Toast.class));
            final var proceed = DownloadDialog.class.getDeclaredMethod("continueSelectedDownload",
                    StoredFileHelper.class);
            proceed.setAccessible(true);
            proceed.invoke(dialog, storage);
            assertEquals(1, dispatched.size());
            final Object[] request = dispatched.get(0);
            assertArrayEquals(videoSelected
                    ? new String[]{video.getContent(), audio.getContent()}
                    : new String[]{audio.getContent()}, (String[]) request[1]);
            assertEquals(videoSelected ? 'v' : 'a', request[3]);
            assertEquals(videoSelected ? Postprocessing.ALGORITHM_MP4_FROM_DASH_MUXER
                    : Postprocessing.ALGORITHM_M4A_NO_DASH, request[6]);
            assertEquals(videoSelected ? 2 : 1, ((List<?>) request[9]).size());
        }
    }

    private static void set(final Object object, final String name, final Object value)
            throws Exception {
        final Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }
}
