"""Create the owner's copy/paste command from the exact verified CI artifact.
The input JSON is local release metadata, never a GitHub token or signing key.
"""
import argparse, ast, hashlib, json
from pathlib import Path

parser=argparse.ArgumentParser()
parser.add_argument('metadata',type=Path)
parser.add_argument('output',type=Path)
parser.add_argument('--publish-only',action='store_true',help='Publish APK to an already updated server without restarting it')
args=parser.parse_args();meta=json.loads(args.metadata.read_text())
for key in ('zip_sha256','bundle_sha256'):
 if len(meta[key])!=64 or any(c not in '0123456789abcdef' for c in meta[key]):raise SystemExit('Invalid SHA-256')
if not meta['url'].startswith('https://'):raise SystemExit('HTTPS URL required')
script=r'''import fcntl, hashlib, io, json, os, pathlib, ssl, subprocess, tarfile, tempfile, time, urllib.request, urllib.error, zipfile
if os.geteuid()!=0:raise SystemExit('Открой консоль сервера под root.')
lock=open('/var/lock/oldy-update.lock','w')
try:fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
except BlockingIOError:raise SystemExit('Обновление уже выполняется в другой консоли.')
release=META
publish_only=PUBLISH_ONLY
PUBLISHER
archive_name='OldyChat-0.6.5-server.tar.gz'
cache=pathlib.Path('/root')/archive_name
with tempfile.TemporaryDirectory(prefix='oldy-update-') as tmp:
 folder=pathlib.Path(tmp);bundle=None
 if cache.is_file() and cache.stat().st_size<180000000:
  data=cache.read_bytes()
  if hashlib.sha256(data).hexdigest()==release['bundle_sha256']:bundle=data
 if bundle is None:
  print('Скачиваем OldЫ Chat 0.6.5…',flush=True);downloaded=folder/'release.zip'
  for attempt in range(3):
   digest=hashlib.sha256();length=0
   try:
    request=urllib.request.Request(release['url'],headers={'User-Agent':'Mozilla/5.0','Accept':'application/zip'})
    with urllib.request.urlopen(request,timeout=90) as source,downloaded.open('wb') as target:
     if source.status!=200:raise ValueError('Неполный ответ сервера загрузки')
     while True:
      chunk=source.read(1048576)
      if not chunk:break
      length+=len(chunk)
      if length>500000000:raise ValueError('Неожиданный размер загрузки')
      digest.update(chunk);target.write(chunk)
    break
   except urllib.error.HTTPError as error:
    raise SystemExit('Загрузка недоступна (HTTP '+str(error.code)+'). Попроси новый файл команды обновления. Текущая версия сервера не изменена.')
   except (OSError,urllib.error.URLError) as error:
    if attempt==2:raise SystemExit('Не удалось скачать обновление: '+str(error.reason if isinstance(error,urllib.error.URLError) else error)+'. Текущая версия сервера не изменена.')
    time.sleep(2)
  if digest.hexdigest()!=release['zip_sha256']:raise SystemExit('Проверка загрузки не пройдена. Сервер не изменён.')
  with zipfile.ZipFile(downloaded) as zipped:
   member=zipped.getinfo(archive_name)
   if member.file_size>180000000:raise SystemExit('Неверный размер архива.')
   bundle=zipped.read(member)
 if hashlib.sha256(bundle).hexdigest()!=release['bundle_sha256']:raise SystemExit('Проверка архива не пройдена. Сервер не изменён.')
 expected={'OldyChat-latest.apk','server.py','install.sh','configure-mail.py','disk_storage.py','configure-disk.py','legal_service.py','legal_texts.json','configure-legal.py','retention.py','sticker_generation.py','configure-stickers.py','sticker_diagnostics.py','release.json'}
 with tarfile.open(fileobj=io.BytesIO(bundle),mode='r:gz') as tar:
  members=tar.getmembers()
  if len(members)!=len(expected) or {m.name for m in members}!=expected or not all(m.isfile() and m.size<180000000 for m in members):raise SystemExit('Неверный состав выпуска.')
  for member in members:
   with tar.extractfile(member) as source,(folder/member.name).open('wb') as target:
    while True:
     chunk=source.read(1048576)
     if not chunk:break
     target.write(chunk)
 manifest=json.loads((folder/'release.json').read_text());apk=folder/'OldyChat-latest.apk'
 if manifest['package']!='chat.oldy' or manifest['version_code']!=14 or manifest['sha256']!=hashlib.sha256(apk.read_bytes()).hexdigest() or manifest['size']!=apk.stat().st_size:raise SystemExit('Проверка APK не пройдена. Сервер не изменён.')
 cached=cache.with_suffix('.download');cached.write_bytes(bundle);cached.chmod(0o600);os.replace(cached,cache)
 if publish_only:
  if not pathlib.Path('/etc/oldy-chat/server.crt').is_file() or not pathlib.Path('/var/lib/oldy-chat').is_dir():raise SystemExit('Сначала установи сервер OldЫ Chat.')
  print('Публикуем APK для обновления внутри приложения…',flush=True)
  publish_release(folder,'/var/lib/oldy-chat/releases')
 else:
  print('Проверка пройдена. Сохраняем копию базы и обновляем сервер…',flush=True)
  with subprocess.Popen(['bash',str(folder/'install.sh')],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True) as process:
   for line in process.stdout:
    if 'UPDATE COMPLETE. Install the new APK on both phones.' not in line:print(line,end='',flush=True)
   if process.wait()!=0:raise SystemExit('Установка сервера завершилась с ошибкой. Обновление не подтверждено.')
 print('Проверяем выдачу обновления и скачивание APK…',flush=True)
 try:
  ctx=ssl.create_default_context(cafile='/etc/oldy-chat/server.crt')
  verify_update('https://5.42.102.11',manifest,context=ctx)
  verify_update('https://5.42.102.11:8443',manifest,context=ctx,download=False)
 except Exception as error:raise SystemExit('Проверка обновления не пройдена: '+str(error)+'. Пришли этот вывод; приложение удалять не нужно.')
 print('OLDY CHAT: UPDATE AVAILABLE 0.6.5-beta',flush=True)
 print('Теперь в текущем приложении: Настройки → Обновления → Обновить. Удалять приложение не нужно.',flush=True)
 if not pathlib.Path('/etc/oldy-chat/mail.env').exists():print('Для писем с кодом выполни: python3 /opt/oldy-chat/configure-mail.py',flush=True)
'''.replace('META',repr({k:meta[k] for k in ('url','zip_sha256','bundle_sha256')})).replace('PUBLISH_ONLY',repr(args.publish_only)).replace('PUBLISHER',Path(__file__).with_name('update-publisher.py').read_text())
ast.parse(script)
args.output.write_text("python3 - <<'OLDY_UPDATE_065'\n"+script+"OLDY_UPDATE_065\n")
print(json.dumps({'file':str(args.output),'bytes':args.output.stat().st_size,'sha256':hashlib.sha256(args.output.read_bytes()).hexdigest()}))
