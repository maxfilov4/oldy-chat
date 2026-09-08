#!/usr/bin/env bash
# Run as root on Ubuntu 24.04. Does not modify SSH or firewall defaults.
set -euo pipefail
if [[ $(id -u) != 0 ]]; then echo 'Run this installer as root.'; exit 1; fi
oldy_src="$(cd "$(dirname "$0")" && pwd)"
if [[ ! -f "$oldy_src/server.py" ]]; then echo 'server.py is missing'; exit 1; fi
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y python3 python3-cryptography openssl
id oldy-chat >/dev/null 2>&1 || useradd --system --home /var/lib/oldy-chat --shell /usr/sbin/nologin oldy-chat
install -d -o root -g root -m 755 /opt/oldy-chat
install -d -o oldy-chat -g oldy-chat -m 700 /var/lib/oldy-chat
install -d -o root -g oldy-chat -m 750 /etc/oldy-chat
install -o root -g root -m 644 "$oldy_src/server.py" /opt/oldy-chat/server.py
if [[ ! -f /etc/oldy-chat/server.crt ]]; then
 openssl req -x509 -newkey rsa:3072 -nodes -sha256 -days 365 -keyout /etc/oldy-chat/server.key -out /etc/oldy-chat/server.crt -subj '/CN=5.42.102.11' -addext 'subjectAltName=IP:5.42.102.11' >/dev/null 2>&1
fi
chown root:oldy-chat /etc/oldy-chat/server.key /etc/oldy-chat/server.crt
chmod 640 /etc/oldy-chat/server.key /etc/oldy-chat/server.crt
cat > /etc/systemd/system/oldy-chat.service <<'UNIT'
[Unit]
Description=Oldy Chat encrypted relay
After=network-online.target
Wants=network-online.target

[Service]
User=oldy-chat
Group=oldy-chat
Environment=OLDY_DATA=/var/lib/oldy-chat
ExecStart=/usr/bin/python3 /opt/oldy-chat/server.py --cert /etc/oldy-chat/server.crt --key /etc/oldy-chat/server.key
Restart=on-failure
RestartSec=5
UMask=0077
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/var/lib/oldy-chat
LimitCORE=0
MemoryMax=512M
TasksMax=120

[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl enable --now oldy-chat
systemctl restart oldy-chat
if command -v ufw >/dev/null && ufw status | head -1 | grep -q 'Status: active'; then ufw allow 8443/tcp comment 'Oldy Chat'; fi
python3 - <<'PY'
import ssl,urllib.request,time
ctx=ssl.create_default_context(cafile='/etc/oldy-chat/server.crt')
for attempt in range(10):
 try:
  # Local check keeps hostname verification enabled for the IP certificate.
  with urllib.request.urlopen('https://5.42.102.11:8443/health',context=ctx,timeout=3) as r:
   print(r.read().decode());break
 except Exception:
  if attempt==9:raise
  time.sleep(1)
PY
echo
echo 'OLDY CHAT: SERVER READY'
echo 'Address: https://5.42.102.11:8443'
openssl x509 -in /etc/oldy-chat/server.crt -noout -fingerprint -sha256
echo 'Paste the SHA-256 fingerprint into Oldy Chat settings on each phone.'
