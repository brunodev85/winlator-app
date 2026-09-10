package com.winlator.ran;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.InetSocketAddress;
import java.net.Socket;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.winlator.MainActivity;
import com.winlator.R;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.core.AppUtils;
import com.winlator.xenvironment.RootFS;
import com.winlator.xenvironment.RootFSInstaller;

import java.io.File;

/**
 * Dedicated RAN Online Mobile home screen (the app's LAUNCHER activity).
 *
 * <p>Provides a game-launcher UX and orchestrates the runtime that Winlator already implements:
 * request storage permission → install the guest rootfs if needed → ensure the RAN container
 * exists → launch the configured RAN executable. Winlator's own {@link MainActivity} remains
 * reachable via "Advanced (Winlator)".</p>
 */
public class RanLauncherActivity extends AppCompatActivity {
    private static final byte PERMISSION_REQUEST_CODE = 10;
    private static final String RAN_VERSION = "1.0.0";

    private RanConfig config;
    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppUtils.setActivityTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.ran_launcher_activity);

        config = new RanConfig(this);

        statusView = findViewById(R.id.TVStatus);
        ((TextView) findViewById(R.id.TVServerName)).setText(config.getServerName());
        ((TextView) findViewById(R.id.TVVersion)).setText(getString(R.string.ran_version_label, RAN_VERSION));

        findViewById(R.id.BTPlay).setOnClickListener(v -> onPlay());
        findViewById(R.id.BTSettings).setOnClickListener(v -> openWinlator(R.id.menu_item_settings));
        findViewById(R.id.BTControls).setOnClickListener(v -> openWinlator(R.id.menu_item_input_controls));
        findViewById(R.id.BTGraphics).setOnClickListener(v -> showGraphicsDialog());
        findViewById(R.id.BTServer).setOnClickListener(v -> showServerDialog());
        findViewById(R.id.BTDiagnostics).setOnClickListener(v -> showDiagnosticsDialog());
        findViewById(R.id.BTAdvanced).setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)));

        if (ensureStoragePermission()) onStorageReady();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasStoragePermission()) refreshStatus();
    }

    // ---- Permission + bootstrap ------------------------------------------------------------

    private boolean hasStoragePermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    /** @return true if permission is already granted; otherwise requests it and returns false. */
    private boolean ensureStoragePermission() {
        if (hasStoragePermission()) return true;
        ActivityCompat.requestPermissions(this, new String[]{
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE
        }, PERMISSION_REQUEST_CODE);
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (hasStoragePermission()) onStorageReady();
            else if (statusView != null) statusView.setText(R.string.ran_storage_permission_required);
        }
    }

    private void onStorageReady() {
        config.ensureDirectories();
        // Create the RAN touch-controls profile so it's available in the Controls editor.
        RanControls.ensureProfileId(this);
        // Install the guest Linux/Wine rootfs into app-private storage if missing/outdated.
        RootFSInstaller.installIfNeeded(this);
        refreshStatus();
    }

    private void refreshStatus() {
        if (statusView == null) return;
        if (!hasStoragePermission()) {
            statusView.setText(R.string.ran_storage_permission_required);
            return;
        }
        if (config.isClientPresent()) {
            statusView.setText(getString(R.string.ran_client_ready, config.getGameExecutable()));
        }
        else {
            statusView.setText(getString(R.string.ran_client_not_found,
                config.getClientDir().getAbsolutePath(), config.getGameExecutable()));
        }
    }

    // ---- Play flow -------------------------------------------------------------------------

    private void onPlay() {
        if (!ensureStoragePermission()) return;
        config.ensureDirectories();

        RootFS rootFS = RootFS.find(this);
        if (!rootFS.isValid()) {
            // rootfs still installing (or failed) — kick it off and ask the user to retry.
            RootFSInstaller.installIfNeeded(this);
            AppUtils.showToast(this, R.string.ran_preparing_runtime);
            return;
        }

        if (!config.isClientPresent()) {
            new AlertDialog.Builder(this)
                .setTitle(R.string.ran_play)
                .setMessage(getString(R.string.ran_client_not_found,
                    config.getClientDir().getAbsolutePath(), config.getGameExecutable()))
                .setPositiveButton(android.R.string.ok, null)
                .show();
            refreshStatus();
            return;
        }

        ContainerManager manager = new ContainerManager(this);
        Container existing = RanContainerProfile.find(manager);
        if (existing != null) {
            RanContainerProfile.syncDrives(existing, config);
            RanGraphics.apply(existing, config);
            RanContainerProfile.startClient(this, existing, config);
        }
        else {
            AppUtils.showToast(this, R.string.ran_creating_container);
            RanContainerProfile.createAsync(manager, config, container -> {
                if (container != null) {
                    RanGraphics.apply(container, config);
                    RanContainerProfile.startClient(this, container, config);
                }
                else {
                    AppUtils.showToast(this, R.string.unable_to_install_system_files);
                }
            });
        }
    }

    private void openWinlator(int selectedMenuItemId) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra("selected_menu_item_id", selectedMenuItemId);
        startActivity(intent);
    }

    // ---- Server config dialog --------------------------------------------------------------

    private void showServerDialog() {
        Context ctx = this;
        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);

        final EditText etName = addField(layout, "Server name", config.getServerName(), InputType.TYPE_CLASS_TEXT);
        final EditText etIp = addField(layout, "Server IP / host", config.getServerIp(), InputType.TYPE_CLASS_TEXT);
        final EditText etPort = addField(layout, "Server port", String.valueOf(config.getServerPort()), InputType.TYPE_CLASS_NUMBER);
        final EditText etExe = addField(layout, "Game executable (GAME_EXE)", config.getGameExecutable(), InputType.TYPE_CLASS_TEXT);
        final EditText etArgs = addField(layout, "Game arguments", config.getGameArguments(), InputType.TYPE_CLASS_TEXT);

        new AlertDialog.Builder(ctx)
            .setTitle(R.string.ran_server)
            .setView(layout)
            .setPositiveButton(R.string.save, (d, w) -> {
                config.setServerName(text(etName));
                config.setServerIp(text(etIp));
                config.setServerPort(parseInt(text(etPort), config.getServerPort()));
                config.setGameExecutable(text(etExe));
                config.setGameArguments(text(etArgs));
                config.save();
                ((TextView) findViewById(R.id.TVServerName)).setText(config.getServerName());
                refreshStatus();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showGraphicsDialog() {
        Context ctx = this;
        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(ctx);
        title.setText("Performance preset");
        layout.addView(title);

        final RadioGroup rg = new RadioGroup(ctx);
        String[] labels = RanGraphics.presetLabels();
        for (int i = 0; i < labels.length; i++) {
            RadioButton rb = new RadioButton(ctx);
            rb.setId(i + 1);
            rb.setText(labels[i] + "  (" + RanGraphics.screenSizeFor(RanGraphics.presetByIndex(i)) + ")");
            rg.addView(rb);
        }
        rg.check(RanGraphics.presetIndex(config.getGraphicsPreset()) + 1);
        layout.addView(rg);

        final CheckBox cbFps = new CheckBox(ctx);
        cbFps.setText("Show FPS overlay");
        cbFps.setChecked(config.isFpsHud());
        layout.addView(cbFps);

        final CheckBox cbDev = new CheckBox(ctx);
        cbDev.setText("Developer HUD (CPU / RAM / GPU)");
        cbDev.setChecked(config.isDevHud());
        layout.addView(cbDev);

        new AlertDialog.Builder(ctx)
            .setTitle(R.string.ran_graphics)
            .setView(layout)
            .setPositiveButton(R.string.save, (d, w) -> {
                int idx = rg.getCheckedRadioButtonId() - 1;
                config.setGraphicsPreset(RanGraphics.presetByIndex(idx < 0 ? 1 : idx));
                config.setFpsHud(cbFps.isChecked());
                config.setDevHud(cbDev.isChecked());
                config.save();
                ContainerManager manager = new ContainerManager(this);
                Container ran = RanContainerProfile.find(manager);
                if (ran != null) RanGraphics.apply(ran, config);
                AppUtils.showToast(this, R.string.save);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showDiagnosticsDialog() {
        RootFS rootFS = RootFS.find(this);
        File exe = config.getGameExecutableFile();
        StringBuilder sb = new StringBuilder();
        sb.append("Device model: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("CPU ABIs: ").append(String.join(", ", Build.SUPPORTED_ABIS)).append('\n');
        sb.append("64-bit ABIs: ").append(String.join(", ", Build.SUPPORTED_64_BIT_ABIS)).append('\n');
        sb.append("Rootfs installed: ").append(rootFS.isValid() ? ("yes (v" + rootFS.getFormattedVersion() + ")") : "no").append('\n');
        sb.append("Graphics preset: ").append(config.getGraphicsPreset())
            .append(" (").append(RanGraphics.screenSizeFor(config.getGraphicsPreset())).append(")\n");
        sb.append('\n');
        sb.append("Server: ").append(config.getServerName()).append('\n');
        sb.append("Address: ").append(config.getServerIp()).append(':').append(config.getServerPort()).append('\n');
        sb.append('\n');
        sb.append("Client dir: ").append(config.getClientDir().getAbsolutePath()).append('\n');
        sb.append("Game exe: ").append(config.getGameExecutable()).append('\n');
        sb.append("Client present: ").append(config.isClientPresent() ? "yes" : "no").append('\n');
        if (exe != null) sb.append("Resolved path: ").append(exe.getAbsolutePath()).append('\n');
        final String base = sb.toString();

        final TextView tv = new TextView(this);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad, pad, pad);
        tv.setTextIsSelectable(true);
        tv.setText(base + "\nServer connection: testing…");
        ScrollView scroll = new ScrollView(this);
        scroll.addView(tv);

        new AlertDialog.Builder(this)
            .setTitle(R.string.ran_diagnostics)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .show();

        // Real connection test (needs INTERNET permission, already declared). Runs off the UI thread.
        final String host = config.getServerIp();
        final int port = config.getServerPort();
        new Thread(() -> {
            final String result = pingServer(host, port, 3000);
            runOnUiThread(() -> tv.setText(base + "\nServer connection: " + result));
        }).start();
    }

    private static String pingServer(String host, int port, int timeoutMs) {
        if (host == null || host.trim().isEmpty()) return "no server set";
        long start = System.currentTimeMillis();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return "ONLINE (" + (System.currentTimeMillis() - start) + " ms)";
        }
        catch (Exception e) {
            return "OFFLINE / unreachable";
        }
    }

    // ---- small view helpers ----------------------------------------------------------------

    private EditText addField(LinearLayout parent, String label, String value, int inputType) {
        TextView tv = new TextView(parent.getContext());
        tv.setText(label);
        parent.addView(tv);
        EditText et = new EditText(parent.getContext());
        et.setInputType(inputType);
        et.setText(value);
        et.setSingleLine(true);
        parent.addView(et, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return et;
    }

    private static String text(EditText et) {
        return et.getText() != null ? et.getText().toString().trim() : "";
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        }
        catch (NumberFormatException e) {
            return fallback;
        }
    }
}
