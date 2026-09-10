"""Fetch fixed official Vosk model releases for checksums and Android recognition tests."""
from pathlib import Path
import hashlib,json,shutil,urllib.request,subprocess,wave,zipfile
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

# The public Piper sample includes its exact source sentence in the same directory.
# It is neural synthetic speech, used only for testing; no TTS model is shipped in Oldi.
# The earlier T-one README transcript described a different recording and was not a valid reference.
ru_fixture=root/'tests/assets/speech/ru.wav'
sample_base='https://raw.githubusercontent.com/rhasspy/piper-samples/39872caae72a8f684e4167c91c9b80007e8601ec/samples/ru/ru_RU/irina/medium/'
sample=cache/'ru-reference.mp3'
with urllib.request.urlopen(sample_base+'speaker_0.mp3',timeout=60) as response,sample.open('wb') as out:shutil.copyfileobj(response,out)
with urllib.request.urlopen(sample_base+'sample.txt',timeout=60) as response:reference=response.read().decode().strip()
(root/'tests/assets/speech/ru-reference.txt').write_text(reference)
subprocess.run(['ffmpeg','-v','error','-y','-i',str(sample),'-ar','16000','-ac','1',str(ru_fixture)],check=True)
for rate in (8000,44100,48000):
 subprocess.run(['ffmpeg','-v','error','-y','-i',str(fixture),'-ar',str(rate),'-ac','2' if rate==48000 else '1',str(root/'tests/assets/speech'/('en-'+str(rate)+'.wav'))],check=True)

# Verify fixture intelligibility independently of Android decoding before the expensive build.
# Recognition uses the same public model, with the upstream Python API and ffmpeg PCM.
from vosk import Model,KaldiRecognizer,SetLogLevel
SetLogLevel(-1)
baseline={};metrics={}
for lang in ('en','ru'):
 model_dir=cache/models[lang]['name']
 if not model_dir.exists():
  with zipfile.ZipFile(cache/(models[lang]['name']+'.zip')) as z:z.extractall(cache)
 recognizer=KaldiRecognizer(Model(str(model_dir)),16000);parts=[]
 with wave.open(str(root/'tests/assets/speech'/(lang+'.wav')),'rb') as wav:
  assert wav.getframerate()==16000 and wav.getnchannels()==1 and wav.getsampwidth()==2
  while pcm:=wav.readframes(4000):
   if recognizer.AcceptWaveform(pcm):parts.append(json.loads(recognizer.Result()).get('text',''))
 parts.append(json.loads(recognizer.FinalResult()).get('text',''));actual=' '.join(parts).strip();baseline[lang]=actual
 if lang=='en':assert 'one' in actual and 'zero' in actual,actual
 else:
  import re
  expected=' '.join(re.findall(r'[а-яё]+',reference.lower()))
  previous=list(range(len(actual)+1))
  for i,char in enumerate(expected,1):
   current=[i]
   for j,other in enumerate(actual,1):current.append(min(current[-1]+1,previous[j]+1,previous[j-1]+(char!=other)))
   previous=current
  cer=previous[-1]/len(expected);metrics[lang]={'character_error_rate':cer,'reference':expected,'acceptance':'integration smoke check: CER <= 0.15; not a general recognition accuracy claim'}
  assert cer<=0.15,(cer,reference,actual)
(root/'build/speech-reference-results.json').write_text(json.dumps({'source':sample_base,'russian_fixture':'neural synthetic speech with published source sentence','results':baseline,'metrics':metrics},ensure_ascii=False,indent=2))
print('SPEECH_REFERENCE_PASS: English human speech and Russian neural speech match published references')

notices=root/'app/src/main/assets/third-party';notices.mkdir(parents=True,exist_ok=True)
with urllib.request.urlopen('https://www.apache.org/licenses/LICENSE-2.0.txt',timeout=30) as response:
 (notices/'Apache-2.0.txt').write_bytes(response.read())
(notices/'Speech-notices.txt').write_text('Vosk API 0.3.75 — Alpha Cephei — Apache-2.0\nhttps://github.com/alphacep/vosk-api\nJNA 5.18.1 — Java Native Access contributors — used under Apache-2.0\nhttps://github.com/java-native-access/jna\nVosk small RU 0.22 and EN-US 0.15 models — Alpha Cephei — Apache-2.0\nhttps://alphacephei.com/vosk/models\nSee Apache-2.0.txt for the license. Original model notices are preserved in each installed model directory.\n')
