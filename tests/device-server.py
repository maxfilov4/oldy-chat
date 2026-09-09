import json,urllib.parse,datetime,os,subprocess,sys,importlib.util
from pathlib import Path
from cryptography import x509
from cryptography.hazmat.primitives import hashes,serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID
import ipaddress
root=Path(__file__).resolve().parents[1];folder=root/'build'/'device-server';folder.mkdir(parents=True,exist_ok=True)
k=rsa.generate_private_key(public_exponent=65537,key_size=3072);name=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,'10.0.2.2')]);now=datetime.datetime.now(datetime.timezone.utc)
cert=x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(k.public_key()).serial_number(x509.random_serial_number()).not_valid_before(now-datetime.timedelta(minutes=1)).not_valid_after(now+datetime.timedelta(days=1)).add_extension(x509.SubjectAlternativeName([x509.IPAddress(ipaddress.ip_address(address)) for address in ('10.0.2.2','127.0.0.1')]),critical=False).sign(k,hashes.SHA256())
(folder/'server.crt').write_bytes(cert.public_bytes(serialization.Encoding.PEM));(folder/'server.key').write_bytes(k.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()));(folder/'pin.txt').write_text(cert.fingerprint(hashes.SHA256()).hex().upper())
os.environ['OLDY_DATA']=str(folder)
spec=importlib.util.spec_from_file_location('device_relay',root/'server/server.py');relay=importlib.util.module_from_spec(spec);spec.loader.exec_module(relay)
# Emulator fixture only. Production owner-key binding is covered by server integration tests.
relay.creator=lambda nick:nick=='alice'
mail={}
relay.mail_code=lambda email,code:mail.__setitem__(email,code)
call_peer=None
class TestHandler(relay.Handler):
 def reply(self,obj,status=200):
  if self.path=='/call-config' and status==200 and os.environ.get('OLDY_TEST_TURN'):obj['relay_only']=True
  return super().reply(obj,status)
 def do_POST(self):
  global call_peer
  if self.path=='/test-call/start':
   if call_peer is None:
    from call_peer import CallPeer
    call_peer=CallPeer(folder/'server.crt')
   return self.reply({'started':True})
  if self.path=='/test-call/ring':return self.reply(call_peer.incoming())
  return super().do_POST()
 def do_GET(self):
  if self.path=='/test-call/status':return self.reply(call_peer.status() if call_peer else {'ready':False})
  if self.path.startswith('/test-code?'):
   email=urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query).get('email',[''])[0]
   return self.reply({'code':mail.get(email,'')})
  return super().do_GET()
relay.Handler=TestHandler
sys.argv=[str(root/'server/server.py'),'--host','127.0.0.1','--port','8444','--cert',str(folder/'server.crt'),'--key',str(folder/'server.key')];relay.main()
