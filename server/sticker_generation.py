"""Opt-in photo-to-character animation through the official OpenAI Images API.
No local photo filter is substituted if the provider is unavailable. Input images
are decoded/re-encoded in memory, never logged or saved. Results are encrypted.
"""
import base64, hashlib, io, json, os, re, secrets, threading, time, urllib.request, urllib.error
from concurrent.futures import ThreadPoolExecutor
from PIL import Image, ImageChops, ImageStat, ImageOps
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

ACTIONS={
 'wave':'raises a hand, waves it side to side with articulated elbow and fingers, then lowers it',
 'laugh':'smiles, opens the mouth in a warm laugh with changing cheeks and eyelids, then relaxes',
 'yes':'raises a hand into a thumbs-up, nods with changing face expression, then returns to the starting pose',
}
MODELS={'gpt-image-1','gpt-image-1.5','gpt-image-2.5-sunburst'}
WORK=ThreadPoolExecutor(max_workers=1,thread_name_prefix='sticker-generation')
GATE=threading.BoundedSemaphore(2)

class GenerationError(Exception):
 def __init__(self,code):self.code=code;super().__init__(code)

def configuration():
 key=os.environ.get('OLDY_STICKER_OPENAI_KEY','')
 model=os.environ.get('OLDY_STICKER_MODEL','gpt-image-1')
 allowed={x.strip() for x in os.environ.get('OLDY_STICKER_USERS','').split(',') if x.strip()}
 try:daily=max(1,min(100,int(os.environ.get('OLDY_STICKER_DAILY_LIMIT','10'))))
 except ValueError:daily=10
 return key,model,allowed,daily

def enabled(nick):
 key,model,allowed,_=configuration()
 return bool(key) and model in MODELS and ('*' in allowed or nick in allowed)

def init(s):
 s.DB.execute('''CREATE TABLE IF NOT EXISTS sticker_jobs(id TEXT PRIMARY KEY,owner TEXT NOT NULL,digest TEXT NOT NULL,state TEXT NOT NULL,error TEXT NOT NULL DEFAULT '',created INTEGER NOT NULL,expires INTEGER NOT NULL,output BLOB)''')
 if 'stage' not in {row[1] for row in s.DB.execute('PRAGMA table_info(sticker_jobs)')}:
  s.DB.execute("ALTER TABLE sticker_jobs ADD COLUMN stage TEXT NOT NULL DEFAULT 'queued'")
 s.DB.execute("UPDATE sticker_jobs SET state='failed',error='SERVER_RESTARTED' WHERE state='generating'")
 cleanup(s)

def cleanup(s):
 with s.LOCK:
  s.DB.execute("UPDATE sticker_jobs SET output=NULL,state=CASE WHEN state='ready' THEN 'expired' ELSE state END WHERE expires<?",(int(time.time()),))
  s.DB.execute('DELETE FROM sticker_jobs WHERE created<?',(int(time.time())-2*86400,))
  s.DB.commit()

def maintenance(s):
 while True:
  try:cleanup(s)
  except Exception:pass
  time.sleep(60)

def sanitized_image(encoded):
 if not isinstance(encoded,str) or not 1<=len(encoded)<=1250000:raise GenerationError('PHOTO_SIZE')
 try:
  raw=base64.b64decode(encoded,validate=True)
  if len(raw)>900000:raise ValueError()
  with Image.open(io.BytesIO(raw)) as original:
   if original.format not in ('JPEG','PNG','WEBP') or not 64<=original.width<=2048 or not 64<=original.height<=2048 or getattr(original,'n_frames',1)!=1:raise ValueError()
   original.load();image=ImageOps.exif_transpose(original).convert('RGB');image.thumbnail((768,768))
   out=io.BytesIO();image.save(out,format='JPEG',quality=92);return out.getvalue()
 except Exception as e:raise GenerationError('PHOTO_INVALID') from e

class NoRedirect(urllib.request.HTTPRedirectHandler):
 def redirect_request(self,*args,**kwargs):return None

def provider_error(error):
 # Return only our fixed public codes, never the provider body (which can echo input).
 try:
  payload=json.loads(error.read(16384)).get('error',{})
  code=str(payload.get('code',''));kind=str(payload.get('type',''));message=str(payload.get('message','')).lower()
 except Exception:code=kind=message=''
 if code in ('unsupported_country_region_territory','country_not_supported'):return 'PROVIDER_REGION'
 if code in ('insufficient_permissions','insufficient_scope') or 'missing scopes' in message or 'insufficient permissions' in message:return 'PROVIDER_SCOPE'
 if code in ('insufficient_quota','billing_hard_limit_reached','billing_not_active') or kind=='insufficient_quota':return 'PROVIDER_BILLING'
 if code in ('model_not_found','model_not_available'):return 'PROVIDER_MODEL'
 if code in ('organization_verification_required','verification_required') or 'organization must be verified' in message or 'verify your organization' in message:return 'PROVIDER_VERIFICATION'
 if code in ('content_policy_violation','moderation_blocked'):return 'PROVIDER_REJECTED'
 return {400:'PROVIDER_REJECTED',401:'PROVIDER_CREDENTIALS',403:'PROVIDER_ACCESS',429:'PROVIDER_LIMIT'}.get(error.code,'PROVIDER_UNAVAILABLE')

