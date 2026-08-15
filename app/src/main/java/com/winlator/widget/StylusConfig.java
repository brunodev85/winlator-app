package com.winlator.widget;

import com.winlator.core.WineRegistryEditor;
import java.io.File;

public final class StylusConfig {
    public static final String REGISTRY_KEY = "Software\\Winlator\\Stylus";

    public enum Mode {
        DIRECT,
        GESTURE;

        private static Mode fromRegistryValue(int value) {
            return value == 1 ? GESTURE : DIRECT;
        }
    }

    public final boolean enabled;
    public final Mode mode;
    public final boolean hoverMove;
    public final int dragThreshold;
    public final boolean keyboardPanEnabled;
    public final int keyboardPanEdgeSize;
    public final int keyboardPanSpeed;
    public final boolean barrelButtonRightClick;

    public StylusConfig(boolean enabled, Mode mode, boolean hoverMove, int dragThreshold,
                        boolean keyboardPanEnabled, int keyboardPanEdgeSize, int keyboardPanSpeed, boolean barrelButtonRightClick) {
        this.enabled = enabled;
        this.mode = mode;
        this.hoverMove = hoverMove;
        this.dragThreshold = dragThreshold;
        this.keyboardPanEnabled = keyboardPanEnabled;
        this.keyboardPanEdgeSize = keyboardPanEdgeSize;
        this.keyboardPanSpeed = keyboardPanSpeed;
        this.barrelButtonRightClick = barrelButtonRightClick;
    }

    public static StylusConfig defaults() {
        return new StylusConfig(true, Mode.DIRECT, true, 4, true, 120, 300, true);
    }

    public static StylusConfig disabled() {
        return new StylusConfig(false, Mode.DIRECT, false, 4, false, 120, 300, false);
    }

    public static StylusConfig load(File userRegFile) {
        StylusConfig defaults = defaults();

        try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {
            boolean enabled                = registryEditor.getDwordValue(REGISTRY_KEY, "Enabled", defaults.enabled ? 1 : 0) != 0;
            Mode    mode                   = Mode.fromRegistryValue(registryEditor.getDwordValue(REGISTRY_KEY, "Mode", defaults.mode == Mode.GESTURE ? 1 : 0));
            boolean hoverMove              = registryEditor.getDwordValue(REGISTRY_KEY, "HoverMove", defaults.hoverMove ? 1 : 0) != 0;
            int     dragThreshold          = registryEditor.getDwordValue(REGISTRY_KEY, "DragThreshold", defaults.dragThreshold);
            dragThreshold       = Math.max(0, Math.min(128, dragThreshold));
            boolean keyboardPanEnabled     = registryEditor.getDwordValue(REGISTRY_KEY, "KeyboardPanEnabled", defaults.keyboardPanEnabled ? 1 : 0) != 0;
            int     keyboardPanEdgeSize    = registryEditor.getDwordValue(REGISTRY_KEY, "KeyboardPanEdgeSize", defaults.keyboardPanEdgeSize);
            int     keyboardPanSpeed       = registryEditor.getDwordValue(REGISTRY_KEY, "KeyboardPanSpeed", defaults.keyboardPanSpeed);
            keyboardPanEdgeSize = Math.max(16, Math.min(1000, keyboardPanEdgeSize));
            keyboardPanSpeed    = Math.max(10, Math.min(2000, keyboardPanSpeed));
            boolean barrelButtonRightClick = registryEditor.getDwordValue(REGISTRY_KEY, "BarrelButtonRightClick", defaults.barrelButtonRightClick ? 1 : 0) != 0;

            return new StylusConfig(enabled, mode, hoverMove, dragThreshold, keyboardPanEnabled, keyboardPanEdgeSize, keyboardPanSpeed, barrelButtonRightClick);
        }
        catch (RuntimeException e) {
            return defaults;
        }
    }
}
