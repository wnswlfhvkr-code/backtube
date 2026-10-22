package org.schabi.newpipe.player.gesture;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/** Handles only touches that begin on the current-track row, leaving sibling controls alone. */
public final class SwipeUpToOpenListener implements View.OnTouchListener {
    private final Runnable openPlayer;
    private final float minimumDistance;
    private final int touchSlop;
    private float startX;
    private float startY;
    private boolean tracking;
    private boolean moved;

    public SwipeUpToOpenListener(final View view, final Runnable openPlayer) {
        this.openPlayer = openPlayer;
        touchSlop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
        minimumDistance = Math.max(touchSlop * 2,
                32 * view.getResources().getDisplayMetrics().density);
    }

    @Override
    public boolean onTouch(final View view, final MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startX = event.getX();
                startY = event.getY();
                tracking = true;
                moved = false;
                view.setPressed(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(event.getX() - startX) > touchSlop
                        || Math.abs(event.getY() - startY) > touchSlop) {
                    moved = true;
                    view.setPressed(false);
                }
                return true;
            case MotionEvent.ACTION_UP:
                view.setPressed(false);
                if (tracking) {
                    tracking = false;
                    final float upward = startY - event.getY();
                    final float sideways = Math.abs(event.getX() - startX);
                    if (upward >= minimumDistance && upward > sideways) {
                        openPlayer.run();
                    } else if (!moved && sideways <= touchSlop
                            && Math.abs(upward) <= touchSlop) {
                        view.performClick();
                    }
                }
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_CANCEL:
                tracking = false;
                view.setPressed(false);
                return true;
            default:
                return true;
        }
    }
}
