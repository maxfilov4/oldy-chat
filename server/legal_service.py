"""Versioned agreements, opt-in abuse reports and account deletion. No private-chat scanning."""
import base64, hashlib, hmac, html, json, os, re, secrets, time, uuid
from pathlib import Path
from urllib.parse import parse_qs, urlsplit
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

VERSION='2026-09-10.1'
REASONS=('spam','harassment','violence','sexual_content','child_safety','impersonation','copyright','other')
UGC={'/send','/room/create','/room/update','/profile','/posts/register','/channels/history/store','/videos/start','/videos/cover','/videos/finish','/blobs/create','/blobs/finish','/stickers/offers'}

def init(s):
 s.DB.executescript('''CREATE TABLE IF NOT EXISTS agreements(nick TEXT PRIMARY KEY,version TEXT NOT NULL,accepted_at INTEGER NOT NULL);
 CREATE TABLE IF NOT EXISTS abuse_reports(id TEXT PRIMARY KEY,reporter TEXT NOT NULL,target TEXT NOT NULL,room TEXT NOT NULL,mid TEXT NOT NULL,reason TEXT NOT NULL,body BLOB NOT NULL,created_at INTEGER NOT NULL,status TEXT NOT NULL DEFAULT 'open',resolution TEXT NOT NULL DEFAULT '');
 CREATE INDEX IF NOT EXISTS abuse_status ON abuse_reports(status,created_at);
 CREATE TABLE IF NOT EXISTS publishing_restrictions(nick TEXT PRIMARY KEY,reason TEXT NOT NULL,created_at INTEGER NOT NULL);
 CREATE TABLE IF NOT EXISTS deletion_codes(ticket TEXT PRIMARY KEY,email TEXT NOT NULL,nick TEXT NOT NULL,digest TEXT NOT NULL,expires INTEGER NOT NULL,attempts INTEGER NOT NULL DEFAULT 0);''')
 s.DB.execute('DELETE FROM abuse_reports WHERE created_at<?',(int(time.time())-90*86400,));s.DB.execute('DELETE FROM deletion_codes WHERE expires<?',(int(time.time()),))

def accepted(s,nick):
 row=s.DB.execute('SELECT version FROM agreements WHERE nick=?',(nick,)).fetchone()
 return row[0] if row else ''

def require(s,nick,path):
 if path in UGC or path.startswith('/video-chunk/'):
  with s.LOCK:
   if s.DB.execute('SELECT 1 FROM publishing_restrictions WHERE nick=?',(nick,)).fetchone():raise s.Problem(403,'Публикации ограничены модератором. Откройте Правила и поддержка для обращения.')
   if accepted(s,nick)!=VERSION:raise s.Problem(428,'Перед публикацией примите обновлённые правила: Настройки → Правила и поддержка.')

def texts():return json.loads(Path(__file__).with_name('legal_texts.json').read_text())

def info(s):
 try:cfg=json.loads((s.ROOT/'legal-config.json').read_text())
 except (OSError,ValueError):cfg={}
 result={k:str(cfg.get(k,''))[:500] for k in ('operator','support_email','child_safety_email','public_url')}
 result.update(version=VERSION,configured=all(result.values()))
 return result

def encrypt(s,value):
 nonce=secrets.token_bytes(12);return nonce+AESGCM(s.CHANNEL_KEY).encrypt(nonce,json.dumps(value,ensure_ascii=False).encode(),b'oldi-report-v1')
def decrypt(s,value):return json.loads(AESGCM(s.CHANNEL_KEY).decrypt(value[:12],value[12:],b'oldi-report-v1'))

