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
import android.widget.FrameLayout;
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
    /** Original-Repository mit Workflow und Skript; dient als Vorlage für eigene Kopien. */
    static final String SOURCE_REPO = "sidmarween/htmlapk";

    private static final int REQ_CONTENT = 1;
    private static final int REQ_ICON = 2;
    private static final int REQ_SAVE_APK = 3;
    private static final int REQ_KEY_EXPORT = 4;
    private static final int REQ_KEY_IMPORT = 5;
    private static final int REQ_SPLASH = 8;
    private static final int REQ_CROP = 6;
    private static final int REQ_EXPORT_PROJECT = 7;


    private EditText editUrl, editName, editPackage, editVersionName, editVersionCode, editFileName;
    private TextView txtContent, txtIcon, txtStatus, txtVersion;
    private ImageView imgIcon;
    private OptionsForm form;
    private KeyManager keys;
    private String pendingKeyExportId;
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
        btnBuild = findViewById(R.id.btnBuild);
        btnCropIcon = findViewById(R.id.btnCropIcon);
        progress = findViewById(R.id.progress);
        resultButtons = findViewById(R.id.resultButtons);

        keys = new KeyManager(getFilesDir());
        form = new OptionsForm(this, findViewById(R.id.optionsContainer), keys, new OptionsForm.Host() {
            @Override
            public void pickSplashImage() {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("image/*");
                startForResult(i, REQ_SPLASH);
            }

            @Override
            public void manageKeys() {
                showKeyManager();
            }

            @Override
            public void setupGithub() {
                showGithubSetup();
            }

            @Override
            public boolean isGithubConfigured() {
                return GithubAab.isValidRepo(prefs.getString("gh_repo", ""))
                        && !prefs.getString("gh_token", "").isEmpty();
            }
        });

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
            i.setType(lastIsAab() ? "application/octet-stream" : "application/vnd.android.package-archive");
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
        form.load(p);
        form.setSplashImage(p.splashFile().isFile() ? BitmapFactory.decodeFile(p.splashFile().getPath()) : null);

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
        form.setAppIcon(icon);
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
        // Optionen, Berechtigungen und Schlüssel schreibt OptionsForm direkt ins Projekt
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
        menu.add(0, 1, 0, "Schlüssel verwalten");
        menu.add(0, 8, 0, "GitHub für AAB einrichten");
        menu.add(0, 3, 0, "Hilfe");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case 1:
                showKeyManager();
                return true;
            case 8:
                showGithubSetup();
                return true;
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
                                + "4. Unter Anzeige, Splash-Screen, WebView und Berechtigungen stellst du "
                                + "ein, wie sich die App verhält. Vor dem Bauen siehst du eine Zusammenfassung.\n\n"
                                + "Alle Apps werden mit einem Schlüssel signiert (Abschnitt Signatur). Sichere "
                                + "deine Schlüssel über „Schlüssel verwalten“ oder ein Backup im Hauptmenü: Ohne "
                                + "sie kannst du deine Apps nach einer Neuinstallation des Builders nicht mehr "
                                + "aktualisieren.")
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
                copyFileTo(lastApk, uri, lastIsAab() ? "AAB gespeichert" : "APK gespeichert");
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
            case REQ_KEY_EXPORT: {
                KeyManager.Entry e = keys.get(pendingKeyExportId);
                if (e != null) copyFileTo(e.file, uri, "Schlüssel gesichert – Passwort: " + e.password);
                break;
            }
            case REQ_KEY_IMPORT:
                askImportPassword(uri);
                break;
            case REQ_SPLASH:
                storeSplashImage(uri);
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

    // ------------------------------------------------------------ Splash-Bild

    private void storeSplashImage(Uri uri) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1024) sample *= 2;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap bmp;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                bmp = BitmapFactory.decodeStream(in, null, o);
            }
            if (bmp == null) throw new IOException("Unbekanntes Bildformat");
            int max = Math.max(bmp.getWidth(), bmp.getHeight());
            if (max > 1024) {
                float f = 1024f / max;
                bmp = Bitmap.createScaledBitmap(bmp, Math.round(bmp.getWidth() * f),
                        Math.round(bmp.getHeight() * f), true);
            }
            try (OutputStream out = new FileOutputStream(project.splashFile())) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            project.opts.splashCustomImage = true;
            project.save();
            form.load(project);
            form.setSplashImage(bmp);
        } catch (IOException e) {
            toast("Bild konnte nicht gelesen werden: " + e.getMessage());
        }
    }

    // ---------------------------------------------------- Schlüssel verwalten

    private void showKeyManager() {
        final java.util.List<KeyManager.Entry> list = keys.list();
        String[] items = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            KeyManager.Entry e = list.get(i);
            items[i] = (e.id.equals(project.keyId) ? "✓ " : "    ") + e.name
                    + "\n      " + keys.fingerprint(e);
        }
        new AlertDialog.Builder(this)
                .setTitle("Signaturschlüssel")
                .setItems(items, (d, which) -> showKeyActions(list.get(which)))
                .setPositiveButton("Neuer Schlüssel", (d, w) -> askNewKey())
                .setNeutralButton("Importieren", (d, w) -> {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    startForResult(i, REQ_KEY_IMPORT);
                })
                .setNegativeButton("Schließen", null)
                .show();
    }

    private void showKeyActions(KeyManager.Entry e) {
        java.util.List<String> actions = new java.util.ArrayList<>();
        actions.add("Für dieses Projekt verwenden");
        actions.add("Exportieren (sichern)");
        if (!e.isDefault()) {
            actions.add("Umbenennen");
            actions.add("Löschen");
        }
        new AlertDialog.Builder(this)
                .setTitle(e.name)
                .setItems(actions.toArray(new String[0]), (d, which) -> {
                    switch (actions.get(which)) {
                        case "Für dieses Projekt verwenden":
                            project.keyId = e.id;
                            project.save();
                            form.refreshKeys();
                            toast("„" + e.name + "“ wird für dieses Projekt verwendet");
                            break;
                        case "Exportieren (sichern)":
                            if (!e.exists()) {
                                toast("Dieser Schlüssel entsteht erst beim ersten Bauen.");
                                return;
                            }
                            pendingKeyExportId = e.id;
                            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                            i.addCategory(Intent.CATEGORY_OPENABLE);
                            i.setType("application/x-pkcs12");
                            i.putExtra(Intent.EXTRA_TITLE, e.name.replaceAll("[\\\\/:*?\"<>|]", "_") + ".p12");
                            startForResult(i, REQ_KEY_EXPORT);
                            break;
                        case "Umbenennen": {
                            EditText name = new EditText(this);
                            name.setText(e.name);
                            new AlertDialog.Builder(this).setTitle("Umbenennen").setView(padded(name))
                                    .setNegativeButton("Abbrechen", null)
                                    .setPositiveButton("OK", (d2, w) -> {
                                        try {
                                            keys.rename(e.id, name.getText().toString());
                                        } catch (IOException ex) {
                                            toast(ex.getMessage());
                                        }
                                        form.refreshKeys();
                                    }).show();
                            break;
                        }
                        case "Löschen":
                            new AlertDialog.Builder(this)
                                    .setTitle("„" + e.name + "“ löschen?")
                                    .setMessage("Apps, die mit diesem Schlüssel signiert sind, kannst du danach "
                                            + "nicht mehr aktualisieren – außer du hast ihn exportiert.")
                                    .setNegativeButton("Abbrechen", null)
                                    .setPositiveButton("Löschen", (d2, w) -> {
                                        keys.delete(e.id);
                                        form.refreshKeys();
                                    }).show();
                            break;
                    }
                })
                .show();
    }

    private void askNewKey() {
        EditText name = new EditText(this);
        name.setHint("Name, z. B. Meine Spiele");
        name.setText(project.displayName());
        new AlertDialog.Builder(this)
                .setTitle("Neuen Schlüssel erstellen")
                .setMessage("Ein eigener Schlüssel für eine App oder eine Gruppe von Apps.")
                .setView(padded(name))
                .setNegativeButton("Abbrechen", null)
                .setPositiveButton("Erstellen", (d, w) -> worker.execute(() -> {
                    try {
                        KeyManager.Entry e = keys.create(name.getText().toString());
                        ui.post(() -> {
                            project.keyId = e.id;
                            project.save();
                            form.refreshKeys();
                            toast("Schlüssel „" + e.name + "“ erstellt und ausgewählt");
                        });
                    } catch (Exception ex) {
                        ui.post(() -> toast("Fehler: " + ex.getMessage()));
                    }
                }))
                .show();
    }

    private void askImportPassword(Uri uri) {
        final byte[] data;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            data = ApkAssembler.readAll(in);
        } catch (IOException e) {
            toast("Datei nicht lesbar");
            return;
        }
        String fileName = displayName(uri).replaceAll("(?i)\\.(p12|pfx|bks|keystore|jks)$", "");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        EditText name = new EditText(this);
        name.setHint("Name");
        name.setText(fileName);
        EditText pw = new EditText(this);
        pw.setHint("Passwort");
        pw.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText alias = new EditText(this);
        alias.setHint("Alias (optional)");
        box.addView(name);
        box.addView(pw);
        box.addView(alias);
        new AlertDialog.Builder(this)
                .setTitle("Schlüssel importieren")
                .setMessage("Unterstützt: .p12 / .pfx und .bks")
                .setView(padded(box))
                .setNegativeButton("Abbrechen", null)
                .setPositiveButton("Importieren", (d, w) -> worker.execute(() -> {
                    try {
                        KeyManager.Entry e = keys.importKey(name.getText().toString(), data,
                                pw.getText().toString(), alias.getText().toString());
                        ui.post(() -> {
                            project.keyId = e.id;
                            project.save();
                            form.refreshKeys();
                            toast("„" + e.name + "“ importiert und ausgewählt");
                        });
                    } catch (Exception ex) {
                        ui.post(() -> toast("Import fehlgeschlagen: " + ex.getMessage()));
                    }
                }))
                .show();
    }

    private View padded(View v) {
        FrameLayout f = new FrameLayout(this);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        f.setPadding(pad, pad / 2, pad, 0);
        f.addView(v);
        return f;
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
        if (!url.isEmpty() && !project.permInternet) {
            project.permInternet = true;
            form.load(project);
            toast("Für eine Website-URL ist Internet nötig – wurde aktiviert");
        }
        if (project.opts.splashEnabled && project.opts.splashCustomImage && !project.splashFile().isFile()) {
            project.opts.splashCustomImage = false;
        }
        saveFieldsToProject();
        showSummary(() -> runBuild(url, name, pkg, versionName, versionCode, apkName));
    }

    private Set<String> permissionsToRemove(Project p) {
        Set<String> remove = new HashSet<>();
        if (!p.permInternet) {
            remove.add("android.permission.INTERNET");
            remove.add("android.permission.ACCESS_NETWORK_STATE");
        }
        if (!p.permMic) {
            remove.add("android.permission.RECORD_AUDIO");
            remove.add("android.permission.MODIFY_AUDIO_SETTINGS");
        }
        if (!p.permCamera) remove.add("android.permission.CAMERA");
        if (!p.permLocation) {
            remove.add("android.permission.ACCESS_FINE_LOCATION");
            remove.add("android.permission.ACCESS_COARSE_LOCATION");
        }
        if (!p.permVibrate) remove.add("android.permission.VIBRATE");
        if (!p.permNotifications) remove.add("android.permission.POST_NOTIFICATIONS");
        return remove;
    }

    /** "Ist alles korrekt?" – Übersicht vor dem Bauen. */
    private void showSummary(Runnable onConfirm) {
        Project p = project;
        de.htmlapk.builder.core.AppConfig c = p.opts;
        java.util.List<String> perms = new java.util.ArrayList<>();
        if (p.permInternet) perms.add("Internet");
        if (p.permMic) perms.add("Mikrofon");
        if (p.permCamera) perms.add("Kamera");
        if (p.permLocation) perms.add("Standort");
        if (p.permVibrate) perms.add("Vibration");
        if (p.permNotifications) perms.add("Benachrichtigungen");
        java.util.List<String> display = new java.util.ArrayList<>();
        display.add("portrait".equals(c.orientation) ? "Hochformat"
                : "landscape".equals(c.orientation) ? "Querformat" : "Automatische Ausrichtung");
        if (c.fullscreen) display.add("Vollbild");
        if (c.titleBar && !c.fullscreen) display.add("Titelleiste");
        if (c.keepScreenOn) display.add("Bildschirm an");
        if (c.cutout && c.fullscreen) display.add("Kamera-Aussparung");
        if (c.desktopMode) display.add("Desktop-Modus");
        if (c.zoom) display.add("Zoom");
        if (c.watermark) display.add("Wasserzeichen");
        java.util.List<String> web = new java.util.ArrayList<>();
        if (c.pullToRefresh) web.add("Ziehen zum Aktualisieren");
        if (c.offlinePage) web.add("Offline-Hinweis");
        if (c.progressBar) web.add("Ladebalken");
        if (c.backNavigation) web.add("Zurück in Web-Historie");
        if (c.exitConfirm) web.add("Beenden bestätigen");
        if (!c.javascript) web.add("JavaScript AUS");
        if (!c.domStorage) web.add("localStorage AUS");
        KeyManager.Entry key = keys.get(p.keyId);

        String content = !p.url.isEmpty() ? p.url
                : p.contentName != null ? p.contentName : "Webdateien";
        StringBuilder sb = new StringBuilder();
        sb.append("App-Name: ").append(editName.getText().toString().trim()).append('\n');
        sb.append("Paket: ").append(editPackage.getText().toString().trim()).append('\n');
        sb.append("Version: ").append(editVersionName.getText().toString().trim())
                .append(" (").append(editVersionCode.getText().toString().trim()).append(")\n");
        sb.append("Inhalt: ").append(content).append("\n\n");
        sb.append("Anzeige: ").append(String.join(" · ", display)).append('\n');
        sb.append("Icon: ").append(p.hasIcon() ? "Eigenes Bild" : "Standard").append('\n');
        sb.append("Splash-Screen: ").append(!c.splashEnabled ? "Aus"
                : (c.splashCustomImage ? "Eigenes Bild" : "App-Icon") + ", "
                + (c.splashDuration / 1000.0 + " s").replace('.', ',')).append('\n');
        sb.append("WebView: ").append(web.isEmpty() ? "Standard" : String.join(" · ", web)).append('\n');
        sb.append("Berechtigungen: ").append(perms.isEmpty() ? "keine" : String.join(", ", perms)).append('\n');
        sb.append("Schlüssel: ").append(key != null ? key.name : "Standard").append('\n');
        sb.append("Format: ").append(p.outputAab ? "AAB für Google Play (Umwandlung über GitHub)" : "APK")
                .append('\n');
        String file = apkFileName(editFileName.getText().toString(),
                editName.getText().toString().trim(), editVersionName.getText().toString().trim());
        sb.append("Datei: ").append(p.outputAab ? file.replaceAll("\\.apk$", ".aab") : file);

        new AlertDialog.Builder(this)
                .setTitle("Ist alles korrekt?")
                .setMessage(sb.toString())
                .setNegativeButton("Ändern", null)
                .setPositiveButton("APK erstellen", (d, w) -> onConfirm.run())
                .show();
    }

    private void runBuild(String url, String name, String pkg, String versionName, int versionCode,
                          String apkName) {
        final Project p = project;
        final Set<String> remove = permissionsToRemove(p);
        if (building) return;
        final boolean aab = p.outputAab;
        final String ghRepo = prefs.getString("gh_repo", "");
        final String ghToken = prefs.getString("gh_token", "");
        if (aab && (!GithubAab.isValidRepo(ghRepo) || ghToken.isEmpty())) {
            showGithubSetup();
            return;
        }
        setBusy(true);
        status("Baue " + name + " …");

        worker.execute(() -> {
            try {
                File apk = build(p, url, name, pkg, versionName, versionCode, remove, apkName);
                // Im Projekt ablegen, damit sie später wieder installiert/geteilt werden kann
                try (InputStream in = new FileInputStream(apk)) {
                    Project.copy(in, p.lastApkFile());
                }
                final File result = aab ? makeAab(apk, p, ghRepo, ghToken) : apk;
                ui.post(() -> {
                    lastApk = result;
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
                    showResultButtons();
                    status("Fertig: " + result.getName() + " (" + (result.length() / 1024) + " KB)\n"
                            + "Paket " + pkg + ", Version " + versionName + " (" + versionCode + ")"
                            + (aab ? "\nDiese Datei lädst du in der Google Play Console hoch." : ""));
                });
            } catch (Throwable t) {
                ui.post(() -> {
                    setBusy(false);
                    status("Fehler: " + t.getMessage());
                });
            }
        });
    }

    /** APK über GitHub in ein AAB umwandeln lassen und hier mit dem Projektschlüssel signieren. */
    private File makeAab(File apk, Project p, String repo, String token) throws Exception {
        File work = new File(getCacheDir(), "work");
        work.mkdirs();
        File unsigned = new File(work, "unsigned.aab");
        new GithubAab(repo, token).convert(apk, unsigned, this::progressText);
        progressText("Signiere AAB …");
        File out = new File(OutputProvider.outputDir(this), apk.getName().replaceAll("\\.apk$", "") + ".aab");
        de.htmlapk.builder.core.AabSigning.sign(unsigned, out, keys.load(p.keyId));
        //noinspection ResultOfMethodCallIgnored
        unsigned.delete();
        //noinspection ResultOfMethodCallIgnored
        apk.delete();
        return out;
    }

    private boolean lastIsAab() {
        return lastApk != null && lastApk.getName().endsWith(".aab");
    }

    private void showResultButtons() {
        resultButtons.setVisibility(View.VISIBLE);
        findViewById(R.id.btnInstall).setVisibility(lastIsAab() ? View.GONE : View.VISIBLE);
    }

    // ------------------------------------------------------------ GitHub

    private void showGithubSetup() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView help = new TextView(this);
        help.setTextSize(13);
        help.setTextColor(0xFF616161);
        help.setText("Die Umwandlung läuft kostenlos über GitHub Actions in deinem eigenen "
                + "GitHub-Konto. So richtest du es einmalig ein:\n\n"
                + "1. Eigene Kopie anlegen: Tippe unten auf „Kopie auf GitHub anlegen“ (oder öffne "
                + "github.com/" + SOURCE_REPO + " und wähle „Use this template“). Gib ihr einen Namen, "
                + "am besten „Private“, damit deine Apps nicht öffentlich sichtbar sind.\n\n"
                + "2. Zugangs-Token erstellen: github.com → Profilbild → Settings → Developer settings → "
                + "Personal access tokens → Fine-grained tokens → Generate new token. Bei „Repository "
                + "access“ nur deine Kopie wählen, bei „Repository permissions“ Contents und Actions "
                + "auf „Read and write“ stellen.\n\n"
                + "3. Unten deine Kopie (z. B. deinname/htmlapk) und den Token eintragen.\n\n"
                + "Der Token bleibt nur auf diesem Handy. Gib ihn niemandem weiter.");
        box.addView(help);
        Button openGithub = new Button(this);
        openGithub.setText("Kopie auf GitHub anlegen");
        openGithub.setAllCaps(false);
        openGithub.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/" + SOURCE_REPO + "/generate")));
            } catch (ActivityNotFoundException e) {
                toast("Kein Browser gefunden");
            }
        });
        box.addView(openGithub);
        EditText repo = new EditText(this);
        repo.setHint("deinname/htmlapk");
        repo.setSingleLine(true);
        repo.setText(prefs.getString("gh_repo", ""));
        box.addView(repo);
        EditText token = new EditText(this);
        token.setHint("Token (github_pat_…)");
        token.setSingleLine(true);
        token.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setText(prefs.getString("gh_token", ""));
        box.addView(token);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(padded(box));
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("GitHub für AAB")
                .setView(scroll)
                .setNegativeButton("Abbrechen", null)
                .setNeutralButton("Entfernen", (d, w) -> {
                    prefs.edit().remove("gh_repo").remove("gh_token").apply();
                    form.updateGithubHint();
                })
                .setPositiveButton("Prüfen & speichern", null)
                .create();
        dlg.setOnShowListener(x -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String r = repo.getText().toString().trim()
                    .replaceFirst("^https?://github.com/", "").replaceAll("/+$", "");
            String t = token.getText().toString().trim();
            if (!GithubAab.isValidRepo(r)) {
                repo.setError("Form: benutzer/repository");
                return;
            }
            if (t.isEmpty()) {
                token.setError("Bitte Token eintragen");
                return;
            }
            v.setEnabled(false);
            toast("Prüfe Verbindung …");
            worker.execute(() -> {
                try {
                    new GithubAab(r, t).check();
                    ui.post(() -> {
                        prefs.edit().putString("gh_repo", r).putString("gh_token", t).apply();
                        form.updateGithubHint();
                        toast("GitHub verbunden – AAB-Erstellung ist bereit");
                        dlg.dismiss();
                    });
                } catch (Exception e) {
                    ui.post(() -> {
                        v.setEnabled(true);
                        new AlertDialog.Builder(this).setTitle("Verbindung fehlgeschlagen")
                                .setMessage(e.getMessage()).setPositiveButton("OK", null).show();
                    });
                }
            });
        }));
        dlg.show();
    }

    private File build(Project p, String url, String name, String pkg, String versionName,
                       int versionCode, Set<String> removePermissions, String apkName) throws Exception {
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
            input.configJson = p.opts.toJson(url);
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
            input.configJson = p.opts.toJson(web.startPage);
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

        if (p.opts.splashEnabled && p.opts.splashCustomImage && p.splashFile().isFile()) {
            try (InputStream in = new FileInputStream(p.splashFile())) {
                input.extraAssets = new java.util.HashMap<>();
                input.extraAssets.put("splash.png", ApkAssembler.readAll(in));
            }
        }

        progressText("APK zusammenbauen …");
        File unsigned = new File(work, "unsigned.apk");
        ApkAssembler.assemble(input, unsigned);

        progressText("Signieren …");
        SigningKey key = keys.load(p.keyId);
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
        i.setType(lastIsAab() ? "application/octet-stream" : "application/vnd.android.package-archive");
        i.putExtra(Intent.EXTRA_STREAM, OutputProvider.uriFor(lastApk));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, lastIsAab() ? "AAB teilen" : "APK teilen"));
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
