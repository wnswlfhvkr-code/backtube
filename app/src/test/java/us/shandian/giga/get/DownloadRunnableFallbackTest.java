package us.shandian.giga.get;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.util.Log;

import java.io.IOException;
import org.junit.Test;
import org.mockito.MockedStatic;

/** Generated connection failures; no network or user download files. */
public class DownloadRunnableFallbackTest {
    private static final long RESUME_OFFSET = 123L;

    @Test
    public void openFailureBeforeConnectionAssignmentReportsOriginalError() throws Exception {
        final DownloadMission mission = mission(0);
        final IOException failure = new IOException("generated connection setup failure");
        when(mission.openConnection(false, RESUME_OFFSET, -1)).thenThrow(failure);

        runWithoutAndroidLog(mission);

        verify(mission).notifyError(same(failure));
        assertEquals(RESUME_OFFSET, mission.fallbackResumeOffset);
    }

    @Test
    public void openFailureBeforeConnectionAssignmentUsesConfiguredRetry() throws Exception {
        final DownloadMission mission = mission(1);
        final IOException first = new IOException("generated first connection failure");
        final IOException second = new IOException("generated second connection failure");
        when(mission.openConnection(false, RESUME_OFFSET, -1))
                .thenThrow(first).thenThrow(second);

        runWithoutAndroidLog(mission);

        verify(mission, times(2)).openConnection(false, RESUME_OFFSET, -1);
        verify(mission).notifyError(same(second));
        assertEquals(RESUME_OFFSET, mission.fallbackResumeOffset);
    }

    @Test
    public void canceledMissionDoesNotReportConnectionSetupFailure() throws Exception {
        final DownloadMission mission = mission(0);
        mission.running = false;
        when(mission.openConnection(false, RESUME_OFFSET, -1))
                .thenThrow(new IOException("generated canceled connection failure"));

        runWithoutAndroidLog(mission);

        verify(mission, never()).notifyError(org.mockito.ArgumentMatchers.any(Exception.class));
        assertEquals(RESUME_OFFSET, mission.fallbackResumeOffset);
    }

    private static DownloadMission mission(final int maxRetry) {
        final DownloadMission mission = mock(DownloadMission.class);
        mission.fallbackResumeOffset = RESUME_OFFSET;
        mission.running = true;
        mission.maxRetry = maxRetry;
        return mission;
    }

    private static void runWithoutAndroidLog(final DownloadMission mission) {
        try (MockedStatic<Log> ignored = mockStatic(Log.class)) {
            new DownloadRunnableFallback(mission).run();
        }
    }
}
