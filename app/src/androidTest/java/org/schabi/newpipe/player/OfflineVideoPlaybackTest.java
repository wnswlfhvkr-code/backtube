package org.schabi.newpipe.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.schabi.newpipe.player.SavedPlaybackTestSupport.callOnMain;
import static org.schabi.newpipe.player.SavedPlaybackTestSupport.runOnMain;
import static org.schabi.newpipe.player.SavedPlaybackTestSupport.waitFor;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.core.content.ContextCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import com.google.android.exoplayer2.C;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.MainActivity;
import org.schabi.newpipe.R;
import org.schabi.newpipe.offline.OfflineLibrary;
import org.schabi.newpipe.offline.OfflineStore;
import org.schabi.newpipe.player.SavedPlaybackTestSupport.RejectNetwork;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.player.ui.MainPlayerUi;
import org.schabi.newpipe.util.NavigationHelper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Owned generated media, real MAIN surface and decoder counters, no extractor network allowed. */
@RunWith(AndroidJUnit4.class)
public class OfflineVideoPlaybackTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final CountDownLatch connected = new CountDownLatch(1);
    private final List<String> created = new ArrayList<>();
    private OfflineLibrary library;
    private PlayerService service;
    private Player player;
    private boolean bound;
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
        library = OfflineLibrary.get(context);
        ContextCompat.startForegroundService(context, new Intent(context, PlayerService.class)
                .putExtra(PlayerService.SHOULD_START_FOREGROUND_EXTRA, true));
        bound = context.bindService(new Intent(context, PlayerService.class)
                .setAction(PlayerService.BIND_PLAYER_HOLDER_ACTION), connection,
                Context.BIND_AUTO_CREATE);
        assertTrue(bound);
        assertTrue(connected.await(30, TimeUnit.SECONDS));
        assertTrue(waitFor(() -> callOnMain(() -> service.getPlayer() != null), 30));
        player = callOnMain(service::getPlayer);
        runOnMain(() -> player.setAutoQueueEnabled(false));
    }

    @After public void tearDown() throws Exception {
        runOnMain(() -> {
            for (final Stage stage : Stage.values()) {
                if (stage == Stage.PRE_ON_CREATE || stage == Stage.DESTROYED) {
                    continue;
                }
                for (final android.app.Activity activity : new ArrayList<>(
                        ActivityLifecycleMonitorRegistry.getInstance()
                                .getActivitiesInStage(stage))) {
                    if (activity instanceof MainActivity || activity instanceof PlayQueueActivity) {
                        activity.finish();
                    }
                }
            }
            if (service != null) {
                service.destroyPlayerAndStopService();
            }
        });
        if (bound) {
            context.unbindService(connection);
        }
        for (final String id : created) {
            library.store().delete(id);
        }
    }

    @Test
    public void singleSavedVideoRendersFramesAndAudioWithoutNetwork() throws Exception {
        final OfflineStore.Entry video = save(true);
        try (RejectNetwork network = new RejectNetwork()) {
            final PlayQueue queue = library.queue(video);
            runOnMain(() -> NavigationHelper.playOnMainPlayer(context, queue, false));
            assertRenderedVideo(video.localUrl());
            assertTrue("saved artwork did not reach the active player", waitFor(
                    () -> callOnMain(() -> player.getThumbnail() != null
                            && player.getCurrentStreamInfo().map(info ->
                                    !info.getThumbnails().isEmpty()
                                    && info.getThumbnails().get(0).getUrl().startsWith("file:"))
                                    .orElse(false)), 30));
            runOnMain(() -> {
                player.pause();
                player.getExoPlayer().seekTo(1000);
                player.getAudioReactor().setPlaybackGain(.6f);
                player.setSleepTimer(60_000);
            });
            final Object engine = callOnMain(player::getExoPlayer);
            final Object activeQueue = callOnMain(player::getPlayQueue);
            runOnMain(() -> player.setListeningMode(true));
            assertTrue(waitFor(() -> callOnMain(() -> !player.getExoPlayer()
                    .getCurrentTracks().isTypeSelected(C.TRACK_TYPE_VIDEO)), 30));
            runOnMain(() -> player.setListeningMode(false));
            assertTrue(waitFor(() -> callOnMain(() -> player.getExoPlayer()
                    .getCurrentTracks().isTypeSelected(C.TRACK_TYPE_VIDEO)), 30));
            runOnMain(() -> {
                assertSame(engine, player.getExoPlayer());
                assertSame(activeQueue, player.getPlayQueue());
                assertFalse(player.getPlayWhenReady());
                assertEquals(1000, player.getExoPlayer().getCurrentPosition(), 300);
                assertEquals(.6f, player.getExoPlayer().getVolume(), .001f);
                assertTrue(player.getSleepTimerRemainingMillis() > 0);
            });
            runOnMain(() -> {
                player.continueInBackgroundOnUiExit();
                assertEquals(PlayerType.AUDIO, player.getPlayerType());
                context.startActivity(new Intent(context, PlayQueueActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            });
            assertTrue(waitFor(() -> callOnMain(() ->
                    SavedPlaybackTestSupport.controllerReady(player)), 30));
            runOnMain(() -> assertTrue(SavedPlaybackTestSupport.activeQueueActivity()
                    .findViewById(R.id.metadata).performAccessibilityAction(
                            AccessibilityNodeInfo.ACTION_EXPAND, null)));
            assertTrue("paused saved video did not reopen MAIN", waitFor(() -> callOnMain(() ->
                    player.getPlayerType() == PlayerType.MAIN
                            && player.UIs().get(MainPlayerUi.class).map(ui ->
                                    ui.getBinding().surfaceView.isShown()
                                            && ui.getBinding().surfaceView.getHolder()
                                                    .getSurface().isValid()).orElse(false)
                            && player.getExoPlayer().getCurrentTracks()
                                    .isTypeSelected(C.TRACK_TYPE_VIDEO)), 30));
            runOnMain(() -> {
                assertFalse(player.getPlayWhenReady());
                assertEquals(1000, player.getExoPlayer().getCurrentPosition(), 300);
                assertEquals(.6f, player.getExoPlayer().getVolume(), .001f);
            });
            assertEquals(0, network.requests.get());
        }
    }

    @Test
    public void selectedAudioThenVideoRendersVideoOnAutomaticTransition() throws Exception {
        final OfflineStore.Entry audio = save(false);
        final OfflineStore.Entry video = save(true);
        try (RejectNetwork network = new RejectNetwork()) {
            final PlayQueue queue = library.queueSelected(List.of(audio.id, video.id));
            assertEquals(audio.localUrl(), queue.getItem(0).getUrl());
            assertEquals(video.localUrl(), queue.getItem(1).getUrl());
            runOnMain(() -> NavigationHelper.playOnMainPlayer(context, queue, false));
            assertRenderedVideo(video.localUrl());
            assertEquals(0, network.requests.get());
        }
    }

    @Test
    public void audioOnlySaveRetainsAudioPlayerAndAudioDecoder() throws Exception {
        final OfflineStore.Entry audio = save(false);
        try (RejectNetwork network = new RejectNetwork()) {
            final PlayQueue queue = library.queue(audio);
            runOnMain(() -> NavigationHelper.playOnBackgroundPlayer(context, queue, true,
                    null, false));
            assertTrue(waitFor(() -> callOnMain(() -> player.isPlaying()
                    && player.getExoPlayer().getAudioDecoderCounters() != null
                    && player.getExoPlayer().getAudioDecoderCounters()
                            .renderedOutputBufferCount > 0),
                    30));
            runOnMain(() -> {
                assertEquals(PlayerType.AUDIO, player.getPlayerType());
                assertFalse(player.getExoPlayer().getCurrentTracks()
                        .isTypeSelected(C.TRACK_TYPE_VIDEO));
            });
            assertEquals(0, network.requests.get());
        }
    }

    private void assertRenderedVideo(final String url) throws Exception {
        assertTrue("saved video did not render both video and audio", waitFor(
                () -> callOnMain(() -> url.equals(player.getVideoUrl()) && player.isPlaying()
                        && player.getExoPlayer().getVideoDecoderCounters() != null
                        && player.getExoPlayer().getVideoDecoderCounters()
                                .renderedOutputBufferCount > 0
                        && player.getExoPlayer().getAudioDecoderCounters() != null
                        && player.getExoPlayer().getAudioDecoderCounters()
                                .renderedOutputBufferCount > 0), 60));
        runOnMain(() -> {
            assertEquals(PlayerType.MAIN, player.getPlayerType());
            final MainPlayerUi ui = player.UIs().get(MainPlayerUi.class).orElseThrow();
            assertNotNull(ui.getBinding().surfaceView.getHolder().getSurface());
            assertTrue(ui.getBinding().surfaceView.isShown());
            assertEquals(View.VISIBLE, ui.getBinding().playbackEndTime.getVisibility());
            assertTrue(player.getExoPlayer().getCurrentTracks().isTypeSelected(C.TRACK_TYPE_AUDIO));
            assertTrue(player.getExoPlayer().getCurrentTracks().isTypeSelected(C.TRACK_TYPE_VIDEO));
        });
    }

    private OfflineStore.Entry save(final boolean video) throws Exception {
        final boolean webm = "webm".equals(InstrumentationRegistry.getArguments()
                .getString("offlineVideoFixture", "mp4"));
        final String asset = video ? webm ? "offline-generated-av.webm" : "offline-generated-av.mp4"
                : "offline-generated-audio.m4a";
        final OfflineStore.Entry entry = library.store().create("Generated offline fixture", "", 0,
                "file:///generated/" + UUID.randomUUID() + "/" + asset,
                video ? webm ? "video/webm" : "video/mp4" : "audio/mp4");
        created.add(entry.id);
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext()
                .getAssets().open(asset)) {
            library.store().copy(entry.id, input);
        }
        return library.store().get(entry.id);
    }
}
