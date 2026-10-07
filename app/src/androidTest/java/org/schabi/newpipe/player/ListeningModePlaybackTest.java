package org.schabi.newpipe.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.IBinder;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.View;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.MediaItem;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.R;
import org.schabi.newpipe.databinding.PlayerBinding;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.player.mediaitem.StreamInfoTag;
import org.schabi.newpipe.player.playqueue.SinglePlayQueue;
import org.schabi.newpipe.player.ui.MainPlayerUi;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Generated black/silent MP4 only; checks actual ExoPlayer video-track selection. */
@RunWith(AndroidJUnit4.class)
public class ListeningModePlaybackTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final CountDownLatch connected = new CountDownLatch(1);
    private final Map<String, Boolean> originalPreferences = new HashMap<>();
    private PlayerService service;
    private Player player;
    private MainPlayerUi ui;
    private PlayerBinding binding;
    private SurfaceTexture texture;
    private Surface surface;
    private File video;
    private boolean bound;
    private StreamInfo info;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(final ComponentName name, final IBinder binder) {
            service = ((PlayerService.LocalBinder) binder).getService();
            connected.countDown();
        }
        @Override public void onServiceDisconnected(final ComponentName name) {
            service = null;
        }
    };

    @Before
    public void setUp() throws Exception {
        setPreference(R.string.auto_queue_key, false);
        setPreference(R.string.data_saver_key, false);
        video = File.createTempFile("listening-mode-", ".mp4", context.getCacheDir());
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext()
                .getAssets().open("listening-mode-fixture.mp4");
             FileOutputStream output = new FileOutputStream(video)) {
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
        ContextCompat.startForegroundService(context, new Intent(context, PlayerService.class)
                .putExtra(PlayerService.SHOULD_START_FOREGROUND_EXTRA, true));
        bound = context.bindService(new Intent(context, PlayerService.class)
                .setAction(PlayerService.BIND_PLAYER_HOLDER_ACTION), connection,
                Context.BIND_AUTO_CREATE);
        assertTrue(bound);
        assertTrue(connected.await(10, TimeUnit.SECONDS));
        await(() -> service.getPlayer() != null);
        run(() -> {
            player = service.getPlayer();
            final Method init = Player.class.getDeclaredMethod("initPlayer", boolean.class);
            init.setAccessible(true);
            init.invoke(player, false);
            info = new StreamInfo(1, "local-listening-fixture", "local-listening-fixture",
                    StreamType.VIDEO_STREAM, "Generated silent video", "", 0);
            info.setDuration(30);
            final VideoStream stream = new VideoStream.Builder().setId("local-muxed")
                    .setContent(Uri.fromFile(video).toString(), true)
                    .setMediaFormat(MediaFormat.MPEG_4).setResolution("90p")
                    .setIsVideoOnly(false).build();
            info.setVideoStreams(List.of(stream));
            info.setAudioStreams(List.of());
            info.setRelatedItems(List.of());
            final SinglePlayQueue queue = new SinglePlayQueue(info);
            queue.init();
            // PlayerService applies the same AppCompat theme used by the real player UI.
            binding = PlayerBinding.inflate(LayoutInflater.from(player.getContext()));
            ui = new MainPlayerUi(player, binding);
            player.UIs().addAndPrepare(ui);
            set("playQueue", queue);
            set("currentMetadata", StreamInfoTag.of(info, List.of(stream), 0, List.of(), -1));
            texture = new SurfaceTexture(0);
            surface = new Surface(texture);
            player.getExoPlayer().setVideoSurface(surface);
            player.getExoPlayer().setMediaItem(MediaItem.fromUri(Uri.fromFile(video)));
            player.getExoPlayer().setRepeatMode(
                    com.google.android.exoplayer2.Player.REPEAT_MODE_ONE);
            player.getExoPlayer().prepare();
            player.play();
            ui.onMetadataChanged(info);
        });
        await(() -> player.isPlaying()
                && player.getExoPlayer().getCurrentTracks().isTypeSelected(C.TRACK_TYPE_VIDEO));
    }

    @After
    public void tearDown() {
        run(() -> {
            if (service != null) {
                service.destroyPlayerAndStopService();
            }
            if (surface != null) {
                surface.release();
            }
            if (texture != null) {
                texture.release();
            }
        });
        if (bound) {
            context.unbindService(connection);
        }
        if (video != null) {
            video.delete();
        }
        final SharedPreferences.Editor editor = preferences().edit();
        originalPreferences.forEach((key, value) -> {
            if (value == null) {
                editor.remove(key);
            } else {
                editor.putBoolean(key, value);
            }
        });
        editor.commit();
    }

    @Test
    public void listeningToggleDisablesVideoAndPreservesPlaybackSession() throws Exception {
        final Object[] original = new Object[2];
        run(() -> {
            original[0] = player.getExoPlayer();
            original[1] = player.getPlayQueue();
            player.pause();
            player.getExoPlayer().seekTo(7000);
            player.setSleepTimer(60_000);
            player.getAudioReactor().setPlaybackGain(.6f);
            button().performClick();
        });
        await(() -> !player.getExoPlayer().getCurrentTracks().isTypeSelected(C.TRACK_TYPE_VIDEO));
        run(() -> {
            assertTrue(player.getTrackSelector().getParameters().disabledTrackTypes.contains(
                    C.TRACK_TYPE_VIDEO));
            assertFalse(player.getPlayWhenReady());
            assertTrue(player.getExoPlayer().getCurrentTracks().isTypeSelected(C.TRACK_TYPE_AUDIO));
            assertEquals(7000, player.getExoPlayer().getCurrentPosition(), 300);
            assertTrue(player.getSleepTimerRemainingMillis() > 50_000);
            assertEquals(.6f, player.getExoPlayer().getVolume(), .001f);
            assertSame(original[0], player.getExoPlayer());
            assertSame(original[1], player.getPlayQueue());
            // Surface/fragment lifecycle callbacks cannot silently turn video back on.
            player.useVideoAndSubtitles(true);
            assertTrue(player.getTrackSelector().getParameters().disabledTrackTypes.contains(
                    C.TRACK_TYPE_VIDEO));
            player.UIs().destroyAll(MainPlayerUi.class);
            binding = PlayerBinding.inflate(LayoutInflater.from(player.getContext()));
            ui = new MainPlayerUi(player, binding);
            player.UIs().addAndPrepare(ui);
            ui.onMetadataChanged(info);
            player.getExoPlayer().setVideoSurface(surface);
            assertTrue(button().isSelected());
            assertEquals(View.GONE, binding.surfaceView.getVisibility());
            button().performClick();
        });
        await(() -> player.getExoPlayer().getCurrentTracks().isTypeSelected(C.TRACK_TYPE_VIDEO));
        run(() -> {
            assertFalse(button().isSelected());
            assertEquals(View.VISIBLE, binding.surfaceView.getVisibility());
            assertFalse(player.getPlayWhenReady());
            assertEquals(7000, player.getExoPlayer().getCurrentPosition(), 300);
            assertSame(original[0], player.getExoPlayer());
            assertSame(original[1], player.getPlayQueue());
            assertTrue(player.getSleepTimerRemainingMillis() > 50_000);
            assertEquals(.6f, player.getExoPlayer().getVolume(), .001f);
        });
    }

    @Test
    public void listeningModeKeepsAudioAdvancingWithoutVideoTrack() throws Exception {
        final long[] position = {0};
        run(() -> {
            button().performClick();
            position[0] = player.getExoPlayer().getCurrentPosition();
        });
        await(() -> player.isPlaying()
                && player.getExoPlayer().getCurrentTracks().isTypeSelected(C.TRACK_TYPE_AUDIO)
                && !player.getExoPlayer().getCurrentTracks().isTypeSelected(C.TRACK_TYPE_VIDEO)
                && player.getExoPlayer().getCurrentPosition() > position[0] + 200);
        run(() -> {
            player.pause();
            player.play();
            position[0] = player.getExoPlayer().getCurrentPosition();
        });
        await(() -> player.isPlaying()
                && !player.getExoPlayer().getCurrentTracks().isTypeSelected(C.TRACK_TYPE_VIDEO)
                && player.getExoPlayer().getCurrentPosition() > position[0] + 200);
    }

    @Test
    public void uiOnlyExitKeepsServiceAndTimerWithoutResumingPause() throws Exception {
        run(() -> {
            player.pause();
            player.setSleepTimer(60_000);
            final Object engine = player.getExoPlayer();
            final Object queue = player.getPlayQueue();
            final Method exit = Player.class.getMethod("continueInBackgroundOnUiExit");
            exit.invoke(player);
            assertEquals(PlayerType.AUDIO, player.getPlayerType());
            assertSame(engine, player.getExoPlayer());
            assertSame(queue, player.getPlayQueue());
            assertFalse(player.getPlayWhenReady());
            assertTrue(player.getSleepTimerRemainingMillis() > 50_000);
            assertTrue(player.getTrackSelector().getParameters().disabledTrackTypes.contains(
                    C.TRACK_TYPE_VIDEO));
        });
    }

    private View button() {
        final int id = context.getResources().getIdentifier("listeningMode", "id",
                context.getPackageName());
        assertTrue("listening-mode control is missing", id != 0);
        final View button = binding.getRoot().findViewById(id);
        assertNotNull(button);
        return button;
    }

    private void set(final String fieldName, final Object value) throws Exception {
        final Field field = Player.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(player, value);
    }

    private SharedPreferences preferences() {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    private void setPreference(final int resource, final boolean value) {
        final String key = context.getString(resource);
        originalPreferences.put(key, preferences().contains(key)
                ? preferences().getBoolean(key, false) : null);
        preferences().edit().putBoolean(key, value).commit();
    }

    private static void await(final Check check) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        final boolean[] result = {false};
        do {
            run(() -> result[0] = check.matches());
            if (result[0]) {
                return;
            }
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        assertTrue("local video state did not settle", result[0]);
    }

    private static void run(final Action action) {
        final FutureTask<Void> task = new FutureTask<>(() -> {
            action.run();
            return null;
        });
        InstrumentationRegistry.getInstrumentation().runOnMainSync(task);
        try {
            task.get();
        } catch (final InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        } catch (final ExecutionException error) {
            throw new AssertionError(error.getCause());
        }
    }

    private interface Check {
        boolean matches();
    }

    private interface Action {
        void run() throws Exception;
    }
}
