"""Fetch fixed official Vosk model releases for checksums and Android recognition tests."""
from pathlib import Path
import hashlib,json,shutil,urllib.request,subprocess
root=Path(__file__).resolve().parents[1];cache=root/'build/speech';cache.mkdir(parents=True,exist_ok=True)
models={}
for lang,name in [('ru','vosk-model-small-ru-0.22'),('en','vosk-model-small-en-us-0.15')]:
 url='https://alphacephei.com/vosk/models/'+name+'.zip';dest=cache/(name+'.zip')
 if not dest.exists():
  with urllib.request.urlopen(url,timeout=180) as response,dest.open('wb') as out:shutil.copyfileobj(response,out)
 if not 10000000<dest.stat().st_size<70000000:raise RuntimeError('Unexpected model size')
 models[lang]={'name':name,'url':url,'sha256':hashlib.sha256(dest.read_bytes()).hexdigest(),'size':dest.stat().st_size,'license':'Apache-2.0'}
 target=root/'tests/assets/speech'/('model-'+lang+'.zip');target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(dest,target)
(root/'app/src/main/assets/speech-models.json').write_text(json.dumps(models,indent=2))
fixture=root/'tests/assets/speech/en.wav'
if not fixture.exists():
 with urllib.request.urlopen('https://raw.githubusercontent.com/alphacep/vosk-api/v0.3.50/python/example/test.wav',timeout=60) as response,fixture.open('wb') as out:shutil.copyfileobj(response,out)
print('Official offline speech models prepared:', ', '.join(models))

subprocess.run(["espeak-ng","-v","ru","-s","135","-w",str(root/"tests/assets/speech/ru.wav"),"привет это проверка распознавания речи один два три"],check=True)
