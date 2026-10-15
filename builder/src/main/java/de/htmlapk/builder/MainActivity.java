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
import android.widget.Toolbar;

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
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Projekt-Editor: alle Eingaben werden im Projekt gespeichert und beim Öffnen wiederhergestellt. */
public class MainActivity extends Activity {

    public static final String EXTRA_PROJECT_ID = "projectId";

    private static final int REQ_CONTENT = 1;
    private static final int REQ_ICON = 2;
    private static final int REQ_SAVE_APK = 3;
    private static final int REQ_EXPORT_KEY = 4;
    private static final int REQ_IMPORT_KEY = 5;
    private static final int REQ_CROP = 6;
    private static final int REQ_EXPORT_PROJECT = 7;

    private static final String[] ORIENTATION_LABELS = {"Automatisch", "Hochformat", "Querformat"};
    private static final String[] ORIENTATION_VALUES = {"auto", "portrait", "landscape"};

    private EditText editUrl, editName, editPackage, editVersionName, editVersionCode, editFileName;
    private TextView txtContent, txtIcon, txtStatus, txtVersion;
    private ImageView imgIcon;
    private CheckBox chkFullscreen, chkZoom, chkKeepScreenOn;
    private CheckBox chkPermInternet, chkPermMic, chkPermCamera, chkPermLocation, chkPermVibrate;
    private Spinner spinOrientation;
    private Button btnBuild, btnCropIcon;
    private ProgressBar progress;
    private LinearLayout resultButtons;
    private Toolbar toolbar;

    private Project project;
    private File lastApk;
    private boolean loading;                 // Felder werden gerade aus dem Projekt gefüllt
    private boolean settingAutomatically;    // Feld wird vom Programm (nicht vom Nutzer) gesetzt
    private boolean building;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        toolbar = findViewById(R.id.toolbar);
        setActionBar(toolbar);
        toolbar.setNavigationIcon(R.drawable.ic_back);
        toolbar.setNavigationOnClickListener(v -> finish());

