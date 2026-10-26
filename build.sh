#!/usr/bin/env bash
# Builds a signed APK without Gradle: aapt2 + javac + d8 + zipalign + apksigner.
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/36.0.0"
ANDROID_JAR="$SDK/platforms/android-36/android.jar"
KEYSTORE="$HOME/.android/oled-launcher.keystore"
OUT=build

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/gen"

# One key for every build, kept outside the repo, so updates install over each other.
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -storepass oledhome -keypass oledhome \
    -alias oled -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=OLED Home" >/dev/null
fi

"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$ANDROID_JAR" --manifest AndroidManifest.xml \
  --min-sdk-version 21 --target-sdk-version 34 --java "$OUT/gen" "$OUT/res.zip"

javac --release 11 -Xlint:all -cp "$ANDROID_JAR" -d "$OUT/classes" $(find src "$OUT/gen" -name '*.java')
"$BT/d8" --release --min-api 21 --lib "$ANDROID_JAR" --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class')

cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -j ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:oledhome --key-pass pass:oledhome \
  --out "$OUT/oled-home.apk" "$OUT/aligned.apk"

ls -l "$OUT/oled-home.apk"
