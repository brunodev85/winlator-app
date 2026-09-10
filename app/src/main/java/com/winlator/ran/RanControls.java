package com.winlator.ran;

import android.content.Context;

import com.winlator.core.FileUtils;
import com.winlator.inputcontrols.ControlsProfile;
import com.winlator.inputcontrols.InputControlsManager;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Manages the default <b>RAN Online</b> on-screen touch-controls profile.
 *
 * <p>The layout ships as an editable asset ({@code assets/ran/ran_controls_default.icp}) in
 * Winlator's own {@code ControlsProfile} format (D-pad → WASD movement, a right-side TRACKPAD for
 * camera/mouse-look, attack/target/pickup/interact buttons, an 8-slot hotbar, and a radial menu
 * for the game windows). On first use it is imported into Winlator's profile store via
 * {@link InputControlsManager#importProfile}. It is intentionally NOT placed in
 * {@code inputcontrols/profiles}, so app updates never overwrite a user's tuning; the user can
 * remap everything in the Controls editor.</p>
 */
public final class RanControls {
    public static final String PROFILE_NAME = "RAN Online";
    public static final String ASSET_PATH = "ran/ran_controls_default.icp";

    private RanControls() {}

    /**
     * @return the id of the "RAN Online" controls profile, creating it from the bundled asset on
     *         first use; 0 if it does not exist and could not be created.
     */
    public static int ensureProfileId(Context context) {
        InputControlsManager manager = new InputControlsManager(context);
        ControlsProfile existing = findByName(manager, PROFILE_NAME);
        if (existing != null) return existing.id;

        try {
            String json = FileUtils.readString(context, ASSET_PATH);
            if (json == null || json.isEmpty()) return 0;
            ControlsProfile created = manager.importProfile(new JSONObject(json));
            return created != null ? created.id : 0;
        }
        catch (JSONException e) {
            return 0;
        }
    }

    private static ControlsProfile findByName(InputControlsManager manager, String name) {
        for (ControlsProfile profile : manager.getProfiles()) {
            if (name.equals(profile.getName())) return profile;
        }
        return null;
    }
}
