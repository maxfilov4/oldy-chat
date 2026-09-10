#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ ! -f .keys/beta.jks ]]; then
 echo 'Restore the existing owner signing key; an update must keep its original identity.' >&2
 exit 1
fi
python3 tools/prepare-speech.py
bash tools/prepare-tunnel.sh
bash tools/prepare-dpi.sh
make -C .native/byedpi -j2
mkdir -p build
python3 tools/check-dpi.py .native/byedpi/ciadpi | tee build/dpi-results.txt
gradle --no-daemon :app:assembleRelease :app:assembleReleaseAndroidTest :app:bundlePlay
mkdir -p build
cp app/build/outputs/apk/release/app-release.apk build/OldyChat-beta.apk
cp app/build/outputs/bundle/play/app-play.aab build/OldyChat-Play.aab
cp app/build/outputs/apk/androidTest/release/app-release-androidTest.apk build/OldyChat-tests.apk
python3 tools/check-native-pages.py build/OldyChat-beta.apk
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
"$sdk/build-tools/35.0.0/apksigner" verify --verbose --print-certs build/OldyChat-beta.apk > build/signing-certificate.txt
grep -q 'certificate SHA-256 digest: c431f53f373f72eef0a013d61cfe0017255a986d22abb81f3dab4e5ce9720ec2' build/signing-certificate.txt || { echo 'Signing identity differs from installed APK.' >&2; exit 1; }
