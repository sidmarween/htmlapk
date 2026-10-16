package de.htmlapk.builder;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import de.htmlapk.builder.core.ApkAssembler;
import de.htmlapk.builder.core.ApkInspector;
import de.htmlapk.builder.core.AppConfig;
import de.htmlapk.builder.core.WebProject;

/**
 * Übernimmt Apps aus anderen Quellen als Projekt:
 *  - beliebige fertige APK (Webdateien, Name, Paket, Version, Icon und – falls erkennbar – Einstellungen)
 *  - Projekt-Export des Builders „HTML to APK“ (metadata.json + content/ + icon/ + keystore/)
 */
public final class ForeignImport {

    /** Schlüssel aus einem fremden Export; das Passwort kennt nur der Nutzer. */
    public static final class ForeignKey {
        public String projectId;
        public String projectName;
        public String alias;
        public byte[] data;
    }

    public static final class Result {
        public Project project;
        public ForeignKey key;
        public final List<String> notes = new ArrayList<>();
    }

    private ForeignImport() {}

    public static boolean isHtmlToApkExport(ZipFile zip) {
        return zip.getEntry("metadata.json") != null && zip.getEntry("project.json") == null;
    }

    // -------------------------------------------------------------------- APK

    public static Result fromApk(Context c, File apk, String fileName) throws Exception {
        ApkInspector.Info info = ApkInspector.inspect(apk);
        Result r = new Result();
        Project p = Project.create(c);
        try {
            String name = info.label != null && !info.label.trim().isEmpty() ? info.label.trim()
                    : WebProject.suggestAppName(fileName == null ? "App" : fileName.replaceAll("(?i)\\.apk$", ""));
            p.name = name;
            p.apkName = name;
            p.nameManual = true;
            if (info.packageName != null && WebProject.isValidPackage(info.packageName)) {
                p.pkg = info.packageName;
                p.packageManual = true;
            }
            setVersion(p, info.versionCode, info.versionName);

            // Einstellungen übernehmen (eigene oder die des Builders „HTML to APK“)
            String start = null;
            byte[] own = info.builderConfigs.get("assets/htmlapk.json");
            if (own != null) {
                start = applyOwnConfig(new JSONObject(new String(own, StandardCharsets.UTF_8)), p);
                r.notes.add("Einstellungen aus dem HTML APK Builder übernommen");
            }
            for (String key : info.builderConfigs.keySet()) {
                if (key.endsWith("/config.json")) {
                    JSONObject cfg = new JSONObject(new String(info.builderConfigs.get(key), StandardCharsets.UTF_8));
                    applyHtmlToApkConfig(cfg, p);
                    String url = firstUrl(cfg);
                    if (url != null && !"local".equals(cfg.optString("mode", "local"))) start = url;
                    r.notes.add("Einstellungen aus „HTML to APK“ übernommen");
                }
            }

            if (start != null && start.startsWith("http")) {
                p.url = start;
                p.permInternet = true;
            } else {
                Project.copy(new ByteArrayInputStream(ApkInspector.webZip(info)), p.contentFile());
                p.contentName = name + " (aus APK, " + info.webFiles.size() + " Dateien).zip";
            }
            if (info.icon != null) {
                Project.copy(new ByteArrayInputStream(info.icon), p.iconFile());
                Project.copy(new ByteArrayInputStream(info.icon), p.iconSourceFile());
                p.iconSourceName = "Icon aus APK";
            }
            p.save();
            r.project = p;
            r.notes.add("Der Signaturschlüssel steckt nicht in einer APK – siehe Hinweis zu Updates");
            return r;
        } catch (Exception e) {
            p.delete();
            throw e;
        }
    }

    // --------------------------------------------------- Export von „HTML to APK“

