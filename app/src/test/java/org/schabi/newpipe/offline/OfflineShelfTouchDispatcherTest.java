package org.schabi.newpipe.offline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.view.MotionEvent;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Sequence policy only; actual ViewGroup input dispatch requires Android execution. */
public class OfflineShelfTouchDispatcherTest {
    @Test public void originalHandleSequenceReachesRecyclerViewIncludingDrop() {
        final OfflineShelfTouchDispatcher dispatcher = new OfflineShelfTouchDispatcher();
        final List<Integer> delivered = new ArrayList<>();
        assertFalse(dispatch(dispatcher, MotionEvent.ACTION_DOWN, false, delivered));
        assertFalse(dispatch(dispatcher, MotionEvent.ACTION_MOVE, true, delivered));
        assertFalse(dispatch(dispatcher, MotionEvent.ACTION_POINTER_DOWN, true, delivered));
        assertFalse(dispatch(dispatcher, MotionEvent.ACTION_POINTER_UP, true, delivered));
        assertFalse(dispatch(dispatcher, MotionEvent.ACTION_UP, true, delivered));
        assertEquals(List.of(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP,
                MotionEvent.ACTION_UP), delivered);
    }

    @Test public void blockedGestureStaysBlockedAfterRecoveryEndsUntilUp() {
        final OfflineShelfTouchDispatcher dispatcher = new OfflineShelfTouchDispatcher();
        final List<Integer> delivered = new ArrayList<>();
        assertTrue(dispatch(dispatcher, MotionEvent.ACTION_DOWN, true, delivered));
        assertTrue(dispatch(dispatcher, MotionEvent.ACTION_MOVE, false, delivered));
        assertTrue(dispatch(dispatcher, MotionEvent.ACTION_POINTER_DOWN, false, delivered));
        assertTrue(dispatch(dispatcher, MotionEvent.ACTION_POINTER_UP, false, delivered));
        assertTrue(dispatch(dispatcher, MotionEvent.ACTION_UP, false, delivered));
        assertTrue(delivered.isEmpty());
        assertFalse(dispatch(dispatcher, MotionEvent.ACTION_DOWN, false, delivered));
        assertFalse(dispatch(dispatcher, MotionEvent.ACTION_UP, false, delivered));
        assertEquals(List.of(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), delivered);
    }

    @Test public void syntheticCancelRunsCleanupAndReachesRecyclerViewEvenAfterBlockedDown() {
        final OfflineShelfTouchDispatcher dispatcher = new OfflineShelfTouchDispatcher();
        final AtomicInteger cancellations = new AtomicInteger();
        final List<Integer> delivered = new ArrayList<>();
        // Check cancellation even on the RED implementation, rather than fail on blocked DOWN.
        dispatch(dispatcher, MotionEvent.ACTION_DOWN, true, delivered);
        delivered.clear();
        assertFalse(dispatcher.dispatch(event(MotionEvent.ACTION_CANCEL), true,
                cancellations::incrementAndGet, touch -> {
                    assertEquals(1, cancellations.get());
                    delivered.add(touch.getActionMasked());
                    return false;
                }));
        assertEquals(List.of(MotionEvent.ACTION_CANCEL), delivered);
        assertFalse(dispatch(dispatcher, MotionEvent.ACTION_DOWN, false, delivered));
    }

    @Test public void forwardedInputRetainsRecyclerViewConsumptionResult() {
        final OfflineShelfTouchDispatcher dispatcher = new OfflineShelfTouchDispatcher();
        assertTrue(dispatcher.dispatch(event(MotionEvent.ACTION_DOWN), false,
                () -> { }, touch -> true));
    }

    private static boolean dispatch(final OfflineShelfTouchDispatcher dispatcher,
                                    final int action, final boolean recovering,
                                    final List<Integer> delivered) {
        return dispatcher.dispatch(event(action), recovering, () -> { }, touch -> {
            delivered.add(touch.getActionMasked());
            return false;
        });
    }

    private static MotionEvent event(final int action) {
        final MotionEvent event = mock(MotionEvent.class);
        when(event.getActionMasked()).thenReturn(action);
        return event;
    }
}
