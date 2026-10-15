package org.schabi.newpipe.offline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Actual RecyclerView 1.4.0 selection/recovery with Android input/view/animation mocks.
 * Exercises the production dispatcher, not Activity/ViewGroup dispatch or a real device.
 */
public class ItemTouchHelperRecoveryBoundaryTest {
    @Test
    public void realLibraryRegrabsRecoveringRowOutsideHandleWithoutLongPress()
            throws Exception {
        try (Boundary fixture = new Boundary()) {
            fixture.beginRecovery(false);
            assertTrue(fixture.forward(event(MotionEvent.ACTION_DOWN, 80)));
            assertSame(fixture.holder, fixture.selected());
            assertEquals(2, fixture.callback.dragSelections);
            assertEquals(0, fixture.recoveryCount());
            assertEquals(0, fixture.callback.clears);
        }
    }

    @Test
    public void productionDispatcherKeepsRecoveringRowUnselected() throws Exception {
        // Mocked item coordinates, not inflated widget hitboxes: title region and menu region.
        for (final float x : new float[]{80f, 310f}) {
            try (Boundary fixture = new Boundary()) {
                fixture.beginRecovery(true);
                final int forwarded = fixture.forwarded;
                // True means consumed; real helper state distinguishes guarding from regrab.
                assertTrue(fixture.dispatch(MotionEvent.ACTION_DOWN, x, true));
                assertNull(fixture.selected());
                assertEquals(1, fixture.callback.dragSelections);
                assertEquals(1, fixture.recoveryCount());
                assertEquals(forwarded, fixture.forwarded);
            }
        }
    }

    @Test
    public void blockedSequenceStaysConsumedAfterRecoveryEndsThenHandleCanStart()
            throws Exception {
        try (Boundary fixture = new Boundary()) {
            fixture.beginRecovery(true);
            fixture.dispatch(MotionEvent.ACTION_DOWN, 80, true);
            assertNull(fixture.selected());
            fixture.finishRecovery();
            assertEquals(1, fixture.callback.clears);
            assertEquals(0, fixture.recoveryCount());
            final int forwarded = fixture.forwarded;

            assertTrue(fixture.dispatch(MotionEvent.ACTION_MOVE, 80, false));
            assertTrue(fixture.dispatch(MotionEvent.ACTION_UP, 80, false));
            assertEquals(forwarded, fixture.forwarded);
            assertNull(fixture.selected());
            assertEquals(1, fixture.callback.dragSelections);

            // A genuinely fresh handle sequence must be forwarded after the blocked UP.
            assertFalse(fixture.dispatch(MotionEvent.ACTION_DOWN, 260, false));
            fixture.helper.startDrag(fixture.holder);
            assertSame(fixture.holder, fixture.selected());
            assertEquals(2, fixture.callback.dragSelections);
        }
    }

    @Test
    public void endedRecoveryStillRejectsFreshDownBeforeDrawCleanup() throws Exception {
        try (Boundary fixture = new Boundary()) {
            fixture.beginRecovery(true);
            fixture.finishAnimation();
            assertEquals(1, fixture.callback.clears);
            assertEquals(1, fixture.recoveryCount());
            // clearView has released Activity's holder; ITH retains the ended animation.
            assertTrue(fixture.dispatch(MotionEvent.ACTION_DOWN, 80, false));
            assertNull(fixture.selected());
            assertEquals(1, fixture.callback.dragSelections);
            assertFalse(fixture.dispatch(MotionEvent.ACTION_CANCEL, 0, false));
            assertEquals(1, fixture.canceled);
            // CANCEL resets this pointer sequence, not ITH's pending draw cleanup.
            assertTrue(fixture.dispatch(MotionEvent.ACTION_DOWN, 310, false));
            assertNull(fixture.selected());
            fixture.drawCleanup();
            assertEquals(0, fixture.recoveryCount());
            // Even draw cleanup must not forward the tail of this blocked gesture.
            assertTrue(fixture.dispatch(MotionEvent.ACTION_UP, 80, false));
            assertFalse(fixture.dispatch(MotionEvent.ACTION_DOWN, 260, false));
            fixture.helper.startDrag(fixture.holder);
            assertSame(fixture.holder, fixture.selected());
        }
    }

