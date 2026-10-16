package de.htmlapk.builder.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Liest aus einer beliebigen fertigen APK (z. B. aus einem anderen HTML-zu-APK-Builder)
 * Paketname, Version, App-Name, Icon und die Webdateien heraus. Reines Java.
 */
public final class ApkInspector {

    public static final class Info {
        public String packageName;
        public int versionCode;
        public String versionName;
        public String label;
        /** Icon als PNG/WebP-Bytes in der höchsten verfügbaren Auflösung, oder null. */
        public byte[] icon;
        /** Ordner in der APK, aus dem die Webdateien stammen, z. B. "assets/www/". */
        public String webRoot;
        /** Webdateien relativ zu webRoot. */
        public Map<String, byte[]> webFiles = new LinkedHashMap<>();
        /** Einstellungsdateien bekannter Builder (Pfad in der APK → Inhalt). */
        public Map<String, byte[]> builderConfigs = new LinkedHashMap<>();
    }

    private static final int TYPE_REFERENCE = 0x01;
    private static final int TYPE_STRING = 0x03;
    private static final int ATTR_DRAWABLE = 0x01010199;

    private ApkInspector() {}

    public static boolean looksLikeApk(ZipFile zip) {
        return zip.getEntry("AndroidManifest.xml") != null && zip.getEntry("classes.dex") != null;
    }

    public static Info inspect(File apk) throws IOException {
        try (ZipFile zip = new ZipFile(apk)) {
            if (!looksLikeApk(zip)) throw new IOException("Keine APK-Datei");
            Info info = new Info();
            byte[] manifest = read(zip, "AndroidManifest.xml");
            AxmlPatcher.ManifestInfo m = AxmlPatcher.readManifestInfo(manifest);
            info.packageName = m.packageName;
            info.versionCode = m.versionCode;
            info.versionName = m.versionName;
            info.label = m.label;

            ZipEntry arscEntry = zip.getEntry("resources.arsc");
            Arsc arsc = arscEntry != null ? new Arsc(read(zip, "resources.arsc")) : null;
            if (info.label == null && m.labelRes != 0 && arsc != null) {
                info.label = arsc.bestString(m.labelRes);
            }
            if (arsc != null && m.iconRes != 0) {
                info.icon = resolveIcon(zip, arsc, m.iconRes, 0);
            }
            if (info.icon == null) info.icon = guessIcon(zip);

            collectWeb(zip, info);
            return info;
        }
    }

    // ---------------------------------------------------------------- Webdateien

