package org.schabi.newpipe.offline;

import android.graphics.Canvas;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import java.util.function.Predicate;

/** Prevent recovery re-grab while preserving the original handle gesture and cancellation. */
final class OfflineShelfTouchDispatcher {
    private boolean blockedGesture;
    private boolean recoveryAwaitingDraw;

    ItemTouchHelper createHelper(final ItemTouchHelper.Callback callback) {
        return new ItemTouchHelper(callback) {
            @Override public void onDrawOver(@NonNull final Canvas canvas,
                                             @NonNull final RecyclerView rows,
                                             @NonNull final RecyclerView.State state) {
                super.onDrawOver(canvas, rows, state);
                // Ended recoveries remain hittable until the library's draw-over cleanup.
                recoveryAwaitingDraw = false;
            }

            @Override public void attachToRecyclerView(@Nullable final RecyclerView rows) {
                super.attachToRecyclerView(rows);
                if (rows == null) {
                    // Detach cancels and clears recoveries synchronously, without another draw.
                    recoveryAwaitingDraw = false;
                    blockedGesture = false;
                }
            }
        };
    }

    void onRecoveryFinished() {
        recoveryAwaitingDraw = true;
    }

    boolean dispatch(final MotionEvent event, final boolean recovering,
                     final Runnable cancelDrag, final Predicate<MotionEvent> forward) {
        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_CANCEL) {
            blockedGesture = false;
            cancelDrag.run();
        } else if (action == MotionEvent.ACTION_DOWN) {
            // ItemTouchHelper can select a recovering holder directly, bypassing the handle.
            blockedGesture = recovering || recoveryAwaitingDraw;
        }
        final boolean consume = blockedGesture;
        if (action == MotionEvent.ACTION_UP) {
            // Recovery may finish before UP; never deliver only the tail of a blocked gesture.
            blockedGesture = false;
        }
        return consume || forward.test(event);
    }
}
