package org.schabi.newpipe.player.helper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.net.Uri;
import android.os.IBinder;
import android.os.Process;
import android.support.v4.media.session.MediaControllerCompat;
import android.util.Log;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.android.exoplayer2.MediaItem;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.player.Player;
import org.schabi.newpipe.player.PlayerService;
import org.schabi.newpipe.player.mediasession.MediaSessionPlayerUi;
import org.schabi.newpipe.player.playqueue.SinglePlayQueue;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Real Android focus arbitration between two APK UIDs, without synthetic focus callbacks. */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class RealAudioFocusHandoffTest {
    private static final String TAG = "AudioFocusReactor";
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final Context testContext = InstrumentationRegistry.getInstrumentation().getContext();
    private final String token = UUID.randomUUID().toString();
    private final CountDownLatch connected = new CountDownLatch(1);
    private final BlockingQueue<Intent> ownerEvents = new LinkedBlockingQueue<>();
    private final BlockingQueue<Integer> focusEvents = new LinkedBlockingQueue<>();
    private final Map<String, Boolean> originalPreferences = new HashMap<>();
    private PlayerService service;
    private Player player;
    private RecordingAudioReactor reactor;
    private MediaControllerCompat controller;
    private File localAudio;
    private boolean bound;
    private boolean receiverRegistered;
    private boolean ownerStarted;
    private boolean ownerReleased;
    private int systemVolume;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context receiverContext, final Intent intent) {
            if (AudioFocusOwnerActivity.STATUS.equals(intent.getAction())
                    && token.equals(intent.getStringExtra(AudioFocusOwnerActivity.TOKEN))) {
                ownerEvents.add(intent);
            }
        }
    };

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
        setPreference(R.string.resume_on_audio_focus_gain_key, null);
        assertTrue("an absent preference must enable automatic resume",
                PlayerHelper.isResumeAfterAudioFocusGain(context));
        setPreference(R.string.auto_queue_key, false);
        ContextCompat.registerReceiver(context, statusReceiver,
                new IntentFilter(AudioFocusOwnerActivity.STATUS), ContextCompat.RECEIVER_EXPORTED);
        receiverRegistered = true;
        localAudio = AudioFocusWav.create(context.getCacheDir());
        ContextCompat.startForegroundService(context, new Intent(context, PlayerService.class)
                .putExtra(PlayerService.SHOULD_START_FOREGROUND_EXTRA, true));
        bound = context.bindService(new Intent(context, PlayerService.class)
                .setAction(PlayerService.BIND_PLAYER_HOLDER_ACTION), connection,
                Context.BIND_AUTO_CREATE);
        assertTrue(bound);
        assertTrue("PlayerService did not connect", connected.await(10, TimeUnit.SECONDS));
        await("PlayerService did not create player",
                () -> service != null && service.getPlayer() != null);
        runOnMain(() -> {
            player = service.getPlayer();
            final Method init = Player.class.getDeclaredMethod("initPlayer", boolean.class);
            init.setAccessible(true);
            init.invoke(player, false);
            final StreamInfoItem item = new StreamInfoItem(0, Uri.fromFile(localAudio).toString(),
                    "Real focus handoff WAV", StreamType.AUDIO_STREAM);
            final SinglePlayQueue queue = new SinglePlayQueue(List.of(item), 0);
            queue.init();
            setField("playQueue", queue);
            player.getAudioReactor().dispose();
            reactor = new RecordingAudioReactor();
            setField("audioReactor", reactor);
            controller = new MediaControllerCompat(context, player.UIs()
                    .get(MediaSessionPlayerUi.class).get().getSessionToken().get());
            player.getExoPlayer().setMediaItem(MediaItem.fromUri(Uri.fromFile(localAudio)));
            player.getExoPlayer().setRepeatMode(
                    com.google.android.exoplayer2.Player.REPEAT_MODE_ONE);
            player.getExoPlayer().prepare();
            player.play();
        });
        await("local playback did not start with real focus", () ->
                player.isPlaying() && reactor.hasAudioFocus());
        runOnMain(() -> {
            systemVolume = reactor.getVolume();
            reactor.requests = 0;
            focusEvents.clear();
        });
    }

    @After
    public void tearDown() throws Exception {
        try {
            if (ownerStarted && !ownerReleased) {
                releaseOwner();
            }
        } finally {
            runOnMain(() -> {
                if (service != null) {
                    service.destroyPlayerAndStopService();
                }
            });
            if (bound) {
                context.unbindService(connection);
            }
            if (receiverRegistered) {
                context.unregisterReceiver(statusReceiver);
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
        }
    }

    @Test
    public void realTransientOwnerReturnsGainAndResumesInBackground() throws Exception {
        startOwner(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        expectFocus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        await("transient owner did not pause Backtube", () -> !player.getPlayWhenReady());
        releaseOwner();
        expectFocus(AudioManager.AUDIOFOCUS_GAIN);
        await("real returned gain did not resume Backtube", player::isPlaying);
        assertVolume(1.0f);
        assertNoNewRequest();
    }

    @Test
    public void realDuckOwnerRestoresOnlyFocusGainOnReturn() throws Exception {
        startOwner(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
        expectFocus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK);
        runOnMain(() -> assertTrue("ducking unexpectedly paused playback",
                player.getPlayWhenReady()));
        assertVolume(.2f);
        runOnMain(() -> reactor.setPlaybackGain(.6f));
        assertVolume(.12f);
        releaseOwner();
        expectFocus(AudioManager.AUDIOFOCUS_GAIN);
        await("duck return left playback stopped", player::isPlaying);
        assertVolume(.6f);
        assertNoNewRequest();
    }

    @Test
    public void realPermanentLossRequiresExplicitPlayAfterOtherVideoLeaves() throws Exception {
        startOwner(AudioManager.AUDIOFOCUS_GAIN);
        expectFocus(AudioManager.AUDIOFOCUS_LOSS);
        await("permanent focus owner did not pause Backtube", () -> !player.getPlayWhenReady());
        releaseOwner();
        // API23 may still dispatch GAIN after permanent loss; it must not restore resume intent.
        assertRemainsPaused(false);
        assertNoNewRequest();
        controller.getTransportControls().play();
        await("explicit play did not reacquire real focus", () ->
                player.isPlaying() && reactor.hasAudioFocus());
        assertVolume(1.0f);
        runOnMain(() -> assertEquals("explicit play should request once", 1, reactor.requests));
    }

    @Test
    public void manualPauseDuringRealInterruptionPreventsAutomaticReturn() throws Exception {
        startOwner(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        expectFocus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        // Real public pause action abandons the suspended request before the owner leaves.
        runOnMain(player::pause);
        releaseOwner();
        assertRemainsPaused(false);
        assertNoNewRequest();
    }

    @Test
    public void manualPlayClearsRealDuckingAfterAbandonWithoutGainCallback() throws Exception {
        startOwner(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
        expectFocus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK);
        assertVolume(.2f);
        runOnMain(player::pause);
        releaseOwner();
        assertRemainsPaused(true);
        assertNoNewRequest();
        assertVolume(.2f);
        controller.getTransportControls().play();
        await("manual play did not recover from real ducking", () ->
                player.isPlaying() && reactor.hasAudioFocus());
        assertVolume(1.0f);
        runOnMain(() -> assertEquals("manual recovery should request once", 1, reactor.requests));
    }

    @Test
    public void sleepExpiryDuringRealInterruptionPreventsAutomaticReturn() throws Exception {
        startOwner(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        expectFocus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        runOnMain(() -> player.setSleepTimer(300));
        await("sleep timer did not expire", () -> player.getSleepTimerRemainingMillis() == 0);
        releaseOwner();
        assertRemainsPaused(false);
        assertNoNewRequest();
    }

    @Test
    public void disabledAutomaticResumeStillRestoresVolumeAndAllowsManualPlay() throws Exception {
        setPreference(R.string.resume_on_audio_focus_gain_key, false);
        startOwner(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        expectFocus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        releaseOwner();
        expectFocus(AudioManager.AUDIOFOCUS_GAIN);
        assertRemainsPaused(false);
        assertVolume(1.0f);
        assertNoNewRequest();
        controller.getTransportControls().play();
        await("manual play after real gain did not resume", player::isPlaying);
        assertVolume(1.0f);
    }

    private void startOwner(final int gainType) throws Exception {
        ownerStarted = true;
        // startActivitySync cannot launch an activity in another process. This explicit component
        // belongs to the test APK, whose UID is asserted by the response below.
        context.startActivity(new Intent().setComponent(new ComponentName(testContext,
                        AudioFocusOwnerActivity.class))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(AudioFocusOwnerActivity.TOKEN, token)
                .putExtra(AudioFocusOwnerActivity.REPLY_PACKAGE, context.getPackageName())
                .putExtra(AudioFocusOwnerActivity.GAIN_TYPE, gainType));
        final Intent status = expectOwner(AudioFocusOwnerActivity.REQUESTED);
        final int ownerUid = status.getIntExtra(AudioFocusOwnerActivity.OWNER_UID, -1);
        // API23's instrumentation context can expose an unpopulated ApplicationInfo (uid=0).
        // Resolve the installed package record, then still require a genuinely separate UID.
        assertEquals(context.getPackageManager()
                .getApplicationInfo(testContext.getPackageName(), 0).uid, ownerUid);
        assertNotEquals("focus owner must have a distinct APK UID", Process.myUid(), ownerUid);
        assertEquals("real competing focus request was rejected",
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED,
                status.getIntExtra(AudioFocusOwnerActivity.RESULT, -1));
    }

    private void releaseOwner() throws Exception {
        context.sendBroadcast(new Intent(AudioFocusOwnerActivity.CONTROL)
                .setPackage(testContext.getPackageName())
                .putExtra(AudioFocusOwnerActivity.TOKEN, token));
        expectOwner(AudioFocusOwnerActivity.RELEASED);
        ownerReleased = true;
    }

    private Intent expectOwner(final String expected) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            final Intent event = ownerEvents.poll(Math.max(1, deadline - System.nanoTime()),
                    TimeUnit.NANOSECONDS);
            if (event == null) {
                break;
            }
            final String kind = event.getStringExtra(AudioFocusOwnerActivity.EVENT);
            if (AudioFocusOwnerActivity.FAILED.equals(kind)) {
                fail("external focus owner failed: "
                        + event.getStringExtra(AudioFocusOwnerActivity.ERROR));
            }
            if (expected.equals(kind)) {
                return event;
            }
        }
        throw new AssertionError("external focus owner did not report " + expected);
    }

    private void expectFocus(final int expected) throws Exception {
        final List<Integer> observed = new ArrayList<>();
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            final Integer event = focusEvents.poll(Math.max(1, deadline - System.nanoTime()),
                    TimeUnit.NANOSECONDS);
            if (event == null) {
                break;
            }
            observed.add(event);
            if (event == expected) {
                return;
            }
        }
        fail("OS did not deliver focus " + expected + "; observed=" + observed);
    }

    private void assertRemainsPaused(final boolean expectNoGain) throws Exception {
        // A bounded observation of callbacks, never polling or re-requesting system focus.
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < deadline) {
            final Integer event = focusEvents.poll(Math.max(1, deadline - System.nanoTime()),
                    TimeUnit.NANOSECONDS);
            if (event != null && expectNoGain) {
                assertNotEquals("request removed from focus stack unexpectedly received gain",
                        AudioManager.AUDIOFOCUS_GAIN, event.intValue());
            }
            runOnMain(() -> assertFalse("playback resumed without permission",
                    player.getPlayWhenReady()));
        }
    }

    private void assertVolume(final float expected) {
        runOnMain(() -> {
            assertEquals(expected, player.getExoPlayer().getVolume(), .001f);
            assertEquals("system media volume changed", systemVolume, reactor.getVolume());
        });
    }

    private void assertNoNewRequest() {
        runOnMain(() -> assertEquals("focus return must not poll or steal ownership",
                0, reactor.requests));
    }

    private SharedPreferences preferences() {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    private void setPreference(final int resource, final Boolean value) {
        final String key = context.getString(resource);
        if (!originalPreferences.containsKey(key)) {
            originalPreferences.put(key, preferences().contains(key)
                    ? preferences().getBoolean(key, false) : null);
        }
        final SharedPreferences.Editor editor = preferences().edit();
        if (value == null) {
            editor.remove(key);
        } else {
            editor.putBoolean(key, value);
        }
        editor.commit();
    }

    private void setField(final String name, final Object value) throws Exception {
        final Field field = Player.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(player, value);
    }

    private final class RecordingAudioReactor extends AudioReactor {
        private int requests;

        private RecordingAudioReactor() {
            super(context, player.getExoPlayer());
        }

        @Override
        public void onAudioFocusChange(final int change) {
            Log.d(TAG, "real handoff target callback=" + change + " uid=" + Process.myUid());
            super.onAudioFocusChange(change);
            focusEvents.add(change);
        }

        @Override
        protected int requestAudioFocusFromSystem() {
            final int result = super.requestAudioFocusFromSystem();
            requests++;
            Log.d(TAG, "real handoff target request result=" + result + " count=" + requests
                    + " uid=" + Process.myUid());
            return result;
        }
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
            } catch (final Exception error) {
                throw new RuntimeException(error);
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
