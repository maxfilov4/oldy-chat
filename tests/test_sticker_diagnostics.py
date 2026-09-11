import base64,contextlib,io,json,os,sys,tempfile,unittest,urllib.error
from pathlib import Path
from unittest.mock import patch
from PIL import Image
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'server'))
import sticker_generation as generator
import sticker_diagnostics as diagnostics
from sticker_fixture import sheet

class StickerDiagnosticsTest(unittest.TestCase):
 def setup_files(self,root):
  settings=Path(root)/'stickers.env';settings.write_text('OLDY_STICKER_OPENAI_KEY=fixture-key-never-print\nOLDY_STICKER_MODEL=gpt-image-1\nOLDY_STICKER_USERS=*\n')
  photo=io.BytesIO();Image.new('RGB',(128,128),'white').save(photo,format='PNG')
  return settings,base64.b64encode(photo.getvalue()).decode()
 def test_single_generation_does_not_change_settings_or_create_collection(self):
  with tempfile.TemporaryDirectory() as root:
   settings,photo=self.setup_files(root);before=settings.read_bytes();output=Path(root)/'results';log=io.StringIO()
   with patch.dict(os.environ,{},clear=False),patch.object(diagnostics.os,'geteuid',return_value=0),patch.object(generator,'render_sheet',return_value=sheet()) as render,contextlib.redirect_stdout(log):
    self.assertTrue(diagnostics.diagnose(photo,settings,output))
   self.assertEqual(render.call_count,1);self.assertEqual(settings.read_bytes(),before);self.assertEqual(len(list(output.glob('*.webp'))),1)
   self.assertNotIn('fixture-key-never-print',log.getvalue());self.assertNotIn(photo,log.getvalue());self.assertIn('STICKER_TEST_OK',log.getvalue());self.assertFalse((Path(root)/'personal-stickers').exists())
 def test_verification_denial_is_classified_without_retry_or_raw_body(self):
  with tempfile.TemporaryDirectory() as root:
   settings,photo=self.setup_files(root);log=io.StringIO();calls=[]
   def rejected(*args):
    calls.append(1);error=urllib.error.HTTPError('https://api.openai.com/v1/images/edits',403,'denied',{},io.BytesIO(json.dumps({'error':{'message':'Your organization must be verified. fixture-key-never-print'}}).encode()))
    raise generator.GenerationError(generator.provider_error(error))
   original=generator.provider_error
   with patch.dict(os.environ,{},clear=False),patch.object(diagnostics.os,'geteuid',return_value=0),patch.object(generator,'render_sheet',side_effect=rejected),contextlib.redirect_stdout(log):
    self.assertFalse(diagnostics.diagnose(photo,settings,Path(root)/'results'))
   self.assertEqual(len(calls),1);self.assertIn('PROVIDER_VERIFICATION; HTTP 403',log.getvalue());self.assertNotIn('fixture-key-never-print',log.getvalue());self.assertIs(generator.provider_error,original)
 def test_error_body_regions_scopes_and_verification(self):
  for code,message,expected in [('unsupported_country_region_territory','private','PROVIDER_REGION'),('insufficient_permissions','private','PROVIDER_SCOPE'),(None,'Your organization must be verified to use this model','PROVIDER_VERIFICATION')]:
   error=urllib.error.HTTPError('https://api.openai.com/v1/images/edits',403,'denied',{},io.BytesIO(json.dumps({'error':{'code':code,'message':message}}).encode()))
   self.assertEqual(generator.provider_error(error),expected)
