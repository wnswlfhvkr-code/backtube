package org.schabi.newpipe.error.autoreport;

import java.io.IOException;

/**
 * Pure scheduling helper for sanitized app fault delivery.
 *
 * <p>Computes how long a scheduler should wait before the next delivery attempt, based solely
 * on the persisted outbox state. When the relay endpoint is missing or invalid, delivery is
 * off and no storage is accessed, so no job or network work must be scheduled.</p>
 */
public final class AppFaultScheduling {
    /** Returned when nothing should be scheduled. */
    private static final long NONE = -1L;

    private AppFaultScheduling() {
    }

    /**
     * Computes the delay until the next fault may be claimed for delivery.
     * @param outbox the persisted fault outbox, possibly {@code null}
     * @param endpoint the build-time relay endpoint, possibly {@code null} or empty
     * @param now current wall clock time in milliseconds
     * @return {@code -1} when delivery is off or nothing is sendable, otherwise the
     *         non-negative delay in milliseconds until the next eligible claim
     * @throws IOException if the local fault storage cannot be read
     */
    public static long delayUntilNext(final AppFaultOutbox outbox, final String endpoint,
                                      final long now) throws IOException {
        if (!AppFaultHttpProtocol.isEndpointConfigured(endpoint) || outbox == null) {
            return NONE;
        }
        final long next = outbox.nextEligibleAt(now);
        if (next < 0) {
            return NONE;
        }
        return Math.max(0L, next - now);
    }
}
