#!/usr/bin/env python3
"""Run by the owner. Public contact data is written only after explicit entry."""
import json,os,re,subprocess
from pathlib import Path
from urllib.parse import urlsplit
if os.geteuid()!=0:raise SystemExit('Run as root.')
print('Публичные сведения Oldi Chat. Эти данные увидят пользователи и Google Play.')
operator=input('Имя / название оператора сервиса: ').strip()
support=input('Публичный e-mail поддержки: ').strip()
child=input('Контакт по безопасности детей (e-mail): ').strip()
url=input('Публичный HTTPS-адрес этого сервера (например https://chat.example.org): ').strip().rstrip('/')
u=urlsplit(url)
if not 2<=len(operator)<=200 or '\n' in operator:raise SystemExit('Укажите оператора.')
if any(not re.fullmatch(r'[^\s@]+@[^\s@]+\.[^\s@]+',x) for x in (support,child)):raise SystemExit('Неверный адрес почты.')
if u.scheme!='https' or not u.hostname or u.username or u.password or u.query or u.fragment or u.path or u.port not in (None,443):raise SystemExit('Нужен публичный HTTPS-домен без пути, на порту 443.')
config=dict(operator=operator,support_email=support,child_safety_email=child,public_url=url)
path=Path('/var/lib/oldy-chat/legal-config.json');tmp=path.with_suffix('.new');tmp.write_text(json.dumps(config,ensure_ascii=False,indent=2));tmp.chmod(0o600);subprocess.run(['chown','oldy-chat:oldy-chat',str(tmp)],check=True);os.replace(tmp,path)
print('Контакты сохранены. Проверьте публичные страницы с другого устройства:')
for key in ('privacy','terms','community','delete'):print(url+'/legal/'+key)
print('Для домена нужен действующий публичный сертификат. Не заменяйте сертификат IP-подключения: старое приложение проверяет его отпечаток. Дополнительный сертификат домена задаётся OLDY_PUBLIC_HOST, OLDY_PUBLIC_CERT, OLDY_PUBLIC_KEY в /etc/oldy-chat/legal.env; затем systemctl restart oldy-chat. Ключ должен читаться только root и группой oldy-chat.')
