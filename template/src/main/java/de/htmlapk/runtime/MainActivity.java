package de.htmlapk.runtime;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.Manifest;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.MimeTypeMap;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

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

/**
 * Laufzeit der erzeugten Apps: ein Vollbild-WebView, der die HTML-Dateien aus
 * assets/www anzeigt (oder eine Website-URL lädt). Einstellungen kommen aus
 * assets/htmlapk.json, die der Builder schreibt.
 *
 * Lokale Dateien werden über https://appassets.androidplatform.net/www/... ausgeliefert
 * statt über file://. So funktionieren fetch(), Module und localStorage wie im Browser.
 */
public class MainActivity extends Activity {

    private static final String ASSET_HOST = "appassets.androidplatform.net";
    private static final String ASSET_BASE = "https://" + ASSET_HOST + "/";
    private static final int REQ_FILE_CHOOSER = 1001;
    private static final int REQ_PERMISSIONS = 1002;

    private WebView webView;
    private String startHost;
    private boolean fullscreen;
    private ValueCallback<Uri[]> pendingFileCallback;

    // Offene Anfragen der Webseite, während Android den Nutzer fragt
    private PermissionRequest pendingMedia;
    private String[] pendingMediaResources;
    private String pendingGeoOrigin;
    private GeolocationPermissions.Callback pendingGeoCallback;
    private Set<String> declaredPermissions;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        JSONObject config = readConfig();
        fullscreen = config.optBoolean("fullscreen", false);
        applyOrientation(config.optString("orientation", "auto"));
        if (fullscreen) {
            requestWindowFeature(Window.FEATURE_NO_TITLE);
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
        if (config.optBoolean("keepScreenOn", false)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        webView = new WebView(this);
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.setBackgroundColor(0xFFFFFFFF);
        root.addView(webView);
        setContentView(root);
        // Android 15+ zeichnet sonst unter Status- und Navigationsleiste.
        // Im Vollbild sollen die Inhalte bewusst den ganzen Bildschirm nutzen.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (fullscreen) {
                v.setPadding(0, 0, 0, insets.getSystemWindowInsetBottom() > 200
                        ? insets.getSystemWindowInsetBottom() : 0); // nur Tastatur
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets.consumeSystemWindowInsets();
        });

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setAllowFileAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setGeolocationEnabled(isDeclared(Manifest.permission.ACCESS_FINE_LOCATION)
                || isDeclared(Manifest.permission.ACCESS_COARSE_LOCATION));
        boolean zoom = config.optBoolean("zoom", false);
        s.setSupportZoom(zoom);
        s.setBuiltInZoomControls(zoom);
        s.setDisplayZoomControls(false);

        webView.setWebViewClient(new AppWebViewClient());
        webView.setWebChromeClient(new AppChromeClient());

        String start = config.optString("start", "index.html").trim();
        String startUrl;
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
    }

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
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
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
        requestPermissions(new String[]{fine, coarse}, REQ_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
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

    // ---------------------------------------------------------------------

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
    }

    private class AppChromeClient extends WebChromeClient {
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
