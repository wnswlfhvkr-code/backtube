package org.schabi.newpipe.player.gesture;

/** Recognizes only the two deliberate player fullscreen swipes. */
public final class FullscreenSwipeDetector {
    public enum Result {
        NONE, ENTER, EXIT, GIVE_TO_PARENT
    }

    private enum State {
        IDLE, INLINE, AWAIT_SECOND, TWO_FINGERS, REJECTED, TRANSITIONED
    }

    private final float intentSlop;
    private final float triggerDistance;
    private State state = State.IDLE;
    private float firstX;
    private float firstY;
    private float secondX;
    private float secondY;

    public FullscreenSwipeDetector(final float intentSlop, final float triggerDistance) {
        this.intentSlop = intentSlop;
        this.triggerDistance = triggerDistance;
    }

    public void down(final float x, final float y, final boolean fullscreen) {
        firstX = x;
        firstY = y;
        state = fullscreen ? State.AWAIT_SECOND : State.INLINE;
    }

    public Result moveOne(final float x, final float y) {
        final float dx = x - firstX;
        final float dy = y - firstY;
        if (state == State.AWAIT_SECOND && (Math.abs(dx) > intentSlop
                || Math.abs(dy) > intentSlop)) {
            state = State.REJECTED;
        } else if (state == State.INLINE) {
            if (dy < -triggerDistance && -dy > Math.abs(dx) * 1.5f) {
                state = State.TRANSITIONED;
                return Result.ENTER;
            }
            if (dy > intentSlop || (Math.abs(dx) > intentSlop
                    && Math.abs(dx) > Math.abs(dy))) {
                state = State.REJECTED;
                return Result.GIVE_TO_PARENT;
            }
        }
        return Result.NONE;
    }

    public void secondDown(final float x0, final float y0,
                           final float x1, final float y1) {
        if (state == State.AWAIT_SECOND) {
            firstX = x0;
            firstY = y0;
            secondX = x1;
            secondY = y1;
            state = State.TWO_FINGERS;
        } else {
            state = State.REJECTED;
        }
    }

    public Result moveTwo(final float x0, final float y0,
                          final float x1, final float y1) {
        if (state != State.TWO_FINGERS) {
            return Result.NONE;
        }
        final float dx0 = x0 - firstX;
        final float dy0 = y0 - firstY;
        final float dx1 = x1 - secondX;
        final float dy1 = y1 - secondY;
        final float initialSeparation = (float) Math.hypot(secondX - firstX, secondY - firstY);
        final float currentSeparation = (float) Math.hypot(x1 - x0, y1 - y0);
        if (dy0 < -intentSlop || dy1 < -intentSlop
                || Math.abs(currentSeparation - initialSeparation) > intentSlop * 2
                || (Math.abs(dx0) > intentSlop && Math.abs(dx0) > Math.abs(dy0))
                || (Math.abs(dx1) > intentSlop && Math.abs(dx1) > Math.abs(dy1))) {
            state = State.REJECTED;
            return Result.NONE;
        }
        if (dy0 > triggerDistance && dy1 > triggerDistance
                && dy0 > Math.abs(dx0) * 1.5f
                && dy1 > Math.abs(dx1) * 1.5f) {
            state = State.TRANSITIONED;
            return Result.EXIT;
        }
        return Result.NONE;
    }

    public void pointerUp() {
        state = State.REJECTED;
    }

    public void cancel() {
        state = State.IDLE;
    }

    public boolean transitioned() {
        return state == State.TRANSITIONED;
    }
}
