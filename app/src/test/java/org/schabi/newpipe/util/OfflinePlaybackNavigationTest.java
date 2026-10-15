package org.schabi.newpipe.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.withSettings;
import static org.mockito.Mockito.never;

import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentManager;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.Lifecycle;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.schabi.newpipe.R;
import org.schabi.newpipe.offline.OfflineLibrary;
import org.schabi.newpipe.offline.OfflineLibraryActivity;
import org.schabi.newpipe.offline.OfflineStore;
import org.schabi.newpipe.player.PlayQueueActivity;
import org.schabi.newpipe.player.Player;
import org.schabi.newpipe.player.PlayerService;
import org.schabi.newpipe.player.playqueue.PlayQueue;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.schedulers.TestScheduler;
import java.util.concurrent.atomic.AtomicInteger;

/** Real routes/owned queue selection; Android delivery and serialization are boundaries. */
public class OfflinePlaybackNavigationTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private final List<Class<?>> targets = new ArrayList<>();
    private final List<PlayQueue> deliveredQueues = new ArrayList<>();
    private Context context;
    private OfflineStore.Entry entry;
    private PlayQueue queue;
    private MockedStatic<SerializedCache> caches;
    private MockedStatic<ContextCompat> services;
    private MockedStatic<Toast> toasts;
    private MockedConstruction<Intent> intents;

    @Before
    public void setUp() throws Exception {
        resetLibrary();
        context = mock(Context.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getFilesDir()).thenReturn(temporary.getRoot());
        when(context.getString(R.string.offline_saved_copy)).thenReturn("Saved copy");
        final OfflineLibrary library = OfflineLibrary.get(context);
        entry = library.store().create("Saved requested track", "https://example.invalid/origin",
                0, "file:///generated-fixture", "audio/wav");
        library.store().copy(entry.id, new ByteArrayInputStream(new byte[]{1, 2, 3}));
        queue = library.queue(entry);
        final SerializedCache cache = mock(SerializedCache.class);
        when(cache.put(any(PlayQueue.class), eq(PlayQueue.class))).thenAnswer(invocation -> {
            deliveredQueues.add(invocation.getArgument(0));
            return "saved-queue-cache";
        });
        caches = mockStatic(SerializedCache.class);
        caches.when(SerializedCache::getInstance).thenReturn(cache);
        services = mockStatic(ContextCompat.class);
        toasts = mockStatic(Toast.class);
        toasts.when(() -> Toast.makeText(any(Context.class), anyInt(), anyInt()))
                .thenReturn(mock(Toast.class));
        intents = mockConstruction(Intent.class, withSettings().defaultAnswer(RETURNS_SELF),
                (intent, construction) -> {
                    if (construction.arguments().size() == 2
                            && construction.arguments().get(1) instanceof Class<?>) {
                        targets.add((Class<?>) construction.arguments().get(1));
                    }
                });
        RxJavaPlugins.setIoSchedulerHandler(scheduler -> Schedulers.trampoline());
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(scheduler -> Schedulers.trampoline());
        RxAndroidPlugins.setMainThreadSchedulerHandler(scheduler -> Schedulers.trampoline());
    }

    @After
    public void tearDown() throws Exception {
        intents.close();
        toasts.close();
        services.close();
        caches.close();
        RxAndroidPlugins.reset();
        RxJavaPlugins.reset();
        resetLibrary();
    }

    @Test
    public void openingSavedCurrentPlaybackNeverOpensOnlineDetail() {
        NavigationHelper.playOnMainPlayer(context, queue, true);
        assertEquals(List.of(PlayQueueActivity.class), targets);
        assertEquals(0, deliveredQueues.size());
    }

    @Test
    public void explicitSavedSelectionDeliversRequestedQueueBeforeController() {
        NavigationHelper.playOnMainPlayer(context, queue, false);
        assertEquals(List.of(PlayerService.class, PlayQueueActivity.class), targets);
        assertSame(queue, deliveredQueues.get(0));
    }

    @Test
    public void immediateSavedHistorySelectionWithoutQueueResolvesOwnedEntry() {
        NavigationHelper.openVideoDetail(context, 0, entry.localUrl(), entry.title,
                null, false, true);
        assertEquals(List.of(PlayerService.class, PlayQueueActivity.class), targets);
        assertEquals(entry.localUrl(), deliveredQueues.get(0).getItem().getUrl());
        assertTrue(deliveredQueues.get(0).isOfflineOnly());
    }

    @Test
    public void passiveSavedHistoryDetailsOpenShelfWithoutRestartingPlayer() {
        NavigationHelper.openVideoDetail(context, 0, entry.localUrl(), entry.title,
                null, false, false);
        assertEquals(List.of(OfflineLibraryActivity.class), targets);
        assertEquals(0, deliveredQueues.size());
    }

    @Test
    public void missingSavedHistorySelectionNeverStartsAnotherTrack() {
        NavigationHelper.openVideoDetail(context, 0, "backtube-offline:missing", "Missing",
                null, false, true);
        assertEquals(List.of(OfflineLibraryActivity.class), targets);
        assertEquals(0, deliveredQueues.size());
    }

    @Test
    public void fragmentNavigationForSavedQueueAvoidsOnlineDetail() {
        NavigationHelper.openVideoDetailFragment(context, mock(FragmentManager.class),
                0, entry.localUrl(), entry.title, queue, true, false);
        assertEquals(List.of(PlayQueueActivity.class), targets);
        assertEquals(0, deliveredQueues.size());
    }

    @Test
    public void savedControllerFromServiceContextStartsInANewTask() {
        NavigationHelper.playOnMainPlayer(context, queue, true);
        verify(intents.constructed().get(0)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    @Test
    public void savedShelfFromServiceContextStartsInANewTask() {
        NavigationHelper.openVideoDetail(context, 0, entry.localUrl(), entry.title,
                null, false, false);
        verify(intents.constructed().get(0)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    @Test
    public void missingSavedSelectionFromServiceContextStartsShelfInANewTask() {
        NavigationHelper.openVideoDetail(context, 0, "backtube-offline:missing", "Missing",
                null, false, true);
        verify(intents.constructed().get(0)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    @Test
    public void lateSavedResolutionCannotReplaceANewerSelection() throws Exception {
        final OfflineLibrary library = OfflineLibrary.get(context);
        final OfflineStore.Entry second = library.store().create("Latest selected track", "",
                0, "file:///second-generated-fixture", "audio/wav");
        library.store().copy(second.id, new ByteArrayInputStream(new byte[]{5, 6, 7}));
        final TestScheduler firstIo = new TestScheduler();
        final TestScheduler secondIo = new TestScheduler();
        final AtomicInteger selection = new AtomicInteger();
        RxJavaPlugins.setIoSchedulerHandler(scheduler ->
                selection.getAndIncrement() == 0 ? firstIo : secondIo);
        NavigationHelper.openVideoDetail(context, 0, entry.localUrl(), entry.title,
                null, false, true);
        NavigationHelper.openVideoDetail(context, 0, second.localUrl(), second.title,
                null, false, true);
        secondIo.triggerActions();
        firstIo.triggerActions();
        assertEquals(1, deliveredQueues.size());
        assertEquals(second.localUrl(), deliveredQueues.get(0).getItem().getUrl());
    }

    @Test
    public void stoppedActivityCannotLaunchLateSavedPlayback() {
        final AppCompatActivity activity = mock(AppCompatActivity.class);
        final Lifecycle lifecycle = mock(Lifecycle.class);
        when(activity.getLifecycle()).thenReturn(lifecycle);
        when(lifecycle.getCurrentState()).thenReturn(Lifecycle.State.CREATED);
        final TestScheduler io = new TestScheduler();
        RxJavaPlugins.setIoSchedulerHandler(scheduler -> io);
        NavigationHelper.openVideoDetail(activity, 0, entry.localUrl(), entry.title,
                null, false, true);
        io.triggerActions();
        assertTrue(targets.isEmpty());
        assertTrue(deliveredQueues.isEmpty());
    }

    @Test
    public void visibleActivityCanLaunchSelectedSavedPlayback() {
        final AppCompatActivity activity = mock(AppCompatActivity.class);
        final Lifecycle lifecycle = mock(Lifecycle.class);
        when(activity.getLifecycle()).thenReturn(lifecycle);
        when(lifecycle.getCurrentState()).thenReturn(Lifecycle.State.STARTED);
        NavigationHelper.openVideoDetail(activity, 0, entry.localUrl(), entry.title,
                null, false, true);
        assertEquals(List.of(PlayerService.class, PlayQueueActivity.class), targets);
        assertEquals(entry.localUrl(), deliveredQueues.get(0).getItem().getUrl());
    }

    @Test
    public void lateSavedResolutionCannotReopenAfterPassiveShelfNavigation() {
        final TestScheduler io = new TestScheduler();
        RxJavaPlugins.setIoSchedulerHandler(scheduler -> io);
        NavigationHelper.openVideoDetail(context, 0, entry.localUrl(), entry.title,
                null, false, true);
        NavigationHelper.openVideoDetail(context, 0, entry.localUrl(), entry.title,
                null, false, false);
        io.triggerActions();
        assertEquals(List.of(OfflineLibraryActivity.class), targets);
        assertTrue(deliveredQueues.isEmpty());
    }

    @Test
    public void freshSavedSelectionAndPlayerSwitchCarryDifferentPlaybackIntent() {
        NavigationHelper.playOnBackgroundPlayer(context, queue, true);
        verify(intents.constructed().get(0)).putExtra(Player.FRESH_SELECTION, true);
        verify(intents.constructed().get(0)).putExtra(Player.PLAY_WHEN_READY, true);
        final Player pausedPlayer = mock(Player.class);
        when(pausedPlayer.getPlayWhenReady()).thenReturn(false);
        NavigationHelper.playOnBackgroundPlayer(context, queue, true, pausedPlayer);
        verify(intents.constructed().get(1)).putExtra(Player.FRESH_SELECTION, false);
        verify(intents.constructed().get(1)).putExtra(Player.PLAY_WHEN_READY, false);
    }

    @Test
    public void selectingOneShelfItemStartsServiceWithoutBackgroundSuccessToast() throws Exception {
        final OfflineLibraryActivity shelf = mock(OfflineLibraryActivity.class);
        final var start = OfflineLibraryActivity.class.getDeclaredMethod("startSavedQueue",
                org.schabi.newpipe.player.playqueue.OfflinePlayQueue.class, boolean.class);
        start.setAccessible(true);
        start.invoke(shelf, queue, true);
        assertEquals(List.of(PlayerService.class, PlayQueueActivity.class), targets);
        services.verify(() -> ContextCompat.startForegroundService(eq(shelf), any(Intent.class)));
        verify(intents.constructed().get(0)).putExtra(Player.FRESH_SELECTION, true);
        verify(intents.constructed().get(0)).putExtra(Player.PLAY_WHEN_READY, true);
        verify(intents.constructed().get(0)).putExtra(Player.RESUME_PLAYBACK, true);
        toasts.verify(() -> Toast.makeText(eq(shelf),
                eq(R.string.background_player_playing_toast), anyInt()), never());
    }

    @Test
    public void shelfPlayAllKeepsItsBackgroundNoticeAndFreshPlaybackIntent() throws Exception {
        final OfflineLibraryActivity shelf = mock(OfflineLibraryActivity.class);
        final var start = OfflineLibraryActivity.class.getDeclaredMethod("startSavedQueue",
                org.schabi.newpipe.player.playqueue.OfflinePlayQueue.class, boolean.class);
        start.setAccessible(true);
        start.invoke(shelf, queue, false);
        services.verify(() -> ContextCompat.startForegroundService(eq(shelf), any(Intent.class)));
        toasts.verify(() -> Toast.makeText(eq(shelf),
                eq(R.string.background_player_playing_toast), anyInt()));
        verify(intents.constructed().get(0)).putExtra(Player.FRESH_SELECTION, true);
        verify(intents.constructed().get(0)).putExtra(Player.PLAY_WHEN_READY, true);
    }

    private static void resetLibrary() throws Exception {
        final Field singleton = OfflineLibrary.class.getDeclaredField("instance");
        singleton.setAccessible(true);
        singleton.set(null, null);
    }
}
