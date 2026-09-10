#!/usr/bin/env python3
"""One-time owner setup; no secret is echoed or sent to Oldi clients."""
import getpass,os,re,subprocess,tempfile
from pathlib import Path
if os.geteuid()!=0:raise SystemExit('Откройте консоль сервера под root.')
print('Мультяшные стикеры: OpenAI API. Генерация платная по тарифу вашего API-аккаунта.')
print('Выбранные пользователями фото отправляются OpenAI после отдельного согласия в приложении.')
print('Подписка ChatGPT не заменяет оплату API. Ключ не нужно присылать в чат.')
key=getpass.getpass('OpenAI API key (ввод скрыт): ').strip()
if not re.fullmatch(r'[A-Za-z0-9_\-]{30,512}',key):raise SystemExit('Некорректный формат ключа. Настройки не изменены.')
users=input('Кому разрешить генерацию: @ники через запятую [oldy]: ').strip().replace('@','') or 'oldy'
if not all(re.fullmatch('[a-z0-9_]{3,24}',n.strip()) for n in users.split(',')):raise SystemExit('Укажите конкретные @ники. Настройки не изменены.')
limit=input('Общий лимит генераций в сутки [10]: ').strip() or '10'
if not limit.isdigit() or not 1<=int(limit)<=100:raise SystemExit('Лимит от 1 до 100. Настройки не изменены.')
print('Первые попытки проверяйте на своём аккаунте. Неудачный запрос также может расходовать лимит API.')
if input('Сохранить настройки и включить для этих аккаунтов? [да/нет]: ').strip().lower() not in ('да','yes'):raise SystemExit('Настройки не изменены.')
folder=Path('/etc/oldy-chat');folder.mkdir(mode=0o750,exist_ok=True)
fd,path=tempfile.mkstemp(prefix='.stickers-',dir=folder)
try:
 os.fchmod(fd,0o600)
 with os.fdopen(fd,'w') as out:out.write('OLDY_STICKER_OPENAI_KEY='+key+'\nOLDY_STICKER_MODEL=gpt-image-1\nOLDY_STICKER_USERS='+','.join(n.strip() for n in users.split(','))+'\nOLDY_STICKER_DAILY_LIMIT='+limit+'\n');out.flush();os.fsync(out.fileno())
 os.replace(path,folder/'stickers.env')
finally:
 if os.path.exists(path):os.unlink(path)
subprocess.run(['systemctl','restart','oldy-chat'],check=True)
print('Настройки сохранены. Теперь в Oldi: чат → + → Создать стикер из фото.')
print('Доступ и оплату API подтвердит первая генерация. Сам ключ на этом шаге не проверялся.')