def remove_account(s,nick,journal=True):
 """Caller holds COND. The independent deletion journal survives ordinary database rollback."""
 row=s.DB.execute('SELECT sig FROM users WHERE nick=?',(nick,)).fetchone()
 if not row:return {'ok':True}
 if journal:
  path=s.ROOT/'account-deletions.jsonl'
  with path.open('a') as out:
   os.chmod(path,0o600);out.write(json.dumps({'key':hashlib.sha256((nick+':'+row[0]).encode()).hexdigest(),'at':int(time.time())})+'\n');out.flush();os.fsync(out.fileno())
 for (rid,) in s.DB.execute('SELECT id FROM rooms WHERE owner=?',(nick,)).fetchall():s.delete_content(nick,rid,'',True)
 for mid,rid in s.DB.execute('SELECT mid,room FROM posts WHERE author=?',(nick,)).fetchall():
  if s.DB.execute('SELECT 1 FROM posts WHERE mid=?',(mid,)).fetchone():s.delete_content(nick,rid,mid)
 mids={r[0] for r in s.DB.execute('SELECT mid FROM archive WHERE sender=?',(nick,))}
 for mid in mids:
  for (owner,) in s.DB.execute('SELECT DISTINCT owner FROM archive WHERE mid=?',(mid,)).fetchall():s.DB.execute("INSERT INTO deletions(owner,kind,room,mid) VALUES(?,'post','',?)",(owner,mid))
  s.DB.execute('DELETE FROM archive WHERE mid=?',(mid,));s.DB.execute("INSERT OR IGNORE INTO deleted_posts VALUES(?,'')",(mid,))
 for (vid,) in s.DB.execute('SELECT id FROM videos WHERE owner=?',(nick,)).fetchall():
  s.DB.execute('INSERT OR IGNORE INTO media_garbage VALUES(?)',(vid,));s.DB.execute('DELETE FROM video_variants WHERE id=?',(vid,));s.DB.execute('DELETE FROM videos WHERE id=?',(vid,))
 for (email,) in s.DB.execute('SELECT email FROM emails WHERE nick=? UNION SELECT email FROM pending_emails WHERE nick=?',(nick,nick)).fetchall():
  s.DB.execute('DELETE FROM signup_codes WHERE email=?',(email,));s.DB.execute('DELETE FROM login_codes WHERE email=?',(email,));s.DB.execute('DELETE FROM deletion_codes WHERE email=?',(email,))
 for table in ('sessions','profiles','members','key_backups','capabilities','room_bans','emails','email_codes','login_codes','pending_emails','email_login_accounts','contact_discovery','agreements','publishing_restrictions','deletion_codes'):
  s.DB.execute('DELETE FROM '+table+' WHERE nick=?',(nick,))
 for table in ('blocks',):s.DB.execute('DELETE FROM '+table+' WHERE owner=? OR target=?',(nick,nick))
 s.DB.execute('DELETE FROM abuse_reports WHERE reporter=? OR target=?',(nick,nick))
 s.DB.execute('DELETE FROM chat_clears WHERE owner=? OR peer=?',(nick,nick))
 for table in ('archive','sticker_offers','hidden_messages','deletions'):s.DB.execute('DELETE FROM '+table+' WHERE owner=?',(nick,))
 s.DB.execute("DELETE FROM handles WHERE kind='user' AND ref=?",(nick,))
 # Registration sequence stays monotonic without retaining the old handle.
 s.DB.execute("UPDATE account_numbers SET nick='deleted:'||number WHERE nick=?",(nick,));s.DB.execute('DELETE FROM users WHERE nick=?',(nick,))
 for key,slot in list(s.INFLIGHT.items()):
  e=slot['envelope']
  if nick in (e.get('from'),e.get('to')):slot['done'].set();s.INFLIGHT.pop(key,None)
 for key in list(s.TYPING):
  if nick in key:s.TYPING.pop(key,None)
 s.DB.commit();s.COND.notify_all();s.collect_media()
 return {'ok':True,'deleted':True,'media_cleanup_pending':bool(s.DB.execute('SELECT 1 FROM media_garbage LIMIT 1').fetchone())}

def replay(s):
 path=s.ROOT/'account-deletions.jsonl'
 if not path.exists():return
 keys=set()
 with path.open() as inp:
  for line in inp:
   try:keys.add(json.loads(line)['key'])
   except (ValueError,KeyError):continue
 with s.COND:
  for nick,sig in s.DB.execute('SELECT nick,sig FROM users').fetchall():
   if hashlib.sha256((nick+':'+sig).encode()).hexdigest() in keys:remove_account(s,nick,False)

