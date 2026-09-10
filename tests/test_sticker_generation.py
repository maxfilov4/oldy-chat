import base64,io,json,os,sqlite3,sys,threading,time,unittest,uuid,hashlib
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
from PIL import Image
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'server'))
import sticker_generation as sg
from sticker_fixture import sheet

class Error(Exception):
 def __init__(self,status,code):self.status=status;self.code=code

class StickerGenerationTest(unittest.TestCase):
 def setUp(self):
  self.db=sqlite3.connect(':memory:',check_same_thread=False)
  self.s=SimpleNamespace(DB=self.db,LOCK=threading.RLock(),CHANNEL_KEY=os.urandom(32),Problem=Error,rate=lambda *a:None)
  self.env=patch.dict(os.environ,{'OLDY_STICKER_OPENAI_KEY':'test-fixture-only','OLDY_STICKER_USERS':'alice','OLDY_STICKER_DAILY_LIMIT':'10'});self.env.start();sg.init(self.s)
  photo=io.BytesIO();Image.new('RGB',(512,512),'#abcdef').save(photo,format='JPEG');self.photo=base64.b64encode(photo.getvalue()).decode()
 def tearDown(self):self.env.stop();self.db.close()
 def request(self,**changes):return dict(id=str(uuid.uuid4()),image=self.photo,action='wave',consent_version=1,**changes)
 def done(self,jid):
  end=time.monotonic()+5
  while time.monotonic()<end:
   result=sg.public(self.s,'alice',jid)
   if result['state']!='generating':return result
   time.sleep(.03)
  self.fail('Generation worker did not finish')
 def test_real_frame_encoding_and_encrypted_account_scoped_job(self):
  request=self.request()
  with patch.object(sg,'render_sheet',return_value=sheet()) as provider:
   first=sg.api(self.s,'/stickers/generate',True,request,'alice');result=self.done(first['id']);self.assertEqual(result['state'],'ready',result)
   duplicate=sg.api(self.s,'/stickers/generate',True,request,'alice');self.assertEqual(duplicate['image'],result['image']);self.assertEqual(provider.call_count,1)
  raw=base64.b64decode(result['image']);self.assertEqual(hashlib.sha256(raw).hexdigest(),result['sha256']);self.assertLessEqual(len(raw),350000)
  with Image.open(io.BytesIO(raw)) as animation:self.assertEqual(animation.n_frames,6);self.assertLessEqual(animation.width,512)
  stored=self.db.execute('SELECT output FROM sticker_jobs').fetchone()[0];self.assertNotIn(b'RIFF',stored);self.assertNotIn(self.photo.encode(),stored)
  with self.assertRaises(Error) as e:sg.public(self.s,'bobby',request['id'])
  self.assertEqual(e.exception.status,404)
  with self.assertRaises(Error) as e:sg.api(self.s,'/stickers/generate',True,dict(request,action='yes'),'alice')
  self.assertEqual(e.exception.status,409)
 def test_disabled_or_unconsented_requests_never_reach_provider(self):
  with patch.object(sg,'render_sheet') as provider:
   with self.assertRaises(Error) as e:sg.api(self.s,'/stickers/generate',True,self.request(),'bobby')
   self.assertEqual(e.exception.status,503)
   for payload in [dict(self.request(),consent_version=False),dict(self.request(),url='https://attacker.example'),dict(self.request(),action='shell')]:
    with self.assertRaises(Error):sg.api(self.s,'/stickers/generate',True,payload,'alice')
   provider.assert_not_called()
 def test_quota_is_persistent_and_provider_error_has_no_secrets(self):
  with patch.object(sg,'render_sheet',side_effect=sg.GenerationError('PROVIDER_ACCESS')):
   for _ in range(3):
    req=self.request();sg.api(self.s,'/stickers/generate',True,req,'alice');result=self.done(req['id']);self.assertEqual(result['error'],'PROVIDER_ACCESS');self.assertNotIn('image',result)
   with self.assertRaises(Error) as e:sg.api(self.s,'/stickers/generate',True,self.request(),'alice')
   self.assertEqual(e.exception.code,'STICKER_DAILY_LIMIT')
 def test_static_or_opaque_sheet_is_rejected(self):
  for color in ((10,30,20,255),(0,0,0,0)):
   buffer=io.BytesIO();Image.new('RGBA',(1536,1024),color).save(buffer,format='PNG')
   with self.assertRaises(sg.GenerationError):sg.animation(buffer.getvalue())
 def test_expired_result_is_removed_without_resetting_daily_quota(self):
  with patch.object(sg,'render_sheet',return_value=sheet()):
   req=self.request();sg.api(self.s,'/stickers/generate',True,req,'alice');self.done(req['id'])
  self.db.execute('UPDATE sticker_jobs SET expires=0');self.db.commit();sg.cleanup(self.s)
  self.assertEqual(sg.public(self.s,'alice',req['id'])['state'],'expired');self.assertIsNone(self.db.execute('SELECT output FROM sticker_jobs').fetchone()[0]);self.assertEqual(self.db.execute('SELECT COUNT(*) FROM sticker_jobs').fetchone()[0],1)
 def test_image_input_is_reencoded_and_bounded(self):
  raw=sg.sanitized_image(self.photo)
  with Image.open(io.BytesIO(raw)) as image:self.assertEqual(image.format,'JPEG');self.assertFalse(image.getexif())
  for invalid in ['https://example.com/photo','*'*1500000,'AAAA']:
   with self.assertRaises(sg.GenerationError):sg.sanitized_image(invalid)
