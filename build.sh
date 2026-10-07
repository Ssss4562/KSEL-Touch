#!/usr/bin/env bash
# Сборка без Gradle: aapt + javac + dx + zipalign + apksigner.
# Нужны: openjdk, aapt, dalvik-exchange (dx), zipalign, apksigner, libandroid-23-java
set -euo pipefail
cd "$(dirname "$0")"
ANDROID_JAR=${ANDROID_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}
OUT=build
APK_NAME=${APK_NAME:-KSEL-Touch}
rm -rf "$OUT" && mkdir -p "$OUT"/{gen,classes,dex}

aapt package -f -m -J "$OUT/gen" -M AndroidManifest.xml -S res -I "$ANDROID_JAR" \
     -F "$OUT/unsigned.apk" --min-sdk-version 16 --target-sdk-version 22 \
     --version-code 1 --version-name 1.0-touch

javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options \
      -bootclasspath "$ANDROID_JAR" -d "$OUT/classes" \
      $(find src "$OUT/gen" -name '*.java')

dalvik-exchange --dex --min-sdk-version=16 --output="$OUT/dex/classes.dex" "$OUT/classes" 2>/dev/null || \
  dx --dex --output="$OUT/dex/classes.dex" "$OUT/classes"

(cd "$OUT/dex" && aapt add -f ../unsigned.apk classes.dex >/dev/null)
zipalign -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

KS=${KEYSTORE:-keystore/debug.jks}
if [ ! -f "$KS" ]; then
  mkdir -p "$(dirname "$KS")"
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias ksel -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=KSEL Touch, O=KSEL Touch, C=RU" >/dev/null 2>&1
fi
apksigner sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --out "$OUT/$APK_NAME.apk" "$OUT/aligned.apk"
apksigner verify "$OUT/$APK_NAME.apk" && echo "OK: $OUT/$APK_NAME.apk ($(du -h "$OUT/$APK_NAME.apk" | cut -f1))"