def render_sheet(photo,action):
 key,model,_,_=configuration()
 prompt=(
  'Create an original 2D cartoon character animation SPRITE SHEET from the reference photo. '
  'Redraw the person fully as a charming hand-drawn cartoon while preserving recognizable facial features, '
  'hair, body proportions, clothing and natural color palette. No photographic textures, no color-filtered photograph. '
  'Exactly SIX chronological animation frames in a strict 3-column by 2-row grid on a 1536x1024 transparent canvas. '
  'Every cell is exactly 512x512. Each cell contains the SAME complete centered upper-body character, '
  'same head size, baseline and camera. Keep all hands and the character inside the central 440x440 area of each cell. '
  'No borders, grid lines, labels, numbers, captions, watermark, background, or props from the photo. '
  'Across the six frames the character '+ACTIONS[action]+'. '
  'The ACTION must be redrawn as different joint/hand/face poses; do NOT rotate, bounce, translate or scale '
  'a single image as the animation. Frame 6 returns close to frame 1 for a loop. '
  'Use expressive but anatomically coherent poses, consistent identity and clean sticker linework.'
 )
 boundary='OldiSticker'+secrets.token_hex(16);parts=[]
 for name,value in {'model':model,'prompt':prompt,'n':'1','size':'1536x1024','quality':'high','background':'transparent','output_format':'png','input_fidelity':'high'}.items():
  parts.append(('--'+boundary+'\r\nContent-Disposition: form-data; name="'+name+'"\r\n\r\n'+value+'\r\n').encode())
 parts.extend([('--'+boundary+'\r\nContent-Disposition: form-data; name="image[]"; filename="reference.jpg"\r\nContent-Type: image/jpeg\r\n\r\n').encode(),photo,b'\r\n',('--'+boundary+'--\r\n').encode()])
 request=urllib.request.Request('https://api.openai.com/v1/images/edits',data=b''.join(parts),headers={'Authorization':'Bearer '+key,'Content-Type':'multipart/form-data; boundary='+boundary,'Accept':'application/json'},method='POST')
 try:
  # No redirect, retry or arbitrary provider URL: avoid key leaks and duplicate charges.
  with urllib.request.build_opener(NoRedirect()).open(request,timeout=240) as response:
   data=response.read(24000001)
   if len(data)>24000000:raise GenerationError('PROVIDER_RESPONSE_SIZE')
   encoded=json.loads(data)['data'][0]['b64_json'];result=base64.b64decode(encoded,validate=True)
   if len(result)>16000000:raise GenerationError('PROVIDER_RESPONSE_SIZE')
   return result
 except urllib.error.HTTPError as e:
  raise GenerationError(provider_error(e)) from None
 except GenerationError:raise
 except Exception:raise GenerationError('PROVIDER_UNAVAILABLE') from None

def animation(sheet):
 try:
  with Image.open(io.BytesIO(sheet)) as source:
   if source.size!=(1536,1024) or source.format not in ('PNG','WEBP'):raise GenerationError('ANIMATION_LAYOUT')
   source.load();rgba=source.convert('RGBA')
  frames=[rgba.crop((x*512,y*512,(x+1)*512,(y+1)*512)) for y in range(2) for x in range(3)]
  # Reject empty cells, non-transparent contact sheets and repeated static pictures.
  for frame in frames:
   alpha=frame.getchannel('A');hist=alpha.histogram()
   if sum(hist[:16])<512*512*.12 or sum(hist[128:])<512*512*.05:raise GenerationError('ANIMATION_LAYOUT')
  if len({hashlib.sha256(f.tobytes()).digest() for f in frames})<4:raise GenerationError('ANIMATION_STATIC')
  delta=[sum(ImageStat.Stat(ImageChops.difference(frames[i].convert('RGB'),frames[i+1].convert('RGB'))).mean) for i in range(5)]
  if max(delta)<2:raise GenerationError('ANIMATION_STATIC')
  for size,quality in ((384,82),(320,75),(256,68)):
   scaled=[f.resize((size,size),Image.Resampling.LANCZOS) for f in frames];out=io.BytesIO()
   scaled[0].save(out,format='WEBP',save_all=True,append_images=scaled[1:],duration=[220,160,160,160,160,300],loop=0,quality=quality,method=4,minimize_size=True)
   value=out.getvalue()
   if len(value)<=350000:
    with Image.open(io.BytesIO(value)) as verify:
     if getattr(verify,'n_frames',1)<4:raise GenerationError('ANIMATION_STATIC')
    return value
  raise GenerationError('ANIMATION_SIZE')
 except GenerationError:raise
 except Exception:raise GenerationError('ANIMATION_LAYOUT') from None

