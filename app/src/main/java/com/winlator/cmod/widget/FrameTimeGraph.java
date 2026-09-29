package com.winlator.cmod.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import com.winlator.cmod.core.UnitUtils;

/**
 * The HUD's frame-time line: the last frame intervals, drawn as one polyline
 * against a dashed reference line. Redrawn only when the HUD refreshes (twice
 * a second), with no allocation per draw.
 */
public class FrameTimeGraph extends View {
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint refPaint = new Paint();
    private final Path path = new Path();
    private final float[] samples;
    private int count = 0;
    private float reference = 16.7f;

    public FrameTimeGraph(Context context, int sampleCount) {
        super(context);
        samples = new float[sampleCount];
        linePaint.setColor(0xFFFFFFFF);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(UnitUtils.dpToPx(1.5f));
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        refPaint.setColor(0xFF5C5C5C);
        refPaint.setStyle(Paint.Style.STROKE);
        refPaint.setStrokeWidth(UnitUtils.dpToPx(1));
        refPaint.setPathEffect(new DashPathEffect(new float[]{UnitUtils.dpToPx(3), UnitUtils.dpToPx(3)}, 0));
    }

    /** Copies the newest {@code n} intervals (ms), oldest first, and redraws. */
    public void setSamples(float[] source, int n, float referenceMs) {
        n = Math.min(n, samples.length);
        System.arraycopy(source, 0, samples, 0, n);
        count = n;
        reference = referenceMs > 0 ? referenceMs : 16.7f;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        // The reference (the average frame time) sits in the middle; the range
        // above and below it is the same size, so spikes stand out.
        float mid = h / 2f;
        canvas.drawLine(0, mid, w, mid, refPaint);
        if (count < 2) return;

        float range = Math.max(reference, 4f);
        path.rewind();
        for (int i = 0; i < count; i++) {
            float x = w * i / (samples.length - 1);
            float dy = (samples[i] - reference) / range * mid;
            float y = Math.max(1, Math.min(h - 1, mid - dy));
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        canvas.drawPath(path, linePaint);
    }
}
