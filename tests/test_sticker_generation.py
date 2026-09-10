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

class StickerReleaseTest(unittest.TestCase):
 def test_upgrade_keeps_key_model_limits_and_enables_new_users(self):
  import importlib.util,tempfile,stat
  spec=importlib.util.spec_from_file_location('configure_stickers',Path(__file__).resolve().parents[1]/'server'/'configure-stickers.py');setup=importlib.util.module_from_spec(spec);spec.loader.exec_module(setup)
  with tempfile.TemporaryDirectory() as folder:
   path=Path(folder)/'stickers.env'
   values={'OLDY_STICKER_OPENAI_KEY':'fixture-not-a-real-key','OLDY_STICKER_USERS':'oldy','OLDY_STICKER_MODEL':'gpt-image-1','OLDY_STICKER_DAILY_LIMIT':'17'}
   setup.save_settings(path,values);self.assertTrue(setup.enable_all(path));after=setup.read_settings(path)
   self.assertEqual(after,dict(values,OLDY_STICKER_USERS='*'));self.assertEqual(stat.S_IMODE(path.stat().st_mode),0o600)
   with patch.dict(os.environ,after):
    for nick in ('oldy','alice','new_future_user'):self.assertTrue(sg.enabled(nick))
   self.assertFalse(setup.enable_all(Path(folder)/'missing.env'));self.assertFalse((Path(folder)/'missing.env').exists())
 def test_http_errors_hide_body_and_distinguish_billing_key_and_capacity(self):
  import urllib.error
  for status,code,expected in [(429,'insufficient_quota','PROVIDER_BILLING'),(400,'billing_hard_limit_reached','PROVIDER_BILLING'),(401,'invalid_api_key','PROVIDER_CREDENTIALS'),(429,'rate_limit_exceeded','PROVIDER_LIMIT'),(404,'model_not_found','PROVIDER_MODEL'),(400,'content_policy_violation','PROVIDER_REJECTED'),(500,'unknown','PROVIDER_UNAVAILABLE')]:
   body=json.dumps({'error':{'code':code,'message':'PRIVATE fixture photo-url or credential'}}).encode()
   error=urllib.error.HTTPError('https://api.openai.com/v1/images/edits',status,'error',{},io.BytesIO(body))
   self.assertEqual(sg.provider_error(error),expected)
 def test_old_job_schema_migrates_without_losing_owned_results(self):
  db=sqlite3.connect(':memory:');s=SimpleNamespace(DB=db,LOCK=threading.RLock())
  db.execute("CREATE TABLE sticker_jobs(id TEXT PRIMARY KEY,owner TEXT NOT NULL,digest TEXT NOT NULL,state TEXT NOT NULL,error TEXT NOT NULL DEFAULT '',created INTEGER NOT NULL,expires INTEGER NOT NULL,output BLOB)")
  now=int(time.time());db.execute("INSERT INTO sticker_jobs VALUES('existing','alice','digest','failed','PROVIDER_ACCESS',?,?,NULL)",(now,now+3600));db.commit()
  sg.init(s);sg.init(s)
  result=sg.public(s,'alice','existing');self.assertEqual(result['error'],'PROVIDER_ACCESS');self.assertEqual(result['stage'],'queued');self.assertGreaterEqual(result['elapsed_seconds'],0);db.close()
