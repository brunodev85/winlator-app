package com.winlator.core;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;

public final class ProfilePathRelocator {
    private static final String TAG = "ProfilePathRelocator";
    private static final String OWNER_ROOT = "/data/data/com.winlator/files/rootfs";
    private static final byte[] OWNER_ROOT_BYTES = OWNER_ROOT.getBytes(StandardCharsets.US_ASCII);
    private static final int CHUNK_SIZE = 1024 * 1024;

    private ProfilePathRelocator() {}

    public static boolean shouldRelocate(File destination) {
        return findRootDir(destination) != null;
    }

    private static File findRootDir(File location) {
        String path = location.getAbsolutePath().replace('\\', '/');
        int end = path.indexOf("/files/rootfs");
        return end >= 0 ? new File(path.substring(0, end + "/files/rootfs".length())) : null;
    }

    private static String createStableRoot(File rootDir) {
        File filesDir = rootDir.getParentFile();
        File dataDir = filesDir != null ? filesDir.getParentFile() : null;
        if (dataDir == null) return null;

        String stableRoot = new File(dataDir, "r").getAbsolutePath().replace('\\', '/');
        if (stableRoot.length() > OWNER_ROOT.length()) return null;

        StringBuilder result = new StringBuilder(stableRoot);
        while (result.length() < OWNER_ROOT.length()) result.append('/');
        return result.toString();
    }

    public static boolean ensureRootAlias(File rootDir) {
        String stableRoot = createStableRoot(rootDir);
        if (stableRoot == null) {
            Log.e(TAG, "Unable to create fixed-length rootfs alias for "+rootDir);
            return false;
        }

        File alias = new File(stableRoot.replaceAll("/+$", ""));
        String target = rootDir.getAbsolutePath();
        if (FileUtils.isSymlink(alias) && target.equals(FileUtils.readSymlink(alias))) return true;

        if (alias.exists() && !FileUtils.delete(alias)) {
            Log.e(TAG, "Unable to replace rootfs alias "+alias);
            return false;
        }

        FileUtils.symlink(target, alias.getAbsolutePath());
        boolean success = FileUtils.isSymlink(alias) && target.equals(FileUtils.readSymlink(alias));
        if (!success) Log.e(TAG, "Rootfs alias validation failed for "+alias+" -> "+target);
        return success;
    }

    public static String relocatePath(String path, File location) {
        if (path == null) return null;
        File rootDir = findRootDir(location);
        String stableRoot = rootDir != null ? createStableRoot(rootDir) : null;
        return stableRoot != null ? path.replace(OWNER_ROOT, stableRoot) : path;
    }

    public static boolean relocateFile(File file) {
        if (!file.isFile() || file.length() < OWNER_ROOT_BYTES.length) return true;

        int bufferSize = (int)Math.min(
            CHUNK_SIZE + OWNER_ROOT_BYTES.length - 1,
            file.length()
        );
        return relocateFile(file, new byte[bufferSize]);
    }

    private static boolean relocateFile(File file, byte[] data) {
        File rootDir = findRootDir(file);
        String stableRoot = rootDir != null ? createStableRoot(rootDir) : null;
        if (stableRoot == null) {
            Log.e(TAG, "Unable to determine stable rootfs path for "+file);
            return false;
        }
        byte[] stableRootBytes = stableRoot.getBytes(StandardCharsets.US_ASCII);

        int replacements = 0;
        try (RandomAccessFile randomAccessFile = new RandomAccessFile(file, "rw");
             FileChannel channel = randomAccessFile.getChannel()) {
            long filePosition = 0;
            long fileSize = channel.size();

            while (filePosition < fileSize) {
                int readLength = (int)Math.min(data.length, fileSize - filePosition);
                ByteBuffer readBuffer = ByteBuffer.wrap(data, 0, readLength);
                int bytesRead = channel.read(readBuffer, filePosition);
                if (bytesRead <= 0) break;

                int scanLength = Math.min(CHUNK_SIZE, bytesRead - OWNER_ROOT_BYTES.length + 1);
                for (int position = 0; position < scanLength; position++) {
                    if (data[position] != OWNER_ROOT_BYTES[0]) continue;

                    boolean match = true;
                    for (int offset = 1; offset < OWNER_ROOT_BYTES.length; offset++) {
                        if (data[position + offset] != OWNER_ROOT_BYTES[offset]) {
                            match = false;
                            break;
                        }
                    }

                    if (match) {
                        channel.write(ByteBuffer.wrap(stableRootBytes), filePosition + position);
                        replacements++;
                        position += OWNER_ROOT_BYTES.length - 1;
                    }
                }

                filePosition += CHUNK_SIZE;
            }

            if (replacements > 0) channel.force(false);
            return true;
        }
        catch (IOException | RuntimeException e) {
            Log.e(TAG, "Unable to relocate owner-profile paths in "+file, e);
            return false;
        }
    }

    public static boolean relocateTree(File root) {
        if (!root.exists()) return true;
        File rootDir = findRootDir(root);
        return rootDir != null && relocateTree(
            root,
            rootDir,
            new byte[CHUNK_SIZE + OWNER_ROOT_BYTES.length - 1]
        );
    }

    private static boolean relocateTree(File root, File rootDir, byte[] data) {
        if (FileUtils.isSymlink(root)) {
            String target = FileUtils.readSymlink(root);
            String relocatedTarget = relocatePath(target, rootDir);
            if (!relocatedTarget.equals(target)) FileUtils.symlink(relocatedTarget, root.getPath());
            return true;
        }

        if (root.isFile()) return root.length() < OWNER_ROOT_BYTES.length || relocateFile(root, data);

        File[] files = root.listFiles();
        if (files == null) return root.isDirectory();
        for (File file : files) {
            if (!relocateTree(file, rootDir, data)) return false;
        }
        return true;
    }

}
