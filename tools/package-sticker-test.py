"""Owner-requested single-photo diagnostic; embeds photo and code, no download URL or key."""
import ast,base64,hashlib,json,sys,textwrap
from pathlib import Path
root=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(root/'server'))
import sticker_generation
photo=Path(sys.argv[1]);output=Path(sys.argv[2])
image=sticker_generation.sanitized_image(base64.b64encode(photo.read_bytes()).decode())
encoded='\n'.join(textwrap.wrap(base64.b64encode(image).decode(),100))
helper=(root/'server/sticker_diagnostics.py').read_text()
script="import sys\nsys.path.insert(0, '/opt/oldy-chat')\n"+helper+"\nPHOTO='''\n"+encoded+"\n'''\ndiagnose(''.join(PHOTO.split()))\n"
ast.parse(script);output.parent.mkdir(parents=True,exist_ok=True)
output.write_text("python3 - <<'OLDI_STICKER_PHOTO_TEST'\n"+script+"OLDI_STICKER_PHOTO_TEST\n")
print(json.dumps({'file':str(output),'bytes':output.stat().st_size,'photo_sha256':hashlib.sha256(image).hexdigest(),'contains_download_url':False}))
