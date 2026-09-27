package com.winlator.cmod.contentdialog;

import android.content.Context;
import android.widget.ArrayAdapter;
import android.widget.Spinner;

import com.winlator.cmod.R;
import com.winlator.cmod.core.Callback;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.KeyValueSet;

import java.util.ArrayList;
import java.util.Arrays;

/** Turnip (Mesa) settings of a glibc container, stored as "renderMode=...,presentMode=...,maxDeviceMemory=...". */
public class GlibcTurnipConfigDialog extends ContentDialog {
    private static final String[] RENDER_MODES = {"default", "sysmem", "gmem"};
    private static final String[] RENDER_MODE_NAMES = {"Default (container TU_DEBUG)", "SYSMEM", "GMEM"};
    private static final String[] PRESENT_MODES = {"default", "mailbox", "fifo", "immediate", "relaxed"};
    private static final String[] PRESENT_MODE_NAMES = {"Default", "Mailbox", "FIFO (VSync)", "Immediate", "FIFO Relaxed"};
    private static final String[] MAX_DEVICE_MEMORY = {"0", "512", "1024", "2048", "3072", "4096"};
    private static final String[] MAX_DEVICE_MEMORY_NAMES = {"Default", "512 MB", "1024 MB", "2048 MB", "3072 MB", "4096 MB"};

    public GlibcTurnipConfigDialog(Context context, String config, Callback<String> onConfirm) {
        super(context, R.layout.glibc_turnip_config_dialog);
        setTitle(R.string.turnip_settings);
        setIcon(R.drawable.icon_settings);

        KeyValueSet values = new KeyValueSet(config);
        Spinner sRenderMode = findViewById(R.id.SRenderMode);
        Spinner sPresentMode = findViewById(R.id.SPresentMode);
        Spinner sMaxDeviceMemory = findViewById(R.id.SMaxDeviceMemory);
        setupSpinner(sRenderMode, RENDER_MODE_NAMES, RENDER_MODES, values.get("renderMode", "default"));
        setupSpinner(sPresentMode, PRESENT_MODE_NAMES, PRESENT_MODES, values.get("presentMode", "default"));
        setupSpinner(sMaxDeviceMemory, MAX_DEVICE_MEMORY_NAMES, MAX_DEVICE_MEMORY, values.get("maxDeviceMemory", "0"));

        setOnConfirmCallback(() -> {
            KeyValueSet result = new KeyValueSet();
            result.put("renderMode", RENDER_MODES[sRenderMode.getSelectedItemPosition()]);
            result.put("presentMode", PRESENT_MODES[sPresentMode.getSelectedItemPosition()]);
            result.put("maxDeviceMemory", MAX_DEVICE_MEMORY[sMaxDeviceMemory.getSelectedItemPosition()]);
            onConfirm.call(result.toString());
        });
    }

    private void setupSpinner(Spinner spinner, String[] names, String[] values, String selected) {
        spinner.setAdapter(new ArrayAdapter<>(getContext(), R.layout.spinner_dropdown_item, names));
        spinner.setSelection(Math.max(0, Arrays.asList(values).indexOf(selected)));
    }

    /** Applies the config on top of the final env vars (after the container ones, which may set TU_DEBUG). */
    public static void setEnvVars(String config, EnvVars envVars) {
        KeyValueSet values = new KeyValueSet(config);

        String renderMode = values.get("renderMode", "default");
        if (!renderMode.equals("default")) {
            ArrayList<String> flags = new ArrayList<>();
            for (String flag : envVars.get("TU_DEBUG").split(",")) {
                if (!flag.isEmpty() && !flag.equals("sysmem") && !flag.equals("gmem")) flags.add(flag);
            }
            flags.add(renderMode);
            envVars.put("TU_DEBUG", String.join(",", flags));
        }

        String presentMode = values.get("presentMode", "default");
        if (!presentMode.equals("default")) envVars.put("MESA_VK_WSI_PRESENT_MODE", presentMode);

        String maxDeviceMemory = values.get("maxDeviceMemory", "0");
        if (!maxDeviceMemory.equals("0")) envVars.put("TU_OVERRIDE_HEAP_SIZE", maxDeviceMemory);
    }
}
