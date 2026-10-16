package de.htmlapk.builder;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

import de.htmlapk.builder.core.ApkAssembler;
import de.htmlapk.builder.core.SigningKey;

/**
 * Verwaltet Signaturschlüssel. Der Standardschlüssel liegt (wie in älteren Versionen)
 * unter files/signing.p12, weitere unter files/keys/&lt;id&gt;.p12 mit Beschreibung &lt;id&gt;.json.
 */
public final class KeyManager {

    public static final String DEFAULT_ID = "default";
    public static final String DEFAULT_PASSWORD = "htmlapk";

    public static final class Entry {
        public String id;
        public String name;
        public String alias;
        public String password;
        public File file;
        public long created;

        public boolean exists() {
            return file.isFile() && file.length() > 0;
        }

        public boolean isDefault() {
            return DEFAULT_ID.equals(id);
        }
    }

    private final File filesDir;

    public KeyManager(File filesDir) {
        this.filesDir = filesDir;
    }

    public File keysDir() {
        File d = new File(filesDir, "keys");
        d.mkdirs();
        return d;
    }

    public File defaultFile() {
        return new File(filesDir, "signing.p12");
    }

    public Entry defaultEntry() {
        Entry e = new Entry();
        e.id = DEFAULT_ID;
        e.name = "Standard-Schlüssel";
        e.alias = SigningKey.ALIAS;
        e.password = DEFAULT_PASSWORD;
        e.file = defaultFile();
        return e;
    }

