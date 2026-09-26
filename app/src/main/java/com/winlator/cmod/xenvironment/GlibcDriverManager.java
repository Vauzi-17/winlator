package com.winlator.cmod.xenvironment;

import android.content.Context;
import android.net.Uri;

import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.TarCompressorUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Vulkan drivers for the glibc runtime. The bundled Turnip lives in the glibc rootfs, imported
 * ones (.tzst or .zip, any layout) are stored per name with an ICD json generated at import time,
 * so they do not depend on the package name.
 */
public class GlibcDriverManager {
    public static final String BUNDLED = "";
    public static final String BUNDLED_NAME = "Turnip 26.2.0 (bundled)";
    private static final String ICD_FILE_NAME = "icd.aarch64.json";
    private final Context context;

    public static class ImportException extends Exception {
        public ImportException(String message) {
            super(message);
        }
    }

    public GlibcDriverManager(Context context) {
        this.context = context;
    }

    public File getDriversDir() {
        return new File(context.getFilesDir(), "glibc_drivers");
    }

    public File getDriverDir(String name) {
        return new File(getDriversDir(), name);
    }

    public List<String> getInstalledDrivers() {
        ArrayList<String> names = new ArrayList<>();
        File[] dirs = getDriversDir().listFiles(file -> file.isDirectory() && new File(file, ICD_FILE_NAME).isFile());
        if (dirs != null) for (File dir : dirs) names.add(dir.getName());
        names.sort(String::compareToIgnoreCase);
        return names;
    }

    public boolean isInstalled(String name) {
        return name != null && !name.isEmpty() && new File(getDriverDir(name), ICD_FILE_NAME).isFile();
    }

    /** ICD json to use for the given driver, falling back to the bundled Turnip. */
    public File getIcdFile(String name) {
        if (isInstalled(name)) return new File(getDriverDir(name), ICD_FILE_NAME);
        return new File(GlibcRootFs.find(context).getRootDir(), "/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json");
    }

    public void removeDriver(String name) {
        if (name != null && !name.isEmpty()) FileUtils.delete(getDriverDir(name));
    }

    /** Imports a driver archive and returns its name. Blocking, call it off the UI thread. */
    public String importDriver(Uri uri) throws ImportException {
        String fileName = FileUtils.getUriFileName(context, uri);
        if (fileName == null) fileName = "driver";
        String name = fileName.replaceAll("(?i)(\\.tar)?\\.(tzst|zst|zip|txz|xz)$", "").replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        if (name.isEmpty()) name = "driver";

        File tmpDir = new File(context.getCacheDir(), "glibc_driver_import");
        FileUtils.delete(tmpDir);
        tmpDir.mkdirs();

        try {
            extractArchive(uri, tmpDir);

            List<File> files = new ArrayList<>();
            collectFiles(tmpDir, files);
            File library = findVulkanLibrary(files);
            if (library == null) throw new ImportException("No Vulkan driver (.so) found in " + fileName);
            if (!isGlibcLibrary(library)) {
                throw new ImportException(library.getName() + " is not a glibc build (it looks like a bionic/adrenotools driver)");
            }

            File driverDir = getDriverDir(name);
            FileUtils.delete(driverDir);
            driverDir.mkdirs();

            // Keep every shared library of the archive next to the driver, some builds ship dependencies.
            for (File file : files) {
                if (file.getName().contains(".so")) {
                    File dst = new File(driverDir, file.getName());
                    if (!file.renameTo(dst)) FileUtils.copy(file, dst);
                    FileUtils.chmod(dst, 0771);
                }
            }

            writeIcdFile(new File(driverDir, ICD_FILE_NAME), new File(driverDir, library.getName()));
            return name;
        }
        catch (IOException | JSONException e) {
            throw new ImportException("Unable to import " + fileName + ": " + e.getMessage());
        }
        finally {
            FileUtils.delete(tmpDir);
        }
    }

