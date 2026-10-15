package org.schabi.newpipe.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.core.content.IntentCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.PlaybackException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.MockedConstruction;
import org.schabi.newpipe.database.stream.model.StreamStateEntity;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.local.history.HistoryRecordManager;
import org.schabi.newpipe.player.helper.PlaybackRecovery;
import org.schabi.newpipe.player.helper.PlayerHelper;
import org.schabi.newpipe.player.helper.AudioReactor;
import org.schabi.newpipe.player.helper.SleepTimer;
import org.schabi.newpipe.player.helper.LastPlaybackSessionStore;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.player.playqueue.PlayQueueItem;
import org.schabi.newpipe.player.playqueue.SinglePlayQueue;
import org.schabi.newpipe.player.ui.MainPlayerUi;
import org.schabi.newpipe.player.ui.PlayerUiList;
import org.schabi.newpipe.util.DependentPreferenceHelper;
import org.schabi.newpipe.util.SerializedCache;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.SerialDisposable;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.schedulers.TestScheduler;

/** Runs real intent policy; stops only at the Android player allocation boundary. */
public class ActiveSessionResumeTest {
    private Player player;
    private ExoPlayer exo;
    private HistoryRecordManager history;
    private Intent intent;
    private PlayQueue requested;
    private long position;
    private MockedStatic<android.util.Log> logging;
    private MockedStatic<IntentCompat> intents;
    private MockedStatic<SerializedCache> caches;
    private MockedStatic<DependentPreferenceHelper> preferences;

    @Before
    public void setUp() throws Exception {
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(ignored -> Schedulers.trampoline());
        RxAndroidPlugins.setMainThreadSchedulerHandler(ignored -> Schedulers.trampoline());
        RxJavaPlugins.setErrorHandler(error -> {
            if (!(error.getCause() instanceof PlayerAllocationBoundary)) {
                throw new AssertionError(error);
            }
        });
        logging = mockStatic(android.util.Log.class);
        player = mock(Player.class);
        doCallRealMethod().when(player).handleIntent(any(Intent.class));
        exo = mock(ExoPlayer.class);
        position = 45_000;
        when(exo.getCurrentPosition()).thenAnswer(ignored -> position);
        doAnswer(call -> {
            position = call.getArgument(1);
            return null;
        }).when(exo).seekTo(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyLong());
        when(exo.getPlaybackState()).thenReturn(com.google.android.exoplayer2.Player.STATE_READY);
        history = mock(HistoryRecordManager.class);
        when(history.loadStreamState(any(PlayQueueItem.class)))
                .thenReturn(Maybe.just(new StreamStateEntity(1, 45_000)));
        set("simpleExoPlayer", exo);
        set("playerType", PlayerType.MAIN);
        set("context", mock(Context.class));
        set("recordManager", history);
        set("sleepTimer", mock(SleepTimer.class));
        set("recommendationRequest", new SerialDisposable());
        set("recoveryRequest", new SerialDisposable());
        set("sessionQueueChanges", new SerialDisposable());
        set("playbackRecovery", new PlaybackRecovery());
        set("databaseUpdateDisposable", new CompositeDisposable());
        final PlayerUiList uis = mock(PlayerUiList.class);
        when(uis.get(MainPlayerUi.class)).thenReturn(Optional.of(mock(MainPlayerUi.class)));
        doAnswer(ignored -> {
            throw new PlayerAllocationBoundary();
        }).when(uis).call(any());
        set("UIs", uis);
        intent = mock(Intent.class);
        when(intent.getStringExtra(Player.PLAY_QUEUE_KEY)).thenReturn("fixture");
        when(intent.getBooleanExtra(Player.PLAY_WHEN_READY, true)).thenReturn(true);
        when(intent.getBooleanExtra(Player.RESUME_PLAYBACK, false)).thenReturn(true);
        when(intent.getBooleanExtra(Player.FRESH_SELECTION, false)).thenReturn(true);
        intents = mockStatic(IntentCompat.class);
        intents.when(() -> IntentCompat.getSerializableExtra(intent, Player.PLAYER_INTENT_TYPE,
                PlayerIntentType.class)).thenReturn(PlayerIntentType.AllOthers);
        intents.when(() -> IntentCompat.getSerializableExtra(intent, Player.PLAYER_TYPE,
                PlayerType.class)).thenReturn(PlayerType.MAIN);
        final SerializedCache cache = mock(SerializedCache.class);
        when(cache.take("fixture", PlayQueue.class)).thenAnswer(ignored -> requested);
        caches = mockStatic(SerializedCache.class);
        caches.when(SerializedCache::getInstance).thenReturn(cache);
        preferences = mockStatic(DependentPreferenceHelper.class);
        preferences.when(() -> DependentPreferenceHelper.getResumePlaybackEnabled(any()))
                .thenReturn(true);
    }

