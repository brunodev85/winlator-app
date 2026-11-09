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
import androidx.preference.PreferenceManager;

import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.core.AppUtils;
import com.winlator.core.FileUtils;
import com.winlator.core.LocaleHelper;
import com.winlator.core.PreloaderDialog;
import com.winlator.shortcut.Shortcut;
import com.winlator.shortcut.ShortcutManager;
import com.winlator.xenvironment.RootFSInstaller;

public class MainActivity extends AppCompatActivity {
    public static final byte PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE = 1;
    public static final byte OPEN_FILE_REQUEST_CODE = 2;

    public final PreloaderDialog preloaderDialog = new PreloaderDialog(this);
    private SharedPreferences preferences;

    private Button btnConfigure;
    private Button btnAction;
    private TextView tvGameStatus;

    private static final String PREF_EXE_PATH = "gothic_exe_path";
    private static final String PREF_CONTAINER_ID = "gothic_container_id";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppUtils.setActivityTheme(this);
        super.onCreate(savedInstanceState);
        
        // Używamy nowego, kafelkowego layoutu launchera zamiast starego menu Winlatora
        setContentView(R.layout.activity_gothic_launcher);

        preferences = PreferenceManager.getDefaultSharedPreferences(this);

        btnConfigure = findViewById(R.id.btnConfigure);
        btnAction = findViewById(R.id.btnAction);
        tvGameStatus = findViewById(R.id.tvGameStatus);

        // Sprawdzamy uprawnienia i instalujemy rootfs (kluczowe dla działania emulatora pod spodem!)
        if (!requestAppPermissions()) {
            RootFSInstaller.installIfNeeded(this);
        }

        // Odświeżamy stany interfejsu (Konfiguracja -> Zielony plus -> Graj)
        updateUIStates();

        // KROK 1: Kliknięcie przycisku "Konfiguruj" - tworzy kontener w tle z optymalizacjami
        btnConfigure.setOnClickListener(v -> createGothicContainer());

        // KROK 2: Kliknięcie przycisku akcji (Wybór folderu .exe lub uruchomienie gry)
        btnAction.setOnClickListener(v -> {
            String savedPath = preferences.getString(PREF_EXE_PATH, "");
            if (savedPath.isEmpty()) {
                openFileSelector();
            } else {
                launchGame();
            }
        });
    }

    private void updateUIStates() {
        ContainerManager manager = new ContainerManager(this);
        Container container = manager.getContainerById(1); // Sprawdzamy czy kontener Gothic (ID: 1) istnieje
        String savedPath = preferences.getString(PREF_EXE_PATH, "");

        if (container == null) {
            // Stan 1: Wymagana konfiguracja kontenera
            btnConfigure.setText("Konfiguruj ➔");
            btnConfigure.setEnabled(true);
            btnConfigure.setBackgroundColor(Color.parseColor("#2C3038"));
            btnAction.setEnabled(false);
            btnAction.setText("Wybierz folder z grą");
            tvGameStatus.setText("Najpierw skonfiguruj środowisko");
        } else if (savedPath.isEmpty()) {
            // Stan 2: Kontener gotowy (zielony plus), ale brak wybranej ścieżki do gry
            btnConfigure.setText("✔ Skonfigurowano");
            btnConfigure.setEnabled(false);
            btnConfigure.setBackgroundColor(Color.parseColor("#2E7D32")); // Zielony akcent
            btnAction.setEnabled(true);
            btnAction.setText("Wybierz folder z grą");
            tvGameStatus.setText("Niewybrany folder");
        } else {
            // Stan 3: Wszystko gotowe, można grać
            btnConfigure.setText("✔ Gotowe");
            btnConfigure.setEnabled(false);
            btnConfigure.setBackgroundColor(Color.parseColor("#2E7D32"));
            btnAction.setEnabled(true);
            btnAction.setText("▶ Graj");
            tvGameStatus.setText("Zainstalowane (GOTOWE)");
        }
    }

    private void createGothicContainer() {
        preloaderDialog.show("Tworzenie kontenera dla Gothica...");
        
        new Thread(() -> {
            ContainerManager manager = new ContainerManager(this);
            Container container = new Container(manager);
            container.name = "Gothic Container";
            container.setResolution("1280x720");
            // Wprowadzamy optymalizacje Box64 pod oszczędność baterii i płynność w New Balance
            container.setEnvVars("BOX64_DYNAREC_BIGBLOCK=2,BOX64_DYNAREC_STRONG_MEM=0");
            container.save();

            preferences.edit().putInt(PREF_CONTAINER_ID, container.id).apply();

            runOnUiThread(() -> {
                preloaderDialog.dismiss();
                Toast.makeText(this, "Kontener utworzony pomyślnie!", Toast.LENGTH_SHORT).show();
                updateUIStates(); // Odświeża widok na zielony znacznik
            });
        }).start();
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
        if (requestCode == OPEN_FILE_REQUEST_CODE && resultCode == Activity.RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                String path = FileUtils.getPath(this, uri);
                if (path == null) path = uri.getPath();

                preferences.edit().putString(PREF_EXE_PATH, path).apply();
                registerShortcut(path);

                updateUIStates();
                Toast.makeText(this, "Utworzono skrót do gry!", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void registerShortcut(String exePath) {
        int containerId = preferences.getInt(PREF_CONTAINER_ID, 1);
        ShortcutManager shortcutManager = new ShortcutManager(this);
        
        Shortcut shortcut = new Shortcut();
        shortcut.name = "Gothic II - New Balance";
        shortcut.containerId = containerId;
        shortcut.path = exePath;
        shortcut.screenSize = "1280x720";
        shortcutManager.add(shortcut);
    }

    private void launchGame() {
        ShortcutManager shortcutManager = new ShortcutManager(this);
        Shortcut shortcut = shortcutManager.getShortcutByPath(preferences.getString(PREF_EXE_PATH, ""));

        if (shortcut != null) {
            // Wywołujemy standardowe środowisko graficzne Winlatora przez ID skrótu
            Intent intent = new Intent(this, XServerDisplayActivity.class);
            intent.putExtra("shortcut_id", shortcut.id);
            startActivity(intent);
        } else {
            Toast.makeText(this, "Nie znaleziono skrótu w bazie!", Toast.LENGTH_SHORT).show();
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
            }
            else finish();
        }
    }

    private boolean requestAppPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) return false;

        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE}, PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE);
        return true;
    }
}
