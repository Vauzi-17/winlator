package com.winlator.cmod.box64;

import android.content.Context;

import com.winlator.cmod.core.EnvVars;

/**
 * Box64 presets for the glibc runtime, matching the ones brunodev85/winlator uses with its
 * Box64 builds. The bionic presets also set BOX64_AVX/BOX64_MMAP32/BOX64_UNITYPLAYER, which were
 * tuned for the bionic Box64 and are not applied here. COMPATIBILITY maps to brunodev's CONSERVATIVE.
 */
public abstract class GlibcBox64Presets {
    public static EnvVars getEnvVars(Context context, String id) {
        EnvVars envVars = new EnvVars();
        envVars.put("BOX64_DYNACACHE", "0");
        envVars.put("BOX64_UNITYPLAYER", "0");

        if (id.equals(Box64Preset.STABILITY)) {
            put(envVars, "2", "0", "0", "1", "0", "2", "128", "0", "0", "0", "0");
        }
        else if (id.equals(Box64Preset.COMPATIBILITY)) {
            put(envVars, "2", "0", "0", "1", "1", "1", "128", "0", "1", "0", "1");
        }
        else if (id.equals(Box64Preset.INTERMEDIATE)) {
            put(envVars, "2", "1", "0", "1", "2", "0", "128", "0", "1", "0", "2");
        }
        else if (id.equals(Box64Preset.PERFORMANCE)) {
            put(envVars, "1", "1", "1", "0", "3", "0", "512", "1", "1", "1", "2");
        }
        else if (id.startsWith(Box64Preset.CUSTOM)) {
            envVars.putAll(Box64PresetManager.getEnvVars("box64", context, id));
        }
        return envVars;
    }

    private static void put(EnvVars envVars, String safeFlags, String fastNan, String fastRound, String x87Double, String bigBlock,
                            String strongMem, String forward, String callRet, String wait, String nativeFlags, String weakBarrier) {
        envVars.put("BOX64_DYNAREC_SAFEFLAGS", safeFlags);
        envVars.put("BOX64_DYNAREC_FASTNAN", fastNan);
        envVars.put("BOX64_DYNAREC_FASTROUND", fastRound);
        envVars.put("BOX64_DYNAREC_X87DOUBLE", x87Double);
        envVars.put("BOX64_DYNAREC_BIGBLOCK", bigBlock);
        envVars.put("BOX64_DYNAREC_STRONGMEM", strongMem);
        envVars.put("BOX64_DYNAREC_FORWARD", forward);
        envVars.put("BOX64_DYNAREC_CALLRET", callRet);
        envVars.put("BOX64_DYNAREC_WAIT", wait);
        envVars.put("BOX64_DYNAREC_NATIVEFLAGS", nativeFlags);
        envVars.put("BOX64_DYNAREC_WEAKBARRIER", weakBarrier);
    }
}