    @After
    public void tearDown() {
        logging.close();
        preferences.close();
        caches.close();
        intents.close();
        RxAndroidPlugins.reset();
        RxJavaPlugins.reset();
    }

    @Test
    public void freshHistoricalOnlineSelectionStartsAtZero() throws Exception {
        assertHistoricalSelectionStartsAtZero("https://example.invalid/old-online");
    }

    @Test
    public void freshHistoricalSavedSelectionStartsAtZero() throws Exception {
        assertHistoricalSelectionStartsAtZero("backtube-offline:generated-old-saved");
    }

    @Test
    public void freshSelectionOfSameActiveItemStartsAtZero() throws Exception {
        requested = queue("https://example.invalid/active");
        set("playQueue", queue("https://example.invalid/active"));
        dispatch();
        assertEquals(0, position);
    }

    @Test
    public void freshSameItemDiscardsStaleQueueRecovery() throws Exception {
        requested = queue("https://example.invalid/active");
        requested.setRecovery(0, 45_000);
        set("playQueue", queue("https://example.invalid/active"));
        dispatch();
        assertEquals(0, position);
    }

    @Test
    public void freshEqualPlaylistDiscardsOtherItemsRecoveryBeforeAutoAdvance() throws Exception {
        final PlayQueue active = twoItemQueue();
        active.setRecovery(1, 31_000);
        requested = twoItemQueue();
        set("playQueue", active);
        dispatch();
        assertEquals(0, position);
        active.setIndex(1);
        set("currentItem", active.getItem(0));
        final Timeline timeline = mock(Timeline.class);
        when(timeline.getWindowCount()).thenReturn(2);
        when(exo.getCurrentTimeline()).thenReturn(timeline);
        doAnswer(ignored -> {
            position = 0;
            return null;
        }).when(exo).seekToDefaultPosition(1);
        doCallRealMethod().when(player).onPlaybackSynchronize(any(),
                org.mockito.ArgumentMatchers.anyBoolean());
        player.onPlaybackSynchronize(active.getItem(), false);
        assertEquals(0, position);
    }

    @Test
    public void freshEqualDisposedQueueStartsAtZeroAtInitialization() throws Exception {
        assertDisposedQueueStart(true, 0);
    }

    @Test
    public void rebindEqualDisposedQueuePreservesPendingRecovery() throws Exception {
        assertDisposedQueueStart(false, 45_000);
    }

    @Test
    public void freshSameItemCancelsPendingOldPositionRetry() throws Exception {
        assertPendingRetryPolicy(true, false, false, 0);
    }

    @Test
    public void freshEqualPlaylistCancelsPendingOldPositionRetry() throws Exception {
        assertPendingRetryPolicy(true, true, false, 0);
    }

    @Test
    public void freshTimestampWinsOverPendingOldPositionRetry() throws Exception {
        assertPendingRetryPolicy(true, false, true, 17_000);
    }

    @Test
    public void nonfreshRebindRetainsPendingOldPositionRetry() throws Exception {
        assertPendingRetryPolicy(false, false, false, 45_000);
    }

