package us.shandian.giga.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.BuildConfig;
import org.schabi.newpipe.streams.io.StoredFileHelper;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import us.shandian.giga.get.DownloadMission;

/** API 23 scheduler checks with synthetic missions and no transport or saved user media. */
@RunWith(AndroidJUnit4.class)
public class OfflineWorkerDrainDeviceTest {
    private static final long TIMEOUT_MS = 3_000;
    private final Context testContext = InstrumentationRegistry.getInstrumentation()
            .getTargetContext();

    @Test public void drainedWorkerWakesOnceAndKeepsSingleDownloadLimit() throws Exception {
        final TestManager manager = manager();
        final FakeMission first = new FakeMission("first");
        final FakeMission second = new FakeMission("second");
        pending(manager).add(first);
        pending(manager).add(second);
        final GateWorker oldWorker = new GateWorker();
        first.init = oldWorker;
        oldWorker.start();
        assertTrue(oldWorker.entered.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        try {
            manager.activateOfflineQueue();
            manager.handleConnectivityState(DownloadManager.NetworkState.Operating, false);
            assertEquals(0, first.starts.get());
            assertEquals(1, second.starts.get());
            // The second mission owns the single slot until it stops.
            second.running = false;
            second.enqueued = false;
            oldWorker.release.countDown();
            oldWorker.join(TIMEOUT_MS);
            assertFalse(oldWorker.isAlive());
            assertTrue(first.started.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
            manager.activateOfflineQueue();
            assertEquals(1, first.starts.get());
            assertEquals(1, second.starts.get());
            assertEquals(1, manager.getRunningMissionsCount());
        } finally {
            oldWorker.release.countDown();
            oldWorker.join(TIMEOUT_MS);
            manager.pauseAllMissions(true);
        }
    }

    @Test public void workerDrainDoesNotBypassDisallowedNetwork() throws Exception {
        final TestManager manager = manager();
        final FakeMission mission = new FakeMission("blocked");
        pending(manager).add(mission);
        final GateWorker oldWorker = new GateWorker();
        mission.init = oldWorker;
        oldWorker.start();
        assertTrue(oldWorker.entered.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        try {
            manager.activateOfflineQueue();
            manager.allowed.set(false);
            oldWorker.release.countDown();
            oldWorker.join(TIMEOUT_MS);
            assertTrue(await(() -> waiterCount(manager) == 0));
            assertEquals(0, mission.starts.get());
            manager.allowed.set(true);
            manager.handleConnectivityState(DownloadManager.NetworkState.Operating, false);
            assertEquals(1, mission.starts.get());
        } finally {
            oldWorker.release.countDown();
            oldWorker.join(TIMEOUT_MS);
            manager.pauseAllMissions(true);
        }
    }

    @Test public void manualPausePreventsLateWorkerWake() throws Exception {
        final TestManager manager = manager();
        final FakeMission mission = new FakeMission("paused");
        pending(manager).add(mission);
        final GateWorker oldWorker = new GateWorker();
        mission.init = oldWorker;
        oldWorker.start();
        assertTrue(oldWorker.entered.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        try {
            manager.activateOfflineQueue();
            assertTrue(await(() -> waiterCount(manager) == 1));
            manager.pauseMission(mission);
            oldWorker.release.countDown();
            oldWorker.join(TIMEOUT_MS);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertFalse(mission.enqueued);
            assertEquals(0, mission.starts.get());
        } finally {
            oldWorker.release.countDown();
            oldWorker.join(TIMEOUT_MS);
            manager.pauseAllMissions(true);
        }
    }

    @Test public void normalAndErrorTerminationReleaseOnlyOneWaitingMission()
            throws Exception {
        final TestManager normal = manager();
        final FakeMission first = new FakeMission("normal-first");
        final FakeMission next = new FakeMission("normal-next");
        pending(normal).add(first);
        pending(normal).add(next);
        normal.activateOfflineQueue();
        assertEquals(1, first.starts.get());
        assertEquals(0, next.starts.get());
        first.running = false;
        first.current = 1;
        normal.runMissions();
        assertEquals(1, next.starts.get());
        assertEquals(1, normal.getRunningMissionsCount());

        final TestManager failed = manager();
        final FakeMission broken = new FakeMission("error-first");
        final FakeMission retry = new FakeMission("error-next");
        pending(failed).add(broken);
        pending(failed).add(retry);
        failed.activateOfflineQueue();
        assertEquals(1, broken.starts.get());
        broken.running = false;
        broken.enqueued = false;
        broken.errCode = DownloadMission.ERROR_FILE_CREATION;
        failed.runMissions();
        assertEquals(1, retry.starts.get());
        assertEquals(1, failed.getRunningMissionsCount());
        normal.pauseAllMissions(true);
        failed.pauseAllMissions(true);
    }

    private TestManager manager() throws Exception {
        assertEquals(BuildConfig.APPLICATION_ID, testContext.getPackageName());
        assertTrue("only an isolated debug package may own these dummy missions",
                testContext.getPackageName().contains(".debug."));
        final TestManager manager = new TestManager(testContext,
                new Handler(Looper.getMainLooper()));
        assertTrue("test package must have no preexisting missions", pending(manager).isEmpty());
        manager.mPrefQueueLimit = true;
        manager.handleConnectivityState(DownloadManager.NetworkState.Operating, true);
        return manager;
    }

    @SuppressWarnings("unchecked")
    private static ArrayList<DownloadMission> pending(final TestManager manager) throws Exception {
        final Field field = DownloadManager.class.getDeclaredField("mMissionsPending");
        field.setAccessible(true);
        return (ArrayList<DownloadMission>) field.get(manager);
    }

    private static int waiterCount(final TestManager manager) {
        try {
            final Field field = DownloadManager.class.getDeclaredField("mOfflineDrainWaiters");
            field.setAccessible(true);
            return ((java.util.Map<?, ?>) field.get(manager)).size();
        } catch (final ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static boolean await(final BooleanSupplier condition) throws Exception {
        final long end = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < end) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }

    private static final class TestManager extends DownloadManager {
        private final AtomicBoolean allowed = new AtomicBoolean(true);

        TestManager(final Context context, final Handler handler) {
            super(context, handler, null, null);
        }

        @Override boolean offlineNetworkAllowed() {
            return allowed.get();
        }
    }

    private static final class FakeMission extends DownloadMission {
        private final AtomicInteger starts = new AtomicInteger();
        private final CountDownLatch started = new CountDownLatch(1);

        FakeMission(final String id) {
            super(new String[]{"https://example.invalid/" + id},
                    new StoredFileHelper(null, id, "audio/mp4", "offline:" + id),
                    'a', null);
            enqueued = true;
        }

        @Override public void start() {
            starts.incrementAndGet();
            running = true;
            started.countDown();
        }

        @Override public void pause() {
            running = false;
        }

        @Override public void setEnqueued(final boolean queued) {
            enqueued = queued;
        }
    }

    private static final class GateWorker extends Thread {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override public void run() {
            entered.countDown();
            boolean done = false;
            while (!done) {
                try {
                    release.await();
                    done = true;
                } catch (final InterruptedException ignored) {
                    // A prior transfer can finish cleanup after an interrupt.
                }
            }
        }
    }
}
