"""Private Yandex Disk app-folder transport. OAuth credentials never leave the API host."""
import hashlib,json,os,re,time,urllib.request,urllib.error
from pathlib import Path
from urllib.parse import urlencode,urlsplit

class DiskError(OSError):pass
class NoRedirect(urllib.request.HTTPRedirectHandler):
 def redirect_request(self,req,fp,code,msg,headers,newurl):return None

class YandexDisk:
 API='https://cloud-api.yandex.net/v1/disk/'
 def __init__(self,token,folder='app:/OldyVideos',opener=None):
  if not re.fullmatch(r'[A-Za-z0-9._~-]{16,2048}',token):raise DiskError('Invalid storage credential')
  if not re.fullmatch(r'app:/[A-Za-z0-9_-]{1,64}',folder):raise DiskError('Use a dedicated app folder')
  self.token,self.folder=token,folder;self.opener=opener or urllib.request.build_opener(NoRedirect())
 @classmethod
 def configured(cls):
  token=os.environ.get('OLDY_DISK_TOKEN','').strip()
  return cls(token,os.environ.get('OLDY_DISK_FOLDER','app:/OldyVideos')) if token else None
 def path(self,name):
  if not re.fullmatch(r'[a-f0-9-]{36}(?:\.(?:compat|480|720|1080))?\.mp4|check-[a-f0-9]{16}\.bin',name):raise DiskError('Invalid media object')
  return self.folder+'/'+name
 def api(self,endpoint,method='GET',**query):
  req=urllib.request.Request(self.API+endpoint+('?' +urlencode(query) if query else ''),method=method,headers={'Authorization':'OAuth '+self.token,'Accept':'application/json','Content-Type':'application/json'})
  try:
   with self.opener.open(req,timeout=30) as r:
    body=r.read(1048577)
    if len(body)>1048576:raise DiskError('Storage response too large')
    return json.loads(body) if body else {}
  except urllib.error.HTTPError as e:
   if e.code==404:return None
   if e.code==409 and endpoint=='resources' and method=='PUT':return {}
   raise DiskError('Storage API HTTP '+str(e.code)) from None
  except (ValueError,urllib.error.URLError,TimeoutError):raise DiskError('Storage API unavailable') from None
 def folder_ready(self):self.api('resources','PUT',path=self.folder)
 @staticmethod
 def safe_url(url):
  parsed=urlsplit(str(url));host=(parsed.hostname or '').lower()
  if parsed.scheme!='https' or parsed.username or parsed.password or parsed.port not in (None,443) or not any(host==suffix or host.endswith('.'+suffix) for suffix in ('yandex.ru','yandex.net','yandex.com','yandexcloud.net')):raise DiskError('Invalid storage transfer address')
  return str(url)
 def transfer(self,url,method='GET',data=None,headers=None):
  # Signed transfer URLs authorize only one object. Never forward the OAuth header.
  url=self.safe_url(url)
  for redirect in range(5):
   try:return self.opener.open(urllib.request.Request(url,data=data,method=method,headers=headers or {}),timeout=60)
   except urllib.error.HTTPError as e:
    if method=='GET' and e.code in (301,302,303,307,308):url=self.safe_url(e.headers.get('Location',''));continue
    raise DiskError('Storage transfer HTTP '+str(e.code)) from None
   except (urllib.error.URLError,TimeoutError):raise DiskError('Storage transfer unavailable') from None
  raise DiskError('Too many storage redirects')
 def metadata(self,name):return self.api('resources',path=self.path(name),fields='type,size,md5,sha256')
 def upload(self,name,file):
  file=Path(file);size=file.stat().st_size;md5=hashlib.md5();sha=hashlib.sha256()
  with file.open('rb') as f:
   for chunk in iter(lambda:f.read(1048576),b''):md5.update(chunk);sha.update(chunk)
  expected=md5.hexdigest();existing=self.metadata(name)
  if existing:
   if existing.get('size')==size and existing.get('md5')==expected:return sha.hexdigest()
   raise DiskError('Remote object differs; local original retained')
  link=self.api('resources/upload',path=self.path(name),overwrite='false')
  if not link or link.get('method','PUT')!='PUT':raise DiskError('Upload unavailable')
  with file.open('rb') as f:
   with self.transfer(link['href'],'PUT',f,{'Content-Type':'application/octet-stream','Content-Length':str(size)}) as response:
    if response.status not in (201,202):raise DiskError('Upload incomplete')
  # A successful upload may still be processing. Never delete the local original before verification.
  for attempt in range(6):
   metadata=self.metadata(name)
   if metadata and metadata.get('size')==size and metadata.get('md5')==expected:return sha.hexdigest()
   if attempt<5:time.sleep(2)
  raise DiskError('Upload awaiting verification; local original retained')
 def open_range(self,name,start,end,size):
  if not 0<=start<=end<size:raise DiskError('Invalid media range')
  link=self.api('resources/download',path=self.path(name))
  if not link:raise DiskError('Remote video missing')
  response=self.transfer(link['href'],headers={'Range':f'bytes={start}-{end}','Accept-Encoding':'identity'})
  expected=f'bytes {start}-{end}/{size}'
  if response.status!=206 or response.headers.get('Content-Range')!=expected or int(response.headers.get('Content-Length','-1'))!=end-start+1:
   response.close();raise DiskError('Storage returned an invalid range')
  return response
 def delete(self,name):
  # Repeated deletion is safe; a 202 is checked on the next maintenance pass.
  self.api('resources','DELETE',path=self.path(name),permanently='true')
  return self.metadata(name) is None
