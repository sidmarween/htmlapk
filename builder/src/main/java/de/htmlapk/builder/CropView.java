package de.htmlapk.builder;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/**
 * Zeigt ein Bild unter einem festen quadratischen Rahmen. Verschieben mit einem Finger,
 * Zoomen mit zwei Fingern. crop() liefert den Rahmeninhalt als quadratisches Bitmap.
 */
public class CropView extends View {

    private Bitmap bitmap;
    private float scale = 1f, tx, ty;            // Bild -> Ansicht: x' = x * scale + tx
    private final RectF frame = new RectF();
    private boolean needsInitialFill;

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint dimPaint = new Paint();
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint frameBgPaint = new Paint();
    private final Path dimPath = new Path();

    private final ScaleGestureDetector scaleDetector;
    private float lastX, lastY;
    private boolean resetLast = true;

    public CropView(Context context) {
        super(context);
        float d = getResources().getDisplayMetrics().density;
        dimPaint.setColor(0xB0000000);
        borderPaint.setColor(Color.WHITE);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(2 * d);
        guidePaint.setColor(0x99FFFFFF);
        guidePaint.setStyle(Paint.Style.STROKE);
        guidePaint.setStrokeWidth(1.5f * d);
        guidePaint.setPathEffect(new DashPathEffect(new float[]{6 * d, 6 * d}, 0));
        frameBgPaint.setColor(0xFF3A3A3A);
        dimPath.setFillType(Path.FillType.EVEN_ODD);

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector det) {
                if (bitmap == null) return false;
                float newScale = clamp(scale * det.getScaleFactor(), minScale(), maxScale());
                float f = newScale / scale;
                tx = det.getFocusX() - (det.getFocusX() - tx) * f;
                ty = det.getFocusY() - (det.getFocusY() - ty) * f;
                scale = newScale;
                constrain();
                invalidate();
                return true;
            }
        });
    }

    public void setBitmap(Bitmap b) {
        bitmap = b;
        if (getWidth() > 0) fill();
        else needsInitialFill = true;
        invalidate();
    }

    public boolean hasBitmap() {
        return bitmap != null;
    }

    /** Rahmen komplett ausfüllen (Ränder werden abgeschnitten). */
    public void fill() {
        if (bitmap == null || frame.isEmpty()) return;
        scale = coverScale();
        center();
    }

    /** Ganzes Bild im Rahmen zeigen (Rest bleibt transparent). */
    public void fit() {
        if (bitmap == null || frame.isEmpty()) return;
        scale = minScale();
        center();
    }

    /** Um 90° im Uhrzeigersinn drehen. */
    public void rotate() {
        if (bitmap == null) return;
        Matrix r = new Matrix();
        r.postRotate(90);
        Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), r, true);
        if (rotated != bitmap) bitmap.recycle();
        bitmap = rotated;
        fill();
    }

    /** Rahmeninhalt als quadratisches Bild in der gewünschten Größe (transparenter Hintergrund). */
    public Bitmap crop(int size) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        float k = size / frame.width();
        Matrix m = new Matrix();
        m.setScale(scale, scale);
        m.postTranslate(tx - frame.left, ty - frame.top);
        m.postScale(k, k);
        c.drawBitmap(bitmap, m, bitmapPaint);
        return out;
    }

    // ----------------------------------------------------------------- intern

    private float fitScaleValue() {
        return Math.min(frame.width() / bitmap.getWidth(), frame.height() / bitmap.getHeight());
    }

    private float coverScale() {
        return Math.max(frame.width() / bitmap.getWidth(), frame.height() / bitmap.getHeight());
    }

    private float minScale() {
        return fitScaleValue();
    }

    private float maxScale() {
        return coverScale() * 8f;
    }

    private void center() {
        tx = frame.centerX() - bitmap.getWidth() * scale / 2f;
        ty = frame.centerY() - bitmap.getHeight() * scale / 2f;
        constrain();
        invalidate();
    }

    /** Ist das Bild größer als der Rahmen, darf kein leerer Rand in den Rahmen rutschen. */
    private void constrain() {
        scale = clamp(scale, minScale(), maxScale());
        float w = bitmap.getWidth() * scale;
        float h = bitmap.getHeight() * scale;
        tx = w <= frame.width() ? frame.centerX() - w / 2f : clamp(tx, frame.right - w, frame.left);
        ty = h <= frame.height() ? frame.centerY() - h / 2f : clamp(ty, frame.bottom - h, frame.top);
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float margin = 24 * getResources().getDisplayMetrics().density;
        float side = Math.min(w, h) - 2 * margin;
        frame.set((w - side) / 2f, (h - side) / 2f, (w + side) / 2f, (h + side) / 2f);
        dimPath.reset();
        dimPath.addRect(0, 0, w, h, Path.Direction.CW);
        dimPath.addRect(frame, Path.Direction.CW);
        if (bitmap != null) {
            if (needsInitialFill || oldw == 0) {
                fill();
                needsInitialFill = false;
            } else {
                constrain();
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(Color.BLACK);
        canvas.drawRect(frame, frameBgPaint); // zeigt transparente Bereiche
        if (bitmap != null) {
            Matrix m = new Matrix();
            m.setScale(scale, scale);
            m.postTranslate(tx, ty);
            canvas.drawBitmap(bitmap, m, bitmapPaint);
        }
        canvas.drawPath(dimPath, dimPaint);
        canvas.drawRect(frame, borderPaint);
        canvas.drawCircle(frame.centerX(), frame.centerY(), frame.width() / 2f, guidePaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (bitmap == null) return false;
        scaleDetector.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = e.getX();
                lastY = e.getY();
                resetLast = false;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP:
                resetLast = true; // nach Fingerwechsel nicht springen
                break;
            case MotionEvent.ACTION_MOVE:
                float x = e.getX(), y = e.getY();
                if (resetLast) {
                    resetLast = false;
                } else if (!scaleDetector.isInProgress() && e.getPointerCount() == 1) {
                    tx += x - lastX;
                    ty += y - lastY;
                    constrain();
                    invalidate();
                }
                lastX = x;
                lastY = y;
                break;
            default:
                break;
        }
        return true;
    }
}
