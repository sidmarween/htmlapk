#!/usr/bin/env bash
# Wandelt eine fertige APK in ein (unsigniertes) Android App Bundle um.
# Aufruf: apk2aab.sh <eingabe.apk> <ausgabe.aab> <pfad/zu/aapt2> <pfad/zu/bundletool.jar>
#
# 1. aapt2 convert: Manifest und Ressourcen vom Binärformat ins Protobuf-Format
# 2. Dateien in die Modul-Struktur von bundletool einsortieren (base/manifest, base/dex, ...)
# 3. bundletool build-bundle
# Signiert wird das AAB anschließend im HTML APK Builder auf dem Handy.
set -euo pipefail

IN=$(readlink -f "$1"); OUT=$(readlink -f "$(dirname "$2")")/$(basename "$2")
AAPT2=$3; BUNDLETOOL=$(readlink -f "$4")
W=$(mktemp -d)
trap 'rm -rf "$W"' EXIT

"$AAPT2" convert --output-format proto -o "$W/proto.apk" "$IN"

mkdir -p "$W/proto" "$W/orig" "$W/base/manifest" "$W/base/dex"
(cd "$W/proto" && unzip -q ../proto.apk)
(cd "$W/orig" && unzip -q "$IN")

mv "$W/proto/AndroidManifest.xml" "$W/base/manifest/AndroidManifest.xml"
mv "$W/proto/resources.pb" "$W/base/resources.pb"
[ -d "$W/proto/res" ] && mv "$W/proto/res" "$W/base/res"

shopt -s nullglob
for f in "$W/orig"/classes*.dex; do cp "$f" "$W/base/dex/"; done
[ -d "$W/orig/assets" ] && cp -r "$W/orig/assets" "$W/base/assets"
[ -d "$W/orig/lib" ] && cp -r "$W/orig/lib" "$W/base/lib"

# Alle übrigen Dateien (außer Signatur, Manifest, Ressourcen, Code) nach root/
(cd "$W/orig" && find . -type f \
    ! -path './META-INF/*' ! -name 'AndroidManifest.xml' ! -name 'resources.arsc' \
    ! -name 'classes*.dex' ! -path './res/*' ! -path './assets/*' ! -path './lib/*' \
    -print0) | while IFS= read -r -d '' f; do
    mkdir -p "$W/base/root/$(dirname "$f")"
    cp "$W/orig/$f" "$W/base/root/$f"
done

(cd "$W/base" && zip -qr ../base.zip .)
java -jar "$BUNDLETOOL" build-bundle --modules="$W/base.zip" --output="$OUT" --overwrite
echo "AAB erstellt: $OUT"
