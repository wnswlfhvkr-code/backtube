package org.schabi.newpipe.player.gesture;

/** Locks the initial drag direction, leaving taps and horizontal controls with their child. */
public final class DownwardDrag {
    private final int touchSlop;
    private float startX;
    private float startY;
    private int direction;

    public DownwardDrag(final int touchSlop) {
        this.touchSlop = touchSlop;
    }

    public void start(final float x, final float y) {
        startX = x;
        startY = y;
        direction = 0;
    }

    public boolean move(final float x, final float y) {
        if (direction == 0) {
            final float dx = Math.abs(x - startX);
            final float dy = y - startY;
            if (Math.max(dx, Math.abs(dy)) > touchSlop) {
                direction = dy > dx ? 1 : -1;
            }
        }
        return direction == 1;
    }
}
