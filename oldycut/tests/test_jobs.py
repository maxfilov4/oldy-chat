import json, tempfile, time, unittest
from pathlib import Path
from unittest.mock import Mock, patch
import requests
from oldycut.ai import API, APIError, UnconfirmedRequest
from oldycut.engine import Runner, Cancelled
from oldycut.diagnostics import RunLog, remaining_seconds


DONE={'id':'resp_demo','status':'completed','usage':{'input_tokens':1000,'output_tokens':100},
      'output':[{'content':[{'type':'output_text','text':'{"keep":[],"notes":[]}'}]}]}
INPUT=[{'type':'input_text','text':'Собери обзор'}]


class NetworkTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.events=[]
        self.api=self.make_api()
    def make_api(self):
        api=API('local-test-key',Runner(event=self.events.append),cache_dir=self.temp.name)
        api.poll_seconds=0;api._post=Mock(return_value={'id':'resp_demo','status':'queued'});return api
    def test_poll_timeout_recovers_same_request_and_caches_result(self):
        self.api._request=Mock(side_effect=[{'status':'queued'},requests.exceptions.ReadTimeout(),{'status':'in_progress'},DONE])
        self.assertEqual(self.api.structured(INPUT)['keep'],[])
        self.assertEqual(self.api._post.call_count,1);self.assertEqual(self.api._request.call_count,4)
        request=self.api._post.call_args.kwargs['json'];self.assertTrue(request['background']);self.assertFalse(request['store'])
        self.assertAlmostEqual(self.api.spent,.015)
        replay=self.make_api();self.assertEqual(replay.structured(INPUT)['keep'],[]);replay._post.assert_not_called();self.assertEqual(replay.spent,0)
        self.assertIn('reconnecting',[e.get('status') for e in self.events])
    def test_resume_after_disconnection_does_not_create_new_generation(self):
        self.api.reconnect_seconds=0;self.api._request=Mock(side_effect=requests.exceptions.ReadTimeout())
        with self.assertRaisesRegex(RuntimeError,'Номер запроса сохранён'):self.api.structured(INPUT)
        replay=self.make_api();replay._request=Mock(return_value=DONE);replay.structured(INPUT)
        replay._post.assert_not_called();self.assertEqual(replay._request.call_args.args,('get','responses/resp_demo'))
    def test_creation_timeout_cannot_silently_duplicate_paid_request(self):
        self.api._post=Mock(side_effect=requests.exceptions.ReadTimeout())
        with self.assertRaises(UnconfirmedRequest) as error:self.api.structured(INPUT)
        self.assertEqual(json.loads(error.exception.path.read_text())['state'],'unknown')
        replay=self.make_api()
        with self.assertRaises(UnconfirmedRequest):replay.structured(INPUT)
        replay._post.assert_not_called()
    def test_cancel_requests_remote_cancel_and_keeps_completed_race(self):
        def fetch(method,path,**kwargs):
            if method=='get':self.api.runner.cancel();return {'status':'in_progress'}
            self.assertEqual(path,'responses/resp_demo/cancel');return DONE
        self.api._request=Mock(side_effect=fetch)
        with self.assertRaises(Cancelled):self.api.structured(INPUT)
        record=json.loads(next(Path(self.temp.name).glob('*.json')).read_text());self.assertEqual(record['state'],'completed')
        self.assertEqual(self.api._request.call_count,2)
    def test_key_error_is_distinct_from_network_error(self):
        reply=Mock(status_code=401,headers={'x-request-id':'req_sample'});reply.json.return_value={'error':{'message':'Bad key local-test-key'}}
        self.api.session.get=Mock(return_value=reply)
        with self.assertRaises(APIError) as error:self.api.check_connection()
        self.assertEqual(error.exception.status,401);self.assertNotIn('local-test-key',str(error.exception));self.assertIn('req_sample',str(error.exception))
    def test_connection_check_only_uses_free_model_lookup(self):
        self.api._request=Mock(return_value={'id':'gpt-6-astra'})
        self.assertIn('Ключ принят',self.api.check_connection())
        self.api._post.assert_not_called();self.assertEqual(self.api._request.call_args.args,('get','models/gpt-6-astra'))
    def test_failed_generation_does_not_return_or_cache_a_plan(self):
        self.api._request=Mock(return_value={'status':'failed','error':{'code':'server_error'}})
        with self.assertRaisesRegex(RuntimeError,'не завершил'):self.api.structured(INPUT)
        self.assertEqual(json.loads(next(Path(self.temp.name).glob('*.json')).read_text())['state'],'failed')


class DiagnosticsTests(unittest.TestCase):
    def test_logs_redact_secrets_and_urls(self):
        with tempfile.TemporaryDirectory() as directory:
            log=RunLog(directory,'Test');log.write('ReadTimeout key-value sk-proj-fake123 Bearer credential https://example.org/file?sig=secret',['key-value'])
            text=log.path.read_text()
            for hidden in ['key-value','sk-proj-fake123','credential','sig=secret']:self.assertNotIn(hidden,text)
            self.assertIn('ReadTimeout',text);self.assertEqual(log.path.stat().st_mode & 0o777,0o600)
    def test_remaining_time_is_measured_and_unknown_before_progress(self):
        self.assertIsNone(remaining_seconds(0,100,30));self.assertIsNone(remaining_seconds(1,100,.1))
        self.assertEqual(remaining_seconds(25,100,10),30)
    def test_ffmpeg_reports_progress_before_process_exits(self):
        events=[];r=Runner(event=events.append)
        r.run(['-re','-f','lavfi','-i','testsrc2=size=64x64:rate=10','-t','1.4','-f','null','-'],duration=1.4,label='Video test')
        media=[e for e in events if e['type']=='media']
        self.assertGreaterEqual(len(media),2);self.assertTrue(any(0<e['seconds']<1.4 for e in media))
        self.assertTrue(all(0<=e['seconds']<=1.4 for e in media));self.assertEqual(events[-1]['type'],'complete')


class ProgressUITests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        from PySide6.QtWidgets import QApplication
        cls.app=QApplication.instance() or QApplication([])
    def test_cloud_elapsed_is_live_and_failure_is_not_100_percent(self):
        from oldycut.jobs_ui import JobDialog
        with tempfile.TemporaryDirectory() as directory:
            d=JobDialog(None,'Test',Path(directory)/'job.log');d.show()
            d.receive({'type':'phase','kind':'cloud','name':'GPT‑6'});d.started-=70;d.tick()
            self.assertIn('1:10',d.timing.text());self.assertIn('не сообщает время',d.timing.text());self.assertEqual(d.bar.maximum(),0)
            d.finish(False,'ReadTimeout');self.assertNotEqual(d.bar.value(),100);self.assertFalse(d.timer.isActive());d.close()
    def test_job_details_available_while_editor_busy(self):
        from oldycut.ui import MainWindow
        from PySide6.QtTest import QTest
        w=MainWindow(restore=False)
        def task(r,p):
            r.begin('Обработка','cloud');r.cancelled.wait(.3);r.check();return 'ok'
        w.start_job(task,lambda value:None,'Test',False);self.assertTrue(w.job_button.isEnabled());self.assertFalse(w.splitter.isEnabled())
        w.show_job();self.assertTrue(w.job_dialog.isVisible())
        deadline=time.monotonic()+3
        while w.worker and time.monotonic()<deadline:QTest.qWait(20)
        self.assertIsNone(w.worker);self.assertEqual(w.job_dialog.bar.value(),100);w.close()
