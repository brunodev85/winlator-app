package com.winlator.ran;

import android.app.Activity;
import android.content.Intent;

import com.winlator.XServerDisplayActivity;
import com.winlator.container.AudioDrivers;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.container.DXWrappers;
import com.winlator.core.Callback;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;

/**
 * Creates and launches the dedicated <b>RAN Online Mobile</b> Wine container.
 *
 * <p>This is a thin configuration layer on top of Winlator's existing {@link ContainerManager}
 * and {@link XServerDisplayActivity}. It does not modify Wine/Box64 behaviour — it only produces a
 * container tuned for the RAN client (32-bit x86, Direct3D 9) and launches the configured
 * executable via the same {@code exec_path} intent the file manager uses to run any .exe.</p>
 *
 * <p>The user's client folder ({@link RanConfig#getClientDir()}) is mapped as Windows drive
 * {@code R:} so the client's files stay external to the APK.</p>
 */
public final class RanContainerProfile {
    /** extraData marker identifying the RAN container among a user's other containers. */
    public static final String MARKER_KEY = "ranProfile";
    public static final String MARKER_VALUE = "t";
    public static final String CONTAINER_NAME = "RAN Online Mobile";
    public static final String DRIVE_LETTER = "R";

    private RanContainerProfile() {}

    /** @return the existing RAN container, or {@code null} if it has not been created yet. */
    public static Container find(ContainerManager manager) {
        for (Container container : manager.getContainers()) {
            if (MARKER_VALUE.equals(container.getExtra(MARKER_KEY))) return container;
        }
        return null;
    }

    /**
     * Builds the container-creation JSON tuned for RAN Online. Keys mirror those used by
     * Winlator's own container editor so {@link Container#loadData(JSONObject)} accepts them;
     * unset keys inherit Winlator's defaults (graphics driver, env vars, etc.).
     */
    public static JSONObject buildCreateData(RanConfig config) throws JSONException {
        JSONObject data = new JSONObject();
        data.put("name", CONTAINER_NAME);
        // Direct3D 9 client → DXVK (d3d9 → Vulkan) is the recommended path.
        data.put("dxwrapper", DXWrappers.DXVK);
        data.put("wincomponents", Container.DEFAULT_WINCOMPONENTS);
        data.put("audioDriver", AudioDrivers.ALSA);
        data.put("drives", buildDrives(config));
        // Resolution / Box64 preset / HUD are applied from the graphics preset via
        // RanGraphics.apply(...) right after creation (see RanLauncherActivity).

        JSONObject extra = new JSONObject();
        extra.put(MARKER_KEY, MARKER_VALUE);
        data.put("extraData", extra);
        return data;
    }

    /** Winlator's default drives plus {@code R:} → the user's RAN client directory. */
    public static String buildDrives(RanConfig config) {
        String clientPath = config.getClientDir().getAbsolutePath();
        return Container.DEFAULT_DRIVES + DRIVE_LETTER + ":" + clientPath;
    }

    /**
     * Creates the RAN container asynchronously, invoking {@code callback} with the new container
     * (or {@code null} on failure) on the UI thread.
     */
    public static void createAsync(ContainerManager manager, RanConfig config, Callback<Container> callback) {
        try {
            manager.createContainerAsync(buildCreateData(config), callback);
        }
        catch (JSONException e) {
            callback.call(null);
        }
    }

    /**
     * Ensures the RAN container's {@code R:} drive still points at the current client directory
     * (the user may have changed GAME_DIRECTORY). Saves only if it changed.
     */
    public static void syncDrives(Container container, RanConfig config) {
        String expected = buildDrives(config);
        if (!expected.equals(container.getDrives())) {
            container.setDrives(expected);
            container.saveData();
        }
    }

    /**
     * Launches the configured RAN executable inside the given container by starting
     * {@link XServerDisplayActivity} with {@code exec_path} (the same mechanism the file manager
     * uses). The caller must have verified {@link RanConfig#isClientPresent()} first.
     *
     * <p>Note: GAME_ARGUMENTS are not yet forwarded through the {@code exec_path} path; argument
     * support arrives with the shortcut-based launch (see docs/RAN_MOBILE_ARCHITECTURE.md).</p>
     */
    public static void startClient(Activity activity, Container container, RanConfig config) {
        File exe = config.getGameExecutableFile();
        if (exe == null) return;
        Intent intent = new Intent(activity, XServerDisplayActivity.class);
        intent.putExtra("container_id", container.id);
        intent.putExtra("exec_path", exe.getAbsolutePath());
        // Auto-apply the RAN touch-controls profile (created on first use from the asset).
        int controlsProfileId = RanControls.ensureProfileId(activity);
        if (controlsProfileId > 0) intent.putExtra("controls_profile_id", controlsProfileId);
        activity.startActivity(intent);
    }
}
