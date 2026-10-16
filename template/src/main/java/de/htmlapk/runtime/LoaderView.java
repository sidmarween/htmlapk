package de.htmlapk.runtime;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

/** Ladeanimation: drehender Kreis-Bogen oder hin- und herlaufender Balken. */
public class LoaderView extends View {

    public static final int STYLE_CIRCLE = 0;
    public static final int STYLE_BAR = 1;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int style = STYLE_CIRCLE;
    private float thickness;
    private final long start = SystemClock.uptimeMillis();

    public LoaderView(Context c) {
        super(c);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeCap(Paint.Cap.ROUND);
        setThickness(4 * c.getResources().getDisplayMetrics().density);
        setColor(0xFF00E5CC);
    }

    public void setStyle(int s) {
        style = s;
        invalidate();
    }

    public void setColor(int color) {
        paint.setColor(color);
        track.setColor((color & 0x00FFFFFF) | 0x33000000);
        invalidate();
    }

    public void setThickness(float px) {
        thickness = Math.max(1f, px);
        paint.setStrokeWidth(thickness);
        track.setStrokeWidth(thickness);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        long t = SystemClock.uptimeMillis() - start;
        if (style == STYLE_BAR) {
            float y = h / 2f;
            float half = thickness / 2f;
            canvas.drawLine(half, y, w - half, y, track);
            float seg = w * 0.35f;
            float p = (t % 1400) / 1400f;                // 0..1
            float x = -seg + p * (w + seg);
            float a = Math.max(half, x), b = Math.min(w - half, x + seg);
            if (b > a) canvas.drawLine(a, y, b, y, paint);
        } else {
            float pad = thickness / 2f + 1;
            float size = Math.min(w, h);
            rect.set((w - size) / 2f + pad, (h - size) / 2f + pad,
                    (w + size) / 2f - pad, (h + size) / 2f - pad);
            float rot = (t % 1000) / 1000f * 360f;
            float sweep = 60f + 200f * (float) (0.5 + 0.5 * Math.sin(t / 380.0));
            canvas.drawArc(rect, rot, sweep, false, paint);
        }
        if (getVisibility() == VISIBLE) postInvalidateOnAnimation();
    }
}
