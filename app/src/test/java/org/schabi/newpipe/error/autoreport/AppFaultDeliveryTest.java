package org.schabi.newpipe.error.autoreport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Coordinator tests for {@link AppFaultDelivery}. Only the network boundary is faked; every
 * assertion about delivery outcome is made against the real, file-backed {@link AppFaultOutbox}.
 */
public class AppFaultDeliveryTest {
    private static final long T0 = 1_000_000_000L;
    private static final long MINUTE = 60_000L;
    private static final long LEASE = 15 * MINUTE;
    private static final long HOUR = 60 * MINUTE;
    private static final long DAY = 24 * HOUR;

    private static final SanitizedAppFault FAULT = new SanitizedAppFault(
            SanitizedAppFault.Fault.NULL_POINTER, SanitizedAppFault.Component.PLAYER, 1005, 34);

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private File directory;

    @Before
    public void setUp() throws IOException {
        directory = folder.newFolder("autoreport");
    }

    private AppFaultOutbox outbox() {
        return new AppFaultOutbox(directory);
    }

    private interface Step {
        AppFaultDelivery.Response run(SanitizedAppFault fault) throws IOException;
    }

    /** Scripted transport; each send consumes exactly one scripted step. */
    private static final class ScriptedTransport implements AppFaultDelivery.Transport {
        private final Deque<Step> steps = new ArrayDeque<>();
        private final List<SanitizedAppFault> sent = new ArrayList<>();

        ScriptedTransport then(final Step step) {
            steps.add(step);
            return this;
        }

        ScriptedTransport thenRespond(final AppFaultDelivery.Response response) {
            return then(fault -> response);
        }

        @Override
        public AppFaultDelivery.Response send(final SanitizedAppFault fault)
                throws IOException {
            sent.add(fault);
            final Step step = steps.poll();
            if (step == null) {
                throw new AssertionError("Unexpected network send");
            }
            return step.run(fault);
        }
    }

    private static AppFaultDelivery.Response created(final long issue) {
        return new AppFaultDelivery.Response(201, true, issue, 0L);
    }

    @Test
    public void emptyOutboxPerformsNoHttp() throws IOException {
        final ScriptedTransport transport = new ScriptedTransport();
        final AppFaultDelivery delivery = new AppFaultDelivery(outbox(), transport);

        assertFalse(delivery.deliverOne(T0));
        assertTrue(transport.sent.isEmpty());
        assertEquals(-1L, outbox().nextEligibleAt(T0));
        assertTrue(outbox().pending(T0).isEmpty());
    }

    @Test
    public void claimIsPersistedBeforeTransportAndConfirmedCreateAcknowledges()
            throws IOException {
        assertEquals(AppFaultOutbox.Result.ADDED, outbox().enqueue(FAULT, T0));
        final List<String> observedBeforeNetwork = new ArrayList<>();
        final ScriptedTransport transport = new ScriptedTransport().then(fault -> {
            // A fresh outbox over the same files sees only what was durably written.
            final AppFaultOutbox persisted = outbox();
            assertEquals(Collections.singletonList(FAULT), persisted.pending(T0));
            assertEquals(T0 + LEASE, persisted.nextEligibleAt(T0));
            assertFalse("leased claim must deny a second sender",
                    persisted.claim(T0).isPresent());
            observedBeforeNetwork.add("leased");
            return created(42L);
        });
        final AppFaultDelivery delivery = new AppFaultDelivery(outbox(), transport);

        assertTrue(delivery.deliverOne(T0));
        assertEquals(Collections.singletonList("leased"), observedBeforeNetwork);
        assertEquals(Collections.singletonList(FAULT), transport.sent);
        assertTrue(outbox().pending(T0 + 1).isEmpty());
        assertEquals(-1L, outbox().nextEligibleAt(T0 + 1));
    }

    @Test
    public void confirmedDuplicateAcknowledgesAndLocalDedupePreventsResend()
            throws IOException {
        outbox().enqueue(FAULT, T0);
        final ScriptedTransport transport = new ScriptedTransport()
                .thenRespond(new AppFaultDelivery.Response(200, true, 7L, 0L));
        final AppFaultDelivery delivery = new AppFaultDelivery(outbox(), transport);

        assertTrue(delivery.deliverOne(T0));
        assertTrue(outbox().pending(T0 + 1).isEmpty());
        assertEquals(AppFaultOutbox.Result.DUPLICATE, outbox().enqueue(FAULT, T0 + 2));
        assertFalse(delivery.deliverOne(T0 + 3));
        assertFalse(delivery.deliverOne(T0 + DAY / 2));
        assertEquals(1, transport.sent.size());
    }

