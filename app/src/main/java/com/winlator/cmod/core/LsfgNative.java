package com.winlator.cmod.core;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import java.io.File;

/**
 * LSFG Native frame generation: the user's own Lossless.dll and the shader
 * cache translated from it.
 *
 * <p>The compute shaders of Lossless Scaling frame generation are not
 * redistributable. They live as resources inside the user's own copy of
 * {@code Lossless.dll}, so nothing is bundled: the DLL is imported in Settings,
 * parsed as data (never loaded or executed) and its DXBC shaders are translated
 * to SPIR-V once, on device. The result is cached, keyed on the DLL's content.
 *
 * <p>Ported from Bannerlator (GPL-3.0), whose LSFG port comes from WinNative and
 * the Eden Emulator Project following lsfg-vk.
 */
public final class LsfgNative {
    private static final String TAG = "LsfgNative";

    static { System.loadLibrary("winlator"); }

    private LsfgNative() {}

    // Mirrors lsfg::DllStatus.
    public static final int STATUS_OK                      = 0;
    public static final int STATUS_NOT_INSTALLED           = 1;
    public static final int STATUS_UNREADABLE_FILE         = 2;
    public static final int STATUS_NOT_PORTABLE_EXECUTABLE = 3;
    public static final int STATUS_MISSING_SHADERS         = 4;
    public static final int STATUS_TRANSLATION_FAILED      = 5;
    public static final int STATUS_CACHE_UNUSABLE          = 6;

    // Mirrors lsfg::Variant.
    public static final int VARIANT_NONE            = 0;
    public static final int VARIANT_SPIRV_FP16      = 1;
    public static final int VARIANT_SPIRV_FP32      = 2;
    public static final int VARIANT_DXBC_TRANSLATED = 3;

    private static native int nativeValidateDll(String dllPath);
    private static native int nativeDllVariant(String dllPath, boolean preferFp16);
    private static native int nativeBuildCache(String dllPath, String cachePath, boolean preferFp16);
    private static native boolean nativeCacheMatchesSource(String cachePath, String dllPath);
    private static native int nativeCacheVariant(String cachePath);
    private static native String nativeStatusName(int status);
    private static native String nativeVariantName(int variant);

    public static File directory(Context context) {
        return new File(context.getFilesDir(), "lsfg");
    }

    /** Where the imported Lossless.dll lives. */
    public static File losslessDll(Context context) {
        return new File(directory(context), "Lossless.dll");
    }

    /** Where the translated SPIR-V chain is cached. */
    public static File cacheFile(Context context) {
        return new File(directory(context), "shaders.cache");
    }

    public static boolean isDllAvailable(Context context) {
        return losslessDll(context).isFile();
    }

    /** True when frame generation has everything it needs to start. */
    public static boolean isReady(Context context) {
        File dll = losslessDll(context);
        File cache = cacheFile(context);
        return dll.isFile() && cache.isFile()
            && nativeCacheMatchesSource(cache.getAbsolutePath(), dll.getAbsolutePath());
    }

    public static String statusName(int status) { return nativeStatusName(status); }

    public static String variantName(int variant) { return nativeVariantName(variant); }

    /** Which producer an existing cache was built with, or VARIANT_NONE. */
    public static int cacheVariant(Context context) {
        File cache = cacheFile(context);
        if (!cache.isFile()) return VARIANT_NONE;
        return nativeCacheVariant(cache.getAbsolutePath());
    }

    /**
     * Copy a Lossless.dll picked by the user into app storage, check that it
     * carries the frame generation shaders and build the shader cache from it.
     * Slow (the shaders are translated on device): call it off the main thread.
     *
     * @return STATUS_OK when frame generation is ready to use.
     */
    public static int importDll(Context context, Uri uri) {
        File dir = directory(context);
        if (!dir.isDirectory() && !dir.mkdirs()) return STATUS_CACHE_UNUSABLE;

        File temp = new File(dir, "Lossless.dll.tmp");
        if (!FileUtils.copy(context, uri, temp)) {
            temp.delete();
            return STATUS_UNREADABLE_FILE;
        }

        int status = nativeValidateDll(temp.getAbsolutePath());
        if (status != STATUS_OK) {
            temp.delete();
            return status;
        }

        File dll = losslessDll(context);
        dll.delete();
        if (!temp.renameTo(dll)) {
            temp.delete();
            return STATUS_UNREADABLE_FILE;
        }
        cacheFile(context).delete();
        return ensureCache(context);
    }

    /**
     * Make sure a shader cache exists and matches the current DLL, building it
     * if not. Slow on a cache miss: call it off the main thread.
     */
    public static int ensureCache(Context context) {
        File dll = losslessDll(context);
        if (!dll.isFile()) return STATUS_NOT_INSTALLED;

        File cache = cacheFile(context);
        if (cache.isFile() && nativeCacheMatchesSource(cache.getAbsolutePath(), dll.getAbsolutePath()))
            return STATUS_OK;

        final long started = System.currentTimeMillis();
        int status = nativeBuildCache(dll.getAbsolutePath(), cache.getAbsolutePath(), false);
        if (status == STATUS_OK) {
            Log.i(TAG, "shader cache built in " + (System.currentTimeMillis() - started)
                    + " ms, variant=" + variantName(nativeCacheVariant(cache.getAbsolutePath())));
        }
        else {
            Log.e(TAG, "shader cache build failed: " + statusName(status));
            cache.delete();
        }
        return status;
    }

    /** Remove the imported DLL and its cache. */
    public static void remove(Context context) {
        losslessDll(context).delete();
        cacheFile(context).delete();
    }

    /** A user-facing explanation of a status, in terms a player can act on. */
    public static String explain(int status) {
        switch (status) {
            case STATUS_OK:
                return "Ready";
            case STATUS_NOT_INSTALLED:
                return "Import your own Lossless.dll in Settings first";
            case STATUS_UNREADABLE_FILE:
                return "Lossless.dll could not be read";
            case STATUS_NOT_PORTABLE_EXECUTABLE:
                return "That file is not a Windows DLL";
            case STATUS_MISSING_SHADERS:
                return "This Lossless.dll does not contain the frame generation shaders";
            case STATUS_TRANSLATION_FAILED:
                return "The frame generation shaders could not be translated on this device";
            case STATUS_CACHE_UNUSABLE:
                return "The shader cache could not be written";
            default:
                return "Unknown error";
        }
    }
}