def deletion_request(s,data,ip):
 s.rate(('delete-request-ip',ip),8,3600);email=s.email_value(data.get('email',''));s.rate(('delete-request-email',email),3,3600)
 ticket=secrets.token_urlsafe(32);hashed=hashlib.sha256(ticket.encode()).hexdigest()
 with s.LOCK:row=s.DB.execute('SELECT nick FROM emails WHERE email=? AND verified=1',(email,)).fetchone()
 if row:
  code=''.join(secrets.choice('0123456789') for _ in range(6));s.mail_code(email,code,purpose='delete')
  with s.LOCK:
   s.DB.execute('DELETE FROM deletion_codes WHERE email=? OR expires<?',(email,int(time.time())));s.DB.execute('INSERT INTO deletion_codes(ticket,email,nick,digest,expires) VALUES(?,?,?,?,?)',(hashed,email,row[0],hashlib.sha256(('delete:'+ticket+':'+code).encode()).hexdigest(),int(time.time())+600));s.DB.commit()
 return {'ticket':ticket,'expires_in':600,'message':'If this is a verified account address, a deletion code was sent. / Если адрес подтверждён, код удаления отправлен.'}

def deletion_confirm(s,data,ip):
 s.rate(('delete-confirm-ip',ip),20,600)
 if data.get('confirm')!='DELETE':raise s.Problem(400,'Подтвердите удаление аккаунта и данных')
 email=s.email_value(data.get('email',''));ticket=str(data.get('ticket',''));code=str(data.get('code',''));hashed=hashlib.sha256(ticket.encode()).hexdigest()
 with s.COND:
  row=s.DB.execute('SELECT email,nick,digest,expires,attempts FROM deletion_codes WHERE ticket=?',(hashed,)).fetchone()
  if not row or row[0]!=email or row[3]<time.time() or row[4]>=5:raise s.Problem(400,'Запросите новый код удаления')
  s.DB.execute('UPDATE deletion_codes SET attempts=attempts+1 WHERE ticket=?',(hashed,));s.DB.commit()
  if not hmac.compare_digest(row[2],hashlib.sha256(('delete:'+ticket+':'+code).encode()).hexdigest()):raise s.Problem(400,'Неверный код удаления')
  if not s.DB.execute('SELECT 1 FROM emails WHERE email=? AND nick=? AND verified=1',(email,row[1])).fetchone():raise s.Problem(400,'Адрес изменился. Запросите новый код')
  return remove_account(s,row[1])

