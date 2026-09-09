#!/usr/bin/env bash
set -euo pipefail
mkdir -p build/screenshots
adb install -r build/OldyChat-beta.apk
adb logcat -c
adb shell am start -W -n chat.oldy/.MainActivity
sleep 3
python3 tools/ui-dump.py build/window.xml 'Регистрация'
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
adb install -r build/OldyChat-tests.apk
python3 tests/device-server.py > build/device-server.log 2>&1 &
server_pid=$!
trap 'kill "$server_pid" 2>/dev/null || true' EXIT
for attempt in $(seq 1 30); do
 if [[ -f build/device-server/pin.txt ]]; then break; fi
 sleep 1
done
pin=$(cat build/device-server/pin.txt)
timeout 150s adb shell am instrument -w -e pin "$pin" chat.oldy.tests/chat.oldy.CryptoInstrumentation > build/crypto-results.txt
cat build/crypto-results.txt
grep -q 'OLDY_CRYPTO_PASS' build/crypto-results.txt
adb shell pm grant chat.oldy android.permission.POST_NOTIFICATIONS
timeout 150s adb shell am instrument -w chat.oldy.tests/chat.oldy.FeatureInstrumentation > build/feature-results.txt
cat build/feature-results.txt
grep -q 'OLDY_FEATURES_PASS' build/feature-results.txt
timeout 150s adb shell am instrument -w -e pin "$pin" chat.oldy.tests/chat.oldy.ScreenOffInstrumentation > build/screen-off-results.txt
cat build/screen-off-results.txt
grep -q 'OLDY_SCREEN_OFF_PASS' build/screen-off-results.txt
# Instrumentation finish can end its process before Java finally restores the screen.
adb shell input keyevent 224
adb shell input keyevent 82
adb shell wm dismiss-keyguard
sleep 2
adb shell am force-stop chat.oldy
adb shell pm grant chat.oldy android.permission.POST_NOTIFICATIONS
adb shell am start -W -n chat.oldy/.MainActivity
sleep 3
adb exec-out screencap -p > build/screenshots/02-chats.png
python3 tools/ui-dump.py build/chats.xml 'Борис'
python3 - <<'PY'
import xml.etree.ElementTree as ET,re,subprocess
nodes=list(ET.parse('build/chats.xml').iter('node'))
node=next(n for n in nodes if n.get('text')=='Борис')
x,y,r,b=map(int,re.findall(r'\d+',node.get('bounds')))
subprocess.run(['adb','shell','input','tap',str((x+r)//2),str((y+b)//2)],check=True)
PY
sleep 2
adb exec-out screencap -p > build/screenshots/03-conversation.png
python3 tools/ui-dump.py build/conversation.xml 'Как тебе OldЫ Chat?'
python3 - <<'PY'
from pathlib import Path
s=Path('build/conversation.xml').read_text()
assert 'Как тебе OldЫ Chat?' in s,s
assert 'Сообщение' in s,s
import xml.etree.ElementTree as ET,re
for node in ET.fromstring(s).iter('node'):
 if 'Как тебе OldЫ Chat?' in node.get('text','') or 'Уже проверяю' in node.get('text',''):
  x,y,r,b=map(int,re.findall(r'\d+',node.get('bounds')))
  assert r-x>100 and b-y>25, ('Message is not visibly laid out',node.attrib)
print('PASS: encrypted history restored and conversation rendered')
PY
timeout 150s adb shell am instrument -w chat.oldy.tests/chat.oldy.DesignInstrumentation > build/design-results.txt
cat build/design-results.txt
grep -q 'OLDY_DESIGN_PASS' build/design-results.txt
timeout 150s adb shell am instrument -w chat.oldy.tests/chat.oldy.VideoInstrumentation > build/video-results.txt
cat build/video-results.txt
grep -q 'OLDY_VIDEO_PASS' build/video-results.txt
adb pull /sdcard/Android/data/chat.oldy/files/review/. build/screenshots/
adb logcat -d -s AndroidRuntime:E > build/android-errors.log
if grep -q 'FATAL EXCEPTION' build/android-errors.log; then cat build/android-errors.log; exit 1; fi
