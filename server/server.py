#!/usr/bin/env python3
"""Oldy Chat beta: authenticated TLS API, encrypted message archive,
ephemeral WebRTC signalling, and owner-only channel MP4 storage.
Message bodies and password-protected key backups stay opaque to the server.
Channel MP4 files are ordinary server files, not end-to-end encrypted.
"""
import subprocess, shutil, io, uuid, base64, hashlib, hmac, json, os, re, secrets, socket, sqlite3, ssl, threading, time
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
MEDIA_JOBS = set()
MEDIA_GATE = threading.BoundedSemaphore(1)
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
 DB.executescript("""CREATE TABLE IF NOT EXISTS posts(mid TEXT PRIMARY KEY,room TEXT NOT NULL,author TEXT NOT NULL,thread TEXT NOT NULL DEFAULT '',video TEXT NOT NULL DEFAULT '');
 CREATE UNIQUE INDEX IF NOT EXISTS post_video ON posts(video) WHERE video!='';
 CREATE TABLE IF NOT EXISTS deleted_posts(mid TEXT PRIMARY KEY,room TEXT NOT NULL);
 CREATE TABLE IF NOT EXISTS deleted_rooms(room TEXT PRIMARY KEY);
 CREATE TABLE IF NOT EXISTS deletions(seq INTEGER PRIMARY KEY AUTOINCREMENT,owner TEXT NOT NULL,kind TEXT NOT NULL,room TEXT NOT NULL,mid TEXT NOT NULL);
 CREATE INDEX IF NOT EXISTS deletion_owner ON deletions(owner,seq);
 CREATE TABLE IF NOT EXISTS signup_codes(ticket TEXT PRIMARY KEY,email TEXT NOT NULL,digest TEXT NOT NULL,expires INTEGER NOT NULL,attempts INTEGER NOT NULL DEFAULT 0);
 CREATE TABLE IF NOT EXISTS media_garbage(id TEXT PRIMARY KEY);
 CREATE TABLE IF NOT EXISTS video_variants(id TEXT PRIMARY KEY,state TEXT NOT NULL,size INTEGER NOT NULL DEFAULT 0);""")
 video_columns={r[1] for r in DB.execute('PRAGMA table_info(videos)')}
 for name,definition in [('kind',"TEXT NOT NULL DEFAULT 'creator'"),('recipient',"TEXT NOT NULL DEFAULT ''")]:
  if name not in video_columns:DB.execute('ALTER TABLE videos ADD COLUMN '+name+' '+definition)
 # Existing routed envelopes establish authorship before clients can register attachments.
 for rid,mid,author in DB.execute('SELECT t.room,t.post,r.owner FROM threads t JOIN rooms r ON r.id=t.room').fetchall():DB.execute("INSERT OR IGNORE INTO posts(mid,room,author) VALUES(?,?,?)",(mid,rid,author))
 for (raw,) in DB.execute('SELECT envelope FROM archive WHERE selfcopy=0').fetchall():
  e=json.loads(raw)
  if e.get('action','publish') in ('publish','comment'):
   DB.execute('INSERT OR IGNORE INTO posts(mid,room,author,thread) VALUES(?,?,?,?)',(e['id'],e.get('room',''),e['from'],e.get('thread','')))
 DB.commit()
 collect_media()

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
 result.update(email=r[0] if r else '',email_verified=bool(r and r[1]),creator_video=creator(nick),moderator=creator(nick))
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
 with LOCK:r=DB.execute('SELECT id,owner,room,name,size,received,ready,kind,recipient FROM videos WHERE id=?',(vid,)).fetchone()
 if not r:raise Problem(404,'Видео не найдено')
 item=dict(zip(('id','owner','room','name','size','received','ready','kind','recipient'),r))
 if item['room']:room_info(item['room'],nick)
 elif nick not in (item['owner'],item['recipient']):raise Problem(404,'Видео не найдено')
 if writing and item['owner']!=nick:raise Problem(403,'Изменять загрузку может только её автор')
 return item