    public static Result fromHtmlToApkExport(Context c, ZipFile zip) throws Exception {
        JSONObject m = new JSONObject(new String(read(zip, "metadata.json"), StandardCharsets.UTF_8));
        Result r = new Result();
        Project p = Project.create(c);
        try {
            String name = m.optString("appName", m.optString("name", "Importierte App")).trim();
            p.name = name;
            p.apkName = m.optString("name", name).trim();
            p.nameManual = true;
            String pkg = m.optString("packageName", "");
            if (WebProject.isValidPackage(pkg)) {
                p.pkg = pkg;
                p.packageManual = true;
            }
            setVersion(p, m.optInt("versionCode", 1), m.optString("versionName", "1.0"));
            applyHtmlToApkConfig(m, p);

            // Inhalt: Datei unter content/ (HTML oder ZIP) oder URL
            String sourceUrl = m.isNull("sourceUrl") ? null : m.optString("sourceUrl", null);
            ZipEntry content = firstUnder(zip, "content/");
            if (content != null) {
                byte[] data = read(zip, content.getName());
                Project.copy(new ByteArrayInputStream(data), p.contentFile());
                boolean isZip = data.length > 4 && data[0] == 'P' && data[1] == 'K';
                p.contentName = name + (isZip ? ".zip" : ".html");
            } else if (sourceUrl != null && sourceUrl.startsWith("http")) {
                p.url = sourceUrl;
                p.permInternet = true;
            } else {
                r.notes.add("Kein Inhalt im Export gefunden – bitte HTML/ZIP neu wählen");
            }

            ZipEntry icon = firstUnder(zip, "icon/");
            if (icon != null) {
                byte[] data = read(zip, icon.getName());
                Project.copy(new ByteArrayInputStream(data), p.iconFile());
                Project.copy(new ByteArrayInputStream(data), p.iconSourceFile());
                p.iconSourceName = "Icon aus Export";
            }
            ZipEntry splash = firstUnder(zip, "splash/");
            if (splash != null) {
                Project.copy(new ByteArrayInputStream(read(zip, splash.getName())), p.splashFile());
                p.opts.splashCustomImage = true;
            }

            ZipEntry ks = firstUnder(zip, "keystore/");
            if (ks != null) {
                ForeignKey k = new ForeignKey();
                k.projectId = p.id;
                k.projectName = name;
                k.alias = m.optString("keystoreAlias", null);
                k.data = read(zip, ks.getName());
                r.key = k;
            }
            p.save();
            r.project = p;
            return r;
        } catch (Exception e) {
            p.delete();
            throw e;
        }
    }

    // ------------------------------------------------------------ Einstellungen

    /** Die installierte Version ist "zuletzt gebaut"; der nächste Build zählt automatisch weiter. */
    private static void setVersion(Project p, int code, String name) {
        int vc = Math.max(1, code);
        String vn = name == null || name.trim().isEmpty() ? "1.0" : name.trim();
        p.lastBuiltCode = vc;
        p.lastBuiltName = vn;
        p.lastBuiltAt = System.currentTimeMillis();
        p.versionCode = vc + 1;
        p.versionName = WebProject.nextVersionName(vn);
    }

