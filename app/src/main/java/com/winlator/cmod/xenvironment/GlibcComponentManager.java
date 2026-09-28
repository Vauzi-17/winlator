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
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Importable components of the glibc runtime: Vulkan drivers and Box64 builds. The bundled ones
 * live in the glibc rootfs, imported ones (.tzst, .txz or .zip, any layout) are stored per name.
 * Everything that depends on the app location is generated or relocated at import time, so the
 * components keep working after a package rename or clone.
 */
public class GlibcComponentManager {
    public enum Type {
        DRIVER("glibc_drivers", "Turnip 26.2.0 (bundled)"),
        BOX64("glibc_box64", "Box64 0.4.4 (bundled)");

        private final String dirName;
        public final String bundledName;

        Type(String dirName, String bundledName) {
            this.dirName = dirName;
            this.bundledName = bundledName;
        }
    }

    /** Value stored in containers/shortcuts for the bundled component. */
    public static final String BUNDLED = "";
    /** Driver value for Vortek (Android's own Vulkan driver through the app); '@' never appears in imported names. */
    public static final String VORTEK = "@vortek";
    public static final String VORTEK_NAME = "Vortek (Android Vulkan driver)";
    private static final String ICD_FILE_NAME = "icd.aarch64.json";
    private static final String BOX64_FILE_NAME = "box64";
    private final Context context;
    private final Type type;

    public static class ImportException extends Exception {
        public ImportException(String message) {
            super(message);
        }
    }

    public GlibcComponentManager(Context context, Type type) {
        this.context = context;
        this.type = type;
    }

    public File getComponentDir(String name) {
        return new File(new File(context.getFilesDir(), type.dirName), name);
    }

    private File getMarkerFile(String name) {
        return new File(getComponentDir(name), type == Type.DRIVER ? ICD_FILE_NAME : BOX64_FILE_NAME);
    }

    public List<String> getInstalled() {
        ArrayList<String> names = new ArrayList<>();
        File[] dirs = new File(context.getFilesDir(), type.dirName).listFiles(File::isDirectory);
        if (dirs != null) for (File dir : dirs) if (getMarkerFile(dir.getName()).isFile()) names.add(dir.getName());
        Collections.sort(names, String::compareToIgnoreCase);
        return names;
    }

    public boolean isInstalled(String name) {
        return name != null && !name.isEmpty() && getMarkerFile(name).isFile();
    }

    public void remove(String name) {
        if (name != null && !name.isEmpty()) FileUtils.delete(getComponentDir(name));
    }

    /** ICD json of the given driver, falling back to the bundled Turnip. */
    public File getIcdFile(String name) {
        if (VORTEK.equals(name)) return GlibcRootFs.find(context).getVortekIcdFile();
        if (isInstalled(name)) return getMarkerFile(name);
        return new File(GlibcRootFs.find(context).getRootDir(), "/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json");
    }

    /** Box64 binary of the given name, falling back to the bundled one. */
    public File getBox64File(String name) {
        if (isInstalled(name)) return getMarkerFile(name);
        return GlibcRootFs.find(context).getBox64File();
    }

    /** Imports a component archive and returns its name. Blocking, call it off the UI thread. */
    public String importComponent(Uri uri) throws ImportException {
        String fileName = FileUtils.getUriFileName(context, uri);
        if (fileName == null) fileName = type == Type.DRIVER ? "driver" : "box64";
        String name = fileName.replaceAll("(?i)(\\.tar)?\\.(tzst|zst|zip|txz|xz)$", "").replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        if (name.isEmpty()) name = type.name().toLowerCase();

        GlibcRootFs rootFs = GlibcRootFs.find(context);
        if (!rootFs.isValid()) throw new ImportException("Create a glibc container first, the glibc runtime is not installed yet");

        File tmpDir = new File(context.getCacheDir(), "glibc_component_import");
        FileUtils.delete(tmpDir);
        tmpDir.mkdirs();

        try {
            extractArchive(uri, tmpDir);
            List<File> files = new ArrayList<>();
            collectFiles(tmpDir, files);

            File componentDir = getComponentDir(name);
            if (type == Type.DRIVER) installDriver(files, componentDir, rootFs);
            else installBox64(files, componentDir, rootFs);
            return name;
        }
        catch (IOException | JSONException e) {
            throw new ImportException("Unable to import " + fileName + ": " + e.getMessage());
        }
        finally {
            FileUtils.delete(tmpDir);
        }
    }

