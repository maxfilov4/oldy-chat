#!/usr/bin/env bash
set -euo pipefail
mkdir -p build/screenshots
adb install -r build/OldyChat-beta.apk
adb install -r build/OldyChat-tests.apk
python3 tests/device-server.py > build/device-server.log 2>&1 &
server_pid=$!
trap 'kill "$server_pid" 2>/dev/null || true' EXIT
for attempt in $(seq 1 30); do
 if [[ -f build/device-server/pin.txt ]]; then break; fi
 sleep 1
done
pin=$(cat build/device-server/pin.txt)
adb shell am instrument -w -e pin "$pin" chat.oldy.tests/chat.oldy.CryptoInstrumentation > build/crypto-results.txt
cat build/crypto-results.txt
rg -q 'OLDY_CRYPTO_PASS' build/crypto-results.txt
adb logcat -c
adb shell am start -W -n chat.oldy/.MainActivity
sleep 3
adb shell uiautomator dump /sdcard/window.xml
adb pull /sdcard/window.xml build/window.xml
python3 - <<'PY'
from pathlib import Path
s=Path('build/window.xml').read_text()
assert 'OldЫ Chat' in s, s
assert 'Регистрация' in s, s
assert 'Пароль' in s, s
assert 'FATAL EXCEPTION' not in s
print('PASS: login screen rendered on Android')
PY
adb exec-out screencap -p > build/screenshots/01-login.png
adb logcat -d -s AndroidRuntime:E > build/android-errors.log
if rg -q 'FATAL EXCEPTION' build/android-errors.log; then cat build/android-errors.log; exit 1; fi
