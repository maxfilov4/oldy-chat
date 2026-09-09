"""Independent WebRTC peer for emulator tests only; never included in a server release."""
import asyncio, array, base64, fractions, json, math, os, ssl, threading, time, urllib.request, uuid
from aiortc import RTCPeerConnection, RTCConfiguration, RTCSessionDescription, AudioStreamTrack
from av import AudioFrame
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa, ec, padding
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

def b64(b): return base64.b64encode(b).decode()
def un64(s): return base64.b64decode(s)
def header(e): return 'oldy-v1\n'+e['id']+'\n'+e['from']+'\n'+e['to']+'\n'+str(e['time'])
def signed(e): return (header(e)+'\n'+e['key']+'\n'+e['iv']+'\n'+e['body']).encode()
OAEP=padding.OAEP(mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=None)

class Tone(AudioStreamTrack):
 def __init__(self): super().__init__(); self.samples=0; self.started=None
 async def recv(self):
  if self.started is None: self.started=time.monotonic()
  await asyncio.sleep(max(0,self.started+self.samples/48000-time.monotonic()))
  frame=AudioFrame(format='s16',layout='mono',samples=960)
  wave=array.array('h',(int(6000*math.sin((self.samples+i)*2*math.pi*330/48000)) for i in range(960)))
  frame.planes[0].update(wave.tobytes());frame.sample_rate=48000;frame.pts=self.samples;frame.time_base=fractions.Fraction(1,48000);self.samples+=960
  return frame

class CallPeer:
 def __init__(self,certificate):
  self.ssl=ssl.create_default_context(cafile=str(certificate));self.token='';self.ready=False;self.error='';self.received=0;self.connected=False;self.ends=0;self.sid='';self.pc=None
  self.loop=asyncio.new_event_loop();self.thread=threading.Thread(target=self.run,daemon=True);self.thread.start()
 def run(self):
  asyncio.set_event_loop(self.loop);self.loop.create_task(self.begin());self.loop.run_forever()
 def request(self,path,body=None):
  headers={'Content-Type':'application/json','Connection':'close'}
  if self.token:headers['Authorization']='Bearer '+self.token
  r=urllib.request.Request('https://127.0.0.1:8444'+path, data=None if body is None else json.dumps(body).encode(), headers=headers)
  with urllib.request.urlopen(r,context=self.ssl,timeout=30) as response:return json.load(response)
 async def api(self,path,body=None):return await asyncio.to_thread(self.request,path,body)
 async def begin(self):
  try:
   self.rsa=await asyncio.to_thread(rsa.generate_private_key,public_exponent=65537,key_size=3072);self.ec=ec.generate_private_key(ec.SECP256R1())
   pub=lambda key:b64(key.public_key().public_bytes(serialization.Encoding.DER,serialization.PublicFormat.SubjectPublicKeyInfo))
   ticket=await self.api('/signup/request',{'email':'voice_test@example.test'});code=await self.api('/test-code?email=voice_test@example.test')
   reply=await self.api('/register',{'nick':'voice_test','name':'Voice test peer','password':uuid.uuid4().hex,'email':'voice_test@example.test','ticket':ticket['ticket'],'code':code['code'],'enc':pub(self.rsa),'sig':pub(self.ec)})
   self.token=reply['token'];self.ready=True
   while True:
    for e in (await self.api('/poll'))['messages']:
     peer=await self.api('/user/'+e['from']);key=serialization.load_der_public_key(un64(peer['sig']));key.verify(un64(e['signature']),signed(e),ec.ECDSA(hashes.SHA256()))
     aes=self.rsa.decrypt(un64(e['key']),OAEP);plain=AESGCM(aes).decrypt(un64(e['iv']),un64(e['body']),header(e).encode()).decode()
     await self.api('/ack',{'from':e['from'],'id':e['id']})
     if plain.startswith('\x1eoldy2:'):
      payload=json.loads(plain[len('\x1eoldy2:'):]);asyncio.create_task(self.receive(e['from'],payload))
  except Exception as ex:self.error=type(ex).__name__+': '+str(ex);print('CALL_PEER_ERROR',self.error,flush=True)
 async def signal(self,op,sdp=''):
  peer=await self.api('/user/alice');e={'v':1,'id':str(uuid.uuid4()),'from':'voice_test','to':'alice','time':int(time.time()*1000),'action':'signal'}
  payload={'kind':'signal','op':op,'sid':self.sid,'sent_at':e['time']}
  if sdp:payload['sdp']=sdp
  aes=os.urandom(32);iv=os.urandom(12);e['body']=b64(AESGCM(aes).encrypt(iv,('\x1eoldy2:'+json.dumps(payload)).encode(),header(e).encode()));e['iv']=b64(iv);e['key']=b64(serialization.load_der_public_key(un64(peer['enc'])).encrypt(aes,OAEP));e['signature']=b64(self.ec.sign(signed(e),ec.ECDSA(hashes.SHA256())))
  await self.api('/send',e)
 async def new_peer(self,sid):
  if self.pc:await self.pc.close()
  self.sid=sid;self.received=0;self.connected=False;self.pc=pc=RTCPeerConnection(RTCConfiguration(iceServers=[]));pc.addTrack(Tone())
  @pc.on('connectionstatechange')
  async def state():
   if pc is self.pc:self.connected=pc.connectionState=='connected'
  @pc.on('track')
  async def consume(track):
   try:
    while True:
     await track.recv()
     if pc is self.pc:self.received+=1
   except Exception:pass
  return pc
 async def receive(self,peer,payload):
  try:
   if peer!='alice':return
   op=payload.get('op','')
   if op=='call_offer':
    pc=await self.new_peer(payload['sid']);await pc.setRemoteDescription(RTCSessionDescription(payload['sdp'],'offer'));await pc.setLocalDescription(await pc.createAnswer());await self.signal('call_answer',pc.localDescription.sdp)
   elif payload.get('sid')==self.sid:
    if op=='call_answer':await self.pc.setRemoteDescription(RTCSessionDescription(payload['sdp'],'answer'))
    elif op in ('call_end','call_busy'):
     self.ends+=1
     if self.pc:await self.pc.close()
  except Exception as ex:self.error=type(ex).__name__+': '+str(ex);print('CALL_PEER_ERROR',self.error,flush=True)
 async def ring(self):
  pc=await self.new_peer(str(uuid.uuid4()));await pc.setLocalDescription(await pc.createOffer());await self.signal('call_offer',pc.localDescription.sdp);return {'sid':self.sid}
 def incoming(self):return asyncio.run_coroutine_threadsafe(self.ring(),self.loop).result(timeout=25)
 def status(self):return {'ready':self.ready,'connected':self.connected,'received':self.received,'ends':self.ends,'error':self.error,'sid':self.sid}
