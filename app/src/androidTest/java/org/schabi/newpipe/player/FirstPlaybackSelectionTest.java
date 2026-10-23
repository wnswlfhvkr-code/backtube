package org.schabi.newpipe.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.schabi.newpipe.player.SavedPlaybackTestSupport.callOnMain;
import static org.schabi.newpipe.player.SavedPlaybackTestSupport.runOnMain;
import static org.schabi.newpipe.player.SavedPlaybackTestSupport.waitFor;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.IBinder;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.App;
import org.schabi.newpipe.MainActivity;
import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.fragments.detail.VideoDetailFragment;
import org.schabi.newpipe.offline.OfflineLibrary;
import org.schabi.newpipe.offline.OfflineStore;
import org.schabi.newpipe.player.helper.LastPlaybackSessionStore;
import org.schabi.newpipe.player.helper.PlayerHolder;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.player.playqueue.SinglePlayQueue;
import org.schabi.newpipe.util.InfoCache;
import org.schabi.newpipe.util.NavigationHelper;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Separate instrumentation processes exercise the first user selection after force-stop. */
@RunWith(AndroidJUnit4.class)
public class FirstPlaybackSelectionTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final SharedPreferences fixture = context.getSharedPreferences(
            "first_playback_selection_fixture", Context.MODE_PRIVATE);
    private final String phase = InstrumentationRegistry.getArguments()
            .getString("first_selection_phase", "");
    private final String path = InstrumentationRegistry.getArguments()
            .getString("first_selection_path", "online");
    private final String prior = InstrumentationRegistry.getArguments()
            .getString("first_selection_prior", "saved");
    private final CountDownLatch connected = new CountDownLatch(1);
    private PlayerService service;
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

    @Test public void firstSelectionAcrossProcessRestart() throws Exception {
        org.junit.Assume.assumeTrue(!phase.isEmpty());
        assertFalse("fixture must begin without a player",
                PlayerHolder.getInstance().isPlayerOpen());
        final SharedPreferences preferences =
                PreferenceManager.getDefaultSharedPreferences(context);
        preferences.edit().putBoolean(context.getString(R.string.auto_queue_key), false)
                .putBoolean(context.getString(R.string.show_comments_key), false)
                .putBoolean(context.getString(R.string.start_main_player_fullscreen_key), false)
                .commit();
        App.getInstance().setNotificationsRequested();
        if ("seed".equals(phase)) {
            seed();
            return;
        }
        assertTrue("force-stop did not replace the application process",
                fixture.getInt("seed_pid", -1) != android.os.Process.myPid());
        final LastPlaybackSessionStore.Snapshot snapshot =
                new LastPlaybackSessionStore(preferences).load();
        if ("none".equals(prior)) {
            assertTrue(snapshot == null);
        } else {
            assertNotNull(snapshot);
            assertEquals("Previous B", snapshot.getQueue().getItem().getTitle());
        }
        final OfflineLibrary library = OfflineLibrary.get(context);
        final PlayQueue selected;
        final String expectedUrl;
        final String expectedMedia;
        if ("online".equals(path)) {
            final StreamInfo info = onlineInfo(new File(fixture.getString("audio", "")));
            InfoCache.getInstance().putInfo(1, info.getUrl(), info, InfoCache.Type.STREAM);
            selected = new SinglePlayQueue(info);
            expectedUrl = info.getUrl();
            expectedMedia = Uri.fromFile(new File(fixture.getString("audio", ""))).toString();
        } else {
            final OfflineStore.Entry entry = library.store().get(fixture.getString("selected", ""));
            assertNotNull(entry);
            selected = library.queue(entry);
            expectedUrl = entry.localUrl();
            expectedMedia = Uri.fromFile(library.store().file(entry)).toString();
        }
        try (SavedPlaybackTestSupport.RejectNetwork network =
                     new SavedPlaybackTestSupport.RejectNetwork()) {
            if ("restored".equals(prior)) {
                ContextCompat.startForegroundService(context,
                        new Intent(context, PlayerService.class)
                        .setAction(PlayerService.ACTION_RESTORE_LAST_SESSION)
                        .putExtra(PlayerService.SHOULD_START_FOREGROUND_EXTRA, true));
                bind();
                assertTrue("previous B was not restored paused", waitFor(() -> callOnMain(() ->
                        service.getPlayer() != null && service.getPlayer().getPlayQueue() != null
                                && !service.getPlayer().getPlayWhenReady()), 30));
            }
            final MainActivity activity = (MainActivity) InstrumentationRegistry
                    .getInstrumentation()
                    .startActivitySync(new Intent(context, MainActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            runOnMain(() -> {
                if ("audio".equals(path)) {
                    NavigationHelper.playOnBackgroundPlayer(context, selected, true);
                    NavigationHelper.openPlayQueue(context);
                } else {
                    NavigationHelper.playOnMainPlayer(activity, selected);
                }
            });
            if (!bound) {
                bind();
            }
            final boolean selectedPlaying = waitFor(() -> callOnMain(() ->
                    service.getPlayer() != null && service.getPlayer().isPlaying()
                            && expectedUrl.equals(service.getPlayer().getVideoUrl())), 60);
            if (!selectedPlaying) {
                PlaybackTestUi.captureScreen(context, "first-selection-failure.png");
            }
            assertTrue(callOnMain(() -> selectionDiagnostic(activity, expectedUrl)),
                    selectedPlaying);
            final Player player = callOnMain(service::getPlayer);
            assertTrue("first selection produced no audio frames", waitFor(() -> callOnMain(() ->
                    player.getExoPlayer().getAudioDecoderCounters() != null
                            && player.getExoPlayer().getAudioDecoderCounters()
                            .renderedOutputBufferCount > 0), 30));
            if ("video".equals(path)) {
                assertTrue("first selected video produced no video frames", waitFor(
                        () -> callOnMain(() -> player.getExoPlayer()
                                .getVideoDecoderCounters() != null
                                && player.getExoPlayer().getVideoDecoderCounters()
                                .renderedOutputBufferCount > 0), 30));
            }
            runOnMain(() -> {
                assertEquals(expectedUrl, player.getPlayQueue().getItem().getUrl());
                assertEquals(expectedUrl, org.schabi.newpipe.player.mediaitem.MediaItemTag
                        .from(player.getExoPlayer().getCurrentMediaItem()).orElseThrow()
                        .getStreamUrl());
                assertEquals(expectedMedia, player.getExoPlayer().getCurrentMediaItem()
                        .localConfiguration.uri.toString());
                assertEquals("Selected A", player.getCurrentStreamInfo().orElseThrow().getName());
                if ("online".equals(path)) {
                    // A uses a distinct 60-second WAV; B is a four-second WAV.
                    assertEquals(60000, player.getExoPlayer().getDuration(), 100);
                }
                if (!"audio".equals(path)) {
                    final VideoDetailFragment detail = (VideoDetailFragment) activity
                            .getSupportFragmentManager()
                            .findFragmentById(R.id.fragment_player_holder);
                    assertNotNull(detail);
                    assertEquals(expectedUrl, detail.getUrl());
                    assertEquals("Selected A", ((TextView) activity
                            .findViewById(R.id.detail_video_title_view)).getText().toString());
                }
                player.pause();
            });
            if ("audio".equals(path)) {
                assertTrue("audio controller displayed another item", waitFor(() -> callOnMain(() ->
                        SavedPlaybackTestSupport.controllerReady(player)), 30));
            }
            assertEquals("first selection attempted a provider request", 0, network.requests.get());
        }
    }

    private void seed() throws Exception {
        fixture.edit().clear().commit();
        final OfflineLibrary library = OfflineLibrary.get(context);
        final File previous = SavedPlaybackTestSupport.createSilentWav(context, 4);
        final OfflineStore.Entry entry = library.store().create("Previous B", "", 0,
                "file:///generated/previous-B", "audio/wav");
        try (InputStream input = new FileInputStream(previous)) {
            library.store().copy(entry.id, input);
        }
        previous.delete();
        fixture.edit().putString("previous", entry.id)
                .putInt("seed_pid", android.os.Process.myPid()).commit();
        if ("online".equals(path)) {
            final File audio = SavedPlaybackTestSupport.createSilentWav(context, 60);
            fixture.edit().putString("audio", audio.getAbsolutePath()).commit();
        } else {
            final boolean video = "video".equals(path);
            final boolean webm = "webm".equals(InstrumentationRegistry.getArguments()
                    .getString("offlineVideoFixture", "mp4"));
            final String asset = video ? webm ? "offline-generated-av.webm"
                    : "offline-generated-av.mp4" : "offline-generated-audio.m4a";
            final String mime = video ? webm ? "video/webm" : "video/mp4" : "audio/mp4";
            final OfflineStore.Entry selected = library.store().create("Selected A", "", 0,
                    "file:///generated/selected-A/" + asset, mime);
            try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext()
                    .getAssets().open(asset)) {
                library.store().copy(selected.id, input);
            }
            fixture.edit().putString("selected", selected.id).commit();
        }
        final LastPlaybackSessionStore sessions = new LastPlaybackSessionStore(
                PreferenceManager.getDefaultSharedPreferences(context));
        if ("none".equals(prior)) {
            sessions.clear();
        } else {
            sessions.save(library.queue(library.store().get(entry.id)), 1200,
                    com.google.android.exoplayer2.Player.REPEAT_MODE_OFF);
        }
        // Drain the session store's apply() before the host force-stops this process.
        assertTrue(PreferenceManager.getDefaultSharedPreferences(context).edit().commit());
    }

    private StreamInfo onlineInfo(final File audio) {
        final String id = "https://example.invalid/first-selection-A";
        final StreamInfo info = new StreamInfo(1, id, id, StreamType.AUDIO_STREAM,
                id, "Selected A", 0);
        info.setUploaderName("Generated fixture");
        info.setDuration(60);
        info.setRelatedItems(List.of());
        info.setAudioStreams(List.of(new AudioStream.Builder().setId("A")
                .setContent(Uri.fromFile(audio).toString(), true).setMediaFormat(MediaFormat.WAV)
                .setAverageBitrate(128).build()));
        return info;
    }

    private void bind() throws Exception {
        // Observe the service started by the user navigation; do not create an empty service.
        bound = context.bindService(new Intent(context, PlayerService.class)
                        .setAction(PlayerService.BIND_PLAYER_HOLDER_ACTION), connection,
                0);
        assertTrue(bound);
        assertTrue(connected.await(30, TimeUnit.SECONDS));
    }

    private String selectionDiagnostic(final MainActivity activity, final String expectedUrl)
            throws Exception {
        final Player current = service.getPlayer();
        final VideoDetailFragment detail = (VideoDetailFragment) activity
                .getSupportFragmentManager().findFragmentById(R.id.fragment_player_holder);
        final StringBuilder result = new StringBuilder("first selection did not play A")
                .append(" hasPlayer=").append(current != null)
                .append(" detailSelected=").append(detail != null
                        && expectedUrl.equals(detail.getUrl()));
        if (current != null) {
            result.append(" type=").append(current.getPlayerType())
                    .append(" hasEngine=").append(!current.exoPlayerIsNull())
                    .append(" ready=").append(current.getPlayWhenReady())
                    .append(" playing=").append(current.isPlaying())
                    .append(" mediaSelected=").append(expectedUrl.equals(current.getVideoUrl()))
                    .append(" queueSelected=").append(current.getPlayQueue() != null
                            && current.getPlayQueue().getItem() != null && expectedUrl.equals(
                            current.getPlayQueue().getItem().getUrl()));
        }
        if (detail != null) {
            final java.lang.reflect.Field error = org.schabi.newpipe.fragments.BaseStateFragment
                    .class.getDeclaredField("lastPanelError");
            error.setAccessible(true);
            final org.schabi.newpipe.error.ErrorInfo info =
                    (org.schabi.newpipe.error.ErrorInfo) error.get(detail);
            if (info != null) {
                for (final String trace : info.getStackTraces()) {
                    final java.util.regex.Matcher names = java.util.regex.Pattern.compile(
                            "(?:java|android|org\\.schabi)[A-Za-z0-9_.$]*(?:Exception|Error)")
                            .matcher(trace);
                    while (names.find()) {
                        result.append(" errorClass=").append(names.group());
                    }
                }
            }
        }
        return result.toString();
    }

    @After public void tearDown() throws Exception {
        if (phase.isEmpty() || "seed".equals(phase)) {
            return;
        }
        runOnMain(() -> {
            for (final Stage stage : Stage.values()) {
                if (stage == Stage.PRE_ON_CREATE || stage == Stage.DESTROYED) {
                    continue;
                }
                for (final android.app.Activity activity : new ArrayList<>(
                        ActivityLifecycleMonitorRegistry.getInstance()
                                .getActivitiesInStage(stage))) {
                    activity.finish();
                }
            }
            PlayerHolder.getInstance().stopService();
            if (service != null) {
                service.destroyPlayerAndStopService();
            }
        });
        if (bound) {
            context.unbindService(connection);
        }
        SavedPlaybackTestSupport.awaitPlaybackTeardown(context);
        new LastPlaybackSessionStore(
                PreferenceManager.getDefaultSharedPreferences(context)).clear();
        for (final String name : new String[]{"previous", "selected"}) {
            final String id = fixture.getString(name, "");
            if (!id.isEmpty()) {
                OfflineLibrary.get(context).store().delete(id);
            }
        }
        final String file = fixture.getString("audio", "");
        if (!file.isEmpty()) {
            new File(file).delete();
        }
        fixture.edit().clear().commit();
    }
}
