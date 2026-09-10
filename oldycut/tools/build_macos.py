"""Reproducible Intel Mac app/DMG build. Run on macOS with Python 3.11 x86_64."""
import hashlib,importlib.metadata,json,os,platform,plistlib,shutil,subprocess,sys
from pathlib import Path
root=Path(__file__).resolve().parents[1];os.chdir(root)
if sys.platform!='darwin' or platform.machine()!='x86_64':raise SystemExit('Build requires an Intel macOS runner')
subprocess.run([sys.executable,'tools/prepare_assets.py'],check=True)
os.environ['MACOSX_DEPLOYMENT_TARGET']='12.0'
from PySide6.QtSvg import QSvgRenderer
from PySide6.QtGui import QImage,QPainter
from PySide6.QtCore import Qt
iconset=root/'build/OldyCut.iconset';iconset.mkdir(parents=True,exist_ok=True)
svg=QSvgRenderer(str(root/'assets/oldycut.svg'))
for size in [16,32,128,256,512]:
    for factor in [1,2]:
        image=QImage(size*factor,size*factor,QImage.Format.Format_ARGB32);image.fill(Qt.GlobalColor.transparent);p=QPainter(image);svg.render(p);p.end();image.save(str(iconset/f'icon_{size}x{size}{"@2x" if factor==2 else ""}.png'))
subprocess.run(['iconutil','-c','icns',str(iconset),'-o','assets/OldyCut.icns'],check=True)
# Bundle notices installed by Python distributions, as well as source locations.
licenses=root/'assets/licenses';licenses.mkdir(exist_ok=True)
for package in ['PySide6','shiboken6','Pillow','requests','keyring','imageio-ffmpeg','certifi','charset-normalizer','idna','urllib3']:
    try:
        dist=importlib.metadata.distribution(package)
        for file in dist.files or []:
            if any(s in str(file).lower() for s in ['license','copying','copyright']) and dist.locate_file(file).is_file():
                out=licenses/(package+'-'+Path(file).name)
                if not out.exists():shutil.copyfile(dist.locate_file(file),out)
    except importlib.metadata.PackageNotFoundError:pass
subprocess.run([sys.executable,'-m','PyInstaller','--clean','--noconfirm','--windowed','--onedir','--name','Oldy Cut','--osx-bundle-identifier','com.oldy.cut','--target-architecture','x86_64','--icon','assets/OldyCut.icns','--add-data','assets:assets','--collect-all','imageio_ffmpeg','--hidden-import','keyring.backends.macOS','--hidden-import','PySide6.QtSvg','--exclude-module','matplotlib','--exclude-module','tkinter','main.py'],check=True)
app=root/'dist/Oldy Cut.app';info=app/'Contents/Info.plist'
with info.open('rb') as f:pl=plistlib.load(f)
pl.update(CFBundleShortVersionString='0.2.0',CFBundleVersion='2',LSMinimumSystemVersion='12.0',NSHighResolutionCapable=True,NSDocumentsFolderUsageDescription='Выбор исходников и сохранение проектов Oldy Cut.',NSDownloadsFolderUsageDescription='Выбор видео из загрузок.',NSRemovableVolumesUsageDescription='Монтаж исходников с внешнего диска.',CFBundleDocumentTypes=[{'CFBundleTypeName':'Oldy Cut Project','CFBundleTypeExtensions':['oldycut'],'CFBundleTypeRole':'Editor'}])
with info.open('wb') as f:plistlib.dump(pl,f)
subprocess.run(['codesign','--force','--deep','--sign','-',str(app)],check=True)
subprocess.run(['codesign','--verify','--deep','--strict','--verbose=2',str(app)],check=True)
from audit_macos import audit
audit(app,root/'build/bundle-check/compatibility.json')
print('Bundled smoke test',flush=True)
subprocess.run([str(app/'Contents/MacOS/Oldy Cut'),'--self-test',str(root/'build/bundle-check')],env={**os.environ,'QT_QPA_PLATFORM':'offscreen'},check=True,timeout=180)
stage=root/'build/dmg';stage.mkdir(exist_ok=True);shutil.copytree(app,stage/'Oldy Cut.app',dirs_exist_ok=True,symlinks=True)
os.symlink('/Applications',stage/'Applications')
shutil.copyfile(root/'README-RU.md',stage/'Как начать.txt')
out=root/'release';out.mkdir(exist_ok=True)
dmg=out/'OldyCut-0.2.0-Intel.dmg'
subprocess.run(['hdiutil','create','-volname','Oldy Cut','-srcfolder',str(stage),'-ov','-format','UDZO',str(dmg)],check=True)
shutil.copyfile(root/'README-RU.md',out/'OldyCut-Readme.txt')
(out/'SHA256SUMS.txt').write_text(hashlib.sha256(dmg.read_bytes()).hexdigest()+'  '+dmg.name+'\n')
print('READY:',dmg,dmg.stat().st_size,flush=True)