    private void installDriver(List<File> files, File driverDir, GlibcRootFs rootFs) throws ImportException, IOException, JSONException {
        File library = findVulkanLibrary(files);
        if (library == null) throw new ImportException("No Vulkan driver (.so) found in the archive");
        if (!isGlibcBinary(library)) throw new ImportException(library.getName() + " is not a glibc build (it looks like a bionic/adrenotools driver)");

        FileUtils.delete(driverDir);
        driverDir.mkdirs();

        // Keep every shared library of the archive next to the driver, some builds ship dependencies.
        ArrayList<File> installed = new ArrayList<>();
        for (File file : files) {
            if (file.getName().contains(".so")) installed.add(moveInto(file, driverDir));
        }
        relocate(installed, rootFs);
        writeIcdFile(new File(driverDir, ICD_FILE_NAME), new File(driverDir, library.getName()));
    }

    private void installBox64(List<File> files, File box64Dir, GlibcRootFs rootFs) throws ImportException, IOException {
        File box64 = null;
        for (File file : files) if (file.getName().equals(BOX64_FILE_NAME)) box64 = file;
        if (box64 == null) throw new ImportException("No box64 binary found in the archive");
        if (!isGlibcBinary(box64)) throw new ImportException("This box64 is not a glibc build (it looks like a bionic build)");

        FileUtils.delete(box64Dir);
        box64Dir.mkdirs();
        File installed = moveInto(box64, box64Dir);
        relocate(Collections.singletonList(installed), rootFs);

        String interpreter = readInterpreter(installed);
        if (interpreter == null || !new File(interpreter).isFile()) {
            FileUtils.delete(box64Dir);
            throw new ImportException("Unsupported box64 build, its loader " + interpreter + " is not part of the glibc runtime (use a build made for Winlator glibc)");
        }
    }

    private static File moveInto(File file, File dir) {
        File dst = new File(dir, file.getName());
        if (!file.renameTo(dst)) FileUtils.copy(file, dst);
        FileUtils.chmod(dst, 0771);
        return dst;
    }

    /** Rewrites any known Winlator glibc prefix that is long enough to point to our rootfs. */
    private static void relocate(List<File> files, GlibcRootFs rootFs) {
        for (String prefix : GlibcRootFs.KNOWN_PREFIXES) {
            String relocated = rootFs.getRelocatedPrefix(prefix.length());
            if (relocated != null) GlibcRootFsInstaller.relocate(files, prefix, relocated, rootFs.getRootDir().getPath(), null);
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

    /** glibc binaries reference versioned symbols such as GLIBC_2.17, bionic ones do not. */
    private static boolean isGlibcBinary(File file) throws IOException {
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

    /** PT_INTERP of a 64-bit little-endian ELF executable, or null. */
    static String readInterpreter(File file) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            byte[] header = new byte[64];
            raf.readFully(header);
            if (header[0] != 0x7f || header[1] != 'E' || header[2] != 'L' || header[3] != 'F' || header[4] != 2 || header[5] != 1) return null;
            ByteBuffer elf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            long phoff = elf.getLong(0x20);
            int phentsize = elf.getShort(0x36) & 0xffff;
            int phnum = elf.getShort(0x38) & 0xffff;

            byte[] entry = new byte[phentsize];
            for (int i = 0; i < phnum; i++) {
                raf.seek(phoff + (long)i * phentsize);
                raf.readFully(entry);
                ByteBuffer ph = ByteBuffer.wrap(entry).order(ByteOrder.LITTLE_ENDIAN);
                if (ph.getInt(0) != 3) continue; // PT_INTERP
                long offset = ph.getLong(8);
                int size = (int)ph.getLong(32);
                byte[] path = new byte[size];
                raf.seek(offset);
                raf.readFully(path);
                int end = 0;
                while (end < path.length && path[end] != 0) end++;
                return new String(path, 0, end, StandardCharsets.US_ASCII);
            }
        }
        catch (IOException | RuntimeException e) {}
        return null;
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
