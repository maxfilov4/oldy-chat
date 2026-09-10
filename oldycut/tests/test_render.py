import copy,json,math,os,struct,subprocess,tempfile,unittest
from pathlib import Path
from PIL import Image,ImageStat
from oldycut.engine import Runner,Renderer,probe,thumbnail,Cancelled,ffmpeg_path,color_preview
from oldycut.model import Project,Clip,Overlay,Export

def make_sample(folder):
    r=Runner();folder=Path(folder);folder.mkdir(parents=True,exist_ok=True)
    paths=[]
    for i,color in enumerate(['red','blue']):
        out=folder/f'Сцена {i+1} — обзор.mp4'
        r.run(['-y','-f','lavfi','-i',f'color={color}:s=320x180:r=30:d=3','-f','lavfi','-i',f'sine=frequency={440+i*220}:sample_rate=48000:duration=3','-c:v','libx264','-preset','ultrafast','-pix_fmt','yuv420p','-c:a','aac','-shortest',out]);paths.append(out)
    moving=folder/'Движение.mov';r.run(['-y','-f','lavfi','-i','testsrc2=s=180x320:r=25:d=2','-c:v','libx264','-preset','ultrafast','-pix_fmt','yuv420p',moving]);paths.append(moving)
    still=folder/'Графика.png';Image.new('RGB',(400,200),(25,185,125)).save(still)
    media=[probe(path,r) for path in paths+[still]]
    for m in media:m.thumb=thumbnail(m,folder/'thumbs',r)
    return media

class RenderTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):cls.tmp=tempfile.TemporaryDirectory();cls.folder=Path(cls.tmp.name);cls.media=make_sample(cls.folder)
    @classmethod
    def tearDownClass(cls):cls.tmp.cleanup()
    def frame(self,path,at):
        out=self.folder/'frame.png';Runner().run(['-y','-ss',at,'-i',path,'-frames:v','1',out]);return Image.open(out).convert('RGB').copy()
    def test_real_transition_and_audio(self):
        p=Project(media=self.media,clips=[Clip(self.media[0].id,0,3),Clip(self.media[1].id,0,3,transition='fade',transition_seconds=.5)],export=Export(width=320,height=180,normalize_audio=False))
        out=self.folder/'joined.mp4';Renderer().render(p,out);m=probe(out)
        self.assertEqual((m.width,m.height),(320,180));self.assertTrue(m.audio);self.assertAlmostEqual(m.duration,5.5,delta=.08)
        before=ImageStat.Stat(self.frame(out,1)).mean;after=ImageStat.Stat(self.frame(out,4)).mean;bridge=ImageStat.Stat(self.frame(out,2.75)).mean
        self.assertGreater(before[0],200);self.assertGreater(after[2],200);self.assertGreater(bridge[0],50);self.assertGreater(bridge[2],50)
        pcm=self.folder/'sound.pcm';Runner().run(['-y','-i',out,'-vn','-ac','1','-ar','8000','-f','s16le',pcm]);values=struct.unpack('<'+'h'*(pcm.stat().st_size//2),pcm.read_bytes());self.assertGreater(max(values),500)
        # Tone still changes at the transition: audio must not be a held/empty track.
        def crossings(a,b):
            chunk=values[int(a*8000):int(b*8000)];return sum(x<0<=y for x,y in zip(chunk,chunk[1:]))/(b-a)
        self.assertAlmostEqual(crossings(.5,1.5),440,delta=4);self.assertAlmostEqual(crossings(3.5,4.5),660,delta=4)
    def test_overlay_color_and_no_audio(self):
        c=Clip(self.media[2].id,0,2);c.grade.saturation=0;c.overlays=[Overlay('35 Вт / 1200p',.1,1.8,position='top')]
        p=Project(media=self.media,clips=[c],export=Export(width=180,height=320,normalize_audio=False));out=self.folder/'portrait.mp4';Renderer().render(p,out);m=probe(out)
        self.assertEqual((m.width,m.height),(180,320));self.assertTrue(m.audio);self.assertAlmostEqual(m.duration,2,delta=.08)
        a=self.frame(out,.6);b=self.frame(out,1.3);self.assertNotEqual(a.tobytes(),b.tobytes())
        # Cyan overlay survives and source below it becomes grayscale.
        self.assertTrue(any(g>r+40 and b>r+40 for r,g,b in a.crop((0,0,180,140)).getdata()))
        stats=ImageStat.Stat(a.crop((30,190,150,280))).mean;self.assertLess(max(stats)-min(stats),8)
    def test_image_speed_and_cover(self):
        p=Project(media=self.media,clips=[Clip(self.media[3].id,0,1),Clip(self.media[2].id,0,2,speed=2)],export=Export(width=320,height=180,fit='cover',normalize_audio=False))
        out=self.folder/'photo-speed.mp4';Renderer().render(p,out);self.assertAlmostEqual(probe(out).duration,2,delta=.08)
        frame=self.frame(out,.4);mean=ImageStat.Stat(frame).mean;self.assertGreater(mean[1],160)
    def test_destination_protected_and_cancellation(self):
        p=Project(media=self.media,clips=[Clip(self.media[0].id,0,1)]);path=self.folder/'existing.mp4';path.write_bytes(b'original')
        with self.assertRaises(ValueError):Renderer().render(p,self.media[0].path)
        runner=Runner();runner.cancel()
        with self.assertRaises(Cancelled):Renderer(runner).render(p,path)
        self.assertEqual(path.read_bytes(),b'original')
    def test_codec_container_and_bitrate(self):
        p=Project(media=self.media,clips=[Clip(self.media[2].id,0,1)],export=Export(container='mov',codec='hevc',width=320,height=180,bitrate_mbps=1,normalize_audio=False))
        out=self.folder/'hevc.mov';Renderer().render(p,out);m=probe(out);self.assertAlmostEqual(m.duration,1,delta=.08)
        report=Runner().run(['-i',out],allow_failure=True);self.assertIn('hevc',report.lower())
    def test_processed_color_frame(self):
        c=Clip(self.media[0].id,0,1);c.grade.saturation=0;out=color_preview(self.media[0],c,self.folder/'color');mean=ImageStat.Stat(Image.open(out)).mean;self.assertLess(max(mean)-min(mean),5)
    def test_exposure_extremes_and_music_mix(self):
        c=Clip(self.media[0].id,0,1);c.grade.exposure=2;c.grade.temperature=.5;c.grade.sharpen=2;c.grade.denoise=6;c.grade.vignette=True
        music=self.folder/'music.wav';Runner().run(['-y','-f','lavfi','-i','sine=frequency=200:sample_rate=48000:duration=2',music])
        p=Project(media=self.media,clips=[c],export=Export(width=320,height=180,music=str(music),normalize_audio=True));out=self.folder/'graded-music.mp4';Renderer().render(p,out);self.assertTrue(probe(out).audio);self.assertAlmostEqual(probe(out).duration,1,delta=.08)

if __name__=='__main__':unittest.main()
