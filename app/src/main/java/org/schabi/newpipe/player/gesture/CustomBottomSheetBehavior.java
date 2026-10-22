package org.schabi.newpipe.player.gesture;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.coordinatorlayout.widget.CoordinatorLayout;

import com.google.android.material.bottomsheet.BottomSheetBehavior;

import org.schabi.newpipe.R;

import java.util.List;

public class CustomBottomSheetBehavior extends BottomSheetBehavior<FrameLayout> {

    public CustomBottomSheetBehavior(@NonNull final Context context,
                                     @Nullable final AttributeSet attrs) {
        super(context, attrs);
        downwardDrag = new DownwardDrag(ViewConfiguration.get(context).getScaledTouchSlop());
    }

    private final Rect globalRect = new Rect();
    private final DownwardDrag downwardDrag;
    private boolean startedOnPlaybackButton;
    private boolean skippingInterception = false;
    private final List<Integer> skipInterceptionOfElements = List.of(
            R.id.detail_content_root_layout, R.id.relatedItemsLayout,
            R.id.itemsListPanel, R.id.view_pager, R.id.tab_layout, R.id.bottomControls,
            R.id.playbackSeekBar);
    private final List<Integer> playbackButtons = List.of(
            R.id.playPauseButton, R.id.playPreviousButton, R.id.playNextButton);

    @Override
    public boolean onInterceptTouchEvent(@NonNull final CoordinatorLayout parent,
                                         @NonNull final FrameLayout child,
                                         @NonNull final MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            skippingInterception = false;
            startedOnPlaybackButton = false;
            downwardDrag.start(event.getX(), event.getY());
        } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL
                || event.getActionMasked() == MotionEvent.ACTION_UP) {
            final boolean protectedGesture = skippingInterception || startedOnPlaybackButton;
            skippingInterception = false;
            startedOnPlaybackButton = false;
            final boolean intercepted = super.onInterceptTouchEvent(parent, child, event);
            return !protectedGesture && intercepted;
        }

        if (startedOnPlaybackButton
                && !downwardDrag.move(event.getX(), event.getY())) {
            return false;
        }

        // Found that user still swiping, continue following
        if (skippingInterception || getState() == BottomSheetBehavior.STATE_SETTLING) {
            return false;
        }

        // The interception listens for the child view with the id "fragment_player_holder",
        // so the following two-finger gesture will be triggered only for the player view on
        // portrait and for the top controls (visible) on landscape.
        setSkipCollapsed(event.getPointerCount() == 2);
        if (event.getPointerCount() == 2) {
            return super.onInterceptTouchEvent(parent, child, event);
        }

        // Don't need to do anything if bottomSheet isn't expanded
        if (getState() == BottomSheetBehavior.STATE_EXPANDED
                && event.getAction() == MotionEvent.ACTION_DOWN) {
            for (final int element : playbackButtons) {
                final View button = child.findViewById(element);
                if (button != null && button.getGlobalVisibleRect(globalRect)
                        && globalRect.contains((int) event.getRawX(), (int) event.getRawY())) {
                    startedOnPlaybackButton = true;
                    // Prime the drag helper while leaving DOWN/taps with the button. A downward
                    // MOVE can then capture the sheet and cancel the button's click normally.
                    super.onInterceptTouchEvent(parent, child, event);
                    return false;
                }
            }
            // Without overriding scrolling will not work when user touches these elements
            for (final int element : skipInterceptionOfElements) {
                final View view = child.findViewById(element);
                if (view != null) {
                    final boolean visible = view.getGlobalVisibleRect(globalRect);
                    if (visible
                            && globalRect.contains((int) event.getRawX(), (int) event.getRawY())) {
                        // Makes bottom part of the player draggable in portrait when
                        // playbackControlRoot is hidden
                        if (element == R.id.bottomControls
                                && child.findViewById(R.id.playbackControlRoot)
                                .getVisibility() != View.VISIBLE) {
                            return super.onInterceptTouchEvent(parent, child, event);
                        }
                        skippingInterception = true;
                        return false;
                    }
                }
            }
        }

        return super.onInterceptTouchEvent(parent, child, event);
    }

}
