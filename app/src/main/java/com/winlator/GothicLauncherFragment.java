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
import com.winlator.core.Callback;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

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

        return view;
    }

    private void updateUIStates() {
        Context context = getContext();
        if (context == null || btnConfigure == null || btnAction == null || tvGameStatus == null) return;

        ContainerManager manager = new ContainerManager(context);
        int containerId = preferences.getInt(PREF_CONTAINER_ID, -1);
        Container container = manager.getContainerById(containerId);
        String savedPath = preferences.getString(PREF_EXE_PATH, "");

        if (container == null) {
            btnConfigure.setText("Konfiguruj");
            btnConfigure.setEnabled(true);
            btnConfigure.setTextColor(Color.parseColor("#8AB4F8"));

            btnAction.setEnabled(false);
            btnAction.setText("Wybierz plik .exe gry");
            btnAction.setBackgroundColor(Color.parseColor("#252A34"));
            tvGameStatus.setText("Wymagana wstępna konfiguracja");
            tvGameStatus.setTextColor(Color.parseColor("#E57373"));
        } else if (savedPath.isEmpty()) {
            btnConfigure.setText("✔ Gotowy");
            btnConfigure.setEnabled(false);
            btnConfigure.setTextColor(Color.parseColor("#4CAF50"));

            btnAction.setEnabled(true);
            btnAction.setText("Wskaż plik Gothic2.exe");
            btnAction.setBackgroundColor(Color.parseColor("#1565C0"));
            tvGameStatus.setText("Kontener utworzony – wybierz plik gry");
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
                        .remove(PREF_EXE_PATH)
                        .apply();

                Toast.makeText(context, "Kontener utworzony pomyślnie!", Toast.LENGTH_LONG).show();
                updateUIStates();
            });

        } catch (Exception e) {
            if (activity != null) {
                try { activity.preloaderDialog.close(); } catch (Exception ignored) {}
            }
            if (btnConfigure != null) btnConfigure.setEnabled(true);
            Toast.makeText(requireContext(), "Błąd tworzenia kontenera: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void openFileSelector() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");

        MainActivity activity = (MainActivity) getActivity();
        if (activity != null) {
            activity.setOpenFileCallback(new Callback<Uri>() {
                @Override
                public void call(Uri uri) {
                    handleSelectedUri(uri);
                }
            });
            activity.startActivityForResult(intent, MainActivity.OPEN_FILE_REQUEST_CODE);
        }
    }

    private void handleSelectedUri(Uri uri) {
        Context context = getContext();
        if (uri == null || context == null) return;

        try {
            final int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            context.getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (Exception ignored) {}

        String path = getRealPathFromURI(context, uri);

        if (path == null || path.isEmpty()) {
            Toast.makeText(context, "Nie można odczytać ścieżki pliku!", Toast.LENGTH_LONG).show();
            return;
        }

        int containerId = preferences.getInt(PREF_CONTAINER_ID, -1);
        ContainerManager manager = new ContainerManager(context);
        Container container = manager.getContainerById(containerId);

        if (container == null) {
            Toast.makeText(context, "Najpierw utwórz kontener!", Toast.LENGTH_LONG).show();
            return;
        }

        createDesktopShortcut(context, containerId, path);

        preferences.edit().putString(PREF_EXE_PATH, path).apply();
        updateUIStates();

        Toast.makeText(context, "Wybrano plik i utworzono skrót Gothica!", Toast.LENGTH_SHORT).show();
    }

    private void createDesktopShortcut(Context context, int containerId, String exePath) {
        try {
            File containerDir = new File(context.getFilesDir(), "containers/" + containerId);
            File desktopDir = new File(containerDir, "desktop");
            if (!desktopDir.exists()) {
                desktopDir.mkdirs();
            }

            File exeFile = new File(exePath);
            File parentDir = exeFile.getParentFile();

            String wineExecPath = "z:/" + exePath.replaceFirst("^/+", "").replace('/', '\\');
            String dosDevicesPath = context.getFilesDir() + "/rootfs/home/xuser/.wine/dosdevices/z:/" + 
                    (parentDir != null ? parentDir.getAbsolutePath().replaceFirst("^/+", "").replace('/', '\\') : "");

            StringBuilder sb = new StringBuilder();
            sb.append("[Desktop Entry]\n");
            sb.append("Name=Gothic2\n");
            sb.append("Exec=env WINEPREFIX=\"").append(context.getFilesDir()).append("/rootfs/home/xuser/.wine\" wine ").append(wineExecPath).append("\n");
            sb.append("Type=Application\n");
            sb.append("StartupNotify=true\n");
            sb.append("Path=").append(dosDevicesPath).append("\n");
            sb.append("Icon=4904_Gothic2.0\n");
            sb.append("StartupWMClass=gothic2.exe\n");

            File desktopFile = new File(desktopDir, "Gothic2.desktop");
            try (FileOutputStream fos = new FileOutputStream(desktopFile)) {
                fos.write(sb.toString().getBytes("UTF-8"));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String getRealPathFromURI(Context context, Uri uri) {
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            return uri.getPath();
        }

        try {
            String uriString = uri.toString();
            if (uriString.contains("raw%3A")) {
                String decoded = Uri.decode(uriString);
                int idx = decoded.indexOf("raw:");
                if (idx != -1) {
                    return decoded.substring(idx + 4);
                }
            }
            if (uriString.contains("primary:")) {
                String split = uriString.split("primary:")[1];
                String decoded = Uri.decode(split);
                return android.os.Environment.getExternalStorageDirectory().getAbsolutePath() + "/" + decoded;
            }
        } catch (Exception ignored) {}

        try {
            File cacheFile = new File(context.getCacheDir(), "gothic_launch.exe");
            try (InputStream in = context.getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(cacheFile)) {
                byte[] buffer = new byte[16384];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
            return cacheFile.getAbsolutePath();
        } catch (Exception e) {
            e.printStackTrace();
        }

        return uri.toString();
    }

    private void launchGame() {
        Context context = getContext();
        if (context == null) return;

        int containerId = preferences.getInt(PREF_CONTAINER_ID, -1);
        String exePath = preferences.getString(PREF_EXE_PATH, "");

        if (containerId <= 0 || exePath.isEmpty()) {
            Toast.makeText(context, "Nie znaleziono kontenera lub pliku EXE!", Toast.LENGTH_LONG).show();
            return;
        }

        ContainerManager manager = new ContainerManager(context);
        Container container = manager.getContainerById(containerId);

        if (container == null) {
            Toast.makeText(context, "Kontener nie istnieje. Skonfiguruj go ponownie!", Toast.LENGTH_SHORT).show();
            preferences.edit().remove(PREF_CONTAINER_ID).remove(PREF_EXE_PATH).apply();
            updateUIStates();
            return;
        }

        // Wskazujemy bezpośrednio ścieżkę do wygenerowanego pliku .desktop
        File containerDir = new File(context.getFilesDir(), "containers/" + containerId);
        File desktopFile = new File(containerDir, "desktop/Gothic2.desktop");

        try {
            Intent intent = new Intent(context, XServerDisplayActivity.class);
            intent.putExtra("container_id", containerId);
            intent.putExtra("shortcut_path", desktopFile.getAbsolutePath());
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(context, "Nie można uruchomić Gothica: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