def collect_media():
 # Durable garbage queue makes deletion recoverable after a crash or an OS file error.
 with LOCK:
  for (vid,) in DB.execute('SELECT id FROM media_garbage').fetchall():
   if vid in MEDIA_JOBS:continue
   try:
    for suffix in ('.part','.mp4','.compat.mp4','.compat.part.mp4'):(ROOT/'videos'/(vid+suffix)).unlink(missing_ok=True)
    DB.execute('DELETE FROM media_garbage WHERE id=?',(vid,))
   except OSError:pass
  DB.commit()

def gone(mid='',room='',thread=''):
 return bool(DB.execute('SELECT 1 FROM deleted_posts WHERE mid IN (?,?)',(mid,thread)).fetchone() or DB.execute('SELECT 1 FROM deleted_rooms WHERE room=?',(room,)).fetchone())

def register_post(nick,data):
 mid=str(data.get('mid',''));rid=str(data.get('room',''));thread=str(data.get('thread',''));vid=str(data.get('video',''))
 if not re.fullmatch('[a-f0-9-]{36}',mid) or thread and not re.fullmatch('[a-f0-9-]{36}',thread):raise Problem(400,'Неверный пост')
 if gone(mid,rid,thread):raise Problem(410,'Публикация удалена')
 room=room_info(rid,nick) if rid else None
 existing=DB.execute('SELECT room,author,thread,video FROM posts WHERE mid=?',(mid,)).fetchone()
 if existing and (existing[0]!=rid or existing[1]!=nick or existing[2]!=thread):raise Problem(403,'Это чужая публикация')
 if room and room['kind']=='channel' and not thread and room['owner']!=nick:raise Problem(403,'Публикует владелец канала')
 if thread and not DB.execute('SELECT 1 FROM threads WHERE room=? AND post=?',(rid,thread)).fetchone():raise Problem(404,'Комментарии закрыты')
 if vid:
  item=video_record(vid,nick,True)
  if item['room']!=rid or not item['ready']:raise Problem(403,'Видео не принадлежит этой публикации')
  if existing and existing[3] and existing[3]!=vid:raise Problem(409,'Видео уже связано с постом')
  other=DB.execute('SELECT mid FROM posts WHERE video=? AND mid!=?',(vid,mid)).fetchone()
  if other:raise Problem(409,'Это видео уже опубликовано')
 if existing:DB.execute("UPDATE posts SET video=CASE WHEN ?='' THEN video ELSE ? END WHERE mid=?",(vid,vid,mid))
 else:DB.execute('INSERT INTO posts VALUES(?,?,?,?,?)',(mid,rid,nick,thread,vid))
 return mid

