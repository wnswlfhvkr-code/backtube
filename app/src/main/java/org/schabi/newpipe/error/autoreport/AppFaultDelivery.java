package org.schabi.newpipe.error.autoreport;

import java.io.IOException;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Performs at most one delivery attempt for one sanitized app fault.
 *
 * <p>The claim is durably persisted by {@link AppFaultOutbox#claim(long)} before the transport
 * is invoked, so a crash during the request still counts as an attempt and holds the lease.
 * A fault is acknowledged only when the relay confirmed a created or duplicate issue with a
 * positive issue number. Transport failures never propagate, recurse or resend; they are
 * recorded as a retry, which the outbox bounds to three attempts per fault.</p>
 */
public final class AppFaultDelivery {
    private final AppFaultOutbox outbox;
    private final Transport transport;
    private final LongSupplier completionClock;

    /** Network boundary sending one sanitized fault to the relay. */
    public interface Transport {
        /**
         * Sends the allowlisted payload of one fault.
         * @param fault the sanitized fault to send
         * @return the parsed relay response
         * @throws IOException on timeout, connection or protocol failure
         */
        Response send(SanitizedAppFault fault) throws IOException;
    }

    /** Parsed relay response; only numeric fields, never raw bodies or headers. */
    public static final class Response {
        private final int status;
        private final boolean confirmed;
        private final long issueNumber;
        private final long retryAfterMillis;

        /**
         * @param status HTTP status code
         * @param confirmed whether the relay confirmed a created or duplicate issue
         * @param issueNumber the confirmed issue number, positive when confirmed
         * @param retryAfterMillis server requested delay, or 0 when absent
         */
        public Response(final int status, final boolean confirmed, final long issueNumber,
                        final long retryAfterMillis) {
            this.status = status;
            this.confirmed = confirmed;
            this.issueNumber = issueNumber;
            this.retryAfterMillis = Math.max(0L, retryAfterMillis);
        }

        public int status() {
            return status;
        }

        public boolean confirmed() {
            return confirmed;
        }

        public long issueNumber() {
            return issueNumber;
        }

        public long retryAfterMillis() {
            return retryAfterMillis;
        }
    }

    /**
     * Creates a delivery that records completion at the deterministic time passed to
     * {@link #deliverOne(long)}.
     * @param outbox the durable fault outbox
     * @param transport the network boundary
     * @throws IllegalArgumentException if a dependency is missing
     */
    public AppFaultDelivery(final AppFaultOutbox outbox, final Transport transport) {
        this(outbox, transport, null);
    }

    /**
     * Creates a delivery that samples a completion clock after the transport returns or throws,
     * so server retry delays are anchored at the actual completion time.
     * @param outbox the durable fault outbox
     * @param transport the network boundary
     * @param completionClock wall clock in milliseconds sampled at completion, or {@code null}
     *        to use the time passed to {@link #deliverOne(long)}
     * @throws IllegalArgumentException if the outbox or transport is missing
     */
    public AppFaultDelivery(final AppFaultOutbox outbox, final Transport transport,
                            final LongSupplier completionClock) {
        if (outbox == null || transport == null) {
            throw new IllegalArgumentException("Missing delivery dependency");
        }
        this.outbox = outbox;
        this.transport = transport;
        this.completionClock = completionClock;
    }

    /**
     * Claims and sends at most one eligible fault.
     * @param now current wall clock time in milliseconds
     * @return {@code true} if a claim was made and a send attempted, {@code false} if no fault
     *         was eligible
     * @throws IOException if local fault storage is unavailable or invalid, or if the
     *         completion clock fails or reports a negative time; the claim then stays leased
     *         and is not completed
     */
    public boolean deliverOne(final long now) throws IOException {
        final Optional<AppFaultOutbox.Claim> claim = outbox.claim(now);
        if (!claim.isPresent()) {
            return false;
        }
        Response response;
        try {
            response = transport.send(claim.get().fault());
        } catch (final IOException | RuntimeException e) {
            response = null;
        }
        final AppFaultOutbox.Outcome outcome = classify(response);
        final long retryAfter = response == null ? 0L : response.retryAfterMillis();
        final long completedAt = completionTime(now);
        outbox.complete(claim.get(), outcome, completedAt, retryAfter);
        return true;
    }

    /**
     * Samples the completion time; a clock rollback can never move it before the claim time.
     * @param now the claim time
     * @return the completion time in milliseconds
     * @throws IOException if the completion clock fails or reports a negative time
     */
    private long completionTime(final long now) throws IOException {
        if (completionClock == null) {
            return now;
        }
        final long sampled;
        try {
            sampled = completionClock.getAsLong();
        } catch (final RuntimeException e) {
            throw new IOException("Completion clock unavailable");
        }
        if (sampled < 0L) {
            throw new IOException("Invalid completion clock");
        }
        return Math.max(now, sampled);
    }

    private static AppFaultOutbox.Outcome classify(final Response response) {
        if (response == null) {
            return AppFaultOutbox.Outcome.RETRY;
        }
        final int status = response.status();
        if (status == 200 || status == 201) {
            // Unconfirmed or malformed success may hide a created issue; retry, never ACK.
            return response.confirmed() && response.issueNumber() > 0
                    ? AppFaultOutbox.Outcome.ACK : AppFaultOutbox.Outcome.RETRY;
        }
        if (status == 408 || status == 429 || status >= 500) {
            return AppFaultOutbox.Outcome.RETRY;
        }
        if (status >= 400 && status < 500) {
            return AppFaultOutbox.Outcome.RETAIN;
        }
        return AppFaultOutbox.Outcome.RETRY;
    }
}
