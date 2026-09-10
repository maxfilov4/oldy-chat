"""Editable, non-destructive project model. No source media is rewritten."""
from __future__ import annotations
from dataclasses import dataclass, field, asdict, fields
from pathlib import Path
import copy, json, math, os, uuid

def uid(): return uuid.uuid4().hex[:16]
def bounded(value, low, high):
    value=float(value)
    if not math.isfinite(value): raise ValueError('Нечисловое значение в проекте')
    return min(high,max(low,value))

@dataclass
class Media:
    path: str
    duration: float
    width: int
    height: int
    fps: float=30
    audio: bool=True
    image: bool=False
    id: str=field(default_factory=uid)
    size: int=0
    stamp: float=0
    thumb: str=''
    @property
    def name(self): return Path(self.path).name

@dataclass
class Grade:
    exposure: float=0
    contrast: float=1
    saturation: float=1
    temperature: float=0
    gamma: float=1
    sharpen: float=0
    denoise: float=0
    vignette: bool=False
    lut: str=''

PRESETS={
    'Оригинал': Grade(), 'Чистый обзор': Grade(contrast=1.06,saturation=1.07,sharpen=.35),
    'Тёплый': Grade(temperature=.18,saturation=1.04),
    'Прохладный': Grade(temperature=-.18,contrast=1.05),
    'Плёнка': Grade(contrast=.92,saturation=.78,temperature=.12,gamma=1.08,vignette=True),
    'Контрастный': Grade(contrast=1.18,saturation=1.12),
    'Чёрно-белый': Grade(contrast=1.1,saturation=0),
}
TRANSITIONS={'cut':'Без перехода','fade':'Наплыв','fadeblack':'Через чёрный',
             'fadewhite':'Через белый','wipeleft':'Шторка','slideleft':'Сдвиг',
             'smoothleft':'Плавный сдвиг','circleopen':'Раскрытие круга'}

@dataclass
class Overlay:
    text: str=''
    start: float=0
    end: float=4
    style: str='card'
    position: str='bottom'
    asset: str=''
    chart: list=field(default_factory=list)

@dataclass
class Clip:
    media_id: str
    start: float
    end: float
    id: str=field(default_factory=uid)
    enabled: bool=True
    label: str=''
    reason: str=''
    grade: Grade=field(default_factory=Grade)
    volume: float=1
    transition: str='cut'
    transition_seconds: float=.35
    overlays: list[Overlay]=field(default_factory=list)
    speed: float=1
    crop_x: float=.5
    crop_y: float=.5
    @property
    def duration(self): return max(0,self.end-self.start)/self.speed

@dataclass
class Export:
    container: str='mp4'
    codec: str='h264'
    width: int=1920
    height: int=1080
    fps: float=30
    quality: str='Высокое'
    bitrate_mbps: float=0
    target_mb: float=0
    fit: str='contain'
    normalize_audio: bool=True
    music: str=''
    music_volume: float=.12
    duck_music: bool=True

