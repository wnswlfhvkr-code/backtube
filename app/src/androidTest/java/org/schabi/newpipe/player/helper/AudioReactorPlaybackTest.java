package org.schabi.newpipe.player.helper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.net.Uri;
import android.os.IBinder;
import android.support.v4.media.session.MediaControllerCompat;
import android.view.KeyEvent;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import com.google.android.exoplayer2.MediaItem;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.player.PlayQueueActivity;
import org.schabi.newpipe.player.Player;
import org.schabi.newpipe.player.PlayerService;
import org.schabi.newpipe.player.mediasession.MediaSessionPlayerUi;
import org.schabi.newpipe.player.notification.NotificationConstants;
import org.schabi.newpipe.player.playqueue.SinglePlayQueue;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Simulated focus callbacks only: no real alerts, settings changes, or remote media. */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class AudioReactorPlaybackTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final CountDownLatch connected = new CountDownLatch(1);
    private final Map<String, Boolean> originalPreferences = new HashMap<>();
    private PlayerService service;
    private Player player;
    private AudioReactor reactor;
    private MediaControllerCompat controller;
    private File localAudio;
    private boolean bound;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(final ComponentName name, final IBinder binder) {
            service = ((PlayerService.LocalBinder) binder).getService();
            connected.countDown();
        }

        @Override
        public void onServiceDisconnected(final ComponentName name) {
            service = null;
        }
    };

    @Before
    public void setUp() throws Exception {
        setPreference(R.string.resume_on_audio_focus_gain_key, true);
        setPreference(R.string.auto_queue_key, false);
        setPreference(R.string.ignore_hardware_media_buttons_key, false);
        localAudio = createSilentWav();
        ContextCompat.startForegroundService(context, new Intent(context, PlayerService.class)
                .putExtra(PlayerService.SHOULD_START_FOREGROUND_EXTRA, true));
        bound = context.bindService(new Intent(context, PlayerService.class)
                .setAction(PlayerService.BIND_PLAYER_HOLDER_ACTION), connection,
                Context.BIND_AUTO_CREATE);
        assertTrue(bound);
        assertTrue("service did not connect", connected.await(10, TimeUnit.SECONDS));
        await("service did not create player",
                () -> service != null && service.getPlayer() != null);
        runOnMain(() -> {
            player = service.getPlayer();
            final Method init = Player.class.getDeclaredMethod("initPlayer", boolean.class);
            init.setAccessible(true);
            init.invoke(player, false);
            final StreamInfoItem item = new StreamInfoItem(0, Uri.fromFile(localAudio).toString(),
                    "Focus test WAV", StreamType.AUDIO_STREAM);
            final SinglePlayQueue queue = new SinglePlayQueue(List.of(item), 0);
            queue.init();
            final Field field = Player.class.getDeclaredField("playQueue");
            field.setAccessible(true);
            field.set(player, queue);
            reactor = player.getAudioReactor();
            controller = new MediaControllerCompat(context, player.UIs()
                    .get(MediaSessionPlayerUi.class).get().getSessionToken().get());
            player.getExoPlayer().setMediaItem(MediaItem.fromUri(Uri.fromFile(localAudio)));
            player.getExoPlayer().setRepeatMode(
                    com.google.android.exoplayer2.Player.REPEAT_MODE_ONE);
            player.getExoPlayer().prepare();
            player.play();
        });
        await("local WAV did not play", player::isPlaying);
    }

    @After
    public void tearDown() {
        runOnMain(() -> {
            final Set<Activity> activities = new HashSet<>();
            for (final Stage stage : Stage.values()) {
                if (stage != Stage.PRE_ON_CREATE && stage != Stage.DESTROYED) {
                    activities.addAll(ActivityLifecycleMonitorRegistry.getInstance()
                            .getActivitiesInStage(stage));
                }
            }
            for (final Activity activity : activities) {
                if (activity instanceof PlayQueueActivity && !activity.isFinishing()) {
                    activity.finish();
                }
            }
            if (service != null) {
                service.destroyPlayerAndStopService();
            }
        });
        if (bound) {
            context.unbindService(connection);
        }
        if (localAudio != null) {
            localAudio.delete();
        }
        final SharedPreferences.Editor editor = preferences().edit();
        for (final Map.Entry<String, Boolean> entry : originalPreferences.entrySet()) {
            if (entry.getValue() == null) {
                editor.remove(entry.getKey());
            } else {
                editor.putBoolean(entry.getKey(), entry.getValue());
            }
        }
        editor.commit();
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test
    public void notificationResumeBeforeGainRestoresVolumeBeforeOpeningApp() throws Exception {
        final int systemVolume = reactor.getVolume();
        interruptPlayback();
        context.sendBroadcast(new Intent(NotificationConstants.ACTION_PLAY_PAUSE)
                .setPackage(context.getPackageName()));
        await("notification did not resume", player::isPlaying);
        assertVolume(1.0f);
        runOnMain(() -> assertTrue("fixture must resume without a foreground activity",
                ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.RESUMED).isEmpty()));
        final Intent openQueue = new Intent(context, PlayQueueActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final Activity activity = InstrumentationRegistry.getInstrumentation()
                .startActivitySync(openQueue);
        try {
            assertVolume(1.0f);
            focus(AudioManager.AUDIOFOCUS_GAIN);
            assertVolume(1.0f);
            assertEquals(systemVolume, reactor.getVolume());
        } finally {
            runOnMain(activity::finish);
        }
    }

    @Test
    public void mediaButtonResumeBeforeGainPreservesSleepGain() throws Exception {
        runOnMain(() -> reactor.setPlaybackGain(.6f));
        interruptPlayback();
        assertVolume(.12f);
        controller.dispatchMediaButtonEvent(new KeyEvent(KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_MEDIA_PLAY));
        controller.dispatchMediaButtonEvent(new KeyEvent(KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_MEDIA_PLAY));
        await("media button did not resume", player::isPlaying);
        assertVolume(.6f);
        focus(AudioManager.AUDIOFOCUS_GAIN);
        assertVolume(.6f);
    }

    @Test
    public void resumeAfterGainWithAutomaticResumeDisabledHasNormalVolume() throws Exception {
        setPreference(R.string.resume_on_audio_focus_gain_key, false);
        interruptPlayback();
        focus(AudioManager.AUDIOFOCUS_GAIN);
        assertVolume(1.0f);
        runOnMain(() -> assertFalse(player.getPlayWhenReady()));
        controller.getTransportControls().play();
        await("media session did not resume", player::isPlaying);
        assertVolume(1.0f);
    }

    @Test
    public void repeatedAlertsRestoreVolumeWithoutWaitingForAnimationFrames() throws Exception {
        for (int i = 0; i < 3; i++) {
            interruptPlayback();
            focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
            runOnMain(() -> {
                reactor.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
                // Assert in the same main-loop turn: no animation frame can run first.
                assertEquals(1.0f, player.getExoPlayer().getVolume(), .001f);
                assertTrue(player.getPlayWhenReady());
            });
            await("transient interruption did not resume", player::isPlaying);
        }
    }

    @Test
    public void explicitMediaPauseSurvivesLateGain() throws Exception {
        interruptPlayback();
        // Waiting on a following main-loop task ensures the media-session pause was delivered.
        controller.getTransportControls().pause();
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        focus(AudioManager.AUDIOFOCUS_GAIN);
        runOnMain(() -> assertFalse(player.getPlayWhenReady()));
        assertVolume(1.0f);
    }

    @Test
    public void permanentLossAndMuteSurviveGain() {
        focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        focus(AudioManager.AUDIOFOCUS_LOSS);
        runOnMain(() -> player.toggleMute());
        focus(AudioManager.AUDIOFOCUS_GAIN);
        runOnMain(() -> {
            assertFalse(player.getPlayWhenReady());
            assertTrue(player.isMuted());
        });
        assertVolume(0.0f);
    }

    private void interruptPlayback() {
        focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK);
        focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        runOnMain(() -> assertFalse(player.getPlayWhenReady()));
    }

    private void focus(final int event) {
        runOnMain(() -> reactor.onAudioFocusChange(event));
    }

    private void assertVolume(final float expected) {
        runOnMain(() -> assertEquals(expected, player.getExoPlayer().getVolume(), .001f));
    }

    private SharedPreferences preferences() {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    private void setPreference(final int keyResource, final boolean value) {
        final String key = context.getString(keyResource);
        if (!originalPreferences.containsKey(key)) {
            originalPreferences.put(key, preferences().contains(key)
                    ? preferences().getBoolean(key, false) : null);
        }
        preferences().edit().putBoolean(key, value).commit();
    }

    private File createSilentWav() throws Exception {
        final int rate = 8000;
        final int length = rate * 30 * 2;
        final ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + length)
                .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(rate).putInt(rate * 2)
                .putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(length);
        final File file = File.createTempFile("audio-focus-", ".wav", context.getCacheDir());
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(header.array());
            output.write(new byte[length]);
        }
        return file;
    }

    private static void await(final String message, final Check check) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        final boolean[] result = {false};
        do {
            runOnMain(() -> result[0] = check.matches());
            if (result[0]) {
                return;
            }
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        assertTrue(message, result[0]);
    }

    private static void runOnMain(final Action action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                action.run();
            } catch (final Exception exception) {
                throw new RuntimeException(exception);
            }
        });
    }

    private interface Check {
        boolean matches();
    }

    private interface Action {
        void run() throws Exception;
    }
}
