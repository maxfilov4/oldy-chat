#!/usr/bin/env python3
"""Sticker-only setup/migration. Never print credentials or change other services."""
import argparse,getpass,os,re,subprocess,tempfile
from pathlib import Path

def read_settings(path):
 values={}
 if path.exists():
  for line in path.read_text().splitlines():
   name,sep,value=line.partition('=')
   if sep and name.startswith('OLDY_STICKER_'):values[name]=value
 return values

def save_settings(path,values):
 path.parent.mkdir(mode=0o750,exist_ok=True)
 fd,tmp=tempfile.mkstemp(prefix='.stickers-',dir=path.parent)
 try:
  os.fchmod(fd,0o600)
  with os.fdopen(fd,'w') as out:
   out.write(''.join(name+'='+value+'\n' for name,value in values.items()));out.flush();os.fsync(out.fileno())
  os.replace(tmp,path)
 finally:
  if os.path.exists(tmp):os.unlink(tmp)

def enable_all(path):
 # Keep the existing key, model and spending limit byte-for-byte.
 values=read_settings(path)
 if not values.get('OLDY_STICKER_OPENAI_KEY'):return False
 values['OLDY_STICKER_USERS']='*';save_settings(path,values);return True

def main():
 parser=argparse.ArgumentParser();parser.add_argument('--enable-all',action='store_true');parser.add_argument('--no-restart',action='store_true');args=parser.parse_args()
 if os.geteuid()!=0:raise SystemExit('Откройте консоль сервера под root.')
 path=Path('/etc/oldy-chat/stickers.env')
 if args.enable_all:
  if not enable_all(path):
   print('Стикеры: ключ ещё не настроен. Выполните python3 /opt/oldy-chat/configure-stickers.py');return
 else:
  values=read_settings(path)
  print('Мультяшные стикеры для ВСЕХ пользователей Oldi. Фото передаётся OpenAI после согласия в приложении.')
  print('Генерация оплачивается с вашего API-аккаунта OpenAI.')
  key=getpass.getpass('OpenAI API key (ввод скрыт)'+(' [Enter — оставить текущий]' if values.get('OLDY_STICKER_OPENAI_KEY') else '')+': ').strip() or values.get('OLDY_STICKER_OPENAI_KEY','')
  if not re.fullmatch(r'[A-Za-z0-9_\-]{30,512}',key):raise SystemExit('Некорректный формат ключа. Настройки не изменены.')
  previous=values.get('OLDY_STICKER_DAILY_LIMIT','10')
  limit=input('Общий лимит попыток генерации в сутки ['+previous+']: ').strip() or previous
  if not limit.isdigit() or not 1<=int(limit)<=100:raise SystemExit('Лимит от 1 до 100. Настройки не изменены.')
  values.update(OLDY_STICKER_OPENAI_KEY=key,OLDY_STICKER_USERS='*',OLDY_STICKER_DAILY_LIMIT=limit)
  values.setdefault('OLDY_STICKER_MODEL','gpt-image-1');save_settings(path,values)
 if not args.no_restart:subprocess.run(['systemctl','restart','oldy-chat'],check=True)
 print('Стикеры: доступ открыт всем текущим и новым аккаунтам. Ключ, модель и общий лимит сохранены.')
 print('В приложении: чат → + → Создать стикер из фото. Доступ и оплату API проверит первая генерация.')

if __name__=='__main__':main()
