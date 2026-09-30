package org.schabi.newpipe.player.helper;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.util.function.DoubleConsumer;
import java.util.function.LongSupplier;

import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.disposables.SerialDisposable;

/** A replaceable timer, confined to the player's main thread. */
public final class SleepTimer {
    private static final long MAX_DURATION_MILLIS = 86400000L;
    private static final long FADE_DURATION_MILLIS = 10000L;
    private static final long FADE_TICK_MILLIS = 200L;

    private final Scheduler scheduler;
    private final LongSupplier elapsedRealtime;
    private final Runnable onExpired;
    private final DoubleConsumer onGainChanged;
    private final SerialDisposable pending = new SerialDisposable();
    private long deadline;
    private long itemTimeRemainingMillis = -1;
    private boolean endOfItem;
    private boolean fadeEnabled = true;
    private double gain = 1.0;

    public SleepTimer(final Scheduler scheduler, final LongSupplier elapsedRealtime,
                      final Runnable onExpired) {
        this(scheduler, elapsedRealtime, onExpired, ignored -> { });
    }

    public SleepTimer(final Scheduler scheduler, final LongSupplier elapsedRealtime,
                      final Runnable onExpired, final DoubleConsumer onGainChanged) {
        this.scheduler = scheduler;
        this.elapsedRealtime = elapsedRealtime;
        this.onExpired = onExpired;
        this.onGainChanged = onGainChanged;
    }

    public void start(final long durationMillis) {
        if (durationMillis <= 0 || durationMillis > MAX_DURATION_MILLIS) {
            throw new IllegalArgumentException("Timer duration must be between 1 ms and 24 hours");
        }
        cancel();
        deadline = elapsedRealtime.getAsLong() + durationMillis;
        updateTimedFade();
        scheduleNext();
    }

    public boolean isEndOfItem() {
        return endOfItem;
    }

    public void startAtEndOfItem() {
        cancel();
        endOfItem = true;
    }

    public void onItemEnded() {
        if (endOfItem) {
            expire();
        }
    }

    public void updateItemTimeRemaining(final long realRemainingMillis) {
        itemTimeRemainingMillis = realRemainingMillis;
        if (endOfItem) {
            updateFade(realRemainingMillis);
        }
    }

    public void extend(final long extraMillis) {
        if (extraMillis <= 0) {
            throw new IllegalArgumentException("Timer extension must be positive");
        }
        final long remaining = remainingMillis();
        final long duration = extraMillis >= MAX_DURATION_MILLIS - remaining
                ? MAX_DURATION_MILLIS : remaining + extraMillis;
        start(duration);
    }

    public void setFadeEnabled(final boolean enabled) {
        if (fadeEnabled == enabled) {
            return;
        }
        fadeEnabled = enabled;
        if (endOfItem) {
            updateFade(itemTimeRemainingMillis);
            return;
        }
        updateTimedFade();
        pending.set(null);
        scheduleNext();
    }

    public boolean isFadeEnabled() {
        return fadeEnabled;
    }

    public double getGain() {
        return gain;
    }

    public void cancel() {
        pending.set(null);
        deadline = 0;
        endOfItem = false;
        itemTimeRemainingMillis = -1;
        setGain(1.0);
    }

    public long remainingMillis() {
        return deadline == 0 ? 0 : Math.max(0, deadline - elapsedRealtime.getAsLong());
    }

    // Also checks elapsed real time when returning from device sleep.
    public boolean expireIfDue() {
        if (deadline == 0 || elapsedRealtime.getAsLong() < deadline) {
            return false;
        }
        expire();
        return true;
    }

    private void scheduleNext() {
        if (deadline == 0 || endOfItem) {
            return;
        }
        final long remaining = remainingMillis();
        if (remaining == 0) {
            expireIfDue();
            return;
        }
        final long delay = !fadeEnabled || remaining > FADE_DURATION_MILLIS
                ? remaining - (fadeEnabled ? FADE_DURATION_MILLIS : 0)
                : Math.min(FADE_TICK_MILLIS, remaining);
        pending.set(scheduler.scheduleDirect(this::onTimerTick, delay, MILLISECONDS));
    }

    private void onTimerTick() {
        if (!expireIfDue()) {
            updateTimedFade();
            scheduleNext();
        }
    }

    private void updateTimedFade() {
        if (deadline != 0) {
            updateFade(remainingMillis());
        }
    }

    private void updateFade(final long remainingMillis) {
        final double nextGain = fadeEnabled && remainingMillis >= 0
                && remainingMillis < FADE_DURATION_MILLIS
                ? remainingMillis / (double) FADE_DURATION_MILLIS : 1.0;
        setGain(nextGain);
    }

    private void expire() {
        pending.set(null);
        deadline = 0;
        endOfItem = false;
        itemTimeRemainingMillis = -1;
        try {
            onExpired.run();
        } finally {
            setGain(1.0);
        }
    }

    private void setGain(final double nextGain) {
        if (Double.compare(gain, nextGain) != 0) {
            gain = nextGain;
            onGainChanged.accept(nextGain);
        }
    }
}
