#!/usr/bin/env python3
"""Oldy Chat beta: authenticated TLS API, encrypted message archive,
ephemeral WebRTC signalling, and owner-only channel MP4 storage.
Message bodies and password-protected key backups stay opaque to the server.
Channel MP4 files are ordinary server files, not end-to-end encrypted.
"""
import io, uuid, base64, hashlib, hmac, json, os, re, secrets, socket, sqlite3, ssl, threading, time
from collections import defaultdict, deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit, parse_qs
from pathlib import Path
from cryptography.hazmat.primitives.serialization import load_der_public_key
from cryptography.hazmat.primitives.asymmetric import rsa, ec
from cryptography.hazmat.primitives import hashes

ROOT = Path(os.environ.get('OLDY_DATA', '/var/lib/oldy-chat'))
LOCK = threading.RLock()
COND = threading.Condition(LOCK)
POLLING = defaultdict(int)
INFLIGHT = {}
TYPING = {}
LIMITS = defaultdict(deque)
NICK = re.compile(r'^[a-z0-9_]{3,24}$')
DB = None
MAX_BODY = 65536

def init_db(path=None):
 global DB
 ROOT.mkdir(parents=True, exist_ok=True, mode=0o700)
 DB = sqlite3.connect(str(path or ROOT/'accounts.sqlite3'), check_same_thread=False)
 DB.execute('PRAGMA journal_mode=WAL')
 DB.executescript('''CREATE TABLE IF NOT EXISTS users(nick TEXT PRIMARY KEY, name TEXT NOT NULL, salt TEXT NOT NULL, password TEXT NOT NULL, enc TEXT NOT NULL, sig TEXT NOT NULL);
 CREATE TABLE IF NOT EXISTS sessions(hash TEXT PRIMARY KEY, nick TEXT NOT NULL, expires INTEGER NOT NULL);''')
 DB.executescript('''CREATE TABLE IF NOT EXISTS profiles(nick TEXT PRIMARY KEY, avatar TEXT NOT NULL DEFAULT 'preset:0', bio TEXT NOT NULL DEFAULT '');
 CREATE TABLE IF NOT EXISTS rooms(id TEXT PRIMARY KEY, title TEXT NOT NULL, kind TEXT NOT NULL, owner TEXT NOT NULL, avatar TEXT NOT NULL);
 CREATE TABLE IF NOT EXISTS members(room TEXT NOT NULL, nick TEXT NOT NULL, PRIMARY KEY(room,nick));''')
 # Additive migration: keep existing account keys, sessions and room membership.
 columns={r[1] for r in DB.execute('PRAGMA table_info(rooms)')}
 if 'public' not in columns:DB.execute('ALTER TABLE rooms ADD COLUMN public INTEGER NOT NULL DEFAULT 0')
 DB.executescript("""CREATE TABLE IF NOT EXISTS handles(handle TEXT PRIMARY KEY COLLATE NOCASE, kind TEXT NOT NULL, ref TEXT NOT NULL, UNIQUE(kind,ref));
 CREATE TABLE IF NOT EXISTS archive(seq INTEGER PRIMARY KEY AUTOINCREMENT,owner TEXT NOT NULL,sender TEXT NOT NULL,mid TEXT NOT NULL,envelope TEXT NOT NULL,delivered INTEGER NOT NULL DEFAULT 0,selfcopy INTEGER NOT NULL DEFAULT 0,UNIQUE(owner,sender,mid));
 CREATE INDEX IF NOT EXISTS archive_owner_pending ON archive(owner,delivered,selfcopy,seq);
 CREATE TABLE IF NOT EXISTS key_backups(nick TEXT PRIMARY KEY,blob TEXT NOT NULL);
 CREATE TABLE IF NOT EXISTS videos(id TEXT PRIMARY KEY,owner TEXT NOT NULL,room TEXT NOT NULL,name TEXT NOT NULL,size INTEGER NOT NULL,received INTEGER NOT NULL DEFAULT 0,ready INTEGER NOT NULL DEFAULT 0);
 CREATE TABLE IF NOT EXISTS threads(room TEXT NOT NULL,post TEXT NOT NULL,PRIMARY KEY(room,post));
 CREATE TABLE IF NOT EXISTS capabilities(nick TEXT PRIMARY KEY,protocol INTEGER NOT NULL);
 CREATE TABLE IF NOT EXISTS blocks(owner TEXT NOT NULL,target TEXT NOT NULL,PRIMARY KEY(owner,target));
 CREATE TABLE IF NOT EXISTS room_bans(room TEXT NOT NULL,nick TEXT NOT NULL,PRIMARY KEY(room,nick));
 CREATE TABLE IF NOT EXISTS emails(email TEXT PRIMARY KEY COLLATE NOCASE,nick TEXT UNIQUE NOT NULL,verified INTEGER NOT NULL DEFAULT 0);
 CREATE TABLE IF NOT EXISTS email_codes(nick TEXT PRIMARY KEY,digest TEXT NOT NULL,expires INTEGER NOT NULL,attempts INTEGER NOT NULL DEFAULT 0);""")
 for (n,) in DB.execute('SELECT nick FROM users').fetchall():DB.execute('INSERT OR IGNORE INTO handles VALUES(?,?,?)',(n,'user',n))
 for rid,kind in DB.execute('SELECT id,kind FROM rooms').fetchall():
  if not DB.execute("SELECT 1 FROM handles WHERE kind='room' AND ref=?",(rid,)).fetchone():
   handle=free_room_handle(kind,rid)
   DB.execute('INSERT INTO handles VALUES(?,?,?)',(handle,'room',rid))
 DB.commit()

def free_room_handle(kind,rid):
 base=kind+'_'+rid.replace('-','')[:12];h=base
 while DB.execute('SELECT 1 FROM handles WHERE handle=?',(h,)).fetchone():h=base[:17]+'_'+secrets.token_hex(3)
 return h

def handle_value(value):
 h=str(value).strip().lstrip('@').lower()
 if not NICK.fullmatch(h):raise Problem(400,'Адрес @: 3–24 латинские буквы, цифры или _')
 return h

def email_value(value):
 e=str(value).strip().lower()
 if len(e)>254 or not re.fullmatch(r"[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\.[a-z]{2,63}",e):raise Problem(400,'Проверь адрес электронной почты')
 return e

