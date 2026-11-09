package com.winlator;

import android.content.Intent;
import android.widget.Toast;

import com.winlator.container.FileInfo;

import java.io.File;

/** File manager variant that creates and immediately launches Gothic's Winlator shortcut. */
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

    @Override
    protected String getDesktopShortcutName(FileInfo exe) {
        return "Gothic2";
    }

    @Override
    protected void onDesktopShortcutCreated(File desktopShortcut) {
        if (!isAdded() || getContext() == null) return;
        Toast.makeText(requireContext(), "Skrót Gothic II utworzony — uruchamiam grę", Toast.LENGTH_SHORT).show();
        Intent intent = new Intent(requireContext(), XServerDisplayActivity.class);
        intent.putExtra("container_id", getContainerId());
        intent.putExtra("shortcut_path", desktopShortcut.getAbsolutePath());
        startActivity(intent);
    }
}
