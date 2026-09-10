"""One explicit owner-requested image test. No retries, key output or configuration writes."""
import base64,io,json,os,re,secrets,sys,threading,time
from pathlib import Path
from types import SimpleNamespace
import sticker_generation as generator

EXPLANATIONS={
 'PROVIDER_VERIFICATION':'Аккаунт генератора требует верификации организации. Владелец должен завершить её в кабинете поставщика API.',
 'PROVIDER_REGION':'Поставщик API отклонил регион подключения сервера. Проверьте поддерживаемые регионы и условия поставщика.',
 'PROVIDER_SCOPE':'У ключа недостаточно разрешений. Проверьте доступ ключа и проекта к генерации изображений.',
 'PROVIDER_CREDENTIALS':'Ключ сервера не принят. Проверьте настройку ключа в кабинете поставщика API.',
 'PROVIDER_BILLING':'Недостаточно средств или достигнут платёжный лимит API-аккаунта.',
 'PROVIDER_MODEL':'Выбранная модель недоступна проекту или не найдена.',
 'PROVIDER_ACCESS':'Доступ к генерации отклонён. По этому ответу точную причину установить нельзя: проверьте разрешения проекта и обратитесь в поддержку поставщика API.',
 'PROVIDER_REJECTED':'Поставщик отклонил запрос изображения. Это не означает, что ключ обязательно неверный.',
 'PROVIDER_LIMIT':'Достигнут лимит запросов поставщика. Повторный запрос автоматически не выполнялся.',
 'PROVIDER_UNAVAILABLE':'Нет корректного ответа генератора. Возможна проблема сети; повторного платного запроса не было.',
}

def settings(path):
 values={}
 for line in Path(path).read_text().splitlines():
  name,separator,value=line.partition('=')
  if separator and name in ('OLDY_STICKER_OPENAI_KEY','OLDY_STICKER_MODEL'):values[name]=value.strip().strip('"').strip("'")
 return values

def diagnose(encoded,settings_path='/etc/oldy-chat/stickers.env',output_root='/var/lib/oldy-chat/sticker-test-results'):
 if os.geteuid()!=0:raise SystemExit('Откройте консоль сервера под root.')
 try:values=settings(settings_path)
 except OSError:raise SystemExit('Настройки стикеров не найдены. Выполните python3 /opt/oldy-chat/configure-stickers.py') from None
 key=values.get('OLDY_STICKER_OPENAI_KEY','');model=values.get('OLDY_STICKER_MODEL','gpt-image-1')
 if not key:raise SystemExit('В настройках стикеров нет ключа.')
 if model not in generator.MODELS:raise SystemExit('В настройках указана неподдерживаемая модель. Настройки не изменены.')
 values['OLDY_STICKER_MODEL']=model;os.environ.update(values)
 photo=generator.sanitized_image(encoded)
 print('OLDI STICKER TEST: одна попытка генерации с присланным фото. Модель: '+model,flush=True)
 print('Успешная генерация оплачивается с настроенного API-аккаунта. Настройки и ключ не меняются.',flush=True)
 print('Ни ключ, ни исходное фото в вывод не попадут. Автоматических повторов нет.',flush=True)
 captured={};original=generator.provider_error
 def capture(error):
  # The payload is used only in memory to classify known errors; it is never printed.
  captured['http_status']=int(error.code)
  raw=error.read(16384)
  try:
   item=json.loads(raw).get('error',{});code=str(item.get('code',''));message=str(item.get('message','')).lower()
  except Exception:code=message=''
  if code in ('unsupported_country_region_territory','country_not_supported'):return 'PROVIDER_REGION'
  if code in ('organization_verification_required','verification_required') or 'organization must be verified' in message or 'verify your organization' in message:return 'PROVIDER_VERIFICATION'
  if code in ('insufficient_permissions','insufficient_scope') or 'missing scopes' in message or 'insufficient permissions' in message:return 'PROVIDER_SCOPE'
  return original(SimpleNamespace(code=error.code,read=lambda limit:raw[:limit]))
 generator.provider_error=capture
 done=threading.Event();started=time.monotonic()
 def progress():
  while not done.wait(10):print('Создание: '+str(int(time.monotonic()-started))+' сек. Запрос один, повторов нет.',flush=True)
 thread=threading.Thread(target=progress,daemon=True);thread.start()
 try:
  sheet=generator.render_sheet(photo,'wave')
  folder=Path(output_root);folder.mkdir(mode=0o700,parents=True,exist_ok=True)
  name='test-'+time.strftime('%Y%m%d-%H%M%S')+'-'+secrets.token_hex(3)
  png=folder/(name+'.png')
  with png.open('xb') as out:os.fchmod(out.fileno(),0o600);out.write(sheet)
  print('Генератор вернул изображение. Проверяем кадры и собираем анимацию.',flush=True)
  try:animated=generator.animation(sheet)
  except generator.GenerationError as error:
   print('STICKER_TEST_FRAMES: '+error.code+'; рисунок сохранён: '+str(png),flush=True);return False
  result=folder/(name+'.webp')
  with result.open('xb') as out:os.fchmod(out.fileno(),0o600);out.write(animated)
  print('STICKER_TEST_OK: стикер создан; '+str(len(animated))+' байт; '+str(result),flush=True)
  print('Это отдельный тест: в личные коллекции и чаты ничего автоматически не добавлялось.',flush=True)
  return True
 except generator.GenerationError as error:
  print('STICKER_TEST_FAILED: '+error.code+'; HTTP '+str(captured.get('http_status','нет ответа')),flush=True)
  print(EXPLANATIONS.get(error.code,'Не удалось завершить создание. Пришлите строку STICKER_TEST_FAILED.'),flush=True)
  return False
 finally:done.set();thread.join(timeout=1);generator.provider_error=original
