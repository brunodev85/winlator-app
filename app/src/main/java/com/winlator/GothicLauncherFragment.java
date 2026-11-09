package com.winlator;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
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

import org.json.JSONObject;

import java.io.File;

public class GothicLauncherFragment extends Fragment {
    private SharedPreferences preferences;
    private Button btnConfigure;
    private Button btnAction;
    private TextView tvGameStatus;

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

        if (btnConfigure != null) {
            btnConfigure.setOnClickListener(v -> createGothicContainer());
        }

        if (btnAction != null) {
            btnAction.setOnClickListener(v -> {
                int containerId = preferences.getInt(PREF_CONTAINER_ID, -1);
                if (containerId == -1) {
                    createGothicContainer();
                } else {
                    openContainerFileManager(containerId);
                }
            });
        }

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        updateUIStates();
    }

    private void updateUIStates() {
        Context context = getContext();
        if (context == null || btnConfigure == null || btnAction == null || tvGameStatus == null) return;

        ContainerManager manager = new ContainerManager(context);
        int containerId = preferences.getInt(PREF_CONTAINER_ID, -1);
        Container container = manager.getContainerById(containerId);

        // Sprawdzamy czy plik Gothic2.desktop już istnieje w kontenerze
        File containerDir = new File(context.getFilesDir(), "containers/" + containerId);
        File desktopFile = new File(containerDir, "desktop/Gothic2.desktop");
        boolean hasShortcut = desktopFile.exists();

        if (container == null) {
            btnConfigure.setText("Konfiguruj");
            btnConfigure.setEnabled(true);
            btnConfigure.setTextColor(Color.parseColor("#8AB4F8"));

            btnAction.setEnabled(false);
            btnAction.setText("Utwórz kontener gry");
            btnAction.setBackgroundColor(Color.parseColor("#252A34"));
            tvGameStatus.setText("Wymagana wstępna konfiguracja");
            tvGameStatus.setTextColor(Color.parseColor("#E57373"));
        } else if (!hasShortcut) {
            btnConfigure.setText("✔ Gotowy");
            btnConfigure.setEnabled(false);
            btnConfigure.setTextColor(Color.parseColor("#4CAF50"));

            btnAction.setEnabled(true);
            btnAction.setText("Otwórz Menedżer Plików (Wskaż EXE)");
            btnAction.setBackgroundColor(Color.parseColor("#1565C0"));
            tvGameStatus.setText("Wejdź w menedżer, znajdź Gothic2.exe i stwórz skrót");
            tvGameStatus.setTextColor(Color.parseColor("#FFB74D"));
        } else {
            btnConfigure.setText("✔ Gotowy");
            btnConfigure.setEnabled(false);
            btnConfigure.setTextColor(Color.parseColor("#4CAF50"));

            btnAction.setEnabled(true);
            btnAction.setText("▶ URUCHOM GOTHIC II");
            btnAction.setBackgroundColor(Color.parseColor("#2E7D32"));
            tvGameStatus.setText("Gra jest gotowa do uruchomienia");
            tvGameStatus.setTextColor(Color.parseColor("#81C784"));
        }
    }

    private void createGothicContainer() {
        if (btnConfigure != null) btnConfigure.setEnabled(false);

        MainActivity activity = (MainActivity) getActivity();
        if (activity != null) {
            try {
                activity.preloaderDialog.show(R.string.starting_up);
            } catch (Exception ignored) {}
        }

        try {
            Context context = requireContext();
            ContainerManager manager = new ContainerManager(context);
            JSONObject data = new JSONObject();
            data.put("name", "Gothic II - New Balance");
            data.put("screenSize", "1280x720");
            data.put("envVars", "BOX64_DYNAREC_BIGBLOCK=2 BOX64_DYNAREC_STRONG_MEM=0");

            manager.createContainerAsync(data, container -> {
                if (activity != null) {
                    try { activity.preloaderDialog.close(); } catch (Exception ignored) {}
                }

                if (container == null) {
                    Toast.makeText(context, "Nie udało się utworzyć kontenera!", Toast.LENGTH_LONG).show();
                    if (btnConfigure != null) btnConfigure.setEnabled(true);
                    updateUIStates();
                    return;
                }

                preferences.edit()
                        .putInt(PREF_CONTAINER_ID, container.id)
                        .apply();

                Toast.makeText(context, "Kontener utworzony pomyślnie!", Toast.LENGTH_LONG).show();
                updateUIStates();

                // Od razu otwieramy menedżer plików kontenera
                openContainerFileManager(container.id);
            });

        } catch (Exception e) {
            if (activity != null) {
                try { activity.preloaderDialog.close(); } catch (Exception ignored) {}
            }
            if (btnConfigure != null) btnConfigure.setEnabled(true);
            Toast.makeText(requireContext(), "Błąd tworzenia kontenera: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void openContainerFileManager(int containerId) {
        MainActivity activity = (MainActivity) getActivity();
        if (activity != null) {
            activity.showFragment(new ContainerFileManagerFragment(containerId));
        }
    }
}