    public List<Entry> list() {
        List<Entry> out = new ArrayList<>();
        File[] files = keysDir().listFiles((d, n) -> n.endsWith(".json"));
        if (files != null) {
            for (File f : files) {
                Entry e = read(f.getName().substring(0, f.getName().length() - 5));
                if (e != null && e.exists()) out.add(e);
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(a.created, b.created));
        out.add(0, defaultEntry());
        return out;
    }

    public Entry get(String id) {
        if (id == null || DEFAULT_ID.equals(id)) return defaultEntry();
        Entry e = read(id);
        return e != null && e.exists() ? e : null;
    }

    private Entry read(String id) {
        if (!id.matches("[a-z0-9]+")) return null;
        File json = new File(keysDir(), id + ".json");
        try (InputStream in = new FileInputStream(json)) {
            JSONObject o = new JSONObject(new String(ApkAssembler.readAll(in), StandardCharsets.UTF_8));
            Entry e = new Entry();
            e.id = id;
            e.name = o.optString("name", "Schlüssel");
            e.alias = o.optString("alias", null);
            e.password = o.optString("password", DEFAULT_PASSWORD);
            e.created = o.optLong("created");
            e.file = new File(keysDir(), id + ".p12");
            return e;
        } catch (Exception ex) {
            return null;
        }
    }

    private void write(Entry e) throws IOException {
        try {
            JSONObject o = new JSONObject();
            o.put("name", e.name);
            if (e.alias != null) o.put("alias", e.alias);
            o.put("password", e.password);
            o.put("created", e.created);
            try (OutputStream out = new FileOutputStream(new File(keysDir(), e.id + ".json"))) {
                out.write(o.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (org.json.JSONException ex) {
            throw new IOException(ex);
        }
    }

    private static String newId() {
        return Long.toString(System.currentTimeMillis(), 36)
                + Integer.toString(new SecureRandom().nextInt(1 << 20), 36);
    }

    /** Neuer, eigener Schlüssel (RSA 2048, ~30 Jahre gültig), Passwort "htmlapk". */
    public Entry create(String name) throws Exception {
        SigningKey key = SigningKey.generate(name.trim().isEmpty() ? "HTML APK Builder" : name.trim());
        Entry e = new Entry();
        e.id = newId();
        e.name = name.trim().isEmpty() ? "Neuer Schlüssel" : name.trim();
        e.alias = SigningKey.ALIAS;
        e.password = DEFAULT_PASSWORD;
        e.created = System.currentTimeMillis();
        e.file = new File(keysDir(), e.id + ".p12");
        try (OutputStream out = new FileOutputStream(e.file)) {
            key.store(out);
        }
        write(e);
        return e;
    }

    /** Importiert einen vorhandenen Schlüssel (.p12/.pfx oder .bks). Prüft Passwort und Inhalt. */
    public Entry importKey(String name, byte[] data, String password, String alias) throws Exception {
        LoadedStore ls = openStore(data, password);
        String found = findKeyAlias(ls.store, alias, password);
        Entry e = new Entry();
        e.id = newId();
        e.name = name.trim().isEmpty() ? "Importierter Schlüssel" : name.trim();
        e.alias = found;
        e.password = password;
        e.created = System.currentTimeMillis();
        e.file = new File(keysDir(), e.id + ".p12");
        // Immer als PKCS12 ablegen (auch wenn BKS importiert wurde)
        try (OutputStream out = new FileOutputStream(e.file)) {
            if ("PKCS12".equals(ls.type)) {
                out.write(data);
            } else {
                KeyStore p12 = KeyStore.getInstance("PKCS12");
                p12.load(null, null);
                p12.setKeyEntry(found, ls.store.getKey(found, password.toCharArray()),
                        password.toCharArray(), ls.store.getCertificateChain(found));
                p12.store(out, password.toCharArray());
            }
        }
        write(e);
        return e;
    }

    public void delete(String id) {
        if (DEFAULT_ID.equals(id) || id == null || !id.matches("[a-z0-9]+")) return;
        //noinspection ResultOfMethodCallIgnored
        new File(keysDir(), id + ".p12").delete();
        //noinspection ResultOfMethodCallIgnored
        new File(keysDir(), id + ".json").delete();
    }

    public void rename(String id, String name) throws IOException {
        Entry e = get(id);
        if (e == null || e.isDefault()) return;
        e.name = name.trim().isEmpty() ? e.name : name.trim();
        write(e);
    }

    /** Lädt den Schlüssel zum Signieren; der Standardschlüssel wird bei Bedarf erzeugt. */
    public SigningKey load(String id) throws Exception {
        Entry e = get(id);
        if (e == null) throw new IOException("Der gewählte Signaturschlüssel existiert nicht mehr");
        if (e.isDefault()) return SigningKey.loadOrCreate(e.file, "HTML APK Builder");
        try (InputStream in = new FileInputStream(e.file)) {
            return SigningKey.load(in, e.password.toCharArray(), e.alias);
        }
    }

    /** Kurzer SHA-256-Fingerabdruck des Zertifikats, z. B. "3F:A1:…:9C". */
    public String fingerprint(Entry e) {
        if (!e.exists()) return "wird beim ersten Bauen erzeugt";
        try (InputStream in = new FileInputStream(e.file)) {
            SigningKey k = SigningKey.load(in, e.password.toCharArray(), e.alias);
            byte[] d = MessageDigest.getInstance("SHA-256").digest(k.certificate.getEncoded());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                if (i > 0) sb.append(':');
                sb.append(String.format("%02X", d[i]));
            }
            return sb.append(":…").toString();
        } catch (Exception ex) {
            return "nicht lesbar";
        }
    }

    // ------------------------------------------------------------------ intern

    private static final class LoadedStore {
        KeyStore store;
        String type;
    }

    private static LoadedStore openStore(byte[] data, String password) throws Exception {
        Exception pkcs12Error = null;
        for (String type : new String[]{"PKCS12", "BKS"}) {
            try {
                KeyStore ks = KeyStore.getInstance(type);
                ks.load(new ByteArrayInputStream(data), password.toCharArray());
                LoadedStore ls = new LoadedStore();
                ls.store = ks;
                ls.type = type;
                return ls;
            } catch (Exception ex) {
                if (pkcs12Error == null) pkcs12Error = ex;
            }
        }
        // Meldung des PKCS12-Versuchs auswerten (BKS fehlt auf manchen Systemen ganz)
        StringBuilder all = new StringBuilder();
        for (Throwable t = pkcs12Error; t != null; t = t.getCause()) {
            all.append(t.getClass().getSimpleName()).append(' ').append(t.getMessage()).append(' ');
        }
        String msg = all.toString().toLowerCase(java.util.Locale.ROOT);
        if (msg.contains("password") || msg.contains("mac") || msg.contains("unrecoverable")
                || msg.contains("badpadding") || msg.contains("decrypt")) {
            throw new IOException("Falsches Passwort");
        }
        throw new IOException("Unbekanntes Format. Unterstützt: .p12/.pfx (PKCS12) und .bks. "
                + "Eine .jks-Datei kannst du am PC umwandeln mit: keytool -importkeystore "
                + "-srckeystore alt.jks -destkeystore neu.p12 -deststoretype PKCS12");
    }

    private static String findKeyAlias(KeyStore ks, String wanted, String password) throws Exception {
        List<String> candidates = new ArrayList<>();
        if (wanted != null && !wanted.trim().isEmpty()) {
            if (!ks.containsAlias(wanted.trim())) throw new IOException("Alias „" + wanted + "“ nicht gefunden");
            candidates.add(wanted.trim());
        } else {
            Enumeration<String> a = ks.aliases();
            while (a.hasMoreElements()) candidates.add(a.nextElement());
        }
        for (String alias : candidates) {
            if (!ks.isKeyEntry(alias)) continue;
            PrivateKey key = (PrivateKey) ks.getKey(alias, password.toCharArray());
            if (key != null && ks.getCertificate(alias) instanceof X509Certificate) return alias;
        }
        throw new IOException("Kein privater Schlüssel in der Datei gefunden");
    }
}
