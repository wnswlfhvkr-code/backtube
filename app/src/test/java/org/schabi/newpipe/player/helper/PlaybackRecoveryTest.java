package org.schabi.newpipe.player.helper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PlaybackRecoveryTest {
    private final PlaybackRecovery recovery = new PlaybackRecovery();
    private final Object item = new Object();

    @Test
    public void unsetPositionIsClampedToStart() {
        final PlaybackRecovery.Attempt attempt = recovery.next(item, Long.MIN_VALUE + 1, false,
                PlaybackRecovery.Failure.TEMPORARY, false);
        assertEquals(0, attempt.positionMillis);
    }

    @Test
    public void recoveredPlaybackUsesLatestPositionWithoutResettingBudget() {
        recovery.next(item, 42000, true, PlaybackRecovery.Failure.TEMPORARY, false);
        recovery.recovered();
        final PlaybackRecovery.Attempt next = recovery.next(item, 80000, false,
                PlaybackRecovery.Failure.TEMPORARY, false);
        assertEquals(80000, next.positionMillis);
        assertFalse(next.playWhenReady);
        assertNull(recovery.next(item, 80000, false,
                PlaybackRecovery.Failure.TEMPORARY, false));
    }

    @Test
    public void httpStatusSeparatesAmbiguousSourcesFromNetworkAndAccessFailures() {
        assertEquals(PlaybackRecovery.Failure.SOURCE_REJECTED,
                PlaybackRecovery.classifyHttpStatus(403));
        assertEquals(PlaybackRecovery.Failure.SOURCE_REJECTED,
                PlaybackRecovery.classifyHttpStatus(410));
        for (final int status : new int[]{408, 500, 502, 503, 504}) {
            assertEquals(PlaybackRecovery.Failure.TEMPORARY,
                    PlaybackRecovery.classifyHttpStatus(status));
        }
        for (final int status : new int[]{-1, 400, 401, 404, 429, 451}) {
            assertEquals(PlaybackRecovery.Failure.PERMANENT,
                    PlaybackRecovery.classifyHttpStatus(status));
        }
    }

    @Test
    public void temporaryFailurePreservesFirstPositionAndIntentWithFiniteBackoff() {
        final PlaybackRecovery.Attempt first = recovery.next(item, 42000, true,
                PlaybackRecovery.Failure.TEMPORARY, false);
        assertNotNull(first);
        assertEquals(1000, first.delayMillis);
        assertEquals(42000, first.positionMillis);
        assertTrue(first.playWhenReady);
        assertFalse(first.refreshSource);
        final PlaybackRecovery.Attempt second = recovery.next(item, 0, false,
                PlaybackRecovery.Failure.TEMPORARY, false);
        assertEquals(3000, second.delayMillis);
        assertEquals(42000, second.positionMillis);
        assertTrue(second.playWhenReady);
        assertNull(recovery.next(item, 0, true, PlaybackRecovery.Failure.TEMPORARY, false));
    }

    @Test
    public void ambiguousForbiddenRefreshesOnlyOnceThenStops() {
        final PlaybackRecovery.Attempt first = recovery.next(item, 42000, false,
                PlaybackRecovery.Failure.SOURCE_REJECTED, false);
        assertTrue(first.refreshSource);
        assertFalse(first.playWhenReady);
        assertNull(recovery.next(item, 0, false,
                PlaybackRecovery.Failure.SOURCE_REJECTED, false));
    }

    @Test
    public void localAndPermanentFailuresNeverRetry() {
        assertNull(recovery.next(item, 1, true,
                PlaybackRecovery.Failure.SOURCE_REJECTED, true));
        assertNull(recovery.next(item, 1, true, PlaybackRecovery.Failure.TEMPORARY, true));
        assertNull(recovery.next(item, 1, true, PlaybackRecovery.Failure.PERMANENT, false));
    }

    @Test
    public void pauseSkipAndDestructionInvalidateLateAttempts() {
        final PlaybackRecovery.Attempt first = recovery.next(item, 42000, true,
                PlaybackRecovery.Failure.TEMPORARY, false);
        assertTrue(recovery.isCurrent(first, item));
        assertFalse(recovery.isCurrent(first, new Object()));
        recovery.cancel();
        assertFalse(recovery.isCurrent(first, item));
    }

    @Test
    public void manualRetryStartsNewBudgetAndInvalidatesOldAttempt() {
        final PlaybackRecovery.Attempt first = recovery.next(item, 42000, true,
                PlaybackRecovery.Failure.SOURCE_REJECTED, false);
        recovery.reset();
        assertFalse(recovery.isCurrent(first, item));
        assertNotNull(recovery.next(item, 42000, true,
                PlaybackRecovery.Failure.SOURCE_REJECTED, false));
    }

    @Test
    public void readyDoesNotPermitRepeatedRefreshLoop() {
        recovery.next(item, 42000, true, PlaybackRecovery.Failure.SOURCE_REJECTED, false);
        recovery.recovered();
        assertNull(recovery.next(item, 45000, true,
                PlaybackRecovery.Failure.SOURCE_REJECTED, false));
    }

    @Test
    public void newQueueItemGetsItsOwnPositionAndBudget() {
        recovery.next(item, 42000, true, PlaybackRecovery.Failure.SOURCE_REJECTED, false);
        final PlaybackRecovery.Attempt next = recovery.next(new Object(), 9000, false,
                PlaybackRecovery.Failure.SOURCE_REJECTED, false);
        assertEquals(9000, next.positionMillis);
        assertFalse(next.playWhenReady);
    }
}
