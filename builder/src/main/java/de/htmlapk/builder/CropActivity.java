package de.htmlapk.builder;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Toolbar;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Zuschneiden des App-Icons. Bekommt das Bild als Intent-Data, speichert das Ergebnis
 * als PNG unter {@link #resultFile} und meldet RESULT_OK.
 */
public class CropActivity extends Activity {

    public static final int OUTPUT_SIZE = 512;

    private CropView cropView;

    public static File resultFile(android.content.Context c) {
        return new File(c.getCacheDir(), "icon_crop.png");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        float d = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF212121);

        Toolbar toolbar = new Toolbar(this);
        toolbar.setTitle("Icon zuschneiden");
        toolbar.setTitleTextColor(Color.WHITE);
        toolbar.setNavigationIcon(R.drawable.ic_back);
        toolbar.setNavigationOnClickListener(v -> finish());
        root.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (56 * d)));

        cropView = new CropView(this);
        root.addView(cropView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView hint = new TextView(this);
        hint.setText("Verschieben mit einem Finger, zoomen mit zwei. Der Kreis zeigt, was bei runden Icons sichtbar bleibt.");
        hint.setTextColor(0xFFBDBDBD);
        hint.setTextSize(13);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding((int) (16 * d), (int) (8 * d), (int) (16 * d), 0);
        root.addView(hint);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding((int) (8 * d), (int) (8 * d), (int) (8 * d), (int) (8 * d));
        bar.addView(button("Drehen", v -> cropView.rotate(), false), weight());
        bar.addView(button("Einpassen", v -> cropView.fit(), false), weight());
        bar.addView(button("Füllen", v -> cropView.fill(), false), weight());
        bar.addView(button("Übernehmen", v -> accept(), true), weight());
        root.addView(bar);

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        setContentView(root);

        Uri uri = getIntent().getData();
        try {
            cropView.setBitmap(decode(uri));
        } catch (Exception e) {
            Toast.makeText(this, "Bild konnte nicht gelesen werden", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private Button button(String text, android.view.View.OnClickListener l, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        if (primary) {
            b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFE8622C));
            b.setTextColor(Color.WHITE);
        }
        return b;
    }

    private static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private void accept() {
        if (!cropView.hasBitmap()) return;
        Bitmap out = cropView.crop(OUTPUT_SIZE);
        try (OutputStream os = new FileOutputStream(resultFile(this))) {
            out.compress(Bitmap.CompressFormat.PNG, 100, os);
            setResult(RESULT_OK);
            finish();
        } catch (IOException e) {
            Toast.makeText(this, "Speichern fehlgeschlagen: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** Lädt das Bild verkleinert (max. ~2048 px) und dreht es laut EXIF richtig herum. */
    private Bitmap decode(Uri uri) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0) throw new IOException("Kein Bild");
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2048) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap bmp;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            bmp = BitmapFactory.decodeStream(in, null, opts);
        }
        if (bmp == null) throw new IOException("Unbekanntes Bildformat");

        int degrees = 0;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            int o = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
            if (o == ExifInterface.ORIENTATION_ROTATE_90) degrees = 90;
            else if (o == ExifInterface.ORIENTATION_ROTATE_180) degrees = 180;
            else if (o == ExifInterface.ORIENTATION_ROTATE_270) degrees = 270;
        } catch (Exception ignored) {
            // PNG, WebP usw. haben oft keine EXIF-Daten
        }
        if (degrees != 0) {
            Matrix m = new Matrix();
            m.postRotate(degrees);
            Bitmap r = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (r != bmp) bmp.recycle();
            bmp = r;
        }
        return bmp;
    }
}
