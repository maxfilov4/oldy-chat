"""Fetch fixed official Vosk model releases for checksums and Android recognition tests."""
from pathlib import Path
import hashlib,json,shutil,urllib.request,subprocess
root=Path(__file__).resolve().parents[1];cache=root/'build/speech';cache.mkdir(parents=True,exist_ok=True)
models={}
expected_hashes={'ru':'961d5ff98a17f4aa6de69864d0aa71fa5bac682301d2b5d17a3f24c5c99a46d4','en':'30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498'}
for lang,name in [('ru','vosk-model-small-ru-0.22'),('en','vosk-model-small-en-us-0.15')]:
 url='https://alphacephei.com/vosk/models/'+name+'.zip';dest=cache/(name+'.zip')
 if not dest.exists():
  with urllib.request.urlopen(url,timeout=180) as response,dest.open('wb') as out:shutil.copyfileobj(response,out)
 if not 10000000<dest.stat().st_size<70000000:raise RuntimeError('Unexpected model size')
 if hashlib.sha256(dest.read_bytes()).hexdigest()!=expected_hashes[lang]:raise RuntimeError('Model release checksum changed')
 models[lang]={'name':name,'url':url,'sha256':hashlib.sha256(dest.read_bytes()).hexdigest(),'size':dest.stat().st_size,'license':'Apache-2.0'}
 target=root/'tests/assets/speech'/('model-'+lang+'.zip');target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(dest,target)
(root/'app/src/main/assets/speech-models.json').write_text(json.dumps(models,indent=2))
fixture=root/'tests/assets/speech/en.wav'
if not fixture.exists():
 with urllib.request.urlopen('https://raw.githubusercontent.com/alphacep/vosk-api/v0.3.50/python/example/test.wav',timeout=60) as response,fixture.open('wb') as out:shutil.copyfileobj(response,out)
print('Official offline speech models prepared:', ', '.join(models))

# Public human-speech example distributed by the model authors; no user recordings enter CI.
ru_fixture=root/'tests/assets/speech/ru.wav'
with urllib.request.urlopen('https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/russian_test_short_from_t_one.wav',timeout=60) as response,ru_fixture.open('wb') as out:shutil.copyfileobj(response,out)
for rate in (8000,44100,48000):
 subprocess.run(['ffmpeg','-v','error','-y','-i',str(fixture),'-ar',str(rate),'-ac','2' if rate==48000 else '1',str(root/'tests/assets/speech'/('en-'+str(rate)+'.wav'))],check=True)

notices=root/'app/src/main/assets/third-party';notices.mkdir(parents=True,exist_ok=True)
with urllib.request.urlopen('https://www.apache.org/licenses/LICENSE-2.0.txt',timeout=30) as response:
 (notices/'Apache-2.0.txt').write_bytes(response.read())
(notices/'Speech-notices.txt').write_text('Vosk API 0.3.75 — Alpha Cephei — Apache-2.0\nhttps://github.com/alphacep/vosk-api\nJNA 5.18.1 — Java Native Access contributors — used under Apache-2.0\nhttps://github.com/java-native-access/jna\nVosk small RU 0.22 and EN-US 0.15 models — Alpha Cephei — Apache-2.0\nhttps://alphacephei.com/vosk/models\nSee Apache-2.0.txt for the license. Original model notices are preserved in each installed model directory.\n')