def public(s,nick,jid):
 with s.LOCK:row=s.DB.execute('SELECT owner,state,error,expires,output,stage,created FROM sticker_jobs WHERE id=?',(jid,)).fetchone()
 if not row or row[0]!=nick:raise s.Problem(404,'STICKER_JOB_NOT_FOUND')
 state=row[1] if row[3]>=int(time.time()) else 'expired'
 result={'id':jid,'state':state,'error':row[2],'stage':row[5],'elapsed_seconds':max(0,int(time.time())-row[6])}
 if state=='ready' and row[4]:
  encrypted=row[4];raw=AESGCM(s.CHANNEL_KEY).decrypt(encrypted[:12],encrypted[12:],('oldi-generated-sticker:'+nick+':'+jid).encode())
  result.update(image=base64.b64encode(raw).decode(),sha256=hashlib.sha256(raw).hexdigest())
 return result

def run(s,nick,jid,photo,action):
 try:
  with s.LOCK:s.DB.execute("UPDATE sticker_jobs SET stage='drawing' WHERE id=? AND owner=?",(jid,nick));s.DB.commit()
  sheet=render_sheet(photo,action)
  with s.LOCK:s.DB.execute("UPDATE sticker_jobs SET stage='encoding' WHERE id=? AND owner=?",(jid,nick));s.DB.commit()
  raw=animation(sheet);nonce=secrets.token_bytes(12)
  encrypted=nonce+AESGCM(s.CHANNEL_KEY).encrypt(nonce,raw,('oldi-generated-sticker:'+nick+':'+jid).encode())
  with s.LOCK:s.DB.execute("UPDATE sticker_jobs SET state='ready',output=? WHERE id=? AND owner=? AND state='generating'",(encrypted,jid,nick));s.DB.commit()
 except Exception as error:
  code=error.code if isinstance(error,GenerationError) else 'GENERATION_FAILED'
  try:
   with s.LOCK:s.DB.execute("UPDATE sticker_jobs SET state='failed',error=? WHERE id=? AND owner=? AND state='generating'",(code,jid,nick));s.DB.commit()
  except Exception:pass
 finally:GATE.release()

def api(s,path,post,data,nick):
 if not path.startswith('/stickers/generation') and path!='/stickers/generate':return None
 if path=='/stickers/generation/config' and not post:
  return {'enabled':enabled(nick),'provider':'OpenAI','consent_version':1,'actions':list(ACTIONS),'retention_seconds':3600,'max_photo_bytes':900000}
 if path.startswith('/stickers/generation/') and not post:
  jid=path.rsplit('/',1)[-1]
  if not re.fullmatch('[a-f0-9-]{36}',jid):raise s.Problem(400,'STICKER_JOB_INVALID')
  s.rate(('sticker-poll',nick),120,60);return public(s,nick,jid)
 if path=='/stickers/generate' and post:
  if set(data)-{'id','image','action','consent_version'} or data.get('consent_version')!=1 or type(data.get('consent_version')) is not int:raise s.Problem(400,'STICKER_CONSENT_REQUIRED')
  jid=data.get('id');action=data.get('action')
  if not isinstance(jid,str) or not re.fullmatch('[a-f0-9-]{36}',jid) or action not in ACTIONS:raise s.Problem(400,'STICKER_JOB_INVALID')
  if not enabled(nick):raise s.Problem(503,'STICKER_GENERATOR_NOT_CONFIGURED')
  s.rate(('sticker-create',nick),6,60)
  try:photo=sanitized_image(data.get('image'))
  except GenerationError as e:raise s.Problem(400,e.code)
  digest=hashlib.sha256(action.encode()+photo).hexdigest();now=int(time.time());_,_,_,daily=configuration()
  with s.LOCK:
   previous=s.DB.execute('SELECT owner,digest FROM sticker_jobs WHERE id=?',(jid,)).fetchone()
   if previous:
    if previous!=(nick,digest):raise s.Problem(409,'STICKER_JOB_CONFLICT')
    return public(s,nick,jid)
   today=now//86400*86400
   if s.DB.execute('SELECT COUNT(*) FROM sticker_jobs WHERE created>=?',(today,)).fetchone()[0]>=daily or s.DB.execute('SELECT COUNT(*) FROM sticker_jobs WHERE owner=? AND created>=?',(nick,today)).fetchone()[0]>=3:raise s.Problem(429,'STICKER_DAILY_LIMIT')
   if not GATE.acquire(blocking=False):raise s.Problem(429,'STICKER_QUEUE_FULL')
   try:
    s.DB.execute("INSERT INTO sticker_jobs(id,owner,digest,state,created,expires) VALUES(?,?,?,'generating',?,?)",(jid,nick,digest,now,now+3600));s.DB.commit()
    WORK.submit(run,s,nick,jid,photo,action)
   except Exception:GATE.release();raise
  return {'id':jid,'state':'generating'}
 raise s.Problem(404,'STICKER_ENDPOINT_NOT_FOUND')
