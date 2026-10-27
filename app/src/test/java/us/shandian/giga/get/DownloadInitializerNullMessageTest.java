package us.shandian.giga.get;

import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.util.Log;

import java.io.IOException;

import org.junit.Test;
import org.mockito.MockedStatic;

public class DownloadInitializerNullMessageTest {
    @Test
    public void nullIoMessageRetriesThenReportsOriginalFailure() throws Exception {
        final DownloadMission mission = mock(DownloadMission.class);
        mission.urls = new String[]{"https://example.invalid/generated"};
        mission.running = true;
        mission.maxRetry = 0;
        final IOException first = new IOException();
        final IOException second = new IOException();
        when(mission.openConnection(mission.urls[0], true, 0, 0))
                .thenThrow(first).thenThrow(second);

        try (MockedStatic<Log> ignored = mockStatic(Log.class)) {
            new DownloadInitializer(mission).run();
        }

        verify(mission, times(2)).openConnection(mission.urls[0], true, 0, 0);
        verify(mission).notifyError(same(second));
    }
}
