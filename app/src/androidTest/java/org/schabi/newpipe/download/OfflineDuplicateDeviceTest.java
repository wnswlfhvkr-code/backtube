package org.schabi.newpipe.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.view.View;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.Toolbar;
import androidx.preference.PreferenceManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.MainActivity;
import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.fragments.detail.VideoDetailFragment;
import org.schabi.newpipe.offline.OfflineLibrary;
import org.schabi.newpipe.offline.OfflineStore;
import org.schabi.newpipe.player.SavedPlaybackTestSupport.RejectNetwork;
import org.schabi.newpipe.util.InfoCache;
import org.schabi.newpipe.util.NavigationHelper;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Generated stream metadata and owned bytes. Real button, selector and Android Toast. */
@RunWith(AndroidJUnit4.class)
public class OfflineDuplicateDeviceTest {
    private static final String ORIGIN = "https://www.youtube.com/watch?v=generated01";
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private OfflineLibrary library;
    private final List<String> created = new ArrayList<>();
    private MainActivity activity;

    @Before public void setup() throws Exception {
        library = OfflineLibrary.get(context);
    }

    @After public void cleanup() throws Exception {
        main(() -> {
            if (activity != null) {
                activity.finish();
            }
            return null;
        });
        InfoCache.getInstance().removeInfo(0, ORIGIN, InfoCache.Type.STREAM);
        for (final String id : created) {
            library.store().delete(id);
        }
    }

    @Test public void completedVideoRapidTapsShowShortToastWithoutSelectorOrNewSave()
            throws Exception {
        final OfflineStore.Entry saved = save("video/webm");
        final byte[] before = mediaBytes(saved);
        final VideoDetailFragment fragment = open(false);
        try (RejectNetwork network = new RejectNetwork()) {
            main(() -> {
                final View button = fragment.requireView()
                        .findViewById(R.id.detail_controls_offline_save);
                assertTrue(button.isShown());
                for (int tap = 0; tap < 20; tap++) {
                    assertTrue(button.performClick());
                }
                return null;
            });
            assertTrue(waitFor(() -> main(() -> field(fragment, "alreadySavedToast") != null)));
            main(() -> {
                assertShortToast((Toast) field(fragment, "alreadySavedToast"));
                assertNull(activity.getSupportFragmentManager().findFragmentByTag("offline_save"));
                return null;
            });
            assertEquals(0, network.requests.get());
        }
        assertEquals(1, library.store().list().size());
        org.junit.Assert.assertArrayEquals(before,
                mediaBytes(saved));
    }

    @Test public void savedAudioAllowsVideoSelectorButAudioConfirmationOnlyShowsToast()
            throws Exception {
        final OfflineStore.Entry saved = save("audio/mp4");
        final VideoDetailFragment fragment = open(true);
        main(() -> {
            fragment.requireView().findViewById(R.id.detail_controls_offline_save).performClick();
            return null;
        });
        assertTrue(waitFor(() -> main(() -> activity.getSupportFragmentManager()
                .findFragmentByTag("offline_save") instanceof DownloadDialog)));
        final DownloadDialog dialog = main(() -> (DownloadDialog) activity
                .getSupportFragmentManager().findFragmentByTag("offline_save"));
        assertNotNull(dialog);
        main(() -> {
            final View root = dialog.requireView();
            ((RadioGroup) root.findViewById(R.id.video_audio_group)).check(R.id.audio_button);
            ((Toolbar) root.findViewById(R.id.toolbar)).getMenu()
                    .performIdentifierAction(R.id.okay, 0);
            return null;
        });
        assertTrue(waitFor(() -> main(() -> field(dialog, "alreadySavedToast") != null)));
        main(() -> {
            assertShortToast((Toast) field(dialog, "alreadySavedToast"));
            return null;
        });
        assertTrue(waitFor(() -> main(() -> activity.getSupportFragmentManager()
                .findFragmentByTag("offline_save") == null)));
        assertEquals(1, library.store().list().size());
        assertEquals(OfflineStore.State.READY, library.store().get(saved.id).state);
    }

