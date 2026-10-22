package us.shandian.giga.get;

import android.os.Handler;
import android.os.Message;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.schabi.newpipe.streams.io.StoredFileHelper;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import us.shandian.giga.util.Utility;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

/** Generated metadata and transport boundaries; no network or Android service. */
public class OfflineInitializationOwnershipTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void initializerIsPublishedBeforeItsChildCanStartTransfers() throws Exception {
        final DownloadMission mission = mission();
        mission.storage = transport();
        final Thread previous = new Thread(() -> { });
        previous.start();
        previous.join(2000);
        mission.init = previous;
        final int[] prepared = new int[0];
        final AtomicInteger replacements = new AtomicInteger();
        final AtomicInteger transfers = new AtomicInteger();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        try (var owners = mockConstruction(DownloadInitializer.class, (owner, setup) -> {
            final Thread child = new Thread(() -> {
                try (var nested = mockConstruction(DownloadInitializer.class);
                     var workers = mockConstruction(DownloadRunnableFallback.class)) {
                    mission.blocks = prepared;
                    mission.running = false;
                    mission.start();
                    replacements.set(nested.constructed().size());
                    transfers.set(workers.constructed().size());
                } catch (final Throwable error) {
                    failure.set(error);
                }
            });
            when(owner.isAlive()).thenAnswer(invocation -> child.isAlive());
            doAnswer(invocation -> {
                // Force the child handoff before the parent's Thread.start() returns.
                child.start();
                child.join(2000);
                assertFalse(child.isAlive());
                return null;
            }).when(owner).start();
        })) {
            mission.start();
            assertEquals(1, owners.constructed().size());
            assertSame(owners.constructed().get(0), mission.init);
        }
        assertEquals(null, failure.get());
        assertEquals("a newly started initializer must not be replaced", 0, replacements.get());
        assertEquals(1, transfers.get());
        assertSame(prepared, mission.blocks);
    }

    @Test public void pausedIncompleteSnapshotRestartsInitializationAfterReload() throws Exception {
        assertIncompleteSnapshot(false);
    }

    @Test public void deadIncompleteOwnerCannotPersistPartialBlocksLater() throws Exception {
        assertIncompleteSnapshot(true);
    }

    @Test public void preparedProgressPersistsAndResumesWithoutReinitializing() throws Exception {
        final DownloadMission mission = mission();
        mission.blocks = new int[]{4, 5};
        mission.done = 17;
        mission.length = 37;
        final Field field = DownloadMission.class.getDeclaredField("blockAcquired");
        field.setAccessible(true);
        field.set(mission, new boolean[2]);
        final CountDownLatch release = new CountDownLatch(1);
        final Thread owner = owner(mission, release);
        try {
            mission.pause();
            final DownloadMission restored = restore(mission);
            try (var initializers = mockConstruction(DownloadInitializer.class);
                 var workers = mockConstruction(DownloadRunnable.class)) {
                restored.start();
                assertEquals(0, initializers.constructed().size());
                assertEquals(2, workers.constructed().size());
                assertEquals(4, restored.blocks[0]);
                assertEquals(5, restored.blocks[1]);
                assertEquals(17, restored.done);
                assertEquals(37, restored.length);
            }
        } finally {
            release.countDown();
            owner.join(2000);
        }
        assertFalse(owner.isAlive());
    }

    private void assertIncompleteSnapshot(final boolean afterExit) throws Exception {
        final DownloadMission mission = mission();
        // The manager writes this valid uninitialized registration before starting an owner.
        assertEquals(null, restore(mission).blocks);
        final int[] partial = {0, 0};
        mission.blocks = partial;
        mission.length = 37;
        final CountDownLatch release = new CountDownLatch(1);
        final Thread owner = owner(mission, release);
        try {
            mission.pause();
            assertSame("pause must preserve live initializer state", partial, mission.blocks);
            if (afterExit) {
                release.countDown();
                owner.join(2000);
                assertFalse(owner.isAlive());
                mission.errCode = DownloadMission.ERROR_TIMEOUT;
                mission.errObject = new java.io.IOException("generated initialization error");
                mission.enqueued = false;
                // The existing error/queue writer may run after the initializer has exited.
                mission.writeThisToFile();
            }
            final DownloadMission restored = restore(mission);
            assertEquals("latest initialization error must survive restart",
                    mission.errCode, restored.errCode);
            assertEquals(mission.enqueued, restored.enqueued);
            if (afterExit) {
                assertEquals(mission.errObject.getMessage(), restored.errObject.getMessage());
            }
            try (var initializers = mockConstruction(DownloadInitializer.class);
                 var workers = mockConstruction(DownloadRunnable.class)) {
                restored.start();
                assertEquals("partial blocks must not skip initialization after reload",
                        1, initializers.constructed().size());
                assertEquals(0, workers.constructed().size());
                assertEquals(null, restored.blocks);
            }
        } finally {
            release.countDown();
            owner.join(2000);
        }
        assertFalse(owner.isAlive());
    }

    private Thread owner(final DownloadMission mission, final CountDownLatch release)
            throws Exception {
        final CountDownLatch started = new CountDownLatch(1);
        final Thread owner = new Thread(() -> {
            started.countDown();
            boolean released = false;
            while (!released) {
                try {
                    release.await();
                    released = true;
                } catch (final InterruptedException ignored) {
                    // Generated preallocation barrier retains its state until cleanup.
                }
            }
        });
        mission.init = owner;
        mission.running = true;
        owner.start();
        assertTrue(started.await(2, TimeUnit.SECONDS));
        return owner;
    }

    private DownloadMission mission() throws Exception {
        final StoredFileHelper storage = new StoredFileHelper(null, "generated", "audio/mp4", "a");
        final DownloadMission mission = new DownloadMission(
                new String[]{"https://example.invalid/generated"}, storage, 'a', null);
        mission.metadata = temporary.newFile();
        handler(mission);
        mission.writeThisToFile();
        return mission;
    }

    private DownloadMission restore(final DownloadMission original) {
        final DownloadMission restored = Utility.readFromFile(original.metadata);
        assertNotNull("generated durable mission must deserialize", restored);
        assertEquals(null, restored.init);
        // This is the existing manager's transient restoration step.
        restored.threads = new Thread[0];
        restored.storage = transport();
        handler(restored);
        return restored;
    }

    private static StoredFileHelper transport() {
        final StoredFileHelper storage = mock(StoredFileHelper.class);
        when(storage.existsAsFile()).thenReturn(true);
        return storage;
    }

    private static void handler(final DownloadMission mission) {
        mission.mHandler = mock(Handler.class);
        when(mission.mHandler.obtainMessage(anyInt(), any())).thenReturn(mock(Message.class));
    }
}
