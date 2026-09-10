package com.winlator.ran;

import android.content.Context;
import android.os.Environment;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * RAN Online Mobile configuration model.
 *
 * <p>Owns the on-device RAN home directory layout and the developer-editable
 * {@code server_config.json}. The RAN game client itself is <b>user-supplied and external</b> —
 * it is never bundled in the APK. The user copies their Windows client (e.g. the whole
 * {@code KYUDO DEVELOPMENT CLIENT} folder) into {@link #getClientDir()} and the app launches the
 * configured executable from there through Wine/Box64.</p>
 *
 * <p>On-device layout (all under {@link #getRanHomeDir()} = {@code <external storage>/RANMobile}):</p>
 * <pre>
 * RANMobile/
 *   ├── client/                 ← user drops their RAN Online client here (Update.exe, minia.exe, ...)
 *   ├── config/server_config.json
 *   ├── logs/                   ← exported diagnostics (see RanLog, later phase)
 *   ├── cache/
 *   └── user/                   ← per-user data kept outside the Wine prefix
 * </pre>
 */
public class RanConfig {
    public static final String RAN_HOME_DIRNAME = "RANMobile";
    public static final String CONFIG_FILENAME = "server_config.json";

    // Config keys (mirror config/server_config.json). Kept stable for external editing.
    public static final String KEY_SERVER_NAME = "serverName";
    public static final String KEY_SERVER_IP = "serverIp";
    public static final String KEY_SERVER_PORT = "serverPort";
    public static final String KEY_PATCH_URL = "patchUrl";
    public static final String KEY_GAME_EXECUTABLE = "gameExecutable"; // GAME_EXE
    public static final String KEY_GAME_DIRECTORY = "gameDirectory";   // GAME_DIRECTORY (optional absolute override)
    public static final String KEY_GAME_ARGUMENTS = "gameArguments";   // GAME_ARGUMENTS
    public static final String KEY_GRAPHICS_PRESET = "graphicsPreset"; // PERFORMANCE|BALANCED|QUALITY
    public static final String KEY_FPS_HUD = "fpsHud";                 // show FPS overlay
    public static final String KEY_DEV_HUD = "devHud";                 // developer HUD (CPU/RAM/GPU)

    // Sensible, non-sensitive defaults. NEVER hardcode private-server credentials here.
    public static final String DEFAULT_SERVER_NAME = "My RAN Server";
    public static final String DEFAULT_SERVER_IP = "127.0.0.1";
    public static final int DEFAULT_SERVER_PORT = 5000;
    public static final String DEFAULT_PATCH_URL = "";
    public static final String DEFAULT_GAME_EXECUTABLE = "Update.exe";
    public static final String DEFAULT_GAME_ARGUMENTS = "";
    public static final String DEFAULT_GRAPHICS_PRESET = "BALANCED";

    private final Context context;
    private final JSONObject data;

    public RanConfig(Context context) {
        this.context = context.getApplicationContext();
        this.data = load();
    }

    /** External RAN home, e.g. {@code /storage/emulated/0/RANMobile}. */
    public File getRanHomeDir() {
        return new File(Environment.getExternalStorageDirectory(), RAN_HOME_DIRNAME);
    }

    public File getConfigDir() {
        return new File(getRanHomeDir(), "config");
    }

    public File getConfigFile() {
        return new File(getConfigDir(), CONFIG_FILENAME);
    }

    /** Default directory holding the user's RAN client, unless overridden via GAME_DIRECTORY. */
    public File getClientDir() {
        String override = data.optString(KEY_GAME_DIRECTORY, "");
        if (override != null && !override.trim().isEmpty()) {
            File dir = new File(override.trim());
            if (dir.isAbsolute()) return dir;
        }
        return new File(getRanHomeDir(), "client");
    }

    public File getLogsDir() {
        return new File(getRanHomeDir(), "logs");
    }

    public File getCacheDir() {
        return new File(getRanHomeDir(), "cache");
    }

    public File getUserDir() {
        return new File(getRanHomeDir(), "user");
    }

    // ---- Typed accessors -------------------------------------------------------------------

    public String getServerName() { return data.optString(KEY_SERVER_NAME, DEFAULT_SERVER_NAME); }
    public String getServerIp() { return data.optString(KEY_SERVER_IP, DEFAULT_SERVER_IP); }
    public int getServerPort() { return data.optInt(KEY_SERVER_PORT, DEFAULT_SERVER_PORT); }
    public String getPatchUrl() { return data.optString(KEY_PATCH_URL, DEFAULT_PATCH_URL); }
    public String getGameArguments() { return data.optString(KEY_GAME_ARGUMENTS, DEFAULT_GAME_ARGUMENTS); }
    public String getGraphicsPreset() { return data.optString(KEY_GRAPHICS_PRESET, DEFAULT_GRAPHICS_PRESET); }
    public boolean isFpsHud() { return data.optBoolean(KEY_FPS_HUD, false); }
    public boolean isDevHud() { return data.optBoolean(KEY_DEV_HUD, false); }

    /**
     * Configured game executable name, sanitized to a bare filename (no path separators, no "..").
     * This prevents a malicious/mis-typed config from escaping the client directory.
     */
    public String getGameExecutable() {
        String exe = data.optString(KEY_GAME_EXECUTABLE, DEFAULT_GAME_EXECUTABLE);
        return sanitizeFilename(exe, DEFAULT_GAME_EXECUTABLE);
    }

    public void setServerName(String v) { put(KEY_SERVER_NAME, v); }
    public void setServerIp(String v) { put(KEY_SERVER_IP, v); }
    public void setServerPort(int v) { put(KEY_SERVER_PORT, v); }
    public void setPatchUrl(String v) { put(KEY_PATCH_URL, v); }
    public void setGameExecutable(String v) { put(KEY_GAME_EXECUTABLE, sanitizeFilename(v, DEFAULT_GAME_EXECUTABLE)); }
    public void setGameDirectory(String v) { put(KEY_GAME_DIRECTORY, v); }
    public void setGameArguments(String v) { put(KEY_GAME_ARGUMENTS, v); }
    public void setGraphicsPreset(String v) { put(KEY_GRAPHICS_PRESET, v); }
    public void setFpsHud(boolean v) { put(KEY_FPS_HUD, v); }
    public void setDevHud(boolean v) { put(KEY_DEV_HUD, v); }

    // ---- Derived ---------------------------------------------------------------------------

    /**
     * Absolute path to the configured game executable, guaranteed to resolve <b>inside</b> the
     * client directory (defends against path traversal). Returns {@code null} if it would escape.
     */
    public File getGameExecutableFile() {
        File clientDir = getClientDir();
        File exe = new File(clientDir, getGameExecutable());
        try {
            String base = clientDir.getCanonicalPath() + File.separator;
            String full = exe.getCanonicalFile().getPath();
            if (full.equals(clientDir.getCanonicalPath()) || !full.startsWith(base)) return null;
        }
        catch (IOException e) {
            return null;
        }
        return exe;
    }

    public boolean isClientPresent() {
        File exe = getGameExecutableFile();
        return exe != null && exe.isFile();
    }

    /** Creates the RAN home directory tree if missing (best-effort; needs storage permission). */
    public boolean ensureDirectories() {
        boolean ok = mkdirs(getRanHomeDir());
        ok &= mkdirs(getClientDir());
        ok &= mkdirs(getConfigDir());
        ok &= mkdirs(getLogsDir());
        ok &= mkdirs(getCacheDir());
        ok &= mkdirs(getUserDir());
        return ok;
    }

    // ---- Persistence -----------------------------------------------------------------------

    private JSONObject load() {
        File file = getConfigFile();
        if (file.isFile()) {
            try {
                byte[] bytes = readAllBytes(file);
                return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            }
            catch (JSONException | IOException e) {
                // fall through to defaults on a corrupt/unreadable config
            }
        }
        return new JSONObject();
    }

    /** Writes the config back to {@code config/server_config.json} (pretty-printed). */
    public boolean save() {
        try {
            ensureDirectories();
            JSONObject out = new JSONObject();
            out.put(KEY_SERVER_NAME, getServerName());
            out.put(KEY_SERVER_IP, getServerIp());
            out.put(KEY_SERVER_PORT, getServerPort());
            out.put(KEY_PATCH_URL, getPatchUrl());
            out.put(KEY_GAME_EXECUTABLE, getGameExecutable());
            out.put(KEY_GAME_DIRECTORY, data.optString(KEY_GAME_DIRECTORY, ""));
            out.put(KEY_GAME_ARGUMENTS, getGameArguments());
            out.put(KEY_GRAPHICS_PRESET, getGraphicsPreset());
            out.put(KEY_FPS_HUD, isFpsHud());
            out.put(KEY_DEV_HUD, isDevHud());
            writeString(getConfigFile(), out.toString(4));
            return true;
        }
        catch (JSONException | IOException e) {
            return false;
        }
    }

    private void put(String key, Object value) {
        try {
            data.put(key, value);
        }
        catch (JSONException e) {
            // ignore
        }
    }

    // ---- Helpers ---------------------------------------------------------------------------

    /** Reduces any input to a safe bare filename; returns {@code fallback} if nothing safe remains. */
    static String sanitizeFilename(String name, String fallback) {
        if (name == null) return fallback;
        String trimmed = name.trim().replace('\\', '/');
        int slash = trimmed.lastIndexOf('/');
        if (slash != -1) trimmed = trimmed.substring(slash + 1);
        if (trimmed.isEmpty() || trimmed.equals(".") || trimmed.equals("..") || trimmed.contains("..")) {
            return fallback;
        }
        return trimmed;
    }

    private static boolean mkdirs(File dir) {
        return dir.isDirectory() || dir.mkdirs();
    }

    private static byte[] readAllBytes(File file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long len = raf.length();
            if (len > 1_000_000L) len = 1_000_000L; // config files are tiny; cap defensively
            byte[] buf = new byte[(int) len];
            raf.readFully(buf);
            return buf;
        }
    }

    private static void writeString(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory()) parent.mkdirs();
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }
}
