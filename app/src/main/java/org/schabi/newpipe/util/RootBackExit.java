package org.schabi.newpipe.util;

/** Monotonic, non-extending confirmation window for leaving the UI task. */
public final class RootBackExit {
    public static final long WINDOW_MILLIS = 1000;
    private long armedAt = -1;
    private long generation;

    public boolean press(final long now) {
        if (armedAt >= 0 && now >= armedAt && now - armedAt < WINDOW_MILLIS) {
            reset();
            return true;
        }
        armedAt = now;
        generation++;
        return false;
    }

    public void reset() {
        armedAt = -1;
        generation++;
    }

    public long generation() {
        return generation;
    }

    public void dismiss(final long expectedGeneration) {
        if (generation == expectedGeneration) {
            reset();
        }
    }
}
