package com.winlator.ran;

import com.winlator.box64.Box64Preset;
import com.winlator.container.Container;
import com.winlator.container.DXWrappers;
import com.winlator.widget.FrameRating;

/**
 * RAN Online Mobile graphics / performance profiles (Phases 6–7).
 *
 * <p>Maps a simple <b>Performance / Balanced / Quality</b> choice onto the RAN Wine container's
 * real Winlator settings (virtual-desktop resolution, Box64 preset, DX wrapper) and drives the
 * in-game HUD ({@link FrameRating.Mode}: FPS overlay or the developer CPU/RAM/GPU overlay).</p>
 *
 * <p>Note: RAN's client renders its own gameplay HUD (HP/SP/EXP/mini-map); those cannot be read
 * from outside the game, so the RAN "HUD" here is the performance overlay plus the fully
 * movable/resizable on-screen controls that ARE the layout editor (Winlator's Controls editor).</p>
 */
public final class RanGraphics {
    public static final String PERFORMANCE = "PERFORMANCE";
    public static final String BALANCED = "BALANCED";
    public static final String QUALITY = "QUALITY";

    private RanGraphics() {}

    public static String[] presetLabels() {
        return new String[]{"Performance", "Balanced", "Quality"};
    }

    public static int presetIndex(String preset) {
        if (PERFORMANCE.equals(preset)) return 0;
        if (QUALITY.equals(preset)) return 2;
        return 1; // BALANCED
    }

    public static String presetByIndex(int index) {
        switch (index) {
            case 0: return PERFORMANCE;
            case 2: return QUALITY;
            default: return BALANCED;
        }
    }

    public static String screenSizeFor(String preset) {
        if (PERFORMANCE.equals(preset)) return "854x480";
        if (QUALITY.equals(preset)) return "1600x900";
        return "1280x720"; // BALANCED
    }

    public static String box64PresetFor(String preset) {
        if (PERFORMANCE.equals(preset)) return Box64Preset.PERFORMANCE;
        if (QUALITY.equals(preset)) return Box64Preset.STABILITY;
        return Box64Preset.INTERMEDIATE; // BALANCED
    }

    private static byte hudModeFor(RanConfig config) {
        if (config.isDevHud()) return (byte) FrameRating.Mode.FULL.ordinal();
        if (config.isFpsHud()) return (byte) FrameRating.Mode.SIMPLE.ordinal();
        return (byte) FrameRating.Mode.DISABLED.ordinal();
    }

    /**
     * Applies the configured preset + HUD toggles to the RAN container. Saves only if something
     * changed, and always keeps DXVK (the Direct3D 9 client's recommended wrapper).
     */
    public static void apply(Container container, RanConfig config) {
        String preset = config.getGraphicsPreset();
        String screenSize = screenSizeFor(preset);
        String box64Preset = box64PresetFor(preset);
        byte hudMode = hudModeFor(config);

        boolean changed = false;
        if (!screenSize.equals(container.getScreenSize())) { container.setScreenSize(screenSize); changed = true; }
        if (!box64Preset.equals(container.getBox64Preset())) { container.setBox64Preset(box64Preset); changed = true; }
        if (!DXWrappers.DXVK.equals(container.getDXWrapper())) { container.setDXWrapper(DXWrappers.DXVK); changed = true; }
        if (hudMode != container.getHUDMode()) { container.setHUDMode(hudMode); changed = true; }
        if (changed) container.saveData();
    }
}
