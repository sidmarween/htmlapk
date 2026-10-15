package de.htmlapk.builder;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

/** Startbild: wird 4 Sekunden gezeigt (Tippen überspringt), dann öffnet sich der Builder. */
public class SplashActivity extends Activity {

    private static final long DURATION_MS = 4000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable next = this::openBuilder;
    private boolean opened;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ImageView image = new ImageView(this);
        image.setImageResource(R.drawable.splash);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setBackgroundColor(0xFFA7DCF4);
        image.setContentDescription("HTML in APK");
        image.setOnClickListener(v -> openBuilder());
        setContentView(image);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!opened) handler.postDelayed(next, DURATION_MS);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(next);
        super.onPause();
    }

    private void openBuilder() {
        if (opened) return;
        opened = true;
        handler.removeCallbacks(next);
        startActivity(new Intent(this, MainActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }
}
