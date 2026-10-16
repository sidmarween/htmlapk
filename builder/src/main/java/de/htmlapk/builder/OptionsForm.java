package de.htmlapk.builder;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import de.htmlapk.builder.core.AppConfig;

/**
 * Baut die Abschnitte Anzeige, Splash-Screen, WebView, Berechtigungen und Signatur
 * und schreibt jede Änderung direkt ins Projekt.
 */
public class OptionsForm {

    public interface Host {
        void pickSplashImage();
        void manageKeys();
        void setupGithub();
        boolean isGithubConfigured();
    }

    private interface Getter { boolean get(); }
    private interface Setter { void set(boolean v); }
    private interface IntGetter { int get(); }
    private interface IntSetter { void set(int v); }
    private interface StrGetter { String get(); }
    private interface StrSetter { void set(String v); }

    private final Activity a;
    private final Host host;
    private final float d;
    private final List<Runnable> loaders = new ArrayList<>();
    private final KeyManager keys;
    private Project p;
    private boolean loading;

    // Splash-Vorschau
    private FrameLayout previewScreen;
    private ImageView previewIcon;
    private LoaderView previewLoader;
    private LinearLayout splashDetails;
    private Bitmap appIcon, splashImage;
    private Button btnSplashImage;

    // Signatur
    private LinearLayout githubBox;
    private TextView githubHint;
    private Spinner keySpinner;
    private TextView keyInfo;
    private List<KeyManager.Entry> keyEntries = new ArrayList<>();

    public OptionsForm(Activity activity, LinearLayout container, KeyManager keys, Host host) {
        this.a = activity;
        this.host = host;
        this.keys = keys;
        this.d = activity.getResources().getDisplayMetrics().density;
        buildDisplay(container);
        buildSplash(container);
        buildWebView(container);
        buildPermissions(container);
        buildSigning(container);
    }

    public void load(Project project) {
        p = project;
        loading = true;
        for (Runnable r : loaders) r.run();
        loading = false;
        updateSplashVisibility();
        refreshKeys();
        updatePreview();
    }

    private AppConfig c() {
        return p.opts;
    }

    // ---------------------------------------------------------------- Anzeige

    private void buildDisplay(LinearLayout root) {
        section(root, "4 · Anzeige");
        check(root, "Vollbild", "Statusleiste ausblenden, Inhalt nutzt den ganzen Bildschirm",
                () -> c().fullscreen, v -> c().fullscreen = v);
        check(root, "Titelleiste anzeigen", "Leiste mit dem App-Namen oben (nicht im Vollbild)",
                () -> c().titleBar, v -> c().titleBar = v);
        colorRow(root, "Farbe der Titelleiste", () -> c().titleBarColor, v -> c().titleBarColor = v);
        check(root, "Bildschirm eingeschaltet lassen", "Verhindern, dass sich der Bildschirm ausschaltet",
                () -> c().keepScreenOn, v -> c().keepScreenOn = v);
        check(root, "Kamera-Aussparung nutzen", "Im Vollbild auch den Bereich der Kamera-Notch bzw. des Punch-Holes verwenden",
                () -> c().cutout, v -> c().cutout = v);
        check(root, "Desktop-Modus", "Webseiten im Desktop-Browser-Layout laden",
                () -> c().desktopMode, v -> c().desktopMode = v);
        check(root, "Zoomen mit zwei Fingern", "Die Seite darf vergrößert werden",
                () -> c().zoom, v -> c().zoom = v);
        check(root, "Wasserzeichen anzeigen", "Kleiner Hinweis „Erstellt mit HTML APK Builder“ unten rechts",
                () -> c().watermark, v -> c().watermark = v);
        spinner(root, "Ausrichtung", new String[]{"Automatisch", "Hochformat", "Querformat"},
                new String[]{"auto", "portrait", "landscape"}, () -> c().orientation, v -> c().orientation = v);
    }

