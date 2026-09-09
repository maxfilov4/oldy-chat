#!/usr/bin/env bash
# Run as root on Ubuntu 24.04. Does not modify SSH or firewall defaults.
set -euo pipefail
if [[ $(id -u) != 0 ]]; then echo 'Run this installer as root.'; exit 1; fi
oldy_src="$(cd "$(dirname "$0")" && pwd)"
if [[ ! -f "$oldy_src/server.py" ]]; then echo 'server.py is missing'; exit 1; fi
python3 -m py_compile "$oldy_src/server.py"
# Make a consistent SQLite snapshot before an additive migration.
oldy_backup="/var/backups/oldy-chat/$(date -u +%Y%m%d-%H%M%S)"
install -d -m 700 "$oldy_backup"
if [[ -f /opt/oldy-chat/server.py ]]; then cp /opt/oldy-chat/server.py "$oldy_backup/server.py"; fi
OLDY_BACKUP_DIR="$oldy_backup" python3 - <<'PYBACKUP'
import sqlite3,os,shutil
from pathlib import Path
source=Path('/var/lib/oldy-chat/accounts.sqlite3')
if source.exists():
 with sqlite3.connect(source) as src,sqlite3.connect(Path(os.environ['OLDY_BACKUP_DIR'])/'accounts.sqlite3') as dst:src.backup(dst)
key=Path('/var/lib/oldy-chat/channel-history.key')
if key.exists():shutil.copy2(key,Path(os.environ['OLDY_BACKUP_DIR'])/key.name)
PYBACKUP
if ss -H -ltnp '( sport = :443 )' | grep -q .; then
 oldy_pid=$(systemctl show oldy-chat --property MainPID --value 2>/dev/null || true)
 if [[ -z "$oldy_pid" || "$oldy_pid" == 0 ]] || ! ss -H -ltnp '( sport = :443 )' | grep -q "pid=$oldy_pid,"; then
  echo 'Port 443 is used by another service. Existing service left unchanged.' >&2; exit 1
 fi
fi
oldy_coturn_present=0
if dpkg-query -W -f='${Status}' coturn 2>/dev/null | grep -q 'install ok installed'; then oldy_coturn_present=1; fi
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y python3 python3-cryptography python3-pil openssl coturn ffmpeg
# A newly installed package may start its default TURN service. Only stop it
# if it did not exist before this installer; never replace an existing TURN setup.
if [[ "$oldy_coturn_present" == 0 ]]; then systemctl disable --now coturn.service 2>/dev/null || true; fi
id oldy-chat >/dev/null 2>&1 || useradd --system --home /var/lib/oldy-chat --shell /usr/sbin/nologin oldy-chat
install -d -o root -g root -m 755 /opt/oldy-chat
install -d -o oldy-chat -g oldy-chat -m 700 /var/lib/oldy-chat
install -d -o root -g oldy-chat -m 750 /etc/oldy-chat
install -o root -g root -m 644 "$oldy_src/server.py" /opt/oldy-chat/server.py
if [[ -f "$oldy_src/configure-mail.py" ]]; then install -o root -g root -m 700 "$oldy_src/configure-mail.py" /opt/oldy-chat/configure-mail.py; fi
install -o root -g root -m 644 "$oldy_src/disk_storage.py" /opt/oldy-chat/disk_storage.py
install -o root -g root -m 700 "$oldy_src/configure-disk.py" /opt/oldy-chat/configure-disk.py
if [[ ! -f /etc/oldy-chat/server.crt ]]; then
 openssl req -x509 -newkey rsa:3072 -nodes -sha256 -days 365 -keyout /etc/oldy-chat/server.key -out /etc/oldy-chat/server.crt -subj '/CN=5.42.102.11' -addext 'subjectAltName=IP:5.42.102.11' >/dev/null 2>&1
fi
chown root:oldy-chat /etc/oldy-chat/server.key /etc/oldy-chat/server.crt
chmod 640 /etc/oldy-chat/server.key /etc/oldy-chat/server.crt
python3 - <<'PYOWNER'
import sqlite3,hashlib,os
from pathlib import Path
source=Path('/var/lib/oldy-chat/accounts.sqlite3');config=Path('/etc/oldy-chat/owner.env')
if source.exists() and not config.exists():
 with sqlite3.connect(source) as db:
  row=db.execute("SELECT sig FROM users WHERE nick='oldy'").fetchone()
 if row:
  config.write_text('OLDY_OWNER_NICK=oldy\nOLDY_OWNER_KEY_HASH='+hashlib.sha256(row[0].encode()).hexdigest()+'\n');config.chmod(0o600)
 else:print('Creator video uploads disabled: established @oldy account not found.')
PYOWNER
cat > /etc/systemd/system/oldy-chat.service <<'UNIT'
[Unit]
Description=Oldy Chat encrypted relay
After=network-online.target
Wants=network-online.target

[Service]
User=oldy-chat
Group=oldy-chat
Environment=OLDY_DATA=/var/lib/oldy-chat
EnvironmentFile=-/etc/oldy-chat/owner.env
EnvironmentFile=-/etc/oldy-chat/mail.env
EnvironmentFile=-/etc/oldy-chat/disk.env
EnvironmentFile=-/etc/oldy-chat/turn.env
ExecStart=/usr/bin/python3 /opt/oldy-chat/server.py --cert /etc/oldy-chat/server.crt --key /etc/oldy-chat/server.key --also-443
Restart=on-failure
RestartSec=5
UMask=0077
AmbientCapabilities=CAP_NET_BIND_SERVICE
CapabilityBoundingSet=CAP_NET_BIND_SERVICE
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/var/lib/oldy-chat
LimitCORE=0
MemoryMax=768M
TasksMax=120

