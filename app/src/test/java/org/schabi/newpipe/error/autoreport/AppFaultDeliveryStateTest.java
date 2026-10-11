package org.schabi.newpipe.error.autoreport;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

/**
 * Delivery state tests for the local app fault outbox. These use real files only and contain
 * no credentials, URLs, device identifiers or personal data.
 */
public class AppFaultDeliveryStateTest {
    private static final long MINUTE = 60_000L;
    private static final long LEASE = 15 * MINUTE;
    private static final long HOUR = 60 * MINUTE;
    private static final long DAY = 24 * HOUR;
    private static final long T0 = 1_700_000_000_000L;
    private static final int LEGACY_MAGIC = 0x41465031;

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private File directory;

    @Before
    public void setUp() throws IOException {
        directory = temporaryFolder.newFolder("autoreport");
    }

    private AppFaultOutbox open() {
        return new AppFaultOutbox(directory);
    }

    private static SanitizedAppFault fault(final int version) {
        return SanitizedAppFault.fromAcra("java.lang.NullPointerException\n"
                + "\tat org.schabi.newpipe.player.Player.handle(Player.java:10)\n", version, 30)
                .orElseThrow(AssertionError::new);
    }

    private AppFaultOutbox.Claim claim(final AppFaultOutbox outbox, final long now)
            throws IOException {
        return outbox.claim(now).orElseThrow(AssertionError::new);
    }

    @Test
    public void claimPersistsAttemptAndLeaseBeforeNetworkBoundary() throws IOException {
        final SanitizedAppFault fault = fault(100);
        assertEquals(AppFaultOutbox.Result.ADDED, open().enqueue(fault, T0));
        assertEquals(T0, open().nextEligibleAt(T0));

        final AppFaultOutbox.Claim claim = claim(open(), T0);
        assertEquals(fault, claim.fault());
        assertEquals(1, claim.attempts());

        // A fresh instance (as after a process death during HTTP) sees the persisted lease.
        final AppFaultOutbox reopened = open();
        assertFalse(reopened.claim(T0).isPresent());
        assertFalse(reopened.claim(T0 + LEASE - 1).isPresent());
        assertEquals(T0 + LEASE, reopened.nextEligibleAt(T0 + 1));
        assertEquals(Collections.singletonList(fault), reopened.pending(T0 + 1));
    }

    @Test
    public void interruptedClaimsAcrossRestartsStopAfterThreeAttempts() throws IOException {
        final SanitizedAppFault fault = fault(100);
        open().enqueue(fault, T0);

        final AppFaultOutbox.Claim first = claim(open(), T0);
        final AppFaultOutbox.Claim second = claim(open(), T0 + LEASE);
        assertEquals(2, second.attempts());
        assertTrue(second.generation() > first.generation());
        final AppFaultOutbox.Claim third = claim(open(), T0 + 2 * LEASE);
        assertEquals(3, third.attempts());
        assertTrue(third.generation() > second.generation());

        final AppFaultOutbox reopened = open();
        assertFalse(reopened.claim(T0 + 3 * LEASE).isPresent());
        assertEquals(-1L, reopened.nextEligibleAt(T0 + 3 * LEASE));
        assertEquals(Collections.singletonList(fault), reopened.pending(T0 + 3 * LEASE));
    }

    @Test
    public void staleCompletionCannotAcknowledgeReclaimedGeneration() throws IOException {
        final SanitizedAppFault fault = fault(100);
        open().enqueue(fault, T0);
        final AppFaultOutbox.Claim stale = claim(open(), T0);
        final AppFaultOutbox.Claim current = claim(open(), T0 + LEASE);

        try {
            open().complete(stale, AppFaultOutbox.Outcome.ACK, T0 + LEASE + 1, 0);
        } catch (final IOException expectedRejection) {
            // Rejecting the stale claim is acceptable; acknowledging it is not.
        }
        assertEquals(Collections.singletonList(fault), open().pending(T0 + LEASE + 1));
        assertFalse(open().claim(T0 + LEASE + 1).isPresent());

        open().complete(current, AppFaultOutbox.Outcome.ACK, T0 + LEASE + 2, 0);
        assertTrue(open().pending(T0 + LEASE + 2).isEmpty());
    }

    @Test
    public void acknowledgementKeepsDedupeAndQuotaReceipts() throws IOException {
        final SanitizedAppFault fault = fault(100);
        open().enqueue(fault, T0);
        open().complete(claim(open(), T0), AppFaultOutbox.Outcome.ACK, T0 + 1, 0);

        final AppFaultOutbox reopened = open();
        assertTrue(reopened.pending(T0 + 2).isEmpty());
        assertEquals(-1L, reopened.nextEligibleAt(T0 + 2));
        assertFalse(reopened.claim(T0 + 2).isPresent());
        assertEquals(AppFaultOutbox.Result.DUPLICATE, reopened.enqueue(fault, T0 + HOUR));
        assertEquals(AppFaultOutbox.Result.ADDED, reopened.enqueue(fault(101), T0 + HOUR));
        assertEquals(AppFaultOutbox.Result.ADDED, reopened.enqueue(fault(102), T0 + HOUR));
        assertEquals(AppFaultOutbox.Result.RATE_LIMITED,
                reopened.enqueue(fault(103), T0 + HOUR));
    }

    @Test
    public void retryUsesFifteenMinuteBaseDelay() throws IOException {
        open().enqueue(fault(100), T0);
        open().complete(claim(open(), T0), AppFaultOutbox.Outcome.RETRY, T0 + MINUTE, 0);

        final long deadline = T0 + MINUTE + LEASE;
        assertEquals(deadline, open().nextEligibleAt(T0 + MINUTE));
        assertFalse(open().claim(deadline - 1).isPresent());
        assertEquals(2, claim(open(), deadline).attempts());
    }

