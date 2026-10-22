package org.schabi.newpipe.player.gesture;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DownwardDragTest {
    @Test
    public void buttonTapAndSmallMotionRemainClicks() {
        final DownwardDrag drag = new DownwardDrag(8);
        drag.start(100, 100);
        assertFalse(drag.move(102, 106));
    }

    @Test
    public void downwardMotionClaimsGestureOnlyAfterSlop() {
        final DownwardDrag drag = new DownwardDrag(8);
        drag.start(100, 100);
        assertTrue(drag.move(101, 110));
        assertTrue(drag.move(100, 125));
    }

    @Test
    public void horizontalSeekCannotBecomeCollapseLater() {
        final DownwardDrag drag = new DownwardDrag(8);
        drag.start(100, 100);
        assertFalse(drag.move(120, 102));
        assertFalse(drag.move(121, 160));
    }

    @Test
    public void upwardScrollCannotBecomeCollapseLater() {
        final DownwardDrag drag = new DownwardDrag(8);
        drag.start(100, 100);
        assertFalse(drag.move(100, 80));
        assertFalse(drag.move(100, 150));
    }
}