    // ----------------------------------------------------------------- Splash

    private void buildSplash(LinearLayout root) {
        section(root, "5 · Splash-Screen");
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));
        LinearLayout texts = textBlock("Splash-Screen aktivieren", "Zeigt dein Logo und eine Ladeanimation beim Öffnen der App");
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Switch sw = new Switch(a);
        row.addView(sw);
        root.addView(row);
        sw.setOnCheckedChangeListener((b, v) -> {
            if (loading) return;
            c().splashEnabled = v;
            updateSplashVisibility();
        });
        loaders.add(() -> sw.setChecked(c().splashEnabled));

        splashDetails = new LinearLayout(a);
        splashDetails.setOrientation(LinearLayout.VERTICAL);
        root.addView(splashDetails);
        LinearLayout s = splashDetails;

        // Vorschau im Handy-Rahmen
        FrameLayout phone = new FrameLayout(a);
        GradientDrawable frame = new GradientDrawable();
        frame.setColor(0xFF1B2026);
        frame.setCornerRadius(dp(28));
        phone.setBackground(frame);
        phone.setPadding(dp(8), dp(8), dp(8), dp(8));
        previewScreen = new FrameLayout(a);
        previewScreen.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(20));
            }
        });
        previewScreen.setClipToOutline(true);
        phone.addView(previewScreen);
        LinearLayout center = new LinearLayout(a);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER_HORIZONTAL);
        previewScreen.addView(center, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        previewIcon = new ImageView(a);
        previewIcon.setClipToOutline(true);
        center.addView(previewIcon);
        previewLoader = new LoaderView(a);
        center.addView(previewLoader);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(180), dp(320));
        plp.gravity = Gravity.CENTER_HORIZONTAL;
        plp.topMargin = dp(8);
        plp.bottomMargin = dp(8);
        s.addView(phone, plp);

        RadioGroup imgChoice = new RadioGroup(a);
        RadioButton rbIcon = new RadioButton(a);
        rbIcon.setText("App-Icon verwenden");
        rbIcon.setId(View.generateViewId());
        RadioButton rbCustom = new RadioButton(a);
        rbCustom.setText("Eigenes Bild");
        rbCustom.setId(View.generateViewId());
        imgChoice.addView(rbIcon);
        imgChoice.addView(rbCustom);
        s.addView(imgChoice);
        btnSplashImage = new Button(a);
        btnSplashImage.setText("Bild für den Splash-Screen wählen");
        btnSplashImage.setAllCaps(false);
        btnSplashImage.setOnClickListener(v -> host.pickSplashImage());
        s.addView(btnSplashImage);
        imgChoice.setOnCheckedChangeListener((g, id) -> {
            if (loading) return;
            c().splashCustomImage = id == rbCustom.getId();
            btnSplashImage.setVisibility(c().splashCustomImage ? View.VISIBLE : View.GONE);
            if (c().splashCustomImage && splashImage == null) host.pickSplashImage();
            updatePreview();
        });
        loaders.add(() -> {
            imgChoice.check(c().splashCustomImage ? rbCustom.getId() : rbIcon.getId());
            btnSplashImage.setVisibility(c().splashCustomImage ? View.VISIBLE : View.GONE);
        });

        colorRow(s, "Hintergrundfarbe", () -> c().splashBg, v -> c().splashBg = v);
        spinner(s, "Lade-Stil", new String[]{"Kreis", "Balken", "Keiner"},
                new String[]{"circle", "bar", "none"}, () -> c().splashLoader, v -> c().splashLoader = v);
        colorRow(s, "Farbe des Rings / Ladebalkens", () -> c().splashLoaderColor, v -> c().splashLoaderColor = v);
        slider(s, "Symbolgröße", 48, 300, 4, "dp", () -> c().splashIconSize, v -> c().splashIconSize = v);
        slider(s, "Ecken des Symbols", 0, 150, 2, "dp", () -> c().splashIconRadius, v -> c().splashIconRadius = v);
        slider(s, "Größe des Lade-Elements", 16, 120, 2, "dp", () -> c().splashLoaderSize, v -> c().splashLoaderSize = v);
        slider(s, "Abstand", 0, 120, 2, "dp", () -> c().splashSpacing, v -> c().splashSpacing = v);
        slider(s, "Anzeigedauer", 500, 10000, 500, "ms", () -> c().splashDuration, v -> c().splashDuration = v);
        slider(s, "Dicke des Rings / Ladebalkens", 1, 16, 1, "dp", () -> c().splashThickness, v -> c().splashThickness = v);
        slider(s, "Dauer der Überblendung", 0, 2000, 100, "ms", () -> c().splashFade, v -> c().splashFade = v);
    }

    public void setAppIcon(Bitmap icon) {
        appIcon = icon;
        updatePreview();
    }

    public void setSplashImage(Bitmap img) {
        splashImage = img;
        updatePreview();
    }

    private void updateSplashVisibility() {
        if (p == null) return;
        splashDetails.setVisibility(c().splashEnabled ? View.VISIBLE : View.GONE);
    }

    private void updatePreview() {
        if (p == null || previewScreen == null) return;
        AppConfig cfg = c();
        float scale = 164f / 360f; // Vorschau-Bildschirm (dp) / typische Handybreite (dp)
        previewScreen.setBackgroundColor(parse(cfg.splashBg, 0xFF0F1318));
        Bitmap img = cfg.splashCustomImage && splashImage != null ? splashImage : appIcon;
        int size = Math.max(4, Math.round(dp(cfg.splashIconSize) * scale));
        int w = size, h = size;
        if (img != null) {
            previewIcon.setImageBitmap(img);
            if (cfg.splashCustomImage && splashImage != null) {
                previewIcon.setScaleType(ImageView.ScaleType.FIT_XY);
                h = Math.round(size * img.getHeight() / (float) img.getWidth());
            } else {
                previewIcon.setScaleType(ImageView.ScaleType.CENTER_CROP);
            }
        } else {
            previewIcon.setImageResource(R.drawable.default_app_icon);
            previewIcon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        }
        final float radius = dp(cfg.splashIconRadius) * scale;
        previewIcon.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        previewIcon.setLayoutParams(new LinearLayout.LayoutParams(w, h));
        previewIcon.invalidateOutline();

        int thick = Math.max(1, Math.round(dp(cfg.splashThickness) * scale));
        previewLoader.setColor(parse(cfg.splashLoaderColor, 0xFF00E5CC));
        previewLoader.setThickness(thick);
        int loaderSize = Math.max(4, Math.round(dp(cfg.splashLoaderSize) * scale));
        LinearLayout.LayoutParams llp;
        if ("bar".equals(cfg.splashLoader)) {
            previewLoader.setStyle(LoaderView.STYLE_BAR);
            llp = new LinearLayout.LayoutParams(Math.max(w, loaderSize * 3), thick + 2);
        } else {
            previewLoader.setStyle(LoaderView.STYLE_CIRCLE);
            llp = new LinearLayout.LayoutParams(loaderSize, loaderSize);
        }
        llp.gravity = Gravity.CENTER_HORIZONTAL;
        llp.topMargin = Math.round(dp(cfg.splashSpacing) * scale);
        previewLoader.setLayoutParams(llp);
        previewLoader.setVisibility("none".equals(cfg.splashLoader) ? View.GONE : View.VISIBLE);
    }

    // ---------------------------------------------------------------- WebView

    private void buildWebView(LinearLayout root) {
        section(root, "6 · WebView");
        sub(root, "Navigation & Erlebnis");
        check(root, "Zum Aktualisieren ziehen", "Ganz oben nach unten wischen lädt die Seite neu",
                () -> c().pullToRefresh, v -> c().pullToRefresh = v);
        check(root, "Kein-Internet-Hinweis", "Bei Verbindungsabbruch einen Hinweis mit „Erneut versuchen“ zeigen",
                () -> c().offlinePage, v -> c().offlinePage = v);
        check(root, "Ladebalken", "Dünnen Balken oben während des Ladens zeigen",
                () -> c().progressBar, v -> c().progressBar = v);
        check(root, "Zurück-Taste in der Web-Historie", "Zurück geht zur vorherigen Seite statt die App zu schließen",
                () -> c().backNavigation, v -> c().backNavigation = v);
        check(root, "Beenden bestätigen", "Vor dem Schließen der App nachfragen",
                () -> c().exitConfirm, v -> c().exitConfirm = v);

        TextView toggle = new TextView(a);
        toggle.setText("Erweiterte Einstellungen ▾");
        toggle.setTextColor(0xFFE8622C);
        toggle.setTextSize(15);
        toggle.setTypeface(Typeface.DEFAULT_BOLD);
        toggle.setPadding(0, dp(14), 0, dp(6));
        root.addView(toggle);
        LinearLayout adv = new LinearLayout(a);
        adv.setOrientation(LinearLayout.VERTICAL);
        adv.setVisibility(View.GONE);
        root.addView(adv);
        toggle.setOnClickListener(v -> {
            boolean show = adv.getVisibility() != View.VISIBLE;
            adv.setVisibility(show ? View.VISIBLE : View.GONE);
            toggle.setText(show ? "Erweiterte Einstellungen ▴" : "Erweiterte Einstellungen ▾");
        });
        check(adv, "JavaScript", "Ohne JavaScript funktionieren die meisten Seiten nicht",
                () -> c().javascript, v -> c().javascript = v);
        check(adv, "DOM-Speicher (localStorage)", "Nötig für Spielstände und Einstellungen der Seite",
                () -> c().domStorage, v -> c().domStorage = v);
        check(adv, "Breites Viewport", "Meta-Viewport der Seite beachten",
                () -> c().wideViewport, v -> c().wideViewport = v);
        check(adv, "Übersichtsmodus", "Seite beim Laden auf Bildschirmbreite einpassen",
                () -> c().overview, v -> c().overview = v);
        spinner(adv, "Cache-Modus",
                new String[]{"Standard", "Cache bevorzugen", "Kein Cache", "Nur Cache (offline)"},
                new String[]{"default", "cache_else_network", "no_cache", "cache_only"},
                () -> c().cacheMode, v -> c().cacheMode = v);
        spinner(adv, "Mixed-Content (http auf https-Seiten)",
                new String[]{"Kompatibilitätsmodus", "Immer erlauben", "Nie erlauben"},
                new String[]{"compat", "always", "never"},
                () -> c().mixedContent, v -> c().mixedContent = v);
        TextView note = hint("Universeller Dateizugriff ist absichtlich nicht wählbar: Deine Dateien werden "
                + "sicher über eine interne https-Adresse geladen, fetch() und Module funktionieren trotzdem.");
        adv.addView(note);
    }

    // --------------------------------------------------------- Berechtigungen

    private void buildPermissions(LinearLayout root) {
        section(root, "7 · Berechtigungen");
        root.addView(hint("Wähle nur, was deine App braucht. Weniger Berechtigungen = mehr Vertrauen."));
        check(root, "Internet / Netzwerk", "Online-Inhalte und Website-URLs",
                () -> p.permInternet, v -> p.permInternet = v);
        check(root, "Mikrofon", "Für getUserMedia({audio: true})",
                () -> p.permMic, v -> p.permMic = v);
        check(root, "Kamera", "Für getUserMedia({video: true})",
                () -> p.permCamera, v -> p.permCamera = v);
        check(root, "Standort", "Für navigator.geolocation",
                () -> p.permLocation, v -> p.permLocation = v);
        check(root, "Vibration", "Für navigator.vibrate()",
                () -> p.permVibrate, v -> p.permVibrate = v);
        check(root, "Benachrichtigungen",
                "Deine Seite kann mit new Notification('Titel', {body: 'Text'}) Android-Benachrichtigungen zeigen",
                () -> p.permNotifications, v -> p.permNotifications = v);
    }

    // ---------------------------------------------------------------- Signatur

    private void buildSigning(LinearLayout root) {
        section(root, "8 · Ausgabe & Signatur");
        spinner(root, "Format", new String[]{"APK", "AAB (Google Play)"}, new String[]{"apk", "aab"},
                () -> p.outputAab ? "aab" : "apk", v -> {
                    p.outputAab = "aab".equals(v);
                    updateGithubHint();
                });
        githubBox = new LinearLayout(a);
        githubBox.setOrientation(LinearLayout.VERTICAL);
        githubHint = hint("");
        githubBox.addView(githubHint);
        Button gh = new Button(a);
        gh.setText("GitHub für AAB einrichten");
        gh.setAllCaps(false);
        gh.setOnClickListener(v -> host.setupGithub());
        githubBox.addView(gh);
        root.addView(githubBox);
        loaders.add(this::updateGithubHint);
        sub(root, "Signaturschlüssel");
        keySpinner = new Spinner(a);
        root.addView(keySpinner);
        keyInfo = hint("");
        root.addView(keyInfo);
        keySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (loading || p == null || pos >= keyEntries.size()) return;
                KeyManager.Entry e = keyEntries.get(pos);
                if (!e.id.equals(p.keyId) && p.lastBuiltCode > 0) {
                    new AlertDialog.Builder(a)
                            .setTitle("Schlüssel wechseln?")
                            .setMessage("Diese App wurde schon mit einem anderen Schlüssel gebaut. Android "
                                    + "installiert ein Update nur, wenn es mit demselben Schlüssel signiert "
                                    + "ist – sonst musst du die App vorher deinstallieren (Daten gehen verloren).")
                            .setNegativeButton("Abbrechen", (dlg, w) -> refreshKeys())
                            .setPositiveButton("Trotzdem wechseln", (dlg, w) -> {
                                p.keyId = e.id;
                                updateKeyInfo();
                            })
                            .setOnCancelListener(dlg -> refreshKeys())
                            .show();
                } else {
                    p.keyId = e.id;
                    updateKeyInfo();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        Button manage = new Button(a);
        manage.setText("🔒  Schlüssel verwalten");
        manage.setAllCaps(false);
        manage.setOnClickListener(v -> host.manageKeys());
        root.addView(manage);
    }

    public void updateGithubHint() {
        if (p == null || githubBox == null) return;
        githubBox.setVisibility(p.outputAab ? View.VISIBLE : View.GONE);
        githubHint.setText(host.isGithubConfigured()
                ? "Die APK wird auf dem Handy gebaut, auf GitHub in ein AAB umgewandelt und hier mit "
                  + "deinem Schlüssel signiert. Für Google Play am besten einen eigenen Schlüssel nutzen "
                  + "(= Upload-Schlüssel) und gut sichern."
                : "Für AAB brauchst du einmalig ein GitHub-Repository mit dem Umwandlungs-Workflow "
                  + "und einen Zugangs-Token.");
    }

    public void refreshKeys() {
        if (p == null) return;
        keyEntries = keys.list();
        if (keys.get(p.keyId) == null) p.keyId = KeyManager.DEFAULT_ID;
        List<String> names = new ArrayList<>();
        int sel = 0;
        for (int i = 0; i < keyEntries.size(); i++) {
            names.add(keyEntries.get(i).name);
            if (keyEntries.get(i).id.equals(p.keyId)) sel = i;
        }
        boolean was = loading;
        loading = true;
        keySpinner.setAdapter(new ArrayAdapter<>(a, android.R.layout.simple_spinner_dropdown_item, names));
        keySpinner.setSelection(sel, false);
        loading = was;
        updateKeyInfo();
    }

    private void updateKeyInfo() {
        KeyManager.Entry e = keys.get(p.keyId);
        if (e == null) return;
        keyInfo.setText("Fingerabdruck: " + keys.fingerprint(e)
                + "\nFür Updates einer installierten App immer denselben Schlüssel verwenden.");
    }

    // ------------------------------------------------------------ Bausteine

    private void section(LinearLayout root, String title) {
        TextView t = new TextView(a, null, 0, R.style.Section);
        t.setText(title);
        root.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        ((LinearLayout.LayoutParams) t.getLayoutParams()).topMargin = dp(20);
        ((LinearLayout.LayoutParams) t.getLayoutParams()).bottomMargin = dp(4);
    }

    private void sub(LinearLayout root, String title) {
        TextView t = new TextView(a);
        t.setText(title);
        t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(0xFF424242);
        t.setPadding(0, dp(6), 0, dp(2));
        root.addView(t);
    }

    private TextView hint(String s) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(13);
        t.setTextColor(0xFF757575);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private LinearLayout textBlock(String title, String subtitle) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(a);
        t.setText(title);
        t.setTextSize(16);
        t.setTextColor(0xFF212121);
        box.addView(t);
        if (subtitle != null) {
            TextView s = new TextView(a);
            s.setText(subtitle);
            s.setTextSize(13);
            s.setTextColor(0xFF757575);
            box.addView(s);
        }
        return box;
    }

    private void check(LinearLayout root, String title, String subtitle, Getter g, Setter s) {
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        CheckBox cb = new CheckBox(a);
        row.addView(cb);
        LinearLayout texts = textBlock(title, subtitle);
        texts.setPadding(dp(6), 0, 0, 0);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.setOnClickListener(v -> cb.toggle());
        cb.setOnCheckedChangeListener((CompoundButton b, boolean v) -> {
            if (!loading) s.set(v);
        });
        loaders.add(() -> cb.setChecked(g.get()));
        root.addView(row);
    }

    private void spinner(LinearLayout root, String label, String[] labels, String[] values,
                         StrGetter g, StrSetter s) {
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));
        TextView t = new TextView(a);
        t.setText(label);
        t.setTextSize(16);
        t.setTextColor(0xFF212121);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Spinner sp = new Spinner(a);
        sp.setAdapter(new ArrayAdapter<>(a, android.R.layout.simple_spinner_dropdown_item, labels));
        row.addView(sp);
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (loading) return;
                s.set(values[pos]);
                updatePreview();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        loaders.add(() -> {
            String cur = g.get();
            for (int i = 0; i < values.length; i++) {
                if (values[i].equals(cur)) sp.setSelection(i, false);
            }
        });
        root.addView(row);
    }

    private void slider(LinearLayout root, String label, int min, int max, int step, String unit,
                        IntGetter g, IntSetter s) {
        LinearLayout head = new LinearLayout(a);
        head.setPadding(0, dp(10), 0, 0);
        TextView t = new TextView(a);
        t.setText(label);
        t.setTextSize(15);
        t.setTextColor(0xFF212121);
        head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView val = new TextView(a);
        val.setTextSize(15);
        val.setTypeface(Typeface.DEFAULT_BOLD);
        val.setTextColor(0xFF212121);
        head.addView(val);
        root.addView(head);
        SeekBar bar = new SeekBar(a);
        bar.setMax((max - min) / step);
        root.addView(bar);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                int v = min + progress * step;
                val.setText(format(v, unit));
                if (!loading && fromUser) {
                    s.set(v);
                    updatePreview();
                }
            }

            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        loaders.add(() -> {
            int v = Math.max(min, Math.min(max, g.get()));
            bar.setProgress((v - min) / step);
            val.setText(format(v, unit));
        });
    }

    private static String format(int v, String unit) {
        if ("ms".equals(unit) && v >= 1000) {
            return (v % 1000 == 0 ? String.valueOf(v / 1000) : String.valueOf(v / 1000.0).replace('.', ',')) + " s";
        }
        return v + " " + unit;
    }

    private static final int[] PALETTE = {
            0xFF0F1318, 0xFF212121, 0xFF424242, 0xFF9E9E9E, 0xFFFFFFFF, 0xFFF4F1EA,
            0xFFE8622C, 0xFFF44336, 0xFFE91E63, 0xFF9C27B0, 0xFF673AB7, 0xFF3F51B5,
            0xFF2196F3, 0xFF03A9F4, 0xFF00E5CC, 0xFF009688, 0xFF4CAF50, 0xFF8BC34A,
            0xFFCDDC39, 0xFFFFEB3B, 0xFFFFC107, 0xFFFF9800, 0xFF795548, 0xFF8FCBED};

    private void colorRow(LinearLayout root, String label, StrGetter g, StrSetter s) {
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));
        TextView t = new TextView(a);
        t.setText(label);
        t.setTextSize(16);
        t.setTextColor(0xFF212121);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        EditText hex = new EditText(a);
        hex.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        hex.setEms(5);
        hex.setTextSize(15);
        hex.setTypeface(Typeface.MONOSPACE);
        row.addView(hex);
        View swatch = new View(a);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(36), dp(36));
        slp.leftMargin = dp(8);
        row.addView(swatch, slp);
        root.addView(row);

        Runnable paint = () -> {
            GradientDrawable dr = new GradientDrawable();
            dr.setShape(GradientDrawable.OVAL);
            dr.setColor(parse(g.get(), Color.GRAY));
            dr.setStroke(dp(1), 0xFFBDBDBD);
            swatch.setBackground(dr);
        };
        hex.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence x, int a1, int b, int c1) {}
            @Override public void onTextChanged(CharSequence x, int a1, int b, int c1) {}

            @Override
            public void afterTextChanged(Editable e) {
                if (loading) return;
                String n = AppConfig.normalizeColor(e.toString());
                if (n == null) {
                    hex.setError("z. B. #0F1318");
                    return;
                }
                hex.setError(null);
                s.set(n);
                paint.run();
                updatePreview();
            }
        });
        swatch.setOnClickListener(v -> {
            LinearLayout grid = new LinearLayout(a);
            grid.setOrientation(LinearLayout.VERTICAL);
            grid.setPadding(dp(16), dp(16), dp(16), dp(8));
            AlertDialog[] dlg = new AlertDialog[1];
            LinearLayout line = null;
            for (int i = 0; i < PALETTE.length; i++) {
                if (i % 6 == 0) {
                    line = new LinearLayout(a);
                    grid.addView(line);
                }
                final int color = PALETTE[i];
                View dot = new View(a);
                GradientDrawable dr = new GradientDrawable();
                dr.setShape(GradientDrawable.OVAL);
                dr.setColor(color);
                dr.setStroke(dp(1), 0xFFBDBDBD);
                dot.setBackground(dr);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(36));
                lp.setMargins(dp(5), dp(5), dp(5), dp(5));
                dot.setOnClickListener(x -> {
                    hex.setText(String.format("#%06X", color & 0xFFFFFF));
                    if (dlg[0] != null) dlg[0].dismiss();
                });
                line.addView(dot, lp);
            }
            dlg[0] = new AlertDialog.Builder(a).setTitle(label).setView(grid)
                    .setNegativeButton("Abbrechen", null).show();
        });
        loaders.add(() -> {
            hex.setText(g.get());
            hex.setError(null);
            paint.run();
        });
    }

    static int parse(String s, int fallback) {
        try {
            return Color.parseColor(s);
        } catch (Exception e) {
            return fallback;
        }
    }

    private int dp(float v) {
        return Math.round(v * d);
    }
}
