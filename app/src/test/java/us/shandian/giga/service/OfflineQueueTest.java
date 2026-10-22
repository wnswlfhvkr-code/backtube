package us.shandian.giga.service;

import android.content.Context;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.schabi.newpipe.streams.io.StoredFileHelper;
import us.shandian.giga.util.Utility;
import org.mockito.MockedStatic;
import org.schabi.newpipe.offline.OfflineDownloads;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import us.shandian.giga.get.DownloadMission;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

public class OfflineQueueTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void duplicateOfflineMissionCannotAllocateMetadataOrScheduleAgain()
            throws Exception {
        final Context context = mock(Context.class);
        final DownloadMission owner = mission();
        final DownloadMission duplicate = mission();
        owner.storage = mock(StoredFileHelper.class);
        duplicate.storage = mock(StoredFileHelper.class);
        org.mockito.Mockito.when(owner.storage.getTag()).thenReturn("offline:generated-owner");
        org.mockito.Mockito.when(duplicate.storage.getTag()).thenReturn("offline:generated-owner");
        final DownloadManager manager = manager(context, owner);
        final java.io.File metadata = temporary.newFolder();
        set(manager, "mPendingMissionsDir", metadata);
        try (MockedStatic<OfflineDownloads> policy = mockStatic(OfflineDownloads.class,
                CALLS_REAL_METHODS);
             MockedStatic<Utility> utility = mockStatic(Utility.class)) {
            policy.when(() -> OfflineDownloads.networkAllowed(context)).thenReturn(false);
            manager.startMission(duplicate);
            assertEquals("duplicate may not allocate mission metadata", 0, metadata.list().length);
            assertEquals(owner, manager.getOfflineMission("generated-owner"));
            assertFalse(duplicate.running);
        }
    }

    @Test public void concurrentOfflineDispatchHasOneMissionAndOneMetadataFile() throws Exception {
        final java.lang.reflect.Field singleton = org.schabi.newpipe.offline.OfflineLibrary.class
                .getDeclaredField("instance");
        singleton.setAccessible(true);
        singleton.set(null, null);
        final Context context = mock(Context.class);
        org.mockito.Mockito.when(context.getApplicationContext()).thenReturn(context);
        org.mockito.Mockito.when(context.getFilesDir()).thenReturn(temporary.newFolder());
        final var store = org.schabi.newpipe.offline.OfflineLibrary.get(context).store();
        final var entry = store.beginDownload("Generated", "https://example.invalid/one", 0,
                "audio/mp4");
        final DownloadManager manager = manager(context);
        set(manager, "mLastNetworkStatus", DownloadManager.NetworkState.Unavailable);
        final java.io.File metadata = temporary.newFolder();
        set(manager, "mPendingMissionsDir", metadata);
        final var pool = java.util.concurrent.Executors.newFixedThreadPool(6);
        final var barrier = new java.util.concurrent.CyclicBarrier(6);
        final List<java.util.concurrent.Future<?>> calls = new ArrayList<>();
        try {
            for (int i = 0; i < 6; i++) {
                final StoredFileHelper storage = mock(StoredFileHelper.class);
                org.mockito.Mockito.when(storage.getTag()).thenReturn("offline:" + entry.id);
        org.mockito.Mockito.when(storage.equals(storage)).thenReturn(true);
                final DownloadMission mission = new DownloadMission(
                        new String[]{"https://example.invalid/generated"}, storage, 'a', null);
                calls.add(pool.submit(() -> {
                    barrier.await();
                    manager.startMission(mission);
                    return null;
                }));
            }
            for (final var call : calls) {
                call.get();
            }
            assertEquals(1, metadata.list().length);
            assertTrue(manager.getOfflineMission(entry.id) != null);
            assertEquals(1, store.list().size());
        } finally {
            pool.shutdownNow();
            singleton.set(null, null);
        }
    }

    @Test public void cancelledOfflineMissionKeepsBytesAndCanRetryWithoutAnotherEntry()
            throws Exception {
        final var singleton = org.schabi.newpipe.offline.OfflineLibrary.class
                .getDeclaredField("instance");
        singleton.setAccessible(true);
        singleton.set(null, null);
        final Context context = mock(Context.class);
        org.mockito.Mockito.when(context.getApplicationContext()).thenReturn(context);
        org.mockito.Mockito.when(context.getFilesDir()).thenReturn(temporary.newFolder());
        final var store = org.schabi.newpipe.offline.OfflineLibrary.get(context).store();
        final var entry = store.beginDownload("Generated", "https://example.invalid/retry", 0,
                "audio/mp4");
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(store.file(entry))) {
            output.write(new byte[]{1, 2, 3});
        }
        assertTrue(store.claimDownload(entry.id));
        final StoredFileHelper storage = mock(StoredFileHelper.class);
        org.mockito.Mockito.when(storage.getTag()).thenReturn("offline:" + entry.id);
        org.mockito.Mockito.when(storage.equals(storage)).thenReturn(true);
        final DownloadMission mission = new DownloadMission(
                new String[]{"https://example.invalid/generated"}, storage, 'a', null);
        final DownloadManager manager = manager(context);
        set(manager, "mLastNetworkStatus", DownloadManager.NetworkState.Unavailable);
        set(manager, "mPendingMissionsDir", temporary.newFolder());
        final android.os.Handler handler = mock(android.os.Handler.class);
        org.mockito.Mockito.when(handler.obtainMessage(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any())).thenReturn(mock(android.os.Message.class));
        set(manager, "mHandler", handler);
        try (MockedStatic<Utility> utility = mockStatic(Utility.class)) {
            manager.startMission(mission);
            final java.io.File metadata = mission.metadata;
            manager.deleteMission(mission, false);
            assertFalse("cancelled owner must not return after process restart", metadata.exists());
            assertEquals(3, store.file(entry).length());
            assertEquals(entry.id, store.beginDownload("Retry", entry.origin, 0, "audio/mp4").id);
            assertTrue(store.claimDownload(entry.id));
            assertEquals(1, store.list().size());
            org.mockito.Mockito.verify(storage, org.mockito.Mockito.never()).delete();
        } finally {
            singleton.set(null, null);
        }
    }

    @Test public void startAllCannotBypassOfflineNetworkPolicy() throws Exception {
        final DownloadMission mission = mission();
        final Context context = mock(Context.class);
        final DownloadManager manager = manager(context, mission);
        try (MockedStatic<OfflineDownloads> policy = mockStatic(OfflineDownloads.class)) {
            policy.when(() -> OfflineDownloads.isManaged(mission)).thenReturn(true);
            policy.when(() -> OfflineDownloads.networkAllowed(context)).thenReturn(false);
            manager.startAllMissions();
            assertFalse(mission.running);
            assertTrue(mission.enqueued);
        }
    }

    @Test public void pausedInitializationCannotRestartFromShelfActivationAfterReload()
            throws Exception {
        final var singleton = org.schabi.newpipe.offline.OfflineLibrary.class
                .getDeclaredField("instance");
        singleton.setAccessible(true);
        singleton.set(null, null);
        final Context context = mock(Context.class);
        org.mockito.Mockito.when(context.getApplicationContext()).thenReturn(context);
        org.mockito.Mockito.when(context.getFilesDir()).thenReturn(temporary.newFolder());
        final var entry = org.schabi.newpipe.offline.OfflineLibrary.get(context).store()
                .beginDownload("Generated", "https://example.invalid/pause-restore", 0,
                        "audio/mp4");
        final StoredFileHelper storage = new StoredFileHelper(null, "generated", "audio/mp4",
                "offline:" + entry.id);
        final DownloadMission mission = new DownloadMission(
                new String[]{"https://example.invalid/generated"}, storage, 'a', null);
        final DownloadManager original = manager(context);
        set(original, "mLastNetworkStatus", DownloadManager.NetworkState.Unavailable);
        set(original, "mPendingMissionsDir", temporary.newFolder());
        final android.os.Handler handler = mock(android.os.Handler.class);
        org.mockito.Mockito.when(handler.obtainMessage(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any())).thenReturn(mock(android.os.Message.class));
        set(original, "mHandler", handler);
        original.startMission(mission);
        final DownloadMission registered = Utility.readFromFile(mission.metadata);
        org.junit.Assert.assertNotNull(registered);
        assertFalse(registered.isInitialized());
        assertTrue(registered.enqueued);
        final var blocks = DownloadMission.class.getDeclaredField("blocks");
        blocks.setAccessible(true);
        final int[] partial = {0, 0};
        blocks.set(mission, partial);
        final var release = new java.util.concurrent.CountDownLatch(1);
        final var started = new java.util.concurrent.CountDownLatch(1);
        final Thread owner = new Thread(() -> {
            started.countDown();
            boolean done = false;
            while (!done) {
                try {
                    release.await();
                    done = true;
                } catch (final InterruptedException ignored) {
                    // Generated incomplete preallocation remains live until cleanup.
                }
            }
        });
        mission.init = owner;
        mission.running = true;
        owner.start();
        assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS));
        try {
            original.pauseMission(mission);
            final var lock = DownloadMission.class.getDeclaredField("LOCK");
            lock.setAccessible(true);
            final DownloadMission restored;
            synchronized (lock.get(mission)) {
                restored = Utility.readFromFile(mission.metadata);
            }
            org.junit.Assert.assertNotNull(restored);
            org.junit.Assert.assertSame(partial, blocks.get(mission));
            restored.threads = new Thread[0];
            final DownloadMission resumed = org.mockito.Mockito.spy(restored);
            doAnswer(invocation -> {
                resumed.running = true;
                return null;
            }).when(resumed).start();
            final DownloadManager reloaded = manager(context, resumed);
            try (MockedStatic<OfflineDownloads> policy = mockStatic(OfflineDownloads.class,
                    CALLS_REAL_METHODS)) {
                policy.when(() -> OfflineDownloads.networkAllowed(context)).thenReturn(true);
                reloaded.activateOfflineQueue();
                assertFalse("explicit pause must survive restored shelf activation",
                        resumed.running);
                assertFalse(resumed.enqueued);
                org.mockito.Mockito.verify(resumed, org.mockito.Mockito.never()).start();
                reloaded.resumeMission(resumed);
                assertTrue(resumed.running);
                org.mockito.Mockito.verify(resumed).start();
            }
        } finally {
            release.countDown();
            owner.join(2000);
            singleton.set(null, null);
        }
        assertFalse(owner.isAlive());
    }

    @Test public void repeatedCapabilityEventsRespectSingleDownloadQueue() throws Exception {
        final DownloadMission first = mission();
        final DownloadMission second = mission();
        final Context context = mock(Context.class);
        final DownloadManager manager = manager(context, first, second);
        try (MockedStatic<OfflineDownloads> policy = mockStatic(OfflineDownloads.class)) {
            policy.when(() -> OfflineDownloads.isManaged(first)).thenReturn(true);
            policy.when(() -> OfflineDownloads.isManaged(second)).thenReturn(true);
            policy.when(() -> OfflineDownloads.networkAllowed(context)).thenReturn(true);
            manager.activateOfflineQueue();
            manager.handleConnectivityState(DownloadManager.NetworkState.Operating, false);
            manager.handleConnectivityState(DownloadManager.NetworkState.Operating, false);
            assertEquals(1, manager.getRunningMissionsCount());
            assertTrue(first.running);
            assertFalse(second.running);
        }
    }

    @Test public void restoredShelfQueueResumesWhenAllowedNetworkReturns() throws Exception {
        final DownloadMission mission = mission();
        final Context context = mock(Context.class);
        final DownloadManager manager = manager(context, mission);
        try (MockedStatic<OfflineDownloads> policy = mockStatic(OfflineDownloads.class)) {
            policy.when(() -> OfflineDownloads.isManaged(mission)).thenReturn(true);
            policy.when(() -> OfflineDownloads.networkAllowed(context)).thenReturn(false);
            manager.activateOfflineQueue();
            assertFalse(mission.running);
            policy.when(() -> OfflineDownloads.networkAllowed(context)).thenReturn(true);
            manager.handleConnectivityState(DownloadManager.NetworkState.Operating, false);
            assertTrue(mission.running);
        }
    }

    private DownloadManager manager(final Context context, final DownloadMission... missions)
            throws Exception {
        // Exercise real scheduling while isolating Android storage and transport discovery.
        final DownloadManager manager = mock(DownloadManager.class, CALLS_REAL_METHODS);
        set(manager, "offlineContext", context);
        set(manager, "mMissionsPending", new ArrayList<>(List.of(missions)));
        set(manager, "mMissionsFinished", new ArrayList<>());
        set(manager, "mLastNetworkStatus", DownloadManager.NetworkState.Operating);
        manager.mPrefQueueLimit = true;
        return manager;
    }

    private void set(final DownloadManager manager, final String fieldName, final Object value)
            throws Exception {
        final Field field = DownloadManager.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(manager, value);
    }

    private DownloadMission mission() {
        final DownloadMission mission = mock(DownloadMission.class);
        mission.enqueued = true;
        mission.errCode = DownloadMission.ERROR_NOTHING;
        doAnswer(invocation -> {
            mission.running = true;
            return null;
        }).when(mission).start();
        doAnswer(invocation -> {
            mission.enqueued = invocation.getArgument(0);
            return null;
        }).when(mission).setEnqueued(anyBoolean());
        return mission;
    }
}
