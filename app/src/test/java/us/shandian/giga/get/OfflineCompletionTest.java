package us.shandian.giga.get;

import android.os.Handler;
import android.os.Message;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.schabi.newpipe.streams.io.StoredFileHelper;

import java.io.File;
import java.io.IOException;

import us.shandian.giga.service.DownloadManagerService;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class OfflineCompletionTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void shelfCommitsBeforeDurableMissionIsRemoved() throws Exception {
        final StoredFileHelper storage = mock(StoredFileHelper.class);
        final DownloadMission mission = completedMission(storage);
        final File metadata = mission.metadata;
        doAnswer(invocation -> {
            assertTrue("resume metadata removed before shelf commit", metadata.exists());
            return null;
        }).when(storage).commitOfflineDownload();
        mission.notifyFinished();
        assertFalse(metadata.exists());
        verify(mission.mHandler).obtainMessage(DownloadManagerService.MESSAGE_FINISHED, mission);
    }

    @Test public void failedShelfCommitPreservesMissionAndCanRetryWithoutDownloading()
            throws Exception {
        final StoredFileHelper storage = mock(StoredFileHelper.class);
        final DownloadMission mission = completedMission(storage);
        final File metadata = mission.metadata;
        doThrow(new IOException("simulated full disk")).doNothing()
                .when(storage).commitOfflineDownload();
        when(storage.existsAsFile()).thenReturn(true);
        mission.notifyFinished();
        assertTrue(metadata.exists());
        verify(mission.mHandler).obtainMessage(DownloadManagerService.MESSAGE_ERROR, mission);
        mission.start();
        assertFalse("retry must finish the shelf commit", metadata.exists());
        verify(mission.mHandler).obtainMessage(DownloadManagerService.MESSAGE_FINISHED, mission);
    }

    private DownloadMission completedMission(final StoredFileHelper storage) throws Exception {
        // No networking or Android service: completion is the only exercised stage.
        final DownloadMission mission = new DownloadMission(
                new String[]{"https://example.invalid/generated.wav"}, storage, 'a', null) {
            @Override void writeThisToFile() { }
        };
        mission.current = mission.urls.length;
        mission.metadata = temporary.newFile();
        mission.mHandler = mock(Handler.class);
        when(mission.mHandler.obtainMessage(anyInt(), same(mission)))
                .thenReturn(mock(Message.class));
        return mission;
    }
}
