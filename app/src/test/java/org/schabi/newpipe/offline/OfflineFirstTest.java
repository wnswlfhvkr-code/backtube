package org.schabi.newpipe.offline;

import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class OfflineFirstTest {
    @Test public void savedMediaDoesNotAskRemoteForMetadata() throws Exception {
        assertEquals("local", OfflineFirst.select(() -> "local", () -> {
            throw new AssertionError("Network must not run");
        }, false));
    }

    @Test public void missingLocalIdentityNeverFallsThroughToExtractor() {
        assertThrows(IOException.class, () -> OfflineFirst.select(() -> null, () -> {
            throw new AssertionError("Network must not run");
        }, true));
    }

    @Test public void ordinaryUnsavedItemUsesExistingOnlinePath() throws Exception {
        assertEquals("online", OfflineFirst.select(() -> null, () -> "online", false));
    }

    @Test public void damagedOfflineInventoryDoesNotBreakOrdinaryOnlinePlayback() throws Exception {
        assertEquals("online", OfflineFirst.select(() -> {
            throw new IOException("Damaged offline inventory");
        }, () -> "online", false));
    }
}
