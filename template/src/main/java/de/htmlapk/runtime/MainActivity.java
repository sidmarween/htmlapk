package de.htmlapk.runtime;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Laufzeit der erzeugten Apps: ein WebView, der die HTML-Dateien aus assets/www anzeigt
 * (oder eine Website-URL lädt). Alle Einstellungen kommen aus assets/htmlapk.json,
 * die der Builder schreibt.
 *
 * Lokale Dateien werden über https://appassets.androidplatform.net/www/... ausgeliefert
 * statt über file://. So funktionieren fetch(), Module und localStorage wie im Browser.
 */
public class MainActivity extends Activity {

    private static final String ASSET_HOST = "appassets.androidplatform.net";
    private static final String ASSET_BASE = "https://" + ASSET_HOST + "/";
    private static final String PERM_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS";
    private static final int REQ_FILE_CHOOSER = 1001;
    private static final int REQ_PERMISSIONS = 1002;
    private static final int REQ_NOTIFICATIONS = 1003;

    private WebView webView;
    private PullToRefreshLayout pullLayout;
    private ProgressBar progressBar;
    private View offlineView;
    private FrameLayout root;
    private String startHost;
    private String startUrl;
    private float density;

    // Einstellungen
    private boolean fullscreen, backNavigation, exitConfirm, offlinePage, showProgress;
    private int statusBarColor = Color.BLACK;

    private boolean mainFrameError;
    private ValueCallback<Uri[]> pendingFileCallback;
    private PermissionRequest pendingMedia;
    private String[] pendingMediaResources;
    private String pendingGeoOrigin;
    private GeolocationPermissions.Callback pendingGeoCallback;
    private Set<String> declaredPermissions;
    private int notificationId = 1;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;

        JSONObject config = readConfig();
        JSONObject web = config.optJSONObject("web");
        if (web == null) web = new JSONObject();
        JSONObject splash = config.optJSONObject("splash");
        if (splash == null) splash = new JSONObject();
        boolean splashOn = splash.optBoolean("enabled", false) && savedInstanceState == null;

        fullscreen = config.optBoolean("fullscreen", false);
        backNavigation = web.optBoolean("backNavigation", true);
        exitConfirm = web.optBoolean("exitConfirm", false);
        offlinePage = web.optBoolean("offlinePage", false);
        showProgress = web.optBoolean("progressBar", false);
        boolean titleBar = config.optBoolean("titleBar", false) && !fullscreen;
        int titleColor = parseColor(config.optString("titleBarColor", "#212121"), 0xFF212121);
        int splashBg = parseColor(splash.optString("bg", "#0F1318"), 0xFF0F1318);
        int accent = parseColor(splash.optString("loaderColor", "#00E5CC"), 0xFF00E5CC);