    private void extractArchive(Uri uri, File dstDir) throws IOException, ImportException {
        byte[] magic = new byte[4];
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null || in.read(magic) != magic.length) throw new ImportException("Unable to read the selected file");
        }

        if (magic[0] == 'P' && magic[1] == 'K') {
            extractZip(uri, dstDir);
        }
        else if ((magic[0] & 0xff) == 0x28 && (magic[1] & 0xff) == 0xb5 && (magic[2] & 0xff) == 0x2f && (magic[3] & 0xff) == 0xfd) {
            if (!TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, uri, dstDir)) throw new ImportException("Invalid .tzst archive");
        }
        else if ((magic[0] & 0xff) == 0xfd && magic[1] == '7' && magic[2] == 'z' && magic[3] == 'X') {
            if (!TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, context, uri, dstDir)) throw new ImportException("Invalid .txz archive");
        }
        else throw new ImportException("Unsupported file, use a .tzst or .zip archive");
    }

    private void extractZip(Uri uri, File dstDir) throws IOException {
        String dstPath = dstDir.getCanonicalPath() + File.separator;
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             ZipInputStream zip = new ZipInputStream(new BufferedInputStream(in))) {
            ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                File file = new File(dstDir, entry.getName());
                if (!file.getCanonicalPath().startsWith(dstPath)) continue; // zip slip
                if (entry.isDirectory()) {
                    file.mkdirs();
                    continue;
                }
                file.getParentFile().mkdirs();
                try (OutputStream out = new FileOutputStream(file)) {
                    int read;
                    while ((read = zip.read(buffer)) != -1) out.write(buffer, 0, read);
                }
            }
        }
    }

    private static void collectFiles(File dir, List<File> files) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (FileUtils.isSymlink(child)) continue;
            if (child.isDirectory()) collectFiles(child, files);
            else files.add(child);
        }
    }

    private static File findVulkanLibrary(List<File> files) {
        // Prefer the library named by an ICD json shipped in the archive.
        for (File file : files) {
            if (!file.getName().endsWith(".json")) continue;
            try {
                String libraryPath = new JSONObject(FileUtils.readString(file)).getJSONObject("ICD").getString("library_path");
                String libraryName = new File(libraryPath).getName();
                for (File candidate : files) if (candidate.getName().equals(libraryName)) return candidate;
            }
            catch (JSONException | RuntimeException e) {}
        }

        for (String pattern : Arrays.asList("libvulkan_freedreno", "vulkan")) {
            for (File file : files) {
                String fileName = file.getName();
                if (fileName.contains(pattern) && fileName.contains(".so")) return file;
            }
        }
        return null;
    }

    /** glibc libraries reference versioned symbols such as GLIBC_2.17, bionic ones do not. */
    private static boolean isGlibcLibrary(File file) throws IOException {
        byte[] needle = "GLIBC_2.".getBytes(StandardCharsets.US_ASCII);
        byte[] buffer = new byte[1 << 16];
        int carry = 0;
        try (InputStream in = new FileInputStream(file)) {
            int read;
            while ((read = in.read(buffer, carry, buffer.length - carry)) != -1) {
                int length = carry + read;
                for (int i = 0; i + needle.length <= length; i++) {
                    int j = 0;
                    while (j < needle.length && buffer[i + j] == needle[j]) j++;
                    if (j == needle.length) return true;
                }
                carry = Math.min(needle.length - 1, length);
                System.arraycopy(buffer, length - carry, buffer, 0, carry);
            }
        }
        return false;
    }

    private static void writeIcdFile(File icdFile, File library) throws JSONException {
        JSONObject icd = new JSONObject();
        icd.put("library_path", library.getAbsolutePath());
        icd.put("api_version", "1.3.0");
        JSONObject json = new JSONObject();
        json.put("file_format_version", "1.0.0");
        json.put("ICD", icd);
        FileUtils.writeString(icdFile, json.toString(4));
    }
}
