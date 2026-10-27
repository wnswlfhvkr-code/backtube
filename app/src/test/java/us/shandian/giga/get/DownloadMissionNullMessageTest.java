package us.shandian.giga.get;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.Handler;
import android.os.Message;
import android.util.Log;

import java.io.IOException;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.schabi.newpipe.streams.io.StoredFileHelper;

public class DownloadMissionNullMessageTest {
    @Test
    public void nullIoMessagePreservesOriginalErrorWhenStorageIsWritable() {
        final StoredFileHelper storage = mock(StoredFileHelper.class);
        when(storage.canWrite()).thenReturn(true);
        final DownloadMission mission = mission(storage);
        final IOException original = new IOException();

        try (MockedStatic<Log> ignored = mockStatic(Log.class)) {
            mission.notifyError(original);
        }

        assertEquals(DownloadMission.ERROR_UNKNOWN_EXCEPTION, mission.errCode);
        assertSame(original, mission.errObject);
        assertFalse(mission.enqueued);
        verify(mission.mHandler).obtainMessage(
                same(us.shandian.giga.service.DownloadManagerService.MESSAGE_ERROR), same(mission));
    }

    @Test
    public void nullIoMessageStillMapsUnwritableStorageToFileError() {
        final StoredFileHelper storage = mock(StoredFileHelper.class);
        when(storage.canWrite()).thenReturn(false);
        final DownloadMission mission = mission(storage);

        try (MockedStatic<Log> ignored = mockStatic(Log.class)) {
            mission.notifyError(new IOException());
        }

        assertEquals(DownloadMission.ERROR_FILE_CREATION, mission.errCode);
        assertEquals(null, mission.errObject);
    }

    private static DownloadMission mission(final StoredFileHelper storage) {
        final DownloadMission mission = new DownloadMission(
                new String[]{"https://example.invalid/fixture"}, storage, 'a', null);
        mission.running = false;
        mission.enqueued = true;
        mission.mHandler = mock(Handler.class);
        when(mission.mHandler.obtainMessage(anyInt(), same(mission)))
                .thenReturn(mock(Message.class));
        return mission;
    }
}