def blocked(a,b):
 with LOCK:return bool(DB.execute('SELECT 1 FROM blocks WHERE (owner=? AND target=?) OR (owner=? AND target=?)',(a,b,b,a)).fetchone())

def private_account(nick):
 result=public_user(nick)
 with LOCK:r=DB.execute('SELECT email,verified FROM emails WHERE nick=?',(nick,)).fetchone()
 result.update(email=r[0] if r else '',email_verified=bool(r and r[1]),creator_video=creator(nick))
 return result

def creator(nick):
 owner=os.environ.get('OLDY_OWNER_NICK','')
 if not owner or nick!=owner:return False
 expected=os.environ.get('OLDY_OWNER_KEY_HASH','')
 if not expected:return False
 with LOCK:r=DB.execute('SELECT sig FROM users WHERE nick=?',(nick,)).fetchone()
 return bool(r and hmac.compare_digest(expected,hashlib.sha256(r[0].encode()).hexdigest()))

def video_record(vid,nick,writing=False):
 if not re.fullmatch('[a-f0-9-]{36}',vid):raise Problem(404,'Видео не найдено')
 with LOCK:r=DB.execute('SELECT id,owner,room,name,size,received,ready FROM videos WHERE id=?',(vid,)).fetchone()
 if not r:raise Problem(404,'Видео не найдено')
 item=dict(zip(('id','owner','room','name','size','received','ready'),r))
 room=room_info(item['room'],nick)
 if writing and (not creator(nick) or item['owner']!=nick or room['owner']!=nick):raise Problem(403,'Загрузка видео доступна только владельцу приложения')
 return item

class Problem(Exception):
 def __init__(self, status, code): self.status,self.code=status,code

def rate(key, maximum, seconds):
 now=time.monotonic()
 with LOCK:
  q=LIMITS[key]
  while q and q[0] < now-seconds:q.popleft()
  if len(q)>=maximum:raise Problem(429,'Слишком много запросов. Попробуйте позже')
  q.append(now)
  if len(LIMITS)>10000:
   for k in list(LIMITS):
    if not LIMITS[k] or LIMITS[k][-1]<now-3600: del LIMITS[k]

def pw_hash(p,salt):return hashlib.scrypt(p.encode(),salt=bytes.fromhex(salt),n=16384,r=8,p=1).hex()
def verify_envelope(e,nick):
 try:
  with LOCK:row=DB.execute('SELECT sig FROM users WHERE nick=?',(nick,)).fetchone()
  signed=('oldy-v1\n'+e['id']+'\n'+e['from']+'\n'+e['to']+'\n'+str(e['time'])+'\n'+e['key']+'\n'+e['iv']+'\n'+e['body']).encode()
  key=load_der_public_key(base64.b64decode(row[0],validate=True))
  key.verify(base64.b64decode(e['signature'],validate=True),signed,ec.ECDSA(hashes.SHA256()))
  if len(base64.b64decode(e['iv'],validate=True))!=12 or len(base64.b64decode(e['key'],validate=True))!=384:raise ValueError()
 except Exception:raise Problem(400,'Подпись сообщения не прошла проверку')
def public_user(nick):
 with LOCK:row=DB.execute('SELECT nick,name,enc,sig FROM users WHERE nick=?',(nick,)).fetchone()
 if not row:raise Problem(404,'Пользователь не найден')
 result=dict(zip(('nick','name','enc','sig'),row))
 with LOCK:profile=DB.execute('SELECT avatar,bio FROM profiles WHERE nick=?',(nick,)).fetchone()
 result.update(avatar=profile[0] if profile else 'preset:0',bio=profile[1] if profile else '')
 with LOCK:cap=DB.execute('SELECT protocol FROM capabilities WHERE nick=?',(nick,)).fetchone()
 result['protocol']=cap[0] if cap else 2
 return result
def issue_session(nick):
 token=secrets.token_urlsafe(32)
 with LOCK:
  DB.execute('DELETE FROM sessions WHERE expires<?',(time.time(),))
  DB.execute('INSERT INTO sessions VALUES(?,?,?)',(hashlib.sha256(token.encode()).hexdigest(),nick,int(time.time())+2592000))
  DB.commit()
 return token

def room_info(rid, nick):
 with LOCK:
  row=DB.execute('SELECT id,title,kind,owner,avatar,public FROM rooms WHERE id=?',(rid,)).fetchone()
  members=[r[0] for r in DB.execute('SELECT nick FROM members WHERE room=? ORDER BY nick',(rid,))]
 if not row or nick not in members:raise Problem(404,'Чат не найден')
 result=dict(zip(('id','title','kind','owner','avatar','public'),row));result['members']=members;result['public']=bool(result['public'])
 with LOCK:
  handle=DB.execute("SELECT handle FROM handles WHERE kind='room' AND ref=?",(rid,)).fetchone()
  result['handle']=handle[0] if handle else ''
  if nick==result['owner']:result['banned']=[r[0] for r in DB.execute('SELECT nick FROM room_bans WHERE room=?',(rid,))]
 return result

def avatar_value(data):
 value=data.get('avatar','preset:0')
 if 'photo' not in data and isinstance(value,str) and re.fullmatch(r'preset:(?:[0-9]|1[01])',value):return value
 raw=data.get('photo','')
 if not isinstance(raw,str) or len(raw)>700000:raise Problem(400,'Фото слишком большое')
 try:
  from PIL import Image,ImageOps
  image=Image.open(io.BytesIO(base64.b64decode(raw,validate=True)))
  if image.width*image.height>4000000 or image.width<1 or image.height<1:raise ValueError()
  image=ImageOps.exif_transpose(image).convert('RGB');image.thumbnail((512,512))
  out=io.BytesIO();image.save(out,format='JPEG',quality=82);clean=out.getvalue()
 except Exception:raise Problem(400,'Выберите обычное фото до 4 мегапикселей')
 key=hashlib.sha256(clean).hexdigest();folder=ROOT/'avatars';folder.mkdir(mode=0o700,exist_ok=True)
 path=folder/(key+'.jpg')
 if not path.exists():
  with open(path,'xb') as f:f.write(clean)
 return 'photo:'+key

