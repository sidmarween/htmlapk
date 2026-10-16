package de.htmlapk.builder.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Baut aus der Vorlagen-APK eine neue, noch unsignierte APK:
 * Manifest patchen, assets/www ersetzen, htmlapk.json schreiben, Icons tauschen.
 * Reines Java, damit es sich auch am PC testen lässt.
 */
public final class ApkAssembler {

    /** Liefert ein PNG in exakt dieser Größe (auf Android über Bitmap, im Test über ImageIO). */
    public interface IconRenderer {
        byte[] render(int width, int height) throws IOException;
    }

    public static final class Input {
        public File templateApk;
        public AxmlPatcher.Changes manifest = new AxmlPatcher.Changes();
        /** Pfade relativ zu www/, z. B. "index.html" oder "img/logo.png". Darf leer sein (URL-Modus). */
        public Map<String, byte[]> webFiles;
        /** Zusätzliche Dateien unter assets/, z. B. "splash.png". */
        public Map<String, byte[]> extraAssets;
        public String configJson;
        /** null = Icon der Vorlage behalten. */
        public IconRenderer icon;
    }

    private ApkAssembler() {}

    public static void assemble(Input in, File outUnsigned) throws IOException {
        Set<String> written = new HashSet<>();
        CountingStream counter = new CountingStream(new FileOutputStream(outUnsigned));
        try (ZipFile zip = new ZipFile(in.templateApk);
             ZipOutputStream out = new ZipOutputStream(counter)) {
            Writer w = new Writer(out, counter);

            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String name = e.getName();
                if (e.isDirectory()) continue;
                if (name.startsWith("META-INF/")) continue;          // alte Signatur
                if (name.startsWith("assets/www/")) continue;        // Demo-Seite der Vorlage
                if (name.equals("assets/htmlapk.json")) continue;

                byte[] data = readAll(zip.getInputStream(e));
                if (name.equals("AndroidManifest.xml")) {
                    data = AxmlPatcher.patch(data, in.manifest);
                } else if (name.equals("resources.arsc") && in.manifest.packageName != null) {
                    data = ArscPatcher.renamePackage(data, in.manifest.packageName);
                } else if (in.icon != null && name.startsWith("res/") && name.endsWith(".png")) {
                    // Die Vorlage enthält nur Launcher-Icons als PNG. Pfade können beim
                    // Release-Build gekürzt sein (res/a.png), daher erkennen wir die
                    // Dichte an der Bildgröße statt am Ordnernamen.
                    int[] wh = pngSize(data);
                    if (wh != null) data = in.icon.render(wh[0], wh[1]);
                }
                // resources.arsc und PNGs müssen unkomprimiert bleiben (Android 11+).
                w.put(name, data, e.getMethod() == ZipEntry.STORED);
                written.add(name);
            }

            if (in.webFiles != null) {
                for (Map.Entry<String, byte[]> f : in.webFiles.entrySet()) {
                    String name = "assets/www/" + f.getKey();
                    if (written.add(name)) w.put(name, f.getValue(), false);
                }
            }
            if (in.extraAssets != null) {
                for (Map.Entry<String, byte[]> f : in.extraAssets.entrySet()) {
                    String name = "assets/" + f.getKey();
                    if (written.add(name)) w.put(name, f.getValue(), false);
                }
            }
            w.put("assets/htmlapk.json", in.configJson.getBytes(StandardCharsets.UTF_8), false);
        }
    }

    /**
     * Schreibt ZIP-Einträge. Unkomprimierte Daten werden wie bei zipalign auf 4 Byte
     * ausgerichtet (Extra-Feld 0xD935). apksig übernimmt diese Ausrichtung beim Signieren.
     */
    private static final class Writer {
        private final ZipOutputStream out;
        private final CountingStream counter;

        Writer(ZipOutputStream out, CountingStream counter) {
            this.out = out;
            this.counter = counter;
        }

        void put(String name, byte[] data, boolean stored) throws IOException {
            ZipEntry e = new ZipEntry(name);
            e.setTime(315532800000L); // 1980-01-01, für reproduzierbare Builds
            if (stored) {
                CRC32 crc = new CRC32();
                crc.update(data);
                e.setMethod(ZipEntry.STORED);
                e.setSize(data.length);
                e.setCompressedSize(data.length);
                e.setCrc(crc.getValue());
                out.flush();
                long headerStart = counter.count;
                int nameLen = name.getBytes(StandardCharsets.UTF_8).length;
                long dataStart = headerStart + 30 + nameLen + 6;
                int pad = (int) ((4 - dataStart % 4) % 4);
                byte[] extra = new byte[6 + pad];
                extra[0] = 0x35;
                extra[1] = (byte) 0xd9;          // Header-ID 0xD935 (Android-Alignment)
                extra[2] = (byte) (2 + pad);     // Länge der Nutzdaten
                extra[4] = 4;                    // Ausrichtung in Bytes
                e.setExtra(extra);
            } else {
                e.setMethod(ZipEntry.DEFLATED);
            }
            out.putNextEntry(e);
            out.write(data);
            out.closeEntry();
        }
    }

    private static final class CountingStream extends java.io.FilterOutputStream {
        long count;

        CountingStream(OutputStream out) { super(out); }

        @Override public void write(int b) throws IOException { out.write(b); count++; }

        @Override public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }
    }

    /** Breite/Höhe aus dem PNG-Header oder null. */
    static int[] pngSize(byte[] d) {
        if (d.length < 24 || (d[0] & 0xff) != 0x89 || d[1] != 'P' || d[2] != 'N' || d[3] != 'G') return null;
        int w = ((d[16] & 0xff) << 24) | ((d[17] & 0xff) << 16) | ((d[18] & 0xff) << 8) | (d[19] & 0xff);
        int h = ((d[20] & 0xff) << 24) | ((d[21] & 0xff) << 16) | ((d[22] & 0xff) << 8) | (d[23] & 0xff);
        return w > 0 && h > 0 ? new int[]{w, h} : null;
    }

    public static byte[] readAll(InputStream in) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    public static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }
}
