package org.schabi.newpipe.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.IBinder;

import androidx.core.content.ContextCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.HttpDataSource;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.player.playqueue.PlayQueueItem;
import org.schabi.newpipe.player.playqueue.SinglePlayQueue;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.reactivex.rxjava3.core.Single;

/** Synthetic HTTP failures and generated local audio only. No server or media URL is contacted. */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class PlaybackRecoveryTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final CountDownLatch connected = new CountDownLatch(1);
    private final AtomicInteger loads = new AtomicInteger();
    private PlayerService service;
    private Player player;
    private File audio;
    private boolean bound;
    private final java.util.Map<String, Boolean> savedPreferences = new java.util.HashMap<>();
    private volatile boolean failExtraction;
    private volatile boolean failMetadataNetworkOnce;
    private volatile io.reactivex.rxjava3.subjects.SingleSubject<StreamInfo> delayedMetadata;
    private StreamInfo firstInfo;
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
        final android.content.SharedPreferences prefs = androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(context);
        for (final int key : new int[]{org.schabi.newpipe.R.string.auto_queue_key,
                org.schabi.newpipe.R.string.resume_on_audio_focus_gain_key}) {
            final String name = context.getString(key);
            savedPreferences.put(name, prefs.contains(name) ? prefs.getBoolean(name, false) : null);
        }
        audio = File.createTempFile("recovery-", ".wav", context.getCacheDir());
        final int dataLength = 8000 * 60 * 2;
        final ByteBuffer wav = ByteBuffer.allocate(44 + dataLength).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + dataLength)
                .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(8000).putInt(16000)
                .putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(dataLength);
        try (FileOutputStream output = new FileOutputStream(audio)) {
            output.write(wav.array());
        }
        ContextCompat.startForegroundService(context, new Intent(context, PlayerService.class)
                .putExtra(PlayerService.SHOULD_START_FOREGROUND_EXTRA, true));
        bound = context.bindService(new Intent(context, PlayerService.class)
                .setAction(PlayerService.BIND_PLAYER_HOLDER_ACTION), connection,
                Context.BIND_AUTO_CREATE);
        assertTrue(bound);
        assertTrue(connected.await(10, TimeUnit.SECONDS));
        await(() -> service != null && service.getPlayer() != null);
        onMain(() -> {
            player = service.getPlayer();
            player.setAutoQueueEnabled(false);
            final Method init = Player.class.getDeclaredMethod("initPlayer", boolean.class);
            init.setAccessible(true);
            init.invoke(player, false);
            final SinglePlayQueue queue = new SinglePlayQueue(item("recovery-A"));
            queue.append(List.of(item("recovery-B")));
            queue.init();
            final Field field = Player.class.getDeclaredField("playQueue");
            field.setAccessible(true);
            field.set(player, queue);
            player.reloadPlayQueueManager();
        });
        await(() -> player.getExoPlayer().getPlaybackState()
                == com.google.android.exoplayer2.Player.STATE_READY);
        onMain(() -> player.getExoPlayer().seekTo(42000));
        await(() -> player.getExoPlayer().getCurrentPosition() == 42000);
    }

    @After
    public void tearDown() {
        onMain(() -> {
            if (service != null) {
                service.destroyPlayerAndStopService();
            }
        });
        if (bound) {
            context.unbindService(connection);
        }
        if (audio != null) {
            audio.delete();
        }
        final android.content.SharedPreferences.Editor editor =
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).edit();
        savedPreferences.forEach((key, value) -> {
            if (value == null) {
                editor.remove(key);
            } else {
                editor.putBoolean(key, value);
            }
        });
        editor.commit();
    }

    @Test
    public void forbiddenRefreshKeepsPausedPositionQueueAndRepeat() throws Exception {
        final int before = loads.get();
        onMain(() -> {
            player.getExoPlayer().setRepeatMode(
                    com.google.android.exoplayer2.Player.REPEAT_MODE_ALL);
            fail(403);
            assertEquals(0, player.getPlayQueue().getIndex());
        });
        await(() -> loads.get() > before && player.getExoPlayer().getPlaybackState()
                == com.google.android.exoplayer2.Player.STATE_READY);
        onMain(() -> {
            assertEquals(42000, player.getExoPlayer().getCurrentPosition());
            assertEquals(0, player.getPlayQueue().getIndex());
            assertEquals(2, player.getPlayQueue().size());
            assertEquals(com.google.android.exoplayer2.Player.REPEAT_MODE_ALL,
                    player.getExoPlayer().getRepeatMode());
            assertFalse(player.getPlayWhenReady());
        });
    }

    @Test
    public void forbiddenRefreshResumesPriorPlayIntent() throws Exception {
        final int before = loads.get();
        onMain(() -> {
            player.play();
            fail(403);
        });
        await(() -> loads.get() > before && player.isPlaying());
        onMain(() -> {
            assertEquals(0, player.getPlayQueue().getIndex());
            assertTrue(player.getExoPlayer().getCurrentPosition() >= 42000);
            assertTrue(player.getExoPlayer().getCurrentPosition() < 45000);
        });
    }

    @Test
    public void pauseCancelsDelayedRefresh() throws Exception {
        final int before = loads.get();
        onMain(() -> {
            player.play();
            fail(403);
            player.pause();
        });
        Thread.sleep(1800);
        onMain(() -> {
            assertEquals(before, loads.get());
            assertFalse(player.getPlayWhenReady());
            assertEquals(0, player.getPlayQueue().getIndex());
        });
    }

    @Test
    public void timerExpiryCancelsDelayedRefresh() throws Exception {
        final int before = loads.get();
        onMain(() -> {
            player.play();
            player.setSleepTimer(100);
            fail(403);
        });
        Thread.sleep(1800);
        onMain(() -> {
            assertEquals(before, loads.get());
            assertFalse(player.getPlayWhenReady());
        });
    }

    @Test
    public void persistentForbiddenStopsAndManualPlayGetsOneNewRefresh() throws Exception {
        final int before = loads.get();
        onMain(() -> fail(403));
        await(() -> loads.get() > before && player.getExoPlayer().getPlaybackState()
                == com.google.android.exoplayer2.Player.STATE_READY);
        final int refreshed = loads.get();
        onMain(() -> fail(403));
        Thread.sleep(1800);
        onMain(() -> {
            assertEquals(refreshed, loads.get());
            assertEquals(0, player.getPlayQueue().getIndex());
            assertFalse(player.getPlayWhenReady());
            player.play();
        });
        await(() -> loads.get() > refreshed && player.isPlaying());
        final int manual = loads.get();
        onMain(() -> fail(403));
        await(() -> loads.get() > manual && player.isPlaying());
    }

    @Test
    public void explicitAccessDenialNeverRefreshesOrSkips() throws Exception {
        final int before = loads.get();
        onMain(() -> fail(401));
        Thread.sleep(1800);
        onMain(() -> {
            assertEquals(before, loads.get());
            assertEquals(0, player.getPlayQueue().getIndex());
            assertFalse(player.getPlayWhenReady());
        });
    }

    @Test
    public void skipInvalidatesTheOldItemRefresh() throws Exception {
        onMain(() -> {
            player.play();
            fail(403);
            player.playNext();
        });
        await(() -> player.getPlayQueue().getIndex() == 1 && player.isPlaying());
        final int afterSkip = loads.get();
        Thread.sleep(1800);
        onMain(() -> {
            assertEquals(1, player.getPlayQueue().getIndex());
            assertEquals("recovery-B", player.getPlayQueue().getItem().getUrl());
            assertEquals(afterSkip, loads.get());
        });
    }

    @Test
    public void skipAfterFailurePreparesNextItemWithoutChangingPausedIntent() throws Exception {
        onMain(() -> {
            fail(403);
            player.playNext();
        });
        await(() -> player.getPlayQueue().getIndex() == 1
                && player.getExoPlayer().getPlaybackState()
                        == com.google.android.exoplayer2.Player.STATE_READY);
        onMain(() -> {
            assertEquals("recovery-B", player.getPlayQueue().getItem().getUrl());
            assertFalse(player.getPlayWhenReady());
            assertFalse(player.isPlaying());
        });
    }

    @Test
    public void stopCancelsDelayedRecovery() throws Exception {
        final int before = loads.get();
        onMain(() -> {
            fail(403);
            player.stop();
        });
        Thread.sleep(1800);
        onMain(() -> assertEquals(before, loads.get()));
    }

    @Test
    public void transientServerFailureHasTwoRetriesAndPreservesPosition() throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            final int before = loads.get();
            onMain(() -> fail(503));
            await(() -> loads.get() > before && player.getExoPlayer().getPlaybackState()
                    == com.google.android.exoplayer2.Player.STATE_READY);
            onMain(() -> assertEquals(42000, player.getExoPlayer().getCurrentPosition()));
        }
        final int before = loads.get();
        onMain(() -> fail(503));
        Thread.sleep(3500);
        onMain(() -> {
            assertEquals(before, loads.get());
            assertEquals(0, player.getPlayQueue().getIndex());
            assertFalse(player.getPlayWhenReady());
        });
    }

    @Test
    public void extractorFailureAfterRefreshStopsBeforeSilentSourceCanSkip() throws Exception {
        onMain(() -> {
            failExtraction = true;
            player.play();
            fail(403);
        });
        await(() -> player.getCurrentState() == Player.STATE_PAUSED);
        Thread.sleep(2500);
        onMain(() -> {
            assertEquals(0, player.getPlayQueue().getIndex());
            assertFalse(player.getPlayWhenReady());
        });
    }

    @Test
    public void stopAfterMetadataRequestStartedCannotResumeFromLateResult() throws Exception {
        final int before = loads.get();
        onMain(() -> {
            delayedMetadata = io.reactivex.rxjava3.subjects.SingleSubject.create();
            player.play();
            fail(403);
        });
        await(() -> loads.get() > before && delayedMetadata.hasObservers());
        onMain(() -> {
            player.stop();
            delayedMetadata.onSuccess(firstInfo);
        });
        Thread.sleep(500);
        onMain(() -> {
            assertEquals(com.google.android.exoplayer2.Player.STATE_IDLE,
                    player.getExoPlayer().getPlaybackState());
            assertFalse(player.isPlaying());
        });
    }

    @Test
    public void pauseAfterMetadataRequestStartedCannotResumeFromLateResult() throws Exception {
        final int before = loads.get();
        onMain(() -> {
            delayedMetadata = io.reactivex.rxjava3.subjects.SingleSubject.create();
            player.play();
            fail(403);
        });
        await(() -> loads.get() > before && delayedMetadata.hasObservers());
        onMain(() -> {
            player.pause();
            delayedMetadata.onSuccess(firstInfo);
        });
        Thread.sleep(500);
        onMain(() -> {
            assertEquals(com.google.android.exoplayer2.Player.STATE_IDLE,
                    player.getExoPlayer().getPlaybackState());
            assertFalse(player.getPlayWhenReady());
            assertEquals(42000, player.getExoPlayer().getCurrentPosition());
            delayedMetadata = null;
            player.play();
        });
        await(player::isPlaying);
        onMain(() -> {
            assertEquals(0, player.getPlayQueue().getIndex());
            assertTrue(player.getExoPlayer().getCurrentPosition() >= 42000);
            assertTrue(player.getExoPlayer().getCurrentPosition() < 45000);
        });
    }

    @Test
    public void metadataRefreshTimeoutIsTerminal() throws Exception {
        final int before = loads.get();
        onMain(() -> {
            failMetadataNetworkOnce = true;
            fail(403);
        });
        await(() -> loads.get() > before && !failMetadataNetworkOnce
                && player.getCurrentState() == Player.STATE_PAUSED);
        final int failed = loads.get();
        Thread.sleep(3500);
        onMain(() -> {
            assertEquals(failed, loads.get());
            assertEquals(0, player.getPlayQueue().getIndex());
            assertFalse(player.getPlayWhenReady());
        });
    }

    @Test
    public void timerAfterRefreshStartedIgnoresLateMetadataFailure() throws Exception {
        final int before = loads.get();
        onMain(() -> {
            delayedMetadata = io.reactivex.rxjava3.subjects.SingleSubject.create();
            player.play();
            fail(403);
        });
        await(() -> loads.get() > before && delayedMetadata.hasObservers());
        onMain(() -> player.setSleepTimer(100));
        await(() -> !player.getPlayWhenReady());
        final int canceled = loads.get();
        onMain(() -> delayedMetadata.onError(new java.net.SocketTimeoutException("Late failure")));
        Thread.sleep(3500);
        onMain(() -> {
            assertEquals(canceled, loads.get());
            assertFalse(player.isPlaying());
            assertEquals(0, player.getPlayQueue().getIndex());
        });
    }

    @Test
    public void localOnlyMissingFileNeverRequestsRemoteMetadata() throws Exception {
        final int before = loads.get();
        onMain(() -> {
            final SinglePlayQueue queue = new SinglePlayQueue(item(
                    org.schabi.newpipe.offline.OfflineStore.LOCAL_PREFIX + "missing-fixture"));
            queue.init();
            final Field field = Player.class.getDeclaredField("playQueue");
            field.setAccessible(true);
            field.set(player, queue);
            player.getExoPlayer().stop();
            player.onPlayerError(new PlaybackException("Missing local fixture",
                    new java.io.FileNotFoundException("Missing local fixture"),
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND));
        });
        Thread.sleep(1800);
        onMain(() -> {
            assertEquals(before, loads.get());
            assertEquals(0, player.getPlayQueue().getIndex());
            assertFalse(player.getPlayWhenReady());
        });
    }

    @Test
    public void transientFocusLossDuringMetadataRefreshResumesWithTimerIntact() throws Exception {
        final int before = loads.get();
        onMain(() -> {
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).edit()
                    .putBoolean(context.getString(
                            org.schabi.newpipe.R.string.resume_on_audio_focus_gain_key), true)
                    .commit();
            delayedMetadata = io.reactivex.rxjava3.subjects.SingleSubject.create();
            player.play();
            player.setSleepTimer(60000);
            fail(403);
        });
        await(() -> loads.get() > before && delayedMetadata.hasObservers());
        onMain(() -> {
            player.getAudioReactor().onAudioFocusChange(
                    android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
            delayedMetadata.onSuccess(firstInfo);
        });
        await(() -> player.getExoPlayer().getPlaybackState()
                == com.google.android.exoplayer2.Player.STATE_READY);
        onMain(() -> {
            assertFalse(player.getPlayWhenReady());
            player.getAudioReactor().onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_GAIN);
        });
        await(player::isPlaying);
        onMain(() -> {
            assertTrue(player.getSleepTimerRemainingMillis() > 0);
            assertEquals(0, player.getPlayQueue().getIndex());
            assertTrue(player.getExoPlayer().getCurrentPosition() >= 42000);
        });
    }

    @Test
    public void transientFocusLossBeforeRecoveryDelayDoesNotCancelResume() throws Exception {
        final int before = loads.get();
        onMain(() -> {
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).edit()
                    .putBoolean(context.getString(
                            org.schabi.newpipe.R.string.resume_on_audio_focus_gain_key), true)
                    .commit();
            player.play();
            fail(403);
            player.getAudioReactor().onAudioFocusChange(
                    android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
            player.getAudioReactor().onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_GAIN);
        });
        await(() -> loads.get() > before && player.isPlaying());
        onMain(() -> {
            assertEquals(0, player.getPlayQueue().getIndex());
            assertTrue(player.getExoPlayer().getCurrentPosition() >= 42000);
        });
    }

    private PlayQueueItem item(final String id) {
        final StreamInfo info = new StreamInfo(1, id, id, StreamType.AUDIO_STREAM, id, "", 0);
        info.setDuration(60);
        info.setUploaderName("Local recovery fixture");
        info.setAudioStreams(List.of(new AudioStream.Builder().setId(id)
                .setContent(Uri.fromFile(audio).toString(), true)
                .setMediaFormat(MediaFormat.WAV).setAverageBitrate(128).build()));
        if (id.equals("recovery-A")) {
            firstInfo = info;
        }
        return new PlayQueueItem(info) {
            @Override
            public Single<StreamInfo> getStream() {
                loads.incrementAndGet();
                if (id.equals("recovery-A") && delayedMetadata != null) {
                    return delayedMetadata;
                }
                if (id.equals("recovery-A") && failMetadataNetworkOnce) {
                    failMetadataNetworkOnce = false;
                    return Single.error(new java.net.SocketTimeoutException("Synthetic timeout"));
                }
                return failExtraction && id.equals("recovery-A")
                        ? Single.error(new org.schabi.newpipe.extractor.exceptions
                                .ExtractionException("Synthetic extractor incompatibility"))
                        : Single.just(info);
            }
        };
    }

    private void fail(final int status) {
        player.getExoPlayer().stop();
        final HttpDataSource.InvalidResponseCodeException cause =
                new HttpDataSource.InvalidResponseCodeException(status, "Synthetic failure", null,
                        Collections.emptyMap(), new DataSpec(Uri.parse("https://example.invalid")),
                        new byte[0]);
        player.onPlayerError(new PlaybackException("Synthetic source failure", cause,
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS));
    }

    private static void await(final Check check) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        final boolean[] ready = {false};
        do {
            onMain(() -> ready[0] = check.matches());
            if (ready[0]) {
                return;
            }
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        assertTrue("recovery condition timed out", ready[0]);
    }

    private static void onMain(final Action action) {
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
