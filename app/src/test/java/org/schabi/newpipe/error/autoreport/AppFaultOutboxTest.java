package org.schabi.newpipe.error.autoreport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class AppFaultOutboxTest {
    private static final long DAY = 86400000L;
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private SanitizedAppFault fault(final int version) {
        return SanitizedAppFault.fromAcra("java.lang.NullPointerException: PRIVATE_TOKEN"
                + "\n\tat org.schabi.newpipe.player.Player.play(/PRIVATE_PATH.java:1)",
                version, 35).get();
    }

    @Test
    public void restartRetainsSanitizedRecordsAndSuppressesDuplicates() throws Exception {
        final File directory = temporary.newFolder();
        assertEquals(AppFaultOutbox.Result.ADDED,
                new AppFaultOutbox(directory).enqueue(fault(123), DAY));
        final AppFaultOutbox reopened = new AppFaultOutbox(directory);
        assertEquals(1, reopened.pending(DAY).size());
        assertEquals(AppFaultOutbox.Result.DUPLICATE, reopened.enqueue(fault(123), DAY + 1));
        for (final File file : directory.listFiles()) {
            final String bytes = new String(Files.readAllBytes(file.toPath()),
                    StandardCharsets.ISO_8859_1);
            assertFalse(bytes.contains("PRIVATE"));
            assertFalse(bytes.contains("Player.play"));
        }
    }

    @Test
    public void mockDeliveryAcknowledgesWithoutLosingDedupeAcrossRestart() throws Exception {
        final File directory = temporary.newFolder();
        final AppFaultOutbox outbox = new AppFaultOutbox(directory);
        outbox.enqueue(fault(123), DAY);
        // In-memory transport substitute: no network implementation or credentials exist.
        final String delivered = outbox.pending(DAY).get(0).issueBody();
        assertTrue(delivered.contains("NULL_POINTER"));
        outbox.acknowledge(fault(123), DAY + 1);
        assertTrue(new AppFaultOutbox(directory).pending(DAY + 2).isEmpty());
        assertEquals(AppFaultOutbox.Result.DUPLICATE,
                new AppFaultOutbox(directory).enqueue(fault(123), DAY + 3));
        assertEquals(AppFaultOutbox.Result.ADDED,
                new AppFaultOutbox(directory).enqueue(fault(123), 2 * DAY + 1));
    }

    @Test
    public void quotaPersistsAcrossRestartAndClockRollbackDoesNotResetIt() throws Exception {
        final File directory = temporary.newFolder();
        for (int version = 1; version <= 3; version++) {
            assertEquals(AppFaultOutbox.Result.ADDED,
                    new AppFaultOutbox(directory).enqueue(fault(version), DAY));
        }
        assertEquals(AppFaultOutbox.Result.RATE_LIMITED,
                new AppFaultOutbox(directory).enqueue(fault(4), DAY + 1));
        assertEquals(AppFaultOutbox.Result.RATE_LIMITED,
                new AppFaultOutbox(directory).enqueue(fault(4), 0));
        assertEquals(AppFaultOutbox.Result.ADDED,
                new AppFaultOutbox(directory).enqueue(fault(4), 2 * DAY + 1));
    }

    @Test
    public void pendingQueueIsBoundedAndExpiresWithoutTransmission() throws Exception {
        final AppFaultOutbox outbox = new AppFaultOutbox(temporary.newFolder());
        for (int version = 1; version <= 8; version++) {
            assertEquals(AppFaultOutbox.Result.ADDED,
                    outbox.enqueue(fault(version), DAY * (1 + (version - 1) / 3)));
        }
        assertEquals(AppFaultOutbox.Result.FULL, outbox.enqueue(fault(9), 4 * DAY));
        assertEquals(8, outbox.pending(4 * DAY).size());
        assertTrue(outbox.pending(11 * DAY).isEmpty());
    }

    @Test
    public void corruptStateFailsClosedInsteadOfClearingQuota() throws Exception {
        final File directory = temporary.newFolder();
        Files.write(new File(directory, "outbox.bin").toPath(), new byte[]{1, 2, 3});
        assertThrows(IOException.class, () -> new AppFaultOutbox(directory).enqueue(fault(1), DAY));
        assertEquals(3, new File(directory, "outbox.bin").length());
    }

    @Test
    public void captureFailureDoesNotThrowOrCreateARecursiveReport() throws Exception {
        final File fileInsteadOfDirectory = temporary.newFile();
        final String trace = "java.lang.NullPointerException: PRIVATE"
                + "\n\tat org.schabi.newpipe.player.Player.play(PRIVATE:1)";
        assertFalse(AppFaultRecorder.record(trace, 123, 35, fileInsteadOfDirectory, DAY));
        assertFalse(AppFaultRecorder.record(trace, 123, 35, null, DAY));
        final File directory = temporary.newFolder();
        assertFalse(AppFaultRecorder.record("java.io.IOException: PRIVATE", 123, 35,
                directory, DAY));
        assertEquals(0, directory.listFiles().length);
        assertTrue(AppFaultRecorder.record(trace, 123, 35, directory, DAY));
        assertFalse(AppFaultRecorder.record(trace, 123, 35, directory, DAY));
    }

    @Test
    public void concurrentInstancesCannotExceedThePersistentQuota() throws Exception {
        final File directory = temporary.newFolder();
        final ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            final List<Future<AppFaultOutbox.Result>> results = new ArrayList<>();
            for (int version = 1; version <= 8; version++) {
                final int uniqueVersion = version;
                results.add(executor.submit(() -> new AppFaultOutbox(directory)
                        .enqueue(fault(uniqueVersion), DAY)));
            }
            int accepted = 0;
            for (final Future<AppFaultOutbox.Result> result : results) {
                final AppFaultOutbox.Result value = result.get();
                if (value == AppFaultOutbox.Result.ADDED) {
                    accepted++;
                } else {
                    assertEquals(AppFaultOutbox.Result.RATE_LIMITED, value);
                }
            }
            assertEquals(3, accepted);
            assertEquals(3, new AppFaultOutbox(directory).pending(DAY).size());
        } finally {
            executor.shutdownNow();
        }
    }
}
