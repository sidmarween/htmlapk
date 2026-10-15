package de.htmlapk.builder.core;

import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Liest die Webdateien des Nutzers ein und prüft Eingaben. Reines Java. */
public final class WebProject {

    public final Map<String, byte[]> files;
    public final String startPage;

    private WebProject(Map<String, byte[]> files, String startPage) {
        this.files = files;
        this.startPage = startPage;
    }

    public static WebProject fromHtml(byte[] html) {
        Map<String, byte[]> m = new LinkedHashMap<>();
        m.put("index.html", html);
        return new WebProject(m, "index.html");
    }

    /**
     * Liest eine ZIP. Liegt alles in einem einzigen Oberordner (typisch beim Packen eines
     * Ordners), wird dieser entfernt. Startseite: index.html, sonst die einzige HTML-Datei.
     */
    public static WebProject fromZip(InputStream zipStream) throws IOException {
        Map<String, byte[]> raw = new LinkedHashMap<>();
        try (ZipInputStream zin = new ZipInputStream(zipStream)) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName().replace('\\', '/');
                while (name.startsWith("/")) name = name.substring(1);
                if (e.isDirectory() || name.isEmpty()) continue;
                if (name.contains("../") || name.startsWith("__MACOSX/") || name.endsWith(".DS_Store")) continue;
                byte[] buf = ApkAssembler.readAll(new NonClosing(zin));
                raw.put(name, buf);
            }
        }
        if (raw.isEmpty()) throw new IOException("Die ZIP-Datei ist leer");

        String prefix = commonTopFolder(raw);
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> en : raw.entrySet()) {
            files.put(en.getKey().substring(prefix.length()), en.getValue());
        }

        String start = null;
        if (files.containsKey("index.html")) {
            start = "index.html";
        } else if (files.containsKey("index.htm")) {
            start = "index.htm";
        } else {
            for (String n : files.keySet()) {
                String l = n.toLowerCase(Locale.ROOT);
                if (l.endsWith(".html") || l.endsWith(".htm")) {
                    if (start == null || n.length() < start.length()) start = n;
                }
            }
        }
        if (start == null) throw new IOException("Keine HTML-Datei in der ZIP gefunden");
        return new WebProject(files, start);
    }

    private static String commonTopFolder(Map<String, byte[]> files) {
        String top = null;
        for (String n : files.keySet()) {
            int slash = n.indexOf('/');
            if (slash < 0) return "";
            String t = n.substring(0, slash + 1);
            if (top == null) top = t;
            else if (!top.equals(t)) return "";
        }
        return top == null ? "" : top;
    }

    public static boolean isValidPackage(String p) {
        return p != null && p.length() <= 200
                && p.matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+");
    }

    /** "Mein Spiel!" -> "com.htmlapk.meinspiel" */
    public static String suggestPackage(String appName) {
        String n = Normalizer.normalize(appName == null ? "" : appName, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace("ß", "ss")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
        if (n.isEmpty() || !Character.isLetter(n.charAt(0))) n = "app" + n;
        return "com.htmlapk." + n;
    }

    public static String configJson(String start, boolean fullscreen, String orientation, boolean zoom) {
        return "{\n"
                + "  \"start\": " + jsonString(start) + ",\n"
                + "  \"fullscreen\": " + fullscreen + ",\n"
                + "  \"orientation\": " + jsonString(orientation) + ",\n"
                + "  \"zoom\": " + zoom + "\n"
                + "}\n";
    }

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    /** Verhindert, dass readAll den ZipInputStream schließt. */
    private static final class NonClosing extends java.io.FilterInputStream {
        NonClosing(InputStream in) { super(in); }
        @Override public void close() {}
    }
}
