package com.winlator.cmod.core;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Records one run for troubleshooting: the output of the wine process tree with crash related
 * debug channels on, and the app's logcat. {@link #finish} packs them with a summary of the
 * settings into a zip under Download/Winlator/diagnostics.
 */
public class DiagnosticsRecorder {
    private static final String TAG = "DiagnosticsRecorder";
    /** Shortcut extra: record the next launch. */
    public static final String EXTRA_NEXT_RUN = "diagnosticsNextRun";
    /** A run shorter than this is reported as a possible crash. */
    public static final long EARLY_EXIT_MILLIS = 30000;
    private final Context context;
    private final String title;
    private final File workDir;
    private final File wineLogFile;
    private final File logcatFile;
    private java.lang.Process logcatProcess;
    private File reportFile;

    public DiagnosticsRecorder(Context context, String title) {
        this.context = context;
        this.title = title;
        workDir = new File(context.getCacheDir(), "diagnostics");
        FileUtils.delete(workDir);
        workDir.mkdirs();
        wineLogFile = new File(workDir, "wine.log");
        logcatFile = new File(workDir, "logcat.txt");
    }

    public File getWineLogFile() {
        return wineLogFile;
    }

    public File getReportFile() {
        return reportFile;
    }

    /** Debug channels for crashes and missing dependencies, kept small enough for long runs. */
    public void applyEnvVars(EnvVars envVars) {
        envVars.put("WINEDEBUG", "+seh,+loaddll,+process,err+all,warn+module");
        envVars.put("BOX64_LOG", "1");
        envVars.put("BOX64_NOBANNER", "0");
        envVars.put("BOX64_SHOWSEGV", "1");
        envVars.put("BOX64_DLSYM_ERROR", "1");
        envVars.put("DXVK_LOG_LEVEL", "info");
        envVars.put("VKD3D_DEBUG", "warn");
    }

    /** Starts the logcat capture; the guest launcher writes the wine output to {@link #getWineLogFile}. */
    public void start() {
        try {
            logcatProcess = new ProcessBuilder("logcat", "-v", "threadtime", "--pid=" + android.os.Process.myPid())
                .redirectErrorStream(true)
                .redirectOutput(logcatFile)
                .start();
        }
        catch (IOException e) {
            Log.e(TAG, "Unable to start logcat", e);
        }
    }

    /** Stops recording and writes the zip. Blocking, returns null when it could not be written. */
    public synchronized File finish(String summary) {
        if (reportFile != null) return reportFile;
        if (logcatProcess != null) {
            logcatProcess.destroy();
            logcatProcess = null;
        }
        // Killing the live capture loses whatever logcat still held in its
        // output buffer, which was everything after the first seconds of play.
        // A dump of what logd keeps for this process covers the end of the run.
        try {
            Process dump = new ProcessBuilder("logcat", "-d", "-v", "threadtime", "--pid=" + android.os.Process.myPid())
                .redirectErrorStream(true)
                .redirectOutput(new File(workDir, "logcat-end.txt"))
                .start();
            if (!dump.waitFor(10, TimeUnit.SECONDS)) dump.destroy();
        }
        catch (IOException | InterruptedException e) {
            Log.e(TAG, "Unable to dump logcat", e);
        }

        FileUtils.writeString(new File(workDir, "summary.txt"), summary);

        String name = title.replaceAll("[^A-Za-z0-9._-]", "_") + "-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".zip";
        for (File dir : new File[]{
            new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Winlator/diagnostics"),
            new File(context.getExternalFilesDir(null), "diagnostics")}) {
            dir.mkdirs();
            File zipFile = new File(dir, name);
            if (zip(zipFile)) {
                reportFile = zipFile;
                break;
            }
        }
        return reportFile;
    }

    private boolean zip(File zipFile) {
        File[] files = workDir.listFiles();
        if (files == null) return false;
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zipFile))) {
            byte[] buffer = new byte[65536];
            for (File file : files) {
                out.putNextEntry(new ZipEntry(file.getName()));
                try (InputStream in = new FileInputStream(file)) {
                    int length;
                    while ((length = in.read(buffer)) > 0) out.write(buffer, 0, length);
                }
                out.closeEntry();
            }
            return true;
        }
        catch (IOException e) {
            Log.e(TAG, "Unable to write " + zipFile, e);
            zipFile.delete();
            return false;
        }
    }

    /** The last lines of a text file, reading at most its last 64 KiB. */
    public static String tail(File file, int maxLines) {
        if (file == null || !file.isFile()) return "";
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long length = raf.length();
            int size = (int)Math.min(length, 65536);
            byte[] data = new byte[size];
            raf.seek(length - size);
            raf.readFully(data);
            String[] lines = new String(data, StandardCharsets.UTF_8).split("\n");
            // The +process/+loaddll trace channels are there for the zip; the
            // errors and warnings are what a player can act on.
            ArrayList<String> picked = new ArrayList<>();
            for (int i = lines.length - 1; i >= 0 && picked.size() < maxLines; i--) {
                String line = lines[i].trim();
                if (line.isEmpty() || line.contains(":trace:")) continue;
                picked.add(0, line.length() > 160 ? line.substring(0, 160) + "…" : line);
            }
            return String.join("\n", picked);
        }
        catch (IOException e) {
            return "";
        }
    }
}
