package us.shandian.giga.service;

import android.content.Context;

import org.junit.Test;
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
