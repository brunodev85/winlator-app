package com.winlator;

import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;

public class GothicContainerFileManagerFragment extends ContainerFileManagerFragment {

    public GothicContainerFileManagerFragment() {
        super();
    }

    public GothicContainerFileManagerFragment(int containerId) {
        super(containerId);
    }

    public GothicContainerFileManagerFragment(int containerId, String startPath) {
        super(containerId, startPath);
    }

    // Metoda pomocnicza do tworzenia pliku .desktop dla Gothica w kontenerze
    public void createGothicShortcut(File exeFile) {
        try {
            int containerId = getContainerId();
            if (containerId <= 0) {
                Toast.makeText(requireContext(), "Błąd: Nieznane ID kontenera", Toast.LENGTH_SHORT).show();
                return;
            }

            File containerDir = new File(requireContext().getFilesDir(), "containers/" + containerId);
            File desktopDir = new File(containerDir, "desktop");
            if (!desktopDir.exists()) {
                desktopDir.mkdirs();
            }

            String filePath = exeFile.getAbsolutePath();
            File parentDir = exeFile.getParentFile();
            String parentDirPath = parentDir != null ? parentDir.getAbsolutePath() : "";

            // Generowanie pliku .desktop dokładnie w takim formacie, jaki obsługuje Wine w Winlatorze
            StringBuilder sb = new StringBuilder();
            sb.append("[Desktop Entry]\n");
            sb.append("Name=Gothic2\n");
            sb.append("Exec=env WINEPREFIX=\"").append(requireContext().getFilesDir()).append("/rootfs/home/xuser/.wine\" wine ").append(filePath.replace("/", "\\\\")).append("\n");
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