    @Test
    public void freshSelectionRestartsRetryBudgetAndClearsPriorFailureFlags() throws Exception {
        final TestScheduler scheduler = pendingRetry(false);
        // Consume the second attempt and leave the previous recovery's failure flags attached.
        player.onPlayerError(temporaryError());
        for (final String flag : java.util.List.of("recoveryFailed", "recoveryCanceled",
                "recoveryRefreshingSource", "recoveryReloading")) {
            set(flag, true);
        }
        dispatch();
        player.onPlayerError(temporaryError());
        scheduler.advanceTimeBy(1, TimeUnit.SECONDS);
        final PlayQueue active = (PlayQueue) get("playQueue");
        assertEquals(0, active.getItem().getRecoveryPosition());
        verify(player, times(1)).reloadPlayQueueManager();
    }

    @Test
    public void freshEqualUnsetPlaylistBoundsRecoveryEventsAndSessionWrites() throws Exception {
        assertBoundedFreshQueueReset(false);
    }

    @Test
    public void freshEqualStalePlaylistBoundsRecoveryEventsAndSessionWrites() throws Exception {
        assertBoundedFreshQueueReset(true);
    }

    @Test
    public void freshTimestampSelectionPreservesExplicitStart() throws Exception {
        final StreamInfo info = new StreamInfo(0, "fixture", "https://example.invalid/active",
                StreamType.AUDIO_STREAM, "Generated timestamp fixture", "", 0);
        info.setStartPosition(17);
        requested = new SinglePlayQueue(info);
        set("playQueue", queue(info.getUrl()));
        dispatch();
        assertEquals(17_000, position);
    }

    @Test
    public void automaticReturnToPreviouslyPausedQueueItemStartsAtZero() throws Exception {
        final PlayQueue queue = queue("https://example.invalid/first");
        queue.append(java.util.List.of(queue("https://example.invalid/second").getItem()));
        set("playQueue", queue);
        set("currentMetadata", mock(org.schabi.newpipe.player.mediaitem.MediaItemTag.class));
        when(exo.getContentPosition()).thenReturn(45_000L);
        when(player.getCurrentStreamInfo()).thenReturn(Optional.empty());
        doCallRealMethod().when(player).saveStreamProgressState();
        player.saveStreamProgressState();
        set("currentItem", queue.getItem(1));
        when(exo.getCurrentMediaItemIndex()).thenReturn(1);
        final Timeline timeline = mock(Timeline.class);
        when(timeline.getWindowCount()).thenReturn(2);
        when(exo.getCurrentTimeline()).thenReturn(timeline);
        doAnswer(ignored -> {
            position = 0;
            return null;
        }).when(exo).seekToDefaultPosition(0);
        doCallRealMethod().when(player).onPlaybackSynchronize(any(),
                org.mockito.ArgumentMatchers.anyBoolean());
        player.onPlaybackSynchronize(queue.getItem(), false);
        assertEquals(0, position);
    }

    @Test
    public void rebindOfSameActiveItemPreservesPosition() throws Exception {
        requested = queue("https://example.invalid/active");
        set("playQueue", queue("https://example.invalid/active"));
        when(intent.getBooleanExtra(Player.FRESH_SELECTION, false)).thenReturn(false);
        dispatch();
        assertEquals(45_000, position);
    }

    @Test
    public void modeSwitchWithRecoveryPreservesPosition() throws Exception {
        requested = queue("https://example.invalid/active");
        requested.setRecovery(0, 29_000);
        set("playQueue", queue("https://example.invalid/active"));
        when(intent.getBooleanExtra(Player.FRESH_SELECTION, false)).thenReturn(false);
        dispatch();
        assertEquals(29_000, position);
    }

