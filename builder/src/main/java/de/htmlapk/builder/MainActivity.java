package de.htmlapk.builder;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import de.htmlapk.builder.core.ApkAssembler;
import de.htmlapk.builder.core.ApkSigning;
import de.htmlapk.builder.core.SigningKey;
import de.htmlapk.builder.core.WebProject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQ_CONTENT = 1;
    private static final int REQ_ICON = 2;
    private static final int REQ_SAVE_APK = 3;
    private static final int REQ_EXPORT_KEY = 4;
    private static final int REQ_IMPORT_KEY = 5;

    private static final String[] ORIENTATION_LABELS = {"Automatisch", "Hochformat", "Querformat"};
    private static final String[] ORIENTATION_VALUES = {"auto", "portrait", "landscape"};

    private EditText editUrl, editName, editPackage, editVersionName, editVersionCode, editFileName;
    private TextView txtContent, txtIcon, txtStatus, txtVersion;
    private ImageView imgIcon;
    private CheckBox chkFullscreen, chkZoom, chkKeepScreenOn;
    private CheckBox chkPermInternet, chkPermMic, chkPermCamera, chkPermLocation, chkPermVibrate;
    private Spinner spinOrientation;
    private Button btnBuild;
    private ProgressBar progress;
    private LinearLayout resultButtons;

    private Uri contentUri;
    private String contentName;
    private Bitmap iconBitmap;
    private File lastApk;
    private boolean packageEditedByUser;
    private boolean nameEditedByUser;
    private boolean settingNameAutomatically;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setActionBar(findViewById(R.id.toolbar));

        // Abstand zu Statusleiste, Navigationsleiste und Tastatur selbst setzen.
        // Ohne Edge-to-Edge sind diese Werte 0, mit Edge-to-Edge (Android 15+) nicht.
        View root = findViewById(R.id.root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        prefs = getSharedPreferences("builder", MODE_PRIVATE);

        editUrl = findViewById(R.id.editUrl);
        editName = findViewById(R.id.editName);
        editPackage = findViewById(R.id.editPackage);
        editVersionName = findViewById(R.id.editVersionName);
        editVersionCode = findViewById(R.id.editVersionCode);
        editFileName = findViewById(R.id.editFileName);
        txtContent = findViewById(R.id.txtContent);
        txtIcon = findViewById(R.id.txtIcon);
        txtStatus = findViewById(R.id.txtStatus);
        txtVersion = findViewById(R.id.txtVersion);
        imgIcon = findViewById(R.id.imgIcon);
        chkFullscreen = findViewById(R.id.chkFullscreen);
        chkZoom = findViewById(R.id.chkZoom);
        chkKeepScreenOn = findViewById(R.id.chkKeepScreenOn);
        chkPermInternet = findViewById(R.id.chkPermInternet);
        chkPermMic = findViewById(R.id.chkPermMic);
        chkPermCamera = findViewById(R.id.chkPermCamera);
        chkPermLocation = findViewById(R.id.chkPermLocation);
        chkPermVibrate = findViewById(R.id.chkPermVibrate);
        spinOrientation = findViewById(R.id.spinOrientation);
        btnBuild = findViewById(R.id.btnBuild);
        progress = findViewById(R.id.progress);
        resultButtons = findViewById(R.id.resultButtons);

        spinOrientation.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, ORIENTATION_LABELS));

        // Letzte Eingaben wiederherstellen
        // App-bezogene Felder starten leer; nur Optionen und Berechtigungen bleiben gespeichert
        editVersionName.setText("1.0");
        editVersionCode.setText("1");
        chkFullscreen.setChecked(prefs.getBoolean("fullscreen", false));
        chkZoom.setChecked(prefs.getBoolean("zoom", false));
        chkKeepScreenOn.setChecked(prefs.getBoolean("keepScreenOn", false));
        chkPermInternet.setChecked(prefs.getBoolean("permInternet", true));
        chkPermMic.setChecked(prefs.getBoolean("permMic", false));
        chkPermCamera.setChecked(prefs.getBoolean("permCamera", false));
        chkPermLocation.setChecked(prefs.getBoolean("permLocation", false));
        chkPermVibrate.setChecked(prefs.getBoolean("permVibrate", false));
        spinOrientation.setSelection(prefs.getInt("orientation", 0));
        // Nur als "selbst geändert" werten, wenn der Paketname nicht dem Vorschlag entspricht
        packageEditedByUser = false;
        showVersionHint("");

        // Paketname automatisch aus dem App-Namen vorschlagen, bis der Nutzer ihn selbst ändert
        // URL eingegeben: gewählte Datei verwerfen und Namen aus der Adresse vorschlagen
        editUrl.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                String url = s.toString().trim();
                if (url.isEmpty() || !editUrl.hasFocus()) return;
                if (contentUri != null) {
                    contentUri = null;
                    contentName = null;
                    txtContent.setText("Website-Modus: Es wird die eingegebene URL geladen.");
                }
                String suggested = WebProject.suggestAppNameFromUrl(url);
                if (!nameEditedByUser && suggested != null) {
                    settingNameAutomatically = true;
                    editName.setText(suggested);
                    settingNameAutomatically = false;
                }
            }
        });

        editName.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                if (!settingNameAutomatically && editName.hasFocus()) nameEditedByUser = true;
                if (s.toString().trim().isEmpty()) nameEditedByUser = false;
                if (!packageEditedByUser) {
                    editPackage.setText(WebProject.suggestPackage(s.toString()));
                }
            }
        });
        editPackage.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                if (editPackage.hasFocus()) packageEditedByUser = true;
                applyVersionForPackage(s.toString().trim());
            }
        });

        findViewById(R.id.btnPickContent).setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "application/zip", "application/x-zip-compressed", "text/html", "application/octet-stream"});
            startForResult(i, REQ_CONTENT);
        });
        findViewById(R.id.btnPickIcon).setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            startForResult(i, REQ_ICON);
        });
        btnBuild.setOnClickListener(v -> startBuild());
        findViewById(R.id.btnInstall).setOnClickListener(v -> install());
        findViewById(R.id.btnShare).setOnClickListener(v -> share());
        findViewById(R.id.btnSave).setOnClickListener(v -> {
            if (lastApk == null) return;
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/vnd.android.package-archive");
            i.putExtra(Intent.EXTRA_TITLE, lastApk.getName());
            startForResult(i, REQ_SAVE_APK);
        });
    }

    @Override
    protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }

    // -------------------------------------------------------------- Menü

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 5, 0, "Info")
                .setIcon(R.drawable.ic_info)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        menu.add(0, 4, 0, "Neue App");
        menu.add(0, 1, 0, "Signaturschlüssel sichern");
        menu.add(0, 2, 0, "Signaturschlüssel importieren");
        menu.add(0, 3, 0, "Hilfe");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case 1: {
                if (!keyFile().exists()) {
                    toast("Es gibt noch keinen Schlüssel. Er entsteht beim ersten Bauen.");
                    return true;
                }
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/x-pkcs12");
                i.putExtra(Intent.EXTRA_TITLE, "htmlapk-schluessel.p12");
                startForResult(i, REQ_EXPORT_KEY);
                return true;
            }
            case 2: {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                startForResult(i, REQ_IMPORT_KEY);
                return true;
            }
            case 5:
                startActivity(new Intent(this, InfoActivity.class));
                return true;
            case 4:
                resetForNewApp();
                return true;
            case 3:
                new AlertDialog.Builder(this)
                        .setTitle("So geht's")
                        .setMessage("1. Wähle eine ZIP mit deinen Webdateien (mit index.html), "
                                + "eine einzelne HTML-Datei oder gib eine Website-URL ein.\n\n"
                                + "2. Gib App-Name und Paketname ein. Der Paketname muss für jede App "
                                + "anders sein und für Updates gleich bleiben.\n\n"
                                + "3. Für ein Update derselben App erhöhe die Versionsnummer.\n\n"
                                + "Alle Apps werden mit deinem eigenen Schlüssel signiert. Sichere ihn "
                                + "über das Menü: Ohne ihn kannst du deine Apps nach einer Neuinstallation "
                                + "des Builders nicht mehr aktualisieren. Passwort der Schlüsseldatei: htmlapk")
                        .setPositiveButton("OK", null)
                        .show();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ---------------------------------------------------------- Auswahl

    private void startForResult(Intent i, int req) {
        try {
            startActivityForResult(i, req);
        } catch (ActivityNotFoundException e) {
            toast("Keine passende App zum Auswählen gefunden");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        switch (requestCode) {
            case REQ_CONTENT:
                contentUri = uri;
                contentName = displayName(uri);
                txtContent.setText("Gewählt: " + contentName);
                editUrl.setText("");
                // Neue Datei = App-Name aus dem Dateinamen (ohne Versionsnummer am Ende)
                nameEditedByUser = false;
                settingNameAutomatically = true;
                editName.setText(WebProject.suggestAppName(contentName));
                settingNameAutomatically = false;
                editFileName.setText("");
                break;
            case REQ_ICON:
                loadIcon(uri);
                break;
            case REQ_SAVE_APK:
                copyFileTo(lastApk, uri, "APK gespeichert");
                break;
            case REQ_EXPORT_KEY:
                copyFileTo(keyFile(), uri, "Schlüssel gesichert (Passwort: htmlapk)");
                break;
            case REQ_IMPORT_KEY:
                importKey(uri);
                break;
        }
    }

    private void loadIcon(Uri uri) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 512) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap bmp;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                bmp = BitmapFactory.decodeStream(in, null, opts);
            }
            if (bmp == null) throw new IOException("Unbekanntes Bildformat");
            iconBitmap = squareCrop(bmp);
            imgIcon.setImageBitmap(iconBitmap);
            txtIcon.setText("Icon: " + displayName(uri));
        } catch (IOException e) {
            toast("Bild konnte nicht gelesen werden: " + e.getMessage());
        }
    }

    private static Bitmap squareCrop(Bitmap b) {
        int s = Math.min(b.getWidth(), b.getHeight());
        if (b.getWidth() == b.getHeight()) return b;
        return Bitmap.createBitmap(b, (b.getWidth() - s) / 2, (b.getHeight() - s) / 2, s, s);
    }

    private void importKey(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            byte[] data = ApkAssembler.readAll(in);
            SigningKey.load(new java.io.ByteArrayInputStream(data)); // prüft Datei und Passwort
            try (OutputStream out = new FileOutputStream(keyFile())) {
                out.write(data);
            }
            toast("Schlüssel importiert");
        } catch (Exception e) {
            toast("Import fehlgeschlagen: Datei muss eine .p12 mit Passwort \"htmlapk\" sein");
        }
    }

    // ------------------------------------------------------------- Bauen

    private void startBuild() {
        final String url = editUrl.getText().toString().trim();
        final String name = editName.getText().toString().trim();
        final String pkg = editPackage.getText().toString().trim();
        final String customFileName = editFileName.getText().toString().trim();
        final String versionName = editVersionName.getText().toString().trim().isEmpty()
                ? "1.0" : editVersionName.getText().toString().trim();
        final int versionCode;
        try {
            versionCode = Integer.parseInt(editVersionCode.getText().toString().trim());
            if (versionCode < 1) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            editVersionCode.setError("Ganze Zahl ab 1");
            return;
        }
        if (url.isEmpty() && contentUri == null) {
            toast("Bitte zuerst eine ZIP/HTML-Datei wählen oder eine URL eingeben");
            return;
        }
        if (!url.isEmpty() && !(url.startsWith("https://") || url.startsWith("http://"))) {
            editUrl.setError("Muss mit https:// beginnen");
            return;
        }
        if (name.isEmpty()) {
            editName.setError("Bitte einen Namen eingeben");
            return;
        }
        if (!WebProject.isValidPackage(pkg)) {
            editPackage.setError("Ungültig. Beispiel: com.meinname.meinspiel");
            return;
        }

        final boolean fullscreen = chkFullscreen.isChecked();
        final boolean zoom = chkZoom.isChecked();
        final boolean keepScreenOn = chkKeepScreenOn.isChecked();
        if (!url.isEmpty() && !chkPermInternet.isChecked()) {
            chkPermInternet.setChecked(true);
            toast("Für eine Website-URL ist Internet nötig – wurde aktiviert");
        }
        final java.util.Set<String> remove = new java.util.HashSet<>();
        if (!chkPermInternet.isChecked()) {
            remove.add("android.permission.INTERNET");
            remove.add("android.permission.ACCESS_NETWORK_STATE");
        }
        if (!chkPermMic.isChecked()) {
            remove.add("android.permission.RECORD_AUDIO");
            remove.add("android.permission.MODIFY_AUDIO_SETTINGS");
        }
        if (!chkPermCamera.isChecked()) remove.add("android.permission.CAMERA");
        if (!chkPermLocation.isChecked()) {
            remove.add("android.permission.ACCESS_FINE_LOCATION");
            remove.add("android.permission.ACCESS_COARSE_LOCATION");
        }
        if (!chkPermVibrate.isChecked()) remove.add("android.permission.VIBRATE");
        final int orientationIdx = spinOrientation.getSelectedItemPosition();
        final Uri content = contentUri;
        final Bitmap icon = iconBitmap;

        prefs.edit()
                .putString("name", name).putString("package", pkg)
                .putString("versionName", versionName).putInt("versionCode", versionCode)
                .putString("url", url).putString("fileName", customFileName).putBoolean("fullscreen", fullscreen)
                .putBoolean("zoom", zoom).putInt("orientation", orientationIdx)
                .putBoolean("keepScreenOn", keepScreenOn)
                .putBoolean("permInternet", chkPermInternet.isChecked())
                .putBoolean("permMic", chkPermMic.isChecked())
                .putBoolean("permCamera", chkPermCamera.isChecked())
                .putBoolean("permLocation", chkPermLocation.isChecked())
                .putBoolean("permVibrate", chkPermVibrate.isChecked())
                .apply();

        setBusy(true);
        status("Baue " + name + " …");

        worker.execute(() -> {
            try {
                File apk = build(url, content, name, pkg, versionName, versionCode,
                        fullscreen, zoom, keepScreenOn, ORIENTATION_VALUES[orientationIdx],
                        remove, icon, customFileName);
                ui.post(() -> {
                    lastApk = apk;
                    setBusy(false);
                    // Gebaute Version merken: nächster Build desselben Pakets wird ein Update
                    prefs.edit().putInt("vc:" + pkg, versionCode)
                            .putString("vn:" + pkg, versionName).apply();
                    applyVersionForPackage(pkg); // Felder schon für das nächste Update vorbereiten
                    resultButtons.setVisibility(View.VISIBLE);
                    status("Fertig: " + apk.getName() + " (" + (apk.length() / 1024) + " KB)\n"
                            + "Paket " + pkg + ", Version " + versionName + " (" + versionCode + ")");
                });
            } catch (Throwable t) {
                ui.post(() -> {
                    setBusy(false);
                    status("Fehler: " + t.getMessage());
                });
            }
        });
    }

    private File build(String url, Uri content, String name, String pkg, String versionName,
                       int versionCode, boolean fullscreen, boolean zoom, boolean keepScreenOn,
                       String orientation, java.util.Set<String> removePermissions,
                       Bitmap icon, String customFileName) throws Exception {
        File work = new File(getCacheDir(), "work");
        work.mkdirs();

        progressText("Vorlage vorbereiten …");
        File template = new File(work, "template.apk");
        try (InputStream in = getAssets().open("template.apk");
             OutputStream out = new FileOutputStream(template)) {
            ApkAssembler.copy(in, out);
        }

        ApkAssembler.Input input = new ApkAssembler.Input();
        input.templateApk = template;
        input.manifest.packageName = pkg;
        input.manifest.appLabel = name;
        input.manifest.versionName = versionName;
        input.manifest.versionCode = versionCode;
        input.manifest.removePermissions.addAll(removePermissions);

        if (!url.isEmpty()) {
            input.webFiles = null;
            input.configJson = WebProject.configJson(url, fullscreen, orientation, zoom, keepScreenOn);
        } else {
            progressText("Dateien lesen …");
            byte[] bytes;
            try (InputStream in = getContentResolver().openInputStream(content)) {
                bytes = ApkAssembler.readAll(in);
            }
            boolean isZip = bytes.length > 4 && bytes[0] == 'P' && bytes[1] == 'K';
            WebProject web = isZip
                    ? WebProject.fromZip(new java.io.ByteArrayInputStream(bytes))
                    : WebProject.fromHtml(bytes);
            input.webFiles = web.files;
            input.configJson = WebProject.configJson(web.startPage, fullscreen, orientation, zoom, keepScreenOn);
        }

        if (icon != null) {
            input.icon = (w, h) -> {
                Bitmap scaled = Bitmap.createScaledBitmap(icon, w, h, true);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                scaled.compress(Bitmap.CompressFormat.PNG, 100, bos);
                return bos.toByteArray();
            };
        }

        progressText("APK zusammenbauen …");
        File unsigned = new File(work, "unsigned.apk");
        ApkAssembler.assemble(input, unsigned);

        progressText("Signieren …");
        SigningKey key = SigningKey.loadOrCreate(keyFile(), "HTML APK Builder");
        File outDir = OutputProvider.outputDir(this);
        File[] old = outDir.listFiles();
        if (old != null) for (File f : old) f.delete();
        File signed = new File(outDir, apkFileName(customFileName, name, versionName));
        ApkSigning.sign(unsigned, signed, key);
        unsigned.delete();
        return signed;
    }

    /**
     * Kennt der Builder den Paketnamen schon, wird die Versionsnummer automatisch
     * erhöht (sonst lässt sich die neue APK nicht als Update installieren).
     */
    private void applyVersionForPackage(String pkg) {
        int last = prefs.getInt("vc:" + pkg, 0);
        if (last > 0) {
            editVersionCode.setText(String.valueOf(last + 1));
            editVersionName.setText(WebProject.nextVersionName(prefs.getString("vn:" + pkg, "1.0")));
        } else {
            editVersionCode.setText("1");
            editVersionName.setText("1.0");
        }
        showVersionHint(pkg);
    }

    private void showVersionHint(String pkg) {
        int last = prefs.getInt("vc:" + pkg, 0);
        txtVersion.setText(last > 0
                ? "Update: zuletzt gebaut als " + prefs.getString("vn:" + pkg, "?") + " (" + last
                  + "). Versionsnummer muss größer sein – wurde erhöht."
                : "Neue App. Für spätere Updates denselben Paketnamen verwenden.");
    }

    private void resetForNewApp() {
        contentUri = null;
        contentName = null;
        txtContent.setText("Noch nichts gewählt. Eine ZIP sollte eine index.html enthalten.");
        editUrl.setText("");
        packageEditedByUser = false;
        nameEditedByUser = false;
        editName.setText("");
        editPackage.setText("");
        editFileName.setText("");
        editVersionName.setText("1.0");
        editVersionCode.setText("1");
        iconBitmap = null;
        imgIcon.setImageResource(R.drawable.default_app_icon);
        txtIcon.setText("Ohne Auswahl bekommt die App das Standard-Icon.");
        resultButtons.setVisibility(View.GONE);
        lastApk = null;
        status("");
        findViewById(R.id.scroll).scrollTo(0, 0);
    }

    /** Eigener Dateiname oder "<App-Name>-<Version>.apk"; unerlaubte Zeichen werden ersetzt. */
    static String apkFileName(String custom, String appName, String versionName) {
        String base = custom == null ? "" : custom.trim();
        if (base.toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) {
            base = base.substring(0, base.length() - 4).trim();
        }
        if (base.isEmpty()) base = appName + "-" + versionName;
        base = base.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").replace(' ', '_');
        while (base.startsWith(".")) base = base.substring(1);
        if (base.length() > 100) base = base.substring(0, 100);
        if (base.isEmpty()) base = "app";
        return base + ".apk";
    }

    private File keyFile() {
        return new File(getFilesDir(), "signing.p12");
    }

    // -------------------------------------------------------- Ergebnis

    private void install() {
        if (lastApk == null) return;
        if (!getPackageManager().canRequestPackageInstalls()) {
            toast("Bitte erlaube dem Builder, Apps zu installieren, und tippe dann erneut");
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(OutputProvider.uriFor(lastApk), "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            toast("Kein Paketinstaller gefunden");
        }
    }

    private void share() {
        if (lastApk == null) return;
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("application/vnd.android.package-archive");
        i.putExtra(Intent.EXTRA_STREAM, OutputProvider.uriFor(lastApk));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, "APK teilen"));
    }

    private void copyFileTo(File src, Uri dest, String okMessage) {
        if (src == null) return;
        try (InputStream in = new FileInputStream(src);
             OutputStream out = getContentResolver().openOutputStream(dest)) {
            if (out == null) throw new IOException("Ziel nicht beschreibbar");
            ApkAssembler.copy(in, out);
            toast(okMessage);
        } catch (IOException e) {
            toast("Speichern fehlgeschlagen: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------ Helfer

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (Exception ignored) {
        }
        String last = uri.getLastPathSegment();
        return last != null ? last : "Datei";
    }

    private void setBusy(boolean busy) {
        btnBuild.setEnabled(!busy);
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (busy) resultButtons.setVisibility(View.GONE);
    }

    private void status(String s) {
        txtStatus.setText(s);
    }

    private void progressText(String s) {
        ui.post(() -> status(s));
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private abstract static class SimpleWatcher implements TextWatcher {
        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
    }
}
