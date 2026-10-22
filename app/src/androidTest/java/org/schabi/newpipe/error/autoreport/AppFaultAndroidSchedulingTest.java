package org.schabi.newpipe.error.autoreport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.work.Configuration;
import androidx.work.NetworkType;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.testing.SynchronousExecutor;
import androidx.work.testing.WorkManagerTestInitHelper;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.BuildConfig;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Verifies that {@link AppFaultScheduler} keeps at most one unfinished, network-constrained
 * delivery job, and none when delivery is off or nothing is sendable. Constraints and initial
 * delays are never signalled as satisfied, so no worker runs and no HTTP request is made.
 */
@RunWith(AndroidJUnit4.class)
public class AppFaultAndroidSchedulingTest {
    private static final String ENDPOINT = "https://relay.invalid/v1/fault";
    private static final long NOW = 1_700_000_000_000L;

    private Context context;
    private File directory;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final Configuration configuration = new Configuration.Builder()
                .setMinimumLoggingLevel(Log.ERROR)
                .setExecutor(new SynchronousExecutor())
                .build();
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration);
        directory = new File(context.getCacheDir(), "app-fault-scheduling-" + System.nanoTime());
        assertTrue(directory.mkdirs());
    }

    @After
    public void tearDown() throws Exception {
        WorkManager.getInstance(context).cancelUniqueWork(AppFaultScheduler.WORK_NAME)
                .getResult().get();
        final File[] files = directory.listFiles();
        if (files != null) {
            for (final File file : files) {
                file.delete();
            }
        }
        directory.delete();
    }

    @Test
    public void emptyOrNullEndpointSchedulesNothing() throws Exception {
        final AppFaultOutbox outbox = queuedOutbox();
        outbox.claim(NOW);
        AppFaultScheduler.schedule(context, "", outbox, NOW);
        assertEquals(0, unfinished().size());
        AppFaultScheduler.schedule(context, null, outbox, NOW);
        assertEquals(0, unfinished().size());
    }

    @Test
    public void nullOutboxSchedulesNothing() throws Exception {
        AppFaultScheduler.schedule(context, ENDPOINT, null, NOW);
        assertEquals(0, unfinished().size());
    }

    @Test
    public void emptyQueueSchedulesNothing() throws Exception {
        AppFaultScheduler.schedule(context, ENDPOINT, new AppFaultOutbox(directory), NOW);
        assertEquals(0, unfinished().size());
    }

    @Test
    public void claimedFaultSchedulesSingleConnectedJob() throws Exception {
        final AppFaultOutbox outbox = queuedOutbox();
        outbox.claim(NOW);

        AppFaultScheduler.schedule(context, ENDPOINT, outbox, NOW);
        assertSingleConnectedJob();

        AppFaultScheduler.schedule(context, ENDPOINT, outbox, NOW + 1);
        assertSingleConnectedJob();

        AppFaultScheduler.schedule(context, ENDPOINT, new AppFaultOutbox(directory), NOW + 2);
        assertSingleConnectedJob();
    }

    @Test
    public void acknowledgedFaultCancelsJob() throws Exception {
        final AppFaultOutbox outbox = queuedOutbox();
        final AppFaultOutbox.Claim claim = outbox.claim(NOW).get();
        AppFaultScheduler.schedule(context, ENDPOINT, outbox, NOW);
        assertSingleConnectedJob();

        assertTrue(outbox.complete(claim, AppFaultOutbox.Outcome.ACK, NOW + 1, 0L));
        AppFaultScheduler.schedule(context, ENDPOINT, outbox, NOW + 2);
        assertEquals(0, unfinished().size());
    }

    @Test
    public void retainedFaultCancelsJob() throws Exception {
        final AppFaultOutbox outbox = queuedOutbox();
        final AppFaultOutbox.Claim claim = outbox.claim(NOW).get();
        AppFaultScheduler.schedule(context, ENDPOINT, outbox, NOW);
        assertSingleConnectedJob();

        assertTrue(outbox.complete(claim, AppFaultOutbox.Outcome.RETAIN, NOW + 1, 0L));
        AppFaultScheduler.schedule(context, ENDPOINT, outbox, NOW + 2);
        assertEquals(0, unfinished().size());
    }

    @Test
    public void startupRequestWithDefaultEmptyEndpointCancelsExistingJob() throws Exception {
        Assume.assumeTrue(
                !AppFaultHttpProtocol.isEndpointConfigured(BuildConfig.APP_FAULT_ENDPOINT));
        final AppFaultOutbox outbox = queuedOutbox();
        outbox.claim(NOW);
        AppFaultScheduler.schedule(context, ENDPOINT, outbox, NOW);
        assertSingleConnectedJob();

        final CountDownLatch finished = new CountDownLatch(1);
        AppFaultScheduler.request(context, finished::countDown);
        assertTrue(finished.await(10, TimeUnit.SECONDS));
        assertEquals(0, unfinished().size());
    }

    private static SanitizedAppFault fault() {
        return new SanitizedAppFault(SanitizedAppFault.Fault.values()[0],
                SanitizedAppFault.Component.values()[0], 1, Build.VERSION.SDK_INT);
    }

    private AppFaultOutbox queuedOutbox() throws Exception {
        final AppFaultOutbox outbox = new AppFaultOutbox(directory);
        assertEquals(AppFaultOutbox.Result.ADDED, outbox.enqueue(fault(), NOW));
        return outbox;
    }

    private void assertSingleConnectedJob() throws Exception {
        final List<WorkInfo> infos = unfinished();
        assertEquals(1, infos.size());
        assertEquals(NetworkType.CONNECTED,
                infos.get(0).getConstraints().getRequiredNetworkType());
    }

    private List<WorkInfo> unfinished() throws Exception {
        final List<WorkInfo> result = new ArrayList<>();
        for (final WorkInfo info : WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(AppFaultScheduler.WORK_NAME).get()) {
            if (!info.getState().isFinished()) {
                result.add(info);
            }
        }
        return result;
    }
}
