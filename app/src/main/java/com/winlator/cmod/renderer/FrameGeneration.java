package com.winlator.cmod.renderer;

import android.content.Context;
import android.view.View;
import android.widget.CheckBox;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import com.winlator.cmod.R;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.core.LsfgNative;
import com.winlator.cmod.widget.XServerView;

/**
 * Settings of LSFG Native frame generation: per shortcut, and live from the
 * in-game sidebar. The work happens in the DisplayX present path
 * (renderer/framegen in native code).
 */
public class FrameGeneration {
    // Mirrors FrameGenerator::Status.
    public static final int STATUS_OFF = 0;
    public static final int STATUS_WAITING = 1;
    public static final int STATUS_RUNNING = 2;
    public static final int STATUS_NO_SHADERS = 3;
    public static final int STATUS_UNSUPPORTED = 4;
    public static final int STATUS_FAILED = 5;

    public static final int MIN_MULTIPLIER = 2;
    public static final int MAX_MULTIPLIER = 4;
    public static final int MIN_FLOW_SCALE = 25;
    public static final int MAX_FLOW_SCALE = 100;
    public static final int DEFAULT_FLOW_SCALE = 80;

    public boolean enabled = false;
    public int multiplier = MIN_MULTIPLIER;
    public int flowScale = DEFAULT_FLOW_SCALE;   // percent
    public boolean adaptive = false;

    public static FrameGeneration fromShortcut(Shortcut shortcut) {
        FrameGeneration config = new FrameGeneration();
        if (shortcut == null) return config;
        config.enabled = shortcut.getExtra("frameGen", "0").equals("1");
        config.multiplier = clamp(parseInt(shortcut.getExtra("frameGenMultiplier"), MIN_MULTIPLIER), MIN_MULTIPLIER, MAX_MULTIPLIER);
        config.flowScale = clamp(parseInt(shortcut.getExtra("frameGenFlowScale"), DEFAULT_FLOW_SCALE), MIN_FLOW_SCALE, MAX_FLOW_SCALE);
        config.adaptive = shortcut.getExtra("frameGenAdaptive", "0").equals("1");
        return config;
    }

    public void saveTo(Shortcut shortcut) {
        shortcut.putExtra("frameGen", enabled ? "1" : null);
        shortcut.putExtra("frameGenMultiplier", multiplier != MIN_MULTIPLIER ? String.valueOf(multiplier) : null);
        shortcut.putExtra("frameGenFlowScale", flowScale != DEFAULT_FLOW_SCALE ? String.valueOf(flowScale) : null);
        shortcut.putExtra("frameGenAdaptive", adaptive ? "1" : null);
    }

    /** One line for a folded settings header, e.g. "2x, flow 0.80". */
    public String getSummary(Context context) {
        if (!enabled) return context.getString(R.string.frame_gen_status_off);
        return multiplier + "x, flow " + String.format("%.2f", flowScale / 100.0f) + (adaptive ? ", adaptive" : "");
    }

    /** Hand the settings to the renderer; takes effect on the next game frame. */
    public void apply(Context context) {
        XServerView.nativeSetFrameGeneration(enabled, multiplier, flowScale / 100.0f, adaptive,
                LsfgNative.cacheFile(context).getAbsolutePath());
    }

    public static int getStatus() {
        return XServerView.nativeGetFrameGenerationStatus();
    }

    public static String getStatusText(Context context) {
        int status = getStatus();
        String detail = XServerView.nativeGetFrameGenerationStatusText();
        String text;
        switch (status) {
            case STATUS_WAITING:
                text = context.getString(R.string.frame_gen_status_waiting);
                break;
            case STATUS_RUNNING:
                text = context.getString(R.string.frame_gen_status_running,
                        Math.round(XServerView.nativeGetFrameGenerationRate()));
                break;
            case STATUS_NO_SHADERS:
                text = context.getString(R.string.frame_gen_status_no_shaders);
                break;
            case STATUS_UNSUPPORTED:
                text = context.getString(R.string.frame_gen_status_unsupported);
                break;
            case STATUS_FAILED:
                text = context.getString(R.string.frame_gen_status_failed);
                break;
            default:
                text = context.getString(R.string.frame_gen_status_off);
                break;
        }
        return detail != null && !detail.isEmpty() && status != STATUS_OFF ? text + "\n" + detail : text;
    }

    /** Fill the shared frame_generation_fields layout from these settings. */
    public void bindViews(View root, Runnable onChanged) {
        final CheckBox cbEnabled = root.findViewById(R.id.CBFrameGen);
        final Spinner sMultiplier = root.findViewById(R.id.SFrameGenMultiplier);
        final SeekBar sbFlowScale = root.findViewById(R.id.SBFrameGenFlowScale);
        final TextView tvFlowScale = root.findViewById(R.id.TVFrameGenFlowScale);
        final CheckBox cbAdaptive = root.findViewById(R.id.CBFrameGenAdaptive);

        cbEnabled.setChecked(enabled);
        sMultiplier.setSelection(multiplier - MIN_MULTIPLIER, false);
        sbFlowScale.setMax(MAX_FLOW_SCALE - MIN_FLOW_SCALE);
        sbFlowScale.setProgress(flowScale - MIN_FLOW_SCALE);
        tvFlowScale.setText(String.format("%.2f", flowScale / 100.0f));
        cbAdaptive.setChecked(adaptive);

        cbEnabled.setOnCheckedChangeListener((v, isChecked) -> {
            enabled = isChecked;
            if (onChanged != null) onChanged.run();
        });
        sMultiplier.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                int value = clamp(position + MIN_MULTIPLIER, MIN_MULTIPLIER, MAX_MULTIPLIER);
                if (value == multiplier) return;
                multiplier = value;
                if (onChanged != null) onChanged.run();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        sbFlowScale.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                flowScale = progress + MIN_FLOW_SCALE;
                tvFlowScale.setText(String.format("%.2f", flowScale / 100.0f));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (onChanged != null) onChanged.run();
            }
        });
        cbAdaptive.setOnCheckedChangeListener((v, isChecked) -> {
            adaptive = isChecked;
            if (onChanged != null) onChanged.run();
        });
    }

    private static int parseInt(String value, int fallback) {
        try {
            return value != null ? Integer.parseInt(value) : fallback;
        }
        catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