    @Test
    public void retryAfterExtendsDelayButIsCappedAtOneDay() throws IOException {
        open().enqueue(fault(100), T0);
        open().complete(claim(open(), T0), AppFaultOutbox.Outcome.RETRY, T0, 2 * HOUR);
        assertEquals(T0 + 2 * HOUR, open().nextEligibleAt(T0));
        assertFalse(open().claim(T0 + 2 * HOUR - 1).isPresent());

        final AppFaultOutbox.Claim second = claim(open(), T0 + 2 * HOUR);
        open().complete(second, AppFaultOutbox.Outcome.RETRY, T0 + 2 * HOUR, 48 * HOUR);
        assertEquals(T0 + 2 * HOUR + DAY, open().nextEligibleAt(T0 + 2 * HOUR));
    }

    @Test
    public void exhaustedOrRetainedFaultsStayUntilSevenDayExpiry() throws IOException {
        final SanitizedAppFault exhausted = fault(100);
        final SanitizedAppFault retained = fault(101);
        open().enqueue(exhausted, T0);
        open().enqueue(retained, T0);

        for (int attempt = 1; attempt <= 3; attempt++) {
            final long now = T0 + (attempt - 1) * DAY;
            AppFaultOutbox.Claim claim = claim(open(), now);
            if (claim.fault().equals(retained)) {
                open().complete(claim, AppFaultOutbox.Outcome.RETAIN, now, 0);
                claim = claim(open(), now);
            }
            assertEquals(exhausted, claim.fault());
            assertEquals(attempt, claim.attempts());
            open().complete(claim, AppFaultOutbox.Outcome.RETRY, now, 0);
        }

        final AppFaultOutbox reopened = open();
        assertEquals(-1L, reopened.nextEligibleAt(T0 + 6 * DAY));
        assertFalse(reopened.claim(T0 + 6 * DAY).isPresent());
        assertEquals(Arrays.asList(exhausted, retained), reopened.pending(T0 + 6 * DAY));
        assertEquals(AppFaultOutbox.Result.DUPLICATE, reopened.enqueue(exhausted, T0 + 6 * DAY));

        assertTrue(reopened.pending(T0 + 7 * DAY).isEmpty());
        assertEquals(-1L, reopened.nextEligibleAt(T0 + 7 * DAY));
    }

    @Test
    public void clockRollbackCannotReopenLeaseOrQuota() throws IOException {
        open().enqueue(fault(100), T0);
        claim(open(), T0);

        final AppFaultOutbox reopened = open();
        assertFalse(reopened.claim(T0 - DAY).isPresent());
        assertEquals(T0 + LEASE, reopened.nextEligibleAt(T0 - DAY));
        assertFalse(reopened.claim(T0 + LEASE - 1).isPresent());

        assertEquals(AppFaultOutbox.Result.ADDED, reopened.enqueue(fault(101), T0 - DAY));
        assertEquals(AppFaultOutbox.Result.ADDED, reopened.enqueue(fault(102), T0 - 2 * DAY));
        assertEquals(AppFaultOutbox.Result.RATE_LIMITED,
                reopened.enqueue(fault(103), T0 - 3 * DAY));
        assertEquals(2, claim(reopened, T0 + LEASE).attempts());
    }

    @Test
    public void legacyLedgerMigratesWithDedupeAndQuotaPreserved() throws IOException {
        writeLegacyLedger(T0, 100, 101, 102);

        final AppFaultOutbox outbox = open();
        assertEquals(AppFaultOutbox.Result.DUPLICATE, outbox.enqueue(fault(100), T0));
        assertEquals(AppFaultOutbox.Result.RATE_LIMITED, outbox.enqueue(fault(103), T0));
        assertEquals(Arrays.asList(fault(100), fault(101), fault(102)), outbox.pending(T0));

        final AppFaultOutbox.Claim claim = claim(open(), T0);
        assertEquals(1, claim.attempts());
        assertTrue(Arrays.asList(fault(100), fault(101), fault(102)).contains(claim.fault()));
    }

    @Test
    public void malformedLedgerFailsClosedWithoutRewriting() throws IOException {
        final File ledger = new File(directory, "outbox.bin");
        final byte[] garbage = {1, 2, 3, 4, 5};
        Files.write(ledger.toPath(), garbage);

        try {
            open().claim(T0);
            fail("Malformed ledger must not be claimable");
        } catch (final IOException expected) {
            // fail closed
        }
        try {
            open().enqueue(fault(100), T0);
            fail("Malformed ledger must not be replaced");
        } catch (final IOException expected) {
            // fail closed
        }
        assertArrayEquals(garbage, Files.readAllBytes(ledger.toPath()));
    }

    private void writeLegacyLedger(final long clock, final int... versions) throws IOException {
        try (DataOutputStream output = new DataOutputStream(
                new FileOutputStream(new File(directory, "outbox.bin")))) {
            output.writeInt(LEGACY_MAGIC);
            output.writeLong(clock);
            output.writeInt(versions.length);
            for (final int version : versions) {
                output.writeByte(SanitizedAppFault.Fault.NULL_POINTER.ordinal());
                output.writeByte(SanitizedAppFault.Component.PLAYER.ordinal());
                output.writeInt(version);
                output.writeInt(30);
                output.writeLong(clock);
                output.writeBoolean(true);
            }
        }
    }
}
