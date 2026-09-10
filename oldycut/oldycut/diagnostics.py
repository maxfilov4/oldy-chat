"""Local, redacted operation logs. Never record prompts, audio or HTTP bodies."""
from __future__ import annotations
import json, os, re, threading, time, uuid
from pathlib import Path


def redact(value, secrets=()):
    text=str(value)
    for secret in secrets:
        if secret:text=text.replace(secret,'[ключ скрыт]')
    text=re.sub(r'(?i)Bearer\s+[^\s\"\'<>]+','Bearer [скрыт]',text)
    text=re.sub(r'\bsk-[A-Za-z0-9_-]+','[ключ скрыт]',text)
    text=re.sub(r'(https?://[^\s?]+)\?[^\s]+',r'\1?[параметры скрыты]',text)
    return text.replace(str(Path.home()),'~')


class RunLog:
    def __init__(self,folder,title):
        folder=Path(folder);folder.mkdir(parents=True,exist_ok=True)
        self.path=folder/(time.strftime('%Y%m%d-%H%M%S-')+uuid.uuid4().hex[:8]+'.log')
        self.lock=threading.Lock();self.started=time.monotonic()
        fd=os.open(self.path,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600);os.close(fd)
        self.write('Oldy Cut 0.2.0 · '+title)
    def write(self,text,secrets=()):
        line=time.strftime('%H:%M:%S')+f'  +{time.monotonic()-self.started:.1f}s  '+redact(text,secrets)
        with self.lock:
            with self.path.open('a',encoding='utf-8') as stream:stream.write(line+'\n')
        return line


def atomic_json(path,value):
    path=Path(path);path.parent.mkdir(parents=True,exist_ok=True)
    temp=path.with_name(path.name+'.'+uuid.uuid4().hex+'.tmp')
    fd=os.open(temp,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    try:
        with os.fdopen(fd,'w',encoding='utf-8') as out:
            json.dump(value,out,ensure_ascii=False);out.flush();os.fsync(out.fileno())
        os.replace(temp,path)
    finally:temp.unlink(missing_ok=True)


def remaining_seconds(processed,total,elapsed):
    """Measured estimate, not a promise. Unknown before enough real progress."""
    if elapsed<1 or processed<=0 or total<=0:return None
    return max(0,(total-min(total,processed))*elapsed/processed)