    @Test
    public void freshSelectionAfterConsumedTimestampStartsAtZero() throws Exception {
        final StreamInfo info = timestampInfo();
        info.setStartPosition(17);
        requested = new SinglePlayQueue(info);
        set("playQueue", queue(info.getUrl()));
        dispatch();
        assertEquals(17_000, position);
        // A later selection reuses the queue data after the first timestamp request completed.
        dispatch();
        assertEquals(0, position);
    }

    @Test
    public void explicitTimestampDoesNotChangeCachedInfoForLaterFreshSelection() throws Exception {
        final StreamInfo info = timestampInfo();
        set("playQueue", queue(info.getUrl()));
        set("streamItemDisposable", new CompositeDisposable());
        final TimestampChangeData data = new TimestampChangeData(0, info.getUrl(), 17);
        intents.when(() -> IntentCompat.getSerializableExtra(intent, Player.PLAYER_INTENT_TYPE,
                PlayerIntentType.class)).thenReturn(PlayerIntentType.TimestampChange);
        intents.when(() -> IntentCompat.getParcelableExtra(intent, Player.PLAYER_INTENT_DATA,
                TimestampChangeData.class)).thenReturn(data);
        try (MockedStatic<org.schabi.newpipe.util.ExtractorHelper> extractor =
                     mockStatic(org.schabi.newpipe.util.ExtractorHelper.class)) {
            extractor.when(() -> org.schabi.newpipe.util.ExtractorHelper.getStreamInfo(
                    0, info.getUrl(), false))
                    .thenReturn(io.reactivex.rxjava3.core.Single.just(info));
            RxJavaPlugins.setIoSchedulerHandler(ignored -> Schedulers.trampoline());
            dispatch();
            assertEquals(17_000, position);
        }
        requested = new SinglePlayQueue(info);
        intents.when(() -> IntentCompat.getSerializableExtra(intent, Player.PLAYER_INTENT_TYPE,
                PlayerIntentType.class)).thenReturn(PlayerIntentType.AllOthers);
        dispatch();
        assertEquals(0, position);
    }

    @Test
    public void explicitZeroTimestampSeeksToZero() throws Exception {
        final StreamInfo info = timestampInfo();
        set("playQueue", queue(info.getUrl()));
        set("streamItemDisposable", new CompositeDisposable());
        final TimestampChangeData data = new TimestampChangeData(0, info.getUrl(), 0);
        intents.when(() -> IntentCompat.getSerializableExtra(intent, Player.PLAYER_INTENT_TYPE,
                PlayerIntentType.class)).thenReturn(PlayerIntentType.TimestampChange);
        intents.when(() -> IntentCompat.getParcelableExtra(intent, Player.PLAYER_INTENT_DATA,
                TimestampChangeData.class)).thenReturn(data);
        try (MockedStatic<org.schabi.newpipe.util.ExtractorHelper> extractor =
                     mockStatic(org.schabi.newpipe.util.ExtractorHelper.class)) {
            extractor.when(() -> org.schabi.newpipe.util.ExtractorHelper.getStreamInfo(
                    0, info.getUrl(), false))
                    .thenReturn(io.reactivex.rxjava3.core.Single.just(info));
            RxJavaPlugins.setIoSchedulerHandler(ignored -> Schedulers.trampoline());
            dispatch();
        }
        assertEquals(0, position);
    }

    private static StreamInfo timestampInfo() {
        return new StreamInfo(0, "fixture", "https://example.invalid/active",
                StreamType.AUDIO_STREAM, "Generated timestamp fixture", "", 0);
    }

    private void assertPendingRetryPolicy(final boolean fresh, final boolean multiple,
                                          final boolean timestamp, final long expected)
            throws Exception {
        final TestScheduler scheduler = pendingRetry(multiple);
        when(intent.getBooleanExtra(Player.FRESH_SELECTION, false)).thenReturn(fresh);
        if (timestamp) {
            final StreamInfo info = timestampInfo();
            info.setStartPosition(17);
            requested = new SinglePlayQueue(info);
        }
        dispatch();
        scheduler.advanceTimeBy(4, TimeUnit.SECONDS);
        final PlayQueue active = (PlayQueue) get("playQueue");
        if (active.getItem().getRecoveryPosition() != PlayQueueItem.RECOVERY_UNSET) {
            synchronize(active);
        }
        assertEquals(expected, position);
        verify(player, times(fresh ? 0 : 1)).reloadPlayQueueManager();
    }

