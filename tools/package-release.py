from pathlib import Path
import hashlib,json,tarfile,shutil,xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[1];build=root/'build';apk=build/'OldyChat-beta.apk'
if not apk.exists():raise SystemExit('Build the APK first')
release=build/'release';release.mkdir(exist_ok=True)
for name in ('server.py','install.sh','configure-mail.py','disk_storage.py','configure-disk.py'):shutil.copyfile(root/'server'/name,release/name)
shutil.copyfile(apk,release/'OldyChat-latest.apk')
android='{http://schemas.android.com/apk/res/android}';app=ET.parse(root/'app/src/main/AndroidManifest.xml').getroot()
manifest={'package':app.get('package','chat.oldy'),'version_code':int(app.get(android+'versionCode')),'version_name':app.get(android+'versionName'),'size':apk.stat().st_size,'sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'notes':'Oldy 0.5: вход по e-mail и коду, номера аккаунтов, поиск каналов по названию, архив и удаление чатов, запись голоса с отменой жестом, английский язык, аудиомастерская, восемь обоев. Видео: своя обложка или выбранный кадр, полноэкранный поворот, меню качества и скорости. Для автоматического хранения видео на Диске владелец один раз запускает configure-disk.py на сервере.'}
(release/'release.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2))
with tarfile.open(build/'OldyChat-0.5.0-server.tar.gz','w:gz') as t:
 for f in sorted(release.iterdir()):t.add(f,arcname=f.name)
print(json.dumps({'archive':str(build/'OldyChat-0.5.0-server.tar.gz'),'apk_sha256':manifest['sha256']}))