class Handler(BaseHTTPRequestHandler):
 protocol_version='HTTP/1.1'
 server_version='OldyRelay/0.3'
 def setup(self):
  super().setup();self.connection.settimeout(35)
 def log_message(self,*args): pass # Never log request data, auth or envelopes.
 def reply(self,obj,status=200):
  raw=json.dumps(obj,ensure_ascii=False,separators=(',',':')).encode()
  self.send_response(status);self.send_header('Content-Type','application/json; charset=utf-8');self.send_header('Cache-Control','no-store');self.send_header('X-Content-Type-Options','nosniff');self.send_header('Content-Length',str(len(raw)));self.end_headers();self.wfile.write(raw)
 def user(self):
  a=self.headers.get('Authorization','')
  if not a.startswith('Bearer '):raise Problem(401,'Войдите в аккаунт')
  digest=hashlib.sha256(a[7:].encode()).hexdigest()
  with LOCK:r=DB.execute('SELECT nick FROM sessions WHERE hash=? AND expires>?',(digest,time.time())).fetchone()
  if not r:raise Problem(401,'Сессия истекла. Войдите снова')
  return r[0]
 def do_GET(self):self.handle_request(False)
 def do_POST(self):self.handle_request(True)
 def handle_request(self,post):
  try:
   path=urlsplit(self.path).path
   rate(('allvideo' if path.startswith('/video') else 'all',self.client_address[0]),2400 if path.startswith('/video') else 600,60)
   data={}
   if path.startswith('/video-chunk/') and post:
    nick=self.user();item=video_record(path[13:],nick,True)
    if item['ready']:raise Problem(409,'Видео уже загружено')
    if self.headers.get('Transfer-Encoding'):raise Problem(400,'Неверный запрос')
    try:n=int(self.headers.get('Content-Length','0'));offset=int(self.headers.get('X-Upload-Offset','-1'))
    except ValueError:raise Problem(400,'Неверный размер')
    if not 1<=n<=1048576 or offset<0 or offset+n>item['size']:raise Problem(413,'Размер части не подходит')
    raw=self.rfile.read(n)
    if len(raw)!=n:raise Problem(400,'Часть не получена полностью')
    if offset==0 and (n<12 or raw[4:8]!=b'ftyp'):raise Problem(400,'Нужен обычный MP4 файл')
    folder=ROOT/'videos';folder.mkdir(mode=0o700,exist_ok=True);file=folder/(item['id']+'.part')
    with LOCK:
     current=DB.execute('SELECT received FROM videos WHERE id=?',(item['id'],)).fetchone()[0]
     if offset!=current:raise Problem(409,'Продолжите с последней загруженной части')
     mode='r+b' if file.exists() else 'w+b'
     with open(file,mode) as f:f.seek(offset);f.write(raw);f.truncate(offset+n);f.flush()
     DB.execute('UPDATE videos SET received=? WHERE id=?',(offset+n,item['id']));DB.commit()
    return self.reply({'received':offset+n})
   if path.startswith('/video-stream/') and not post:
    nick=self.user();item=video_record(path[14:],nick)
    if not item['ready']:raise Problem(404,'Видео ещё загружается')
    file=ROOT/'videos'/(item['id']+'.mp4');size=item['size'];start=0;end=size-1;partial=False
    header=self.headers.get('Range','')
    if header:
     m=re.fullmatch(r'bytes=(\d+)-(\d*)',header)
     if not m:raise Problem(416,'Неверный диапазон')
     start=int(m[1]);end=min(size-1,int(m[2])) if m[2] else size-1;partial=True
     if start>=size or start>end:raise Problem(416,'Неверный диапазон')
    with open(file,'rb') as f:
     self.send_response(206 if partial else 200);self.send_header('Content-Type','video/mp4');self.send_header('Accept-Ranges','bytes');self.send_header('Cache-Control','private, no-store');self.send_header('X-Content-Type-Options','nosniff');self.send_header('Content-Length',str(end-start+1))
     if partial:self.send_header('Content-Range',f'bytes {start}-{end}/{size}')
     self.end_headers();f.seek(start);left=end-start+1
     while left:
      chunk=f.read(min(65536,left))
      if not chunk:break
      self.wfile.write(chunk);left-=len(chunk)
    return
   if post:
    if self.headers.get('Transfer-Encoding'):raise Problem(400,'Unsupported request')
    try:n=int(self.headers.get('Content-Length','0'))
    except ValueError:raise Problem(400,'Invalid length')
    if n<0 or n>(750000 if path in ('/profile','/room/update') else MAX_BODY):raise Problem(413,'Запрос слишком большой')
    try:data=json.loads(self.rfile.read(n))
    except Exception:raise Problem(400,'Некорректный JSON')
    if not isinstance(data,dict):raise Problem(400,'Некорректный запрос')
   if path=='/health' and not post:return self.reply({'service':'oldy-chat','version':3,'history':'encrypted-cloud-and-devices'})
   if path=='/updates' and not post:
    release=ROOT/'releases'/'release.json';apk=ROOT/'releases'/'OldyChat-latest.apk'
    if not release.exists() or not apk.exists():return self.reply({'available':False,'required':False})
    try:
     info=json.loads(release.read_text())
     if info.get('package')!='chat.oldy' or not isinstance(info.get('version_code'),int) or not re.fullmatch('[a-f0-9]{64}',info.get('sha256','')) or info.get('size')!=apk.stat().st_size:raise ValueError()
     return self.reply({'available':True,'required':False,'version_code':info['version_code'],'version_name':str(info.get('version_name',''))[:40],'notes':str(info.get('notes',''))[:4000],'sha256':info['sha256'],'size':info['size'],'path':'/download/OldyChat-latest.apk'})
    except Exception:return self.reply({'available':False,'required':False})
   if path=='/download/OldyChat-latest.apk' and not post:
    rate(('apk-download',self.client_address[0]),15,3600)
    apk=ROOT/'releases'/'OldyChat-latest.apk'
    try:f=open(apk,'rb')
    except FileNotFoundError:raise Problem(404,'Обновление ещё не опубликовано')
    with f:
     length=os.fstat(f.fileno()).st_size
     self.send_response(200);self.send_header('Content-Type','application/vnd.android.package-archive');self.send_header('Content-Length',str(length));self.send_header('Cache-Control','no-store');self.send_header('X-Content-Type-Options','nosniff');self.end_headers()
     while True:
      chunk=f.read(65536)
      if not chunk:break
      self.wfile.write(chunk)
    return
   if path=='/handles' and not post:
    rate(('handles',self.client_address[0]),90,60)
    h=handle_value(parse_qs(urlsplit(self.path).query).get('handle',[''])[0])
    with LOCK:taken=DB.execute('SELECT 1 FROM handles WHERE handle=?',(h,)).fetchone()
    return self.reply({'handle':h,'available':not bool(taken)})
   if path in ('/register','/login') and post:
    rate(('auth',self.client_address[0]),20,300)
    nick=str(data.get('nick','')).lower().strip();password=data.get('password','')
    if path=='/login' and '@' in nick:
     with LOCK:email_row=DB.execute('SELECT nick FROM emails WHERE email=?',(nick,)).fetchone()
     nick=email_row[0] if email_row else 'unknown_email_login'
    if not NICK.fullmatch(nick) or not isinstance(password,str) or not 8<=len(password)<=128:raise Problem(400,'Ник: 3–24 латинских символа, цифры или _. Пароль: 8–128 символов')
    if path=='/register':
     rate(('register',self.client_address[0]),5,3600)
     email=email_value(data['email']) if data.get('email') else ''
     name=str(data.get('name',nick)).strip()[:40] or nick
     try:
      enc=data['enc'];sig=data['sig']
      if not isinstance(enc,str) or not isinstance(sig,str) or len(enc)>2048 or len(sig)>512:raise ValueError()
      rk=load_der_public_key(base64.b64decode(enc,validate=True));sk=load_der_public_key(base64.b64decode(sig,validate=True))
      if not isinstance(rk,rsa.RSAPublicKey) or rk.key_size!=3072 or not isinstance(sk,ec.EllipticCurvePublicKey) or sk.curve.name!='secp256r1':raise ValueError()
     except Exception:raise Problem(400,'Неверные ключи устройства')
     salt=secrets.token_hex(16);hashed=pw_hash(password,salt)
     with LOCK:
      try:
       DB.execute('INSERT INTO handles VALUES(?,?,?)',(nick,'user',nick))
       DB.execute('INSERT INTO users VALUES(?,?,?,?,?,?)',(nick,name,salt,hashed,enc,sig))
       if email:DB.execute('INSERT INTO emails(email,nick) VALUES(?,?)',(email,nick))
       DB.commit()
      except sqlite3.IntegrityError:
       DB.rollback();raise Problem(409,'Этот @адрес или e-mail уже используется')
    else:
     with LOCK:r=DB.execute('SELECT salt,password FROM users WHERE nick=?',(nick,)).fetchone()
     candidate=pw_hash(password,r[0] if r else '00'*16)
     if not r or not hmac.compare_digest(candidate,r[1]):raise Problem(401,'Неверный ник или пароль')
    return self.reply({'token':issue_session(nick),'user':private_account(nick)})
   nick=self.user()
   if path=='/videos/start' and post:
    if not creator(nick):raise Problem(403,'Большие MP4 может загружать только владелец приложения')
    rate(('video-start',nick),20,3600);rid=str(data.get('room',''));room=room_info(rid,nick)
    if room['owner']!=nick or room['kind']!='channel':raise Problem(403,'Эта загрузка доступна в твоём канале')
    size=data.get('size');name=str(data.get('name','Видео.mp4'))[:120]
    if not isinstance(size,int) or isinstance(size,bool) or not 12<=size<=2147483648 or not name.lower().endswith('.mp4'):raise Problem(400,'Выберите MP4 до 2 ГБ')
    import shutil
    if shutil.disk_usage(ROOT).free<size+268435456:raise Problem(507,'На сервере недостаточно места для этого видео')
    vid=str(uuid.uuid4())
    with LOCK:DB.execute('INSERT INTO videos(id,owner,room,name,size) VALUES(?,?,?,?,?)',(vid,nick,rid,name,size));DB.commit()
    return self.reply({'id':vid,'received':0,'size':size})
   if path.startswith('/video-status/') and not post:return self.reply(video_record(path[14:],nick,True))
   if path=='/videos/finish' and post:
    item=video_record(str(data.get('id','')),nick,True)
    if item['ready']:return self.reply(item)
    file=ROOT/'videos'/(item['id']+'.part')
    if item['received']!=item['size'] or not file.exists() or file.stat().st_size!=item['size']:raise Problem(409,'Дождитесь полной загрузки')
    with LOCK:
     os.replace(file,file.with_suffix('.mp4'));DB.execute('UPDATE videos SET ready=1 WHERE id=?',(item['id'],));DB.commit()
    return self.reply(video_record(item['id'],nick,True))
   if path=='/videos/cancel' and post:
    item=video_record(str(data.get('id','')),nick,True)
    if item['ready']:raise Problem(409,'Опубликованное видео не удаляется отменой загрузки')
    with LOCK:
     (ROOT/'videos'/(item['id']+'.part')).unlink(missing_ok=True);DB.execute('DELETE FROM videos WHERE id=?',(item['id'],));DB.commit()
    return self.reply({'ok':True})
   if path=='/threads' and post:
    rid=str(data.get('room',''));post_id=str(data.get('post',''));room=room_info(rid,nick)
    if not re.fullmatch('[a-f0-9-]{36}',post_id):raise Problem(400,'Пост не найден')
    with LOCK:
     exists=DB.execute('SELECT 1 FROM threads WHERE room=? AND post=?',(rid,post_id)).fetchone()
     if not exists:
      if room['owner']!=nick:
       rows=DB.execute('SELECT envelope FROM archive WHERE mid=? AND sender=? AND selfcopy=0',(post_id,room['owner'])).fetchall()
       if not any(json.loads(r[0]).get('room')==rid and json.loads(r[0]).get('action','publish')=='publish' for r in rows):raise Problem(404,'Пост пока не синхронизирован')
      DB.execute('INSERT OR IGNORE INTO threads VALUES(?,?)',(rid,post_id));DB.commit()
    return self.reply({'room':rid,'post':post_id,'link':'oldy://comments/'+rid+'/'+post_id})
   if path=='/key-backup' and not post:
    with LOCK:r=DB.execute('SELECT blob FROM key_backups WHERE nick=?',(nick,)).fetchone()
    if not r:raise Problem(404,'Резервная копия ключей пока не настроена. Нужна копия с прежнего телефона.')
    return self.reply({'backup':r[0]})
   if path=='/key-backup' and post:
    rate(('key-backup',nick),10,3600);blob=data.get('backup')
    if not isinstance(blob,str) or len(blob)>50000:raise Problem(400,'Неверная копия ключей')
    try:
     backup=json.loads(blob)
     if backup.get('format')!='oldy-backup-v1' or not all(isinstance(backup.get(k),str) for k in ('salt','iv','data')):raise ValueError()
     if len(base64.b64decode(backup['salt'],validate=True))!=16 or len(base64.b64decode(backup['iv'],validate=True))!=12 or len(base64.b64decode(backup['data'],validate=True))<32:raise ValueError()
    except Exception:raise Problem(400,'Неверная копия ключей')
    with LOCK:DB.execute('INSERT INTO key_backups VALUES(?,?) ON CONFLICT(nick) DO UPDATE SET blob=excluded.blob',(nick,blob));DB.commit()
    return self.reply({'ok':True})
   if path=='/history' and not post:
    try:after=max(0,int(parse_qs(urlsplit(self.path).query).get('after',['0'])[0]))
    except ValueError:raise Problem(400,'Неверный курсор')
    with LOCK:rows=DB.execute('SELECT seq,envelope,delivered FROM archive WHERE owner=? AND seq>? ORDER BY seq LIMIT 30',(nick,after)).fetchall()
    return self.reply({'items':[{'seq':r[0],'envelope':json.loads(r[1]),'delivered':bool(r[2])} for r in rows],'next':rows[-1][0] if rows else after})
   if path=='/history/store' and post:
    e=data.get('envelope');rate(('history-store',nick),240,60)
    if not isinstance(e,dict) or e.get('from')!=nick or e.get('to')!=nick or e.get('v')!=1 or not re.fullmatch('[a-f0-9-]{36}',str(e.get('id',''))):raise Problem(400,'Неверная резервная копия')
    if set(e)!=set(('v','id','from','to','time','body','iv','key','signature')):raise Problem(400,'Неверные поля')
    for key,limit in [('body',48000),('iv',24),('key',600),('signature',160)]:
     if not isinstance(e.get(key),str) or not 1<=len(e[key])<=limit:raise Problem(400,'Неверный конверт')
     try:base64.b64decode(e[key],validate=True)
     except Exception:raise Problem(400,'Неверный конверт')
    verify_envelope(e,nick)
    with LOCK:DB.execute('INSERT OR IGNORE INTO archive(owner,sender,mid,envelope,delivered,selfcopy) VALUES(?,?,?,?,1,1)',(nick,nick,e['id'],json.dumps(e,separators=(',',':'))));DB.commit()
    return self.reply({'stored':True})
   if path=='/receipts' and post:
    ids=data.get('ids',[])
    if not isinstance(ids,list) or len(ids)>100:raise Problem(400,'Неверный список')
    done=[]
    with LOCK:
     for mid in ids:
      row=DB.execute('SELECT COUNT(*),MIN(delivered) FROM archive WHERE sender=? AND mid=? AND selfcopy=0',(nick,str(mid))).fetchone()
      if row[0] and row[1]:done.append(mid)
    return self.reply({'delivered':done})
   if path=='/capabilities' and post:
    protocol=data.get('protocol')
    if not isinstance(protocol,int) or protocol<2 or protocol>100:raise Problem(400,'Неверная версия протокола')
    with LOCK:DB.execute('INSERT INTO capabilities VALUES(?,?) ON CONFLICT(nick) DO UPDATE SET protocol=excluded.protocol',(nick,protocol));DB.commit()
    return self.reply({'ok':True})
   if path=='/blocks' and not post:
    with LOCK:names=[r[0] for r in DB.execute('SELECT target FROM blocks WHERE owner=?',(nick,))]
    return self.reply({'blocked':names})
   if path=='/blocks' and post:
    target=handle_value(data.get('target',''));public_user(target)
    if target==nick:raise Problem(400,'Это твой аккаунт')
    if not isinstance(data.get('blocked'),bool):raise Problem(400,'Укажите действие')
    with COND:
     if data['blocked']:
      DB.execute('INSERT OR IGNORE INTO blocks VALUES(?,?)',(nick,target))
      for k in list(TYPING):
       if set(k)=={nick,target}:TYPING.pop(k,None)
     else:DB.execute('DELETE FROM blocks WHERE owner=? AND target=?',(nick,target))
     DB.commit();COND.notify_all()
    return self.reply({'ok':True})
   if path=='/email/link' and post:
    email=email_value(data.get('email',''));rate(('email-link',nick),5,3600)
    with LOCK:
     try:
      DB.execute('INSERT INTO emails(email,nick) VALUES(?,?) ON CONFLICT(nick) DO UPDATE SET email=excluded.email,verified=0',(email,nick))
      DB.execute('DELETE FROM email_codes WHERE nick=?',(nick,));DB.commit()
     except sqlite3.IntegrityError:DB.rollback();raise Problem(409,'Этот e-mail уже используется')
    return self.reply(private_account(nick))
   if path=='/email/request' and post:
    rate(('email-request',nick),3,3600)
    import smtplib,ssl
    from email.message import EmailMessage
    host=os.environ.get('OLDY_SMTP_HOST','');sender=os.environ.get('OLDY_SMTP_FROM','')
    if not host or not sender:raise Problem(503,'Почта добавлена. Отправка кодов ещё не подключена владельцем сервера.')
    email=private_account(nick)['email']
    if not email:raise Problem(400,'Сначала добавьте e-mail')
    code=''.join(secrets.choice('0123456789') for _ in range(6));digest=hashlib.sha256((nick+':'+code).encode()).hexdigest()
    with LOCK:DB.execute('INSERT OR REPLACE INTO email_codes VALUES(?,?,?,0)',(nick,digest,int(time.time())+600));DB.commit()
    msg=EmailMessage();msg['Subject']='Код подтверждения OldЫ Chat';msg['From']=sender;msg['To']=email;msg.set_content('Код подтверждения: '+code+'\nДействует 10 минут. Никому не сообщайте код.')
    try:
     with smtplib.SMTP(host,int(os.environ.get('OLDY_SMTP_PORT','587')),timeout=15) as smtp:
      smtp.starttls(context=ssl.create_default_context())
      if os.environ.get('OLDY_SMTP_USER'):smtp.login(os.environ['OLDY_SMTP_USER'],os.environ.get('OLDY_SMTP_PASSWORD',''))
      smtp.send_message(msg)
    except Exception:
     with LOCK:DB.execute('DELETE FROM email_codes WHERE nick=?',(nick,));DB.commit()
     raise Problem(503,'Не удалось отправить письмо. Попробуйте позже.')
    return self.reply({'ok':True})
   if path=='/email/verify' and post:
    rate(('email-verify',nick),10,600);code=str(data.get('code',''))
    with LOCK:
     r=DB.execute('SELECT digest,expires,attempts FROM email_codes WHERE nick=?',(nick,)).fetchone()
     if not r or r[1]<time.time() or r[2]>=5:raise Problem(400,'Запросите новый код')
     DB.execute('UPDATE email_codes SET attempts=attempts+1 WHERE nick=?',(nick,));DB.commit()
     if not hmac.compare_digest(r[0],hashlib.sha256((nick+':'+code).encode()).hexdigest()):raise Problem(400,'Неверный код')
     DB.execute('UPDATE emails SET verified=1 WHERE nick=?',(nick,));DB.execute('DELETE FROM email_codes WHERE nick=?',(nick,));DB.commit()
    return self.reply(private_account(nick))
   if path=='/search' and not post:
    q=parse_qs(urlsplit(self.path).query).get('q',[''])[0].strip().lstrip('@').lower()[:60]
    if len(q)<2:return self.reply({'users':[],'rooms':[]})
    escaped=q.replace('\\','\\\\').replace('%','\\%').replace('_','\\_');pattern='%'+escaped+'%'
    with LOCK:
     users=[r[0] for r in DB.execute("SELECT nick FROM users WHERE (nick LIKE ? ESCAPE '\\' OR lower(name) LIKE ? ESCAPE '\\') AND nick!=? LIMIT 20",(pattern,pattern,nick))]
     rooms=[]
     for rid,title,kind,avatar,handle,public in DB.execute("SELECT r.id,r.title,r.kind,r.avatar,h.handle,r.public FROM rooms r JOIN handles h ON h.ref=r.id AND h.kind='room' WHERE (r.public=1 OR EXISTS(SELECT 1 FROM members m WHERE m.room=r.id AND m.nick=?)) AND (h.handle LIKE ? ESCAPE '\\' OR lower(r.title) LIKE ? ESCAPE '\\') LIMIT 20",(nick,pattern,pattern)):
      rooms.append(dict(id=rid,title=title,kind=kind,avatar=avatar,handle=handle,public=bool(public),joined=bool(DB.execute('SELECT 1 FROM members WHERE room=? AND nick=?',(rid,nick)).fetchone())))
    return self.reply({'users':[public_user(u) for u in users],'rooms':rooms})
   if path=='/campaign' and not post:
    config=ROOT/'campaign.json'
    if not config.exists():return self.reply({'enabled':False})
    try:
     ad=json.loads(config.read_text())
     if ad.get('enabled') is not True:return self.reply({'enabled':False})
     if not isinstance(ad.get('revision'),int) or ad['revision']<1:raise ValueError()
     for key,limit in [('title',80),('text',1000),('url',500)]:
      if not isinstance(ad.get(key),str) or len(ad[key])>limit:raise ValueError()
     link=urlsplit(ad['url'])
     if link.scheme!='https' or not link.hostname or link.username or link.password:raise ValueError()
     return self.reply({k:ad[k] for k in ('enabled','revision','title','text','url')})
    except (ValueError,KeyError):return self.reply({'enabled':False})
   if path=='/profile' and post:
    rate(('profile',nick),20,3600)
    name=str(data.get('name',public_user(nick)['name'])).strip()[:40] or nick
    bio=str(data.get('bio',public_user(nick)['bio'])).strip()[:160];avatar=avatar_value(data) if 'avatar' in data or 'photo' in data else public_user(nick)['avatar']
    with LOCK:
     DB.execute('UPDATE users SET name=? WHERE nick=?',(name,nick))
     DB.execute('INSERT INTO profiles VALUES(?,?,?) ON CONFLICT(nick) DO UPDATE SET avatar=excluded.avatar,bio=excluded.bio',(nick,avatar,bio));DB.commit()
    return self.reply(private_account(nick))
   if path.startswith('/avatar/') and not post:
    key=path[8:]
    if not re.fullmatch('[0-9a-f]{64}',key):raise Problem(404,'Фото не найдено')
    with LOCK:
     used=DB.execute('SELECT 1 FROM profiles WHERE avatar=? UNION SELECT 1 FROM rooms WHERE avatar=?',('photo:'+key,'photo:'+key)).fetchone()
    if not used:raise Problem(404,'Фото не найдено')
    file=ROOT/'avatars'/(key+'.jpg')
    if not file.exists():raise Problem(404,'Фото не найдено')
    return self.reply({'photo':base64.b64encode(file.read_bytes()).decode()})
   if path=='/rooms' and not post:
    with LOCK:ids=[r[0] for r in DB.execute('SELECT room FROM members WHERE nick=?',(nick,))]
    return self.reply({'rooms':[room_info(r,nick) for r in ids]})
   if path.startswith('/room/') and not post:return self.reply(room_info(path[6:],nick))
   if path=='/room/create' and post:
    rate(('room-create',nick),10,3600)
    title=str(data.get('title','')).strip()[:60];kind=data.get('kind');members=data.get('members',[])
    if not title or kind not in ('group','channel') or not isinstance(members,list) or len(members)>49:raise Problem(400,'Укажите название и до 49 участников')
    members=list(dict.fromkeys([nick]+[str(x) for x in members]))
    for member in members:public_user(member)
    rid=str(uuid.uuid4());public=data.get('public',False)
    if not isinstance(public,bool):raise Problem(400,'Неверная видимость')
    av=data.get('avatar','preset:0')
    if not isinstance(av,str) or not re.fullmatch(r'preset:(?:[0-9]|1[01])',av):av='preset:0'
    with LOCK:
     handle=handle_value(data['handle']) if data.get('handle') else free_room_handle(kind,rid)
     try:
      DB.execute('INSERT INTO handles VALUES(?,?,?)',(handle,'room',rid))
      DB.execute('INSERT INTO rooms(id,title,kind,owner,avatar,public) VALUES(?,?,?,?,?,?)',(rid,title,kind,nick,av,int(public)))
      DB.executemany('INSERT INTO members VALUES(?,?)',[(rid,m) for m in members]);DB.commit()
     except sqlite3.IntegrityError:DB.rollback();raise Problem(409,'Этот @адрес уже занят')
    return self.reply(room_info(rid,nick))
   if path=='/room/update' and post:
    rate(('room-update',nick),30,3600)
    rid=str(data.get('id',''));room=room_info(rid,nick)
    if room['owner']!=nick:raise Problem(403,'Только создатель может менять чат')
    members=data.get('members',room['members']);title=str(data.get('title',room['title'])).strip()[:60]
    if not isinstance(members,list) or len(members)>50 or nick not in members or not title:raise Problem(400,'Неверный список участников')
    members=list(dict.fromkeys(str(m) for m in members))
    for m in members:public_user(m)
    av=avatar_value(data) if 'avatar' in data or 'photo' in data else room['avatar']
    handle=handle_value(data.get('handle',room['handle']));public=data.get('public',bool(room['public']))
    if not isinstance(public,bool):raise Problem(400,'Неверная видимость')
    with LOCK:
     if any(DB.execute('SELECT 1 FROM room_bans WHERE room=? AND nick=?',(rid,m)).fetchone() for m in members):raise Problem(403,'Сначала разблокируйте участника')
     try:DB.execute("UPDATE handles SET handle=? WHERE kind='room' AND ref=?",(handle,rid))
     except sqlite3.IntegrityError:DB.rollback();raise Problem(409,'Этот @адрес уже занят')
     DB.execute('UPDATE rooms SET title=?,avatar=?,public=? WHERE id=?',(title,av,int(public),rid))
     DB.execute('DELETE FROM members WHERE room=?',(rid,))
     DB.executemany('INSERT INTO members VALUES(?,?)',[(rid,m) for m in members]);DB.commit()
    return self.reply(room_info(rid,nick))
   if path=='/room/join' and post:
    rid=str(data.get('id',''))
    with LOCK:
     r=DB.execute('SELECT public FROM rooms WHERE id=?',(rid,)).fetchone()
     if not r or not r[0]:raise Problem(404,'Публичный чат не найден')
     if DB.execute('SELECT 1 FROM room_bans WHERE room=? AND nick=?',(rid,nick)).fetchone():raise Problem(403,'Владелец ограничил доступ к этому чату')
     if not DB.execute('SELECT 1 FROM members WHERE room=? AND nick=?',(rid,nick)).fetchone():
      if DB.execute('SELECT COUNT(*) FROM members WHERE room=?',(rid,)).fetchone()[0]>=50:raise Problem(409,'В бета-версии до 50 участников')
      DB.execute('INSERT INTO members VALUES(?,?)',(rid,nick));DB.commit()
    return self.reply(room_info(rid,nick))
   if path=='/room/ban' and post:
    rid=str(data.get('id',''));room=room_info(rid,nick);target=handle_value(data.get('target',''))
    if room['owner']!=nick:raise Problem(403,'Блокировать в группе или канале может только владелец')
    if target==nick:raise Problem(400,'Нельзя заблокировать владельца')
    public_user(target)
    if not isinstance(data.get('banned'),bool):raise Problem(400,'Укажите действие')
    with LOCK:
     if data['banned']:
      DB.execute('INSERT OR IGNORE INTO room_bans VALUES(?,?)',(rid,target));DB.execute('DELETE FROM members WHERE room=? AND nick=?',(rid,target))
     else:DB.execute('DELETE FROM room_bans WHERE room=? AND nick=?',(rid,target))
     DB.commit()
    return self.reply(room_info(rid,nick))
   if path=='/room/leave' and post:
    rid=str(data.get('id',''));room=room_info(rid,nick)
    if room['owner']==nick:raise Problem(400,'Создатель остаётся в чате')
    with LOCK:DB.execute('DELETE FROM members WHERE room=? AND nick=?',(rid,nick));DB.commit()
    return self.reply({'ok':True})
   if path=='/typing' and post:
    target=str(data.get('to',''));public_user(target)
    if blocked(nick,target):raise Problem(403,'Личные сообщения недоступны')
    with LOCK:
     now=time.monotonic()
     for k in list(TYPING):
      if TYPING[k]<now:del TYPING[k]
     if len(TYPING)<2000:TYPING[(nick,target)]=now+6
    return self.reply({'ok':True})
   if path.startswith('/typing/') and not post:
    peer=path[8:]
    return self.reply({'typing':TYPING.get((peer,nick),0)>time.monotonic()})
   if path=='/me' and not post:return self.reply(private_account(nick))
   if path=='/logout' and post:
    with LOCK:DB.execute('DELETE FROM sessions WHERE hash=?',(hashlib.sha256(self.headers['Authorization'][7:].encode()).hexdigest(),));DB.commit()
    return self.reply({'ok':True})
   if path=='/users' and not post:
    q=parse_qs(urlsplit(self.path).query).get('q',[''])[0].lower().strip()
    if not re.fullmatch('[a-z0-9_]{1,24}',q):return self.reply({'users':[]})
    # Escape LIKE wildcard: underscores are literal nickname characters.
    escaped=q.replace('_','\\_')+'%'
    with LOCK:rows=DB.execute("SELECT nick,name,enc,sig FROM users WHERE nick LIKE ? ESCAPE '\\' AND nick!=? LIMIT 20",(escaped,nick)).fetchall()
    return self.reply({'users':[public_user(r[0]) for r in rows]})
   if path.startswith('/user/') and not post:return self.reply(public_user(path[6:]))
   if path=='/poll' and not post:
    deadline=time.monotonic()+22
    with COND:
     if POLLING[nick]>=2:raise Problem(429,'Уже открыто подключение')
     POLLING[nick]+=1;COND.notify_all()
     try:
      while time.monotonic()<deadline:
       messages=[v['envelope'] for v in INFLIGHT.values() if v['to']==nick and v['expires']>time.monotonic() and not v['done'].is_set() and (v['envelope'].get('room') or not blocked(v['envelope']['from'],nick))][:20]
       durable=[json.loads(r[0]) for r in DB.execute('SELECT envelope FROM archive WHERE owner=? AND delivered=0 AND selfcopy=0 ORDER BY seq LIMIT 20',(nick,))]
       messages=(durable+messages)[:20]
       if messages:break
       COND.wait(min(2,max(0,deadline-time.monotonic())))
      else:messages=[]
      self.reply({'messages':messages})
     finally:POLLING[nick]-=1
    return
   if path=='/send' and post:
    rate(('send',nick),120,60)
    e=data;target=e.get('to');mid=e.get('id')
    if e.get('v')!=1 or e.get('from')!=nick or not isinstance(target,str) or not NICK.fullmatch(target) or not isinstance(mid,str) or not re.fullmatch('[0-9a-f-]{36}',mid):raise Problem(400,'Неверный конверт')
    if not isinstance(e.get('time'),int) or abs(e['time']-int(time.time()*1000))>366*86400000:raise Problem(400,'Неверное время')
    for key,limit in [('body',48000),('iv',24),('key',600),('signature',160)]:
     if not isinstance(e.get(key),str) or not 1<=len(e[key])<=limit:raise Problem(400,'Неверный конверт')
     try:base64.b64decode(e[key],validate=True)
     except Exception:raise Problem(400,'Неверный конверт')
    if set(e)-{'room','action','thread'}!=set(('v','id','from','to','time','body','iv','key','signature')):raise Problem(400,'Лишние поля конверта')
    verify_envelope(e,nick)
    public_user(target)
    route=e.get('room','');action=e.get('action','publish')
    if action not in ('publish','reaction','pin','unpin','signal','comment'):raise Problem(400,'Неизвестное действие')
    if route:
     room=room_info(str(route),nick)
     if e.get('thread'):
      with LOCK:valid_thread=DB.execute('SELECT 1 FROM threads WHERE room=? AND post=?',(route,str(e['thread']))).fetchone()
      if not valid_thread:raise Problem(404,'Комментарии ещё не созданы')
     if action=='comment' and not e.get('thread'):raise Problem(400,'Не указана ветка комментариев')
     if target not in room['members']:raise Problem(403,'Участник покинул чат')
     if action in ('pin','unpin') and room['owner']!=nick:raise Problem(403,'Закрепляет владелец')
     if room['kind']=='channel' and action=='publish' and room['owner']!=nick:raise Problem(403,'Публикует владелец канала')
    elif blocked(nick,target):raise Problem(403,'Личные сообщения недоступны')
    if action!='signal':
     with COND:
      existing=DB.execute('SELECT delivered,envelope FROM archive WHERE owner=? AND sender=? AND mid=? AND selfcopy=0',(target,nick,mid)).fetchone()
      if existing:
       if json.loads(existing[1])!=e:raise Problem(409,'Идентификатор уже использован')
       return self.reply({'stored':True,'delivered':bool(existing[0])})
      DB.execute('INSERT INTO archive(owner,sender,mid,envelope) VALUES(?,?,?,?)',(target,nick,mid,json.dumps(e,separators=(',',':'))));DB.commit();COND.notify_all()
     return self.reply({'stored':True,'delivered':False})
    slotkey=(nick,mid);slot={'to':target,'envelope':e,'expires':time.monotonic()+18,'done':threading.Event()}
    with COND:
     wait=time.monotonic()+2
     while not POLLING[target] and time.monotonic()<wait:COND.wait(.2)
     if not POLLING[target]:raise Problem(409,'Собеседник не в сети. Сообщение остаётся на телефоне')
     if slotkey in INFLIGHT:raise Problem(409,'Сообщение уже передаётся')
     if len(INFLIGHT)>=200:raise Problem(503,'Сервер занят')
     INFLIGHT[slotkey]=slot;COND.notify_all()
    try:
     if slot['done'].wait(18):return self.reply({'delivered':True})
     raise Problem(409,'Ожидание собеседника. Сообщение остаётся на телефоне')
    finally:
     with COND:INFLIGHT.pop(slotkey,None)
   if path=='/ack' and post:
    with COND:
     slot=INFLIGHT.get((data.get('from'),data.get('id')))
     if slot and slot['to']==nick:slot['done'].set()
     DB.execute('UPDATE archive SET delivered=1 WHERE owner=? AND sender=? AND mid=?',(nick,str(data.get('from','')),str(data.get('id',''))));DB.commit()
    return self.reply({'ok':True})
   raise Problem(404,'Не найдено')
  except Problem as e:
   try:self.reply({'error':e.code},e.status)
   except (BrokenPipeError,ConnectionError,socket.timeout):pass
  except (BrokenPipeError,ConnectionError,socket.timeout):pass
  except Exception:
   try:self.reply({'error':'Ошибка сервера'},500)
   except Exception:pass

