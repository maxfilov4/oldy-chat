from pathlib import Path
import base64,io,tarfile
root=Path(__file__).resolve().parents[1]
buf=io.BytesIO()
with tarfile.open(fileobj=buf,mode='w:gz') as t:
 for f in ('server.py','install.sh','configure-mail.py','disk_storage.py','configure-disk.py','legal_service.py','legal_texts.json','configure-legal.py','retention.py'):t.add(root/'server'/f,arcname=f)
payload=base64.b64encode(buf.getvalue()).decode()
command="""python3 - <<'OLDY_INSTALL'
import base64,io,tarfile,tempfile,subprocess,os
print('SERVER MAINTENANCE ONLY: this command does not publish an Android update.',flush=True)
payload='PAYLOAD'
with tempfile.TemporaryDirectory(prefix='oldy-install-') as folder:
 with tarfile.open(fileobj=io.BytesIO(base64.b64decode(payload)),mode='r:gz') as t:
  t.extractall(folder,filter='data')
 subprocess.run(['bash',os.path.join(folder,'install.sh')],check=True)
OLDY_INSTALL
""".replace('PAYLOAD',payload)
(root/'build').mkdir(exist_ok=True)
(root/'build'/'Server-maintenance-only.txt').write_text(command)
(root/'build'/'Install-OldyChat.txt').unlink(missing_ok=True)
print('Created server maintenance command. Use package-console.py with the full release for in-app updates.')
