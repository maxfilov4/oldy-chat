import base64,concurrent.futures,importlib.util,json,os,sqlite3,tempfile,threading,time,unittest,urllib.request,urllib.error,uuid
from pathlib import Path
from cryptography.hazmat.primitives.asymmetric import rsa,ec
from cryptography.hazmat.primitives.serialization import Encoding,PublicFormat
from cryptography.hazmat.primitives import hashes

class RelayTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  cls.temp=tempfile.TemporaryDirectory();os.environ['OLDY_DATA']=cls.temp.name
  spec=importlib.util.spec_from_file_location('relay',Path(__file__).resolve().parents[1]/'server/server.py');cls.mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(cls.mod)
  cls.mod.init_db();cls.server=cls.mod.Relay(('127.0.0.1',0),cls.mod.Handler);threading.Thread(target=cls.server.serve_forever,daemon=True).start();cls.url='http://127.0.0.1:'+str(cls.server.server_port)
  enc=rsa.generate_private_key(public_exponent=65537,key_size=3072).public_key();cls.signing=ec.generate_private_key(ec.SECP256R1());sig=cls.signing.public_key()
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
 def setUp(self):
  self.mod.LIMITS.clear()
  with self.mod.LOCK:
   self.mod.DB.execute("DELETE FROM archive");self.mod.DB.commit()
 def test_auth_and_private_directory(self):
  self.assertEqual(self.request('/me')[0],401)
  self.assertEqual(self.request('/login',{'nick':'alice','password':'wrong password'})[0],401)
  code,result=self.request('/login',{'nick':'alice','password':'correct horse battery'});self.assertEqual(code,200);self.assertNotEqual(result['token'],self.tokens['alice'])
  self.assertEqual(self.request('/me',token=result['token'])[1]['nick'],'alice')
  self.request('/logout',{},result['token']);self.assertEqual(self.request('/me',token=result['token'])[0],401)
 def signed(self,e):
  signed=('oldy-v1\n'+e['id']+'\n'+e['from']+'\n'+e['to']+'\n'+str(e['time'])+'\n'+e['key']+'\n'+e['iv']+'\n'+e['body']).encode()
  e['signature']=base64.b64encode(self.signing.sign(signed,ec.ECDSA(hashes.SHA256()))).decode();return e
 def envelope(self):return self.signed({'v':1,'id':str(uuid.uuid4()),'from':'alice','to':'bobby','time':int(time.time()*1000),'body':base64.b64encode(b'opaque ciphertext').decode(),'iv':base64.b64encode(os.urandom(12)).decode(),'key':base64.b64encode(os.urandom(384)).decode()})
 def test_offline_ciphertext_persists_and_history_is_private(self):
  e=self.envelope();code,result=self.request('/send',e,self.tokens['alice']);self.assertEqual(code,200);self.assertTrue(result['stored']);self.assertFalse(result['delivered']);self.assertFalse(self.mod.INFLIGHT)
  row=self.mod.DB.execute('SELECT envelope FROM archive WHERE owner=?',('bobby',)).fetchone();self.assertEqual(json.loads(row[0]),e);self.assertNotIn('text',json.loads(row[0]))
  self.assertEqual(self.request('/history',token=self.tokens['eve_test'])[1]['items'],[])
  history=self.request('/history',token=self.tokens['bobby'])[1];self.assertEqual(history['items'][0]['envelope'],e);self.assertFalse(history['items'][0]['delivered'])
  self.assertEqual(self.request('/history?after='+str(history['next']),token=self.tokens['bobby'])[1]['items'],[])
  with self.mod.LOCK:self.mod.DB.close();self.mod.init_db()
  self.assertEqual(self.request('/poll',token=self.tokens['bobby'])[1]['messages'],[e])
 def test_delivery_requires_recipient_ack(self):
  e=self.envelope();self.assertEqual(self.request('/send',e,self.tokens['alice']),(200,{'stored':True,'delivered':False}))
  self.assertEqual(self.request('/poll',token=self.tokens['bobby'])[1]['messages'],[e])
  self.request('/ack',{'from':'alice','id':e['id']},self.tokens['eve_test'])
  self.assertEqual(self.request('/receipts',{'ids':[e['id']]},self.tokens['alice'])[1]['delivered'],[])
  self.request('/ack',{'from':'alice','id':e['id']},self.tokens['bobby'])
  self.assertEqual(self.request('/receipts',{'ids':[e['id']]},self.tokens['alice'])[1]['delivered'],[e['id']])
  self.assertEqual(self.request('/receipts',{'ids':[e['id']]},self.tokens['eve_test'])[1]['delivered'],[])
  self.assertEqual(self.request('/send',e,self.tokens['alice']),(200,{'stored':True,'delivered':True}))
  altered=dict(e);altered['body']=base64.b64encode(b'another ciphertext').decode();self.signed(altered)
  self.assertEqual(self.request('/send',altered,self.tokens['alice'])[0],409)
 def test_tampered_ciphertext_and_signature_rejected(self):
  e=self.envelope();e['body']=base64.b64encode(b'tampered ciphertext').decode()
  self.assertEqual(self.request('/send',e,self.tokens['alice'])[0],400)
  self.assertEqual(self.mod.DB.execute('SELECT COUNT(*) FROM archive').fetchone()[0],0)
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

 def test_handles_are_global_case_insensitive_and_atomic(self):
  owner=self.tokens['alice']
  self.assertFalse(self.request('/handles?handle=ALICE')[1]['available'])
  body={'title':'New room','handle':'same_handle','kind':'group','members':[],'public':True}
  with concurrent.futures.ThreadPoolExecutor() as pool:
   results=list(pool.map(lambda _:self.request('/room/create',body,owner),range(2)))
  self.assertEqual(sorted(x[0] for x in results),[200,409])
  rid=next(x[1]['id'] for x in results if x[0]==200)
  self.assertEqual(self.request('/room/update',{'id':rid,'handle':'BOBBY'},owner)[0],409)
  self.assertEqual(self.request('/room/'+rid,token=owner)[1]['handle'],'same_handle')
  self.assertEqual(self.request('/register',{'nick':'same_handle','password':'correct password',**self.keys})[0],409)
  self.assertFalse(self.request('/handles?handle=SAME_HANDLE')[1]['available'])
 def test_search_join_owner_ban_and_private_rooms(self):
  owner=self.tokens['alice'];member=self.tokens['bobby'];other=self.tokens['eve_test']
  code,r=self.request('/room/create',{'title':'Gaming news','kind':'channel','handle':'oldy_news','public':True,'members':['bobby']},owner)
  self.assertEqual(code,200);rid=r['id']
  self.assertEqual(len(self.request('/search?q=%40oldy_news',token=other)[1]['rooms']),1)
  self.assertEqual(self.request('/room/ban',{'id':rid,'target':'eve_test','banned':True},member)[0],403)
  self.assertEqual(self.request('/room/join',{'id':rid},other)[0],200)
  self.assertEqual(self.request('/room/ban',{'id':rid,'target':'eve_test','banned':True},owner)[0],200)
  self.assertEqual(self.request('/room/join',{'id':rid},other)[0],403)
  self.assertEqual(self.request('/room/update',{'id':rid,'members':['alice','eve_test']},owner)[0],403)
  self.assertEqual(self.request('/room/ban',{'id':rid,'target':'eve_test','banned':False},owner)[0],200)
  self.assertEqual(self.request('/room/join',{'id':rid},other)[0],200)
  code,private=self.request('/room/create',{'title':'Very private','kind':'group','handle':'secret_room','members':[]},owner)
  self.assertEqual(code,200)
  self.assertEqual(self.request('/search?q=secret_room',token=member)[1]['rooms'],[])
  self.assertEqual(self.request('/room/join',{'id':private['id']},member)[0],404)
 def test_direct_blocks_do_not_ban_group_members(self):
  owner=self.tokens['alice'];member=self.tokens['bobby'];e=self.envelope()
  self.assertEqual(self.request('/blocks',{'target':'alice','blocked':True},member)[0],200)
  self.assertEqual(self.request('/send',e,owner)[0],403)
  self.assertEqual(self.request('/typing',{'to':'bobby'},owner)[0],403)
  code,r=self.request('/room/create',{'title':'Still together','kind':'group','members':['bobby']},owner)
  self.assertEqual(code,200)
  e['room']=r['id'];e['action']='publish'
  self.assertEqual(self.request('/send',e,owner)[0],200) # Personal block does not apply to this group.
  self.assertEqual(self.request('/room/'+r['id'],token=member)[0],200)
  self.request('/blocks',{'target':'alice','blocked':False},member)
 def test_channel_subscriber_can_react_but_not_publish_or_pin(self):
  code,r=self.request('/room/create',{'title':'Channel','kind':'channel','members':['bobby']},self.tokens['alice'])
  self.assertEqual(code,200)
  e=self.envelope();e.update({'from':'bobby','to':'alice','room':r['id'],'action':'publish'});self.signed(e)
  self.assertEqual(self.request('/send',e,self.tokens['bobby'])[0],403)
  e['action']='pin';self.assertEqual(self.request('/send',e,self.tokens['bobby'])[0],403)
  e['action']='reaction';self.assertEqual(self.request('/send',e,self.tokens['bobby'])[0],200)
 def test_email_login_uniqueness_privacy_and_verification(self):
  body={'nick':'email_user','email':'PLAYER@Example.org','password':'correct password',**self.keys}
  code,r=self.request('/register',body);self.assertEqual(code,200);token=r['token']
  self.assertEqual(r['user']['email'],'player@example.org');self.assertFalse(r['user']['email_verified'])
  self.assertEqual(self.request('/login',{'nick':'PLAYER@example.org','password':'correct password'})[0],200)
  self.assertNotIn('email',self.request('/user/email_user',token=self.tokens['bobby'])[1])
  body['nick']='other_email';self.assertEqual(self.request('/register',body)[0],409)
  self.assertTrue(self.request('/handles?handle=other_email')[1]['available'])
  self.assertEqual(self.request('/email/request',{},token)[0],503)
  import hashlib
  with self.mod.LOCK:
   self.mod.DB.execute('INSERT INTO email_codes VALUES(?,?,?,0)',('email_user',hashlib.sha256(b'email_user:123456').hexdigest(),int(time.time())+60));self.mod.DB.commit()
  self.assertEqual(self.request('/email/verify',{'code':'111111'},token)[0],400)
  self.assertEqual(self.request('/email/verify',{'code':'123456'},token)[1]['email_verified'],True)
  self.assertEqual(self.request('/email/verify',{'code':'123456'},token)[0],400)
 def test_migration_preserves_account_key_material(self):
  with self.mod.LOCK:
   before=self.mod.DB.execute('SELECT nick,enc,sig,salt,password FROM users ORDER BY nick').fetchall()
   self.mod.DB.close();self.mod.init_db()
   after=self.mod.DB.execute('SELECT nick,enc,sig,salt,password FROM users ORDER BY nick').fetchall()
  self.assertEqual(before,after)
  self.assertEqual(self.request('/me',token=self.tokens['alice'])[0],200)

 def test_optional_updates_only_advertise_available_apk(self):
  self.assertEqual(self.request('/updates')[1],{'available':False,'required':False})
  folder=Path(self.temp.name)/'releases';folder.mkdir(exist_ok=True)
  apk=b'test apk fixture';(folder/'OldyChat-latest.apk').write_bytes(apk)
  import hashlib
  release={'package':'chat.oldy','version_code':4,'version_name':'0.3','sha256':hashlib.sha256(apk).hexdigest(),'size':len(apk),'notes':'Optional update'}
  (folder/'release.json').write_text(json.dumps(release))
  code,info=self.request('/updates');self.assertEqual(code,200);self.assertTrue(info['available']);self.assertFalse(info['required'])
  with urllib.request.urlopen(self.url+info['path']) as response:self.assertEqual(response.read(),apk)
  self.assertEqual(self.request('/login',{'nick':'alice','password':'correct horse battery'})[0],200)
  (folder/'OldyChat-latest.apk').unlink();self.assertFalse(self.request('/updates')[1]['available'])
 def test_legacy_clients_default_to_compatible_protocol(self):
  token=self.tokens['alice']
  self.assertEqual(self.request('/user/bobby',token=token)[1]['protocol'],2)
  self.assertEqual(self.request('/capabilities',{'protocol':3},self.tokens['bobby'])[0],200)
  self.assertEqual(self.request('/user/bobby',token=token)[1]['protocol'],3)
  with self.mod.LOCK:self.mod.DB.execute("DELETE FROM capabilities WHERE nick='bobby'");self.mod.DB.commit()

 def test_encrypted_key_backup_and_self_archive_scope(self):
  alice=self.tokens['alice'];bob=self.tokens['bobby']
  backup=json.dumps({'format':'oldy-backup-v1','salt':base64.b64encode(os.urandom(16)).decode(),'iv':base64.b64encode(os.urandom(12)).decode(),'data':base64.b64encode(os.urandom(128)).decode()})
  self.assertEqual(self.request('/key-backup',{'backup':backup},alice)[0],200)
  self.assertEqual(self.request('/key-backup',token=alice)[1]['backup'],backup)
  self.assertEqual(self.request('/key-backup',token=bob)[0],404)
  self.assertEqual(self.request('/key-backup',{'backup':'private keys in plaintext'},alice)[0],400)
  e=self.envelope();self.assertEqual(self.request('/history/store',{'envelope':e},alice)[0],400)
  e['to']='alice';self.signed(e)
  self.assertEqual(self.request('/history/store',{'envelope':e},alice)[0],200)
  self.assertEqual(self.request('/history/store',{'envelope':e},alice)[0],200)
  self.assertEqual(len(self.request('/history',token=alice)[1]['items']),1)
  self.assertEqual(self.request('/history',token=bob)[1]['items'],[])

 def binary(self,path,body=None,token='',headers=None):
  req=urllib.request.Request(self.url+path,data=body,headers={'Authorization':'Bearer '+token,**(headers or {})})
  try:
   with urllib.request.urlopen(req,timeout=10) as r:return r.status,dict(r.headers),r.read()
  except urllib.error.HTTPError as e:return e.code,dict(e.headers),e.read()

 def test_owner_only_mp4_chunks_resume_range_and_membership(self):
  import hashlib
  from unittest.mock import patch
  owner={'OLDY_OWNER_NICK':'alice','OLDY_OWNER_KEY_HASH':hashlib.sha256(self.keys['sig'].encode()).hexdigest()}
  alice=self.tokens['alice'];bob=self.tokens['bobby'];eve=self.tokens['eve_test']
  rid=self.request('/room/create',{'title':'Video channel','kind':'channel','members':['bobby']},alice)[1]['id']
  group=self.request('/room/create',{'title':'Video group','kind':'group','members':[]},alice)[1]['id']
  # Container header plus random bytes is sufficient to test transport, not decoding.
  raw=b'\x00\x00\x00\x18ftypisom'+os.urandom(3000)
  body={'room':rid,'size':len(raw),'name':'Review.mp4'}
  with patch.dict(os.environ,owner):
   self.assertTrue(self.request('/me',token=alice)[1]['creator_video'])
   self.assertFalse(self.request('/me',token=bob)[1]['creator_video'])
   self.assertEqual(self.request('/videos/start',body,bob)[0],403)
   self.assertEqual(self.request('/videos/start',{**body,'room':group},alice)[0],403)
   self.assertEqual(self.request('/videos/start',{**body,'size':2147483649},alice)[0],400)
   self.assertEqual(self.request('/videos/start',{**body,'name':'Review.exe'},alice)[0],400)
   code,item=self.request('/videos/start',body,alice);self.assertEqual(code,200);vid=item['id']
   path='/video-chunk/'+vid
   self.assertEqual(self.binary(path,raw[:1000],bob,{'X-Upload-Offset':'0'})[0],403)
   self.assertEqual(self.binary(path,b'not an mp4 file',alice,{'X-Upload-Offset':'0'})[0],400)
   self.assertEqual(self.binary(path,raw[:1000],alice,{'X-Upload-Offset':'0'})[0],200)
   self.assertEqual(self.binary(path,raw[:1000],alice,{'X-Upload-Offset':'0'})[0],409)
   self.assertEqual(self.request('/video-status/'+vid,token=alice)[1]['received'],1000)
   self.assertEqual(self.request('/videos/finish',{'id':vid},alice)[0],409)
   self.assertEqual(self.binary(path,raw[1000:],alice,{'X-Upload-Offset':'1000'})[0],200)
   self.assertEqual(self.request('/videos/finish',{'id':vid},alice)[0],200)
   self.assertEqual(self.request('/videos/finish',{'id':vid},alice)[0],200)
   code,headers,part=self.binary('/video-stream/'+vid,token=bob,headers={'Range':'bytes=500-1499'})
   self.assertEqual(code,206);self.assertEqual(part,raw[500:1500]);self.assertEqual(headers['Content-Range'],f'bytes 500-1499/{len(raw)}')
   self.assertEqual(self.binary('/video-stream/'+vid,token=bob)[2],raw)
   self.assertEqual(self.binary('/video-stream/'+vid,token=eve)[0],404)
   self.assertEqual(self.binary('/video-stream/'+vid)[0],401)
   self.assertEqual(self.binary('/video-stream/'+vid,token=bob,headers={'Range':'bytes=99999-'})[0],416)
   self.request('/room/ban',{'id':rid,'target':'bobby','banned':True},alice)
   self.assertEqual(self.binary('/video-stream/'+vid,token=bob)[0],404)
  with patch.dict(os.environ,{**owner,'OLDY_OWNER_KEY_HASH':'0'*64}):
   self.assertEqual(self.request('/videos/start',body,alice)[0],403)

 def test_comments_are_separate_per_post_and_channel_permissions_hold(self):
  alice=self.tokens['alice'];bob=self.tokens['bobby'];eve=self.tokens['eve_test']
  rid=self.request('/room/create',{'title':'Thread test','kind':'channel','members':['bobby']},alice)[1]['id']
  post1=str(uuid.uuid4());post2=str(uuid.uuid4())
  self.assertEqual(self.request('/threads',{'room':rid,'post':post1},bob)[0],404)
  link1=self.request('/threads',{'room':rid,'post':post1},alice)[1]['link']
  link2=self.request('/threads',{'room':rid,'post':post2},alice)[1]['link'];self.assertNotEqual(link1,link2)
  self.assertEqual(self.request('/threads',{'room':rid,'post':post1},bob)[1]['link'],link1)
  self.assertEqual(self.request('/threads',{'room':rid,'post':post1},eve)[0],404)
  e=self.envelope();e.update({'from':'bobby','to':'alice','room':rid,'thread':post1,'action':'comment'});self.signed(e)
  self.assertEqual(self.request('/send',e,bob)[0],200)
  e['action']='publish';self.assertEqual(self.request('/send',e,bob)[0],403)
  e['action']='pin';self.assertEqual(self.request('/send',e,bob)[0],403)
  e['action']='comment';e['thread']=str(uuid.uuid4());self.assertEqual(self.request('/send',e,bob)[0],404)

if __name__=='__main__':unittest.main()