def delete_content(nick,rid,mid,whole=False):
 room=DB.execute('SELECT owner FROM rooms WHERE id=?',(rid,)).fetchone() if rid else None
 if whole:
  if not room:raise Problem(404,'Сообщество не найдено')
  if room[0]!=nick and not creator(nick):raise Problem(403,'Удалять сообщество может его создатель')
 else:
  post=DB.execute('SELECT room,author,thread,video FROM posts WHERE mid=?',(mid,)).fetchone()
  if not post or post[0]!=rid:raise Problem(404,'Сначала дождитесь синхронизации публикации')
  if post[1]!=nick and not creator(nick):
   recipient=not rid and DB.execute('SELECT 1 FROM archive WHERE owner=? AND mid=? AND selfcopy=0',(nick,mid)).fetchone()
   if not recipient:raise Problem(403,'Удалять можно только свои публикации')
 affected=set(r[0] for r in DB.execute('SELECT nick FROM members WHERE room=?',(rid,))) if rid else {nick}
 selected=DB.execute('SELECT mid,video,author FROM posts WHERE room=?' if whole else 'SELECT mid,video,author FROM posts WHERE mid=? OR (room=? AND thread=?)',(rid,) if whole else (mid,rid,mid)).fetchall()
 mids={r[0] for r in selected};videos={r[1] for r in selected if r[1]};affected.update(r[2] for r in selected)
 # Includes legacy envelopes and received users' opaque self-snapshots with matching IDs.
 remove=[]
 for seq,owner,raw in DB.execute('SELECT seq,owner,envelope FROM archive').fetchall():
  e=json.loads(raw)
  if e['id'] in mids or rid and e.get('room')==rid and (whole or e.get('thread')==mid):
   remove.append((seq,));affected.add(owner);mids.add(e['id'])
 if whole:videos.update(r[0] for r in DB.execute('SELECT id FROM videos WHERE room=?',(rid,)))
 # Include selfcopies encountered before the routed envelopes in the first pass.
 for message in mids:
  affected.update(r[0] for r in DB.execute('SELECT owner FROM archive WHERE mid=?',(message,)))
  DB.execute('DELETE FROM archive WHERE mid=?',(message,))
  DB.execute('INSERT OR IGNORE INTO deleted_posts VALUES(?,?)',(message,rid))
  DB.execute('DELETE FROM posts WHERE mid=?',(message,))
 DB.executemany('DELETE FROM archive WHERE seq=?',remove)
 for vid in videos:
  DB.execute('INSERT OR IGNORE INTO media_garbage VALUES(?)',(vid,));DB.execute('DELETE FROM videos WHERE id=?',(vid,));DB.execute('DELETE FROM video_variants WHERE id=?',(vid,))
 if whole:
  DB.execute('INSERT OR IGNORE INTO deleted_rooms VALUES(?)',(rid,))
  for table in ('members','room_bans','threads'):DB.execute('DELETE FROM '+table+' WHERE room=?',(rid,))
  DB.execute("DELETE FROM handles WHERE kind='room' AND ref=?",(rid,));DB.execute('DELETE FROM rooms WHERE id=?',(rid,))
 else:DB.execute('DELETE FROM threads WHERE room=? AND post=?',(rid,mid))
 for owner in affected:DB.execute('INSERT INTO deletions(owner,kind,room,mid) VALUES(?,?,?,?)',(owner,'room' if whole else 'post',rid,mid))
 for key,slot in list(INFLIGHT.items()):
  e=slot['envelope']
  if e.get('room')==rid and (whole or e.get('thread')==mid) or e['id'] in mids:slot['done'].set();INFLIGHT.pop(key,None)
 DB.commit();COND.notify_all()
 collect_media()
 pending=any(DB.execute('SELECT 1 FROM media_garbage WHERE id=?',(vid,)).fetchone() for vid in videos)
 return {'ok':True,'kind':'room' if whole else 'post','room':rid,'mid':mid,'media_cleanup_pending':pending}

def compatible_video(vid):
 # One bounded, low-priority conversion. A delete revokes access immediately and cancels the worker.
 try:
  while not MEDIA_GATE.acquire(timeout=1):
   with LOCK:exists=DB.execute('SELECT 1 FROM videos WHERE id=?',(vid,)).fetchone()
   if not exists:return
  try:
   source=ROOT/'videos'/(vid+'.mp4');dest=ROOT/'videos'/(vid+'.compat.part.mp4')
   args=['ffmpeg','-nostdin','-v','error','-threads','1','-filter_threads','1','-protocol_whitelist','file,pipe','-i',str(source),'-map','0:v:0','-map','0:a:0?','-vf',"scale='min(iw,if(gte(iw,ih),1920,1080))':'min(ih,if(gte(iw,ih),1080,1920))':force_original_aspect_ratio=decrease:force_divisible_by=2,setsar=1",'-c:v','libx264','-preset','veryfast','-crf','23','-pix_fmt','yuv420p','-r','30','-threads','1','-c:a','aac','-b:a','128k','-movflags','+faststart','-y',str(dest)]
   process=subprocess.Popen(args,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,start_new_session=True)
   deadline=time.monotonic()+7200
   while process.poll() is None:
    with LOCK:exists=DB.execute('SELECT 1 FROM videos WHERE id=?',(vid,)).fetchone()
    if not exists or time.monotonic()>deadline or shutil.disk_usage(ROOT).free<134217728:
     process.terminate()
     try:process.wait(timeout=5)
     except subprocess.TimeoutExpired:process.kill();process.wait()
     break
    time.sleep(.5)
   with LOCK:
    exists=DB.execute('SELECT 1 FROM videos WHERE id=?',(vid,)).fetchone()
    if exists and process.returncode==0 and dest.exists():
     final=dest.with_name(vid+'.compat.mp4');os.replace(dest,final);DB.execute("UPDATE video_variants SET state='ready',size=? WHERE id=?",(final.stat().st_size,vid))
    elif exists:DB.execute("UPDATE video_variants SET state='error' WHERE id=?",(vid,))
    DB.commit()
  finally:MEDIA_GATE.release()
 except Exception:
  with LOCK:DB.execute("UPDATE video_variants SET state='error' WHERE id=?",(vid,));DB.commit()
 finally:
  with LOCK:MEDIA_JOBS.discard(vid)
  collect_media()


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

