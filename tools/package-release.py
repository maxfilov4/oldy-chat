from pathlib import Path
import hashlib,json,tarfile,shutil,xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[1];build=root/'build';apk=build/'OldyChat-beta.apk'
if not apk.exists():raise SystemExit('Build the APK first')
release=build/'release';release.mkdir(exist_ok=True)
for name in ('server.py','install.sh','configure-mail.py','disk_storage.py','configure-disk.py','legal_service.py','legal_texts.json','configure-legal.py','retention.py','sticker_generation.py','configure-stickers.py','sticker_diagnostics.py'):shutil.copyfile(root/'server'/name,release/name)
shutil.copyfile(apk,release/'OldyChat-latest.apk')
android='{http://schemas.android.com/apk/res/android}';app=ET.parse(root/'app/src/main/AndroidManifest.xml').getroot()
manifest={'package':app.get('package','chat.oldy'),'version_code':int(app.get(android+'versionCode')),'version_name':app.get(android+'versionName'),'size':apk.stat().st_size,'sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'notes':"Oldi 0.6.4: упрощено создание личных стикеров. Короткий статус, крупная анимация и вопрос «Добавить в личные стикеры?» с кнопками «Да» и «Нет». Сохранённые стикеры остаются в личной коллекции без срока удаления. Служебные названия и текст о временном хранении убраны из редактора. Добавлена отдельная диагностика доступа к генератору для владельца сервера."}
(release/'release.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2))
with tarfile.open(build/'OldyChat-0.6.4-server.tar.gz','w:gz') as t:
 for f in sorted(release.iterdir()):t.add(f,arcname=f.name)
print(json.dumps({'archive':str(build/'OldyChat-0.6.4-server.tar.gz'),'apk_sha256':manifest['sha256']}))
