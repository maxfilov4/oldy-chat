import base64,concurrent.futures,importlib.util,json,os,sqlite3,tempfile,threading,time,unittest,urllib.request,urllib.error,uuid
from pathlib import Path
from cryptography.hazmat.primitives.asymmetric import rsa,ec
from cryptography.hazmat.primitives.serialization import Encoding,PublicFormat

class RelayTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  cls.temp=tempfile.TemporaryDirectory();os.environ['OLDY_DATA']=cls.temp.name
  spec=importlib.util.spec_from_file_location('relay',Path(__file__).resolve().parents[1]/'server/server.py');cls.mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(cls.mod)
  cls.mod.init_db();cls.server=cls.mod.Relay(('127.0.0.1',0),cls.mod.Handler);threading.Thread(target=cls.server.serve_forever,daemon=True).start();cls.url='http://127.0.0.1:'+str(cls.server.server_port)
  enc=rsa.generate_private_key(public_exponent=65537,key_size=3072).public_key();sig=ec.generate_private_key(ec.SECP256R1()).public_key()
  cls.keys={k:base64.b64encode(v.public_bytes(Encoding.DER,PublicFormat.SubjectPublicKeyInfo)).decode() for k,v in [('enc',enc),('sig',sig)]}
  cls.tokens={}
  for nick in ('alice','bobby','eve_test'):
   status,data=cls.request('/register',{'nick':nick,'password':'correct horse battery','name':nick,**cls.keys});assert status==200,data;cls.tokens[nick]=data['token']
 @classmethod
 def tearDownClass(cls):cls.server.shutdown();cls.server.server_close();cls.mod.DB.close();cls.temp.cleanup()
 @classmethod
 def request(cls,path,body=None,token=''):
  req=urllib.request.Request(cls.url+path,data=None if body is None else json.dumps(body).encode(),headers={'Content-Type':'application/json','Authorization':'Bearer '+token})
  try:
   with urllib.request.urlopen(req,timeout=30) as r:return r.status,json.load(r)
  except urllib.error.HTTPError as e:return e.code,json.load(e)
 def test_auth_and_private_directory(self):
  self.assertEqual(self.request('/me')[0],401)
  self.assertEqual(self.request('/login',{'nick':'alice','password':'wrong password'})[0],401)
  code,result=self.request('/login',{'nick':'alice','password':'correct horse battery'});self.assertEqual(code,200);self.assertNotEqual(result['token'],self.tokens['alice'])
  self.assertEqual(self.request('/me',token=result['token'])[1]['nick'],'alice')
  self.request('/logout',{},result['token']);self.assertEqual(self.request('/me',token=result['token'])[0],401)
 def envelope(self):return {'v':1,'id':str(uuid.uuid4()),'from':'alice','to':'bobby','time':int(time.time()*1000),'body':base64.b64encode(b'opaque ciphertext').decode(),'iv':base64.b64encode(os.urandom(12)).decode(),'key':base64.b64encode(os.urandom(384)).decode(),'signature':base64.b64encode(os.urandom(70)).decode()}
 def test_offline_never_persists_envelope(self):
  e=self.envelope();code,_=self.request('/send',e,self.tokens['alice']);self.assertEqual(code,409);self.assertFalse(self.mod.INFLIGHT)
  rows=self.mod.DB.execute("SELECT name FROM sqlite_master WHERE type='table'").fetchall();self.assertEqual({r[0] for r in rows},{'users','sessions','profiles','rooms','members'})
  for f in Path(self.temp.name).rglob('*'):
   if f.is_file():self.assertNotIn(e['body'].encode(),f.read_bytes())
 def test_delivery_requires_recipient_ack(self):
  e=self.envelope()
  with concurrent.futures.ThreadPoolExecutor() as pool:
   receive=pool.submit(self.request,'/poll',None,self.tokens['bobby'])
   deadline=time.monotonic()+3
   while not self.mod.POLLING['bobby'] and time.monotonic()<deadline:time.sleep(.01)
   send=pool.submit(self.request,'/send',e,self.tokens['alice'])
   code,r=receive.result();self.assertEqual(code,200);self.assertEqual(r['messages'][0],e)
   self.request('/ack',{'from':'alice','id':e['id']},self.tokens['eve_test']);self.assertFalse(send.done())
   self.request('/ack',{'from':'alice','id':e['id']},self.tokens['bobby']);self.assertEqual(send.result(),(200,{'delivered':True}))
  self.assertFalse(self.mod.INFLIGHT)
 def test_sender_spoof_and_plaintext_fields_rejected(self):
  e=self.envelope();self.assertEqual(self.request('/send',e,self.tokens['eve_test'])[0],400)
  e['text']='must never reach storage';self.assertEqual(self.request('/send',e,self.tokens['alice'])[0],400)
 def test_room_membership_and_owner_permissions(self):
  code,r=self.request('/room/create',{'title':'Friends','kind':'group','members':['bobby']},self.tokens['alice']);self.assertEqual(code,200)
  rid=r['id'];self.assertEqual(self.request('/room/'+rid,token=self.tokens['eve_test'])[0],404)
  self.assertEqual(self.request('/room/update',{'id':rid,'title':'hijacked'},self.tokens['bobby'])[0],403)
  self.assertEqual(self.request('/room/update',{'id':rid,'members':['alice','eve_test']},self.tokens['alice'])[0],200)
  self.assertEqual(self.request('/room/'+rid,token=self.tokens['bobby'])[0],404)
  self.assertEqual(self.request('/room/'+rid,token=self.tokens['eve_test'])[0],200)
 def test_profile_presets_and_private_avatar_api(self):
  code,p=self.request('/profile',{'avatar':'preset:8','bio':'Hello'},self.tokens['alice']);self.assertEqual(code,200);self.assertEqual(p['avatar'],'preset:8')
  code,p=self.request('/profile',{'name':'Alice new'},self.tokens['alice']);self.assertEqual(code,200);self.assertEqual(p['avatar'],'preset:8');self.assertEqual(p['bio'],'Hello')
  self.assertEqual(self.request('/profile',{'avatar':'file:/etc/passwd'},self.tokens['alice'])[0],400)
  self.assertEqual(self.request('/avatar/'+'0'*64)[0],401)
 def test_avatar_reencoded_and_invalid_image_rejected(self):
  from PIL import Image
  import io
  out=io.BytesIO();Image.new('RGB',(32,32),'red').save(out,format='PNG')
  code,p=self.request('/profile',{'photo':base64.b64encode(out.getvalue()).decode()},self.tokens['bobby']);self.assertEqual(code,200);self.assertTrue(p['avatar'].startswith('photo:'))
  code,r=self.request('/avatar/'+p['avatar'][6:],token=self.tokens['alice']);self.assertEqual(code,200)
  image=Image.open(io.BytesIO(base64.b64decode(r['photo'])));self.assertEqual(image.format,'JPEG')
  self.assertEqual(self.request('/profile',{'photo':base64.b64encode(b'not an image').decode()},self.tokens['bobby'])[0],400)
 def test_password_and_token_not_stored_verbatim(self):
  row=self.mod.DB.execute('SELECT password FROM users WHERE nick=?',('alice',)).fetchone();self.assertNotEqual(row[0],'correct horse battery')
  tokens=[r[0] for r in self.mod.DB.execute('SELECT hash FROM sessions')];self.assertNotIn(self.tokens['alice'],tokens)

if __name__=='__main__':unittest.main()
