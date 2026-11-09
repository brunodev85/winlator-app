package com.winlator;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.preference.PreferenceManager;

import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.core.AppUtils;
import com.winlator.core.LocaleHelper;
import com.winlator.core.PreloaderDialog;
import com.winlator.xenvironment.RootFSInstaller;

import org.json.JSONObject;

import java.util.function.Consumer;

public class MainActivity extends AppCompatActivity {

    public static final boolean DEBUG_MODE = false;
    public static final int CONTAINER_PATTERN_COMPRESSION_LEVEL = 3;

    public static final int PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE = 1;
    public static final int OPEN_FILE_REQUEST_CODE = 2;
    public static final int OPEN_DIRECTORY_REQUEST_CODE = 3;
    public static final int EDIT_INPUT_CONTROLS_REQUEST_CODE = 4;

    private static final String PREF_EXE_PATH = "gothic_exe_path";
    private static final String PREF_CONTAINER_ID = "gothic_container_id";

    public final PreloaderDialog preloaderDialog = new PreloaderDialog(this);

    private SharedPreferences preferences;
    private Button btnConfigure;
    private Button btnAction;
    private TextView tvGameStatus;

    private Consumer<Uri> openFileCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppUtils.setActivityTheme(this);
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_gothic_launcher);

        preferences = PreferenceManager.getDefaultSharedPreferences(this);

        btnConfigure = findViewById(R.id.btnConfigure);
        btnAction = findViewById(R.id.btnAction);
        tvGameStatus = findViewById(R.id.tvGameStatus);

        if (!requestAppPermissions()) {
            RootFSInstaller.installIfNeeded(this);
        }

        updateUIStates();

        if (btnConfigure != null) {
            btnConfigure.setOnClickListener(v -> createGothicContainer());
        }

        if (btnAction != null) {
            btnAction.setOnClickListener(v -> {
                String savedPath = preferences.getString(PREF_EXE_PATH, "");

                if (savedPath.isEmpty()) {
                    openFileSelector();
                } else {
                    launchGame();
                }
            });
        }
    }

    public void setOpenFileCallback(Consumer<Uri> callback) {
        openFileCallback = callback;
    }

    public void showFragment(Fragment fragment) {
        FragmentManager fragmentManager = getSupportFragmentManager();
        FragmentTransaction transaction = fragmentManager.beginTransaction();
        try {
            transaction.replace(android.R.id.content, fragment);
            transaction.addToBackStack(null);
            transaction.commit();
        } catch (Exception ignored) {
        }
    }

    private void updateUIStates() {
        if (btnConfigure == null || btnAction == null || tvGameStatus == null) {
            return;
        }

        ContainerManager manager = new ContainerManager(this);
        int containerId = preferences.getInt(PREF_CONTAINER_ID, -1);
        Container container = manager.getContainerById(containerId);
        String savedPath = preferences.getString(PREF_EXE_PATH, "");

        if (container == null) {
            btnConfigure.setText("Konfiguruj ➔");
            btnConfigure.setEnabled(true);
            btnConfigure.setBackgroundColor(Color.parseColor("#2C3038"));

            btnAction.setEnabled(false);
            btnAction.setText("Wybierz plik .exe gry");
            tvGameStatus.setText("Najpierw skonfiguruj środowisko");
        } else if (savedPath.isEmpty()) {
            btnConfigure.setText("✔ Skonfigurowano");
            btnConfigure.setEnabled(false);
            btnConfigure.setBackgroundColor(Color.parseColor("#2E7D32"));

            btnAction.setEnabled(true);
            btnAction.setText("Wybierz plik .exe gry");
            tvGameStatus.setText("Niewybrany plik .exe");
        } else {
            btnConfigure.setText("✔ Gotowe");
            btnConfigure.setEnabled(false);
            btnConfigure.setBackgroundColor(Color.parseColor("#2E7D32"));

            btnAction.setEnabled(true);
            btnAction.setText("▶ Graj w Gothica");
            tvGameStatus.setText("Zainstalowane (GOTOWE)");
        }
    }

    private void createGothicContainer() {
        if (btnConfigure != null) {
            btnConfigure.setEnabled(false);
        }
        
        try {
            preloaderDialog.show(R.string.starting_up);
        } catch (Exception e) {
            // Zabezpieczenie przed różnicami w wersjach PreloaderDialog w konstruktorach
        }

        try {
            ContainerManager manager = new ContainerManager(this);
            JSONObject data = new JSONObject();
            data.put("name", "Gothic II - New Balance");
            data.put("screenSize", "1280x720");
            data.put("envVars", "BOX64_DYNAREC_BIGBLOCK=2 BOX64_DYNAREC_STRONG_MEM=0");

            manager.createContainerAsync(data, container -> {
                try { preloaderDialog.close(); } catch (Exception ignored) {}

                if (container == null) {
                    Toast.makeText(MainActivity.this, "Nie udało się utworzyć kontenera!", Toast.LENGTH_LONG).show();
                    if (btnConfigure != null) btnConfigure.setEnabled(true);
                    updateUIStates();
                    return;
                }

                preferences.edit()
                        .putInt(PREF_CONTAINER_ID, container.id)
                        .remove(PREF_EXE_PATH)
                        .apply();

                Toast.makeText(MainActivity.this, "Kontener utworzony pomyślnie!", Toast.LENGTH_LONG).show();
                updateUIStates();
            });

        } catch (Exception e) {
            try { preloaderDialog.close(); } catch (Exception ignored) {}
            if (btnConfigure != null) btnConfigure.setEnabled(true);
            Toast.makeText(this, "Błąd tworzenia kontenera: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void openFileSelector() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, OPEN_FILE_REQUEST_CODE);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != OPEN_FILE_REQUEST_CODE || resultCode != Activity.RESULT_OK || data == null) {
            return;
        }

        Uri uri = data.getData();
        if (uri == null) return;

        if (openFileCallback != null) {
            openFileCallback.accept(uri);
            openFileCallback = null;
            return;
        }

        try {
            final int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (Exception ignored) {}

        String path = uri.toString();

        int containerId = preferences.getInt(PREF_CONTAINER_ID, -1);
        ContainerManager manager = new ContainerManager(this);

        if (manager.getContainerById(containerId) == null) {
            Toast.makeText(this, "Najpierw utwórz kontener!", Toast.LENGTH_LONG).show();
            return;
        }

        preferences.edit().putString(PREF_EXE_PATH, path).apply();
        updateUIStates();

        Toast.makeText(this, "Wybrano plik Gothic EXE!", Toast.LENGTH_SHORT).show();
    }

    private void launchGame() {
        int containerId = preferences.getInt(PREF_CONTAINER_ID, -1);
        String exePath = preferences.getString(PREF_EXE_PATH, "");

        if (containerId <= 0 || exePath.isEmpty()) {
            Toast.makeText(this, "Nie znaleziono kontenera lub pliku EXE!", Toast.LENGTH_LONG).show();
            return;
        }

        ContainerManager manager = new ContainerManager(this);
        Container container = manager.getContainerById(containerId);

        if (container == null) {
            Toast.makeText(this, "Kontener nie istnieje. Skonfiguruj go ponownie!", Toast.LENGTH_LONG).show();
            preferences.edit().remove(PREF_CONTAINER_ID).remove(PREF_EXE_PATH).apply();
            updateUIStates();
            return;
        }

        Intent intent = new Intent(MainActivity.this, XServerDisplayActivity.class);
        intent.putExtra("container_id", containerId);
        intent.putExtra("exec_path", exePath);

        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Nie można uruchomić Gothica: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.setSystemLocale(newBase));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                RootFSInstaller.installIfNeeded(this);
            } else {
                finish();
            }
        }
    }

    private boolean requestAppPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            return false;
        }

        ActivityCompat.requestPermissions(this, new String[]{
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE
        }, PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE);

        return true;
    }
}
