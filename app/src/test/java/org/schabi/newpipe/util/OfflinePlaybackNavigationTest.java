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
import org.schabi.newpipe.MainActivity;
import org.schabi.newpipe.fragments.detail.VideoDetailFragment;
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
    public void savedVideoSingleSelectionOpensMainVideoScreen() throws Exception {
        final OfflineStore.Entry video = savedVideo();
        requestShelfPlayback(shelfFixture(), video);
        assertEquals(List.of(MainActivity.class), targets);
        assertEquals(video.localUrl(), deliveredQueues.get(0).getItem().getUrl());
        verify(intents.constructed().get(0)).putExtra(
                VideoDetailFragment.KEY_PLAY_IMMEDIATELY, true);
    }

    @Test
    public void selectedAudioThenVideoQueueOpensMainWithoutLosingOrder() throws Exception {
        final OfflineStore.Entry video = savedVideo();
        requestSelectedPlayback(shelfFixture(), List.of(entry.id, video.id));
        assertEquals(List.of(MainActivity.class), targets);
        assertEquals(List.of(entry.localUrl(), video.localUrl()), deliveredQueues.get(0)
                .getStreams().stream().map(item -> item.getUrl()).toList());
        assertTrue(deliveredQueues.get(0).isOfflineOnly());
    }

    @Test
    public void savedVideoHistoryResolvesOwnedQueueAndOpensMain() throws Exception {
        final OfflineStore.Entry video = savedVideo();
        NavigationHelper.openVideoDetail(context, 0, video.localUrl(), video.title,
                null, false, true);
        assertEquals(List.of(MainActivity.class), targets);
        assertEquals(video.localUrl(), deliveredQueues.get(0).getItem().getUrl());
    }

    @Test
    public void expandingSavedVideoPreservesSwitchingPlaybackIntent() throws Exception {
        final PlayQueue videoQueue = OfflineLibrary.get(context).queue(savedVideo());
        NavigationHelper.playOnMainPlayer(context, videoQueue, true);
        assertEquals(List.of(MainActivity.class), targets);
        assertSame(videoQueue, deliveredQueues.get(0));
        verify(intents.constructed().get(0)).putExtra(
                VideoDetailFragment.KEY_SWITCHING_PLAYERS, true);
        verify(intents.constructed().get(0)).putExtra(
                VideoDetailFragment.KEY_PLAY_IMMEDIATELY, false);
    }

    private OfflineStore.Entry savedVideo() throws Exception {
        final OfflineLibrary library = OfflineLibrary.get(context);
        final OfflineStore.Entry video = library.store().create("Generated video", "", 0,
                "file:///generated-av.mp4", "video/mp4");
        library.store().copy(video.id, new ByteArrayInputStream(new byte[]{4, 5, 6}));
        return video;
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

    @Test
    public void stoppedShelfCannotLaunchLateQueueConstruction() throws Exception {
        final OfflineLibraryActivity shelf = shelfFixture();
        final TestScheduler io = new TestScheduler();
        RxJavaPlugins.setIoSchedulerHandler(scheduler -> io);
        requestShelfPlayback(shelf, entry);
        setShelfField(shelf, "resumed", false);
        io.triggerActions();
        assertTrue("a stopped shelf must not start the service or open the controller",
                targets.isEmpty());
        assertTrue(deliveredQueues.isEmpty());
    }

    @Test
    public void obsoleteShelfRequestCannotReplaceNewerSavedPlayback() throws Exception {
        final OfflineLibrary library = OfflineLibrary.get(context);
        final OfflineStore.Entry newer = library.store().create("Newer", "", 0,
                "file:///newer-generated-fixture", "audio/wav");
        library.store().copy(newer.id, new ByteArrayInputStream(new byte[]{4, 5, 6}));
        final OfflineLibraryActivity shelf = shelfFixture();
        final TestScheduler firstIo = new TestScheduler();
        final TestScheduler secondIo = new TestScheduler();
        final AtomicInteger requests = new AtomicInteger();
        RxJavaPlugins.setIoSchedulerHandler(scheduler ->
                requests.getAndIncrement() == 0 ? firstIo : secondIo);
        requestShelfPlayback(shelf, entry);
        requestShelfPlayback(shelf, newer);
        secondIo.triggerActions();
        firstIo.triggerActions();
        assertEquals("only the latest shelf request may replace the active queue",
                1, deliveredQueues.size());
        assertEquals(newer.localUrl(), deliveredQueues.get(0).getItem().getUrl());
    }

    @Test
    public void selectedShelfRequestUsesImmutableIdsFreshOrderAndFreshServiceIntent()
            throws Exception {
        final OfflineLibrary library = OfflineLibrary.get(context);
        final OfflineStore.Entry second = library.store().create("Second", "", 0,
                "content://generated/second", "audio/wav");
        library.store().copy(second.id, new ByteArrayInputStream(new byte[]{4, 5, 6}));
        final OfflineLibraryActivity shelf = shelfFixture();
        final TestScheduler io = new TestScheduler();
        RxJavaPlugins.setIoSchedulerHandler(scheduler -> io);
        final List<String> ids = new ArrayList<>(List.of(second.id, entry.id, second.id));
        requestSelectedPlayback(shelf, ids);
        ids.clear();
        library.store().move(second.id, -1);
        io.triggerActions();
        assertEquals(1, deliveredQueues.size());
        assertEquals(2, deliveredQueues.get(0).size());
        assertEquals(second.localUrl(), deliveredQueues.get(0).getItem(0).getUrl());
        assertEquals(entry.localUrl(), deliveredQueues.get(0).getItem(1).getUrl());
        assertTrue(deliveredQueues.get(0).isOfflineOnly());
        assertEquals(List.of(PlayerService.class, PlayQueueActivity.class), targets);
        verify(intents.constructed().get(0)).putExtra(Player.FRESH_SELECTION, true);
        verify(intents.constructed().get(0)).putExtra(Player.PLAY_WHEN_READY, true);
    }

    @Test
    public void zeroOrUnavailableSelectedShelfRequestCannotReplaceCurrentPlayback()
            throws Exception {
        final OfflineLibraryActivity shelf = shelfFixture();
        requestSelectedPlayback(shelf, List.of());
        requestSelectedPlayback(shelf, List.of("deleted", "missing", "deleted"));
        assertTrue(deliveredQueues.isEmpty());
        assertTrue(targets.isEmpty());
    }

    @Test
    public void cancelAndToolbarUpDiscardPendingSelectionWithoutClearingNewMode()
            throws Exception {
        for (final String cancel : List.of("cancelSelection", "leaveShelf", "onBackPressed")) {
            final OfflineLibraryActivity shelf = shelfFixture();
            setSelection(shelf, List.of(entry.id));
            final TestScheduler io = new TestScheduler();
            RxJavaPlugins.setIoSchedulerHandler(scheduler -> io);
            requestSelectedPlayback(shelf, List.of(entry.id));
            invokeShelf(shelf, cancel);
            setSelection(shelf, List.of(entry.id));
            io.triggerActions();
            assertTrue(cancel + " must suppress queue replacement", deliveredQueues.isEmpty());
            assertTrue(cancel + " must suppress controller", targets.isEmpty());
            assertEquals(List.of(entry.id), selectionSnapshot(shelf));
        }
    }

    @Test
    public void stoppedOrCancelledErrorCallbackDoesNotShowAnErrorToast() throws Exception {
        for (final boolean stopped : List.of(false, true)) {
            final OfflineLibraryActivity shelf = shelfFixture();
            final TestScheduler io = new TestScheduler();
            RxJavaPlugins.setIoSchedulerHandler(scheduler -> io);
            requestShelfPlayback(shelf, entry);
            OfflineLibrary.get(context).store().delete(entry.id);
            if (stopped) {
                setShelfField(shelf, "resumed", false);
            } else {
                invokeShelf(shelf, "cancelSelection");
            }
            io.triggerActions();
            toasts.verify(() -> Toast.makeText(eq(shelf),
                    eq(R.string.offline_storage_error), anyInt()), never());
            assertTrue(targets.isEmpty());
        }
    }

    @Test
    public void changingSelectedRowsInvalidatesPendingQueueAndUpdatesCountControls()
            throws Exception {
        final OfflineLibrary library = OfflineLibrary.get(context);
        final OfflineStore.Entry second = library.store().create("Second", "", 0,
                "content://generated/second", "audio/wav");
        library.store().copy(second.id, new ByteArrayInputStream(new byte[]{4, 5, 6}));
        final OfflineLibraryActivity shelf = shelfFixture();
        invokeShelf(shelf, "enterSelection");
        assertEquals("0 selected", ((android.widget.TextView) shelfField(shelf,
                "selectedCount")).getText());
        assertTrue(!((android.widget.Button) shelfField(shelf, "playSelected")).isEnabled());
        toggleShelfSelection(shelf, entry);
        final TestScheduler io = new TestScheduler();
        RxJavaPlugins.setIoSchedulerHandler(scheduler -> io);
        requestSelectedPlayback(shelf, List.of(entry.id));
        toggleShelfSelection(shelf, entry);
        toggleShelfSelection(shelf, second);
        io.triggerActions();
        assertTrue(deliveredQueues.isEmpty());
        assertEquals(List.of(second.id), selectionSnapshot(shelf));
        assertEquals("1 selected", ((android.widget.TextView) shelfField(shelf,
                "selectedCount")).getText());
        assertTrue(((android.widget.Button) shelfField(shelf, "playSelected")).isEnabled());
        assertTrue(!((android.widget.Button) shelfField(shelf, "manage")).isEnabled());
        invokeShelf(shelf, "clearSelection");
        assertEquals(List.of(), selectionSnapshot(shelf));
        assertTrue(!((android.widget.Button) shelfField(shelf, "playSelected")).isEnabled());
        invokeShelf(shelf, "cancelSelection");
        assertTrue(((android.widget.Button) shelfField(shelf, "manage")).isEnabled());
    }

    private static void toggleShelfSelection(final OfflineLibraryActivity shelf,
                                              final OfflineStore.Entry entry) throws Exception {
        final var toggle = OfflineLibraryActivity.class.getDeclaredMethod("toggleSelection",
                OfflineStore.Entry.class);
        toggle.setAccessible(true);
        final OfflineLibrary library = (OfflineLibrary) shelfField(shelf, "library");
        toggle.invoke(shelf, library.store().get(entry.id));
    }

    private static Object shelfField(final OfflineLibraryActivity shelf, final String name)
            throws Exception {
        final Field field = OfflineLibraryActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(shelf);
    }

    private static void requestSelectedPlayback(final OfflineLibraryActivity shelf,
                                                 final List<String> ids) throws Exception {
        final var play = OfflineLibraryActivity.class.getDeclaredMethod("playSelected", List.class);
        play.setAccessible(true);
        play.invoke(shelf, ids);
    }

    private static Object invokeShelf(final OfflineLibraryActivity shelf, final String method)
            throws Exception {
        final var call = OfflineLibraryActivity.class.getDeclaredMethod(method);
        call.setAccessible(true);
        return call.invoke(shelf);
    }

    private static void setSelection(final OfflineLibraryActivity shelf, final List<String> ids)
            throws Exception {
        final Object selection = shelfSelection(shelf);
        final var restore = selection.getClass().getDeclaredMethod("restore", boolean.class,
                List.class);
        restore.setAccessible(true);
        restore.invoke(selection, true, ids);
    }

    private static Object shelfSelection(final OfflineLibraryActivity shelf) throws Exception {
        final Field selection = OfflineLibraryActivity.class.getDeclaredField("selection");
        selection.setAccessible(true);
        return selection.get(shelf);
    }

    private static Object selectionSnapshot(final OfflineLibraryActivity shelf) throws Exception {
        final Object selection = shelfSelection(shelf);
        final var snapshot = selection.getClass().getDeclaredMethod("snapshot");
        snapshot.setAccessible(true);
        return snapshot.invoke(selection);
    }

    private OfflineLibraryActivity shelfFixture() throws Exception {
        final OfflineLibraryActivity shelf = mock(OfflineLibraryActivity.class);
        org.mockito.Mockito.doCallRealMethod().when(shelf).onBackPressed();
        setShelfField(shelf, "library", OfflineLibrary.get(context));
        setShelfField(shelf, "reads", new io.reactivex.rxjava3.disposables.CompositeDisposable());
        setShelfField(shelf, "resumed", true);
        final Class<?> selectionType = Class.forName(
                "org.schabi.newpipe.offline.OfflineShelfSelection");
        final var constructor = selectionType.getDeclaredConstructor();
        constructor.setAccessible(true);
        setShelfField(shelf, "selection", constructor.newInstance());
        final Class<?> adapterType = Class.forName(
                "org.schabi.newpipe.offline.OfflineShelfAdapter");
        setShelfField(shelf, "adapter", mock(adapterType));
        setShelfField(shelf, "selectionControls", mock(android.widget.LinearLayout.class));
        final android.widget.TextView selectedCount = mock(android.widget.TextView.class);
        final var countText = new java.util.concurrent.atomic.AtomicReference<CharSequence>();
        org.mockito.Mockito.doAnswer(invocation -> {
            countText.set(invocation.getArgument(0));
            return null;
        }).when(selectedCount).setText(any(CharSequence.class));
        when(selectedCount.getText()).thenAnswer(ignored -> countText.get());
        when(shelf.getString(eq(R.string.offline_selected_count), any(Object[].class)))
                .thenAnswer(invocation -> invocation.getArgument(1) + " selected");
        setShelfField(shelf, "selectedCount", selectedCount);
        setShelfField(shelf, "settings", mock(android.widget.ScrollView.class));
        setShelfField(shelf, "playableCount", mock(android.widget.TextView.class));
        for (final String button : List.of("selectSaved", "playSelected", "clearSelection",
                "manage", "playAll")) {
            final android.widget.Button control = mock(android.widget.Button.class);
            final var enabled = new java.util.concurrent.atomic.AtomicBoolean();
            org.mockito.Mockito.doAnswer(invocation -> {
                enabled.set(invocation.getArgument(0));
                return null;
            }).when(control).setEnabled(org.mockito.ArgumentMatchers.anyBoolean());
            when(control.isEnabled()).thenAnswer(ignored -> enabled.get());
            setShelfField(shelf, button, control);
        }
        return shelf;
    }

    private static void setShelfField(final OfflineLibraryActivity shelf, final String name,
                                      final Object value) throws Exception {
        final Field field = OfflineLibraryActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(shelf, value);
    }

    private static void requestShelfPlayback(final OfflineLibraryActivity shelf,
                                              final OfflineStore.Entry selected) throws Exception {
        final var play = OfflineLibraryActivity.class.getDeclaredMethod("playSaved",
                OfflineStore.Entry.class);
        play.setAccessible(true);
        play.invoke(shelf, selected);
    }

    private static void resetLibrary() throws Exception {
        final Field singleton = OfflineLibrary.class.getDeclaredField("instance");
        singleton.setAccessible(true);
        singleton.set(null, null);
    }
}