def mail_code(email,code):
 import smtplib
 from email.message import EmailMessage
 host=os.environ.get('OLDY_SMTP_HOST','');sender=os.environ.get('OLDY_SMTP_FROM','')
 if not host or not sender:raise Problem(503,'Владелец ещё не настроил отправку писем. Регистрация откроется после подключения почты.')
 msg=EmailMessage();msg['Subject']='Код подтверждения OldЫ Chat';msg['From']=sender;msg['To']=email;msg.set_content('Код подтверждения: '+code+'\nДействует 10 минут. Никому не сообщайте этот код.')
 try:
  port=int(os.environ.get('OLDY_SMTP_PORT','587'));tls=ssl.create_default_context()
  smtp=smtplib.SMTP_SSL(host,port,context=tls,timeout=15) if port==465 else smtplib.SMTP(host,port,timeout=15)
  with smtp:
   if port!=465:smtp.starttls(context=tls)
   if os.environ.get('OLDY_SMTP_USER'):smtp.login(os.environ['OLDY_SMTP_USER'],os.environ.get('OLDY_SMTP_PASSWORD',''))
   smtp.send_message(msg)
 except Exception:raise Problem(503,'Не удалось отправить письмо. Проверьте адрес или попробуйте позже.')

def verified_snapshot(owner,mid):
 row=DB.execute('SELECT author FROM posts WHERE mid=?',(mid,)).fetchone()
 if row and row[0]==owner:return True
 if DB.execute('SELECT 1 FROM archive WHERE owner=? AND mid=? AND selfcopy=0',(owner,mid)).fetchone():return True
 # A selfcopy alone cannot prove which account originally owned a legacy post.
 return False

