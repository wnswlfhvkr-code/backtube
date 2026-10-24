package org.schabi.newpipe.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.schabi.newpipe.player.SavedPlaybackTestSupport.callOnMain;
import static org.schabi.newpipe.player.SavedPlaybackTestSupport.runOnMain;
import static org.schabi.newpipe.player.SavedPlaybackTestSupport.waitFor;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.IBinder;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.player.playqueue.PlayQueueItem;
import org.schabi.newpipe.player.playqueue.SinglePlayQueue;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.reactivex.rxjava3.core.Single;

/** Actual initial failed media sources with a ready successor; generated local audio only. */
@RunWith(AndroidJUnit4.class)
public class InitialSourceFailureTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final CountDownLatch connected = new CountDownLatch(1);
    private PlayerService service;
    private Player player;
    private File localAudio;
    private boolean bound;
    private final java.util.Map<String, Boolean> savedPreferences = new java.util.HashMap<>();
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(final ComponentName name, final IBinder binder) {
            service = ((PlayerService.LocalBinder) binder).getService();
            connected.countDown();
        }
        @Override public void onServiceDisconnected(final ComponentName name) {
            service = null;
        }
    };

    @Before public void setUp() throws Exception {
        final android.content.SharedPreferences prefs =
                PreferenceManager.getDefaultSharedPreferences(context);
        for (final int key : new int[]{R.string.data_saver_key, R.string.auto_queue_key}) {
            final String name = context.getString(key);
            savedPreferences.put(name, prefs.contains(name) ? prefs.getBoolean(name, false) : null);
        }
        localAudio = SavedPlaybackTestSupport.createSilentWav(context, 60);
        ContextCompat.startForegroundService(context, new Intent(context, PlayerService.class)
                .putExtra(PlayerService.SHOULD_START_FOREGROUND_EXTRA, true));
        bound = context.bindService(new Intent(context, PlayerService.class)
                .setAction(PlayerService.BIND_PLAYER_HOLDER_ACTION), connection,
                Context.BIND_AUTO_CREATE);
        assertTrue(bound);
        assertTrue(connected.await(30, TimeUnit.SECONDS));
        runOnMain(() -> {
            player = service.getPlayer();
            final Method init = Player.class.getDeclaredMethod("initPlayer", boolean.class);
            init.setAccessible(true);
            init.invoke(player, false);
        });
    }

    @After public void tearDown() throws Exception {
        runOnMain(() -> {
            if (service != null) {
                service.destroyPlayerAndStopService();
            }
        });
        if (bound) {
            context.unbindService(connection);
        }
        try {
            SavedPlaybackTestSupport.awaitPlaybackTeardown(context);
        } finally {
            org.schabi.newpipe.util.InfoCache.getInstance().removeInfo(1, "initial-related-B",
                    org.schabi.newpipe.util.InfoCache.Type.STREAM);
            org.schabi.newpipe.util.InfoCache.getInstance().removeInfo(1, "initial-failed-A",
                    org.schabi.newpipe.util.InfoCache.Type.STREAM);
            if (localAudio != null) {
                localAudio.delete();
            }
            final android.content.SharedPreferences.Editor editor =
                    PreferenceManager.getDefaultSharedPreferences(context).edit();
            savedPreferences.forEach((key, value) -> {
                if (value == null) {
                    editor.remove(key);
                } else {
                    editor.putBoolean(key, value);
                }
            });
            editor.commit();
        }
    }

    private void setField(final String name, final Object value) throws Exception {
        final Field field = Player.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(player, value);
    }

    @Test
    public void initialAudioOnlyFailureRetainsSelectionUntilRetry() throws Exception {
        verifyInitialFailedSourceRetainsSelection(true, true, false);
    }

    @Test
    public void initialAudioOnlyFailureDoesNotStartRelatedRecommendation() throws Exception {
        verifyInitialFailedSourceRetainsSelection(true, false, false);
    }

    @Test
    public void initialExtractorFailureRetainsSelectionUntilRetry() throws Exception {
        verifyInitialFailedSourceRetainsSelection(false, true, false);
    }

    @Test
    public void freshReselectionAfterInitialFailureReloadsSelectedSource() throws Exception {
        verifyInitialFailedSourceRetainsSelection(true, true, true);
    }

    private void verifyInitialFailedSourceRetainsSelection(final boolean audioOnly,
                                                          final boolean queuedRelated,
                                                          final boolean freshReselection)
            throws Exception {
        final java.util.concurrent.atomic.AtomicBoolean fail =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        final StreamInfo selected = new StreamInfo(1, "initial-failed-A", "initial-failed-A",
                StreamType.AUDIO_STREAM, "initial-failed-A", "Selected A", 0);
        selected.setUploaderName("Generated fixture");
        selected.setRelatedItems(List.of(new StreamInfoItem(1, "initial-related-B", "Related B",
                StreamType.AUDIO_STREAM)));
        final StreamInfo related = new StreamInfo(1, "initial-related-B", "initial-related-B",
                StreamType.AUDIO_STREAM, "initial-related-B", "Related B", 0);
        related.setUploaderName("Generated fixture");
        related.setRelatedItems(List.of());
        final List<AudioStream> streams = List.of(new AudioStream.Builder().setId("generated")
                .setContent(Uri.fromFile(localAudio).toString(), true)
                .setMediaFormat(MediaFormat.WAV).setAverageBitrate(128).build());
        related.setAudioStreams(streams);
        org.schabi.newpipe.util.InfoCache.getInstance().putInfo(1, related.getUrl(), related,
                org.schabi.newpipe.util.InfoCache.Type.STREAM);
        final PlayQueueItem first = new PlayQueueItem(selected) {
            @Override public Single<StreamInfo> getStream() {
                if (!audioOnly && fail.get()) {
                    return Single.error(new org.schabi.newpipe.extractor.exceptions
                            .ExtractionException("Synthetic initial extraction failure"));
                }
                selected.setAudioStreams(fail.get() ? List.of() : streams);
                return Single.just(selected);
            }
        };
        final PlayQueueItem next = new PlayQueueItem(related) {
            @Override public Single<StreamInfo> getStream() {
                return Single.just(related);
            }
        };
        runOnMain(() -> {
            player.setAutoQueueEnabled(true);
            player.getPrefs().edit().putBoolean(context.getString(R.string.data_saver_key),
                    audioOnly).commit();
            setField("playerType", PlayerType.AUDIO);
            final SinglePlayQueue queue = new SinglePlayQueue(first);
            if (queuedRelated) {
                queue.append(List.of(next));
            }
            final Method init = org.schabi.newpipe.player.Player.class.getDeclaredMethod(
                    "initPlayback", org.schabi.newpipe.player.playqueue.PlayQueue.class,
                    boolean.class);
            init.setAccessible(true);
            init.invoke(player, queue, true);
        });
        assertTrue("initial failure did not reach the real player", waitFor(() -> callOnMain(() ->
                org.schabi.newpipe.player.mediaitem.MediaItemTag
                        .from(player.getExoPlayer().getCurrentMediaItem())
                        .map(tag -> !tag.getErrors().isEmpty()).orElse(false)), 30));
        runOnMain(() -> assertTrue("unexpected initial failure type",
                org.schabi.newpipe.player.mediaitem.MediaItemTag
                        .from(player.getExoPlayer().getCurrentMediaItem()).orElseThrow()
                        .getErrors().stream().anyMatch(error -> audioOnly
                                ? error instanceof org.schabi.newpipe.player.mediasource
                                        .FailedMediaSource.AudioOnlyUnavailableException
                                : error instanceof org.schabi.newpipe.player.mediasource
                                        .FailedMediaSource.StreamInfoLoadException)));
        // FailedMediaSource uses two seconds of silence. Waiting beyond it catches accidental
        // automatic completion rather than only checking the resolved source type.
        Thread.sleep(3500);
        runOnMain(() -> {
            assertEquals("initial failure skipped selected A", "initial-failed-A",
                    player.getPlayQueue().getItem().getUrl());
            assertEquals(0, player.getPlayQueue().getIndex());
            assertFalse("initial failure did not stop for retry", player.getPlayWhenReady());
            assertEquals(queuedRelated ? 2 : 1, player.getPlayQueue().size());
            fail.set(false);
            if (freshReselection) {
                selected.setAudioStreams(streams);
                org.schabi.newpipe.util.InfoCache.getInstance().putInfo(1, selected.getUrl(),
                        selected, org.schabi.newpipe.util.InfoCache.Type.STREAM);
                // Real navigation serializes its queue; anonymous extractor test items do not.
                final SinglePlayQueue reselected = new SinglePlayQueue(selected);
                reselected.append(List.of(new PlayQueueItem(related)));
                org.schabi.newpipe.util.NavigationHelper.playOnBackgroundPlayer(context,
                        reselected, true, null, false);
            } else {
                player.play();
            }
        });
        assertTrue("manual retry did not play selected A", waitFor(() -> callOnMain(() ->
                player.isPlaying() && "initial-failed-A".equals(player.getVideoUrl())
                        && player.getCurrentStreamInfo().isPresent()), 30));
        runOnMain(() -> {
            assertEquals(Uri.fromFile(localAudio).toString(), player.getExoPlayer()
                    .getCurrentMediaItem().localConfiguration.uri.toString());
            player.pause();
        });
    }

}