    @Test
    public void detachClearsEndedRecoveryBarrierBeforeAResumedHandleSequence() throws Exception {
        try (Boundary fixture = new Boundary()) {
            fixture.beginRecovery(true);
            fixture.finishAnimation();
            assertEquals(1, fixture.recoveryCount());
            fixture.helper.attachToRecyclerView(null);
            assertEquals(0, fixture.recoveryCount());
            fixture.helper.attachToRecyclerView(fixture.rows);
            assertFalse(fixture.dispatch(MotionEvent.ACTION_DOWN, 260, false));
            fixture.helper.startDrag(fixture.holder);
            assertSame(fixture.holder, fixture.selected());
            assertEquals(2, fixture.callback.dragSelections);
        }
    }

    @Test
    public void cancelIsForwardedInvokesCallbackAndClearsBlockedSequence() throws Exception {
        try (Boundary fixture = new Boundary()) {
            fixture.beginRecovery(true);
            fixture.dispatch(MotionEvent.ACTION_DOWN, 80, true);
            assertNull(fixture.selected());
            final int forwarded = fixture.forwarded;

            // Lifecycle cancellation must reach the real helper even during a blocked sequence.
            assertFalse(fixture.dispatch(MotionEvent.ACTION_CANCEL, 0, true));
            assertEquals(1, fixture.canceled);
            assertEquals(forwarded + 1, fixture.forwarded);
            assertNull(fixture.selected());
            fixture.finishRecovery();

            assertFalse(fixture.dispatch(MotionEvent.ACTION_DOWN, 260, false));
            fixture.helper.startDrag(fixture.holder);
            assertSame(fixture.holder, fixture.selected());
            assertEquals(2, fixture.callback.dragSelections);
            final int activeForwarded = fixture.forwarded;
            fixture.dispatch(MotionEvent.ACTION_CANCEL, 0, true);
            assertEquals(2, fixture.canceled);
            assertEquals(activeForwarded + 1, fixture.forwarded);
            assertNull(fixture.selected());
        }
    }

    private static MotionEvent event(final int action, final float x) {
        final MotionEvent event = mock(MotionEvent.class);
        when(event.getAction()).thenReturn(action);
        when(event.getActionMasked()).thenReturn(action);
        when(event.getPointerId(0)).thenReturn(0);
        when(event.getX()).thenReturn(x);
        when(event.getX(0)).thenReturn(x);
        when(event.getY()).thenReturn(32f);
        when(event.getY(0)).thenReturn(32f);
        return event;
    }

