#!/bin/bash
# c700-keymapd v2 APK build — pure command-line, everything inside workspace.
# All paths passed to Windows binaries converted via cygpath (immune to MSYS_NO_PATHCONV).
set -e
ROOT="/c/Users/weixiao/.pi/workspace/c700-keymap"
A="$ROOT/android"
JH="$A/jdk-17.0.20.1+1"
BT="$A/sdk/build-tools/36.0.0"
PLATFORM="$A/sdk/platforms/android-36/android.jar"
APP="$ROOT/app"
OUT="$APP/build"
VER=1.0.0
W() { cygpath -w "$1"; }

rm -rf "$OUT"
mkdir -p "$OUT/obj" "$OUT/apk"

echo "== daemon dex =="
DAEMON="$ROOT/daemon"
mkdir -p "$DAEMON/build"
(cd "$DAEMON" && "$JH/bin/javac" -source 11 -target 11 -nowarn -encoding UTF-8 \
  -classpath "$(W "$PLATFORM")" -d "$(W "$DAEMON/build")" $(find src -name '*.java'))
(cd "$DAEMON/build" && "$JH/bin/java" -cp "$(W "$BT/lib/d8.jar")" com.android.tools.r8.D8 \
  --release --min-api 34 --lib "$(W "$PLATFORM")" --output . $(find . -name '*.class'))
cp "$DAEMON/build/classes.dex" "$ROOT/out/inject.dex"

echo "== javac =="
(cd "$APP" && "$JH/bin/javac" -source 11 -target 11 -nowarn -encoding UTF-8 \
  -classpath "$(W "$PLATFORM")" -d "$(W "$OUT/obj")" \
  $(find src -name '*.java'))

echo "== d8 =="
(cd "$OUT/obj" && "$JH/bin/java" -cp "$(W "$BT/lib/d8.jar")" com.android.tools.r8.D8 \
  --release --min-api 34 --lib "$(W "$PLATFORM")" \
  --output . $(find . -name '*.class'))

echo "== aapt2 compile/link =="
(cd "$APP" && find res -type f | while read -r f; do
  "$BT/aapt2.exe" compile -o "$(W "$OUT")" "$(W "$APP/$f")"
done)
FLATS=()
for f in "$OUT"/*.flat; do FLATS+=("$(W "$f")"); done
"$BT/aapt2.exe" link -o "$(W "$OUT/base.apk")" \
  --manifest "$(W "$APP/AndroidManifest.xml")" \
  -I "$(W "$PLATFORM")" \
  "${FLATS[@]}" \
  --min-sdk-version 34 --target-sdk-version 36 --version-code 200 --version-name "$VER"

echo "== dex into apk =="
cd "$OUT/apk" && cp ../base.apk unaligned.apk && cp ../obj/classes.dex .
"$BT/aapt.exe" add unaligned.apk classes.dex >/dev/null

echo "== zipalign =="
"$BT/zipalign.exe" -f 4 unaligned.apk aligned.apk

echo "== sign =="
KS="$ROOT/debug.keystore"
if [ ! -f "$KS" ]; then
  "$JH/bin/keytool" -genkeypair -keystore "$(W "$KS")" -alias c700 -keyalg RSA \
    -keysize 2048 -validity 10000 -storepass android -keypass android \
    -dname "CN=c700-keymapd debug" >/dev/null 2>&1
fi
"$JH/bin/java" -cp "$(W "$BT/lib/apksigner.jar")" com.android.apksigner.ApkSignerTool \
  sign --ks "$(W "$KS")" --ks-pass pass:android --key-pass pass:android \
  "$(W "$OUT/apk/aligned.apk")"

mkdir -p "$ROOT/out"
cp aligned.apk "$ROOT/out/c700-keymapd.apk"
echo "== DONE =="
ls -la "$ROOT/out/"