    /** Schlüssel von „HTML to APK“ (metadata.json und config.json in der APK). */
    static void applyHtmlToApkConfig(JSONObject j, Project p) {
        AppConfig c = p.opts;
        if (j.has("showHeader")) c.fullscreen = !j.optBoolean("showHeader", true);
        if (j.has("showActionBar")) c.titleBar = j.optBoolean("showActionBar");
        color(j, "primaryColor", v -> c.titleBarColor = v);
        if (j.has("keepScreenOn")) c.keepScreenOn = j.optBoolean("keepScreenOn");
        if (j.has("showCameraCutout")) c.cutout = j.optBoolean("showCameraCutout");
        if (j.has("desktopMode")) c.desktopMode = j.optBoolean("desktopMode");
        if (j.has("showWatermark")) c.watermark = j.optBoolean("showWatermark");
        if (j.has("orientation")) {
            String o = j.optString("orientation", "").toLowerCase(Locale.ROOT);
            c.orientation = o.contains("portrait") ? "portrait" : o.contains("landscape") ? "landscape" : "auto";
        }
        if (j.has("pullToRefresh")) c.pullToRefresh = j.optBoolean("pullToRefresh");
        if (j.has("noInternetDialog")) c.offlinePage = j.optBoolean("noInternetDialog");
        if (j.has("loadingIndicator")) c.progressBar = j.optBoolean("loadingIndicator");
        if (j.has("backHistoryNavigation")) c.backNavigation = j.optBoolean("backHistoryNavigation");
        if (j.has("exitConfirmationDialog")) c.exitConfirm = j.optBoolean("exitConfirmationDialog");

        // Splash
        if (j.has("splashEnabled") || j.has("hasSplash")) {
            c.splashEnabled = j.optBoolean("splashEnabled") || j.optBoolean("hasSplash");
        }
        color(j, "splashBgColor", v -> c.splashBg = v);
        color(j, "splashLoaderColor", v -> c.splashLoaderColor = v);
        if (j.has("splashLoaderStyle")) {
            String s = j.optString("splashLoaderStyle", "circle").toLowerCase(Locale.ROOT);
            c.splashLoader = s.contains("none") ? "none"
                    : (s.contains("bar") || s.contains("linear") || s.contains("line")) ? "bar" : "circle";
        }
        if (j.optBoolean("splashHideLoader")) c.splashLoader = "none";
        c.splashIconSize = clampInt(j, "splashIconSize", c.splashIconSize, 16, 400);
        c.splashIconRadius = clampInt(j, "splashIconRadius", c.splashIconRadius, 0, 200);
        c.splashLoaderSize = clampInt(j, "splashLoaderSize", c.splashLoaderSize, 8, 200);
        c.splashSpacing = clampInt(j, "splashSpacing", c.splashSpacing, 0, 200);
        c.splashThickness = clampInt(j, "splashLoaderThickness", c.splashThickness, 1, 24);
        if (j.has("splashDuration")) {
            double d = j.optDouble("splashDuration", 2);
            int ms = (int) Math.round(d < 100 ? d * 1000 : d);   // Sekunden oder Millisekunden
            c.splashDuration = Math.max(0, Math.min(15000, ms));
        }
        if (j.has("splashFadeDuration")) c.splashFade = clampInt(j, "splashFadeDuration", c.splashFade, 0, 3000);
        if (j.has("splashFadeOut") && !j.optBoolean("splashFadeOut", true)) c.splashFade = 0;

        // Berechtigungen (Liste oder als JSON-Text)
        JSONArray perms = j.optJSONArray("permissions");
        if (perms == null && j.has("permissionsJson")) {
            try {
                perms = new JSONArray(j.optString("permissionsJson", "[]"));
            } catch (Exception ignored) {
            }
        }
        if (perms != null) {
            p.permInternet = p.permMic = p.permCamera = p.permLocation = p.permVibrate = p.permNotifications = false;
            for (int i = 0; i < perms.length(); i++) {
                String s = perms.optString(i, "");
                if (s.endsWith(".INTERNET") || s.endsWith(".ACCESS_NETWORK_STATE")) p.permInternet = true;
                else if (s.endsWith(".RECORD_AUDIO")) p.permMic = true;
                else if (s.endsWith(".CAMERA")) p.permCamera = true;
                else if (s.endsWith("_LOCATION")) p.permLocation = true;
                else if (s.endsWith(".VIBRATE")) p.permVibrate = true;
                else if (s.endsWith(".POST_NOTIFICATIONS")) p.permNotifications = true;
            }
        }

        // WebView-Einstellungen (Objekt oder JSON-Text)
        JSONObject wv = j.optJSONObject("webViewSettings");
        if (wv == null && j.has("webViewSettingsJson")) {
            try {
                wv = new JSONObject(j.optString("webViewSettingsJson", "{}"));
            } catch (Exception ignored) {
            }
        }
        if (wv != null) {
            if (wv.has("javaScriptEnabled")) c.javascript = wv.optBoolean("javaScriptEnabled", true);
            if (wv.has("domStorageEnabled")) c.domStorage = wv.optBoolean("domStorageEnabled", true);
            if (wv.has("useWideViewPort")) c.wideViewport = wv.optBoolean("useWideViewPort", true);
            if (wv.has("loadWithOverviewMode")) c.overview = wv.optBoolean("loadWithOverviewMode", true);
            if (wv.has("builtInZoomControls")) c.zoom = wv.optBoolean("builtInZoomControls");
            if (wv.has("cacheMode")) {
                String cm = wv.optString("cacheMode", "").toLowerCase(Locale.ROOT);
                c.cacheMode = cm.equals("1") || cm.contains("else") ? "cache_else_network"
                        : cm.equals("2") || cm.contains("no_cache") ? "no_cache"
                        : cm.equals("3") || cm.contains("only") ? "cache_only" : "default";
            }
            if (wv.has("mixedContentMode")) {
                String mm = wv.optString("mixedContentMode", "").toLowerCase(Locale.ROOT);
                c.mixedContent = mm.equals("0") || mm.contains("always") ? "always"
                        : mm.equals("1") || mm.contains("never") ? "never" : "compat";
            }
        }
    }

