#!/usr/bin/env python3
"""Run interactively as root; verifies TLS/SMTP login before changing configuration."""
import getpass,os,pathlib,re,smtplib,ssl,subprocess,tempfile

def main():
 if os.geteuid()!=0:raise SystemExit('Запустите эту команду под root.')
 host=input('SMTP-сервер вашей почты: ').strip()
 if not re.fullmatch(r'[a-zA-Z0-9.-]{1,253}',host):raise SystemExit('Некорректный SMTP-сервер.')
 port=int(input('Порт [587]: ').strip() or '587')
 if port not in (465,587):raise SystemExit('Укажите 465 (TLS) или 587 (STARTTLS).')
 sender=input('E-mail отправителя: ').strip();user=input('SMTP-логин [тот же e-mail]: ').strip() or sender
 if not re.fullmatch(r'[^\s@]+@[^\s@]+\.[^\s@]+',sender):raise SystemExit('Проверьте адрес отправителя.')
 password=getpass.getpass('Пароль приложения для почты (при вводе скрыт): ')
 if not password or any(c in value for value in (sender,user,password) for c in '\r\n\0'):raise SystemExit('Некорректные данные.')
 print('Проверяем защищённое подключение к почте…',flush=True);ctx=ssl.create_default_context()
 try:
  smtp=smtplib.SMTP_SSL(host,port,timeout=20,context=ctx) if port==465 else smtplib.SMTP(host,port,timeout=20)
  with smtp:
   smtp.ehlo()
   if port==587:smtp.starttls(context=ctx);smtp.ehlo()
   smtp.login(user,password)
 except Exception:raise SystemExit('Не удалось войти на SMTP-сервер. Настройки чата не изменены. Проверьте адрес, порт и пароль приложения почты.')
 values={'OLDY_SMTP_HOST':host,'OLDY_SMTP_PORT':str(port),'OLDY_SMTP_FROM':sender,'OLDY_SMTP_USER':user,'OLDY_SMTP_PASSWORD':password}
 destination=pathlib.Path('/etc/oldy-chat/mail.env');destination.parent.mkdir(mode=0o750,exist_ok=True)
 fd,tmp=tempfile.mkstemp(prefix='mail-',dir=destination.parent)
 try:
  os.fchmod(fd,0o600)
  with os.fdopen(fd,'w') as out:
   for k,v in values.items():out.write(k+'="'+v.replace('\\','\\\\').replace('"','\\"')+'"\n')
  os.replace(tmp,destination)
 finally:
  if os.path.exists(tmp):os.unlink(tmp)
 subprocess.run(['systemctl','restart','oldy-chat'],check=True)
 print('Почта подключена. Зарегистрируйте тестовый аккаунт и проверьте получение кода, включая папку «Спам».')
if __name__=='__main__':main()
