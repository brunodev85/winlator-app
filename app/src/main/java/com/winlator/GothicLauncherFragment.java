package com.winlator;

import android.content.Context;
import android.content.Intent;
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
import java.util.Locale;

/** Launcher for the Gothic container. The selected EXE is added from the container
 * file manager using its "Dodaj skrót" context-menu action. */
public class GothicLauncherFragment extends Fragment {
    private static final String PREF_CONTAINER_ID = "gothic_container_id";
    private static final String EXPECTED_SHORTCUT_NAME = "Gothic2.desktop";

    private SharedPreferences preferences;
    private Button btnConfigure;
    private Button btnAction;
    private TextView tvGameStatus;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.activity_gothic_launcher, parent, false);
        preferences = PreferenceManager.getDefaultSharedPreferences(requireContext());

        btnConfigure = view.findViewById(R.id.btnConfigure);
        btnAction = view.findViewById(R.id.btnAction);
        tvGameStatus = view.findViewById(R.id.tvGameStatus);

        btnConfigure.setOnClickListener(v -> createGothicContainer());
        btnAction.setOnClickListener(v -> onMainAction());
        updateUIStates();
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        updateUIStates();
    }

    private Container getGothicContainer() {
        if (getContext() == null || preferences == null) return null;
        int containerId = preferences.getInt(PREF_CONTAINER_ID, -1);
        return new ContainerManager(requireContext()).getContainerById(containerId);
    }

    /** The actual container desktop is inside the container root, not files/containers/<id>. */
    @Nullable
    private File findGameShortcut(Container container) {
        if (container == null) return null;
        File desktopDir = new File(container.getUserDir(), "Desktop");
        File expected = new File(desktopDir, EXPECTED_SHORTCUT_NAME);
        if (expected.isFile()) return expected;

        File[] entries = desktopDir.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".desktop"));
        if (entries == null || entries.length == 0) return null;

        // This launcher is dedicated to Gothic. Prefer a shortcut whose name mentions it;
        // otherwise use the sole available desktop shortcut created from the selected EXE.
        for (File entry : entries) {
            if (entry.getName().toLowerCase(Locale.ROOT).contains("gothic")) return entry;
        }
        return entries.length == 1 ? entries[0] : null;
    }

    private void updateUIStates() {
        if (!isAdded() || btnConfigure == null || btnAction == null || tvGameStatus == null) return;

        Container container = getGothicContainer();
        File shortcut = findGameShortcut(container);
        if (container == null) {
            btnConfigure.setText("Konfiguruj");
            btnConfigure.setEnabled(true);
            btnConfigure.setTextColor(Color.parseColor("#8AB4F8"));
            btnAction.setEnabled(false);
            btnAction.setText("Utwórz kontener gry");
            btnAction.setBackgroundColor(Color.parseColor("#252A34"));
            tvGameStatus.setText("Wymagana wstępna konfiguracja");
            tvGameStatus.setTextColor(Color.parseColor("#E57373"));
        } else {
            btnConfigure.setText("✔ Gotowy");
            btnConfigure.setEnabled(false);
            btnConfigure.setTextColor(Color.parseColor("#4CAF50"));
            btnAction.setEnabled(true);

            if (shortcut == null) {
                btnAction.setText("Otwórz menedżer plików (wybierz EXE)");
                btnAction.setBackgroundColor(Color.parseColor("#1565C0"));
                tvGameStatus.setText("W menedżerze plików otwórz menu pliku EXE i wybierz „Dodaj skrót”");
                tvGameStatus.setTextColor(Color.parseColor("#FFB74D"));
            } else {
                btnAction.setText("▶ URUCHOM GOTHIC II");
                btnAction.setBackgroundColor(Color.parseColor("#2E7D32"));
                tvGameStatus.setText("Skrót gotowy: " + shortcut.getName());
                tvGameStatus.setTextColor(Color.parseColor("#81C784"));
            }
        }
    }

    private void onMainAction() {
        Container container = getGothicContainer();
        if (container == null) {
            Toast.makeText(requireContext(), "Najpierw utwórz kontener gry", Toast.LENGTH_SHORT).show();
            return;
        }

        File shortcut = findGameShortcut(container);
        if (shortcut == null) {
            openContainerFileManager(container.id);
        } else {
            launchGameShortcut(container, shortcut);
        }
    }

    private void launchGameShortcut(Container container, File shortcut) {
        if (!isAdded()) return;
        Intent intent = new Intent(requireContext(), XServerDisplayActivity.class);
        intent.putExtra("container_id", container.id);
        intent.putExtra("shortcut_path", shortcut.getAbsolutePath());
        startActivity(intent);
    }

    private void createGothicContainer() {
        if (!isAdded()) return;
        btnConfigure.setEnabled(false);

        MainActivity activity = (MainActivity) getActivity();
        Context context = requireContext();
        try {
            if (activity != null) activity.preloaderDialog.show(R.string.starting_up);

            ContainerManager manager = new ContainerManager(context);
            JSONObject data = new JSONObject();
            data.put("name", "Gothic II - New Balance");
            data.put("screenSize", "1280x720");
            data.put("envVars", "BOX64_DYNAREC_BIGBLOCK=2 BOX64_DYNAREC_STRONG_MEM=0");

            manager.createContainerAsync(data, container -> {
                if (!isAdded()) return;
                if (activity != null) {
                    try { activity.preloaderDialog.close(); } catch (Exception ignored) {}
                }

                if (container == null) {
                    Toast.makeText(context, "Nie udało się utworzyć kontenera!", Toast.LENGTH_LONG).show();
                    btnConfigure.setEnabled(true);
                    updateUIStates();
                    return;
                }

                preferences.edit().putInt(PREF_CONTAINER_ID, container.id).apply();
                Toast.makeText(context, "Kontener utworzony pomyślnie", Toast.LENGTH_SHORT).show();
                updateUIStates();
                openContainerFileManager(container.id);
            });
        } catch (Exception e) {
            if (activity != null) {
                try { activity.preloaderDialog.close(); } catch (Exception ignored) {}
            }
            btnConfigure.setEnabled(true);
            Toast.makeText(context, "Błąd tworzenia kontenera: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void openContainerFileManager(int containerId) {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).showFragment(new GothicContainerFileManagerFragment(containerId));
        }
    }
}