    @Test
    public void timeoutReopensWithBackoffAndStopsAfterThreeAttempts() throws IOException {
        outbox().enqueue(FAULT, T0);
        final Step timeout = fault -> {
            throw new SocketTimeoutException("timeout");
        };
        final ScriptedTransport transport = new ScriptedTransport()
                .then(timeout).then(timeout).then(timeout);
        final AppFaultDelivery delivery = new AppFaultDelivery(outbox(), transport);

        assertTrue(delivery.deliverOne(T0));
        assertEquals(Collections.singletonList(FAULT), outbox().pending(T0));
        assertEquals(T0 + LEASE, outbox().nextEligibleAt(T0));
        assertFalse("backoff must hold", delivery.deliverOne(T0 + LEASE - 1));
        assertEquals(1, transport.sent.size());

        final long second = T0 + LEASE;
        assertTrue(delivery.deliverOne(second));
        assertEquals(second + 2 * LEASE, outbox().nextEligibleAt(second));

        final long third = second + 2 * LEASE;
        assertTrue(delivery.deliverOne(third));
        assertEquals(3, transport.sent.size());

        // Attempts exhausted: retained locally, never sent again, no eligible work remains.
        assertEquals(Collections.singletonList(FAULT), outbox().pending(third + 1));
        assertEquals(-1L, outbox().nextEligibleAt(third + 1));
        assertFalse(delivery.deliverOne(third + DAY));
        assertEquals(3, transport.sent.size());
    }

    @Test
    public void rateLimitedRetryAfterIsPersistedAndCappedAtOneDay() throws IOException {
        outbox().enqueue(FAULT, T0);
        final ScriptedTransport transport = new ScriptedTransport()
                .thenRespond(new AppFaultDelivery.Response(429, false, 0L, 2 * HOUR))
                .thenRespond(new AppFaultDelivery.Response(429, false, 0L, 3 * DAY));
        final AppFaultDelivery delivery = new AppFaultDelivery(outbox(), transport);

        assertTrue(delivery.deliverOne(T0));
        // Survives a process restart via a new outbox instance.
        assertEquals(T0 + 2 * HOUR, outbox().nextEligibleAt(T0));
        assertFalse(delivery.deliverOne(T0 + 2 * HOUR - 1));
        assertEquals(1, transport.sent.size());

        final long second = T0 + 2 * HOUR;
        assertTrue(delivery.deliverOne(second));
        assertEquals(second + DAY, outbox().nextEligibleAt(second));
        assertEquals(Collections.singletonList(FAULT), outbox().pending(second));
    }

    @Test
    public void serverErrorRetriesAfterLease() throws IOException {
        outbox().enqueue(FAULT, T0);
        final ScriptedTransport transport = new ScriptedTransport()
                .thenRespond(new AppFaultDelivery.Response(503, false, 0L, 0L));
        final AppFaultDelivery delivery = new AppFaultDelivery(outbox(), transport);

        assertTrue(delivery.deliverOne(T0));
        assertEquals(Collections.singletonList(FAULT), outbox().pending(T0));
        assertEquals(T0 + LEASE, outbox().nextEligibleAt(T0));
    }

    @Test
    public void unconfirmedOrInvalidSuccessIsNeverAcknowledged() throws IOException {
        final AppFaultDelivery.Response[] invalid = {
                new AppFaultDelivery.Response(200, false, 0L, 0L),
                new AppFaultDelivery.Response(201, true, 0L, 0L),
                new AppFaultDelivery.Response(201, true, -5L, 0L),
                new AppFaultDelivery.Response(204, true, 9L, 0L),
        };
        for (final AppFaultDelivery.Response response : invalid) {
            final File isolated = folder.newFolder();
            final AppFaultOutbox local = new AppFaultOutbox(isolated);
            local.enqueue(FAULT, T0);
            final ScriptedTransport transport = new ScriptedTransport().thenRespond(response);
            final AppFaultDelivery delivery =
                    new AppFaultDelivery(new AppFaultOutbox(isolated), transport);

            assertTrue(delivery.deliverOne(T0));
            final AppFaultOutbox reread = new AppFaultOutbox(isolated);
            assertEquals(Collections.singletonList(FAULT), reread.pending(T0));
            final long next = reread.nextEligibleAt(T0);
            assertTrue("invalid success must not be immediately resent",
                    next == -1L || next > T0);
            assertFalse(delivery.deliverOne(T0));
            assertEquals(1, transport.sent.size());
        }
    }

