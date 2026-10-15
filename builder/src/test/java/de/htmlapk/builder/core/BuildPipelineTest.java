package de.htmlapk.builder.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.android.apksig.ApkVerifier;

import org.junit.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

/**
 * Durchläuft die komplette Pipeline mit der echten Vorlagen-APK aus :template.
 * Die erzeugte APK landet in build/test-output/sample.apk, damit die CI sie
 * zusätzlich mit aapt2 und apksigner prüfen kann.
 */
public class BuildPipelineTest {

    private static File templateApk() {
        String p = System.getProperty("templateApk");
        assertNotNull("System-Property templateApk fehlt", p);
        File f = new File(p);
        assertTrue("Vorlage fehlt: " + f, f.isFile());
        return f;
    }

    private static File outDir() {
        File d = new File(System.getProperty("testOut", "build/test-output"));
        d.mkdirs();
        return d;
    }

    @Test
    public void fullPipeline() throws Exception {
        File template = templateApk();

        // Webprojekt als ZIP mit Oberordner, wie beim Packen eines Ordners
        ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(zipBytes)) {
            z.putNextEntry(new ZipEntry("meinprojekt/index.html"));
            z.write("<h1>Hallo</h1><script src=\"js/app.js\"></script>".getBytes(StandardCharsets.UTF_8));
            z.putNextEntry(new ZipEntry("meinprojekt/js/app.js"));
            z.write("console.log(1)".getBytes(StandardCharsets.UTF_8));
            z.putNextEntry(new ZipEntry("__MACOSX/meinprojekt/._index.html"));
            z.write(new byte[]{1});
        }
        WebProject web = WebProject.fromZip(new ByteArrayInputStream(zipBytes.toByteArray()));
        assertEquals("index.html", web.startPage);
        assertTrue(web.files.containsKey("js/app.js"));

        ApkAssembler.Input in = new ApkAssembler.Input();
        in.templateApk = template;
        in.manifest.packageName = "com.example.meinspiel";
        in.manifest.appLabel = "Mein Spiel äöü 🎮";
        in.manifest.versionName = "2.3.1";
        in.manifest.versionCode = 42;
        in.webFiles = web.files;
        in.configJson = WebProject.configJson(web.startPage, true, "landscape", false);
        Map<String, int[]> rendered = new LinkedHashMap<>();
        in.icon = (w, h) -> {
            rendered.put(w + "x" + h, new int[]{w, h});
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setColor(Color.MAGENTA);
            g.fillRect(0, 0, w, h);
            g.dispose();
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            ImageIO.write(img, "png", o);
            return o.toByteArray();
        };

        File unsigned = new File(outDir(), "sample-unsigned.apk");
        File signed = new File(outDir(), "sample.apk");
        ApkAssembler.assemble(in, unsigned);

        File p12 = new File(outDir(), "test-key.p12");
        p12.delete();
        SigningKey key = SigningKey.loadOrCreate(p12, "HTML APK Builder");
        SigningKey again = SigningKey.loadOrCreate(p12, "egal");
        assertEquals(key.certificate, again.certificate);

        ApkSigning.sign(unsigned, signed, key);

        ApkVerifier.Result r = new ApkVerifier.Builder(signed).build().verify();
        assertTrue(r.isVerified());
        assertTrue(r.isVerifiedUsingV2Scheme());

        try (ZipFile z = new ZipFile(signed)) {
            byte[] manifest = ApkAssembler.readAll(z.getInputStream(z.getEntry("AndroidManifest.xml")));
            assertEquals("com.example.meinspiel", AxmlPatcher.readPackageName(manifest));
            assertNotNull(z.getEntry("assets/www/index.html"));
            assertNotNull(z.getEntry("assets/www/js/app.js"));
            assertTrue(z.getEntry("assets/www/js/app.js") != null);
            String cfg = new String(ApkAssembler.readAll(z.getInputStream(z.getEntry("assets/htmlapk.json"))),
                    StandardCharsets.UTF_8);
            assertTrue(cfg.contains("\"landscape\""));
            assertEquals(ZipEntry.STORED, z.getEntry("resources.arsc").getMethod());
        }
        assertEquals("Fünf Icon-Dichten erwartet: " + rendered.keySet(), 5, rendered.size());
        assertStoredEntriesAligned(signed);
    }

    /** Android 11+ verlangt 4-Byte-ausgerichtete, unkomprimierte resources.arsc. */
    private static void assertStoredEntriesAligned(File apk) throws Exception {
        byte[] f = java.nio.file.Files.readAllBytes(apk.toPath());
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(f).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int eocd = f.length - 22;
        while (eocd > 0 && b.getInt(eocd) != 0x06054b50) eocd--;
        int entries = b.getShort(eocd + 10) & 0xffff;
        int cd = b.getInt(eocd + 16);
        int checked = 0;
        for (int i = 0; i < entries; i++) {
            int method = b.getShort(cd + 10) & 0xffff;
            int cdName = b.getShort(cd + 28) & 0xffff;
            int cdExtra = b.getShort(cd + 30) & 0xffff;
            int cdComment = b.getShort(cd + 32) & 0xffff;
            int lfh = b.getInt(cd + 42);
            String name = new String(f, cd + 46, cdName, StandardCharsets.UTF_8);
            int data = lfh + 30 + (b.getShort(lfh + 26) & 0xffff) + (b.getShort(lfh + 28) & 0xffff);
            if (method == 0) {
                assertTrue(name + " nicht ausgerichtet (" + data + ")", data % 4 == 0);
                checked++;
            }
            cd += 46 + cdName + cdExtra + cdComment;
        }
        assertTrue("Keine unkomprimierten Einträge geprüft", checked > 0);
    }

    @Test
    public void packageNames() {
        assertTrue(WebProject.isValidPackage("com.example.app"));
        assertTrue(!WebProject.isValidPackage("app"));
        assertTrue(!WebProject.isValidPackage("com.1abc.app"));
        assertEquals("com.htmlapk.meinspiel", WebProject.suggestPackage("Mein Spiel!"));
        assertEquals("com.htmlapk.grossestrasse", WebProject.suggestPackage("Große Straße"));
        assertEquals("com.htmlapk.app2048", WebProject.suggestPackage("2048"));
    }
}
