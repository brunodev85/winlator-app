package com.winlator.core;

import android.app.UiModeManager;
import android.content.Context;
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.provider.Settings;
import android.view.Display;

// shortcut: SEM desktop reflection may break on future OneUI; UiModeManager covers generic desktop mode.
public abstract class DexUtils {
    private static final String SAMSUNG_DEX_FEATURE = "com.samsung.feature.samsung_dex";

    public static boolean isDesktopMode(Context context) {
        UiModeManager uiModeManager = (UiModeManager)context.getSystemService(Context.UI_MODE_SERVICE);
        if (uiModeManager != null && uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_DESK) return true;
        return isSamsungDexMode(context);
    }

    public static boolean isSamsungDexMode(Context context) {
        try {
            if (context.getPackageManager().hasSystemFeature(SAMSUNG_DEX_FEATURE)) {
                Configuration config = context.getResources().getConfiguration();
                int enabled = Configuration.class.getField("SEM_DESKTOP_MODE_ENABLED").getInt(null);
                if (config.getClass().getField("semDesktopModeEnabled").getInt(config) == enabled) return true;
            }
        }
        catch (Exception ignored) {}
        return false;
    }

    public static boolean hasExternalDisplay(Context context) {
        try {
            DisplayManager displayManager = (DisplayManager)context.getSystemService(Context.DISPLAY_SERVICE);
            if (displayManager == null) return false;
            for (Display display : displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) {
                if (display.getDisplayId() != Display.DEFAULT_DISPLAY) return true;
            }
        }
        catch (Exception ignored) {}
        return false;
    }

    // System pointer speed (-7..7) -> multiplier, so the DeX mouse feel carries into Wine.
    public static float getSystemPointerSpeedFactor(Context context) {
        int speed = 0;
        try {
            speed = Settings.System.getInt(context.getContentResolver(), "pointer_speed", 0);
        }
        catch (Exception ignored) {}
        return Math.max(0.25f, Math.min(2.0f, 1.0f + speed * 0.1f));
    }
}
