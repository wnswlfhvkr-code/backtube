package us.shandian.giga.get;

import android.util.Log;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.schabi.newpipe.streams.io.SharpStream;
import org.schabi.newpipe.streams.io.StoredFileHelper;

import java.net.HttpURLConnection;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Generated 403 responses; no network requests or user download files. */
public class RangeRecoveryOwnerTest {
    @Test
    public void zeroBasedOnlyWorkerRequestsExpiredUrlRecovery() throws Exception {
        final Fixture fixture = new Fixture(0, 403);
        fixture.run();
        verify(fixture.mission).doRecover(DownloadMission.ERROR_HTTP_FORBIDDEN);
        verify(fixture.stream).close();
        verify(fixture.mission, never()).notifyFinished();
    }

    @Test
    public void otherWorkerDoesNotStartConcurrentExpiredUrlRecovery() throws Exception {
        final Fixture fixture = new Fixture(1, 403);
        fixture.run();
        verify(fixture.mission, never()).doRecover(DownloadMission.ERROR_HTTP_FORBIDDEN);
        verify(fixture.stream).close();
    }

    @Test
    public void nonForbiddenHttpErrorUsesNormalFailurePath() throws Exception {
        final Fixture fixture = new Fixture(0, 500);
        fixture.run();
        verify(fixture.mission, never()).doRecover(DownloadMission.ERROR_HTTP_FORBIDDEN);
        verify(fixture.mission).notifyError(any(Exception.class));
        verify(fixture.stream).close();
    }

    private static final class Fixture {
        final DownloadMission mission = mock(DownloadMission.class);
        final SharpStream stream = mock(SharpStream.class);
        final HttpURLConnection connection = mock(HttpURLConnection.class);
        final int workerId;

        Fixture(final int workerId, final int status) throws Exception {
            this.workerId = workerId;
            mission.storage = mock(StoredFileHelper.class);
            mission.running = true;
            mission.errCode = DownloadMission.ERROR_NOTHING;
            mission.length = 4;
            mission.offsets = new long[]{0};
            mission.current = 0;
            mission.maxRetry = 0;
            when(mission.storage.getStream()).thenReturn(stream);
            when(mission.acquireBlock()).thenReturn(new DownloadMission.Block());
            when(mission.openConnection(false, 0, 3)).thenReturn(connection);
            doThrow(new DownloadMission.HttpError(status)).when(mission)
                    .establishConnection(workerId, connection);
        }

        void run() {
            try (MockedStatic<Log> ignored = mockStatic(Log.class)) {
                new DownloadRunnable(mission, workerId).run();
            }
        }
    }
}
