package com.winlator.inputcontrols;

import org.junit.Test;
import static org.junit.Assert.*;

public class CursorVisibilityTest {
    @Test public void hiddenCursorKeepsMouseInputEnabled() {
        ControlsProfile profile = new ControlsProfile(null, 1);
        assertTrue(profile.isCursorVisible());
        profile.setHideMouseCursor(true);
        assertFalse(profile.isCursorVisible());
        assertFalse(profile.isDisableMouseInput());
        profile.setHideMouseCursor(false);
        assertTrue(profile.isCursorVisible());
    }

    @Test public void disablingMouseInputStillHidesTheCursor() {
        ControlsProfile profile = new ControlsProfile(null, 1);
        profile.setDisableMouseInput(true);
        assertFalse(profile.isCursorVisible());
        profile.setHideMouseCursor(true);
        profile.setDisableMouseInput(false);
        assertFalse(profile.isCursorVisible());
    }
}
