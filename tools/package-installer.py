from pathlib import Path
import base64,io,tarfile
root=Path(__file__).resolve().parents[1]
buf=io.BytesIO()
with tarfile.open(fileobj=buf,mode='w:gz') as t:
 for f in ('server.py','install.sh','configure-mail.py','disk_storage.py','configure-disk.py'):t.add(root/'server'/f,arcname=f)
payload=base64.b64encode(buf.getvalue()).decode()
command="""python3 - <<'OLDY_INSTALL'
import base64,io,tarfile,tempfile,subprocess,os
payload='PAYLOAD'
with tempfile.TemporaryDirectory(prefix='oldy-install-') as folder:
 with tarfile.open(fileobj=io.BytesIO(base64.b64decode(payload)),mode='r:gz') as t:
  t.extractall(folder,filter='data')
 subprocess.run(['bash',os.path.join(folder,'install.sh')],check=True)
OLDY_INSTALL
""".replace('PAYLOAD',payload)
(root/'build').mkdir(exist_ok=True)
(root/'build'/'Install-OldyChat.txt').write_text(command)
print('Created self-contained console command (no GitHub credentials).')
