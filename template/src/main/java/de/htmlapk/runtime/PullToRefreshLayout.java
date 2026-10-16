package de.htmlapk.runtime;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.webkit.WebView;
import android.widget.FrameLayout;

/**
 * "Zum Aktualisieren ziehen" ohne AndroidX: Steht die Seite ganz oben und wird nach unten
 * gezogen, erscheint ein Kreis; beim Loslassen über der Schwelle wird neu geladen.
 */
public class PullToRefreshLayout extends FrameLayout {

    public interface OnRefresh {
        void refresh();
    }

    private final WebView webView;
    private final FrameLayout bubble;
    private final LoaderView loader;
    private final int touchSlop;
    private final float density;
    private boolean enabled;
    private boolean dragging;
    private boolean refreshing;
    private float downY, downX;
    private OnRefresh listener;

    public PullToRefreshLayout(Context c, WebView webView, int color) {
        super(c);
        this.webView = webView;
        density = c.getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
        addView(webView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        bubble = new FrameLayout(c);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xFFFFFFFF);
        bubble.setBackground(bg);
        bubble.setElevation(6 * density);
        loader = new LoaderView(c);
        loader.setColor(color);
        loader.setThickness(3 * density);
        int pad = Math.round(9 * density);
        bubble.addView(loader, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        bubble.setPadding(pad, pad, pad, pad);
        int size = Math.round(42 * density);
        LayoutParams lp = new LayoutParams(size, size, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        addView(bubble, lp);
        bubble.setTranslationY(-size * 1.5f);
        bubble.setVisibility(GONE);
    }

    public void setRefreshEnabled(boolean on) {
        enabled = on;
    }

    public void setOnRefresh(OnRefresh l) {
        listener = l;
    }

    /** Vom Aufrufer zu melden, sobald die Seite fertig geladen ist. */
    public void finishRefresh() {
        if (!refreshing) return;
        refreshing = false;
        bubble.animate().translationY(-bubble.getHeight() * 1.5f).alpha(0f).setDuration(200)
                .withEndAction(() -> bubble.setVisibility(GONE)).start();
    }

    private float threshold() {
        return 80 * density;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        if (!enabled || refreshing) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = e.getY();
                downX = e.getX();
                dragging = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (e.getPointerCount() > 1) return false;
                float dy = e.getY() - downY;
                float dx = Math.abs(e.getX() - downX);
                if (dy > touchSlop && dy > dx * 1.5f && webView.getScrollY() == 0
                        && !webView.canScrollVertically(-1)) {
                    dragging = true;
                    bubble.setVisibility(VISIBLE);
                    bubble.setAlpha(0f);
                    return true;
                }
                break;
            default:
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!dragging) return super.onTouchEvent(e);
        float pull = Math.max(0, e.getY() - downY) * 0.5f;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                float max = threshold() * 1.4f;
                float y = Math.min(pull, max);
                bubble.setTranslationY(y - bubble.getHeight());
                bubble.setAlpha(Math.min(1f, y / threshold()));
                bubble.setRotation(y * 2);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                if (pull >= threshold() && e.getActionMasked() == MotionEvent.ACTION_UP) {
                    refreshing = true;
                    bubble.animate().translationY(threshold() * 0.6f).setDuration(150).start();
                    if (listener != null) listener.refresh();
                    // Sicherheitsnetz, falls die Seite nie "fertig" meldet
                    postDelayed(this::finishRefresh, 15000);
                } else {
                    bubble.animate().translationY(-bubble.getHeight() * 1.5f).alpha(0f).setDuration(200)
                            .withEndAction(() -> bubble.setVisibility(GONE)).start();
                }
                return true;
            default:
                return true;
        }
    }

    View bubbleView() {
        return bubble;
    }
}
