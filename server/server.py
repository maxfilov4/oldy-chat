#!/usr/bin/env python3
"""Oldy Chat beta relay. No durable message storage. TLS terminates here.
Only accounts, public keys and hashed sessions are persisted. Transit slots
exist for <= 20 seconds only while the sender's HTTP request is active.
"""
import base64, hashlib, hmac, json, os, re, secrets, socket, sqlite3, ssl, threading, time
from collections import defaultdict, deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit, parse_qs
from pathlib import Path
from cryptography.hazmat.primitives.serialization import load_der_public_key
from cryptography.hazmat.primitives.asymmetric import rsa, ec

ROOT = Path(os.environ.get('OLDY_DATA', '/var/lib/oldy-chat'))
LOCK = threading.RLock()
COND = threading.Condition(LOCK)
POLLING = defaultdict(int)
INFLIGHT = {}
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
 DB.commit()

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
def public_user(nick):
 with LOCK:row=DB.execute('SELECT nick,name,enc,sig FROM users WHERE nick=?',(nick,)).fetchone()
 if not row:raise Problem(404,'Пользователь не найден')
 return dict(zip(('nick','name','enc','sig'),row))
def issue_session(nick):
 token=secrets.token_urlsafe(32)
 with LOCK:
  DB.execute('DELETE FROM sessions WHERE expires<?',(time.time(),))
  DB.execute('INSERT INTO sessions VALUES(?,?,?)',(hashlib.sha256(token.encode()).hexdigest(),nick,int(time.time())+2592000))
  DB.commit()
 return token

class Handler(BaseHTTPRequestHandler):
 protocol_version='HTTP/1.1'
 server_version='OldyRelay/0.1'
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
   rate(('all',self.client_address[0]),600,60)
   data={}
   if post:
    if self.headers.get('Transfer-Encoding'):raise Problem(400,'Unsupported request')
    try:n=int(self.headers.get('Content-Length','0'))
    except ValueError:raise Problem(400,'Invalid length')
    if n<0 or n>MAX_BODY:raise Problem(413,'Запрос слишком большой')
    try:data=json.loads(self.rfile.read(n))
    except Exception:raise Problem(400,'Некорректный JSON')
    if not isinstance(data,dict):raise Problem(400,'Некорректный запрос')
   if path=='/health' and not post:return self.reply({'service':'oldy-chat','version':1,'history':'devices-only'})
   if path in ('/register','/login') and post:
    rate(('auth',self.client_address[0]),20,300)
    nick=str(data.get('nick','')).lower().strip();password=data.get('password','')
    if not NICK.fullmatch(nick) or not isinstance(password,str) or not 8<=len(password)<=128:raise Problem(400,'Ник: 3–24 латинских символа, цифры или _. Пароль: 8–128 символов')
    if path=='/register':
     rate(('register',self.client_address[0]),5,3600)
     name=str(data.get('name',nick)).strip()[:40] or nick
     try:
      enc=data['enc'];sig=data['sig']
      if not isinstance(enc,str) or not isinstance(sig,str) or len(enc)>2048 or len(sig)>512:raise ValueError()
      rk=load_der_public_key(base64.b64decode(enc,validate=True));sk=load_der_public_key(base64.b64decode(sig,validate=True))
      if not isinstance(rk,rsa.RSAPublicKey) or rk.key_size!=3072 or not isinstance(sk,ec.EllipticCurvePublicKey) or sk.curve.name!='secp256r1':raise ValueError()
     except Exception:raise Problem(400,'Неверные ключи устройства')
     salt=secrets.token_hex(16);hashed=pw_hash(password,salt)
     with LOCK:
      try:DB.execute('INSERT INTO users VALUES(?,?,?,?,?,?)',(nick,name,salt,hashed,enc,sig));DB.commit()
      except sqlite3.IntegrityError:raise Problem(409,'Этот ник уже занят')
    else:
     with LOCK:r=DB.execute('SELECT salt,password FROM users WHERE nick=?',(nick,)).fetchone()
     candidate=pw_hash(password,r[0] if r else '00'*16)
     if not r or not hmac.compare_digest(candidate,r[1]):raise Problem(401,'Неверный ник или пароль')
    return self.reply({'token':issue_session(nick),'user':public_user(nick)})
   nick=self.user()
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
   if path=='/me' and not post:return self.reply(public_user(nick))
   if path=='/logout' and post:
    with LOCK:DB.execute('DELETE FROM sessions WHERE hash=?',(hashlib.sha256(self.headers['Authorization'][7:].encode()).hexdigest(),));DB.commit()
    return self.reply({'ok':True})
   if path=='/users' and not post:
    q=parse_qs(urlsplit(self.path).query).get('q',[''])[0].lower().strip()
    if not re.fullmatch('[a-z0-9_]{1,24}',q):return self.reply({'users':[]})
    # Escape LIKE wildcard: underscores are literal nickname characters.
    escaped=q.replace('_','\\_')+'%'
    with LOCK:rows=DB.execute("SELECT nick,name,enc,sig FROM users WHERE nick LIKE ? ESCAPE '\\' AND nick!=? LIMIT 20",(escaped,nick)).fetchall()
    return self.reply({'users':[dict(zip(('nick','name','enc','sig'),r)) for r in rows]})
   if path.startswith('/user/') and not post:return self.reply(public_user(path[6:]))
   if path=='/poll' and not post:
    deadline=time.monotonic()+22
    with COND:
     if POLLING[nick]>=2:raise Problem(429,'Уже открыто подключение')
     POLLING[nick]+=1;COND.notify_all()
     try:
      while time.monotonic()<deadline:
       messages=[v['envelope'] for v in INFLIGHT.values() if v['to']==nick and v['expires']>time.monotonic() and not v['done'].is_set()][:20]
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
    for key,limit in [('body',24000),('iv',24),('key',600),('signature',160)]:
     if not isinstance(e.get(key),str) or not 1<=len(e[key])<=limit:raise Problem(400,'Неверный конверт')
     try:base64.b64decode(e[key],validate=True)
     except Exception:raise Problem(400,'Неверный конверт')
    if set(e)!=set(('v','id','from','to','time','body','iv','key','signature')):raise Problem(400,'Лишние поля конверта')
    public_user(target)
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
 p=argparse.ArgumentParser();p.add_argument('--host',default='0.0.0.0');p.add_argument('--port',type=int,default=8443);p.add_argument('--cert');p.add_argument('--key');p.add_argument('--test-http',action='store_true');a=p.parse_args()
 if a.test_http and a.host not in ('127.0.0.1','::1'):p.error('Test HTTP is localhost-only')
 if not a.test_http and not(a.cert and a.key):p.error('TLS certificate and key required')
 init_db();srv=Relay((a.host,a.port),Handler)
 if not a.test_http:
  tls=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);tls.minimum_version=ssl.TLSVersion.TLSv1_2;tls.load_cert_chain(a.cert,a.key);srv.tls=tls
 print('Oldy relay started',flush=True);srv.serve_forever()
if __name__=='__main__':main()
