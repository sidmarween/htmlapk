package de.htmlapk.builder.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Eigener Signaturschlüssel des Builders (RSA 2048 + selbstsigniertes Zertifikat),
 * gespeichert als PKCS12-Datei. Derselbe Schlüssel muss für Updates einer App
 * wiederverwendet werden, deshalb wird er einmal erzeugt und dann behalten.
 *
 * Das Zertifikat wird von Hand als DER kodiert, weil Android keine öffentliche
 * API dafür hat und wir keine BouncyCastle-Abhängigkeit wollen.
 */
public final class SigningKey {

    public static final String ALIAS = "htmlapk";
    public static final char[] PASSWORD = "htmlapk".toCharArray();

    public final PrivateKey privateKey;
    public final X509Certificate certificate;

    private SigningKey(PrivateKey key, X509Certificate cert) {
        this.privateKey = key;
        this.certificate = cert;
    }

    /** Lädt den Schlüssel aus der Datei oder erzeugt ihn beim ersten Aufruf. */
    public static SigningKey loadOrCreate(File p12, String commonName) throws IOException, GeneralSecurityException {
        if (p12.exists() && p12.length() > 0) {
            try (InputStream in = new FileInputStream(p12)) {
                return load(in);
            }
        }
        SigningKey k = generate(commonName);
        File tmp = new File(p12.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            k.store(out);
        }
        if (!tmp.renameTo(p12)) throw new IOException("Schlüssel konnte nicht gespeichert werden");
        return k;
    }

    public static SigningKey load(InputStream in) throws IOException, GeneralSecurityException {
        return load(in, PASSWORD, null);
    }

    /** PKCS12 mit eigenem Passwort; alias == null: erster Eintrag mit privatem Schlüssel. */
    public static SigningKey load(InputStream in, char[] password, String alias)
            throws IOException, GeneralSecurityException {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(in, password);
        if (alias == null || !ks.containsAlias(alias)) {
            alias = null;
            if (ks.containsAlias(ALIAS) && ks.isKeyEntry(ALIAS)) alias = ALIAS;
            java.util.Enumeration<String> all = ks.aliases();
            while (alias == null && all.hasMoreElements()) {
                String a = all.nextElement();
                if (ks.isKeyEntry(a)) alias = a;
            }
            if (alias == null) throw new GeneralSecurityException("Kein Schlüssel in der Datei");
        }
        PrivateKey key = (PrivateKey) ks.getKey(alias, password);
        X509Certificate cert = (X509Certificate) ks.getCertificate(alias);
        if (key == null || cert == null) throw new GeneralSecurityException("Kein Schlüssel in der Datei");
        return new SigningKey(key, cert);
    }

    public void store(OutputStream out) throws IOException, GeneralSecurityException {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry(ALIAS, privateKey, PASSWORD, new Certificate[]{certificate});
        ks.store(out, PASSWORD);
    }

    public static SigningKey generate(String commonName) throws GeneralSecurityException {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048, new SecureRandom());
        KeyPair kp = gen.generateKeyPair();

        long now = System.currentTimeMillis();
        Date from = new Date(now - 24L * 3600 * 1000);
        Date to = new Date(now + 30L * 365 * 24 * 3600 * 1000); // ~30 Jahre (Play verlangt > 2033)
        byte[] serial = new byte[16];
        new SecureRandom().nextBytes(serial);
        serial[0] &= 0x7f;

        byte[] sigAlg = seq(oid(1, 2, 840, 113549, 1, 1, 11), new byte[]{0x05, 0x00}); // sha256WithRSA
        byte[] name = seq(set(seq(oid(2, 5, 4, 3), tlv(0x0c, commonName.getBytes(StandardCharsets.UTF_8)))));
        byte[] tbs = seq(
                tlv(0xa0, integer(BigInteger.valueOf(2))),           // Version 3
                integer(new BigInteger(serial)),
                sigAlg,
                name,
                seq(time(from), time(to)),
                name,
                kp.getPublic().getEncoded());                        // SubjectPublicKeyInfo

        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(kp.getPrivate());
        s.update(tbs);
        byte[] sig = s.sign();
        byte[] bitString = new byte[sig.length + 1];
        System.arraycopy(sig, 0, bitString, 1, sig.length);

        byte[] der = seq(tbs, sigAlg, tlv(0x03, bitString));
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
        cert.verify(kp.getPublic());
        return new SigningKey(kp.getPrivate(), cert);
    }

    // ------------------------------------------------------------ DER-Helfer

    private static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(tag);
        int len = value.length;
        if (len < 0x80) {
            o.write(len);
        } else if (len < 0x100) {
            o.write(0x81);
            o.write(len);
        } else if (len < 0x10000) {
            o.write(0x82);
            o.write(len >>> 8);
            o.write(len & 0xff);
        } else {
            o.write(0x83);
            o.write(len >>> 16);
            o.write((len >>> 8) & 0xff);
            o.write(len & 0xff);
        }
        o.write(value, 0, value.length);
        return o.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }

    private static byte[] seq(byte[]... parts) { return tlv(0x30, concat(parts)); }

    private static byte[] set(byte[]... parts) { return tlv(0x31, concat(parts)); }

    private static byte[] integer(BigInteger v) { return tlv(0x02, v.toByteArray()); }

    private static byte[] oid(int... arcs) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(arcs[0] * 40 + arcs[1]);
        for (int i = 2; i < arcs.length; i++) {
            int v = arcs[i];
            byte[] tmp = new byte[5];
            int n = 0;
            do {
                tmp[n++] = (byte) (v & 0x7f);
                v >>>= 7;
            } while (v != 0);
            for (int k = n - 1; k >= 0; k--) o.write(tmp[k] | (k > 0 ? 0x80 : 0));
        }
        return tlv(0x06, o.toByteArray());
    }

    private static byte[] time(Date d) {
        SimpleDateFormat utc = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        utc.setTimeZone(TimeZone.getTimeZone("UTC"));
        SimpleDateFormat gen = new SimpleDateFormat("yyyyMMddHHmmss'Z'", Locale.US);
        gen.setTimeZone(TimeZone.getTimeZone("UTC"));
        int year = Integer.parseInt(new SimpleDateFormat("yyyy", Locale.US).format(d));
        return year < 2050
                ? tlv(0x17, utc.format(d).getBytes(StandardCharsets.US_ASCII))
                : tlv(0x18, gen.format(d).getBytes(StandardCharsets.US_ASCII));
    }
}
