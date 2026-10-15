package org.schabi.newpipe.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.local.history.HistoryRecordManager;
import org.schabi.newpipe.offline.OfflineLibrary;
import org.schabi.newpipe.offline.OfflineStore;
import org.schabi.newpipe.player.helper.LastPlaybackSessionStore;
import org.schabi.newpipe.player.playqueue.OfflinePlayQueue;
import org.schabi.newpipe.util.NavigationHelper;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Generated saved media and fixture-scoped history only. Compile-only until Windows QA runs it. */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class ActiveSessionResumeTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final CountDownLatch connected = new CountDownLatch(1);
    private final List<OfflineStore.Entry> entries = new ArrayList<>();
    private OfflineLibrary library;
    private PlayerService service;
    private Player player;
    private File audio;
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
        library = OfflineLibrary.get(context);
        audio = SavedPlaybackTestSupport.createSilentWav(context, 300);
        for (final String id : List.of("first", "last-active")) {
            final OfflineStore.Entry entry = library.store().create("Generated session " + id,
                    "", 0, Uri.fromFile(audio).toString(), "audio/wav");
            entries.add(entry);
            try (FileInputStream input = new FileInputStream(audio)) {
                library.store().copy(entry.id, input);
            }
        }
        ContextCompat.startForegroundService(context, new Intent(context, PlayerService.class)
                .putExtra(PlayerService.SHOULD_START_FOREGROUND_EXTRA, true));
        bound = context.bindService(new Intent(context, PlayerService.class)
                .setAction(PlayerService.BIND_PLAYER_HOLDER_ACTION), connection,
                Context.BIND_AUTO_CREATE);
        assertTrue(bound);
        assertTrue(connected.await(10, TimeUnit.SECONDS));
        assertTrue(SavedPlaybackTestSupport.waitFor(() -> SavedPlaybackTestSupport.callOnMain(
                () -> service.getPlayer() != null), 10));
        player = SavedPlaybackTestSupport.callOnMain(service::getPlayer);
    }

    @After
    public void tearDown() throws Exception {
        if (service != null) {
            SavedPlaybackTestSupport.runOnMain(service::destroyPlayerAndStopService);
        }
        if (bound) {
            context.unbindService(connection);
        }
        final LastPlaybackSessionStore sessions = new LastPlaybackSessionStore(
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(context));
        final LastPlaybackSessionStore.Snapshot snapshot = sessions.load();
        if (snapshot != null && entries.stream().anyMatch(entry -> entry.localUrl().equals(
                snapshot.getQueue().getItem().getUrl()))) {
            sessions.clear();
        }
        final HistoryRecordManager history = new HistoryRecordManager(context);
        for (final OfflineStore.Entry entry : entries) {
            final StreamInfo info = library.streamInfo(0, entry.localUrl());
            final var state = history.loadStreamState(info).blockingGet();
            if (state != null) {
                history.deleteStreamHistoryAndState(state.getStreamUid()).blockingAwait();
            }
            library.store().delete(entry.id);
        }
        if (audio != null) {
            assertTrue(audio.delete());
        }
    }

    @Test
    public void historicalSavedSelectionAndSameActiveSelectionStartAtZero() throws Exception {
        final StreamInfo info = library.streamInfo(0, entries.get(1).localUrl());
        final HistoryRecordManager history = new HistoryRecordManager(context);
        history.saveStreamState(info, 45_000).blockingAwait();
        startFresh(queue());
        assertEquals(45_000, history.loadStreamState(info).blockingGet().getProgressMillis());
        SavedPlaybackTestSupport.runOnMain(() -> {
            player.getExoPlayer().seekTo(12_000);
            player.pause();
        });
        assertTrue(SavedPlaybackTestSupport.waitFor(() -> SavedPlaybackTestSupport.callOnMain(
                () -> player.getExoPlayer().getCurrentPosition() == 12_000), 10));
        startFresh(SavedPlaybackTestSupport.callOnMain(player::getPlayQueue));
    }

    @Test
    public void lastActiveQueueAndLatestPositionRestorePausedThroughService() throws Exception {
        startFresh(queue());
        SavedPlaybackTestSupport.runOnMain(() -> {
            player.getExoPlayer().seekTo(12_000);
            player.saveStreamProgressState();
            player.getExoPlayer().seekTo(23_000);
        });
        assertTrue(SavedPlaybackTestSupport.waitFor(() -> SavedPlaybackTestSupport.callOnMain(
                () -> player.getExoPlayer().getCurrentPosition() == 23_000), 10));
        SavedPlaybackTestSupport.runOnMain(player::saveLastSession);
        final LastPlaybackSessionStore.Snapshot saved = new LastPlaybackSessionStore(
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)).load();
        assertNotNull(saved);
        assertEquals(1, saved.getQueue().getIndex());
        assertEquals(23_000, saved.getQueue().getItem().getRecoveryPosition());
        player = SavedPlaybackTestSupport.restoreSavedService(context, service);
        assertTrue(SavedPlaybackTestSupport.waitFor(() -> SavedPlaybackTestSupport.callOnMain(
                () -> player.getExoPlayer().getPlaybackState()
                        == com.google.android.exoplayer2.Player.STATE_READY), 10));
        SavedPlaybackTestSupport.runOnMain(() -> {
            assertFalse(player.getPlayWhenReady());
            assertEquals(2, player.getPlayQueue().size());
            assertEquals(1, player.getPlayQueue().getIndex());
            assertEquals(entries.get(0).localUrl(), player.getPlayQueue().getItem(0).getUrl());
            assertEquals(entries.get(1).localUrl(), player.getPlayQueue().getItem().getUrl());
            assertEquals(23_000, player.getExoPlayer().getCurrentPosition());
        });
    }

    private void startFresh(final org.schabi.newpipe.player.playqueue.PlayQueue queue)
            throws Exception {
        SavedPlaybackTestSupport.runOnMain(() -> NavigationHelper.playOnBackgroundPlayer(
                context, queue, true));
        assertTrue(SavedPlaybackTestSupport.waitFor(() -> SavedPlaybackTestSupport.callOnMain(
                () -> player.getExoPlayer() != null
                        && player.getExoPlayer().getPlaybackState()
                        == com.google.android.exoplayer2.Player.STATE_READY
                        && player.getExoPlayer().getCurrentPosition() < 1000), 10));
        SavedPlaybackTestSupport.runOnMain(player::pause);
        assertTrue(SavedPlaybackTestSupport.callOnMain(
                () -> player.getExoPlayer().getCurrentPosition() < 1000));
    }

    private OfflinePlayQueue queue() {
        final List<StreamInfoItem> items = new ArrayList<>();
        for (final OfflineStore.Entry entry : entries) {
            final StreamInfoItem item = new StreamInfoItem(0, entry.localUrl(), entry.title,
                    StreamType.AUDIO_STREAM);
            item.setDuration(300);
            items.add(item);
        }
        return new OfflinePlayQueue(items, 1);
    }
}
