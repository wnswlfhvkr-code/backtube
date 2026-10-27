package us.shandian.giga.service;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.os.Handler;
import android.os.Message;
import android.util.Log;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.schabi.newpipe.streams.io.StoredFileHelper;

import us.shandian.giga.get.DownloadMission;

public class DownloadForcePauseTest {
    @Test
    public void forcedPauseSignalsInitializerAndPublishedTransferWithoutForgettingThem()
            throws Exception {
        final DownloadMission mission = mission();
        final DownloadManager manager = manager(mission);
        final RetainedWorker initializer = new RetainedWorker();
        final RetainedWorker transfer = new RetainedWorker();
        final Thread[] published = {transfer};
        mission.init = initializer;
        mission.threads = published;
        initializer.start();
        transfer.start();
        assertTrue(initializer.started.await(2, TimeUnit.SECONDS));
        assertTrue(transfer.started.await(2, TimeUnit.SECONDS));

        try {
            try (MockedStatic<Log> ignored = org.mockito.Mockito.mockStatic(Log.class)) {
                manager.pauseAllMissions(true);
            }

            assertTrue(initializer.interrupted.await(1, TimeUnit.SECONDS));
            assertTrue(transfer.interrupted.await(1, TimeUnit.SECONDS));
            assertSame(initializer, mission.init);
            assertSame(published, mission.threads);
            assertFalse(mission.running);
            assertTrue("force pause must not join a still-owned worker", transfer.isAlive());
        } finally {
            initializer.release.countDown();
            transfer.release.countDown();
            initializer.join(2000);
            transfer.join(2000);
        }
    }

    @Test
    public void forcedPauseSignalsPublishedTransferWithoutInitializer() throws Exception {
        final DownloadMission mission = mission();
        final DownloadManager manager = manager(mission);
        final RetainedWorker transfer = new RetainedWorker();
        final Thread[] published = {transfer};
        mission.threads = published;
        transfer.start();
        assertTrue(transfer.started.await(2, TimeUnit.SECONDS));

        try {
            try (MockedStatic<Log> ignored = org.mockito.Mockito.mockStatic(Log.class)) {
                manager.pauseAllMissions(true);
            }

            assertTrue(transfer.interrupted.await(1, TimeUnit.SECONDS));
            assertSame(published, mission.threads);
            assertFalse(mission.running);
        } finally {
            transfer.release.countDown();
            transfer.join(2000);
        }
    }

    private static DownloadMission mission() {
        final DownloadMission mission = new DownloadMission(
                new String[]{"https://example.invalid/generated"}, mock(StoredFileHelper.class),
                'a', null);
        mission.running = true;
        mission.mHandler = mock(Handler.class);
        when(mission.mHandler.obtainMessage(anyInt(), same(mission)))
                .thenReturn(mock(Message.class));
        return mission;
    }

    private static DownloadManager manager(final DownloadMission mission) throws Exception {
        final DownloadManager manager = mock(DownloadManager.class, CALLS_REAL_METHODS);
        final ArrayList<DownloadMission> pending = new ArrayList<>();
        pending.add(mission);
        final Field field = DownloadManager.class.getDeclaredField("mMissionsPending");
        field.setAccessible(true);
        field.set(manager, pending);
        return manager;
    }

    private static final class RetainedWorker extends Thread {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch interrupted = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void run() {
            started.countDown();
            boolean finished = false;
            while (!finished) {
                try {
                    release.await();
                    finished = true;
                } catch (final InterruptedException ignored) {
                    interrupted.countDown();
                }
            }
        }
    }
}
