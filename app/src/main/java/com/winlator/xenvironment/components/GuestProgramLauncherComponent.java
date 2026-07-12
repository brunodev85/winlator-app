package com.winlator.xenvironment.components;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Process;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.winlator.box64.Box64Preset;
import com.winlator.box64.Box64PresetManager;
import com.winlator.core.Callback;
import com.winlator.core.DefaultVersion;
import com.winlator.core.EnvVars;
import com.winlator.core.FileUtils;
import com.winlator.core.GeneralComponents;
import com.winlator.core.LocaleHelper;
import com.winlator.core.ProcessHelper;
import com.winlator.core.ProfilePathRelocator;
import com.winlator.widget.LogView;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xenvironment.EnvironmentComponent;
import com.winlator.xenvironment.RootFS;

import java.io.File;
import java.util.List;

public class GuestProgramLauncherComponent extends EnvironmentComponent {
    private static final String TAG = "GuestProgramLauncher";
    private String guestExecutable;
    private static int pid = -1;
    private EnvVars envVars;
    private String box64Preset = Box64Preset.CONSERVATIVE;
    private Callback<Integer> terminationCallback;
    private static final Object lock = new Object();

    @Override
    public void start() {
        synchronized (lock) {
            stop();
            if (!prepareBox64()) {
                if (terminationCallback != null) terminationCallback.call(-1);
                return;
            }
            copyDefaultBox64RCFile();
            pid = execGuestProgram();
            if (pid == -1 && terminationCallback != null) terminationCallback.call(-1);
        }
    }

    @Override
    public void stop() {
        synchronized (lock) {
            if (pid != -1) {
                Process.killProcess(pid);
                pid = -1;
            }
        }
    }

    public Callback<Integer> getTerminationCallback() {
        return terminationCallback;
    }

    public void setTerminationCallback(Callback<Integer> terminationCallback) {
        this.terminationCallback = terminationCallback;
    }

    public String getGuestExecutable() {
        return guestExecutable;
    }

    public void setGuestExecutable(String guestExecutable) {
        this.guestExecutable = guestExecutable;
    }

    public EnvVars getEnvVars() {
        return envVars;
    }

    public void setEnvVars(EnvVars envVars) {
        this.envVars = envVars;
    }

    public String getBox64Preset() {
        return box64Preset;
    }

    public void setBox64Preset(String box64Preset) {
        this.box64Preset = box64Preset;
    }

    private int execGuestProgram() {
        RootFS rootFS = environment.getRootFS();
        File rootDir = rootFS.getRootDir();
        Context context = environment.getContext();
        String box64Version = getBox64Version(context);
        if (box64Version.equals(DefaultVersion.BOX64) && !ProfilePathRelocator.ensureRootAlias(rootDir)) {
            Log.e(TAG, "Stable rootfs alias is unavailable");
            return -1;
        }

        EnvVars envVars = new EnvVars();
        addBox64EnvVars(envVars);
        LocaleHelper.setEnvVars(envVars);

        envVars.put("HOME", rootDir+RootFS.HOME_PATH);
        envVars.put("USER", RootFS.USER);
        envVars.put("TMPDIR", rootDir+"/tmp");
        envVars.put("DISPLAY", ":0");
        envVars.put("PATH", rootDir+rootFS.getWinePath()+"/bin:"+rootDir+"/usr/local/bin:"+rootDir+"/usr/bin");
        if (box64Version.equals(DefaultVersion.BOX64)) {
            envVars.put("LD_LIBRARY_PATH", "/system/lib64:"+context.getApplicationInfo().nativeLibraryDir);
        }
        else {
            envVars.put("LD_LIBRARY_PATH", rootFS.getLibDir().getPath());
        }
        envVars.put("BOX64_LD_LIBRARY_PATH", rootDir+"/lib/x86_64-linux-gnu");
        envVars.put("ANDROID_SYSVSHM_SERVER", rootDir+UnixSocketConfig.SYSVSHM_SERVER_PATH);
        envVars.put("WINLATOR_ROOTFS", rootDir);

        if (this.envVars != null) envVars.putAll(this.envVars);

        File shmDir = new File(rootDir, "/tmp/shm");
        if (!shmDir.isDirectory()) shmDir.mkdirs();

        String command = getBox64Executable(context, box64Version)+" "+guestExecutable;

        return ProcessHelper.exec(command, envVars, rootDir, (status) -> {
            synchronized (lock) {
                pid = -1;
            }
            if (terminationCallback != null) terminationCallback.call(status);
        });
    }

