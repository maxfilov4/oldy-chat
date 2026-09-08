#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$sdk" ]]; then echo 'Set ANDROID_HOME to your Android SDK directory.' >&2; exit 1; fi
bt="$sdk/build-tools/35.0.0"
android="$sdk/platforms/android-35/android.jar"
mkdir -p build/generated build/classes build/dex .keys
"$bt/aapt2" compile --dir app/src/main/res -o build/resources.zip
"$bt/aapt2" link -I "$android" --manifest app/src/main/AndroidManifest.xml --java build/generated -o build/base.apk build/resources.zip
find app/src/main/java build/generated -name '*.java' > build/sources.txt
javac -encoding UTF-8 -source 8 -target 8 -bootclasspath "$android" -d build/classes @build/sources.txt
jar cf build/classes.jar -C build/classes .
"$bt/d8" --lib "$android" --min-api 26 --output build/dex build/classes.jar
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q -u ../unsigned.apk classes*.dex)
"$bt/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk
if [[ ! -f .keys/beta.jks ]]; then
 keytool -genkeypair -keystore .keys/beta.jks -storepass android -keypass android -alias oldy-beta -keyalg RSA -keysize 3072 -validity 3650 -dname 'CN=Oldy Chat Beta' -noprompt
fi
"$bt/apksigner" sign --ks .keys/beta.jks --ks-key-alias oldy-beta --ks-pass pass:android --key-pass pass:android --out build/OldyChat-beta.apk build/aligned.apk
"$bt/apksigner" verify --verbose build/OldyChat-beta.apk
echo 'Built build/OldyChat-beta.apk'
mkdir -p build/test-classes build/test-dex
javac -encoding UTF-8 -source 8 -target 8 -bootclasspath "$android" -classpath build/classes -d build/test-classes tests/CryptoInstrumentation.java
jar cf build/tests.jar -C build/test-classes .
"$bt/d8" --lib "$android" --classpath build/classes.jar --min-api 26 --output build/test-dex build/tests.jar
"$bt/aapt2" link -I "$android" --manifest tests/AndroidManifest.xml -o build/test-base.apk
(cd build/test-dex && zip -q -u ../test-base.apk classes*.dex)
"$bt/zipalign" -f -p 4 build/test-base.apk build/test-aligned.apk
"$bt/apksigner" sign --ks .keys/beta.jks --ks-key-alias oldy-beta --ks-pass pass:android --key-pass pass:android --out build/OldyChat-tests.apk build/test-aligned.apk
