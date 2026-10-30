package org.schabi.newpipe.player.gesture;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FullscreenSwipeDetectorTest {
    private final FullscreenSwipeDetector detector = new FullscreenSwipeDetector(12f, 72f);

    @Test
    public void inlineUpwardSwipeEntersOnce() {
        detector.down(100f, 200f, false);
        assertEquals(FullscreenSwipeDetector.Result.NONE, detector.moveOne(101f, 160f));
        assertEquals(FullscreenSwipeDetector.Result.ENTER, detector.moveOne(102f, 115f));
        assertEquals(FullscreenSwipeDetector.Result.NONE, detector.moveOne(102f, 50f));
    }

    @Test
    public void inlineDownwardAndHorizontalSwipeLeavePageScrollAvailable() {
        detector.down(100f, 100f, false);
        assertEquals(FullscreenSwipeDetector.Result.GIVE_TO_PARENT,
                detector.moveOne(100f, 116f));
        assertEquals(FullscreenSwipeDetector.Result.NONE, detector.moveOne(100f, -100f));

        detector.down(100f, 100f, false);
        assertEquals(FullscreenSwipeDetector.Result.GIVE_TO_PARENT,
                detector.moveOne(116f, 100f));
    }

    @Test
    public void twoFingerDownwardSwipeExitsOnce() {
        detector.down(80f, 100f, true);
        detector.secondDown(80f, 100f, 180f, 100f);
        assertEquals(FullscreenSwipeDetector.Result.NONE,
                detector.moveTwo(82f, 140f, 182f, 140f));
        assertEquals(FullscreenSwipeDetector.Result.EXIT,
                detector.moveTwo(82f, 180f, 181f, 178f));
        assertEquals(FullscreenSwipeDetector.Result.NONE,
                detector.moveTwo(82f, 220f, 181f, 220f));
    }

    @Test
    public void singleFingerDownwardDoesNotExitFullscreen() {
        detector.down(100f, 100f, true);
        assertEquals(FullscreenSwipeDetector.Result.NONE, detector.moveOne(100f, 200f));
    }

    @Test
    public void pinchAndHorizontalMotionDoNotExit() {
        detector.down(80f, 100f, true);
        detector.secondDown(80f, 100f, 180f, 100f);
        assertEquals(FullscreenSwipeDetector.Result.NONE,
                detector.moveTwo(50f, 180f, 210f, 180f));
        assertEquals(FullscreenSwipeDetector.Result.NONE,
                detector.moveTwo(50f, 250f, 210f, 250f));

        detector.down(80f, 100f, true);
        detector.secondDown(80f, 100f, 180f, 100f);
        assertEquals(FullscreenSwipeDetector.Result.NONE,
                detector.moveTwo(170f, 180f, 270f, 180f));
    }

    @Test
    public void secondFingerAfterExistingSwipeDoesNotExit() {
        detector.down(80f, 100f, true);
        detector.moveOne(80f, 120f);
        detector.secondDown(80f, 120f, 180f, 120f);
        assertEquals(FullscreenSwipeDetector.Result.NONE,
                detector.moveTwo(80f, 220f, 180f, 220f));
    }

    @Test
    public void cancelAndPointerLiftDoNotTriggerLater() {
        detector.down(80f, 100f, true);
        detector.secondDown(80f, 100f, 180f, 100f);
        detector.cancel();
        assertEquals(FullscreenSwipeDetector.Result.NONE,
                detector.moveTwo(80f, 200f, 180f, 200f));

        detector.down(80f, 100f, true);
        detector.secondDown(80f, 100f, 180f, 100f);
        detector.pointerUp();
        assertEquals(FullscreenSwipeDetector.Result.NONE,
                detector.moveTwo(80f, 200f, 180f, 200f));
    }
}