    /** htmlapk.json aus einer APK dieses Builders; liefert den Start (Datei oder URL). */
    static String applyOwnConfig(JSONObject j, Project p) {
        AppConfig c = p.opts;
        JSONObject splash = j.optJSONObject("splash");
        JSONObject web = j.optJSONObject("web");
        JSONObject flat = new JSONObject();
        try {
            for (String k : new String[]{"fullscreen", "orientation", "zoom", "keepScreenOn", "titleBar",
                    "titleBarColor", "cutout", "desktopMode", "watermark"}) {
                if (j.has(k)) flat.put(k, j.get(k));
            }
            if (splash != null) {
                String[][] map = {{"enabled", "splashEnabled"}, {"bg", "splashBg"}, {"loader", "splashLoader"},
                        {"loaderColor", "splashLoaderColor"}, {"iconSize", "splashIconSize"},
                        {"iconRadius", "splashIconRadius"}, {"loaderSize", "splashLoaderSize"},
                        {"spacing", "splashSpacing"}, {"duration", "splashDuration"},
                        {"thickness", "splashThickness"}, {"fade", "splashFade"}};
                for (String[] kv : map) if (splash.has(kv[0])) flat.put(kv[1], splash.get(kv[0]));
            }
            if (web != null) {
                java.util.Iterator<String> it = web.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    flat.put(k, web.get(k));
                }
            }
        } catch (org.json.JSONException ignored) {
        }
        Project.readOptions(flat, c);
        c.splashCustomImage = false;   // Splash-Bild steckt nicht im Webinhalt
        return j.optString("start", null);
    }

    private interface StrSetter { void set(String v); }

    private static void color(JSONObject j, String key, StrSetter s) {
        if (!j.has(key) || j.isNull(key)) return;
        String n = AppConfig.normalizeColor(j.optString(key, ""));
        if (n != null) s.set(n);
    }

    private static int clampInt(JSONObject j, String key, int def, int min, int max) {
        if (!j.has(key)) return def;
        return Math.max(min, Math.min(max, (int) Math.round(j.optDouble(key, def))));
    }

    private static String firstUrl(JSONObject j) {
        for (String k : new String[]{"url", "sourceUrl", "startUrl", "webUrl", "websiteUrl"}) {
            String v = j.optString(k, "");
            if (v.startsWith("http://") || v.startsWith("https://")) return v;
        }
        return null;
    }

    private static ZipEntry firstUnder(ZipFile zip, String prefix) {
        Enumeration<? extends ZipEntry> en = zip.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            if (!e.isDirectory() && e.getName().startsWith(prefix) && !e.getName().contains("..")) return e;
        }
        return null;
    }

    private static byte[] read(ZipFile zip, String name) throws IOException {
        return ApkAssembler.readAll(zip.getInputStream(zip.getEntry(name)));
    }

    static void write(File f, byte[] data) throws IOException {
        try (OutputStream o = new FileOutputStream(f)) {
            o.write(data);
        }
    }
}
