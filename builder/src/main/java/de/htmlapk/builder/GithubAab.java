package de.htmlapk.builder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import de.htmlapk.builder.core.ApkAssembler;

/**
 * Lässt eine APK auf GitHub Actions in ein AAB umwandeln (Workflow .github/workflows/aab.yml).
 * Ablauf: temporäres Release anlegen → app.apk hochladen → Workflow starten → auf
 * app-unsigned.aab warten → herunterladen → Release und Tag wieder löschen.
 * Der Signaturschlüssel verlässt dabei nie das Handy.
 */
public final class GithubAab {

    public interface Progress {
        void update(String text);
    }

    private static final String API = "https://api.github.com";
    private static final String WORKFLOW = "aab.yml";
    private static final long TIMEOUT_MS = 12 * 60 * 1000;

    private final String repo;
    private final String token;

    public GithubAab(String repo, String token) {
        this.repo = repo.trim();
        this.token = token.trim();
    }

    public static boolean isValidRepo(String r) {
        return r != null && r.trim().matches("[A-Za-z0-9-]+/[A-Za-z0-9._-]+");
    }

    /** Prüft Token, Repository und ob der Workflow vorhanden ist. Wirft eine verständliche Meldung. */
    public void check() throws IOException {
        call("GET", "/repos/" + repo, null);
        try {
            call("GET", "/repos/" + repo + "/actions/workflows/" + WORKFLOW, null);
        } catch (IOException e) {
            throw new IOException("Im Repository fehlt der Workflow .github/workflows/" + WORKFLOW
                    + " (und scripts/apk2aab.sh). Kopiere beide aus github.com/sidmarween/htmlapk.");
        }
    }

