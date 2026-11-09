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

        btnConfigure.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).showFragment(new ContainersFragment());
            }
        });

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
        Container container = manager.getContainerById(preferences.getInt(PREF_CONTAINER_ID, 1));
        String savedPath = preferences.getString(PREF_EXE_PATH, "");

        if (container == null) {
            btnConfigure.setText("Konfiguruj Kontener ➔");
            btnConfigure.setEnabled(true);
            btnConfigure.setBackgroundColor(Color.parseColor("#2C3038"));
            btnAction.setEnabled(false);
            btnAction.setText("Wybierz plik .exe gry");
            tvGameStatus.setText("Najpierw utwórz kontener w Winlatorze");
        } else if (savedPath.isEmpty()) {
            btnConfigure.setText("✔ Kontener Gotowy");
            btnConfigure.setEnabled(false);
            btnConfigure.setBackgroundColor(Color.parseColor("#2E7D32"));
            btnAction.setEnabled(true);
            btnAction.setText("Wybierz plik .exe gry");
            tvGameStatus.setText("Wskaż plik Gothic2.exe");
        } else {
            btnConfigure.setText("✔ Gotowe");
            btnConfigure.setEnabled(false);
            btnConfigure.setBackgroundColor(Color.parseColor("#2E7D32"));
            btnAction.setEnabled(true);
            btnAction.setText("▶ Graj w Gothica");
            tvGameStatus.setText("Zainstalowane (GOTOWE)");
        }
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
                // Używamy uri.toString() zamiast nieistniejącej metody FileUtils
                String path = uri.toString();

                preferences.edit().putString(PREF_EXE_PATH, path).apply();
                updateUIStates();
                Toast.makeText(context, "Zapisano ścieżkę do gry!", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void launchGame() {
        Context context = getContext();
        if (context == null) return;

        int containerId = preferences.getInt(PREF_CONTAINER_ID, 1);
        String exePath = preferences.getString(PREF_EXE_PATH, "");

        if (!exePath.isEmpty()) {
            Intent intent = new Intent(context, XServerDisplayActivity.class);
            intent.putExtra("container_id", containerId);
            intent.putExtra("shortcut_path", exePath);
            startActivity(intent);
        } else {
            Toast.makeText(context, "Najpierw wybierz plik .exe!", Toast.LENGTH_SHORT).show();
        }
    }
}