class Relay(ThreadingHTTPServer):
 daemon_threads=True
 request_queue_size=64
 slots=threading.BoundedSemaphore(96)
 tls=None
 def process_request(self,request,address):
  if not self.slots.acquire(blocking=False):request.close();return
  try:super().process_request(request,address)
  except Exception:self.slots.release();raise
 def process_request_thread(self,request,address):
  try:
   request.settimeout(10)
   if self.tls:request=self.tls.wrap_socket(request,server_side=True)
   super().process_request_thread(request,address)
  except (ssl.SSLError,OSError):request.close()
  finally:self.slots.release()

def main():
 import argparse
 p=argparse.ArgumentParser();p.add_argument('--host',default='0.0.0.0');p.add_argument('--port',type=int,default=8443);p.add_argument('--cert');p.add_argument('--key');p.add_argument('--test-http',action='store_true');p.add_argument('--also-443',action='store_true');a=p.parse_args()
 if a.test_http and a.host not in ('127.0.0.1','::1'):p.error('Test HTTP is localhost-only')
 if not a.test_http and not(a.cert and a.key):p.error('TLS certificate and key required')
 init_db();srv=Relay((a.host,a.port),Handler)
 if not a.test_http:
  tls=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);tls.minimum_version=ssl.TLSVersion.TLSv1_2;tls.load_cert_chain(a.cert,a.key);srv.tls=tls
 if a.also_443:
  second=Relay((a.host,443),Handler);second.tls=srv.tls
  threading.Thread(target=second.serve_forever,daemon=True).start()
 print('Oldy relay started',flush=True);srv.serve_forever()
if __name__=='__main__':main()