        applyOrientation(config.optString("orientation", "auto"));
        if (fullscreen) {
            requestWindowFeature(Window.FEATURE_NO_TITLE);
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN);
            if (Build.VERSION.SDK_INT >= 28 && config.optBoolean("cutout", false)) {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                getWindow().setAttributes(lp);
            }
        }
        if (config.optBoolean("keepScreenOn", false)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        if (titleBar) statusBarColor = titleColor;
        else statusBarColor = 0xFF000000;
        setStatusBar(splashOn ? splashBg : statusBarColor);
        getWindow().setBackgroundDrawable(new ColorDrawable(splashOn ? splashBg : Color.WHITE));

        // ---------------------------------------------------------- Layout
        root = new FrameLayout(this);
        root.setBackgroundColor(splashOn ? splashBg : Color.WHITE);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(Color.WHITE);
        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (titleBar) column.addView(makeTitleBar(titleColor), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        FrameLayout content = new FrameLayout(this);
        column.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        webView = new WebView(this);
        pullLayout = new PullToRefreshLayout(this, webView, accent);
        pullLayout.setRefreshEnabled(web.optBoolean("pullToRefresh", false));
        pullLayout.setOnRefresh(() -> webView.reload());
        content.addView(pullLayout, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setIndeterminate(false);
        progressBar.setProgressTintList(ColorStateList.valueOf(accent));
        progressBar.setProgressBackgroundTintList(ColorStateList.valueOf(0x00000000));
        progressBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3), Gravity.TOP);
        content.addView(progressBar, plp);
        // Die Plattform-Leiste hat oben/unten etwas Rand; leicht nach oben schieben
        progressBar.setScaleY(2f);

        offlineView = makeOfflineView(accent);
        offlineView.setVisibility(View.GONE);
        content.addView(offlineView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (config.optBoolean("watermark", false)) {
            TextView mark = new TextView(this);
            mark.setText("Erstellt mit HTML APK Builder");
            mark.setTextSize(10);
            mark.setTextColor(0xE6FFFFFF);
            mark.setPadding(dp(8), dp(3), dp(8), dp(3));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0x80000000);
            bg.setCornerRadius(dp(10));
            mark.setBackground(bg);
            FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.END);
            mlp.setMargins(0, 0, dp(8), dp(8));
            content.addView(mark, mlp);
        }

        setContentView(root);
        // Android 15+ zeichnet sonst unter Status- und Navigationsleiste.
        // Im Vollbild sollen die Inhalte bewusst den ganzen Bildschirm nutzen.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (fullscreen) {
                v.setPadding(0, 0, 0, insets.getSystemWindowInsetBottom() > dp(80)
                        ? insets.getSystemWindowInsetBottom() : 0); // nur Tastatur
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets.consumeSystemWindowInsets();
        });

        // -------------------------------------------------------- WebView
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(web.optBoolean("javascript", true));
        s.setDomStorageEnabled(web.optBoolean("domStorage", true));
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        boolean desktop = config.optBoolean("desktopMode", false);
        s.setUseWideViewPort(desktop || web.optBoolean("wideViewport", true));
        s.setLoadWithOverviewMode(desktop || web.optBoolean("overview", true));
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setCacheMode(cacheMode(web.optString("cacheMode", "default")));
        s.setMixedContentMode(mixedMode(web.optString("mixedContent", "compat")));
        s.setGeolocationEnabled(isDeclared(Manifest.permission.ACCESS_FINE_LOCATION)
                || isDeclared(Manifest.permission.ACCESS_COARSE_LOCATION));
        boolean zoom = config.optBoolean("zoom", false);
        s.setSupportZoom(zoom);
        s.setBuiltInZoomControls(zoom);
        s.setDisplayZoomControls(false);
        if (desktop) s.setUserAgentString(desktopUserAgent(s.getUserAgentString()));

        if (isDeclared(PERM_NOTIFICATIONS) || Build.VERSION.SDK_INT < 33) {
            webView.addJavascriptInterface(new NotifyBridge(), "HtmlApkNotify");
        }
        webView.setWebViewClient(new AppWebViewClient());
        webView.setWebChromeClient(new AppChromeClient());

        String start = config.optString("start", "index.html").trim();
        if (start.startsWith("http://") || start.startsWith("https://")) {
            startUrl = start;
        } else {
            while (start.startsWith("/")) start = start.substring(1);
            startUrl = ASSET_BASE + "www/" + start;
        }
        startHost = Uri.parse(startUrl).getHost();

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(startUrl);
        }

        if (splashOn) showSplash(splash, splashBg, accent);
    }

    // ------------------------------------------------------------ Bausteine

    private View makeTitleBar(int color) {
        TextView title = new TextView(this);
        CharSequence label = getApplicationInfo().loadLabel(getPackageManager());
        title.setText(label);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(isLight(color) ? 0xFF212121 : Color.WHITE);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(16), 0, dp(16), 0);
        title.setSingleLine(true);
        title.setBackgroundColor(color);
        title.setElevation(dp(4));
        return title;
    }

    private View makeOfflineView(int accent) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(Color.WHITE);
        box.setPadding(dp(32), dp(32), dp(32), dp(32));
        box.setClickable(true);

        TextView icon = new TextView(this);
        icon.setText("⚠");
        icon.setTextSize(48);
        icon.setTextColor(0xFF9E9E9E);
        icon.setGravity(Gravity.CENTER);
        box.addView(icon);

        TextView title = new TextView(this);
        title.setText("Keine Internetverbindung");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFF212121);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(12), 0, dp(6));
        box.addView(title);

        TextView text = new TextView(this);
        text.setText("Prüfe deine Verbindung und versuche es erneut.");
        text.setTextSize(15);
        text.setTextColor(0xFF757575);
        text.setGravity(Gravity.CENTER);
        box.addView(text);

        Button retry = new Button(this);
        retry.setText("Erneut versuchen");
        retry.setAllCaps(false);
        retry.setTextColor(isLight(accent) ? 0xFF212121 : Color.WHITE);
        retry.setBackgroundTintList(ColorStateList.valueOf(accent));
        retry.setOnClickListener(v -> {
            offlineView.setVisibility(View.GONE);
            mainFrameError = false;
            webView.reload();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(20);
        box.addView(retry, lp);
        return box;
    }

    /** Eigener Startbildschirm der App (Logo + Ladeanimation), danach Überblendung. */
    private void showSplash(JSONObject sp, int bg, int accent) {
        final FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(bg);
        overlay.setClickable(true);

        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER_HORIZONTAL);
        overlay.addView(center, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        int iconSize = dp(clamp(sp.optInt("iconSize", 180), 16, 400));
        final float radius = dp(clamp(sp.optInt("iconRadius", 16), 0, 200));
        ImageView img = new ImageView(this);
        int w = iconSize, h = iconSize;
        Bitmap custom = null;
        if (sp.optBoolean("custom", false)) {
            try (InputStream in = getAssets().open("splash.png")) {
                custom = BitmapFactory.decodeStream(in);
            } catch (IOException ignored) {
            }
        }
        if (custom != null) {
            img.setImageBitmap(custom);
            img.setScaleType(ImageView.ScaleType.FIT_XY);
            h = Math.round(iconSize * (custom.getHeight() / (float) custom.getWidth()));
        } else {
            try {
                img.setImageDrawable(getPackageManager().getApplicationIcon(getPackageName()));
            } catch (PackageManager.NameNotFoundException ignored) {
            }
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        }
        img.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        img.setClipToOutline(true);
        center.addView(img, new LinearLayout.LayoutParams(w, h));

        String style = sp.optString("loader", "circle");
        if (!"none".equals(style)) {
            LoaderView loader = new LoaderView(this);
            loader.setColor(accent);
            loader.setThickness(dp(clamp(sp.optInt("thickness", 4), 1, 24)));
            int loaderSize = dp(clamp(sp.optInt("loaderSize", 48), 8, 200));
            LinearLayout.LayoutParams llp;
            if ("bar".equals(style)) {
                loader.setStyle(LoaderView.STYLE_BAR);
                llp = new LinearLayout.LayoutParams(Math.max(w, loaderSize * 3),
                        dp(clamp(sp.optInt("thickness", 4), 1, 24)) + dp(2));
            } else {
                llp = new LinearLayout.LayoutParams(loaderSize, loaderSize);
            }
            llp.topMargin = dp(clamp(sp.optInt("spacing", 28), 0, 200));
            center.addView(loader, llp);
        }

        root.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        long duration = clamp(sp.optInt("duration", 2000), 0, 15000);
        long fade = clamp(sp.optInt("fade", 400), 0, 3000);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            root.setBackgroundColor(Color.WHITE);
            getWindow().setBackgroundDrawable(new ColorDrawable(Color.WHITE));
            setStatusBar(statusBarColor);
            overlay.animate().alpha(0f).setDuration(fade)
                    .withEndAction(() -> root.removeView(overlay)).start();
        }, duration);
    }

    // ------------------------------------------------------------ Lebenszyklus

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && fullscreen) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN);
        }
    }

    @Override
    public void onBackPressed() {
        if (backNavigation && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        if (exitConfirm) {
            new AlertDialog.Builder(this)
                    .setMessage("App beenden?")
                    .setNegativeButton("Abbrechen", null)
                    .setPositiveButton("Beenden", (d, w) -> finish())
                    .show();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        webView.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
    }

    @Override
    protected void onDestroy() {
        webView.destroy();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE_CHOOSER && pendingFileCallback != null) {
            pendingFileCallback.onReceiveValue(
                    WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            pendingFileCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    // ------------------------------------------------------- Berechtigungen

    /** Steht die Berechtigung im Manifest? (Der Builder entfernt nicht gewählte.) */
    private boolean isDeclared(String permission) {
        if (declaredPermissions == null) {
            declaredPermissions = new HashSet<>();
            try {
                PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(),
                        PackageManager.GET_PERMISSIONS);
                if (pi.requestedPermissions != null) {
                    declaredPermissions.addAll(Arrays.asList(pi.requestedPermissions));
                }
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return declaredPermissions.contains(permission);
    }

    private boolean isGranted(String permission) {
        return Build.VERSION.SDK_INT < 23
                || checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private static String androidPermissionFor(String webResource) {
        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(webResource)) {
            return Manifest.permission.RECORD_AUDIO;
        }
        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(webResource)) {
            return Manifest.permission.CAMERA;
        }
        return null; // andere Ressourcen (z. B. DRM, MIDI) werden nicht freigegeben
    }

    /** Mikrofon/Kamera für getUserMedia() */
    private void handleMediaRequest(PermissionRequest request) {
        List<String> allowed = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String res : request.getResources()) {
            String perm = androidPermissionFor(res);
            if (perm == null || !isDeclared(perm)) continue;
            allowed.add(res);
            if (!isGranted(perm)) missing.add(perm);
        }
        if (allowed.isEmpty()) {
            request.deny();
            return;
        }
        if (missing.isEmpty()) {
            request.grant(allowed.toArray(new String[0]));
            return;
        }
        if (pendingMedia != null) pendingMedia.deny();
        pendingMedia = request;
        pendingMediaResources = allowed.toArray(new String[0]);
        requestPermissions(missing.toArray(new String[0]), REQ_PERMISSIONS);
    }

    /** Standort für navigator.geolocation */
    private void handleGeoRequest(String origin, GeolocationPermissions.Callback callback) {
        String fine = Manifest.permission.ACCESS_FINE_LOCATION;
        String coarse = Manifest.permission.ACCESS_COARSE_LOCATION;
        if (!isDeclared(fine) && !isDeclared(coarse)) {
            callback.invoke(origin, false, false);
            return;
        }
        if (isGranted(fine) || isGranted(coarse)) {
            callback.invoke(origin, true, false);
            return;
        }
        if (pendingGeoCallback != null) pendingGeoCallback.invoke(pendingGeoOrigin, false, false);
        pendingGeoOrigin = origin;
        pendingGeoCallback = callback;
        List<String> ask = new ArrayList<>();
        if (isDeclared(fine)) ask.add(fine);
        if (isDeclared(coarse)) ask.add(coarse);
        requestPermissions(ask.toArray(new String[0]), REQ_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_NOTIFICATIONS) {
            getSharedPreferences("htmlapk", MODE_PRIVATE).edit().putBoolean("notifAsked", true).apply();
            String state = notificationState();
            webView.evaluateJavascript("window.__htmlapkNotifyResult&&window.__htmlapkNotifyResult('"
                    + state + "')", null);
            return;
        }
        if (requestCode != REQ_PERMISSIONS) return;
        if (pendingMedia != null) {
            List<String> granted = new ArrayList<>();
            for (String res : pendingMediaResources) {
                if (isGranted(androidPermissionFor(res))) granted.add(res);
            }
            if (granted.isEmpty()) pendingMedia.deny();
            else pendingMedia.grant(granted.toArray(new String[0]));
            pendingMedia = null;
            pendingMediaResources = null;
        }
        if (pendingGeoCallback != null) {
            boolean ok = isGranted(Manifest.permission.ACCESS_FINE_LOCATION)
                    || isGranted(Manifest.permission.ACCESS_COARSE_LOCATION);
            pendingGeoCallback.invoke(pendingGeoOrigin, ok, false);
            pendingGeoCallback = null;
            pendingGeoOrigin = null;
        }
    }

    // ------------------------------------------------------ Benachrichtigungen

    private String notificationState() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        boolean enabled = Build.VERSION.SDK_INT < 24 || nm.areNotificationsEnabled();
        if (Build.VERSION.SDK_INT >= 33) {
            if (!isDeclared(PERM_NOTIFICATIONS)) return "denied";
            if (isGranted(PERM_NOTIFICATIONS)) return enabled ? "granted" : "denied";
            boolean asked = getSharedPreferences("htmlapk", MODE_PRIVATE).getBoolean("notifAsked", false);
            return asked ? "denied" : "default";
        }
        return enabled ? "granted" : "denied";
    }

    private void showNotification(String title, String body) {
        if (!"granted".equals(notificationState())) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            if (nm.getNotificationChannel("web") == null) {
                nm.createNotificationChannel(new NotificationChannel("web", "Benachrichtigungen",
                        NotificationManager.IMPORTANCE_DEFAULT));
            }
            b = new Notification.Builder(this, "web");
        } else {
            b = new Notification.Builder(this);
        }
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        b.setSmallIcon(getApplicationInfo().icon)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(pi);
        nm.notify(notificationId++, b.build());
    }

    /** Für Webseiten: window.HtmlApkNotify bzw. das Standard-Objekt Notification. */
    private class NotifyBridge {
        @JavascriptInterface
        public void notify(String title, String body) {
            runOnUiThread(() -> showNotification(title == null ? "" : title, body == null ? "" : body));
        }

        @JavascriptInterface
        public String permission() {
            return notificationState();
        }

        @JavascriptInterface
        public void requestPermission() {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 33 && isDeclared(PERM_NOTIFICATIONS)
                        && !isGranted(PERM_NOTIFICATIONS)) {
                    requestPermissions(new String[]{PERM_NOTIFICATIONS}, REQ_NOTIFICATIONS);
                } else {
                    webView.evaluateJavascript("window.__htmlapkNotifyResult&&window.__htmlapkNotifyResult('"
                            + notificationState() + "')", null);
                }
            });
        }
    }

    /** Macht "new Notification(...)" und "Notification.requestPermission()" in der App nutzbar. */
    private static final String NOTIFICATION_SHIM =
            "(function(){if(!window.HtmlApkNotify||window.__htmlapkShim)return;window.__htmlapkShim=1;"
            + "var B=window.HtmlApkNotify,q=[];"
            + "function N(t,o){o=o||{};this.title=String(t);this.body=String(o.body||'');"
            + "B.notify(this.title,this.body);}"
            + "N.prototype.close=function(){};N.prototype.addEventListener=function(){};"
            + "Object.defineProperty(N,'permission',{get:function(){return B.permission();}});"
            + "N.requestPermission=function(cb){return new Promise(function(r){"
            + "q.push(function(s){if(cb)cb(s);r(s);});B.requestPermission();});};"
            + "window.__htmlapkNotifyResult=function(s){var a=q;q=[];a.forEach(function(f){f(s);});};"
            + "window.Notification=N;})();";

    // ---------------------------------------------------------------- Helfer

    private JSONObject readConfig() {
        try (InputStream in = getAssets().open("htmlapk.json")) {
            return new JSONObject(new String(readAll(in), "UTF-8"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void applyOrientation(String o) {
        switch (o) {
            case "portrait":
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
                break;
            case "landscape":
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                break;
            default:
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }
    }

    private void setStatusBar(int color) {
        if (fullscreen) return;
        Window w = getWindow();
        w.setStatusBarColor(color);
        if (Build.VERSION.SDK_INT >= 23) {
            View decor = w.getDecorView();
            int flags = decor.getSystemUiVisibility();
            if (isLight(color)) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            else flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            decor.setSystemUiVisibility(flags);
        }
    }

    private static int cacheMode(String m) {
        switch (m) {
            case "cache_else_network": return WebSettings.LOAD_CACHE_ELSE_NETWORK;
            case "no_cache": return WebSettings.LOAD_NO_CACHE;
            case "cache_only": return WebSettings.LOAD_CACHE_ONLY;
            default: return WebSettings.LOAD_DEFAULT;
        }
    }

    private static int mixedMode(String m) {
        switch (m) {
            case "always": return WebSettings.MIXED_CONTENT_ALWAYS_ALLOW;
            case "never": return WebSettings.MIXED_CONTENT_NEVER_ALLOW;
            default: return WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE;
        }
    }

    /** Desktop-Browser vortäuschen, Chrome-Version aus dem echten User-Agent übernehmen. */
    private static String desktopUserAgent(String mobile) {
        String chrome = "120.0.0.0";
        Matcher m = Pattern.compile("Chrome/([\\d.]+)").matcher(mobile == null ? "" : mobile);
        if (m.find()) chrome = m.group(1);
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/"
                + chrome + " Safari/537.36";
    }

    private static int parseColor(String s, int fallback) {
        try {
            return Color.parseColor(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static boolean isLight(int color) {
        double l = 0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color);
        return l > 170;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private int dp(int v) {
        return Math.round(v * density);
    }

    /** true = Link wird außerhalb der App geöffnet. */
    private boolean handleNavigation(Uri uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.equals("http") || scheme.equals("https")) {
            String host = uri.getHost();
            if (host == null || host.equals(ASSET_HOST) || host.equals(startHost)) {
                return false;
            }
        }
        try {
            Intent intent = scheme.equals("intent")
                    ? Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                    : new Intent(Intent.ACTION_VIEW, uri);
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            intent.setComponent(null);
            startActivity(intent);
        } catch (Exception ignored) {
            // Kein passendes Programm: Link einfach ignorieren.
        }
        return true;
    }

    private WebResourceResponse serveAsset(Uri uri) {
        String path = uri.getPath() == null ? "" : uri.getPath();
        while (path.startsWith("/")) path = path.substring(1);
        if (path.isEmpty() || path.endsWith("/")) path += "index.html";
        if (path.contains("..")) return notFound();

        InputStream in;
        try {
            in = getAssets().open(path);
        } catch (IOException e) {
            try {
                // Ordner ohne abschließenden Schrägstrich
                in = getAssets().open(path + "/index.html");
                path = path + "/index.html";
            } catch (IOException e2) {
                return notFound();
            }
        }
        String mime = guessMime(path);
        Map<String, String> headers = new HashMap<>();
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Cache-Control", "no-cache");
        String encoding = mime.startsWith("text/") || mime.endsWith("javascript")
                || mime.endsWith("json") || mime.endsWith("xml") ? "UTF-8" : null;
        return new WebResourceResponse(mime, encoding, 200, "OK", headers, in);
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
                new HashMap<String, String>(), null);
    }

    private static String guessMime(String path) {
        int dot = path.lastIndexOf('.');
        String ext = dot >= 0 ? path.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        switch (ext) {
            case "html": case "htm": return "text/html";
            case "js": case "mjs": return "text/javascript";
            case "css": return "text/css";
            case "json": case "map": return "application/json";
            case "svg": return "image/svg+xml";
            case "wasm": return "application/wasm";
            case "txt": return "text/plain";
            case "xml": return "application/xml";
            case "webmanifest": return "application/manifest+json";
        }
        String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return m != null ? m : "application/octet-stream";
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    // ---------------------------------------------------------------------

    private class AppWebViewClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (ASSET_HOST.equals(uri.getHost())) {
                return serveAsset(uri);
            }
            return null;
        }

        @SuppressWarnings("deprecation")
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleNavigation(Uri.parse(url));
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (Build.VERSION.SDK_INT >= 24 && request.isRedirect()) {
                return false;
            }
            return handleNavigation(request.getUrl());
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            mainFrameError = false;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            pullLayout.finishRefresh();
            if (!mainFrameError) offlineView.setVisibility(View.GONE);
            view.evaluateJavascript(NOTIFICATION_SHIM, null);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) onMainFrameError();
        }

        @SuppressWarnings("deprecation")
        @Override
        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            if (Build.VERSION.SDK_INT < 23) onMainFrameError();
        }
    }

    private void onMainFrameError() {
        mainFrameError = true;
        pullLayout.finishRefresh();
        if (offlinePage) offlineView.setVisibility(View.VISIBLE);
    }

    private class AppChromeClient extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int progress) {
            if (!showProgress) return;
            progressBar.setProgress(progress);
            progressBar.setVisibility(progress < 100 ? View.VISIBLE : View.GONE);
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            runOnUiThread(() -> handleMediaRequest(request));
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String origin,
                                                       GeolocationPermissions.Callback callback) {
            runOnUiThread(() -> handleGeoRequest(origin, callback));
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
            if (pendingFileCallback != null) {
                pendingFileCallback.onReceiveValue(null);
            }
            pendingFileCallback = callback;
            try {
                startActivityForResult(params.createIntent(), REQ_FILE_CHOOSER);
            } catch (ActivityNotFoundException e) {
                pendingFileCallback = null;
                return false;
            }
            return true;
        }
    }
}
