package org.schabi.newpipe.player.helper;

/** Bounded, item-scoped recovery state. All calls are made on the player thread. */
public final class PlaybackRecovery {
    public enum Failure { TEMPORARY, SOURCE_REJECTED, PERMANENT }

    public static Failure classifyHttpStatus(final int status) {
        if (status == 403 || status == 410) {
            // A media URL may be stale; one ordinary refresh can establish that. This does
            // not imply that a 403 is expired, nor does it authorize bypassing access controls.
            return Failure.SOURCE_REJECTED;
        }
        if (status == 408 || status >= 500 && status <= 599) {
            return Failure.TEMPORARY;
        }
        return Failure.PERMANENT;
    }

    public static final class Attempt {
        public final long positionMillis;
        public final boolean playWhenReady;
        public final long delayMillis;
        public final boolean refreshSource;
        private final long generation;

        private Attempt(final long position, final boolean playIntent, final long delay,
                        final boolean refresh, final long token) {
            positionMillis = position;
            playWhenReady = playIntent;
            delayMillis = delay;
            refreshSource = refresh;
            generation = token;
        }
    }

    private Object item;
    private long generation;
    private int attempts;
    private boolean refreshed;
    private boolean hasPosition;
    private long position;
    private boolean playIntent;

    public Attempt next(final Object currentItem, final long positionMillis,
                        final boolean playWhenReady, final Failure failure,
                        final boolean localOnly) {
        if (item != currentItem) {
            reset();
            item = currentItem;
        }
        cancel();
        if (!hasPosition) {
            position = Math.max(0, positionMillis);
            playIntent = playWhenReady;
            hasPosition = true;
        }
        if (localOnly || failure == Failure.PERMANENT || attempts >= 2
                || (failure == Failure.SOURCE_REJECTED && refreshed)) {
            return null;
        }
        final boolean refresh = failure == Failure.SOURCE_REJECTED;
        refreshed |= refresh;
        return new Attempt(position, playIntent, ++attempts == 1 ? 1000 : 3000,
                refresh, generation);
    }

    public boolean isCurrent(final Attempt attempt, final Object currentItem) {
        return item == currentItem && attempt.generation == generation;
    }

    public long positionFor(final Object currentItem, final long fallback) {
        return item == currentItem && hasPosition ? position : fallback;
    }

    public void cancel() {
        generation++;
    }

    /** Keep the item budget even after READY: repeated READY/error cycles must not loop. */
    public void recovered() {
        hasPosition = false;
        cancel();
    }

    public void reset() {
        cancel();
        item = null;
        attempts = 0;
        refreshed = false;
        hasPosition = false;
    }
}