    private TestScheduler pendingRetry(final boolean multiple) throws Exception {
        final PlayQueue active = multiple ? twoItemQueue() : queue(timestampInfo().getUrl());
        requested = multiple ? twoItemQueue() : queue(timestampInfo().getUrl());
        set("playQueue", active);
        when(exo.getPlaybackState())
                .thenReturn(com.google.android.exoplayer2.Player.STATE_BUFFERING);
        doCallRealMethod().when(player).onPlayerError(any());
        final TestScheduler scheduler = new TestScheduler();
        RxAndroidPlugins.setMainThreadSchedulerHandler(ignored -> scheduler);
        player.onPlayerError(temporaryError());
        return scheduler;
    }

    private static PlaybackException temporaryError() {
        try (MockedStatic<android.os.SystemClock> clock =
                     mockStatic(android.os.SystemClock.class)) {
            return new PlaybackException("Generated transient fixture", null,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED);
        }
    }

    private void synchronize(final PlayQueue active) {
        final Timeline timeline = mock(Timeline.class);
        when(timeline.getWindowCount()).thenReturn(active.size());
        when(exo.getCurrentTimeline()).thenReturn(timeline);
        doAnswer(ignored -> {
            position = 0;
            return null;
        }).when(exo).seekToDefaultPosition(active.getIndex());
        doCallRealMethod().when(player).onPlaybackSynchronize(any(),
                org.mockito.ArgumentMatchers.anyBoolean());
        player.onPlaybackSynchronize(active.getItem(), true);
    }

    private void assertBoundedFreshQueueReset(final boolean stale) throws Exception {
        final PlayQueue active = largeQueue();
        requested = largeQueue();
        if (stale) {
            for (int i = 0; i < active.size(); i++) {
                active.setRecovery(i, 45_000);
                requested.setRecovery(i, 31_000);
            }
        }
        active.init();
        requested.init();
        set("playQueue", active);
        final SharedPreferences prefs =
                ActiveSessionPersistenceTest.preferences(new HashMap<>());
        set("lastSessionStore", new LastPlaybackSessionStore(prefs));
        doCallRealMethod().when(player).saveLastSession();
        final int[] activeEvents = {0};
        final int[] incomingEvents = {0};
        try (MockedStatic<android.os.SystemClock> clock =
                     mockStatic(android.os.SystemClock.class)) {
            // Same actual save callback as Player.initPlayback's active queue subscription.
            final var sessions = active.getBroadcastReceiver().subscribe(event -> {
                activeEvents[0]++;
                player.saveLastSession();
            });
            final var incoming = requested.getBroadcastReceiver()
                    .subscribe(event -> incomingEvents[0]++);
            activeEvents[0] = 0;
            incomingEvents[0] = 0;
            clearInvocations(prefs.edit());
            try {
                dispatch();
            } finally {
                sessions.dispose();
                incoming.dispose();
            }
            final long saves = org.mockito.Mockito.mockingDetails(prefs.edit()).getInvocations()
                    .stream().filter(call -> call.getMethod().getName().equals("apply")).count();
            assertTrue("Active recovery events=" + activeEvents[0] + ", session writes=" + saves,
                    activeEvents[0] <= 2);
            assertTrue("Incoming recovery events=" + incomingEvents[0], incomingEvents[0] <= 2);
            verify(prefs.edit(), org.mockito.Mockito.atMost(2)).apply();
            assertEquals(0, position);
            for (int i = 1; i < active.size(); i++) {
                assertEquals(PlayQueueItem.RECOVERY_UNSET, active.getItem(i).getRecoveryPosition());
                assertEquals(PlayQueueItem.RECOVERY_UNSET,
                        requested.getItem(i).getRecoveryPosition());
            }
        }
    }

