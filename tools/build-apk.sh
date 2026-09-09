#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$sdk" ]]; then echo 'Set ANDROID_HOME to your Android SDK directory.' >&2; exit 1; fi
bt="$sdk/build-tools/35.0.0"
android="$sdk/platforms/android-35/android.jar"
mkdir -p build/generated build/classes build/dex .keys build/webrtc
if [[ ! -f build/webrtc/classes.jar ]]; then
 curl --fail --location --retry 2 https://repo.maven.apache.org/maven2/io/github/webrtc-sdk/android/144.7559.12/android-144.7559.12.aar -o build/webrtc/sdk.aar
 echo 'd1564a43a85d0687db51862a590263c6764f9ac8f87e7960b1e9c9f222b898aa  build/webrtc/sdk.aar' | sha256sum -c -
 unzip -q -o build/webrtc/sdk.aar -d build/webrtc
fi
rtc=build/webrtc/classes.jar
"$bt/aapt2" compile --dir app/src/main/res -o build/resources.zip
"$bt/aapt2" link -I "$android" --manifest app/src/main/AndroidManifest.xml --java build/generated -A app/src/main/assets -o build/base.apk build/resources.zip
find app/src/main/java build/generated -name '*.java' > build/sources.txt
javac -encoding UTF-8 --release 8 -classpath "$android:$rtc" -d build/classes @build/sources.txt
jar cf build/classes.jar -C build/classes .
"$bt/d8" --lib "$android" --min-api 26 --output build/dex build/classes.jar "$rtc"
cp build/base.apk build/unsigned.apk
mkdir -p build/native/lib
cp -r build/webrtc/jni/arm64-v8a build/webrtc/jni/armeabi-v7a build/webrtc/jni/x86_64 build/native/lib/
(cd build/native && zip -q -r ../unsigned.apk lib)
(cd build/dex && zip -q -u ../unsigned.apk classes*.dex)
"$bt/zipalign" -f -P 16 4 build/unsigned.apk build/aligned.apk
if [[ ! -f .keys/beta.jks ]]; then
 echo 'Existing beta signing key is required for an in-place update. Restore the owner signing backup/cache.' >&2
 exit 1
fi
"$bt/apksigner" sign --ks .keys/beta.jks --ks-key-alias oldy-beta --ks-pass pass:android --key-pass pass:android --out build/OldyChat-beta.apk build/aligned.apk
"$bt/apksigner" verify --verbose build/OldyChat-beta.apk
"$bt/apksigner" verify --print-certs build/OldyChat-beta.apk > build/signing-certificate.txt
grep -q 'certificate SHA-256 digest: c431f53f373f72eef0a013d61cfe0017255a986d22abb81f3dab4e5ce9720ec2' build/signing-certificate.txt || { echo 'Signing identity differs from installed 0.2 APK.' >&2; exit 1; }
echo 'Built build/OldyChat-beta.apk'
mkdir -p build/test-classes build/test-dex
javac -encoding UTF-8 --release 8 -classpath "$android:build/classes:$rtc" -d build/test-classes tests/*Instrumentation.java
jar cf build/tests.jar -C build/test-classes .
"$bt/d8" --lib "$android" --classpath build/classes.jar --min-api 26 --output build/test-dex build/tests.jar
"$bt/aapt2" link -I "$android" --manifest tests/AndroidManifest.xml -A tests/assets -o build/test-base.apk
(cd build/test-dex && zip -q -u ../test-base.apk classes*.dex)
"$bt/zipalign" -f -P 16 4 build/test-base.apk build/test-aligned.apk
"$bt/apksigner" sign --ks .keys/beta.jks --ks-key-alias oldy-beta --ks-pass pass:android --key-pass pass:android --out build/OldyChat-tests.apk build/test-aligned.apk
