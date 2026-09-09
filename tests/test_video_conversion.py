"""Exercise the actual fallback codec pipeline, including a portrait Full HD source."""
import importlib.util,json,os,shutil,subprocess,tempfile,unittest,uuid
from pathlib import Path

@unittest.skipUnless(shutil.which('ffmpeg') and shutil.which('ffprobe'),'ffmpeg/ffprobe required')
class ConversionTest(unittest.TestCase):
 def test_portrait_frames_codecs_and_media_cleanup(self):
  with tempfile.TemporaryDirectory() as temporary:
   spec=importlib.util.spec_from_file_location('conversion_relay',Path(__file__).resolve().parents[1]/'server/server.py');relay=importlib.util.module_from_spec(spec);spec.loader.exec_module(relay);relay.ROOT=Path(temporary);relay.init_db()
   folder=relay.ROOT/'videos';folder.mkdir();vid=str(uuid.uuid4());source=folder/(vid+'.mp4')
   try:
    subprocess.run(['ffmpeg','-nostdin','-v','error','-f','lavfi','-i','testsrc2=size=1080x1920:rate=12','-f','lavfi','-i','sine=frequency=700:sample_rate=44100','-t','2','-c:v','libx264','-preset','ultrafast','-threads','1','-c:a','aac','-y',str(source)],check=True,timeout=45)
    with relay.LOCK:
     relay.DB.execute('INSERT INTO videos(id,owner,room,name,size,received,ready) VALUES(?,?,?,?,?,?,1)',(vid,'alice','fixture','portrait.mp4',source.stat().st_size,source.stat().st_size))
     relay.DB.execute("INSERT INTO video_variants(id,state) VALUES(?,'processing')",(vid,));relay.DB.commit();relay.MEDIA_JOBS.add(vid)
    legacy=folder/(vid+'.cover.jpg');legacy.write_bytes(b'old blurry thumbnail')
    cover=relay.make_video_cover(vid);self.assertTrue(cover.is_file())
    from PIL import Image,ImageStat
    with Image.open(cover) as picture:
     self.assertEqual((picture.width,picture.height),(720,1280));self.assertEqual(picture.format,'JPEG');self.assertGreater(max(ImageStat.Stat(picture).stddev),20,'Cover should contain a real video frame')
    self.assertEqual(relay.make_video_cover(vid),cover)
    relay.compatible_video(vid)
    variant=folder/(vid+'.compat.mp4');self.assertTrue(variant.exists());self.assertEqual(relay.DB.execute('SELECT state FROM video_variants WHERE id=?',(vid,)).fetchone()[0],'ready')
    info=json.loads(subprocess.check_output(['ffprobe','-v','error','-show_streams','-of','json',str(variant)]))
    video=next(s for s in info['streams'] if s['codec_type']=='video');audio=next(s for s in info['streams'] if s['codec_type']=='audio')
    self.assertEqual((video['width'],video['height']),(1080,1920));self.assertEqual(video['codec_name'],'h264');self.assertEqual(video['pix_fmt'],'yuv420p');self.assertEqual(audio['codec_name'],'aac')
    frames=subprocess.check_output(['ffmpeg','-nostdin','-v','error','-i',str(variant),'-map','0:v:0','-vf','fps=2,scale=32:32','-f','framemd5','-'],timeout=20).decode().splitlines();hashes=[row.split(',')[-1].strip() for row in frames if row and not row.startswith('#')]
    self.assertGreaterEqual(len(set(hashes)),3,'Output must contain changing frames, not a frozen image')
    with relay.LOCK:relay.DB.execute('INSERT INTO media_garbage VALUES(?)',(vid,));relay.DB.execute('DELETE FROM videos WHERE id=?',(vid,));relay.DB.commit()
    relay.collect_media();self.assertFalse(source.exists());self.assertFalse(variant.exists());self.assertFalse(cover.exists());self.assertFalse(legacy.exists())
   finally:relay.DB.close()
