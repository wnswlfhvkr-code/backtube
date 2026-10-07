package org.schabi.newpipe.player;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.test.platform.app.InstrumentationRegistry;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

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
