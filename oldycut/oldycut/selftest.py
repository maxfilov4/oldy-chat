"""Runs in the frozen app: exercises bundled FFmpeg, fonts and Qt without API."""
import json,platform,sys
from pathlib import Path
from .engine import Runner,probe,thumbnail,Renderer,ffmpeg_path
from .model import Project,Clip,Overlay,Export
def run(folder):
    from PySide6.QtWidgets import QApplication
    from .ui import MainWindow,STYLE
    folder=Path(folder);folder.mkdir(parents=True,exist_ok=True);r=Runner();src=folder/'source.mp4'
    r.run(['-y','-f','lavfi','-i','testsrc2=s=320x180:r=30:d=1.5','-f','lavfi','-i','sine=frequency=440:duration=1.5','-c:v','libx264','-preset','ultrafast','-pix_fmt','yuv420p','-c:a','aac','-shortest',src])
    m=probe(src);m.thumb=thumbnail(m,folder/'thumbs');c=Clip(m.id,0,1.5,overlays=[Overlay('35 Вт / 1200p',.1,1.3)])
    p=Project(name='Проверка сборки',media=[m],clips=[c],export=Export(width=320,height=180));out=folder/'render.mp4';Renderer().render(p,out)
    check=probe(out);assert check.width==320 and check.height==180 and check.audio and check.duration>1.4
    app=QApplication.instance() or QApplication([]);app.setStyle('Fusion');app.setStyleSheet(STYLE);w=MainWindow(restore=False);w.project=p;w.refresh();w.show();app.processEvents();w.grab().save(str(folder/'app.png'));w.close()
    report={'platform':platform.platform(),'architecture':platform.machine(),'ffmpeg':Path(ffmpeg_path()).name,'render_size':out.stat().st_size,'duration':check.duration,'qt_ui':'ok','api':'not called'}
    (folder/'result.json').write_text(json.dumps(report,indent=2));print(json.dumps(report),flush=True);return 0