    private VideoDetailFragment open(final boolean withAudio) throws Exception {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString(context.getString(R.string.autoplay_key),
                        context.getString(R.string.autoplay_never_key))
                .apply();
        final StreamInfo info = new StreamInfo(0, ORIGIN, ORIGIN, StreamType.VIDEO_STREAM,
                ORIGIN, "Generated duplicate-save verification", 0);
        info.setDuration(10);
        info.setVideoStreams(List.of(new VideoStream.Builder().setId("generated-video")
                .setContent("https://example.invalid/generated.webm", true)
                .setMediaFormat(MediaFormat.WEBM).setResolution("180p")
                .setIsVideoOnly(false).build()));
        if (withAudio) {
            info.setAudioStreams(List.of(new AudioStream.Builder().setId("generated-audio")
                    .setContent("https://example.invalid/generated.m4a", true)
                    .setMediaFormat(MediaFormat.M4A).setAverageBitrate(96).build()));
        }
        InfoCache.getInstance().putInfo(0, ORIGIN, info, InfoCache.Type.STREAM);
        activity = (MainActivity) InstrumentationRegistry.getInstrumentation()
                .startActivitySync(new Intent(context, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        main(() -> {
            NavigationHelper.openVideoDetailFragment(activity, activity.getSupportFragmentManager(),
                    0, ORIGIN, info.getName(), null, false, false);
            return null;
        });
        assertTrue(waitFor(() -> main(() -> {
            final androidx.fragment.app.Fragment fragment = activity.getSupportFragmentManager()
                    .findFragmentById(R.id.fragment_player_holder);
            return fragment instanceof VideoDetailFragment && fragment.getView() != null
                    && field(fragment, "currentInfo") == info
                    && fragment.requireView().findViewById(R.id.detail_controls_offline_save)
                            .isShown();
        })));
        return main(() -> (VideoDetailFragment) activity.getSupportFragmentManager()
                .findFragmentById(R.id.fragment_player_holder));
    }

    private void assertShortToast(final Toast toast) {
        assertEquals(Toast.LENGTH_SHORT, toast.getDuration());
        final TextView message = toast.getView().findViewById(android.R.id.message);
        assertEquals(context.getString(R.string.offline_already_saved),
                message.getText().toString());
        final Configuration config = new Configuration(context.getResources().getConfiguration());
        config.setLocale(Locale.KOREAN);
        assertEquals("이미 저장된 항목입니다", context.createConfigurationContext(config)
                .getString(R.string.offline_already_saved));
    }

    private OfflineStore.Entry save(final String mime) throws Exception {
        final OfflineStore.Entry entry = library.store().create("Generated saved copy", ORIGIN, 0,
                "file:///generated/duplicate-test", mime);
        created.add(entry.id);
        library.store().copy(entry.id, new ByteArrayInputStream(new byte[]{1, 2, 3}));
        return library.store().get(entry.id);
    }

    private byte[] mediaBytes(final OfflineStore.Entry entry) throws Exception {
        try (java.io.FileInputStream input =
                     new java.io.FileInputStream(library.store().file(entry));
             java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            final byte[] buffer = new byte[128];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static Object field(final Object target, final String name) throws Exception {
        final Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static <T> T main(final java.util.concurrent.Callable<T> action) throws Exception {
        final FutureTask<T> task = new FutureTask<>(action);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(task);
        return task.get(30, TimeUnit.SECONDS);
    }

    private static boolean waitFor(final java.util.concurrent.Callable<Boolean> condition)
            throws Exception {
        final long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        do {
            if (condition.call()) {
                return true;
            }
            Thread.sleep(100);
        } while (System.nanoTime() < until);
        return false;
    }
}
