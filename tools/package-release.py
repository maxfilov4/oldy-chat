from pathlib import Path
import hashlib,json,tarfile,shutil,xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[1];build=root/'build';apk=build/'OldyChat-beta.apk'
if not apk.exists():raise SystemExit('Build the APK first')
release=build/'release';release.mkdir(exist_ok=True)
for name in ('server.py','install.sh','configure-mail.py','disk_storage.py','configure-disk.py','legal_service.py','legal_texts.json','configure-legal.py','retention.py','sticker_generation.py','configure-stickers.py','sticker_diagnostics.py'):shutil.copyfile(root/'server'/name,release/name)
shutil.copyfile(apk,release/'OldyChat-latest.apk')
android='{http://schemas.android.com/apk/res/android}';app=ET.parse(root/'app/src/main/AndroidManifest.xml').getroot()
manifest={'package':app.get('package','chat.oldy'),'version_code':int(app.get(android+'versionCode')),'version_name':app.get(android+'versionName'),'size':apk.stat().st_size,'sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'notes':"Oldi 0.6.5: создание стикера запускается сразу после нажатия, без отдельной проверки и повторных окон. Добавлен короткий статус ожидания, сняты суточные лимиты для всех аккаунтов и включена быстрая модель. После готовности приложение предлагает добавить анимацию в личные стикеры без срока удаления."}
(release/'release.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2))
with tarfile.open(build/'OldyChat-0.6.5-server.tar.gz','w:gz') as t:
 for f in sorted(release.iterdir()):t.add(f,arcname=f.name)
print(json.dumps({'archive':str(build/'OldyChat-0.6.5-server.tar.gz'),'apk_sha256':manifest['sha256']}))
