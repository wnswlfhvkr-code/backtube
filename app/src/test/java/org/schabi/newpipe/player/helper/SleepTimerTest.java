package org.schabi.newpipe.player.helper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import io.reactivex.rxjava3.schedulers.TestScheduler;

public class SleepTimerTest {
    @Test
    public void expiresOnceAndCanBeCancelledOrReplaced() {
        final TestScheduler scheduler = new TestScheduler();
        final AtomicInteger pauses = new AtomicInteger();
        final SleepTimer timer = new SleepTimer(scheduler,
                () -> scheduler.now(MILLISECONDS), pauses::incrementAndGet);

        timer.start(1000);
        scheduler.advanceTimeBy(400, MILLISECONDS);
        assertEquals(600, timer.remainingMillis());
        timer.start(2000);
        scheduler.advanceTimeBy(600, MILLISECONDS);
        assertEquals(0, pauses.get());
        assertEquals(1400, timer.remainingMillis());
        scheduler.advanceTimeBy(1400, MILLISECONDS);
        assertEquals(1, pauses.get());
        assertEquals(0, timer.remainingMillis());
        assertFalse(timer.expireIfDue());

        timer.start(1000);
        timer.cancel();
        scheduler.advanceTimeBy(2000, MILLISECONDS);
        assertEquals(1, pauses.get());
        assertEquals(0, timer.remainingMillis());
    }

    @Test
    public void elapsedTimeExpiryIsHandledAfterDeviceSleep() {
        final TestScheduler scheduler = new TestScheduler();
        final AtomicLong elapsed = new AtomicLong(100);
        final AtomicInteger pauses = new AtomicInteger();
        final SleepTimer timer = new SleepTimer(scheduler, elapsed::get, pauses::incrementAndGet);
        timer.start(1000);
        elapsed.addAndGet(2000);
        assertEquals(0, timer.remainingMillis());
        assertTrue(timer.expireIfDue());
        scheduler.advanceTimeBy(1000, MILLISECONDS);
        assertEquals(1, pauses.get());
    }

    @Test
    public void rejectsInvalidDurationsWithoutReplacingActiveTimer() {
        final TestScheduler scheduler = new TestScheduler();
        final SleepTimer timer = new SleepTimer(scheduler,
                () -> scheduler.now(MILLISECONDS), () -> { });
        timer.start(1000);
        for (final long duration : new long[]{0, -1, 86400001, Long.MAX_VALUE}) {
            org.junit.Assert.assertThrows(IllegalArgumentException.class,
                    () -> timer.start(duration));
            assertEquals(1000, timer.remainingMillis());
        }
        timer.start(86400000);
        assertEquals(86400000, timer.remainingMillis());
    }

    @Test
    public void endOfItemModeFadesOnlyFromPlayerUpdatesAndExpiresOnItemEnd() {
        final TestScheduler scheduler = new TestScheduler();
        final AtomicInteger pauses = new AtomicInteger();
        final AtomicReference<Double> gain = new AtomicReference<>(1.0);
        final SleepTimer timer = new SleepTimer(scheduler,
                () -> scheduler.now(MILLISECONDS), pauses::incrementAndGet, gain::set);

        timer.startAtEndOfItem();
        assertTrue(timer.isEndOfItem());
        timer.updateItemTimeRemaining(5000);
        assertEquals(0.5, gain.get(), 0.0001);
        scheduler.advanceTimeBy(3600000, MILLISECONDS);
        assertEquals(0, pauses.get());

        timer.onItemEnded();
        assertEquals(1, pauses.get());
        assertFalse(timer.isEndOfItem());
        assertEquals(1.0, gain.get(), 0.0001);
    }

    @Test
    public void timedFadeKeepsReducedGainUntilExpiryCallbackThenRestoresIt() {
        final TestScheduler scheduler = new TestScheduler();
        final AtomicReference<Double> gain = new AtomicReference<>(1.0);
        final AtomicReference<Double> gainAtPause = new AtomicReference<>(1.0);
        final SleepTimer timer = new SleepTimer(scheduler,
                () -> scheduler.now(MILLISECONDS), () -> gainAtPause.set(gain.get()), gain::set);

        timer.start(10000);
        scheduler.advanceTimeBy(9800, MILLISECONDS);
        assertEquals(0.02, gain.get(), 0.0001);
        scheduler.advanceTimeBy(200, MILLISECONDS);
        assertEquals(0.02, gainAtPause.get(), 0.0001);
        assertEquals(1.0, gain.get(), 0.0001);
    }

    @Test
    public void extendConvertsEndModeAndCapsAnActiveTimerAtTwentyFourHours() {
        final TestScheduler scheduler = new TestScheduler();
        final SleepTimer timer = new SleepTimer(scheduler,
                () -> scheduler.now(MILLISECONDS), () -> { });

        timer.startAtEndOfItem();
        timer.extend(15000);
        assertFalse(timer.isEndOfItem());
        assertEquals(15000, timer.remainingMillis());
        scheduler.advanceTimeBy(1000, MILLISECONDS);
        timer.extend(5000);
        assertEquals(19000, timer.remainingMillis());
        timer.extend(Long.MAX_VALUE);
        assertEquals(86400000, timer.remainingMillis());
    }

    @Test
    public void disablingFadeRestoresGain() {
        final TestScheduler scheduler = new TestScheduler();
        final AtomicReference<Double> gain = new AtomicReference<>(1.0);
        final SleepTimer timer = new SleepTimer(scheduler,
                () -> scheduler.now(MILLISECONDS), () -> { }, gain::set);

        timer.startAtEndOfItem();
        timer.updateItemTimeRemaining(1000);
        timer.setFadeEnabled(false);
        assertFalse(timer.isFadeEnabled());
        assertEquals(1.0, gain.get(), 0.0001);
    }
}
