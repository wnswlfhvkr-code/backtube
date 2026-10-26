package us.shandian.giga.get;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.Test;
import org.schabi.newpipe.streams.io.StoredFileHelper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

public class MobileDataMissionPersistenceTest {
    @Test public void mobileApprovalSurvivesPendingMissionSerialization() throws Exception {
        final DownloadMission mission = new DownloadMission(
                new String[]{"https://example.invalid/generated"},
                mock(StoredFileHelper.class), 'a', null);
        mission.storage = null;
        assertFalse(mission.mobileDataApproved);
        mission.mobileDataApproved = true;
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(mission);
        }
        try (ObjectInputStream input = new ObjectInputStream(
                new ByteArrayInputStream(bytes.toByteArray()))) {
            assertTrue(((DownloadMission) input.readObject()).mobileDataApproved);
        }
    }
}