[Install]
WantedBy=multi-user.target
UNIT
if [[ -f "$oldy_src/OldyChat-latest.apk" && -f "$oldy_src/release.json" ]]; then
 OLDY_RELEASE_DIR="$oldy_src" python3 - <<'PYRELEASE'
import hashlib,json,os,shutil
from pathlib import Path
src=Path(os.environ['OLDY_RELEASE_DIR']);apk=src/'OldyChat-latest.apk';info=json.loads((src/'release.json').read_text())
if info['package']!='chat.oldy' or info['sha256']!=hashlib.sha256(apk.read_bytes()).hexdigest() or info['size']!=apk.stat().st_size:raise SystemExit('Release verification failed')
dest=Path('/var/lib/oldy-chat/releases');dest.mkdir(mode=0o755,exist_ok=True)
for name in ['OldyChat-latest.apk','release.json']:
 temporary=dest/(name+'.new');shutil.copyfile(src/name,temporary);temporary.chmod(0o644);os.replace(temporary,dest/name)
print('APK installed for in-app updates:',info['version_name'])
PYRELEASE
fi
systemctl daemon-reload
systemctl enable --now oldy-chat
systemctl restart oldy-chat
if command -v ufw >/dev/null && ufw status | head -1 | grep -q 'Status: active'; then ufw allow 8443/tcp comment 'Oldy Chat'; ufw allow 443/tcp comment 'Oldy Chat HTTPS'; ufw allow 3478/udp comment 'Oldy Chat direct media'; fi
# Reuse the dedicated discovery service; preserve unrelated coturn installations.
oldy_turn_available=1
if { ss -H -lunp '( sport = :3478 )'; ss -H -ltnp '( sport = :3478 )'; } | grep -q . && ! systemctl is-active --quiet oldy-stun; then
 oldy_turn_available=0
 echo 'Port 3478 belongs to another service. TURN was not enabled; existing service preserved.'
fi
if [[ "$oldy_turn_available" == 1 ]]; then
 python3 - <<'PYTURN'
import secrets,os
from pathlib import Path
folder=Path('/etc/oldy-chat');env=folder/'turn.env';values={}
if env.exists():
 for line in env.read_text().splitlines():
  if '=' in line:
   k,v=line.split('=',1);values[k]=v
secret=values.get('OLDY_TURN_SECRET') or secrets.token_hex(32)
env.write_text('OLDY_TURN_HOST=5.42.102.11\nOLDY_TURN_SECRET='+secret+'\n');env.chmod(0o600)
config='''listening-port=3478
listening-ip=0.0.0.0
min-port=49160
max-port=49220
realm=oldy.chat
fingerprint
use-auth-secret
no-tls
no-dtls
no-tcp-relay
no-cli
no-software-attribute
no-multicast-peers
stale-nonce=600
user-quota=4
total-quota=40
max-bps=256000
bps-capacity=5000000
pidfile=/run/oldy-stun/turnserver.pid
log-file=stdout
no-stdout-log
denied-peer-ip=0.0.0.0-0.255.255.255
denied-peer-ip=10.0.0.0-10.255.255.255
denied-peer-ip=100.64.0.0-100.127.255.255
denied-peer-ip=127.0.0.0-127.255.255.255
denied-peer-ip=169.254.0.0-169.254.255.255
denied-peer-ip=172.16.0.0-172.31.255.255
denied-peer-ip=192.168.0.0-192.168.255.255
denied-peer-ip=224.0.0.0-255.255.255.255
denied-peer-ip=::1
denied-peer-ip=fc00::-fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff
denied-peer-ip=fe80::-febf:ffff:ffff:ffff:ffff:ffff:ffff:ffff
'''
(folder/'stun.conf').write_text(config+'static-auth-secret='+secret+'\n');(folder/'stun.conf').chmod(0o640)
PYTURN
 chown root:oldy-chat /etc/oldy-chat/stun.conf
 cat > /etc/systemd/system/oldy-stun.service <<'UNIT'
[Unit]
Description=Oldy Chat authenticated audio relay and STUN
After=network-online.target
[Service]
User=oldy-chat
Group=oldy-chat
RuntimeDirectory=oldy-stun
ExecStart=/usr/bin/turnserver -c /etc/oldy-chat/stun.conf
Restart=on-failure
NoNewPrivileges=true
ProtectHome=true
ProtectSystem=strict
PrivateTmp=true
MemoryMax=96M
[Install]
WantedBy=multi-user.target
UNIT
 systemctl daemon-reload
 systemctl enable --now oldy-stun
 systemctl restart oldy-stun
 if command -v ufw >/dev/null && ufw status | head -1 | grep -q 'Status: active'; then
  ufw allow 3478/tcp comment 'Oldy calls'
  ufw allow 49160:49220/udp comment 'Oldy audio relay'
 fi
 systemctl restart oldy-chat
fi
python3 - <<'PY' 
import ssl,urllib.request,time
ctx=ssl.create_default_context(cafile='/etc/oldy-chat/server.crt')
for attempt in range(10):
 try:
  # Local check keeps hostname verification enabled for the IP certificate.
  with urllib.request.urlopen('https://5.42.102.11/health',context=ctx,timeout=3) as r:
   print(r.read().decode());break
 except Exception:
  if attempt==9:raise
  time.sleep(1)
PY
echo
echo 'OLDY CHAT: SERVER READY'
echo 'HTTPS 443 ready; 8443 kept for existing phones.'
openssl x509 -in /etc/oldy-chat/server.crt -noout -fingerprint -sha256
if [[ -f "$oldy_src/OldyChat-latest.apk" && -f "$oldy_src/release.json" ]]; then
 echo 'APK published. Open Settings > Updates in the existing app.'
else
 echo 'SERVER ONLY: no Android release was included. In-app updates require the full release with APK and release.json.'
fi
