package de.htmlapk.builder;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import de.htmlapk.builder.core.ApkAssembler;
import de.htmlapk.builder.core.AppConfig;

/**
 * Ein gespeichertes App-Projekt. Alles liegt in files/projects/&lt;id&gt;/:
 * project.json (Felder), content.bin (Kopie der HTML/ZIP), icon.png (zugeschnitten),
 * icon_source (Originalbild zum erneuten Zuschneiden) und last.apk (zuletzt gebaut).
 */
public final class Project {

    public final String id;
    private final File dir;

    public String apkName = "";
    public String name = "";
    public String pkg = "";
    public String url = "";
    public String contentName;
    public String iconSourceName;
    public String versionName = "1.0";
    public int versionCode = 1;
    public boolean nameManual, packageManual;

    /** Alle Laufzeit-Optionen der App (Anzeige, Splash, WebView). */
    public AppConfig opts = new AppConfig();
    public boolean permInternet = true, permMic, permCamera, permLocation, permVibrate, permNotifications;
    /** Signaturschlüssel (siehe KeyManager); "default" = Standardschlüssel des Builders. */
    public String keyId = KeyManager.DEFAULT_ID;

    public int lastBuiltCode;
    public String lastBuiltName;
    public long lastBuiltAt, createdAt, updatedAt;

    private Project(File dir) {
        this.dir = dir;
        this.id = dir.getName();
    }

    // ------------------------------------------------------------ Dateien

    public static File root(Context c) {
        File r = new File(c.getFilesDir(), "projects");
        r.mkdirs();
        return r;
    }

    public File dir() { return dir; }
    public File contentFile() { return new File(dir, "content.bin"); }
    public File iconFile() { return new File(dir, "icon.png"); }
    public File iconSourceFile() { return new File(dir, "icon_source"); }
    public File lastApkFile() { return new File(dir, "last.apk"); }
    public File splashFile() { return new File(dir, "splash.png"); }

    public boolean hasContent() { return contentFile().isFile() && contentFile().length() > 0; }
    public boolean hasIcon() { return iconFile().isFile(); }

    /** Nichts eingetragen, nichts gewählt, nie gebaut. */
    public boolean isBlank() {
        return apkName.trim().isEmpty() && name.trim().isEmpty() && url.trim().isEmpty()
                && !hasContent() && !hasIcon() && lastBuiltCode == 0;
    }

    public String displayName() {
        if (!name.trim().isEmpty()) return name.trim();
        if (!apkName.trim().isEmpty()) return apkName.trim();
        return "Neues Projekt";
    }

    // ------------------------------------------------------- Laden/Speichern

    public static Project create(Context c) {
        SecureRandom r = new SecureRandom();
        String id = Long.toString(System.currentTimeMillis(), 36) + Integer.toString(r.nextInt(1 << 20), 36);
        File d = new File(root(c), id);
        d.mkdirs();
        Project p = new Project(d);
        p.createdAt = System.currentTimeMillis();
        p.save();
        return p;
    }

