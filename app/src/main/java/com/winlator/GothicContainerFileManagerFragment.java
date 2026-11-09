package com.winlator;

import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;

public class GothicContainerFileManagerFragment extends ContainerFileManagerFragment {

    public GothicContainerFileManagerFragment() {
        super(0, null);
    }

    public GothicContainerFileManagerFragment(int containerId) {
        super(containerId);
    }

    public GothicContainerFileManagerFragment(int containerId, String startPath) {
        super(containerId, startPath);
    }

    public void createGothicShortcut(File exeFile) {
        try {
            int containerId = getContainerId();
            if (containerId <= 0) {
                Toast.makeText(requireContext(), "Błąd: Nieznane ID kontenera", Toast.LENGTH_SHORT).show();
                return;
            }

            if (exeFile == null || !exeFile.exists() || !exeFile.getName().toLowerCase().endsWith(".exe")) {
                Toast.makeText(requireContext(), "Błąd: Brak poprawnego pliku .exe", Toast.LENGTH_SHORT).show();
                return;
            }

            File containerDir = new File(requireContext().getFilesDir(), "containers/" + containerId);
            File desktopDir = new File(containerDir, "desktop");
            if (!desktopDir.exists()) {
                desktopDir.mkdirs();
            }

            String filePath = exeFile.getAbsolutePath();
            String parentDirPath = exeFile.getParentFile() != null ? exeFile.getParentFile().getAbsolutePath() : "";

            File winePrefix = new File(requireContext().getFilesDir(), "rootfs/home/xuser/.wine");
            String escapedPath = filePath.replace("\\", "\\\\").replace("\"", "\\\"");
            String execCommand = "env WINEPREFIX=\"" + winePrefix.getAbsolutePath() + "\" wine \"" + escapedPath + "\"";

            StringBuilder sb = new StringBuilder();
            sb.append("[Desktop Entry]\n");
            sb.append("Name=Gothic2\n");
            sb.append("Exec=").append(execCommand).append("\n");
            sb.append("Type=Application\n");
            sb.append("StartupNotify=true\n");
            sb.append("Path=").append(parentDirPath).append("\n");
            sb.append("Icon=4904_Gothic2.0\n");
            sb.append("StartupWMClass=gothic2.exe\n");

            File desktopFile = new File(desktopDir, "Gothic2.desktop");
            try (FileOutputStream fos = new FileOutputStream(desktopFile)) {
                fos.write(sb.toString().getBytes("UTF-8"));
            }

            Toast.makeText(requireContext(), "Utworzono skrót Gothic2.desktop!", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Błąd tworzenia skrótu: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}