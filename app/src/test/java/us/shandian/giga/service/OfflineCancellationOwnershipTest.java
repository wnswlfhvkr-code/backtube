package us.shandian.giga.service;

import android.content.Context;
import android.os.Handler;
import android.os.Message;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.schabi.newpipe.offline.OfflineLibrary;
import org.schabi.newpipe.streams.io.StoredFileHelper;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import us.shandian.giga.get.DownloadMission;
import us.shandian.giga.postprocessing.Postprocessing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Generated blocked workers retain real manager ownership until they can no longer write. */
public class OfflineCancellationOwnershipTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @After public void reset() throws Exception {
        final Field field = OfflineLibrary.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, null);
    }

    @Test public void initializerCannotBeForgottenWhileAlive() throws Exception {
        assertOwnedUntilQuiescent("initializer");
    }

    @Test public void transferCannotBeForgottenWhileAlive() throws Exception {
        assertOwnedUntilQuiescent("transfer");
    }

    @Test public void postprocessorCannotBeForgottenWhileAlive() throws Exception {
        assertOwnedUntilQuiescent("postprocessor");
    }

    @Test public void initializerRemainsOwnedWhileItHandsOffToTransferThreads() throws Exception {
        reset();
        final Context context = mock(Context.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getFilesDir()).thenReturn(temporary.newFolder());
        final var store = OfflineLibrary.get(context).store();
        final var entry = store.beginDownload("Generated", "https://example.invalid/handoff", 0,
                "audio/mp4");
        final StoredFileHelper storage = mock(StoredFileHelper.class);
        when(storage.getTag()).thenReturn("offline:" + entry.id);
        when(storage.equals(storage)).thenReturn(true);
        when(storage.existsAsFile()).thenReturn(true);
        final DownloadMission mission = new DownloadMission(
                new String[]{"https://example.invalid/generated"}, storage, 'a', null);
        final DownloadManager manager = mock(DownloadManager.class, CALLS_REAL_METHODS);
        set(manager, "offlineContext", context);
        set(manager, "mMissionsPending", new ArrayList<DownloadMission>());
        set(manager, "mMissionsFinished", new ArrayList<>());
        set(manager, "mLastNetworkStatus", DownloadManager.NetworkState.Unavailable);
        set(manager, "mPendingMissionsDir", temporary.newFolder());
        final Handler handler = mock(Handler.class);
        when(handler.obtainMessage(anyInt(), any())).thenReturn(mock(Message.class));
        set(manager, "mHandler", handler);
        manager.startMission(mission);
        final int[] prepared = new int[0];
        set(mission, "blocks", prepared);
        final CountDownLatch constructing = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        final Thread initializer = new Thread(() -> {
            try (var workers = org.mockito.Mockito.mockConstruction(
                    us.shandian.giga.get.DownloadRunnableFallback.class, (worker, setup) -> {
                        constructing.countDown();
                        boolean released = false;
                        while (!released) {
                            try {
                                release.await();
                                released = true;
                            } catch (final InterruptedException ignored) {
                                // Pause cannot erase this still-live initializer's ownership.
                            }
                        }
                    })) {
                mission.start();
            } catch (final Throwable error) {
                failure.set(error);
            }
        });
        mission.init = initializer;
        initializer.start();
        assertTrue(constructing.await(2, TimeUnit.SECONDS));
        try {
            manager.deleteMission(mission, false);
            assertTrue("initializer-to-transfer handoff must keep its owner",
                    manager.getOfflineMission(entry.id) == mission);
            assertSame(storage, mission.storage);
            assertTrue(mission.metadata.exists());
        } finally {
            release.countDown();
            initializer.join(2000);
        }
        assertFalse(initializer.isAlive());
        assertEquals(null, failure.get());
        assertFalse("pause must not resume the mission during transfer handoff", mission.running);
        try (var restarts = org.mockito.Mockito.mockConstruction(
                us.shandian.giga.get.DownloadRunnableFallback.class)) {
            mission.start();
            assertEquals(1, restarts.constructed().size());
            assertSame(restarts.constructed().get(0), mission.threads[0]);
            assertSame(prepared, get(mission, "blocks"));
            assertTrue(mission.running);
        }
        mission.running = false;
        manager.deleteMission(mission, false);
        assertEquals(null, manager.getOfflineMission(entry.id));
        assertTrue(store.file(entry).exists());
        assertEquals(1, store.list().size());
    }

    @Test public void pauseRetainsAssignedTransfersWhileTheInitializerIsStillAlive()
            throws Exception {
        final Transition fixture = transition();
        final DownloadMission mission = fixture.mission;
        final int[] prepared = {4, 5};
        set(mission, "blocks", prepared);
        mission.threadCount = 2;
        final CountDownLatch constructingSecond = new CountDownLatch(1);
        final CountDownLatch releaseInitializer = new CountDownLatch(1);
        final CountDownLatch releaseTransfer = new CountDownLatch(1);
        final Thread transfer = new Thread(() -> awaitRelease(releaseTransfer));
        final var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        final Thread initializer = new Thread(() -> {
            try (var workers = org.mockito.Mockito.mockConstruction(
                    us.shandian.giga.get.DownloadRunnable.class, (worker, setup) -> {
                        if ((Integer) setup.arguments().get(1) == 1) {
                            constructingSecond.countDown();
                            awaitRelease(releaseInitializer);
                        }
                    })) {
                mission.start();
            } catch (final Throwable error) {
                failure.set(error);
            }
        });
        mission.init = initializer;
        initializer.start();
        assertTrue(constructingSecond.await(2, TimeUnit.SECONDS));
        final Thread[] published = mission.threads;
        assertEquals(2, published.length);
        assertEquals(null, published[1]);
        // Substitute generated transport at the actual published first-worker slot.
        transfer.start();
        published[0] = transfer;
        final Object acquired = get(mission, "blockAcquired");
        try (var logs = org.mockito.Mockito.mockStatic(android.util.Log.class)) {
            fixture.manager.deleteMission(mission, false);
            assertTrue("pause must retain the published transfer array",
                    mission.threads == published);
            assertTrue("pause must retain prepared progress", get(mission, "blocks") == prepared);
            assertTrue("pause must retain acquired blocks",
                    get(mission, "blockAcquired") == acquired);
            releaseInitializer.countDown();
            initializer.join(2000);
            assertFalse(initializer.isAlive());
            assertEquals(null, failure.get());
            fixture.manager.deleteMission(mission, false);
            assertTrue("assigned live transfer must retain its manager owner",
                    fixture.manager.getOfflineMission(fixture.entry.id) == mission);
            assertSame(fixture.storage, mission.storage);
        } finally {
            releaseInitializer.countDown();
            releaseTransfer.countDown();
            initializer.join(2000);
            transfer.join(2000);
        }
        assertFalse(transfer.isAlive());
        try (var restarts = org.mockito.Mockito.mockConstruction(
                us.shandian.giga.get.DownloadRunnable.class)) {
            mission.start();
            assertEquals(2, restarts.constructed().size());
            assertSame(prepared, get(mission, "blocks"));
            assertEquals(4, prepared[0]);
            assertEquals(5, prepared[1]);
            assertTrue(mission.running);
        }
        mission.running = false;
        fixture.manager.deleteMission(mission, false);
        assertEquals(null, fixture.manager.getOfflineMission(fixture.entry.id));
        assertTrue(fixture.store.file(fixture.entry).exists());
    }

    @Test public void pausedIncompleteInitializationResetsOnlyAfterItsOwnerExits()
            throws Exception {
        final Transition fixture = transition();
        final DownloadMission mission = fixture.mission;
        final int[] partial = {7};
        set(mission, "blocks", partial);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch started = new CountDownLatch(1);
        final Thread initializer = new Thread(() -> {
            started.countDown();
            awaitRelease(release);
        });
        mission.init = initializer;
        mission.running = true;
        initializer.start();
        assertTrue(started.await(2, TimeUnit.SECONDS));
        try {
            mission.pause();
            assertTrue("live initialization state may not be reset",
                    get(mission, "blocks") == partial);
            assertFalse(mission.running);
        } finally {
            release.countDown();
            initializer.join(2000);
        }
        assertFalse(initializer.isAlive());
        try (var restarts = org.mockito.Mockito.mockConstruction(
                us.shandian.giga.get.DownloadInitializer.class)) {
            mission.start();
            assertEquals(1, restarts.constructed().size());
            assertSame(restarts.constructed().get(0), mission.init);
            assertEquals(null, get(mission, "blocks"));
            assertTrue(mission.running);
        }
        mission.running = false;
        fixture.manager.deleteMission(mission, false);
        assertTrue(fixture.store.file(fixture.entry).exists());
    }

    private Transition transition() throws Exception {
        reset();
        final Context context = mock(Context.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getFilesDir()).thenReturn(temporary.newFolder());
        final var store = OfflineLibrary.get(context).store();
        final var entry = store.beginDownload("Generated", "https://example.invalid/assigned", 0,
                "audio/mp4");
        final StoredFileHelper storage = mock(StoredFileHelper.class);
        when(storage.getTag()).thenReturn("offline:" + entry.id);
        when(storage.equals(storage)).thenReturn(true);
        when(storage.existsAsFile()).thenReturn(true);
        final DownloadMission mission = new DownloadMission(
                new String[]{"https://example.invalid/generated"}, storage, 'a', null);
        final DownloadManager manager = mock(DownloadManager.class, CALLS_REAL_METHODS);
        set(manager, "offlineContext", context);
        set(manager, "mMissionsPending", new ArrayList<DownloadMission>());
        set(manager, "mMissionsFinished", new ArrayList<>());
        set(manager, "mLastNetworkStatus", DownloadManager.NetworkState.Unavailable);
        set(manager, "mPendingMissionsDir", temporary.newFolder());
        final Handler handler = mock(Handler.class);
        when(handler.obtainMessage(anyInt(), any())).thenReturn(mock(Message.class));
        set(manager, "mHandler", handler);
        manager.startMission(mission);
        return new Transition(store, entry, storage, mission, manager);
    }

    private static void awaitRelease(final CountDownLatch release) {
        boolean released = false;
        while (!released) {
            try {
                release.await();
                released = true;
            } catch (final InterruptedException ignored) {
                // Model a generated initializer/transport retaining ownership until cleanup.
            }
        }
    }

    private static Object get(final Object object, final String name) throws Exception {
        final Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static final class Transition {
        private final org.schabi.newpipe.offline.OfflineStore store;
        private final org.schabi.newpipe.offline.OfflineStore.Entry entry;
        private final StoredFileHelper storage;
        private final DownloadMission mission;
        private final DownloadManager manager;

        private Transition(final org.schabi.newpipe.offline.OfflineStore store,
                           final org.schabi.newpipe.offline.OfflineStore.Entry entry,
                           final StoredFileHelper storage, final DownloadMission mission,
                           final DownloadManager manager) {
            this.store = store;
            this.entry = entry;
            this.storage = storage;
            this.mission = mission;
            this.manager = manager;
        }
    }

    private void assertOwnedUntilQuiescent(final String role) throws Exception {
        reset();
        final Context context = mock(Context.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getFilesDir()).thenReturn(temporary.newFolder());
        final var store = OfflineLibrary.get(context).store();
        final var entry = store.beginDownload("Generated", "https://example.invalid/" + role,
                0, "audio/mp4");
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(store.file(entry))) {
            output.write(new byte[]{1, 2, 3});
        }
        final StoredFileHelper storage = mock(StoredFileHelper.class);
        when(storage.getTag()).thenReturn("offline:" + entry.id);
        when(storage.equals(storage)).thenReturn(true);
        final DownloadMission mission = spy(new DownloadMission(
                new String[]{"https://example.invalid/generated"}, storage, 'a', null));
        doNothing().when(mission).start();
        final DownloadManager manager = mock(DownloadManager.class, CALLS_REAL_METHODS);
        set(manager, "offlineContext", context);
        set(manager, "mMissionsPending", new ArrayList<DownloadMission>());
        set(manager, "mMissionsFinished", new ArrayList<>());
        set(manager, "mLastNetworkStatus", DownloadManager.NetworkState.Unavailable);
        set(manager, "mPendingMissionsDir", temporary.newFolder());
        final Handler handler = mock(Handler.class);
        when(handler.obtainMessage(anyInt(), any())).thenReturn(mock(Message.class));
        set(manager, "mHandler", handler);
        manager.startMission(mission);
        final File metadata = mission.metadata;
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final Thread worker = new Thread(() -> {
            started.countDown();
            boolean done = false;
            while (!done) {
                try {
                    release.await();
                    done = true;
                } catch (final InterruptedException ignored) {
                    // Model a transport/processor still finishing its current owned write.
                }
            }
            mission.psState = 0;
            mission.running = false;
        });
        if (role.equals("initializer")) {
            mission.init = worker;
        } else {
            mission.threads = new Thread[]{worker};
        }
        if (role.equals("postprocessor")) {
            mission.psAlgorithm = mock(Postprocessing.class);
            mission.psState = 1;
        }
        mission.running = true;
        worker.start();
        assertTrue(started.await(2, TimeUnit.SECONDS));
        try (org.mockito.MockedStatic<android.util.Log> logs =
                     org.mockito.Mockito.mockStatic(android.util.Log.class)) {
            manager.deleteMission(mission, false);
            assertTrue("live owner must remain registered",
                    manager.getOfflineMission(entry.id) == mission);
            assertSame(storage, mission.storage);
            assertTrue(metadata.exists());
            assertEquals(3, store.file(entry).length());
            assertEquals(entry.id, store.beginDownload("Retry", entry.origin, 0, "audio/mp4").id);
            manager.resumeMission(mission);
            verify(mission, never()).start();
            verify(storage, never()).delete();
        } finally {
            release.countDown();
            worker.join(2000);
        }
        assertFalse(worker.isAlive());
        manager.deleteMission(mission, false);
        assertEquals(null, manager.getOfflineMission(entry.id));
        assertFalse(metadata.exists());
        assertEquals(3, store.file(entry).length());
        assertEquals(1, store.list().size());
    }

    private static void set(final Object object, final String name, final Object value)
            throws Exception {
        final Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }
}