    private boolean prepareBox64() {
        Context context = environment.getContext();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String box64Version = getBox64Version(context);
        String currentBox64Version = preferences.getString("current_box64_version", "");

        if (box64Version.equals(DefaultVersion.BOX64)) {
            File nativeLibraryDir = new File(context.getApplicationInfo().nativeLibraryDir);
            boolean complete = new File(nativeLibraryDir, "libbox64launcher.so").isFile()
                && new File(nativeLibraryDir, "libbox64.so").isFile()
                && new File(nativeLibraryDir, "libglibcloader.so").isFile();
            if (!complete) Log.e(TAG, "Packaged Box64 runtime is incomplete");
            return complete;
        }

        if (!box64Version.equals(currentBox64Version)) {
            GeneralComponents.extractFile(GeneralComponents.Type.BOX64, context, box64Version, DefaultVersion.BOX64);
            preferences.edit().putString("current_box64_version", box64Version).apply();
        }
        return true;
    }

    private String getBox64Version(Context context) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        return preferences.getString("box64_version", DefaultVersion.BOX64);
    }

    private File getBox64Executable(Context context, String box64Version) {
        if (box64Version.equals(DefaultVersion.BOX64)) {
            return new File(context.getApplicationInfo().nativeLibraryDir, "libbox64launcher.so");
        }
        return new File(environment.getRootFS().getRootDir(), "/usr/local/bin/box64");
    }

    private void copyDefaultBox64RCFile() {
        Context context = environment.getContext();
        RootFS rootFS = environment.getRootFS();
        FileUtils.copy(context, "box64/default.box64rc", new File(rootFS.getRootDir(), "/etc/config.box64rc"));
    }

    private void addBox64EnvVars(EnvVars envVars) {
        Context context = environment.getContext();
        RootFS rootFS = environment.getRootFS();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        int box64Logs = preferences.getInt("box64_logs", 0);
        boolean saveToFile = preferences.getBoolean("save_logs_to_file", false);

        envVars.put("BOX64_NOBANNER", box64Logs >= 1 ? "0" : "1");
        envVars.put("BOX64_DYNAREC", "1");
        envVars.put("BOX64_UNITYPLAYER", "0");

        if (box64Logs >= 1) {
            envVars.put("BOX64_LOG", "1");
            envVars.put("BOX64_DYNAREC_MISSING", "1");

            if (box64Logs == 2) {
                envVars.put("BOX64_SHOWSEGV", "1");
                envVars.put("BOX64_DLSYM_ERROR", "1");
                envVars.put("BOX64_TRACE_FILE", "stderr");

                if (saveToFile) {
                    File parent = (new File(preferences.getString("log_file", LogView.getLogFile().getPath()))).getParentFile();
                    if (parent != null && parent.isDirectory()) {
                        File traceDir = new File(parent, "trace");
                        if (!traceDir.isDirectory()) traceDir.mkdirs();
                        FileUtils.clear(traceDir);

                        envVars.put("BOX64_TRACE_FILE", traceDir+"/box64-%pid.txt");
                    }
                }
            }
        }

        envVars.putAll(Box64PresetManager.getEnvVars(context, box64Preset));

        File box64RCFile = new File(rootFS.getRootDir(), "/etc/config.box64rc");
        envVars.put("BOX64_RCFILE", box64RCFile.getPath());
    }

    @Override
    public void onPause() {
        synchronized (lock) {
            if (pid != -1) {
                List<ProcessHelper.PStat> processes = ProcessHelper.getChildProcesses();
                for (int i = processes.size()-1; i >= 0; i--) {
                    ProcessHelper.PStat process = processes.get(i);
                    if (process.guestProcess && process.state != ProcessHelper.PState.STOPPED) {
                        ProcessHelper.suspendProcess(process.pid);
                    }
                }
            }
        }
    }

    @Override
    public void onResume() {
        synchronized (lock) {
            if (pid != -1) {
                List<ProcessHelper.PStat> processes = ProcessHelper.getChildProcesses();
                for (int i = 0; i < processes.size(); i++) {
                    ProcessHelper.PStat process = processes.get(i);
                    if (process.guestProcess && process.state == ProcessHelper.PState.STOPPED) {
                        ProcessHelper.resumeProcess(process.pid);
                    }
                }
            }
        }
    }
}