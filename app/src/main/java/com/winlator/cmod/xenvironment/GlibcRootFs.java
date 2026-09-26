package com.winlator.cmod.xenvironment;

import android.content.Context;

import androidx.annotation.NonNull;

import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.WineInfo;

import java.io.File;
import java.io.IOException;

/**
 * Optional glibc runtime (Linux side taken from brunodev85/winlator), used by containers whose
 * wine version is {@link #WINE_VERSION}. The bionic ImageFs keeps hosting the containers
 * (wineprefixes), this rootfs only provides glibc, box64 (glibc build), wine and Mesa.
 *
 * The upstream rootfs is compiled with the absolute prefix {@link #ORIGINAL_PREFIX} baked into
 * ELF interpreters, glibc itself (ld.so.cache, gconv, /etc files), X11/pulse/alsa/fontconfig and
 * more. To stay independent from the package name (renamed or cloned APKs), the prefix is
 * rewritten on device at install time to the real location of this rootfs. The replacement keeps
 * the exact byte length by padding with extra slashes ("/a/b////g" resolves like "/a/b/g"),
 * so binaries stay valid. This only works while the real path is not longer than the original.
 */
public class GlibcRootFs {
    public static final String ORIGINAL_PREFIX = "/data/data/com.winlator/files/rootfs";
    public static final String WINE_VERSION = "wine-10.10-glibc-x86_64";
    public static final int LATEST_VERSION = 1; // increment when the bundled glibc assets change
    public static final String ASSETS_DIR = "glibc";
    private static final String ROOT_DIR_NAME = "g";
    private final File rootDir;
    private final boolean supported;

    private GlibcRootFs(File rootDir, boolean supported) {
        this.rootDir = rootDir;
        this.supported = supported;
    }

    public static GlibcRootFs find(Context context) {
        File dataDir = context.getDataDir();
        File legacyDataDir = new File("/data/data/" + context.getPackageName());
        File[] candidates = {
            isSameDir(legacyDataDir, dataDir) ? new File(legacyDataDir, ROOT_DIR_NAME) : null,
            new File(dataDir, ROOT_DIR_NAME)
        };

        for (File candidate : candidates) {
            if (candidate != null && candidate.getPath().length() <= ORIGINAL_PREFIX.length()) {
                return new GlibcRootFs(candidate, true);
            }
        }
        return new GlibcRootFs(new File(dataDir, ROOT_DIR_NAME), false);
    }

    private static boolean isSameDir(File a, File b) {
        try {
            return a.getCanonicalPath().equals(b.getCanonicalPath());
        }
        catch (IOException e) {
            return false;
        }
    }

    /** Whether the rootfs path is short enough for the in-place prefix relocation. */
    public boolean isSupported() {
        return supported;
    }

    /** Longest package name that still fits the relocation for the given app data dir prefix. */
    public static int getMaxPackageNameLength() {
        return ORIGINAL_PREFIX.length() - "/data/data/".length() - ("/" + ROOT_DIR_NAME).length();
    }

    /** Same length as {@link #ORIGINAL_PREFIX}, resolving to {@link #getRootDir()}. */
    public String getRelocatedPrefix() {
        String path = rootDir.getPath();
        int padding = ORIGINAL_PREFIX.length() - path.length();
        StringBuilder sb = new StringBuilder(rootDir.getParent());
        for (int i = 0; i <= padding; i++) sb.append('/');
        sb.append(rootDir.getName());
        return sb.toString();
    }

    public File getRootDir() {
        return rootDir;
    }

    public boolean isValid() {
        return rootDir.isDirectory() && getVersionFile().exists() && new File(rootDir, "/usr/local/bin/box64").exists();
    }

    public int getVersion() {
        File versionFile = getVersionFile();
        try {
            return versionFile.exists() ? Integer.parseInt(FileUtils.readLines(versionFile).get(0).trim()) : 0;
        }
        catch (NumberFormatException | IndexOutOfBoundsException e) {
            return 0;
        }
    }

    public void createVersionFile(int version) {
        File versionFile = getVersionFile();
        versionFile.getParentFile().mkdirs();
        FileUtils.writeString(versionFile, String.valueOf(version));
    }

    private File getVersionFile() {
        return new File(rootDir, ".winlator/.glibc_version");
    }

    public String getWinePath() {
        return rootDir + "/opt/wine";
    }

    public File getLibDir() {
        return new File(rootDir, "/usr/lib");
    }

    public File getTmpDir() {
        return new File(rootDir, "/tmp");
    }

    public File getBox64File() {
        return new File(rootDir, "/usr/local/bin/box64");
    }

    public File getBox64RCFile() {
        return new File(rootDir, "/etc/config.box64rc");
    }

    public WineInfo getWineInfo() {
        return new WineInfo("wine", "10.10", "x86_64", getWinePath());
    }

    public static boolean isGlibcWineVersion(String wineVersion) {
        return WINE_VERSION.equals(wineVersion);
    }

    @NonNull
    @Override
    public String toString() {
        return rootDir.getPath();
    }
}