def api(s,h,path,post,data,nick):
 if path=='/legal/status' and not post:return dict(info(s),accepted_version=accepted(s,nick))
 if path=='/legal/accept' and post:
  if data.get('version')!=VERSION or data.get('accept') is not True:raise s.Problem(400,'Откройте и примите актуальную версию правил')
  with s.LOCK:s.DB.execute('INSERT OR REPLACE INTO agreements VALUES(?,?,?)',(nick,VERSION,int(time.time())));s.DB.commit()
  return {'ok':True,'version':VERSION}
 if path=='/account/delete' and post:
  if data.get('confirm')!=nick:raise s.Problem(400,'Для подтверждения введите свой @ник без @')
  # Existing authenticated session is required, with a separate explicit destructive confirmation.
  with s.COND:return remove_account(s,nick)
 if path=='/reports' and post:
  s.rate(('report',nick),8,3600);reason=data.get('reason');target=str(data.get('target',''));mid=str(data.get('mid',''));rid=str(data.get('room',''));excerpt=str(data.get('excerpt',''));detail=str(data.get('detail',''))
  if reason not in REASONS or len(excerpt)>4000 or len(detail)>2000 or data.get('share_consent') is not True:raise s.Problem(400,'Выберите причину и подтвердите отправку указанных данных модератору')
  if target==nick:raise s.Problem(400,'Выберите другого участника')
  with s.LOCK:
   s.public_user(target)
   if mid:
    record=s.DB.execute('SELECT room,author FROM posts WHERE mid=?',(mid,)).fetchone()
    if not record or record!=(rid,target):raise s.Problem(400,'Автор или сообщение не подтверждены сервером')
    if rid:
     if not s.DB.execute('SELECT 1 FROM members WHERE room=? AND nick=?',(rid,nick)).fetchone():raise s.Problem(403,'Сообщение недоступно')
    elif not s.DB.execute('SELECT 1 FROM archive WHERE owner=? AND sender=? AND mid=? AND selfcopy=0',(nick,target,mid)).fetchone():raise s.Problem(403,'Сообщение недоступно')
   elif rid:raise s.Problem(400,'Укажите сообщение сообщества')
   identity=str(uuid.uuid4());s.DB.execute('INSERT INTO abuse_reports(id,reporter,target,room,mid,reason,body,created_at) VALUES(?,?,?,?,?,?,?,?)',(identity,nick,target,rid,mid,reason,encrypt(s,{'excerpt':excerpt,'detail':detail}),int(time.time())));s.DB.commit()
  return {'ok':True,'id':identity}
 if path=='/reports/mine' and not post:
  with s.LOCK:rows=s.DB.execute('SELECT id,reason,status,resolution,created_at FROM abuse_reports WHERE reporter=? ORDER BY created_at DESC LIMIT 100',(nick,)).fetchall()
  return {'reports':[dict(zip(('id','reason','status','resolution','created_at'),r)) for r in rows]}
 if path.startswith('/moderation/'):
  if not s.creator(nick):raise s.Problem(403,'Только назначенный модератор')
  if path=='/moderation/reports' and not post:
   with s.LOCK:
    s.DB.execute('DELETE FROM abuse_reports WHERE created_at<?',(int(time.time())-90*86400,));s.DB.commit()
    rows=s.DB.execute("SELECT id,reporter,target,room,mid,reason,body,created_at,status,resolution FROM abuse_reports ORDER BY status='open' DESC,reason='child_safety' DESC,created_at LIMIT 100").fetchall()
   result=[]
   for row in rows:
    item=dict(zip(('id','reporter','target','room','mid','reason','body','created_at','status','resolution'),row));item.update(decrypt(s,item.pop('body')));result.append(item)
   return {'reports':result}
  if path=='/moderation/action' and post:
   action=data.get('action');identity=str(data.get('id',''));note=str(data.get('note',''))[:500]
   if action not in ('remove_message','restrict_user','restore_user','dismiss','resolve'):raise s.Problem(400,'Неизвестное действие')
   with s.COND:
    row=s.DB.execute('SELECT target,room,mid FROM abuse_reports WHERE id=?',(identity,)).fetchone()
    if not row:raise s.Problem(404,'Жалоба не найдена')
    if action=='remove_message':
     if not row[2]:raise s.Problem(400,'В жалобе нет сообщения')
     s.delete_content(nick,row[1],row[2])
    elif action=='restrict_user':
     if s.creator(row[0]):raise s.Problem(403,'Нельзя ограничить назначенного модератора')
     s.DB.execute('INSERT OR REPLACE INTO publishing_restrictions VALUES(?,?,?)',(row[0],note,int(time.time())))
    elif action=='restore_user':s.DB.execute('DELETE FROM publishing_restrictions WHERE nick=?',(row[0],))
    s.DB.execute('UPDATE abuse_reports SET status=?,resolution=? WHERE id=?',('dismissed' if action=='dismiss' else 'resolved',action+': '+note,identity));s.DB.commit()
   return {'ok':True}
 return None

