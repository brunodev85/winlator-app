package com.winlator;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.core.FileUtils;
import com.winlator.core.PreloaderDialog;
import com.winlator.shortcut.Shortcut;
import com.winlator.shortcut.ShortcutManager;

public class GothicLauncherFragment extends Fragment {
    private SharedPreferences preferences;
    private Button btnConfigure;
    private Button btnAction;
    private TextView tvGameStatus;

    private static final String PREF_EXE_PATH = "gothic_exe_path";
    private static final String PREF_CONTAINER_ID = "gothic_container_id";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.activity_gothic_launcher, container, false);

        Context context = requireContext();
        preferences = PreferenceManager.getDefaultSharedPreferences(context);

        btnConfigure = view.findViewById(R.id.btnConfigure);
        btnAction = view.findViewById(R.id.btnAction);
        tvGameStatus = view.findViewById(R.id.tvGameStatus);

        updateUIStates();

        btnConfigure.setOnClickListener(v -> createGothicContainer());

        btnAction.setOnClickListener(v -> {
            String savedPath = preferences.getString(PREF_EXE_PATH, "");
            if (savedPath.isEmpty()) {
                openFileSelector();
            } else {
                launchGame();
            }
        });

        return view;
    }

    private void updateUIStates() {
        Context context = getContext();
        if (context == null) return;

        ContainerManager manager = new ContainerManager(context);
        Container container = manager.getContainerById(1);
        String savedPath = preferences.getString(PREF_EXE_PATH, "");

        if (container == null) {
            btnConfigure.setText("Konfiguruj ➔");
            btnConfigure.setEnabled(true);
            btnConfigure.setBackgroundColor(Color.parseColor("#2C3038"));
            btnAction.setEnabled(false);
            btnAction.setText("Wybierz folder z grą");
            tvGameStatus.setText("Najpierw skonfiguruj środowisko");
        } else if (savedPath.isEmpty()) {
            btnConfigure.setText("✔ Skonfigurowano");
            btnConfigure.setEnabled(false);
            btnConfigure.setBackgroundColor(Color.parseColor("#2E7D32"));
            btnAction.setEnabled(true);
            btnAction.setText("Wybierz folder z grą");
            tvGameStatus.setText("Niewybrany folder");
        } else {
            btnConfigure.setText("✔ Gotowe");
            btnConfigure.setEnabled(false);
            btnConfigure.setBackgroundColor(Color.parseColor("#2E7D32"));
            btnAction.setEnabled(true);
            btnAction.setText("▶ Graj");
            tvGameStatus.setText("Zainstalowane (GOTOWE)");
        }
    }

    private void createGothicContainer() {
        Activity activity = getActivity();
        if (activity == null) return;

        PreloaderDialog preloaderDialog = new PreloaderDialog(activity);
        preloaderDialog.show("Tworzenie kontenera dla Gothica...");

        new Thread(() -> {
            ContainerManager manager = new ContainerManager(activity);
            Container container = new Container(manager);
            container.setName("Gothic Container");
            container.setScreenSize("1280x720");
            container.setEnvVars("BOX64_DYNAREC_BIGBLOCK=2,BOX64_DYNAREC_STRONG_MEM=0");
            container.save();

            preferences.edit().putInt(PREF_CONTAINER_ID, container.id).apply();

            activity.runOnUiThread(() -> {
                preloaderDialog.dismiss();
                Toast.makeText(activity, "Kontener utworzony!", Toast.LENGTH_SHORT).show();
                updateUIStates();
            });
        }).start();
    }

    private void openFileSelector() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, MainActivity.OPEN_FILE_REQUEST_CODE);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == MainActivity.OPEN_FILE_REQUEST_CODE && resultCode == Activity.RESULT_OK && data != null) {
            Uri uri = data.getData();
            Context context = getContext();
            if (uri != null && context != null) {
                String path = FileUtils.getPath(context, uri);
                if (path == null) path = uri.getPath();

                preferences.edit().putString(PREF_EXE_PATH, path).apply();
                registerShortcut(path);

                updateUIStates();
                Toast.makeText(context, "Utworzono skrót!", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void registerShortcut(String exePath) {
        Context context = getContext();
        if (context == null) return;

        int containerId = preferences.getInt(PREF_CONTAINER_ID, 1);
        ShortcutManager shortcutManager = new ShortcutManager(context);

        Shortcut shortcut = new Shortcut();
        shortcut.name = "Gothic II - New Balance";
        shortcut.containerId = containerId;
        shortcut.path = exePath;
        shortcut.screenSize = "1280x720";
        shortcutManager.add(shortcut);
    }

    private void launchGame() {
        Context context = getContext();
        if (context == null) return;

        ShortcutManager shortcutManager = new ShortcutManager(context);
        Shortcut shortcut = shortcutManager.getShortcutByPath(preferences.getString(PREF_EXE_PATH, ""));

        if (shortcut != null) {
            Intent intent = new Intent(context, XServerDisplayActivity.class);
            intent.putExtra("shortcut_id", shortcut.id);
            startActivity(intent);
        } else {
            Toast.makeText(context, "Nie znaleziono skrótu!", Toast.LENGTH_SHORT).show();
        }
    }
}
