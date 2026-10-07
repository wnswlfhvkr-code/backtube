package org.schabi.newpipe.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.test.platform.app.InstrumentationRegistry;

import com.google.android.material.bottomsheet.BottomSheetBehavior;

import org.schabi.newpipe.MainActivity;
import org.schabi.newpipe.R;
import org.schabi.newpipe.fragments.detail.VideoDetailFragment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** UI operations shared by local playback regression scenarios. */
final class PlaybackTestUi {
    private PlaybackTestUi() {
    }

    static void clickAccessibilityText(final String text) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        SystemClock.sleep(300);
        for (int attempt = 0; attempt < 12; attempt++) {
            final AccessibilityNodeInfo visible = findNodeByText(text);
            if (visible != null) {
                visible.recycle();
                break;
            }
            final AccessibilityNodeInfo root = InstrumentationRegistry.getInstrumentation()
                    .getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                scrollForward(root);
                root.recycle();
            }
            SystemClock.sleep(300);
        }
        // Wait for native ListView scrolling to settle before reading touch coordinates.
        SystemClock.sleep(800);
        final AccessibilityNodeInfo node = findNodeByText(text);
        assertTrue("missing accessibility text: " + text, node != null);
        final Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        node.recycle();
        assertFalse("empty accessibility bounds: " + text, bounds.isEmpty());
        final long now = SystemClock.uptimeMillis();
        final MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN,
                bounds.centerX(), bounds.centerY(), 0);
        final MotionEvent up = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP,
                bounds.centerX(), bounds.centerY(), 0);
        try {
            InstrumentationRegistry.getInstrumentation().sendPointerSync(down);
            InstrumentationRegistry.getInstrumentation().sendPointerSync(up);
        } finally {
            down.recycle();
            up.recycle();
        }
    }

    static AccessibilityNodeInfo findNodeByText(final String text) {
        final AccessibilityNodeInfo root = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().getRootInActiveWindow();
        if (root == null) {
            return null;
        }
        final List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        root.recycle();
        AccessibilityNodeInfo match = null;
        for (final AccessibilityNodeInfo node : nodes) {
            if (match == null && node.isVisibleToUser() && fullyVisibleInList(node)
                    && text.equalsIgnoreCase(String.valueOf(node.getText()))) {
                match = node;
            } else {
                node.recycle();
            }
        }
        return match;
    }

    private static boolean fullyVisibleInList(final AccessibilityNodeInfo node) {
        final Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        AccessibilityNodeInfo parent = node.getParent();
        while (parent != null) {
            if (parent.isScrollable()) {
                final Rect viewport = new Rect();
                parent.getBoundsInScreen(viewport);
                parent.recycle();
                return viewport.contains(bounds);
            }
            final AccessibilityNodeInfo next = parent.getParent();
            parent.recycle();
            parent = next;
        }
        return true;
    }

    private static boolean scrollForward(final AccessibilityNodeInfo node) {
        if (node.isScrollable()
                && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            return true;
        }
        for (int index = 0; index < node.getChildCount(); index++) {
            final AccessibilityNodeInfo child = node.getChild(index);
            if (child != null) {
                final boolean scrolled = scrollForward(child);
                child.recycle();
                if (scrolled) {
                    return true;
                }
            }
        }
        return false;
    }

    static void exerciseVisibleDetailTimer(final Context context, final Player player,
                                          final Supplier<MainActivity> activity)
            throws IOException {
        tapDetailTimer(activity);
        clickAccessibilityText(context.getString(R.string.personal_sleep_timer_minutes, 15));
        awaitUi(() -> player.getSleepTimerRemainingMillis() > 14 * 60_000L,
                "15 minute selection did not set the timer");
        assertEquals(context.getString(R.string.personal_sleep_timer_remaining, 15),
                readOnMain(() -> ((TextView) activity.get().findViewById(
                        R.id.detail_controls_sleep_timer)).getText().toString()));
        assertFalse("setting a timer resumed paused playback", readOnMain(player::isPlaying));
        captureDetailTimer(context, activity, "backtube-detail-timer-generated.png");
        for (final boolean playing : new boolean[]{false, true}) {
            runOnMain(() -> {
                if (playing) {
                    player.play();
                } else {
                    player.pause();
                }
            });
            awaitUi(() -> player.isPlaying() == playing, "playback state did not settle");
            tapDetailTimer(activity);
            clickAccessibilityText(context.getString(R.string.personal_sleep_timer_custom));
            clickAccessibilityText(context.getString(R.string.cancel));
            assertEquals("dialog cancellation changed playback", playing,
                    (boolean) readOnMain(player::isPlaying));
            assertTrue(readOnMain(() -> player.getSleepTimerRemainingMillis() > 14 * 60_000L));
        }
        tapDetailTimer(activity);
        clickAccessibilityText(context.getString(R.string.personal_sleep_timer_extend));
        awaitUi(() -> player.getSleepTimerRemainingMillis() > 29 * 60_000L,
                "extend selection did not add 15 minutes");
        runOnMain(player::pause);
        tapDetailTimer(activity);
        runOnMain(() -> activity.get().setRequestedOrientation(
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
        awaitUi(() -> activity.get() != null && activity.get().getResources()
                .getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE,
                "detail screen did not rotate");
        revealDetailTimer(activity);
        assertNull("old timer dialog survived rotation", findNodeByText(
                context.getString(R.string.personal_sleep_timer_minutes, 15)));
        assertTrue(readOnMain(() -> player.getSleepTimerRemainingMillis() > 29 * 60_000L));
        captureDetailTimer(context, activity, "backtube-detail-timer-generated-landscape.png");
        tapDetailTimer(activity);
        clickAccessibilityText(context.getString(R.string.personal_sleep_timer_cancel_timer));
        awaitUi(() -> player.getSleepTimerRemainingMillis() == 0,
                "cancel selection did not stop the timer");
        assertFalse("cancelling timer resumed playback", readOnMain(player::isPlaying));
    }

    private static void tapDetailTimer(final Supplier<MainActivity> activity) {
        final Rect bounds = revealDetailTimer(activity);
        final long now = SystemClock.uptimeMillis();
        final MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN,
                bounds.centerX(), bounds.centerY(), 0);
        final MotionEvent up = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP,
                bounds.centerX(), bounds.centerY(), 0);
        try {
            InstrumentationRegistry.getInstrumentation().sendPointerSync(down);
            InstrumentationRegistry.getInstrumentation().sendPointerSync(up);
        } finally {
            down.recycle();
            up.recycle();
        }
    }

    private static void captureDetailTimer(final Context context,
                                          final Supplier<MainActivity> activity,
                                          final String name) throws IOException {
        revealDetailTimer(activity);
        captureScreen(context, name);
        assertTrue("timer left the viewport during screenshot capture",
                readOnMain(() -> detailTimerBounds(activity.get(), false)) != null);
    }

    private static Rect revealDetailTimer(final Supplier<MainActivity> activity) {
        awaitUi(() -> detailTimerBounds(activity.get(), true) != null,
                "detail timer was not fully visible inside the viewport");
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        final Rect settled = readOnMain(() -> detailTimerBounds(activity.get(), false));
        assertTrue("timer moved out of view before interaction", settled != null);
        return settled;
    }

    private static Rect detailTimerBounds(final MainActivity activity, final boolean scroll) {
        if (activity == null) {
            return null;
        }
        final androidx.fragment.app.Fragment fragment = activity.getSupportFragmentManager()
                .findFragmentById(R.id.fragment_player_holder);
        if (!(fragment instanceof VideoDetailFragment) || !fragment.isResumed()) {
            return null;
        }
        assertFalse("generated detail fixture displayed an extraction error",
                ((VideoDetailFragment) fragment).isErrorPanelVisible());
        final View root = fragment.getView();
        if (root == null || BottomSheetBehavior.from(activity.findViewById(
                R.id.fragment_player_holder)).getState() != BottomSheetBehavior.STATE_EXPANDED) {
            return null;
        }
        assertFalse("detail error panel is visible", root.findViewById(R.id.error_panel).isShown());
        final View timer = root.findViewById(R.id.detail_controls_sleep_timer);
        if (!root.findViewById(R.id.detail_content_root_hiding).isShown()
                || root.findViewById(R.id.loading_progress_bar).isShown()
                || !timer.isShown() || !timer.isEnabled()
                || timer.getWidth() == 0 || timer.getHeight() == 0) {
            return null;
        }
        if (scroll) {
            timer.requestRectangleOnScreen(
                    new Rect(0, 0, timer.getWidth(), timer.getHeight()), true);
        }
        final Rect visible = new Rect();
        final Rect viewport = new Rect();
        if (!timer.getGlobalVisibleRect(visible)
                || visible.width() != timer.getWidth() || visible.height() != timer.getHeight()
                || !root.findViewById(R.id.detail_main_content).getGlobalVisibleRect(viewport)
                || !viewport.contains(visible)) {
            return null;
        }
        final int[] position = new int[2];
        timer.getLocationOnScreen(position);
        final Rect screenBounds = new Rect(position[0], position[1],
                position[0] + timer.getWidth(), position[1] + timer.getHeight());
        final Rect window = new Rect();
        activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(window);
        return window.contains(screenBounds) ? screenBounds : null;
    }

    private static void awaitUi(final BooleanSupplier condition, final String message) {
        final long deadline = SystemClock.uptimeMillis() + 8000;
        do {
            if (readOnMain(condition::getAsBoolean)) {
                return;
            }
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        assertTrue(message, readOnMain(condition::getAsBoolean));
    }

    private static void runOnMain(final Runnable action) {
        readOnMain(() -> {
            action.run();
            return null;
        });
    }

    private static <T> T readOnMain(final Supplier<T> action) {
        final FutureTask<T> task = new FutureTask<>(action::get);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(task);
        try {
            return task.get();
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        } catch (final ExecutionException exception) {
            throw new AssertionError(exception.getCause());
        }
    }

    static void captureScreen(final Context context, final String name) throws IOException {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        // Surface rotation/dialog animations can outlive the activity's idle queue.
        SystemClock.sleep(800);
        final Bitmap screenshot = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().takeScreenshot();
        assertTrue("could not capture screen", screenshot != null);
        final File externalFiles = context.getExternalFilesDir(null);
        assertTrue("external files directory was unavailable", externalFiles != null);
        final File directory = new File(externalFiles, "personal-playback-test");
        assertTrue("could not create screenshot directory",
                directory.exists() || directory.mkdirs());
        final File output = new File(directory, name);
        try (FileOutputStream stream = new FileOutputStream(output)) {
            assertTrue("could not write screenshot", screenshot.compress(
                    Bitmap.CompressFormat.PNG, 100, stream));
        } finally {
            screenshot.recycle();
        }
        assertTrue("screenshot was empty", output.length() > 0);
    }

}