    @Test
    public void permanentForbiddenIsRetainedWithoutEligibleWork() throws IOException {
        outbox().enqueue(FAULT, T0);
        final ScriptedTransport transport = new ScriptedTransport()
                .thenRespond(new AppFaultDelivery.Response(403, false, 0L, 0L));
        final AppFaultDelivery delivery = new AppFaultDelivery(outbox(), transport);

        assertTrue(delivery.deliverOne(T0));
        assertEquals(Collections.singletonList(FAULT), outbox().pending(T0 + 1));
        assertEquals(-1L, outbox().nextEligibleAt(T0 + 1));
        assertFalse(delivery.deliverOne(T0 + DAY));
        assertEquals(1, transport.sent.size());
    }

    @Test
    public void interruptedClaimIsNotResentUntilLeaseExpiresAndStaleClaimCannotComplete()
            throws IOException {
        outbox().enqueue(FAULT, T0);
        // A previous process claimed and then crashed before completing.
        final Optional<AppFaultOutbox.Claim> crashed = outbox().claim(T0);
        assertTrue(crashed.isPresent());

        final ScriptedTransport transport = new ScriptedTransport().thenRespond(created(11L));
        final AppFaultDelivery delivery = new AppFaultDelivery(outbox(), transport);

        assertFalse(delivery.deliverOne(T0 + LEASE - 1));
        assertTrue(transport.sent.isEmpty());

        assertTrue(delivery.deliverOne(T0 + LEASE));
        assertEquals(1, transport.sent.size());
        assertTrue(outbox().pending(T0 + LEASE).isEmpty());
        assertFalse("stale generation must be rejected", outbox().complete(
                crashed.get(), AppFaultOutbox.Outcome.RETRY, T0 + LEASE, 0L));
        assertTrue(outbox().pending(T0 + LEASE).isEmpty());
    }

    @Test
    public void uncheckedTransportFailureDoesNotRecurseOrAcknowledge() throws IOException {
        outbox().enqueue(FAULT, T0);
        final ScriptedTransport transport = new ScriptedTransport().then(fault -> {
            throw new IllegalStateException("boom");
        });
        final AppFaultDelivery delivery = new AppFaultDelivery(outbox(), transport);

        try {
            delivery.deliverOne(T0);
        } catch (final RuntimeException expectedOrHandled) {
            // Either propagating or handling is acceptable; resending is not.
        }
        assertEquals(1, transport.sent.size());
        assertEquals(Collections.singletonList(FAULT), outbox().pending(T0));
        final long next = outbox().nextEligibleAt(T0);
        assertTrue(next == -1L || next > T0);
        assertFalse(delivery.deliverOne(T0));
        assertEquals(1, transport.sent.size());
    }

    @Test
    public void retryAfterIsMeasuredFromCompletionClockNotClaimTime() throws IOException {
        outbox().enqueue(FAULT, T0);
        final AtomicLong clock = new AtomicLong(T0);
        final long retryAfter = 15 * MINUTE;
        final long requestDuration = 20_000L;
        final ScriptedTransport transport = new ScriptedTransport().then(fault -> {
            // The claim must already be durable before any network work starts.
            final AppFaultOutbox persisted = outbox();
            assertEquals(Collections.singletonList(FAULT), persisted.pending(T0));
            assertEquals(T0 + LEASE, persisted.nextEligibleAt(T0));
            assertFalse("leased claim must deny a second sender",
                    persisted.claim(T0).isPresent());
            // The request itself takes time; completion happens later than the claim.
            clock.addAndGet(requestDuration);
            return new AppFaultDelivery.Response(429, false, 0L, retryAfter);
        });
        final AppFaultDelivery delivery =
                new AppFaultDelivery(outbox(), transport, clock::get);

        assertTrue(delivery.deliverOne(T0));
        assertEquals(1, transport.sent.size());
        assertEquals(T0 + requestDuration, clock.get());

        final long deadline = clock.get() + retryAfter;
        assertEquals("retry deadline must be anchored at completion time",
                deadline, outbox().nextEligibleAt(clock.get()));
        assertEquals(Collections.singletonList(FAULT), outbox().pending(clock.get()));

        // An empty claim does not alter delivery state, so the boundary check stays honest.
        assertFalse("nothing may be eligible before the deadline",
                outbox().claim(deadline - 1).isPresent());
        assertEquals(deadline, outbox().nextEligibleAt(deadline - 1));
        assertTrue("fault must be eligible exactly at the deadline",
                outbox().claim(deadline).isPresent());
        assertEquals(1, transport.sent.size());
    }
}
