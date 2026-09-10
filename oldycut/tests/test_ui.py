import os,tempfile,unittest
os.environ.setdefault('QT_QPA_PLATFORM','offscreen')
from PySide6.QtWidgets import QApplication,QDialog
from PySide6.QtTest import QTest
from PySide6.QtCore import Qt
from oldycut.ui import MainWindow,STYLE,OverlayDialog
from oldycut.model import Media,Clip,Project

class UITests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):cls.app=QApplication.instance() or QApplication([]);cls.app.setStyleSheet(STYLE)
    def setUp(self):
        self.w=MainWindow(restore=False);m=Media('/tmp/test.mp4',20,1920,1080,audio=False);self.w.project=Project(media=[m],clips=[Clip(m.id,0,10),Clip(m.id,10,20)]);self.w.refresh();self.w.show();self.app.processEvents()
    def tearDown(self):self.w.close();self.app.processEvents()
    def test_trim_undo_restore_and_reorder(self):
        self.w.trim_in.setValue(2);self.w.apply_clip();self.assertEqual(self.w.project.clips[0].start,2);self.w.undo();self.assertEqual(self.w.project.clips[0].start,0)
        first=self.w.project.clips[0].id;self.w.move_clip(1);self.assertEqual(self.w.project.clips[1].id,first);self.w.toggle_clip();self.assertEqual(len(self.w.project.active()),1)
        self.w.show_removed.setChecked(True);self.assertEqual(self.w.timeline.count(),2)
    def test_export_portrait_mapping(self):
        self.w.aspect.setCurrentIndex(1);self.w.read_export();e=self.w.project.export;self.assertEqual((e.width,e.height),(1080,1920));self.assertEqual(e.container,'mp4')
    def test_overlay_dialog(self):
        d=OverlayDialog(self.w.project.clips[0],self.w.project.media,self.w);d.text.setPlainText('35 Вт');d.finish();self.assertEqual(d.result(),QDialog.DialogCode.Accepted);self.assertEqual(d.overlays[0].text,'35 Вт')

if __name__=='__main__':unittest.main()