    private static PlayQueue largeQueue() {
        final PlayQueue queue = queue("https://example.invalid/first");
        for (int i = 1; i < 24; i++) {
            queue.append(java.util.List.of(queue("https://example.invalid/item-" + i).getItem()));
        }
        return queue;
    }

    private void assertDisposedQueueStart(final boolean fresh, final long expected)
            throws Exception {
        final PlayQueue active = spy(twoItemQueue());
        active.setRecovery(0, 45_000);
        active.dispose();
        requested = spy(twoItemQueue());
        set("playQueue", active);
        when(intent.getBooleanExtra(Player.FRESH_SELECTION, false)).thenReturn(fresh);
        when(player.exoPlayerIsNull()).thenReturn(true);
        final long[] initializedPosition = {-1};
        for (final PlayQueue candidate : java.util.List.of(active, requested)) {
            doAnswer(ignored -> {
                initializedPosition[0] = candidate.getItem().getRecoveryPosition();
                throw new PlayerAllocationBoundary();
            }).when(candidate).init();
        }
        final SharedPreferences prefs = mock(SharedPreferences.class);
        when(prefs.getBoolean(any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(true);
        when(player.getPrefs()).thenReturn(prefs);
        when(player.getContext()).thenReturn(mock(Context.class));
        final Field field = Player.class.getDeclaredField("UIs");
        field.setAccessible(true);
        doNothing().when((PlayerUiList) field.get(player)).call(any());
        try (MockedConstruction<ExoPlayer.Builder> builders = mockConstruction(
                ExoPlayer.Builder.class,
                org.mockito.Mockito.withSettings().defaultAnswer(org.mockito.Mockito.RETURNS_SELF),
                (builder, ignored) -> when(builder.build()).thenReturn(exo));
             MockedConstruction<AudioReactor> audio = mockConstruction(AudioReactor.class);
             MockedStatic<PlayerHelper> helpers = mockStatic(PlayerHelper.class);
             MockedStatic<PreferenceManager> settings = mockStatic(PreferenceManager.class);
             MockedStatic<ContextCompat> contexts = mockStatic(ContextCompat.class)) {
            helpers.when(() -> PlayerHelper.retrievePlaybackParametersFromPrefs(player))
                    .thenReturn(new com.google.android.exoplayer2.PlaybackParameters(1));
            settings.when(() -> PreferenceManager.getDefaultSharedPreferences(any()))
                    .thenReturn(prefs);
            dispatch();
        }
        assertEquals(expected, initializedPosition[0]);
    }

    private static PlayQueue twoItemQueue() {
        final PlayQueue queue = queue("https://example.invalid/first");
        queue.append(java.util.List.of(queue("https://example.invalid/second").getItem()));
        return queue;
    }

    private void assertHistoricalSelectionStartsAtZero(final String url) throws Exception {
        requested = queue(url);
        set("playQueue", queue("https://example.invalid/current"));
        dispatch();
        final long recovery = requested.getItem().getRecoveryPosition();
        assertEquals(0, recovery == PlayQueueItem.RECOVERY_UNSET ? 0 : recovery);
    }

    private void dispatch() {
        try {
            player.handleIntent(intent);
        } catch (final PlayerAllocationBoundary ignored) {
            // Intent policy has completed. Real Android/Exo allocation is a device boundary.
        }
    }

    private void set(final String name, final Object value) throws Exception {
        final Field field = Player.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(player, value);
    }

    private Object get(final String name) throws Exception {
        final Field field = Player.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(player);
    }

    private static PlayQueue queue(final String url) {
        final StreamInfoItem item = new StreamInfoItem(0, url, "Generated fixture",
                StreamType.AUDIO_STREAM);
        item.setDuration(300);
        return new SinglePlayQueue(item);
    }

    private static final class PlayerAllocationBoundary extends RuntimeException {
    }
}