    /** Wandelt apk in ein unsigniertes AAB um (Datei out). */
    public void convert(File apk, File out, Progress progress) throws Exception {
        String tag = "aab-" + Long.toString(System.currentTimeMillis(), 36)
                + "-" + Integer.toString(new SecureRandom().nextInt(1 << 20), 36);
        progress.update("Verbinde mit GitHub …");
        JSONObject repoInfo = call("GET", "/repos/" + repo, null);
        String branch = repoInfo.optString("default_branch", "main");

        JSONObject rel = new JSONObject();
        rel.put("tag_name", tag);
        rel.put("target_commitish", branch);
        rel.put("name", "AAB " + tag + " (temporär)");
        rel.put("body", "Temporär vom HTML APK Builder angelegt – wird automatisch wieder gelöscht.");
        rel.put("prerelease", true);
        JSONObject release = call("POST", "/repos/" + repo + "/releases", rel);
        long releaseId = release.getLong("id");
        try {
            progress.update("Lade APK zu GitHub hoch …");
            upload(release.getString("upload_url"), "app.apk", apk);

            progress.update("Starte Umwandlung auf GitHub …");
            JSONObject dispatch = new JSONObject();
            dispatch.put("ref", branch);
            JSONObject inputs = new JSONObject();
            inputs.put("tag", tag);
            dispatch.put("inputs", inputs);
            call("POST", "/repos/" + repo + "/actions/workflows/" + WORKFLOW + "/dispatches", dispatch);

            long start = System.currentTimeMillis();
            while (true) {
                long secs = (System.currentTimeMillis() - start) / 1000;
                progress.update("GitHub wandelt in AAB um … " + secs + " s\n(dauert meist 1–2 Minuten)");
                Thread.sleep(6000);
                JSONArray assets = callArray("/repos/" + repo + "/releases/" + releaseId + "/assets");
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    String name = a.getString("name");
                    if (name.equals("app-unsigned.aab")) {
                        progress.update("Lade AAB herunter …");
                        download(a.getLong("id"), out);
                        return;
                    }
                    if (name.equals("error.txt")) {
                        File err = new File(out.getPath() + ".err");
                        download(a.getLong("id"), err);
                        String msg = new String(ApkAssembler.readAll(new FileInputStream(err)),
                                StandardCharsets.UTF_8);
                        //noinspection ResultOfMethodCallIgnored
                        err.delete();
                        throw new IOException(msg.trim());
                    }
                }
                if (System.currentTimeMillis() - start > TIMEOUT_MS) {
                    throw new IOException("GitHub hat nach 12 Minuten nicht geantwortet. "
                            + "Schau im Repository unter „Actions“ nach dem Lauf „APK zu AAB“.");
                }
            }
        } finally {
            // Aufräumen, auch bei Fehlern
            try {
                call("DELETE", "/repos/" + repo + "/releases/" + releaseId, null);
            } catch (IOException ignored) {
            }
            try {
                call("DELETE", "/repos/" + repo + "/git/refs/tags/" + tag, null);
            } catch (IOException ignored) {
            }
        }
    }

    // ------------------------------------------------------------------- HTTP

    private HttpURLConnection open(String method, String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        c.setRequestProperty("User-Agent", "HTML-APK-Builder");
        return c;
    }

    private String exchange(String method, String path, JSONObject body) throws IOException {
        HttpURLConnection c = open(method, path.startsWith("http") ? path : API + path);
        try {
            if (body != null) {
                byte[] b = body.toString().getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setFixedLengthStreamingMode(b.length);
                try (OutputStream o = c.getOutputStream()) {
                    o.write(b);
                }
            }
            int code = c.getResponseCode();
            if (code >= 400) throw httpError(code, c);
            if (code == 204) return "";
            try (InputStream in = c.getInputStream()) {
                return new String(ApkAssembler.readAll(in), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }

    private JSONObject call(String method, String path, JSONObject body) throws IOException {
        String s = exchange(method, path, body);
        try {
            return s.isEmpty() ? new JSONObject() : new JSONObject(s);
        } catch (org.json.JSONException e) {
            throw new IOException("Unerwartete Antwort von GitHub");
        }
    }

    private JSONArray callArray(String path) throws IOException {
        try {
            return new JSONArray(exchange("GET", path, null));
        } catch (org.json.JSONException e) {
            throw new IOException("Unerwartete Antwort von GitHub");
        }
    }

    private void upload(String uploadUrlTemplate, String name, File file) throws IOException {
        String url = uploadUrlTemplate.replaceAll("\\{.*\\}$", "")
                + "?name=" + URLEncoder.encode(name, "UTF-8");
        HttpURLConnection c = open("POST", url);
        try {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/octet-stream");
            c.setFixedLengthStreamingMode(file.length());
            c.setReadTimeout(300000);
            try (InputStream in = new FileInputStream(file); OutputStream o = c.getOutputStream()) {
                ApkAssembler.copy(in, o);
            }
            int code = c.getResponseCode();
            if (code >= 400) throw httpError(code, c);
        } finally {
            c.disconnect();
        }
    }

    /** Assets leiten auf einen Speicher-Server um; dorthin ohne GitHub-Token weiterladen. */
    private void download(long assetId, File out) throws IOException {
        HttpURLConnection c = open("GET", API + "/repos/" + repo + "/releases/assets/" + assetId);
        c.setRequestProperty("Accept", "application/octet-stream");
        c.setInstanceFollowRedirects(false);
        InputStream body;
        int code = c.getResponseCode();
        HttpURLConnection target = c;
        if (code == 301 || code == 302 || code == 307 || code == 308) {
            String location = c.getHeaderField("Location");
            c.disconnect();
            target = (HttpURLConnection) new URL(location).openConnection();
            target.setConnectTimeout(20000);
            target.setReadTimeout(120000);
            code = target.getResponseCode();
        }
        if (code >= 400) throw httpError(code, target);
        body = target.getInputStream();
        try (InputStream in = body; OutputStream o = new FileOutputStream(out)) {
            ApkAssembler.copy(in, o);
        } finally {
            target.disconnect();
        }
    }

    private IOException httpError(int code, HttpURLConnection c) {
        String detail = "";
        try (InputStream e = c.getErrorStream()) {
            if (e != null) {
                JSONObject o = new JSONObject(new String(ApkAssembler.readAll(e), StandardCharsets.UTF_8));
                detail = o.optString("message", "");
            }
        } catch (Exception ignored) {
        }
        switch (code) {
            case 401:
                return new IOException("GitHub-Token ungültig oder abgelaufen");
            case 403:
                return new IOException("Kein Zugriff (" + detail + "). Der Token braucht für „" + repo
                        + "“ die Rechte Contents und Actions jeweils „Read and write“.");
            case 404:
                return new IOException("Nicht gefunden: Repository „" + repo + "“ existiert nicht "
                        + "oder der Token hat keinen Zugriff darauf.");
            case 422:
                return new IOException("GitHub hat die Anfrage abgelehnt: " + detail);
            default:
                return new IOException("GitHub-Fehler " + code + (detail.isEmpty() ? "" : ": " + detail));
        }
    }
}
