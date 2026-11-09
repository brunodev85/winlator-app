package com.winlator;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.container.Container;
import com.winlator.container.FileInfo;
import com.winlator.contentdialog.ContentDialog;
import com.winlator.contentdialog.FileInfoDialog;
import com.winlator.core.AppUtils;
import com.winlator.core.FileUtils;
import com.winlator.core.StringUtils;
import com.winlator.core.WineUtils;
import com.winlator.win32.MSIcon;
import com.winlator.win32.MSLink;
import com.winlator.win32.PEParser;
import com.winlator.xenvironment.RootFS;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class ContainerFileManagerFragment extends BaseFileManagerFragment<FileInfo> {
    private static final int MENU_ITEM_CREATE_SHORTCUT = Menu.FIRST + 100;

    private final int containerId;
    private String startPath;
    private Container container;

    public ContainerFileManagerFragment() {
        this(0, null);
    }

    public ContainerFileManagerFragment(int containerId) {
        this(containerId, null);
    }

    public ContainerFileManagerFragment(int containerId, String startPath) {
        this.containerId = containerId;
        this.startPath = startPath;
    }

    public int getContainerId() {
        return containerId;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        container = manager != null ? manager.getContainerById(containerId) : null;
        if (container == null) return;

        viewStyle = ViewStyle.valueOf(preferences.getString("container_file_manager_view_style", "GRID"));
        if (startPath != null) {
            setCurrentWorkingPath(WineUtils.unixToDOSPath(startPath, container));
            startPath = null;
        }
    }

    @Override
    public void refreshContent() {
        super.refreshContent();
        if (container == null || recyclerView == null || emptyTextView == null) return;

        FileInfo parent = !folderStack.isEmpty() ? folderStack.peek() : null;
        ArrayList<FileInfo> files = manager.loadFiles(container, parent);
        recyclerView.setAdapter(new FileInfoAdapter(files));
        emptyTextView.setVisibility(files.isEmpty() ? View.VISIBLE : View.GONE);
        updateActionBarTitle();
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater menuInflater) {
        menuInflater.inflate(R.menu.container_file_manager_menu, menu);
        refreshViewStyleMenuItem(menu.findItem(R.id.menu_item_view_style));
    }

    private void createFolder() {
        clearClipboard();
        if (folderStack.isEmpty()) return;

        ContentDialog.prompt(getContext(), R.string.new_folder, null, (name) -> {
            File file = new File(folderStack.peek().toFile(), name);
            if (file.exists()) {
                AppUtils.showToast(getContext(), R.string.there_already_file_with_that_name);
            } else if (file.mkdir()) {
                refreshContent();
            }
        });
    }

    private void instantiateClipboard(FileInfo file, boolean cutMode) {
        clearClipboard();
        clipboard = new Clipboard(new File[]{file.toFile()}, cutMode);
        pasteButton.setVisibility(View.VISIBLE);
    }

    private void addFavorite(FileInfo file) {
        Context context = getContext();
        if (context == null || container == null) return;

        File favoritesDir = new File(container.getUserDir(), context.getString(R.string.favorites));
        if (!favoritesDir.isDirectory()) favoritesDir.mkdirs();
        File targetFile = new File(favoritesDir, FileUtils.getBasename(file.name) + ".lnk");
        if (targetFile.exists()) {
            Toast.makeText(context, "Skrót już istnieje w Ulubionych", Toast.LENGTH_SHORT).show();
            return;
        }

        MSLink.LinkInfo linkInfo = new MSLink.LinkInfo();
        linkInfo.targetPath = WineUtils.unixToDOSPath(file.path, container);
        linkInfo.isDirectory = file.type == FileInfo.Type.DIRECTORY;
        if (MSLink.createFile(linkInfo, targetFile)) {
            AppUtils.showToast(context, R.string.file_added_to_favorites);
        }
    }

    /** Override to give a game shortcut a fixed name recognized by its launcher. */
    protected String getDesktopShortcutName(FileInfo exe) {
        String name = StringUtils.clearReservedChars(FileUtils.getBasename(exe.name));
        return name.isEmpty() ? "Program" : name;
    }

    /** Called after both the Windows .lnk and Winlator .desktop shortcut are created. */
    protected void onDesktopShortcutCreated(File desktopShortcut) {
        Context context = getContext();
        if (context != null) Toast.makeText(context, "Dodano skrót: " + FileUtils.getBasename(desktopShortcut.getName()), Toast.LENGTH_SHORT).show();
        refreshContent();
    }

    private void createDesktopShortcut(FileInfo file) {
        Context context = getContext();
        if (context == null || container == null) return;

        File exeFile = file.toFile();
        if (!exeFile.isFile() || !file.name.toLowerCase(Locale.ROOT).endsWith(".exe")) return;

        String shortcutName = getDesktopShortcutName(file);
        if (shortcutName == null || shortcutName.trim().isEmpty()) shortcutName = "Program";
        shortcutName = StringUtils.clearReservedChars(shortcutName);

        File desktopDir = new File(container.getUserDir(), "Desktop");
        if (!desktopDir.isDirectory() && !desktopDir.mkdirs()) {
            Toast.makeText(context, "Nie udało się utworzyć pulpitu kontenera", Toast.LENGTH_LONG).show();
            return;
        }

        File desktopFile = new File(desktopDir, shortcutName + ".desktop");
        File linkFile = new File(desktopDir, shortcutName + ".lnk");
        if (desktopFile.exists() || linkFile.exists()) {
            Toast.makeText(context, "Skrót o tej nazwie już istnieje", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            String exeDosPath = WineUtils.unixToDOSPath(exeFile.getAbsolutePath(), container);
            MSLink.LinkInfo linkInfo = new MSLink.LinkInfo();
            linkInfo.targetPath = exeDosPath;
            linkInfo.isDirectory = false;
            if (!MSLink.createFile(linkInfo, linkFile)) {
                Toast.makeText(context, "Nie udało się utworzyć skrótu Windows", Toast.LENGTH_LONG).show();
                return;
            }

            String linkDosPath = WineUtils.unixToDOSPath(linkFile.getAbsolutePath(), container);
            String desktopEntry = "[Desktop Entry]\n"
                    + "Name=" + shortcutName + "\n"
                    + "Exec=wine start.exe " + StringUtils.escapeDOSPath(linkDosPath) + "\n"
                    + "Type=Application\n"
                    + "StartupNotify=true\n";
            try (FileOutputStream output = new FileOutputStream(desktopFile)) {
                output.write(desktopEntry.getBytes("UTF-8"));
            }
            onDesktopShortcutCreated(desktopFile);
        } catch (Exception e) {
            linkFile.delete();
            desktopFile.delete();
            Toast.makeText(context, "Błąd tworzenia skrótu: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void pasteFiles() {
        if (folderStack.isEmpty()) {
            clearClipboard();
            AppUtils.showToast(getContext(), R.string.you_cannot_paste_files_here);
            return;
        }
        clipboard.targetDir = folderStack.peek().toFile();
        super.pasteFiles();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem menuItem) {
        int itemId = menuItem.getItemId();
        switch (itemId) {
            case R.id.menu_item_home:
                folderStack.clear();
                refreshContent();
                return true;
            case R.id.menu_item_view_style:
                setViewStyle(viewStyle == ViewStyle.GRID ? ViewStyle.LIST : ViewStyle.GRID);
                preferences.edit().putString("container_file_manager_view_style", viewStyle.name()).apply();
                refreshViewStyleMenuItem(menuItem);
                return true;
            case R.id.menu_item_new_folder:
                createFolder();
                return true;
            default:
                return super.onOptionsItemSelected(menuItem);
        }
    }

    private void setCurrentWorkingPath(String dosPath) {
        dosPath = StringUtils.removeEndSlash(dosPath);
        String[] names = dosPath.split("\\\\");
        String basePath = "";
        folderStack.clear();

        for (String name : names) {
            if (!name.isEmpty()) {
                String unixPath = WineUtils.dosToUnixPath(basePath + name, container);
                if (basePath.isEmpty() && name.matches("[A-Za-z]:")) {
                    folderStack.push(new FileInfo(container, name, unixPath, FileInfo.Type.DRIVE));
                } else {
                    folderStack.push(new FileInfo(container, unixPath, FileInfo.Type.DIRECTORY));
                }
                basePath += name + "\\";
            }
        }
        updateActionBarTitle();
    }

    private String getCurrentWorkingPath() {
        if (folderStack.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < folderStack.size(); i++) {
            if (i > 0) sb.append("\\");
            sb.append(folderStack.elementAt(i).getDisplayName());
        }
        if (folderStack.size() == 1) sb.append("\\");
        return sb.toString();
    }

    private void updateActionBarTitle() {
        Activity activity = getActivity();
        if (!(activity instanceof AppCompatActivity)) return;
        ActionBar actionBar = ((AppCompatActivity) activity).getSupportActionBar();
        if (actionBar == null) return;

        if (!folderStack.isEmpty()) {
            actionBar.setHomeAsUpIndicator(R.drawable.icon_action_bar_back);
            actionBar.setTitle(getCurrentWorkingPath());
        } else {
            actionBar.setHomeAsUpIndicator(R.drawable.icon_action_bar_menu);
            actionBar.setTitle(getHomeTitle());
        }
    }

    @Override
    protected String getHomeTitle() {
        return container != null ? container.getName() : "";
    }

    private static class LoadIconTask {
        private final Executor executor = Executors.newSingleThreadExecutor();
        private final ContainerFileManagerFragment fragment;
        private final WeakReference<ImageView> imageViewWeakRef;
        private volatile boolean canceled;

        private LoadIconTask(ContainerFileManagerFragment fragment, ImageView imageView) {
            this.fragment = fragment;
            imageViewWeakRef = new WeakReference<>(imageView);
        }

        public void loadAsync(FileInfo file) {
            executor.execute(() -> {
                Object icon = fragment.getIconForFile(file);
                ImageView imageView = imageViewWeakRef.get();
                if (imageView != null && !canceled) {
                    imageView.post(() -> {
                        if (canceled) return;
                        if (icon instanceof Bitmap) imageView.setImageBitmap((Bitmap) icon);
                        else imageView.setImageResource((Integer) icon);
                    });
                }
            });
        }

        public void cancel() {
            canceled = true;
        }
    }

    private class FileInfoAdapter extends RecyclerView.Adapter<FileInfoAdapter.ViewHolder> {
        private final List<FileInfo> data;

        private class ViewHolder extends RecyclerView.ViewHolder {
            private final ImageView runButton;
            private final ImageView menuButton;
            private final ImageView imageView;
            private final TextView title;
            private final TextView subtitle;

            private ViewHolder(View view) {
                super(view);
                imageView = view.findViewById(R.id.ImageView);
                title = view.findViewById(R.id.TVTitle);
                subtitle = view.findViewById(R.id.TVSubtitle);
                runButton = view.findViewById(R.id.BTRun);
                menuButton = view.findViewById(R.id.BTMenu);
            }
        }

        FileInfoAdapter(List<FileInfo> data) {
            this.data = data;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            int resource = viewStyle == ViewStyle.LIST ? R.layout.file_list_item : R.layout.file_grid_item;
            return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(resource, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            Context context = getContext();
            FileInfo item = data.get(position);
            FileInfo.Type type = item.type;
            if (item.type == FileInfo.Type.DRIVE) {
                holder.title.setText(context.getString(R.string.drive) + " (" + item.name + ")");
            } else {
                MSLink.LinkInfo linkInfo = item.getLinkinfo();
                if (linkInfo != null && linkInfo.isDirectory) type = FileInfo.Type.DIRECTORY;
                holder.title.setText(item.getDisplayName());
            }

            holder.subtitle.setVisibility(View.GONE);
            holder.runButton.setImageResource(R.drawable.icon_open);
            if (type == FileInfo.Type.DIRECTORY && !folderStack.isEmpty()) {
                holder.subtitle.setText(item.getItemCount() + " " + context.getString(R.string.items));
                holder.subtitle.setVisibility(View.VISIBLE);
            } else if (type == FileInfo.Type.FILE) {
                holder.runButton.setImageResource(R.drawable.icon_run);
                holder.subtitle.setText(StringUtils.formatBytes(item.getSize()));
                holder.subtitle.setVisibility(View.VISIBLE);
            }

            if (type == FileInfo.Type.FILE) {
                holder.imageView.setImageResource(R.drawable.container_file);
                LoadIconTask task = (LoadIconTask) holder.imageView.getTag();
                if (task != null) task.cancel();
                task = new LoadIconTask(ContainerFileManagerFragment.this, holder.imageView);
                holder.imageView.setTag(task);
                task.loadAsync(item);
            } else {
                holder.imageView.setImageResource((Integer) getIconForFile(item));
            }

            holder.imageView.setOnClickListener(v -> openFile(item));
            holder.runButton.setOnClickListener(v -> openFile(item));
            holder.menuButton.setOnClickListener(v -> showListItemMenu(v, item));
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        private void showListItemMenu(View anchorView, FileInfo file) {
            Context context = getContext();
            if (context == null) return;
            PopupMenu listItemMenu = new PopupMenu(context, anchorView);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) listItemMenu.setForceShowIcon(true);
            listItemMenu.inflate(R.menu.file_manager_popup_menu);

            Menu menu = listItemMenu.getMenu();
            menu.findItem(R.id.menu_item_settings).setVisible(false);
            if (folderStack.isEmpty()) {
                menu.findItem(R.id.menu_item_cut).setVisible(false);
                menu.findItem(R.id.menu_item_remove).setVisible(false);
                menu.findItem(R.id.menu_item_rename).setVisible(false);
                menu.findItem(R.id.menu_item_add_favorite).setVisible(false);
            } else if ("Favorites".equals(folderStack.peek().name)) {
                menu.findItem(R.id.menu_item_add_favorite).setVisible(false);
            }

            MenuItem createShortcutItem = menu.add(Menu.NONE, MENU_ITEM_CREATE_SHORTCUT, Menu.NONE, "Dodaj skrót");
            createShortcutItem.setVisible(file.type == FileInfo.Type.FILE
                    && file.name.toLowerCase(Locale.ROOT).endsWith(".exe"));

            listItemMenu.setOnMenuItemClickListener(menuItem -> {
                switch (menuItem.getItemId()) {
                    case R.id.menu_item_copy:
                    case R.id.menu_item_cut:
                        instantiateClipboard(file, menuItem.getItemId() == R.id.menu_item_cut);
                        break;
                    case R.id.menu_item_remove:
                        clearClipboard();
                        ContentDialog.confirm(context, R.string.do_you_want_to_remove_this_file, () -> removeFile(file.toFile()));
                        break;
                    case R.id.menu_item_rename:
                        clearClipboard();
                        ContentDialog.prompt(context, R.string.rename, file.name, newName -> {
                            file.renameTo(newName);
                            refreshContent();
                        });
                        break;
                    case R.id.menu_item_add_favorite:
                        addFavorite(file);
                        break;
                    case R.id.menu_item_info:
                        new FileInfoDialog(context, file, container).show();
                        break;
                    case MENU_ITEM_CREATE_SHORTCUT:
                        createDesktopShortcut(file);
                        break;
                }
                return true;
            });
            listItemMenu.show();
        }

        private void openFile(FileInfo file) {
            Activity activity = getActivity();
            if (activity == null) return;
            MSLink.LinkInfo linkInfo = file.getLinkinfo();
            boolean isFile = linkInfo != null ? !linkInfo.isDirectory : file.type == FileInfo.Type.FILE;
            if (isFile) {
                Intent intent = new Intent(activity, XServerDisplayActivity.class);
                intent.putExtra("container_id", container.id);
                intent.putExtra("exec_path", file.path);
                activity.startActivity(intent);
            } else {
                folderStack.push(file);
                refreshContent();
            }
        }
    }

    public Object getIconForFile(FileInfo file) {
        if (file.type == FileInfo.Type.DIRECTORY) {
            Context context = getContext();
            if (context == null) return R.drawable.container_folder;
            if (file.path.endsWith(RootFS.USER + "/" + context.getString(R.string.documents))) return R.drawable.container_folder_documents;
            if (file.path.endsWith(RootFS.USER + "/" + context.getString(R.string.favorites))) return R.drawable.container_folder_favorites;
            return R.drawable.container_folder;
        }
        if (file.type == FileInfo.Type.DRIVE) return R.drawable.container_drive;

        String extension = FileUtils.getExtension(file.path);
        switch (extension) {
            case "exe": {
                Bitmap bitmap = PEParser.extractIcon(file.toFile());
                return bitmap != null ? bitmap : R.drawable.container_file_window;
            }
            case "bat":
                return R.drawable.container_file_window;
            case "ico": {
                Bitmap bitmap = MSIcon.decodeFile(file.toFile());
                if (bitmap != null) return bitmap;
                break;
            }
            case "dll":
                return R.drawable.container_file_library;
            case "lnk": {
                MSLink.LinkInfo linkInfo = file.getLinkinfo();
                if (linkInfo != null) {
                    if (linkInfo.isDirectory) return R.drawable.container_folder;
                    String targetPath = linkInfo.iconLocation != null ? linkInfo.iconLocation : linkInfo.targetPath;
                    targetPath = WineUtils.dosToUnixPath(targetPath, container);
                    Bitmap bitmap = targetPath.endsWith(".ico")
                            ? MSIcon.decodeFile(new File(targetPath))
                            : PEParser.extractIcon(new File(targetPath), linkInfo.iconIndex);
                    if (bitmap != null) return bitmap;
                }
                return R.drawable.container_file_link;
            }
        }
        return R.drawable.container_file;
    }
}