    private static void collectWeb(ZipFile zip, Info info) throws IOException {
        List<String> names = new ArrayList<>();
        Enumeration<? extends ZipEntry> en = zip.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            if (!e.isDirectory() && e.getName().startsWith("assets/")) names.add(e.getName());
        }
        // Bekannte Ordner zuerst (Cordova/viele Builder: www, Capacitor: public)
        String root = null;
        for (String cand : new String[]{"assets/www/", "assets/public/", "assets/web/", "assets/html/", "assets/"}) {
            if (names.contains(cand + "index.html") || names.contains(cand + "index.htm")) {
                root = cand;
                break;
            }
        }
        if (root == null) {
            String best = null;
            for (String n : names) {
                String l = n.toLowerCase(Locale.ROOT);
                if ((l.endsWith(".html") || l.endsWith(".htm")) && (best == null || n.length() < best.length())) {
                    best = n;
                }
            }
            if (best == null) throw new IOException("In der APK wurden keine HTML-Dateien gefunden");
            root = best.substring(0, best.lastIndexOf('/') + 1);
        }
        info.webRoot = root;
        for (String n : names) {
            if (!n.startsWith(root)) continue;
            String rel = n.substring(root.length());
            if (rel.startsWith("dexopt/") || rel.equals("htmlapk.json") || rel.equals("splash.png")
                    && root.equals("assets/")) {
                continue;
            }
            info.webFiles.put(rel, read(zip, n));
        }
        // Einstellungen bekannter Builder erkennen und nicht als Webdatei übernehmen
        byte[] own = zip.getEntry("assets/htmlapk.json") != null ? read(zip, "assets/htmlapk.json") : null;
        if (own != null) info.builderConfigs.put("assets/htmlapk.json", own);
        byte[] cfg = info.webFiles.get("config.json");
        if (cfg != null) {
            String s = new String(cfg, StandardCharsets.UTF_8);
            if (s.contains("\"splashLoaderStyle\"") || s.contains("\"admobEnabled\"")
                    || s.contains("\"backHistoryNavigation\"")) {
                info.builderConfigs.put(root + "config.json", cfg);
                info.webFiles.remove("config.json");
                info.webFiles.remove("runtime.cache");
            }
        }
        if (info.webFiles.isEmpty()) throw new IOException("In der APK wurden keine Webdateien gefunden");
    }

    /** Packt die Webdateien als ZIP (Format, das der Builder als Inhalt speichert). */
    public static byte[] webZip(Info info) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            for (Map.Entry<String, byte[]> f : info.webFiles.entrySet()) {
                z.putNextEntry(new ZipEntry(f.getKey()));
                z.write(f.getValue());
                z.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    // --------------------------------------------------------------------- Icon

    private static byte[] resolveIcon(ZipFile zip, Arsc arsc, int resId, int depth) throws IOException {
        List<Arsc.Value> values = arsc.values(resId);
        Arsc.Value bestBitmap = null;
        Arsc.Value xml = null;
        for (Arsc.Value v : values) {
            if (v.type != TYPE_STRING || v.string == null) continue;
            String p = v.string.toLowerCase(Locale.ROOT);
            if (p.endsWith(".png") || p.endsWith(".webp") || p.endsWith(".jpg")) {
                if (bestBitmap == null || v.density() > bestBitmap.density()) bestBitmap = v;
            } else if (p.endsWith(".xml")) {
                xml = v;
            }
        }
        if (bestBitmap != null && zip.getEntry(bestBitmap.string) != null) return read(zip, bestBitmap.string);
        if (xml != null && depth < 2 && zip.getEntry(xml.string) != null) {
            // Adaptives Icon: Vordergrund-Bild verwenden
            int fg = AxmlPatcher.findReference(read(zip, xml.string), "foreground", ATTR_DRAWABLE);
            if (fg != 0) return resolveIcon(zip, arsc, fg, depth + 1);
        }
        return null;
    }

    /** Fallback bei fehlender/ungewöhnlicher Ressourcentabelle: größtes ic_launcher-Bild. */
    private static byte[] guessIcon(ZipFile zip) throws IOException {
        ZipEntry best = null;
        Enumeration<? extends ZipEntry> en = zip.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            String n = e.getName().toLowerCase(Locale.ROOT);
            if (!n.startsWith("res/") || !n.contains("ic_launcher") || n.contains("background")
                    || n.contains("foreground") || n.contains("round")) continue;
            if (!(n.endsWith(".png") || n.endsWith(".webp"))) continue;
            if (best == null || e.getSize() > best.getSize()) best = e;
        }
        return best != null ? read(zip, best.getName()) : null;
    }

    private static byte[] read(ZipFile zip, String name) throws IOException {
        return ApkAssembler.readAll(zip.getInputStream(zip.getEntry(name)));
    }

    // -------------------------------------------------------------- resources.arsc

    /** Minimaler Leser für resources.arsc: Werte einer Ressourcen-ID über alle Konfigurationen. */
    static final class Arsc {
        static final class Value {
            int type;
            int data;
            int densityRaw;
            String string;

            int density() {
                if (densityRaw == 0) return 160;
                if (densityRaw >= 0xFFFE) return 1;   // any/none: nur als Notlösung
                return densityRaw;
            }
        }

        private final ByteBuffer b;
        private final List<String> globalStrings;
        private final int tableEnd;

        Arsc(byte[] data) throws IOException {
            b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            if ((b.getShort(0) & 0xffff) != 0x0002) throw new IOException("Keine resources.arsc");
            tableEnd = Math.min(b.getInt(4), data.length);
            int pos = b.getShort(2) & 0xffff;
            List<String> pool = null;
            if ((b.getShort(pos) & 0xffff) == 0x0001) pool = readPool(b, pos);
            globalStrings = pool != null ? pool : new ArrayList<>();
        }

        String bestString(int resId) {
            String fallback = null;
            for (Value v : values(resId)) {
                if (v.type != TYPE_STRING) continue;
                if (v.densityRaw == 0 && v.string != null) fallback = fallback == null ? v.string : fallback;
            }
            if (fallback != null) return fallback;
            for (Value v : values(resId)) if (v.type == TYPE_STRING && v.string != null) return v.string;
            return null;
        }

        List<Value> values(int resId) {
            List<Value> out = new ArrayList<>();
            int pkgId = resId >>> 24, typeId = (resId >> 16) & 0xff, entryId = resId & 0xffff;
            int pos = b.getShort(2) & 0xffff;
            while (pos + 8 <= tableEnd) {
                int type = b.getShort(pos) & 0xffff;
                int size = b.getInt(pos + 4);
                if (size <= 0) break;
                if (type == 0x0200 && (b.getInt(pos + 8) & 0xff) == pkgId) {
                    scanPackage(pos, size, typeId, entryId, out);
                }
                pos += size;
            }
            return out;
        }

        private void scanPackage(int pkg, int pkgSize, int typeId, int entryId, List<Value> out) {
            int pos = pkg + (b.getShort(pkg + 2) & 0xffff);
            int end = pkg + pkgSize;
            while (pos + 8 <= end) {
                int type = b.getShort(pos) & 0xffff;
                int headerSize = b.getShort(pos + 2) & 0xffff;
                int size = b.getInt(pos + 4);
                if (size <= 0) break;
                if (type == 0x0201 && (b.get(pos + 8) & 0xff) == typeId) {
                    Value v = readEntry(pos, headerSize, entryId);
                    if (v != null) out.add(v);
                }
                pos += size;
            }
        }

        private Value readEntry(int chunk, int headerSize, int entryId) {
            int flags = b.get(chunk + 9) & 0xff;
            int entryCount = b.getInt(chunk + 12);
            int entriesStart = chunk + b.getInt(chunk + 16);
            int config = chunk + 20;
            int configSize = b.getInt(config);
            int density = configSize >= 16 ? b.getShort(config + 14) & 0xffff : 0;
            int offsets = chunk + headerSize;
            boolean sparse = (flags & 0x01) != 0;
            boolean offset16 = (flags & 0x02) != 0;
            int offset = -1;
            if (sparse) {
                for (int i = 0; i < entryCount; i++) {
                    int idx = b.getShort(offsets + i * 4) & 0xffff;
                    if (idx == entryId) {
                        offset = (b.getShort(offsets + i * 4 + 2) & 0xffff) * 4;
                        break;
                    }
                }
            } else if (entryId < entryCount) {
                if (offset16) {
                    int o = b.getShort(offsets + entryId * 2) & 0xffff;
                    offset = o == 0xffff ? -1 : o * 4;
                } else {
                    int o = b.getInt(offsets + entryId * 4);
                    offset = o == -1 ? -1 : o;
                }
            }
            if (offset < 0) return null;
            int e = entriesStart + offset;
            int eFlags = b.getShort(e + 2) & 0xffff;
            Value v = new Value();
            v.densityRaw = density;
            if ((eFlags & 0x0008) != 0) {          // kompakter Eintrag (neueres aapt2)
                v.type = (eFlags >> 8) & 0xff;
                v.data = b.getInt(e + 4);
            } else {
                if ((eFlags & 0x0001) != 0) return null;   // komplexer Eintrag (Style o. Ä.)
                int eSize = b.getShort(e) & 0xffff;
                int val = e + eSize;
                v.type = b.get(val + 3) & 0xff;
                v.data = b.getInt(val + 4);
            }
            if (v.type == TYPE_STRING && v.data >= 0 && v.data < globalStrings.size()) {
                v.string = globalStrings.get(v.data);
            }
            return v;
        }

        private static List<String> readPool(ByteBuffer b, int sp) {
            int count = b.getInt(sp + 8);
            int flags = b.getInt(sp + 16);
            int stringsStart = b.getInt(sp + 20);
            int header = b.getShort(sp + 2) & 0xffff;
            boolean utf8 = (flags & (1 << 8)) != 0;
            List<String> out = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int off = sp + stringsStart + b.getInt(sp + header + i * 4);
                out.add(utf8 ? utf8At(b, off) : utf16At(b, off));
            }
            return out;
        }

        private static String utf8At(ByteBuffer b, int off) {
            int[] o = {off};
            len8(b, o);
            int n = len8(b, o);
            byte[] d = new byte[n];
            for (int i = 0; i < n; i++) d[i] = b.get(o[0] + i);
            return new String(d, StandardCharsets.UTF_8);
        }

        private static int len8(ByteBuffer b, int[] o) {
            int len = b.get(o[0]++) & 0xff;
            if ((len & 0x80) != 0) len = ((len & 0x7f) << 8) | (b.get(o[0]++) & 0xff);
            return len;
        }

        private static String utf16At(ByteBuffer b, int off) {
            int len = b.getShort(off) & 0xffff;
            off += 2;
            if ((len & 0x8000) != 0) {
                len = ((len & 0x7fff) << 16) | (b.getShort(off) & 0xffff);
                off += 2;
            }
            char[] c = new char[len];
            for (int i = 0; i < len; i++) c[i] = b.getChar(off + i * 2);
            return new String(c);
        }
    }
}