        View root = findViewById(R.id.root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        prefs = getSharedPreferences("builder", MODE_PRIVATE);

        project = Project.load(this, getIntent().getStringExtra(EXTRA_PROJECT_ID));
        if (project == null) project = Project.create(this);

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
        btnCropIcon = findViewById(R.id.btnCropIcon);
        progress = findViewById(R.id.progress);
        resultButtons = findViewById(R.id.resultButtons);

        spinOrientation.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, ORIENTATION_LABELS));

        installWatchers();
        loadProjectIntoFields();

        findViewById(R.id.btnPickContent).setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "application/zip", "application/x-zip-compressed", "text/html", "application/octet-stream"});
            startForResult(i, REQ_CONTENT);
        });
        btnCropIcon.setOnClickListener(v -> openCropper());
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
    protected void onPause() {
        if (project != null) saveFieldsToProject();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }

    // ------------------------------------------------- Projekt <-> Felder

    private void loadProjectIntoFields() {
        loading = true;
        Project p = project;
        editFileName.setText(p.apkName);
        editName.setText(p.name);
        editPackage.setText(p.pkg);
        editUrl.setText(p.url);
        chkFullscreen.setChecked(p.fullscreen);
        chkZoom.setChecked(p.zoom);
        chkKeepScreenOn.setChecked(p.keepScreenOn);
        spinOrientation.setSelection(Math.max(0, Math.min(2, p.orientation)));
        chkPermInternet.setChecked(p.permInternet);
        chkPermMic.setChecked(p.permMic);
        chkPermCamera.setChecked(p.permCamera);
        chkPermLocation.setChecked(p.permLocation);
        chkPermVibrate.setChecked(p.permVibrate);

        // Schon gebaut: nächste Version ist automatisch ein Update
        if (p.lastBuiltCode > 0 && p.versionCode <= p.lastBuiltCode) {
            p.versionCode = p.lastBuiltCode + 1;
            p.versionName = WebProject.nextVersionName(p.lastBuiltName);
        }
        editVersionName.setText(p.versionName);
        editVersionCode.setText(String.valueOf(p.versionCode));

        if (!p.url.isEmpty()) {
            txtContent.setText("Website-Modus: Es wird die eingegebene URL geladen.");
        } else if (p.hasContent()) {
            txtContent.setText("Gespeichert: " + (p.contentName != null ? p.contentName : "Webdateien"));
        }

        Bitmap icon = p.hasIcon() ? BitmapFactory.decodeFile(p.iconFile().getPath()) : null;
        if (icon != null) {
            imgIcon.setImageBitmap(icon);
            txtIcon.setText("Icon: " + (p.iconSourceName != null ? p.iconSourceName : "eigenes Bild"));
        }
        btnCropIcon.setVisibility(p.iconSourceFile().isFile() ? View.VISIBLE : View.GONE);

        // Zuletzt gebaute APK wieder anbieten
        if (p.lastApkFile().isFile() && p.lastBuiltCode > 0) {
            try {
                File out = new File(OutputProvider.outputDir(this),
                        apkFileName(p.apkName, p.displayName(), p.lastBuiltName));
                clearOutputDir();
                try (InputStream in = new FileInputStream(p.lastApkFile())) {
                    Project.copy(in, out);
                }
                lastApk = out;
                resultButtons.setVisibility(View.VISIBLE);
                status("Zuletzt gebaut: Version " + p.lastBuiltName + " (" + p.lastBuiltCode + ") am "
                        + new SimpleDateFormat("dd.MM.yyyy, HH:mm", Locale.GERMANY).format(new Date(p.lastBuiltAt)));
            } catch (IOException ignored) {
            }
        }
        showVersionHint();
        updateTitle();
        loading = false;
    }

    private void saveFieldsToProject() {
        Project p = project;
        p.apkName = editFileName.getText().toString().trim();
        p.name = editName.getText().toString().trim();
        p.pkg = editPackage.getText().toString().trim();
        p.url = editUrl.getText().toString().trim();
        p.versionName = editVersionName.getText().toString().trim();
        try {
            p.versionCode = Integer.parseInt(editVersionCode.getText().toString().trim());
        } catch (NumberFormatException ignored) {
        }
        p.fullscreen = chkFullscreen.isChecked();
        p.zoom = chkZoom.isChecked();
        p.keepScreenOn = chkKeepScreenOn.isChecked();
        p.orientation = spinOrientation.getSelectedItemPosition();
        p.permInternet = chkPermInternet.isChecked();
        p.permMic = chkPermMic.isChecked();
        p.permCamera = chkPermCamera.isChecked();
        p.permLocation = chkPermLocation.isChecked();
        p.permVibrate = chkPermVibrate.isChecked();
        p.save();
    }

    private void updateTitle() {
        toolbar.setTitle(project.displayName());
    }

    /**
     * Automatik: APK-Name -> App-Name -> Paketname. Ein Feld, das der Nutzer selbst
     * geändert hat, wird nicht mehr überschrieben (bis er es leert).
     */
    private void installWatchers() {
        editFileName.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                if (loading || settingAutomatically) return;
                if (!project.nameManual) {
                    String n = s.toString().trim().replaceAll("(?i)\\.apk$", "").trim();
                    setAutomatically(editName, n);
                }
                updateTitle();
            }
        });

        editUrl.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                if (loading) return;
                String url = s.toString().trim();
                if (url.isEmpty() || !editUrl.hasFocus()) return;
                if (project.hasContent()) {
                    //noinspection ResultOfMethodCallIgnored
                    project.contentFile().delete();
                    project.contentName = null;
                }
                txtContent.setText("Website-Modus: Es wird die eingegebene URL geladen.");
                String suggested = WebProject.suggestAppNameFromUrl(url);
                if (suggested != null) fillNamesFrom(suggested);
            }
        });

        editName.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                if (loading) return;
                if (!settingAutomatically && editName.hasFocus()) project.nameManual = true;
                if (s.toString().trim().isEmpty()) project.nameManual = false;
                if (!project.packageManual) {
                    setAutomatically(editPackage, s.toString().trim().isEmpty()
                            ? "" : WebProject.suggestPackage(s.toString()));
                }
                updateTitle();
            }
        });

        editPackage.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                if (loading) return;
                if (!settingAutomatically && editPackage.hasFocus()) project.packageManual = true;
                if (s.toString().trim().isEmpty()) project.packageManual = false;
                // Projekt noch nie gebaut, aber Paket schon bekannt? Dann als Update fortsetzen
                if (project.lastBuiltCode == 0) applyVersionForPackage(s.toString().trim());
            }
        });
    }

    /** Vorschlag aus Datei oder URL: füllt APK-Name (falls leer) und darüber App-Name/Paket. */
    private void fillNamesFrom(String suggested) {
        if (editFileName.getText().toString().trim().isEmpty()) {
            setAutomatically(editFileName, suggested);
        }
        if (!project.nameManual) setAutomatically(editName, suggested);
        updateTitle();
    }

    private void setAutomatically(EditText field, String text) {
        if (field.getText().toString().equals(text)) return;
        settingAutomatically = true;
        field.setText(text);
        settingAutomatically = false;
    }

    // -------------------------------------------------------------- Menü

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 5, 0, "Info")
                .setIcon(R.drawable.ic_info)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        menu.add(0, 7, 0, "Projekt exportieren");
        menu.add(0, 6, 0, "Projekt löschen");
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
            case 7: {
                saveFieldsToProject();
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/zip");
                i.putExtra(Intent.EXTRA_TITLE, ProjectArchive.exportFileName(project.displayName()));
                startForResult(i, REQ_EXPORT_PROJECT);
                return true;
            }
            case 6:
                new AlertDialog.Builder(this)
                        .setTitle("„" + project.displayName() + "“ löschen?")
                        .setMessage("Das Projekt mit allen gespeicherten Dateien wird entfernt. "
                                + "Bereits installierte Apps bleiben auf dem Handy.")
                        .setNegativeButton("Abbrechen", null)
                        .setPositiveButton("Löschen", (d, w) -> {
                            project.delete();
                            project = null;
                            finish();
                        })
                        .show();
                return true;
            case 3:
                new AlertDialog.Builder(this)
                        .setTitle("So geht's")
                        .setMessage("1. Wähle eine ZIP mit deinen Webdateien (mit index.html), "
                                + "eine einzelne HTML-Datei oder gib eine Website-URL ein.\n\n"
                                + "2. Gib einen APK-Namen ein – App-Name und Paketname werden daraus "
                                + "erzeugt. Der Paketname muss für jede App anders sein und für Updates "
                                + "gleich bleiben.\n\n"
                                + "3. Alles wird im Projekt gespeichert. Für ein Update öffnest du das "
                                + "Projekt wieder und tippst auf „APK bauen“ – die Versionsnummer wird "
                                + "automatisch erhöht.\n\n"
                                + "Alle Apps werden mit deinem eigenen Schlüssel signiert. Sichere ihn "
                                + "über das Menü: Ohne ihn kannst du deine Apps nach einer Neuinstallation "
                                + "des Builders nicht mehr aktualisieren. Passwort der Schlüsseldatei: htmlapk")
                        .setPositiveButton("OK", null)
                        .show();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void finish() {
        if (project != null) {
            saveFieldsToProject();
            // Mit + angelegt und nichts eingetragen: kein leeres Projekt zurücklassen
            if (project.isBlank()) {
                project.delete();
                project = null;
            }
        }
        super.finish();
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
        if (project == null) return;
        if (requestCode == REQ_CROP) {
            if (resultCode == RESULT_OK) applyCroppedIcon();
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        switch (requestCode) {
            case REQ_CONTENT:
                storeContent(uri);
                break;
            case REQ_ICON:
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new IOException("Bild nicht lesbar");
                    Project.copy(in, project.iconSourceFile());
                    project.iconSourceName = displayName(uri);
                    project.save();
                    openCropper();
                } catch (IOException e) {
                    toast("Bild konnte nicht gelesen werden: " + e.getMessage());
                }
                break;
            case REQ_SAVE_APK:
                copyFileTo(lastApk, uri, "APK gespeichert");
                break;
            case REQ_EXPORT_PROJECT: {
                final File dir = project.dir();
                final String label = project.displayName();
                worker.execute(() -> {
                    try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                        if (out == null) throw new IOException("Ziel nicht beschreibbar");
                        ProjectArchive.exportProject(dir, out);
                        ui.post(() -> toast("„" + label + "“ exportiert"));
                    } catch (Exception e) {
                        ui.post(() -> toast("Export fehlgeschlagen: " + e.getMessage()));
                    }
                });
                break;
            }
            case REQ_EXPORT_KEY:
                copyFileTo(keyFile(), uri, "Schlüssel gesichert (Passwort: htmlapk)");
                break;
            case REQ_IMPORT_KEY:
                importKey(uri);
                break;
        }
    }

    /** Kopiert die gewählte HTML/ZIP ins Projekt, damit sie dauerhaft verfügbar bleibt. */
    private void storeContent(Uri uri) {
        String fileName = displayName(uri);
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Datei nicht lesbar");
            Project.copy(in, project.contentFile());
        } catch (IOException e) {
            toast("Datei konnte nicht gelesen werden: " + e.getMessage());
            return;
        }
        project.contentName = fileName;
        txtContent.setText("Gespeichert: " + fileName);
        setAutomatically(editUrl, "");
        fillNamesFrom(WebProject.suggestAppName(fileName));
        project.save();
    }

    private void openCropper() {
        if (!project.iconSourceFile().isFile()) return;
        Intent i = new Intent(this, CropActivity.class);
        i.putExtra(CropActivity.EXTRA_PATH, project.iconSourceFile().getPath());
        startActivityForResult(i, REQ_CROP);
    }

    private void applyCroppedIcon() {
        try (InputStream in = new FileInputStream(CropActivity.resultFile(this))) {
            Project.copy(in, project.iconFile());
        } catch (IOException e) {
            toast("Zugeschnittenes Bild konnte nicht gespeichert werden");
            return;
        }
        Bitmap bmp = BitmapFactory.decodeFile(project.iconFile().getPath());
        if (bmp != null) imgIcon.setImageBitmap(bmp);
        txtIcon.setText("Icon: " + (project.iconSourceName != null ? project.iconSourceName : "eigenes Bild")
                + " (zugeschnitten)");
        btnCropIcon.setVisibility(View.VISIBLE);
        project.save();
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
        if (building) return;
        final String url = editUrl.getText().toString().trim();
        final String name = editName.getText().toString().trim();
        final String pkg = editPackage.getText().toString().trim();
        final String apkName = editFileName.getText().toString().trim();
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
        if (url.isEmpty() && !project.hasContent()) {
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
        if (!url.isEmpty() && !chkPermInternet.isChecked()) {
            chkPermInternet.setChecked(true);
            toast("Für eine Website-URL ist Internet nötig – wurde aktiviert");
        }
        saveFieldsToProject();

        final Set<String> remove = new HashSet<>();
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

        final boolean fullscreen = chkFullscreen.isChecked();
        final boolean zoom = chkZoom.isChecked();
        final boolean keepScreenOn = chkKeepScreenOn.isChecked();
        final String orientation = ORIENTATION_VALUES[spinOrientation.getSelectedItemPosition()];
        final Project p = project;

        setBusy(true);
        status("Baue " + name + " …");

        worker.execute(() -> {
            try {
                File apk = build(p, url, name, pkg, versionName, versionCode,
                        fullscreen, zoom, keepScreenOn, orientation, remove, apkName);
                // Im Projekt ablegen, damit sie später wieder installiert/geteilt werden kann
                try (InputStream in = new FileInputStream(apk)) {
                    Project.copy(in, p.lastApkFile());
                }
                ui.post(() -> {
                    lastApk = apk;
                    setBusy(false);
                    p.lastBuiltCode = versionCode;
                    p.lastBuiltName = versionName;
                    p.lastBuiltAt = System.currentTimeMillis();
                    // Paket global merken (falls ein anderes Projekt dasselbe Paket nutzt)
                    prefs.edit().putInt("vc:" + pkg, versionCode)
                            .putString("vn:" + pkg, versionName).apply();
                    // Felder schon für das nächste Update vorbereiten
                    loading = true;
                    editVersionCode.setText(String.valueOf(versionCode + 1));
                    editVersionName.setText(WebProject.nextVersionName(versionName));
                    loading = false;
                    saveFieldsToProject();
                    showVersionHint();
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

    private File build(Project p, String url, String name, String pkg, String versionName,
                       int versionCode, boolean fullscreen, boolean zoom, boolean keepScreenOn,
                       String orientation, Set<String> removePermissions, String apkName) throws Exception {
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
            try (InputStream in = new FileInputStream(p.contentFile())) {
                bytes = ApkAssembler.readAll(in);
            }
            boolean isZip = bytes.length > 4 && bytes[0] == 'P' && bytes[1] == 'K';
            WebProject web = isZip
                    ? WebProject.fromZip(new java.io.ByteArrayInputStream(bytes))
                    : WebProject.fromHtml(bytes);
            input.webFiles = web.files;
            input.configJson = WebProject.configJson(web.startPage, fullscreen, orientation, zoom, keepScreenOn);
        }

        final Bitmap icon = p.hasIcon() ? BitmapFactory.decodeFile(p.iconFile().getPath()) : null;
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
        clearOutputDir();
        File signed = new File(OutputProvider.outputDir(this), apkFileName(apkName, name, versionName));
        ApkSigning.sign(unsigned, signed, key);
        //noinspection ResultOfMethodCallIgnored
        unsigned.delete();
        return signed;
    }

    private void clearOutputDir() {
        File[] old = OutputProvider.outputDir(this).listFiles();
        if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    /** Projekt noch nie gebaut, Paket aber schon von früher bekannt: als Update weiterzählen. */
    private void applyVersionForPackage(String pkg) {
        int last = prefs.getInt("vc:" + pkg, 0);
        loading = true;
        if (last > 0) {
            editVersionCode.setText(String.valueOf(last + 1));
            editVersionName.setText(WebProject.nextVersionName(prefs.getString("vn:" + pkg, "1.0")));
        } else {
            editVersionCode.setText("1");
            editVersionName.setText("1.0");
        }
        loading = false;
        showVersionHint();
    }

    private void showVersionHint() {
        String pkg = editPackage.getText().toString().trim();
        int last = project.lastBuiltCode > 0 ? project.lastBuiltCode : prefs.getInt("vc:" + pkg, 0);
        String lastName = project.lastBuiltCode > 0 ? project.lastBuiltName : prefs.getString("vn:" + pkg, "?");
        txtVersion.setText(last > 0
                ? "Update: zuletzt gebaut als " + lastName + " (" + last
                  + "). Versionsnummer wurde automatisch erhöht."
                : "Neue App. Für spätere Updates einfach dieses Projekt wieder öffnen.");
    }

    /** Eigener Dateiname oder "<App-Name>-<Version>.apk"; unerlaubte Zeichen werden ersetzt. */
    static String apkFileName(String custom, String appName, String versionName) {
        String base = custom == null ? "" : custom.trim();
        if (base.toLowerCase(Locale.ROOT).endsWith(".apk")) {
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
        building = busy;
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