    public static Project load(Context c, String id) {
        if (id == null || id.contains("/") || id.contains("..")) return null;
        File d = new File(root(c), id);
        File json = new File(d, "project.json");
        if (!json.isFile()) return null;
        try (InputStream in = new FileInputStream(json)) {
            JSONObject o = new JSONObject(new String(ApkAssembler.readAll(in), StandardCharsets.UTF_8));
            Project p = new Project(d);
            p.apkName = o.optString("apkName", "");
            p.name = o.optString("name", "");
            p.pkg = o.optString("pkg", "");
            p.url = o.optString("url", "");
            p.contentName = o.optString("contentName", null);
            p.iconSourceName = o.optString("iconSourceName", null);
            p.versionName = o.optString("versionName", "1.0");
            p.versionCode = o.optInt("versionCode", 1);
            p.nameManual = o.optBoolean("nameManual");
            p.packageManual = o.optBoolean("packageManual");
            JSONObject opts = o.optJSONObject("opts");
            if (opts != null) {
                readOptions(opts, p.opts);
            } else {
                // Projekte aus Version 2.0/2.1
                p.opts.fullscreen = o.optBoolean("fullscreen");
                p.opts.zoom = o.optBoolean("zoom");
                p.opts.keepScreenOn = o.optBoolean("keepScreenOn");
                int ori = o.optInt("orientation");
                p.opts.orientation = ori == 1 ? "portrait" : ori == 2 ? "landscape" : "auto";
            }
            p.permNotifications = o.optBoolean("permNotifications");
            p.keyId = o.optString("keyId", KeyManager.DEFAULT_ID);
            p.permInternet = o.optBoolean("permInternet", true);
            p.permMic = o.optBoolean("permMic");
            p.permCamera = o.optBoolean("permCamera");
            p.permLocation = o.optBoolean("permLocation");
            p.permVibrate = o.optBoolean("permVibrate");
            p.lastBuiltCode = o.optInt("lastBuiltCode");
            p.lastBuiltName = o.optString("lastBuiltName", null);
            p.lastBuiltAt = o.optLong("lastBuiltAt");
            p.createdAt = o.optLong("createdAt");
            p.updatedAt = o.optLong("updatedAt");
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    public void save() {
        updatedAt = System.currentTimeMillis();
        try {
            JSONObject o = new JSONObject();
            o.put("apkName", apkName);
            o.put("name", name);
            o.put("pkg", pkg);
            o.put("url", url);
            if (contentName != null) o.put("contentName", contentName);
            if (iconSourceName != null) o.put("iconSourceName", iconSourceName);
            o.put("versionName", versionName);
            o.put("versionCode", versionCode);
            o.put("nameManual", nameManual);
            o.put("packageManual", packageManual);
            o.put("opts", writeOptions(opts));
            o.put("permNotifications", permNotifications);
            o.put("keyId", keyId);
            o.put("permInternet", permInternet);
            o.put("permMic", permMic);
            o.put("permCamera", permCamera);
            o.put("permLocation", permLocation);
            o.put("permVibrate", permVibrate);
            o.put("lastBuiltCode", lastBuiltCode);
            if (lastBuiltName != null) o.put("lastBuiltName", lastBuiltName);
            o.put("lastBuiltAt", lastBuiltAt);
            o.put("createdAt", createdAt);
            o.put("updatedAt", updatedAt);
            dir.mkdirs();
            File tmp = new File(dir, "project.json.tmp");
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(o.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            File json = new File(dir, "project.json");
            if (!tmp.renameTo(json)) throw new IOException("rename");
        } catch (Exception ignored) {
            // Speichern ist best effort; beim nächsten Mal wird es erneut versucht
        }
    }

    /** Speichert alle öffentlichen Felder von AppConfig (boolean/int/String) per Reflection. */
    static JSONObject writeOptions(AppConfig c) throws org.json.JSONException {
        JSONObject o = new JSONObject();
        for (java.lang.reflect.Field f : AppConfig.class.getFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            try {
                o.put(f.getName(), f.get(c));
            } catch (IllegalAccessException ignored) {
            }
        }
        return o;
    }

    static void readOptions(JSONObject o, AppConfig c) {
        for (java.lang.reflect.Field f : AppConfig.class.getFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || !o.has(f.getName())) continue;
            try {
                Class<?> t = f.getType();
                if (t == boolean.class) f.setBoolean(c, o.getBoolean(f.getName()));
                else if (t == int.class) f.setInt(c, o.getInt(f.getName()));
                else if (t == String.class) f.set(c, o.getString(f.getName()));
            } catch (Exception ignored) {
                // falscher Typ: Standardwert behalten
            }
        }
    }

    public static List<Project> list(Context c) {
        List<Project> out = new ArrayList<>();
        File[] dirs = root(c).listFiles();
        if (dirs != null) {
            for (File d : dirs) {
                Project p = load(c, d.getName());
                if (p != null) out.add(p);
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(b.updatedAt, a.updatedAt));
        return out;
    }

    public void delete() {
        deleteRecursive(dir);
    }

    private static void deleteRecursive(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    public static void copy(InputStream in, File dest) throws IOException {
        File tmp = new File(dest.getPath() + ".tmp");
        try (InputStream is = in; OutputStream out = new FileOutputStream(tmp)) {
            ApkAssembler.copy(is, out);
        }
        if (!tmp.renameTo(dest)) throw new IOException("Datei konnte nicht gespeichert werden");
    }
}
