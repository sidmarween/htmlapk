package de.htmlapk.builder.core;

import com.android.apksig.ApkSigner;

import java.io.File;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;

/**
 * Signiert ein Android App Bundle. Google Play erwartet bei AABs eine klassische
 * JAR-Signatur (wie jarsigner) – also nur das v1-Schema, ohne APK-Signaturblock.
 */
public final class AabSigning {

    private AabSigning() {}

    public static void sign(File unsignedAab, File out, SigningKey key) throws Exception {
        File tmp = new File(out.getPath() + ".tmp");
        signV1(unsignedAab, tmp, key);
        metaInfFirst(tmp, out);
        //noinspection ResultOfMethodCallIgnored
        tmp.delete();
    }

    private static void signV1(File unsignedAab, File out, SigningKey key) throws Exception {
        List<X509Certificate> chain = Collections.singletonList(key.certificate);
        ApkSigner.SignerConfig cfg =
                new ApkSigner.SignerConfig.Builder("CERT", key.privateKey, chain).build();
        new ApkSigner.Builder(Collections.singletonList(cfg))
                .setInputApk(unsignedAab)
                .setOutputApk(out)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(false)
                .setV3SigningEnabled(false)
                .setV4SigningEnabled(false)
                .setMinSdkVersion(24)              // SHA-256 statt SHA-1; ohne Manifest im Wurzelverzeichnis
                .setDebuggableApkPermitted(true)   // Manifest liegt in base/manifest (Protobuf)
                .setCreatedBy("HTML APK Builder")
                .build()
                .sign();
    }

    /**
     * Ordnet die Einträge wie jarsigner: META-INF/MANIFEST.MF zuerst, dann die Signaturdateien,
     * danach alles andere. Die v1-Signatur hängt nicht von der Reihenfolge ab, aber Werkzeuge,
     * die per JarInputStream lesen, erwarten das Manifest am Anfang.
     */
    static void metaInfFirst(File in, File out) throws java.io.IOException {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(in);
             java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(
                     new java.io.FileOutputStream(out))) {
            List<java.util.zip.ZipEntry> first = new java.util.ArrayList<>();
            List<java.util.zip.ZipEntry> meta = new java.util.ArrayList<>();
            List<java.util.zip.ZipEntry> rest = new java.util.ArrayList<>();
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                java.util.zip.ZipEntry e = en.nextElement();
                String n = e.getName().toUpperCase(java.util.Locale.ROOT);
                if (n.equals("META-INF/MANIFEST.MF")) first.add(e);
                else if (n.startsWith("META-INF/") && !n.substring(9).contains("/")) meta.add(e);
                else rest.add(e);
            }
            first.addAll(meta);
            first.addAll(rest);
            for (java.util.zip.ZipEntry e : first) {
                if (e.isDirectory()) continue;
                byte[] data = ApkAssembler.readAll(zip.getInputStream(e));
                java.util.zip.ZipEntry ne = new java.util.zip.ZipEntry(e.getName());
                ne.setTime(e.getTime());
                if (e.getMethod() == java.util.zip.ZipEntry.STORED) {
                    java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                    crc.update(data);
                    ne.setMethod(java.util.zip.ZipEntry.STORED);
                    ne.setSize(data.length);
                    ne.setCompressedSize(data.length);
                    ne.setCrc(crc.getValue());
                } else {
                    ne.setMethod(java.util.zip.ZipEntry.DEFLATED);
                }
                zos.putNextEntry(ne);
                zos.write(data);
                zos.closeEntry();
            }
        }
    }
}
