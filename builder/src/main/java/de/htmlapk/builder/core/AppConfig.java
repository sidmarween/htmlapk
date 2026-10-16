package de.htmlapk.builder.core;

/**
 * Alle Laufzeit-Einstellungen einer erzeugten App. Wird im Projekt gespeichert und als
 * assets/htmlapk.json in die APK geschrieben (gelesen von der Vorlage, de.htmlapk.runtime).
 * Reines Java, damit es sich ohne Android testen lässt.
 */
public final class AppConfig {

    // Anzeige
    public boolean fullscreen;
    public boolean titleBar;
    public String titleBarColor = "#212121";
    public boolean keepScreenOn;
    public boolean cutout;
    public boolean desktopMode;
    public boolean watermark;
    public boolean zoom;
    public String orientation = "auto";

    // Splash-Screen
    public boolean splashEnabled;
    public boolean splashCustomImage;
    public String splashBg = "#0F1318";
    public String splashLoader = "circle";      // circle | bar | none
    public String splashLoaderColor = "#00E5CC";
    public int splashIconSize = 180;            // dp
    public int splashIconRadius = 16;           // dp
    public int splashLoaderSize = 48;           // dp
    public int splashSpacing = 28;              // dp
    public int splashDuration = 2000;           // ms
    public int splashThickness = 4;             // dp
    public int splashFade = 400;                // ms

    // WebView
    public boolean pullToRefresh;
    public boolean offlinePage;
    public boolean progressBar;
    public boolean backNavigation = true;
    public boolean exitConfirm;
    public boolean javascript = true;
    public boolean domStorage = true;
    public boolean wideViewport = true;
    public boolean overview = true;
    public String cacheMode = "default";        // default | cache_else_network | no_cache | cache_only
    public String mixedContent = "compat";      // compat | always | never

    /** Inhalt von assets/htmlapk.json. start = Startdatei in www/ oder eine URL. */
    public String toJson(String start) {
        StringBuilder sb = new StringBuilder("{\n");
        kv(sb, 1, "start", start, true);
        kv(sb, 1, "fullscreen", fullscreen, true);
        kv(sb, 1, "orientation", orientation, true);
        kv(sb, 1, "zoom", zoom, true);
        kv(sb, 1, "keepScreenOn", keepScreenOn, true);
        kv(sb, 1, "titleBar", titleBar, true);
        kv(sb, 1, "titleBarColor", titleBarColor, true);
        kv(sb, 1, "cutout", cutout, true);
        kv(sb, 1, "desktopMode", desktopMode, true);
        kv(sb, 1, "watermark", watermark, true);

        sb.append("  \"splash\": {\n");
        kv(sb, 2, "enabled", splashEnabled, true);
        kv(sb, 2, "custom", splashCustomImage, true);
        kv(sb, 2, "bg", splashBg, true);
        kv(sb, 2, "loader", splashLoader, true);
        kv(sb, 2, "loaderColor", splashLoaderColor, true);
        kv(sb, 2, "iconSize", splashIconSize, true);
        kv(sb, 2, "iconRadius", splashIconRadius, true);
        kv(sb, 2, "loaderSize", splashLoaderSize, true);
        kv(sb, 2, "spacing", splashSpacing, true);
        kv(sb, 2, "duration", splashDuration, true);
        kv(sb, 2, "thickness", splashThickness, true);
        kv(sb, 2, "fade", splashFade, false);
        sb.append("  },\n");

        sb.append("  \"web\": {\n");
        kv(sb, 2, "pullToRefresh", pullToRefresh, true);
        kv(sb, 2, "offlinePage", offlinePage, true);
        kv(sb, 2, "progressBar", progressBar, true);
        kv(sb, 2, "backNavigation", backNavigation, true);
        kv(sb, 2, "exitConfirm", exitConfirm, true);
        kv(sb, 2, "javascript", javascript, true);
        kv(sb, 2, "domStorage", domStorage, true);
        kv(sb, 2, "wideViewport", wideViewport, true);
        kv(sb, 2, "overview", overview, true);
        kv(sb, 2, "cacheMode", cacheMode, true);
        kv(sb, 2, "mixedContent", mixedContent, false);
        sb.append("  }\n");
        return sb.append("}\n").toString();
    }

    /** Akzeptiert #RGB, #RRGGBB und #AARRGGBB; liefert normalisiert "#RRGGBB"/"#AARRGGBB" oder null. */
    public static String normalizeColor(String s) {
        if (s == null) return null;
        String c = s.trim();
        if (!c.startsWith("#")) c = "#" + c;
        if (!c.matches("#[0-9a-fA-F]{3}|#[0-9a-fA-F]{6}|#[0-9a-fA-F]{8}")) return null;
        if (c.length() == 4) {
            c = "#" + c.charAt(1) + c.charAt(1) + c.charAt(2) + c.charAt(2) + c.charAt(3) + c.charAt(3);
        }
        return c.toUpperCase(java.util.Locale.ROOT);
    }

    private static void kv(StringBuilder sb, int indent, String key, Object value, boolean comma) {
        for (int i = 0; i < indent; i++) sb.append("  ");
        sb.append('"').append(key).append("\": ");
        if (value instanceof String) sb.append(quote((String) value));
        else sb.append(value);
        if (comma) sb.append(',');
        sb.append('\n');
    }

    static String quote(String s) {
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
}