class Handler(BaseHTTPRequestHandler):
 protocol_version='HTTP/1.1'
 server_version='OldyRelay/0.4'
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
    if item['kind']!='blob' and offset==0 and (n<12 or raw[4:8]!=b'ftyp'):raise Problem(400,'Нужен обычный MP4 файл')
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
    variant=parse_qs(urlsplit(self.path).query).get('compatible',['0'])[0]=='1'
    file=ROOT/'videos'/(item['id']+('.compat.mp4' if variant else '.mp4'));size=item['size']
    if variant:
     with LOCK:v=DB.execute('SELECT state,size FROM video_variants WHERE id=?',(item['id'],)).fetchone()
     if not v or v[0]!='ready':raise Problem(409,'Видео ещё обрабатывается')
     size=v[1]
    start=0;end=size-1;partial=False
    header=self.headers.get('Range','')
    if header:
     m=re.fullmatch(r'bytes=(\d+)-(\d*)',header)
     if not m:raise Problem(416,'Неверный диапазон')
     start=int(m[1]);end=min(size-1,int(m[2])) if m[2] else size-1;partial=True
     if start>=size or start>end:raise Problem(416,'Неверный диапазон')
    with open(file,'rb') as f:
     self.send_response(206 if partial else 200);self.send_header('Content-Type','application/octet-stream' if item['kind']=='blob' else 'video/mp4');self.send_header('Accept-Ranges','bytes');self.send_header('Cache-Control','private, no-store');self.send_header('X-Content-Type-Options','nosniff');self.send_header('Content-Length',str(end-start+1))
     if partial:self.send_header('Content-Range',f'bytes {start}-{end}/{size}')
     self.end_headers();f.seek(start);left=end-start+1
     while left:
      with LOCK:exists=DB.execute('SELECT 1 FROM videos WHERE id=?',(item['id'],)).fetchone()
      if not exists:self.close_connection=True;break
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
   if path=='/health' and not post:return self.reply({'service':'oldy-chat','version':4,'history':'encrypted-cloud-and-devices'})
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
   if path=='/signup/request' and post:
    rate(('signup-ip',self.client_address[0]),10,3600);email=email_value(data.get('email',''));rate(('signup-email',email),3,3600)
    with LOCK:
     if DB.execute('SELECT 1 FROM emails WHERE email=?',(email,)).fetchone():raise Problem(409,'Этот e-mail уже зарегистрирован. Войдите в аккаунт.')
    ticket=secrets.token_urlsafe(32);code=''.join(secrets.choice('0123456789') for _ in range(6));hashed=hashlib.sha256(ticket.encode()).hexdigest()
    mail_code(email,code)
    with LOCK:
     DB.execute('DELETE FROM signup_codes WHERE expires<? OR email=?',(int(time.time()),email));DB.execute('INSERT INTO signup_codes(ticket,email,digest,expires) VALUES(?,?,?,?)',(hashed,email,hashlib.sha256((ticket+':'+code).encode()).hexdigest(),int(time.time())+600));DB.commit()
    return self.reply({'ticket':ticket,'email':email,'expires_in':600})
   if path in ('/register','/login') and post:
    rate(('auth',self.client_address[0]),20,300)
    nick=str(data.get('nick','')).lower().strip();password=data.get('password','')
    if path=='/login' and '@' in nick:
     with LOCK:email_row=DB.execute('SELECT nick FROM emails WHERE email=?',(nick,)).fetchone()
     nick=email_row[0] if email_row else 'unknown_email_login'
    if not NICK.fullmatch(nick) or not isinstance(password,str) or not 8<=len(password)<=128:raise Problem(400,'Ник: 3–24 латинских символа, цифры или _. Пароль: 8–128 символов')
    if path=='/register':
     rate(('register',self.client_address[0]),5,3600)
     email=email_value(data.get('email',''));ticket=str(data.get('ticket',''));code=str(data.get('code',''));ticket_hash=hashlib.sha256(ticket.encode()).hexdigest()
     with LOCK:
      proof=DB.execute('SELECT email,digest,expires,attempts FROM signup_codes WHERE ticket=?',(ticket_hash,)).fetchone()
      if not proof or proof[0]!=email or proof[2]<time.time() or proof[3]>=5:raise Problem(400,'Сначала запросите код подтверждения на почту')
      DB.execute('UPDATE signup_codes SET attempts=attempts+1 WHERE ticket=?',(ticket_hash,));DB.commit()
      if not hmac.compare_digest(proof[1],hashlib.sha256((ticket+':'+code).encode()).hexdigest()):raise Problem(400,'Неверный код из письма')
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
       if not DB.execute('SELECT 1 FROM signup_codes WHERE ticket=?',(ticket_hash,)).fetchone():raise Problem(400,'Код уже использован')
       DB.execute('INSERT INTO handles VALUES(?,?,?)',(nick,'user',nick))
       DB.execute('INSERT INTO users VALUES(?,?,?,?,?,?)',(nick,name,salt,hashed,enc,sig))
       if email:DB.execute('INSERT INTO emails(email,nick,verified) VALUES(?,?,1)',(email,nick))
       DB.execute('DELETE FROM signup_codes WHERE ticket=?',(ticket_hash,))
       DB.commit()
      except sqlite3.IntegrityError:
       DB.rollback();raise Problem(409,'Этот @адрес или e-mail уже используется')
    else:
     with LOCK:r=DB.execute('SELECT salt,password FROM users WHERE nick=?',(nick,)).fetchone()
     candidate=pw_hash(password,r[0] if r else '00'*16)
     if not r or not hmac.compare_digest(candidate,r[1]):raise Problem(401,'Неверный ник или пароль')
    return self.reply({'token':issue_session(nick),'user':private_account(nick)})
   nick=self.user()
   if path=='/call-config' and post:
    peer=str(data.get('peer',''));public_user(peer)
    if peer==nick:raise Problem(400,'Выберите другого участника')
    if blocked(nick,peer):raise Problem(403,'Звонки этому участнику недоступны')
    rate(('call-config',nick),30,3600)
    host=os.environ.get('OLDY_TURN_HOST','5.42.102.11')
    if not re.fullmatch(r'[a-zA-Z0-9.-]{1,253}',host):raise Problem(503,'Неверная настройка звонков на сервере')
    servers=[{'url':'stun:'+host+':3478'}];secret=os.environ.get('OLDY_TURN_SECRET','')
    if len(secret)>=32:
     username=str(int(time.time())+7200)+':'+nick
     credential=base64.b64encode(hmac.new(secret.encode(),username.encode(),hashlib.sha1).digest()).decode()
     servers += [{'url':'turn:'+host+':3478?transport='+transport,'username':username,'credential':credential} for transport in ('udp','tcp')]
    return self.reply({'servers':servers,'relay':len(servers)>1,'expires_in':7200 if secret else 0})
   if path=='/videos/start' and post:
    kind=str(data.get('kind','creator'));rid=str(data.get('room',''));recipient=str(data.get('recipient',''))
    room=room_info(rid,nick) if rid else None
    if kind=='creator':
     if not creator(nick):raise Problem(403,'Большие MP4 может загружать только владелец приложения')
     if not room or room['owner']!=nick or room['kind']!='channel':raise Problem(403,'Эта загрузка доступна в твоём канале')
    elif kind in ('round','blob'):
     if room and room['kind']=='channel' and room['owner']!=nick and not data.get('thread'):raise Problem(403,'Публикует владелец канала')
     if data.get('thread'):
      with LOCK:thread_exists=DB.execute('SELECT 1 FROM threads WHERE room=? AND post=?',(rid,str(data['thread']))).fetchone()
      if not thread_exists:raise Problem(404,'Комментарии не найдены')
     if not room:
      public_user(recipient)
      if blocked(nick,recipient):raise Problem(403,'Личные сообщения недоступны')
    else:raise Problem(400,'Неизвестная загрузка')
    rate(('video-start',nick),20,3600)
    size=data.get('size');name=str(data.get('name','Видео.mp4'))[:120]
    if not isinstance(size,int) or isinstance(size,bool) or not 12<=size<=(26214416 if kind=='blob' else 33554432 if kind=='round' else 2147483648) or not name.lower().endswith('.mp4'):raise Problem(400,'Превышен размер MP4')
    if shutil.disk_usage(ROOT).free<size+268435456:raise Problem(507,'На сервере недостаточно места для этого видео')
    vid=str(uuid.uuid4())
    with LOCK:DB.execute('INSERT INTO videos(id,owner,room,name,size,kind,recipient) VALUES(?,?,?,?,?,?,?)',(vid,nick,rid,name,size,kind,recipient));DB.commit()
    return self.reply({'id':vid,'received':0,'size':size})
   if path=='/videos/compatible' and post:
    item=video_record(str(data.get('id','')),nick);vid=item['id']
    if not item['ready']:raise Problem(409,'Дождитесь загрузки')
    if item['kind']=='blob':raise Problem(400,'Это зашифрованное вложение')
    with LOCK:
     variant=DB.execute('SELECT state,size FROM video_variants WHERE id=?',(vid,)).fetchone()
     if variant and variant[0]=='ready':return self.reply({'state':'ready','size':variant[1]})
     if vid not in MEDIA_JOBS:
      if variant and variant[0]=='error' and not data.get('retry'):return self.reply({'state':'error'})
      if len(MEDIA_JOBS)>=3:raise Problem(429,'Обрабатываются другие видео. Повторите позже.')
      if shutil.which('ffmpeg') is None:raise Problem(503,'Для обработки обновите сервер')
      rate(('convert',nick),6,3600)
      if shutil.disk_usage(ROOT).free<item['size']+268435456:raise Problem(507,'Для обработки недостаточно места')
      DB.execute("INSERT OR REPLACE INTO video_variants(id,state) VALUES(?,'processing')",(vid,));DB.commit();MEDIA_JOBS.add(vid)
      threading.Thread(target=compatible_video,args=(vid,),daemon=True).start()
    return self.reply({'state':'processing'})
   if path=='/posts/register' and post:
    with LOCK:mid=register_post(nick,data);DB.commit()
    return self.reply({'ok':True,'mid':mid})
   if path in ('/posts/delete','/room/delete') and post:
    rate(('delete',nick),60,60)
    with COND:result=delete_content(nick,str(data.get('room',data.get('id',''))),str(data.get('mid','')),path=='/room/delete')
    return self.reply(result)
   if path=='/deletions' and not post:
    try:after=max(0,int(parse_qs(urlsplit(self.path).query).get('after',['0'])[0]))
    except ValueError:raise Problem(400,'Неверный курсор')
    collect_media()
    with LOCK:rows=DB.execute('SELECT seq,kind,room,mid FROM deletions WHERE owner=? AND seq>? ORDER BY seq LIMIT 100',(nick,after)).fetchall()
    return self.reply({'items':[dict(zip(('seq','kind','room','mid'),r)) for r in rows],'next':rows[-1][0] if rows else after})
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
    with LOCK:
     if DB.execute('SELECT 1 FROM posts WHERE video=?',(item['id'],)).fetchone():raise Problem(409,'Удалите публикацию вместе с видео')
     DB.execute('INSERT OR IGNORE INTO media_garbage VALUES(?)',(item['id'],));DB.execute('DELETE FROM videos WHERE id=?',(item['id'],));DB.execute('DELETE FROM video_variants WHERE id=?',(item['id'],));DB.commit()
    collect_media()
    return self.reply({'ok':True})
   if path=='/threads' and post:
    rid=str(data.get('room',''));post_id=str(data.get('post',''));room=room_info(rid,nick)
    if not re.fullmatch('[a-f0-9-]{36}',post_id):raise Problem(400,'Пост не найден')
    with LOCK:
     if gone(post_id,rid):raise Problem(410,'Публикация удалена')
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
    with LOCK:
     rows=DB.execute('SELECT seq,envelope,delivered,mid,selfcopy FROM archive WHERE owner=? AND seq>? ORDER BY seq LIMIT 30',(nick,after)).fetchall()
     items=[{'seq':r[0],'envelope':json.loads(r[1]),'delivered':bool(r[2]),'verified_snapshot':bool(r[4] and verified_snapshot(nick,r[3]))} for r in rows]
    return self.reply({'items':items,'next':rows[-1][0] if rows else after})
   if path=='/history/store' and post:
    e=data.get('envelope');rate(('history-store',nick),240,60)
    if not isinstance(e,dict) or e.get('from')!=nick or e.get('to')!=nick or e.get('v')!=1 or not re.fullmatch('[a-f0-9-]{36}',str(e.get('id',''))):raise Problem(400,'Неверная резервная копия')
    if set(e)!=set(('v','id','from','to','time','body','iv','key','signature')):raise Problem(400,'Неверные поля')
    for key,limit in [('body',48000),('iv',24),('key',600),('signature',160)]:
     if not isinstance(e.get(key),str) or not 1<=len(e[key])<=limit:raise Problem(400,'Неверный конверт')
     try:base64.b64decode(e[key],validate=True)
     except Exception:raise Problem(400,'Неверный конверт')
    verify_envelope(e,nick)
    with LOCK:
     if gone(e['id']):raise Problem(410,'Публикация удалена')
     DB.execute('INSERT OR IGNORE INTO archive(owner,sender,mid,envelope,delivered,selfcopy) VALUES(?,?,?,?,1,1)',(nick,nick,e['id'],json.dumps(e,separators=(',',':'))));DB.commit()
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
      if gone(mid,str(route),str(e.get('thread',''))):raise Problem(410,'Публикация удалена')
      if action in ('publish','comment'):
       old=DB.execute('SELECT room,author,thread FROM posts WHERE mid=?',(mid,)).fetchone()
       expected=(str(route),nick,str(e.get('thread','')))
       if old and old!=expected:raise Problem(409,'Идентификатор уже использован')
       DB.execute('INSERT OR IGNORE INTO posts(mid,room,author,thread) VALUES(?,?,?,?)',(mid,*expected))
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