    private static Object field(final Object target, final String name) throws Exception {
        final Field field = ItemTouchHelper.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static final class Boundary implements AutoCloseable {
        private final RecyclerView rows = mock(RecyclerView.class);
        private final View item = mock(View.class);
        private final RecyclerView.ViewHolder holder = new RecyclerView.ViewHolder(item) { };
        private final OfflineShelfTouchDispatcher dispatcher = new OfflineShelfTouchDispatcher();
        private final RecordingCallback callback = new RecordingCallback(
                dispatcher::onRecoveryFinished);
        private final ItemTouchHelper helper = dispatcher.createHelper(callback);
        private final List<Frame> frames = new ArrayList<>();
        private final MockedStatic<ViewConfiguration> configurations;
        private final MockedStatic<VelocityTracker> velocities;
        private final MockedStatic<ValueAnimator> animations;
        private final MockedConstruction<GestureDetector> gestures;
        private RecyclerView.OnItemTouchListener input;
        private int forwarded;
        private int canceled;

        Boundary() {
            final Context context = mock(Context.class);
            final ViewConfiguration configuration = mock(ViewConfiguration.class);
            final VelocityTracker velocity = mock(VelocityTracker.class);
            when(rows.getContext()).thenReturn(context);
            when(rows.getResources()).thenReturn(mock(Resources.class));
            when(rows.getLayoutManager()).thenReturn(mock(RecyclerView.LayoutManager.class));
            when(rows.findChildViewUnder(anyFloat(), anyFloat())).thenReturn(item);
            when(item.getParent()).thenReturn(rows);
            when(item.getWidth()).thenReturn(320);
            when(item.getHeight()).thenReturn(64);
            doAnswer(invocation -> {
                input = invocation.getArgument(0);
                return null;
            }).when(rows).addOnItemTouchListener(any());

            configurations = mockStatic(ViewConfiguration.class);
            velocities = mockStatic(VelocityTracker.class);
            animations = mockStatic(ValueAnimator.class);
            gestures = mockConstruction(GestureDetector.class);
            configurations.when(() -> ViewConfiguration.get(context)).thenReturn(configuration);
            velocities.when(VelocityTracker::obtain).thenReturn(velocity);
            animations.when(() -> ValueAnimator.ofFloat(0f, 1f)).thenAnswer(invocation -> {
                final Frame frame = new Frame();
                frames.add(frame);
                return frame.animator;
            });
            helper.attachToRecyclerView(rows);
        }

        void beginRecovery(final boolean throughProduction) throws Exception {
            assertFalse(callback.isLongPressDragEnabled());
            if (throughProduction) {
                assertFalse(dispatch(MotionEvent.ACTION_DOWN, 260, false));
            } else {
                assertFalse(forward(event(MotionEvent.ACTION_DOWN, 260)));
            }
            // Models the existing handle's real startDrag call after its original DOWN.
            helper.startDrag(holder);
            assertSame(holder, selected());
            assertEquals(1, callback.dragSelections);
            if (throughProduction) {
                assertFalse(dispatch(MotionEvent.ACTION_UP, 260, true));
            } else {
                assertFalse(forward(event(MotionEvent.ACTION_UP, 260)));
            }
            assertNull(selected());
            assertEquals(1, recoveryCount());
            assertEquals(0, callback.clears);
        }

        boolean dispatch(final int action, final float x, final boolean recovering) {
            return dispatcher.dispatch(event(action, x), recovering,
                    () -> canceled++, this::forward);
        }

        boolean forward(final MotionEvent event) {
            forwarded++;
            return input.onInterceptTouchEvent(rows, event);
        }

        Object selected() throws Exception {
            return field(helper, "mSelected");
        }

        int recoveryCount() throws Exception {
            return ((List<?>) field(helper, "mRecoverAnimations")).size();
        }

        void finishRecovery() {
            finishAnimation();
            drawCleanup();
        }

        void finishAnimation() {
            frames.get(frames.size() - 1).finish();
        }

        void drawCleanup() {
            // Execute real helper draw-over cleanup; Canvas is only an Android boundary mock.
            helper.onDrawOver(mock(Canvas.class), rows, mock(RecyclerView.State.class));
        }

        @Override
        public void close() {
            try {
                helper.attachToRecyclerView(null);
            } finally {
                gestures.close();
                animations.close();
                velocities.close();
                configurations.close();
            }
        }
    }

    private static final class Frame {
        private final ValueAnimator animator = mock(ValueAnimator.class);
        private final List<Animator.AnimatorListener> listeners = new ArrayList<>();
        private boolean ended;

        Frame() {
            doAnswer(invocation -> {
                listeners.add(invocation.getArgument(0));
                return null;
            }).when(animator).addListener(any());
            // Scheduling is frozen; real RecoverAnimation still handles lifecycle callbacks.
            doAnswer(invocation -> {
                if (!ended) {
                    ended = true;
                    for (final Animator.AnimatorListener listener : new ArrayList<>(listeners)) {
                        listener.onAnimationCancel(animator);
                        listener.onAnimationEnd(animator);
                    }
                }
                return null;
            }).when(animator).cancel();
        }

        void finish() {
            if (!ended) {
                ended = true;
                for (final Animator.AnimatorListener listener : new ArrayList<>(listeners)) {
                    listener.onAnimationEnd(animator);
                }
            }
        }
    }

    private static final class RecordingCallback extends ItemTouchHelper.Callback {
        private int dragSelections;
        private int clears;
        private final Runnable recoveryFinished;

        RecordingCallback(final Runnable recoveryFinished) {
            this.recoveryFinished = recoveryFinished;
        }

        @Override
        public int getMovementFlags(final RecyclerView rows,
                                    final RecyclerView.ViewHolder holder) {
            return makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0);
        }

        @Override
        public boolean isLongPressDragEnabled() {
            return false;
        }

        @Override
        public boolean isItemViewSwipeEnabled() {
            return false;
        }

        @Override
        public boolean onMove(final RecyclerView rows, final RecyclerView.ViewHolder from,
                              final RecyclerView.ViewHolder to) {
            return false;
        }

        @Override
        public void onSwiped(final RecyclerView.ViewHolder holder, final int direction) {
            // Swipe is deliberately disabled, matching the shelf callback.
        }

        @Override
        public void onSelectedChanged(final RecyclerView.ViewHolder holder,
                                      final int actionState) {
            if (holder != null && actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                dragSelections++;
            }
        }

        @Override
        public void clearView(final RecyclerView rows, final RecyclerView.ViewHolder holder) {
            clears++;
            // Same lifecycle notification as the Activity's matching-holder clearView.
            recoveryFinished.run();
        }
    }
}
