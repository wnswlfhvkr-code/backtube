package org.schabi.newpipe.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RootBackExitTest {
    @Test
    public void firstPressArmsAndSecondPressBeforeOneSecondExits() {
        final RootBackExit exit = new RootBackExit();
        assertFalse(exit.press(100));
        assertTrue(exit.press(1099));
        assertFalse(exit.press(1100));
    }

    @Test
    public void exactDeadlineRequiresAnotherFirstPress() {
        final RootBackExit exit = new RootBackExit();
        assertFalse(exit.press(100));
        assertFalse(exit.press(1100));
        assertTrue(exit.press(1101));
    }

    @Test
    public void navigationAndLifecycleResetArmedWindow() {
        final RootBackExit exit = new RootBackExit();
        exit.press(100);
        exit.reset();
        assertFalse(exit.press(200));
    }

    @Test
    public void oldPromptDismissalCannotDisarmNewPrompt() {
        final RootBackExit exit = new RootBackExit();
        exit.press(100);
        final long oldPrompt = exit.generation();
        exit.press(1100);
        exit.dismiss(oldPrompt);
        assertTrue(exit.press(1101));
    }

    @Test
    public void matchingPromptDismissalDisarmsExit() {
        final RootBackExit exit = new RootBackExit();
        exit.press(100);
        exit.dismiss(exit.generation());
        assertFalse(exit.press(200));
    }
}
