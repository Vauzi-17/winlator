package com.winlator.cmod.xenvironment;

import android.content.Context;
import android.util.Log;

import com.winlator.cmod.core.Callback;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.TarCompressorUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Set;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public abstract class GlibcRootFsInstaller {
    private static final String TAG = "GlibcRootFsInstaller";
    // Rough uncompressed size of rootfs.tzst, only used for progress reporting.
    private static final long ESTIMATED_ROOTFS_SIZE = 420L * 1024 * 1024;

    /**
     * Installs (or updates) the glibc rootfs when needed. Blocking, call it off the UI thread.
     * @param onProgress optional, receives 0-100
     * @return true when a usable glibc rootfs is present afterwards
     */
    public static synchronized boolean installIfNeeded(Context context, Callback<Integer> onProgress) {
        GlibcRootFs rootFs = GlibcRootFs.find(context);
        if (!rootFs.isSupported()) {
            Log.e(TAG, "Package name too long for glibc relocation: " + rootFs.getRootDir());
            return false;
        }
        if (rootFs.isValid() && rootFs.getVersion() >= GlibcRootFs.LATEST_VERSION) return true;
        return install(context, rootFs, onProgress);
    }

    private static boolean install(Context context, GlibcRootFs rootFs, Callback<Integer> onProgress) {
        File rootDir = rootFs.getRootDir();
        FileUtils.delete(rootDir);
        rootDir.mkdirs();

        final List<File> extractedFiles = new ArrayList<>();
        final AtomicLong totalSize = new AtomicLong();
        final String rootPath = rootDir.getPath();

        boolean success = extractAsset(context, "rootfs.tzst", rootDir, (file, size) -> {
            extractedFiles.add(file);
            if (size > 0 && onProgress != null) {
                onProgress.call((int)Math.min(95, totalSize.addAndGet(size) * 100 / ESTIMATED_ROOTFS_SIZE));
            }
            return file;
        });

        // Only the Linux side of the patches is used, the wineprefix comes from the containers.
        success = success && extractAsset(context, "rootfs_patches.tzst", rootDir, (file, size) -> {
            String relativePath = file.getPath().substring(rootPath.length()).replace("/./", "/");
            if (relativePath.startsWith("/home") || relativePath.startsWith("/opt/apps")) return null;
            extractedFiles.add(file);
            return file;
        });

        for (String asset : new String[]{"box64.tzst", "turnip.tzst", "zink.tzst", "vortek.tzst"}) {
            success = success && extractAsset(context, asset, rootDir, (file, size) -> {
                extractedFiles.add(file);
                return file;
            });
        }

        if (!success) {
            Log.e(TAG, "Unable to extract glibc runtime assets");
            FileUtils.delete(rootDir);
            return false;
        }

        File box64RCFile = rootFs.getBox64RCFile();
        FileUtils.copy(context, GlibcRootFs.ASSETS_DIR + "/default.box64rc", box64RCFile);
        extractedFiles.add(box64RCFile);

        relocate(extractedFiles, GlibcRootFs.ORIGINAL_PREFIX, rootFs.getRelocatedPrefix(), rootPath, readRelocateList(context, rootPath));

        rootFs.getTmpDir().mkdirs();
        FileUtils.chmod(rootFs.getBox64File(), 0771);
        rootFs.createVersionFile(GlibcRootFs.LATEST_VERSION);
        if (onProgress != null) onProgress.call(100);
        return true;
    }

    /** Adds Vortek's client ICD to a rootfs installed before it was bundled. Blocking. */
    public static synchronized boolean installVortekIfNeeded(Context context) {
        GlibcRootFs rootFs = GlibcRootFs.find(context);
        if (!rootFs.isValid()) return false;
        File rootDir = rootFs.getRootDir();
        if (rootFs.getVortekIcdFile().isFile() && new File(rootDir, "usr/lib/libvulkan_vortek.so").isFile()) return true;

        final List<File> extractedFiles = new ArrayList<>();
        if (!extractAsset(context, "vortek.tzst", rootDir, (file, size) -> {
            extractedFiles.add(file);
            return file;
        })) {
            Log.e(TAG, "Unable to extract the Vortek driver");
            return false;
        }

        relocate(extractedFiles, GlibcRootFs.ORIGINAL_PREFIX, rootFs.getRelocatedPrefix(), rootDir.getPath(), null);
        return true;
    }

    private static boolean extractAsset(Context context, String name, File rootDir, com.winlator.cmod.core.OnExtractFileListener listener) {
        return TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, GlibcRootFs.ASSETS_DIR + "/" + name, rootDir, listener);
    }

    /** Absolute paths of the files known (at build time) to contain the prefix, or null to scan everything. */
    private static Set<String> readRelocateList(Context context, String rootPath) {
        try (InputStream in = context.getAssets().open(GlibcRootFs.ASSETS_DIR + "/relocate.txt")) {
            Set<String> paths = new HashSet<>();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) paths.add(new File(rootPath, line.trim()).getPath());
            }
            return paths;
        }
        catch (IOException e) {
            return null;
        }
    }

    /**
     * Rewrites every occurrence of {@code from} with {@code paddedTo} (same length) inside regular
     * files, and symlinks pointing into {@code from} to {@code realTo}.
     */
    static void relocate(List<File> files, String from, String paddedTo, String realTo, Set<String> onlyPaths) {
        byte[] needle = from.getBytes(StandardCharsets.US_ASCII);
        byte[] replacement = paddedTo.getBytes(StandardCharsets.US_ASCII);
        if (needle.length != replacement.length) throw new IllegalArgumentException("Relocated prefix must keep the same length");

        int patchedFiles = 0;
        for (File file : files) {
            try {
                if (FileUtils.isSymlink(file)) {
                    String target = FileUtils.readSymlink(file);
                    if (target.startsWith(from)) {
                        FileUtils.symlink(realTo + target.substring(from.length()), file.getPath());
                    }
                }
                else if (file.isFile()) {
                    String path = file.getPath().replace("/./", "/");
                    // default.box64rc is copied, not extracted, so it is not in the build-time list.
                    boolean fromArchive = !path.equals(new File(realTo, "etc/config.box64rc").getPath());
                    if (onlyPaths != null && fromArchive && !onlyPaths.contains(path)) continue;
                    if (patchFile(file, needle, replacement)) patchedFiles++;
                }
            }
            catch (IOException e) {
                Log.e(TAG, "Unable to relocate " + file, e);
            }
        }
        Log.d(TAG, "Relocated " + patchedFiles + " files from " + from + " to " + paddedTo);
    }

    private static boolean patchFile(File file, byte[] needle, byte[] replacement) throws IOException {
        List<Long> offsets = findOccurrences(file, needle);
        if (offsets.isEmpty()) return false;

        if (!file.canWrite()) FileUtils.chmod(file, 0771);
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            for (long offset : offsets) {
                raf.seek(offset);
                raf.write(replacement);
            }
        }
        return true;
    }

    private static List<Long> findOccurrences(File file, byte[] needle) throws IOException {
        List<Long> offsets = new ArrayList<>();
        final int overlap = needle.length - 1;
        byte[] buffer = new byte[1 << 20];
        long bufferStart = 0; // file offset of buffer[0]
        int filled = 0;

        try (InputStream in = new FileInputStream(file)) {
            int read;
            while ((read = in.read(buffer, filled, buffer.length - filled)) != -1) {
                filled += read;
                if (filled < buffer.length) continue;
                scan(buffer, filled, needle, bufferStart, offsets);
                System.arraycopy(buffer, filled - overlap, buffer, 0, overlap);
                bufferStart += filled - overlap;
                filled = overlap;
            }
            scan(buffer, filled, needle, bufferStart, offsets);
        }
        // The carried-over overlap is one byte shorter than the needle, so no match is reported twice.
        return offsets;
    }

    private static void scan(byte[] buffer, int length, byte[] needle, long bufferStart, List<Long> offsets) {
        byte first = needle[0];
        int last = length - needle.length;
        outer:
        for (int i = 0; i <= last; i++) {
            if (buffer[i] != first) continue;
            for (int j = 1; j < needle.length; j++) {
                if (buffer[i + j] != needle[j]) continue outer;
            }
            offsets.add(bufferStart + i);
            i += needle.length - 1;
        }
    }
}
