import hashlib,importlib.util,io,json,tempfile,unittest,urllib.error
from pathlib import Path
spec=importlib.util.spec_from_file_location('disk_transport',Path(__file__).resolve().parents[1]/'server/disk_storage.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
class Reply(io.BytesIO):
 def __init__(self,body=b'',status=200,headers=None):super().__init__(body);self.status=status;self.headers=headers or {}
class TransportTest(unittest.TestCase):
 def test_transfer_never_leaks_oauth_and_validates_ranges(self):
  calls=[]
  class Opener:
   def open(self,req,timeout):
    calls.append(req)
    if req.full_url.startswith(m.YandexDisk.API):return Reply(json.dumps({'href':'https://downloader.disk.yandex.ru/signed-private-object','method':'GET'}).encode())
    return Reply(b'abcd',206,{'Content-Range':'bytes 2-5/8','Content-Length':'4'})
  disk=m.YandexDisk('fixture_token_12345678',opener=Opener());name='11111111-1111-4111-a111-111111111111.mp4'
  with disk.open_range(name,2,5,8) as response:self.assertEqual(response.read(),b'abcd')
  self.assertEqual(calls[0].get_header('Authorization'),'OAuth fixture_token_12345678');self.assertIsNone(calls[1].get_header('Authorization'));self.assertEqual(calls[1].get_header('Range'),'bytes=2-5')
  class Bad(Opener):
   def open(self,req,timeout):
    r=super().open(req,timeout)
    if not req.full_url.startswith(m.YandexDisk.API):r.headers['Content-Range']='bytes 0-3/8'
    return r
  disk.opener=Bad()
  with self.assertRaises(m.DiskError):disk.open_range(name,2,5,8)
 def test_untrusted_transfer_hosts_and_paths_rejected(self):
  disk=m.YandexDisk('fixture_token_12345678')
  for url in ('http://downloader.disk.yandex.ru/file','https://yandex.ru.evil.test/file','https://localhost/file','https://user:pass@yandex.ru/file','https://yandex.ru:8443/file'):
   with self.assertRaises(m.DiskError):disk.safe_url(url)
  for path in ('../../file','user.txt','11111111-1111-4111-a111-111111111111.mp4/../../x'):
   with self.assertRaises(m.DiskError):disk.path(path)
 def test_existing_object_must_match_before_local_can_be_removed(self):
  with tempfile.TemporaryDirectory() as folder:
   file=Path(folder)/'source.mp4';file.write_bytes(b'new original')
   disk=m.YandexDisk('fixture_token_12345678');disk.metadata=lambda name:{'size':file.stat().st_size,'md5':hashlib.md5(b'old original').hexdigest()}
   with self.assertRaises(m.DiskError):disk.upload('11111111-1111-4111-a111-111111111111.mp4',file)
   self.assertEqual(file.read_bytes(),b'new original')
   disk.metadata=lambda name:{'size':file.stat().st_size,'md5':hashlib.md5(file.read_bytes()).hexdigest()}
   self.assertEqual(disk.upload('11111111-1111-4111-a111-111111111111.mp4',file),hashlib.sha256(file.read_bytes()).hexdigest())
