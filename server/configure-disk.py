#!/usr/bin/env python3
"""One-time owner-run setup; tests the exact app-folder upload/read/delete path."""
import getpass,os,pathlib,secrets,subprocess,tempfile
from disk_storage import YandexDisk,DiskError

def main():
 if os.geteuid()!=0:raise SystemExit('Запусти настройку под root.')
 print('Разрешение OAuth-приложения: cloud_api:disk.app_folder. Пароль от Яндекса не нужен.')
 token=getpass.getpass('OAuth-токен Диска (ввод скрыт): ').strip()
 try:
  disk=YandexDisk(token);disk.folder_ready();name='check-'+secrets.token_hex(8)+'.bin';data=secrets.token_bytes(1024)
  with tempfile.TemporaryDirectory() as tmp:
   f=pathlib.Path(tmp)/name;f.write_bytes(data);disk.upload(name,f)
   with disk.open_range(name,123,456,len(data)) as response:
    if response.read()!=data[123:457]:raise DiskError('Range verification failed')
   if not disk.delete(name):print('Тестовый файл ещё удаляется. Размер: 1 КБ.')
 except Exception as e:raise SystemExit('Проверка не пройдена. Настройки чата не изменены. Проверь токен, разрешение папки приложения и доступ сервера к Диску. '+(str(e) if isinstance(e,DiskError) else ''))
 dest=pathlib.Path('/etc/oldy-chat/disk.env');dest.parent.mkdir(mode=0o750,exist_ok=True)
 fd,tmp=tempfile.mkstemp(prefix='disk-',dir=dest.parent)
 try:
  os.fchmod(fd,0o600)
  with os.fdopen(fd,'w') as out:out.write('OLDY_DISK_TOKEN="'+token+'"\nOLDY_DISK_FOLDER="app:/OldyVideos"\n')
  os.replace(tmp,dest)
 finally:
  if os.path.exists(tmp):os.unlink(tmp)
 subprocess.run(['systemctl','restart','oldy-chat'],check=True)
 print('Диск подключён. Видео каналов будут переноситься автоматически. Локальный оригинал удаляется только после проверки копии. Статус: journalctl -u oldy-chat --since today')
if __name__=='__main__':main()
