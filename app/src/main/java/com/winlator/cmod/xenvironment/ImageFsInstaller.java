package com.winlator.cmod.xenvironment;

import android.content.Context;

import androidx.appcompat.app.AppCompatActivity;

import com.winlator.cmod.MainActivity;
import com.winlator.cmod.R;
import com.winlator.cmod.SettingsFragment;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.contents.AdrenotoolsManager;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.DownloadProgressDialog;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.PreloaderDialog;
import com.winlator.cmod.core.TarCompressorUtils;
import com.winlator.cmod.core.WineInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.os.SystemClock;
import com.winlator.cmod.core.OnExtractFileListener;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public abstract class ImageFsInstaller {
    public static final byte LATEST_VERSION = 21;
    
    public abstract interface onInstallationFinish {
        public void call();
    }

    private static void resetContainerImgVersions(Context context) {
        ContainerManager manager = new ContainerManager(context);
        for (Container container : manager.getContainers()) {
            container.putExtra("imgVersion", null);
            container.saveData();
        }
    }

    // Rough uncompressed/compressed ratios of the zstd assets, only used for the progress bar.
    private static final float IMAGEFS_RATIO = 4.4f;
    private static final float WINE_RATIO = 7.0f;
    private static final float DRIVER_RATIO = 4.0f;

    /** Aggregates the extracted bytes of parallel jobs and only touches the UI when the percentage changes. */
    private static class ProgressTracker {
        private final AtomicLong extracted = new AtomicLong();
        private final AtomicInteger lastProgress = new AtomicInteger(-1);
        private final AtomicLong lastUpdate = new AtomicLong();
        private final long total;
        private final MainActivity activity;
        private final DownloadProgressDialog dialog;

        private ProgressTracker(MainActivity activity, DownloadProgressDialog dialog, long total) {
            this.activity = activity;
            this.dialog = dialog;
            this.total = Math.max(total, 1);
        }

        private OnExtractFileListener listener() {
            return (file, size) -> {
                if (size > 0) {
                    int progress = (int)Math.min(99, extracted.addAndGet(size) * 100 / total);
                    long now = SystemClock.uptimeMillis();
                    int previous = lastProgress.get();
                    if (progress > previous && now - lastUpdate.get() >= 100 && lastProgress.compareAndSet(previous, progress)) {
                        lastUpdate.set(now);
                        activity.runOnUiThread(() -> dialog.setProgress(progress));
                    }
                }
                return file;
            };
        }
    }

    private static long estimateSize(MainActivity activity, String assetFile, float ratio) {
        return (long)(FileUtils.getSize(activity, assetFile) * ratio);
    }

    private static boolean installWineFromAssets(MainActivity activity, String version, ProgressTracker tracker) {
        File outFile = new File(ImageFs.find(activity).getRootDir(), "/opt/" + version);
        outFile.mkdirs();
        return TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, activity, version + ".tar.zst", outFile, tracker.listener());
    }

    public static void installFromAssets(final MainActivity activity, onInstallationFinish callback) {
        AppUtils.keepScreenOn(activity);
        ImageFs imageFs = ImageFs.find(activity);
        File rootDir = imageFs.getRootDir();

        SettingsFragment.resetEmulatorsVersion(activity);

        final DownloadProgressDialog dialog = new DownloadProgressDialog(activity);
        dialog.show(R.string.installing_system_files);

        Executors.newSingleThreadExecutor().execute(() -> {
            clearRootDir(rootDir);

            String[] wineVersions = activity.getResources().getStringArray(R.array.wine_entries);
            AdrenotoolsManager adrenotoolsManager = new AdrenotoolsManager(activity);
            String[] drivers = activity.getResources().getStringArray(R.array.wrapper_graphics_driver_version_entries);

            long total = estimateSize(activity, "imagefs.tar.zst", IMAGEFS_RATIO);
            for (String version : wineVersions) total += estimateSize(activity, version + ".tar.zst", WINE_RATIO);
            for (String driver : drivers) total += estimateSize(activity, adrenotoolsManager.getAssetPath(driver), DRIVER_RATIO);
            final ProgressTracker tracker = new ProgressTracker(activity, dialog, total);

            // ImageFs, each wine and the drivers go to separate directories, so they can be extracted in parallel.
            ArrayList<Callable<Boolean>> jobs = new ArrayList<>();
            jobs.add(() -> TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, activity, "imagefs.tar.zst", rootDir, tracker.listener()));
            for (String version : wineVersions) jobs.add(() -> installWineFromAssets(activity, version, tracker));
            jobs.add(() -> {
                for (String driver : drivers) adrenotoolsManager.extractDriverFromResources(driver, tracker.listener());
                return true;
            });

            boolean success = true;
            ExecutorService executor = Executors.newFixedThreadPool(Math.min(jobs.size(), Math.max(2, Runtime.getRuntime().availableProcessors() / 2)));
            try {
                for (Future<Boolean> result : executor.invokeAll(jobs)) success &= result.get();
            }
            catch (InterruptedException | ExecutionException e) {
                success = false;
            }
            finally {
                executor.shutdown();
            }

            if (success) {
                imageFs.createImgVersionFile(LATEST_VERSION);
                resetContainerImgVersions(activity);
            }
            else AppUtils.showToast(activity, R.string.unable_to_install_system_files);

            dialog.closeOnUiThread();
            activity.runOnUiThread(() -> {if (callback != null) callback.call();});
        });
    }

    public static boolean installIfNeeded(final MainActivity activity, onInstallationFinish callback) {
        ImageFs imageFs = ImageFs.find(activity);
        
        if (!imageFs.isValid() || imageFs.getVersion() < LATEST_VERSION) {
            installFromAssets(activity, callback);
            return true;
        }    
        
        return false;
    }

    private static void clearOptDir(File optDir) {
        File[] files = optDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.getName().equals("installed-wine")) continue;
                FileUtils.delete(file);
            }
        }
    }

    private static void clearRootDir(File rootDir) {
        if (rootDir.isDirectory()) {
            File[] files = rootDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        String name = file.getName();
                        if (name.equals("home")) {
                            continue;
                        }
                    }
                    FileUtils.delete(file);
                }
            }
        }
        else rootDir.mkdirs();
    }
}