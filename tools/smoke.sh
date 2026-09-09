#!/usr/bin/env bash
set -euo pipefail
mkdir -p build/screenshots
adb install -r build/OldyChat-beta.apk
adb logcat -c
adb shell am start -W -n chat.oldy/.MainActivity
adb exec-out screencap -p > build/screenshots/00-welcome.png
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
export OLDY_TURN_HOST=10.0.2.2 OLDY_TEST_TURN=1
OLDY_TURN_SECRET=$(python3 -c 'import secrets; print(secrets.token_hex(32))')
export OLDY_TURN_SECRET
turnserver -c /dev/null --listening-ip=0.0.0.0 --listening-port=3478 --realm=oldy-fixture --use-auth-secret --static-auth-secret="$OLDY_TURN_SECRET" --allow-loopback-peers --min-port=49200 --max-port=49230 --no-cli --no-tls --no-dtls --no-tcp-relay --pidfile=build/test-turn.pid --log-file=stdout > build/test-turn.log 2>&1 &
turn_pid=$!
python3 tests/device-server.py > build/device-server.log 2>&1 &
server_pid=$!
trap 'adb logcat -d -s Camera2Session:D CameraCapturer:D EglRenderer:D oldy-round-encoder:D MediaCodec:E CCodec:E > build/capture-debug.log; adb logcat -d -s AndroidRuntime:E MediaPlayer:E > build/android-errors.log; adb pull /sdcard/Android/data/chat.oldy/files/review/. build/screenshots/ >/dev/null 2>&1 || true; kill "$server_pid" "$turn_pid" 2>/dev/null || true' EXIT
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
timeout 150s adb shell am instrument -w chat.oldy.tests/chat.oldy.UpdateInstrumentation > build/update-results.txt
cat build/update-results.txt
grep -q 'OLDY_UPDATE_PASS' build/update-results.txt
timeout 150s adb shell am instrument -w chat.oldy.tests/chat.oldy.ChannelInstrumentation > build/channel-results.txt
cat build/channel-results.txt
grep -q 'OLDY_CHANNEL_PASS' build/channel-results.txt
timeout 150s adb shell am instrument -w chat.oldy.tests/chat.oldy.RefreshInstrumentation > build/refresh-results.txt
cat build/refresh-results.txt
grep -q 'OLDY_REFRESH_PASS' build/refresh-results.txt
adb shell pm grant chat.oldy android.permission.RECORD_AUDIO
adb shell pm grant chat.oldy android.permission.CAMERA
timeout 150s adb shell am instrument -w chat.oldy.tests/chat.oldy.CaptureInstrumentation > build/capture-results.txt
cat build/capture-results.txt
grep -q 'OLDY_CAPTURE_PASS' build/capture-results.txt
timeout 200s adb shell am instrument -w chat.oldy.tests/chat.oldy.CallInstrumentation > build/call-results.txt
cat build/call-results.txt
grep -q 'OLDY_CALL_PASS' build/call-results.txt
adb pull /sdcard/Android/data/chat.oldy/files/review/. build/screenshots/
adb logcat -d -s AndroidRuntime:E > build/android-errors.log
if grep -q 'FATAL EXCEPTION' build/android-errors.log; then cat build/android-errors.log; exit 1; fi