@dataclass
class Project:
    name: str='Новый обзор'
    id: str=field(default_factory=uid)
    media: list[Media]=field(default_factory=list)
    clips: list[Clip]=field(default_factory=list)
    export: Export=field(default_factory=Export)
    prompt: str='Собери обзор в указанном порядке. Убери длинные паузы, неудачные дубли и повторы. Сохрани смысл и естественный темп речи. При упоминании мощности, разрешения и FPS добавь короткую карточку с точными значениями. Между разделами — короткий наплыв.'
    transcript: dict=field(default_factory=dict)
    notes: list=field(default_factory=list)
    api_spent: float=0
    schema: int=1
    def media_for(self, clip):
        return next(m for m in self.media if m.id==clip.media_id)
    def active(self): return [c for c in self.clips if c.enabled and c.duration>=.08]
    def transition_lengths(self):
        clips=self.active(); out=[0.0]
        for a,b in zip(clips,clips[1:]):
            sec=min(b.transition_seconds,a.duration/3,b.duration/3) if b.transition!='cut' else 0
            out.append(math.floor(sec*self.export.fps)/self.export.fps)
        return out[:len(clips)]
    @property
    def duration(self): return sum(c.duration for c in self.active())-sum(self.transition_lengths())
    def to_dict(self): return asdict(self)
    @classmethod
    def from_dict(cls,d):
        if d.get('schema',1)!=1: raise ValueError('Эта версия проекта пока не поддерживается')
        def build(typ,x): return typ(**{f.name:x[f.name] for f in fields(typ) if f.name in x})
        p=build(cls,d);p.media=[build(Media,m) for m in d.get('media',[])]
        p.export=build(Export,d.get('export',{}));p.clips=[]
        for raw in d.get('clips',[]):
            c=build(Clip,raw);c.grade=build(Grade,raw.get('grade',{}))
            c.overlays=[build(Overlay,o) for o in raw.get('overlays',[])]
            p.clips.append(c)
        p.validate();return p
    def validate(self):
        if len(self.media)>1000 or len(self.clips)>10000: raise ValueError('Слишком много фрагментов')
        ids={m.id:m for m in self.media}
        if len(ids)!=len(self.media): raise ValueError('Повторяющиеся исходники в проекте')
        for m in self.media:
            if not math.isfinite(m.duration) or m.duration<=0: raise ValueError('Неверная длительность исходника')
        for c in self.clips:
            if c.media_id not in ids: raise ValueError('В проекте не найден исходник фрагмента')
            m=ids[c.media_id]
            c.start=bounded(c.start,0,m.duration);c.end=bounded(c.end,c.start,m.duration)
            c.speed=bounded(c.speed,.25,4);c.volume=bounded(c.volume,0,3)
            c.crop_x=bounded(c.crop_x,0,1);c.crop_y=bounded(c.crop_y,0,1)
            if c.transition not in TRANSITIONS: c.transition='cut'
            c.transition_seconds=bounded(c.transition_seconds,0,2)
            g=c.grade
            for k,l,h in [('exposure',-2,2),('contrast',.3,2),('saturation',0,2),('temperature',-.5,.5),('gamma',.3,3),('sharpen',0,2),('denoise',0,6)]:
                setattr(g,k,bounded(getattr(g,k),l,h))
            if len(c.overlays)>12: raise ValueError('Максимум 12 вставок на фрагмент')
            for o in c.overlays:
                o.start=bounded(o.start,0,c.duration);o.end=bounded(o.end,o.start,c.duration)
                o.text=str(o.text)[:500]
                if o.style not in ['card','title','caption','image','chart']: o.style='card'
                if o.position not in ['top','center','bottom']: o.position='bottom'
                for row in o.chart:
                    row['value']=bounded(row['value'],0,1e7);row['label']=str(row['label'])[:60]
        e=self.export
        if e.container not in ['mp4','mov','mkv']: raise ValueError('Неверный формат экспорта')
        if e.codec not in ['h264','hevc']: raise ValueError('Неверный видеокодек')
        e.width=int(bounded(e.width,128,7680))//2*2;e.height=int(bounded(e.height,128,7680))//2*2
        e.fps=bounded(e.fps,10,120);e.bitrate_mbps=bounded(e.bitrate_mbps,0,250)
        e.target_mb=bounded(e.target_mb,0,1e6);e.music_volume=bounded(e.music_volume,0,1)
        if e.fit not in ['contain','cover']:e.fit='contain'
    def save(self,path):
        self.validate();p=Path(path);p.parent.mkdir(parents=True,exist_ok=True)
        tmp=p.with_name(p.name+'.tmp');tmp.write_text(json.dumps(self.to_dict(),ensure_ascii=False,indent=2),encoding='utf-8');os.replace(tmp,p)
    @classmethod
    def load(cls,path):
        if Path(path).stat().st_size>40_000_000:raise ValueError('Файл проекта слишком большой')
        return cls.from_dict(json.loads(Path(path).read_text(encoding='utf-8')))

class History:
    def __init__(self): self.undo_stack=[];self.redo_stack=[]
    def push(self,p):
        self.undo_stack.append(copy.deepcopy(p.to_dict()));self.undo_stack=self.undo_stack[-50:];self.redo_stack=[]
    def undo(self,p):
        if not self.undo_stack:return p
        self.redo_stack.append(p.to_dict());return Project.from_dict(self.undo_stack.pop())
    def redo(self,p):
        if not self.redo_stack:return p
        self.undo_stack.append(p.to_dict());return Project.from_dict(self.redo_stack.pop())

def timecode(seconds):
    seconds=max(0,float(seconds));h=int(seconds//3600);m=int(seconds%3600//60);s=seconds%60
    return f'{h:02}:{m:02}:{s:05.2f}' if h else f'{m:02}:{s:05.2f}'

def subtract_ranges(start,end,remove,pad=0):
    cuts=sorted((max(start,float(a)-pad),min(end,float(b)+pad)) for a,b in remove if b>a and b>start and a<end)
    out=[];pos=start
    for a,b in cuts:
        if a>pos+.08:out.append((pos,a))
        pos=max(pos,b)
    if end>pos+.08:out.append((pos,end))
    return out
