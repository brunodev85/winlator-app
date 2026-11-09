package com.winlator;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.PopupMenu;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.container.Container;
import com.winlator.core.FileUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ContainerFileManagerFragment extends BaseFileManagerFragment<File> {
    private int containerId;
    private File currentDir;

    public ContainerFileManagerFragment() {}

    public ContainerFileManagerFragment(int containerId) {
        this.containerId = containerId;
    }

    public ContainerFileManagerFragment(int containerId, String startPath) {
        this.containerId = containerId;
        if (startPath != null) {
            this.currentDir = new File(startPath);
        }
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null && containerId == 0) {
            containerId = savedInstanceState.getInt("container_id", 0);
        }
        
        Container container = manager.getContainerById(containerId);
        if (container != null && currentDir == null) {
            // Domyślny katalog początkowy kontenera (np. dyski Wine / dosdevices)
            currentDir = new File(requireContext().getFilesDir(), "rootfs/home/xuser/.wine/dosdevices");
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("container_id", containerId);
    }

    @Override
    protected String getHomeTitle() {
        return "Menedżer Plików Kontenera";
    }

    public int getContainerId() {
        return containerId;
    }

    @Override
    public void refreshContent() {
        super.refreshContent();
        if (currentDir == null || !currentDir.exists()) {
            currentDir = new File(requireContext().getFilesDir(), "rootfs/home/xuser/.wine/dosdevices");
        }

        File[] filesList = currentDir.listFiles();
        List<File> files = filesList != null ? new ArrayList<>(Arrays.asList(filesList)) : new ArrayList<>();
        Collections.sort(files, (f1, f2) -> {
            if (f1.isDirectory() && !f2.isDirectory()) return -1;
            if (!f1.isDirectory() && f2.isDirectory()) return 1;
            return f1.getName().compareToIgnoreCase(f2.getName());
        });

        // Ustawienie adaptera widoku listy/siatki plików w menedżerze
        FileManagerAdapter adapter = new FileManagerAdapter(files);
        recyclerView.setAdapter(adapter);

        if (emptyTextView != null) {
            emptyTextView.setVisibility(files.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    private void openFolder(File folder) {
        folderStack.push(currentDir);
        currentDir = folder;
        refreshContent();
    }

    @Override
    public boolean onOptionsMenuClicked() {
        if (!folderStack.isEmpty()) {
            currentDir = folderStack.pop();
            refreshContent();
            return true;
        }
        return super.onOptionsMenuClicked();
    }

    private void showFilePopupMenu(View view, File file) {
        PopupMenu popupMenu = new PopupMenu(requireContext(), view);
        popupMenu.getMenu().add("Otwórz");
        
        // Dodajemy opcję tworzenia skrótu Gothic dla plików .exe
        if (file.getName().toLowerCase().endsWith(".exe")) {
            popupMenu.getMenu().add("Stwórz skrót Gothic");
        }
        
        popupMenu.getMenu().add("Usuń");

        popupMenu.setOnMenuItemClickListener(item -> {
            String title = item.getTitle().toString();
            if (title.equals("Otwórz")) {
                if (file.isDirectory()) {
                    openFolder(file);
                } else {
                    FileUtils.openFile(requireActivity(), file);
                }
            } else if (title.equals("Stwórz skrót Gothic")) {
                createGothicShortcut(file);
            } else if (title.equals("Usuń")) {
                removeFile(file);
            }
            return true;
        });
        popupMenu.show();
    }

    private void createGothicShortcut(File exeFile) {
        try {
            String filePath = exeFile.getAbsolutePath();
            File containerDir = new File(requireContext().getFilesDir(), "containers/" + containerId);
            File desktopDir = new File(containerDir, "desktop");
            if (!desktopDir.exists()) {
                desktopDir.mkdirs();
            }

            File parentDir = exeFile.getParentFile();
            String parentDirPath = parentDir != null ? parentDir.getAbsolutePath() : "";

            // Generowanie pliku .desktop zgodnego ze strukturą Wine w Winlatorze
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

    // Wewnętrzny adapter listy plików
    private class FileManagerAdapter extends RecyclerView.Adapter<FileManagerAdapter.ViewHolder> {
        private final List<File> files;

        public FileManagerAdapter(List<File> files) {
            this.files = files;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.file_manager_item, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            File file = files.get(position);
            holder.nameTextView.setText(file.getName());
            
            if (file.isDirectory()) {
                holder.iconView.setImageResource(R.drawable.icon_folder);
            } else if (file.getName().toLowerCase().endsWith(".exe")) {
                holder.iconView.setImageResource(R.drawable.icon_exe);
            } else {
                holder.iconView.setImageResource(R.drawable.icon_file);
            }

            holder.itemView.setOnClickListener(v -> {
                if (file.isDirectory()) {
                    openFolder(file);
                } else {
                    showFilePopupMenu(v, file);
                }
            });

            holder.menuButton.setOnClickListener(v -> showFilePopupMenu(v, file));
        }

        @Override
        public int getItemCount() {
            return files.size();
        }

        public class ViewHolder extends RecyclerView.ViewHolder {
            public final android.widget.ImageView iconView;
            public final TextView nameTextView;
            public final View menuButton;

            public ViewHolder(@NonNull View itemView) {
                super(itemView);
                iconView = itemView.findViewById(R.id.IVFileIcon);
                nameTextView = itemView.findViewById(R.id.TVFileName);
                menuButton = itemView.findViewById(R.id.BTItemMenu);
            }
        }
    }
}