def page(s,key,lang):
 cfg=info(s);docs=texts();lang='en' if lang=='en' else 'ru';t=docs[lang];key=key if key in t else 'privacy';doc=t[key]
 esc=html.escape
 contact=esc(cfg['operator'])+' · '+esc(cfg['support_email']) if cfg['configured'] else ('Operator contact details are not configured yet.' if lang=='en' else 'Контакты оператора пока не настроены.')
 nav=' · '.join('<a href="/legal/'+k+'?lang='+lang+'">'+esc(t[k]['title'])+'</a>' for k in ('privacy','terms','community','delete'))
 body=''.join('<p>'+esc(x).replace('\n','<br>')+'</p>' for x in doc['body'])
 form=''
 if key=='delete':form='''<form id="request"><label>E-mail <input name="email" type="email" autocomplete="email" required maxlength="254"></label><button>Получить код удаления / Request deletion code</button></form><form id="confirm" hidden><label>Код удаления / Deletion code <input name="code" inputmode="numeric" pattern="[0-9]{6}" required maxlength="6"></label><label><input name="accept" type="checkbox" required> Удалить аккаунт и данные без возможности восстановления / Permanently delete account and data</label><button>Удалить / Delete permanently</button></form><p id="status" role="status"></p><script src="/legal/delete.js" defer></script>'''
 return '<!doctype html><html lang="'+lang+'"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>'+esc(doc['title'])+' · Oldi Chat</title><style>body{font:17px/1.6 system-ui;max-width:780px;margin:32px auto;padding:0 20px;color:#19283d;background:#f5f8fb}a{color:#245f9d}nav{font-size:14px}h1{line-height:1.2}input,button{font:inherit;margin:12px 0;padding:12px;max-width:100%;box-sizing:border-box}label{display:block}button{background:#23638c;color:white;border:0;border-radius:10px}footer{font-size:14px;color:#546476}</style><nav>'+nav+' · <a href="?lang='+('ru' if lang=='en' else 'en')+'">RU / EN</a></nav><h1>'+esc(doc['title'])+'</h1><p>Oldi Chat · '+VERSION+'</p>'+body+form+'<footer>'+contact+'<br>Child safety: '+esc(cfg['child_safety_email'])+'</footer></html>'

DELETE_JS='''"use strict";let ticket="",email="";const status=document.getElementById("status");async function send(path,body){const r=await fetch(path,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify(body),credentials:"omit",redirect:"error"});const data=await r.json();if(!r.ok)throw Error(data.error||"Request failed");return data;}document.getElementById("request").onsubmit=async e=>{e.preventDefault();const form=e.target,button=form.querySelector("button");button.disabled=true;try{email=new FormData(form).get("email");const r=await send("/account/deletion/request",{email});ticket=r.ticket;status.textContent=r.message;document.getElementById("confirm").hidden=false;}catch(err){status.textContent=err.message;}finally{button.disabled=false;}};document.getElementById("confirm").onsubmit=async e=>{e.preventDefault();const form=e.target,button=form.querySelector("button");button.disabled=true;try{await send("/account/deletion/confirm",{email,ticket,code:new FormData(form).get("code"),confirm:"DELETE"});status.textContent="Аккаунт удалён. Account deleted.";form.hidden=true;document.getElementById("request").hidden=true;ticket="";}catch(err){status.textContent=err.message;}finally{button.disabled=false;}};'''

def public(s,h,path,post,data):
 if path in ('/account/deletion/request','/account/deletion/confirm') and post:
  h.reply(deletion_request(s,data,h.client_address[0]) if path.endswith('/request') else deletion_confirm(s,data,h.client_address[0]));return True
 if path=='/legal/info' and not post:h.reply(info(s));return True
 if path in ('/legal/privacy','/legal/terms','/legal/community','/legal/delete','/legal/delete.js') and not post:
  lang=parse_qs(urlsplit(h.path).query).get('lang',['ru'])[0];js=path=='/legal/delete.js';raw=(DELETE_JS if js else page(s,path.rsplit('/',1)[1],lang)).encode()
  h.send_response(200);h.send_header('Content-Type','application/javascript; charset=utf-8' if js else 'text/html; charset=utf-8');h.send_header('Content-Length',str(len(raw)));h.send_header('Cache-Control','no-store');h.send_header('X-Content-Type-Options','nosniff');h.send_header('Referrer-Policy','no-referrer');h.send_header('Content-Security-Policy',"default-src 'none'; style-src 'unsafe-inline'; script-src 'self'; connect-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'");h.end_headers();h.wfile.write(raw);return True
 return False


def maintenance(s):
 while True:
  time.sleep(3600)
  with s.LOCK:
   s.DB.execute('DELETE FROM abuse_reports WHERE created_at<?',(int(time.time())-90*86400,));s.DB.execute('DELETE FROM deletion_codes WHERE expires<?',(int(time.time()),));s.DB.commit()
