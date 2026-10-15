package de.htmlapk.builder;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import de.htmlapk.builder.core.ApkAssembler;
import de.htmlapk.builder.core.SigningKey;

/**
 * Export/Import von Projekten als ZIP.
 * Einzelprojekt: project.json, content.bin, icon.png, icon_source, last.apk im Wurzelverzeichnis.
 * Backup: projects/&lt;id&gt;/... für jedes Projekt plus signing.p12 (Signaturschlüssel).
 */
public final class ProjectArchive {

    /** Nur diese Dateien werden ex- und importiert (schützt auch vor manipulierten ZIPs). */
    private static final Set<String> FILES = new HashSet<>(Arrays.asList(
            "project.json", "content.bin", "icon.png", "icon_source", "last.apk"));
    private static final String KEY_ENTRY = "signing.p12";

    public static final class ImportResult {
        public int projects;
        public boolean keyImported;
        public boolean keySkipped;
    }

    private ProjectArchive() {}

    public static void exportProject(File projectDir, OutputStream out) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            addProjectFiles(zip, projectDir, "");
        }
    }

    public static void exportAll(Context c, File keyFile, OutputStream out) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            List<Project> all = Project.list(c);
            for (Project p : all) {
                addProjectFiles(zip, p.dir(), "projects/" + p.id + "/");
            }
            if (keyFile.isFile()) addFile(zip, keyFile, KEY_ENTRY);
        }
    }

    private static void addProjectFiles(ZipOutputStream zip, File dir, String prefix) throws IOException {
        for (String name : FILES) {
            File f = new File(dir, name);
            if (f.isFile()) addFile(zip, f, prefix + name);
        }
    }

    private static void addFile(ZipOutputStream zip, File f, String entry) throws IOException {
        zip.putNextEntry(new ZipEntry(entry));
        try (InputStream in = new FileInputStream(f)) {
            ApkAssembler.copy(in, zip);
        }
        zip.closeEntry();
    }

    /**
     * Liest ein Einzelprojekt oder ein Backup. Jedes Projekt bekommt eine neue ID, damit
     * nichts überschrieben wird. Der Schlüssel wird nur übernommen, wenn noch keiner existiert.
     */
    public static ImportResult importArchive(Context c, InputStream in, File keyFile) throws IOException {
        File staging = new File(c.getCacheDir(), "import");
        deleteRecursive(staging);
        staging.mkdirs();

        Map<String, File> groups = new LinkedHashMap<>();
        byte[] keyBytes = null;
        try (ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = e.getName().replace('\\', '/');
                if (name.equals(KEY_ENTRY)) {
                    keyBytes = ApkAssembler.readAll(new NonClosing(zip));
                    continue;
                }
                String group, base;
                int slash = name.lastIndexOf('/');
                if (slash < 0) {
                    group = "";
                    base = name;
                } else {
                    group = name.substring(0, slash);
                    base = name.substring(slash + 1);
                }
                if (!FILES.contains(base)) continue;
                File dir = groups.get(group);
                if (dir == null) {
                    dir = new File(staging, "g" + groups.size());
                    dir.mkdirs();
                    groups.put(group, dir);
                }
                try (OutputStream out = new FileOutputStream(new File(dir, base))) {
                    ApkAssembler.copy(zip, out);
                }
            }
        }

        ImportResult r = new ImportResult();
        SecureRandom rnd = new SecureRandom();
        for (File g : groups.values()) {
            if (!new File(g, "project.json").isFile()) continue;
            String id = Long.toString(System.currentTimeMillis(), 36)
                    + Integer.toString(rnd.nextInt(1 << 20), 36);
            File target = new File(Project.root(c), id);
            if (!g.renameTo(target)) continue;
            Project p = Project.load(c, id);
            if (p == null) {
                deleteRecursive(target);
                continue;
            }
            p.save(); // aktualisiert das Datum, damit es oben in der Liste steht
            r.projects++;
        }

        if (keyBytes != null) {
            if (keyFile.isFile()) {
                r.keySkipped = !Arrays.equals(keyBytes, ApkAssembler.readAll(new FileInputStream(keyFile)));
            } else {
                try {
                    SigningKey.load(new java.io.ByteArrayInputStream(keyBytes)); // gültig?
                    try (OutputStream out = new FileOutputStream(keyFile)) {
                        out.write(keyBytes);
                    }
                    r.keyImported = true;
                } catch (Exception ignored) {
                    // ungültiger Schlüssel wird einfach nicht übernommen
                }
            }
        }
        deleteRecursive(staging);
        if (r.projects == 0 && !r.keyImported) {
            throw new IOException("Keine Projekte in der Datei gefunden");
        }
        return r;
    }

    static void deleteRecursive(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /** Dateiname für den Export, z. B. "Lemmy.htmlapk.zip". */
    public static String exportFileName(String displayName) {
        String base = displayName.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (base.isEmpty()) base = "Projekt";
        return base + ".htmlapk.zip";
    }

    private static final class NonClosing extends FilterInputStream {
        NonClosing(InputStream in) { super(in); }
        @Override public void close() {}
    }
}
