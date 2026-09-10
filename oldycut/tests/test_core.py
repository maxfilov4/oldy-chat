import copy,json,math,tempfile,unittest
from pathlib import Path
from unittest.mock import Mock
from oldycut.model import *
from oldycut.ai import build_clips,Planner,API

class ModelTests(unittest.TestCase):
    def setUp(self):self.m=Media('/tmp/source.mp4',20,1920,1080);self.p=Project(media=[self.m],clips=[Clip(self.m.id,0,8),Clip(self.m.id,8,20,transition='fade',transition_seconds=.5)])
    def test_roundtrip_and_undo(self):
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'review.oldycut';self.p.save(path);p=Project.load(path);self.assertEqual(p.to_dict(),self.p.to_dict())
        h=History();h.push(p);p.clips[0].enabled=False;p=h.undo(p);self.assertTrue(p.clips[0].enabled);p=h.redo(p);self.assertFalse(p.clips[0].enabled)
    def test_transition_duration(self):self.assertAlmostEqual(self.p.duration,19.5)
    def test_nan_rejected(self):
        self.p.clips[0].start=float('nan')
        with self.assertRaises(ValueError):self.p.validate()
    def test_wrong_source_rejected(self):
        self.p.clips[0].media_id='unknown'
        with self.assertRaises(ValueError):self.p.validate()
    def test_silence_merge_preserves_speech(self):self.assertEqual(subtract_ranges(0,10,[(2,4),(3,6),(9,15)]),[(0,2),(6,9)])
    def test_ai_clips_are_recoverable(self):
        rows=[dict(start=2,end=8,label='Суть',transition='cut',grade='Оригинал',overlays=[])]
        clips=build_clips({'keep':rows},self.m,0,20,{})
        self.assertEqual([(c.start,c.end,c.enabled) for c in clips],[(0,2,False),(2,8,True),(8,20,False)])
    def test_ai_bad_ranges_never_applied(self):
        for a,b in [(0,30),(math.nan,8),(5,3)]:
            with self.assertRaises(ValueError):build_clips({'keep':[{'start':a,'end':b}]},self.m,0,20,{})
        with self.assertRaises(ValueError):build_clips({'keep':[dict(start=0,end=8),dict(start=4,end=9)]},self.m,0,20,{})
    def test_refine_preserves_manual_color(self):
        self.p.clips[0].grade.exposure=.6;api=Mock();api.spent=.1
        api.structured.return_value={'changes':[dict(clip_id=self.p.clips[0].id,enabled=True,start=1,end=8,label='Короче',transition='cut',grade='Сохранить',overlays=[])],'order':[],'notes':[]}
        edited=Planner(api).refine(self.p,'Укороти начало')
        self.assertEqual(edited.clips[0].grade.exposure,.6);self.assertEqual(edited.clips[0].start,1);self.assertEqual(self.p.clips[0].start,0)
    def test_unknown_image_cannot_be_loaded(self):
        row=dict(start=0,end=8,overlays=[dict(style='image',asset_id='/etc/passwd')])
        with self.assertRaises(ValueError):build_clips({'keep':[row]},self.m,0,20,{})
    def test_api_schema_and_usage(self):
        directory=tempfile.TemporaryDirectory();self.addCleanup(directory.cleanup)
        api=API('test-key',budget=10,cache_dir=directory.name);api._post=Mock(return_value={'status':'completed','usage':{'input_tokens':1000,'output_tokens':100},'output':[{'content':[{'type':'output_text','text':'{"keep":[]}'}]}]})
        self.assertEqual(api.structured([{'type':'input_text','text':'Задание'}]),{'keep':[]});self.assertAlmostEqual(api.spent,.015)
        data=api._post.call_args.kwargs['json'];self.assertFalse(data['store']);self.assertTrue(data['text']['format']['strict']);self.assertEqual(data['model'],'gpt-6-astra')
    def test_budget_blocks_before_call(self):
        api=API('test-key',budget=.01);api._post=Mock()
        with self.assertRaises(ValueError):api.structured([{'type':'input_text','text':'Задание'}])
        api._post.assert_not_called()

if __name__=='__main__':unittest.main()
