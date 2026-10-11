package org.schabi.newpipe.error.autoreport;

import static org.junit.Assert.assertEquals;
 import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

public class AppFaultSchedulingTest {
    private static final String ENDPOINT = "https://relay.example/v1/fault";
    private static final String TRACE = "java.lang.NullPointerException: boom\n"
            + "\tat org.schabi.newpipe.player.Player.run(Player.java:1)";
    private static final long NOW = 1000L;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private AppFaultOutbox queued(final String name) throws IOException {
        final AppFaultOutbox outbox = new AppFaultOutbox(folder.newFolder(name));
        assertEquals(AppFaultOutbox.Result.ADDED, outbox.enqueue(
                SanitizedAppFault.fromAcra(TRACE, 1, 30).get(), NOW));
        return outbox;
    }

    @Test
    public void emptyEndpointIsOffWithoutReadingStorage() throws IOException {
        assertEquals(-1L, AppFaultScheduling.delayUntilNext(null, "", NOW));
    }

    @Test
    public void emptyQueueHasNoDelay() throws IOException {
        final AppFaultOutbox outbox = new AppFaultOutbox(folder.newFolder("empty"));
        assertEquals(-1L, AppFaultScheduling.delayUntilNext(outbox, ENDPOINT, NOW));
    }

    @Test
    public void claimLeaseDelaysAcrossReopen() throws IOException {
        final AppFaultOutbox outbox = queued("lease");
        assertEquals(0L, AppFaultScheduling.delayUntilNext(outbox, ENDPOINT, NOW));
        assertTrue(outbox.claim(NOW).isPresent());
        final AppFaultOutbox reopened = new AppFaultOutbox(new File(folder.getRoot(), "lease"));
        assertEquals(15 * 60000L, AppFaultScheduling.delayUntilNext(reopened, ENDPOINT, NOW));
    }

    @Test
    public void ackOrRetainLeavesNothingToSchedule() throws IOException {
        for (final AppFaultOutbox.Outcome outcome : new AppFaultOutbox.Outcome[] {
                AppFaultOutbox.Outcome.ACK, AppFaultOutbox.Outcome.RETAIN}) {
            final AppFaultOutbox outbox = queued(outcome.name());
            assertTrue(outbox.complete(outbox.claim(NOW).get(), outcome, NOW, 0L));
            assertEquals(-1L, AppFaultScheduling.delayUntilNext(outbox, ENDPOINT, NOW));
        }
    }
}
