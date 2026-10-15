package de.htmlapk.builder.core;

import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;

import java.io.File;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;

/** Signiert mit apksig (v1 + v2 + v3). apksig richtet unkomprimierte Einträge dabei aus. */
public final class ApkSigning {

    private ApkSigning() {}

    public static void sign(File unsigned, File out, SigningKey key) throws Exception {
        List<X509Certificate> chain = Collections.singletonList(key.certificate);
        ApkSigner.SignerConfig cfg =
                new ApkSigner.SignerConfig.Builder("CERT", key.privateKey, chain).build();
        new ApkSigner.Builder(Collections.singletonList(cfg))
                .setInputApk(unsigned)
                .setOutputApk(out)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .build()
                .sign();

        ApkVerifier.Result r = new ApkVerifier.Builder(out).build().verify();
        if (!r.isVerified()) {
            StringBuilder sb = new StringBuilder("Signatur ungültig:");
            for (ApkVerifier.IssueWithParams e : r.getErrors()) sb.append("\n").append(e);
            throw new IllegalStateException(sb.toString());
        }
    }
}
