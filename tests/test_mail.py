"""Transactional mail is plain text with a real sender and standard headers."""
import importlib.util,os,unittest
from unittest.mock import patch
from pathlib import Path
from email.utils import parsedate_to_datetime,parseaddr
class MailTest(unittest.TestCase):
 def test_plain_mail_headers_and_protected_transport(self):
  spec=importlib.util.spec_from_file_location('mail_relay',Path(__file__).resolve().parents[1]/'server/server.py');relay=importlib.util.module_from_spec(spec);spec.loader.exec_module(relay)
  actions=[];sent=[]
  class SMTP:
   def __init__(self,*args,**kwargs):actions.append('connect')
   def __enter__(self):return self
   def __exit__(self,*args):pass
   def starttls(self,context):actions.append('tls')
   def login(self,user,password):actions.append('login')
   def send_message(self,message):sent.append(message);actions.append('send')
  with patch.dict(os.environ,{'OLDY_SMTP_HOST':'smtp.example.test','OLDY_SMTP_FROM':'sender@example.test','OLDY_SMTP_USER':'sender@example.test','OLDY_SMTP_PASSWORD':'fixture-only','OLDY_SMTP_PORT':'587'}),patch('smtplib.SMTP',SMTP):
   relay.mail_code('recipient@example.test','123456');relay.mail_code('recipient@example.test','654321')
  self.assertEqual(actions[:4],['connect','tls','login','send']);msg=sent[0]
  self.assertEqual(msg.get_content_type(),'text/plain');self.assertEqual(parseaddr(msg['From']),('Oldy Chat','sender@example.test'));self.assertEqual(msg['To'],'recipient@example.test');self.assertIsNotNone(parsedate_to_datetime(msg['Date']));self.assertNotEqual(msg['Message-ID'],sent[1]['Message-ID']);self.assertIn('@example.test>',msg['Message-ID']);self.assertIn('123456',msg.get_content());self.assertIn('10 минут',msg.get_content());self.assertNotIn('http',msg.get_content());self.assertNotIn('<html',msg.get_content())
