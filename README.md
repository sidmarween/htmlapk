# HTML APK Builder

Eine Android-App, die direkt auf dem Handy aus HTML-Dateien (oder einer Website-URL)
eine installierbare APK baut – ohne PC, ohne Android Studio, ohne Internet.

## So funktioniert's (Template-Prinzip)

```
template/   WebView-App ("Hülle"). Wird einmal gebaut und als assets/template.apk
            in die Builder-App gepackt.
builder/    Die Builder-App. Sie nimmt die Vorlage und
              1. ersetzt Paketname, App-Name und Version im binären AndroidManifest  (AxmlPatcher)
              2. benennt das Paket in resources.arsc um                           (ArscPatcher)
              3. legt deine Dateien nach assets/www und schreibt htmlapk.json     (ApkAssembler)
              4. tauscht die Icon-PNGs gegen dein Bild aus
              5. richtet unkomprimierte Einträge aus (wie zipalign)
              6. signiert mit v1 + v2 + v3                                        (apksig)
```

Die Kernlogik in `builder/src/main/java/de/htmlapk/builder/core/` ist reines Java
und wird bei jedem Build mit der echten Vorlage getestet.

## App bauen (ohne PC, über GitHub)

1. Der Workflow `.github/workflows/build.yml` startet bei jedem Push (Tab **Actions**).
2. Mit dem Secret `RELEASE_KEY_PASSWORD` wird der Builder mit dem Release-Schlüssel
   `signing/release.p12` signiert und unter **Releases → latest** als `HtmlApkBuilder.apk`
   veröffentlicht.
3. Ohne dieses Secret (z. B. in Kopien und Forks) wird nur getestet und mit einem einmaligen
   Test-Schlüssel signiert – es wird nichts veröffentlicht.

`signing/release.p12` ist mit einem starken Passwort (AES-256) geschützt und ohne das
Secret wertlos. Das Passwort gehört nur in das Secret und in einen Passwort-Manager.

## AAB für Google Play

Der Builder lädt die APK in ein temporäres Release des eigenen Repositorys, der Workflow
`.github/workflows/aab.yml` wandelt sie mit `scripts/apk2aab.sh` (aapt2 + bundletool) um,
und der Builder signiert das AAB anschließend **auf dem Handy**. Jeder Nutzer braucht dafür
ein eigenes Repository: auf github.com „Use this template“ (oder Fork; dann im Tab „Actions“
die Workflows aktivieren) und einen Fine-grained Token mit Contents + Actions „Read and write“.

## Benutzung

- **Inhalt:** ZIP mit deinen Webdateien (eine `index.html` sollte drin sein; ein
  einzelner Oberordner wird automatisch entfernt), eine einzelne HTML-Datei oder eine URL.
- **Paketname:** eindeutige ID, z. B. `com.meinname.meinspiel`. Für Updates gleich lassen
  und die **Versionsnummer** erhöhen.
- **Icon:** beliebiges Bild, wird quadratisch zugeschnitten.
- **Optionen:** Vollbild, Zoom, Bildschirmausrichtung.

Die erzeugten Apps laden lokale Dateien über `https://appassets.androidplatform.net/www/…`.
Deshalb funktionieren `fetch()`, ES-Module und `localStorage` wie im normalen Browser.
Links zu anderen Websites öffnen sich im Browser, `<input type="file">` funktioniert.

## Signaturschlüssel – wichtig

Beim ersten Bauen erzeugt der Builder einen eigenen Schlüssel (`signing.p12`, Passwort
`htmlapk`). Alle Apps werden damit signiert. **Sichere ihn über das Menü**: Ohne ihn
lassen sich deine Apps nach einer Neuinstallation des Builders nicht mehr aktualisieren.
Über „Signaturschlüssel importieren“ kannst du ihn zurückspielen.

Der Builder selbst wird mit dem privaten Release-Schlüssel signiert (siehe oben). So kann
niemand eine manipulierte Version als „Update“ über deine installierte unterschieben.

## Grenzen

- Keine Push-Nachrichten, kein Zugriff auf Kamera/Standort aus JavaScript (bräuchte
  zusätzliche Berechtigungen in der Vorlage).
- Für den Play Store besser eine Release-Signatur mit eigenem, gut gesichertem Schlüssel nutzen.
