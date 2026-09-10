from __future__ import annotations
import hashlib, json, math, os, re, shutil, subprocess, sys, tempfile, threading, time
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageOps
from .model import Media, Project, Clip, Overlay, Export, timecode

class Cancelled(Exception): pass

def app_dir():
    root=Path.home()/('Library/Application Support/Oldy Cut' if sys.platform=='darwin' else '.local/share/oldy-cut')
    root.mkdir(parents=True,exist_ok=True);return root

def resources(): return Path(getattr(sys,'_MEIPASS',Path(__file__).resolve().parents[1]))/'assets'

def ffmpeg_path():
    configured=os.environ.get('OLDYCUT_FFMPEG')
    if configured and Path(configured).is_file():return configured
    try:
        import imageio_ffmpeg
        return imageio_ffmpeg.get_ffmpeg_exe()
    except Exception:
        path=shutil.which('ffmpeg')
        if path:return path
    raise RuntimeError('В приложении не найден FFmpeg. Переустанови Oldy Cut.')

class Runner:
    def __init__(self, log=None):
        self.cancelled=threading.Event();self.process=None;self.lock=threading.Lock();self.log=log or (lambda s:None)
    def check(self):
        if self.cancelled.is_set():raise Cancelled('Операция отменена')
    def cancel(self):
        self.cancelled.set()
        with self.lock:
            p=self.process
            if p and p.poll() is None:
                try:p.terminate()
                except OSError:pass
    def run(self,args,cwd=None,allow_failure=False):
        self.check()
        with self.lock:
            p=subprocess.Popen([ffmpeg_path(),'-hide_banner','-nostdin',*map(str,args)],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,cwd=cwd)
            self.process=p
        tail=[];kill_deadline=None
        # Reading is on a worker, never the GUI thread. Reader drains the pipe to
        # prevent large/long FFmpeg output from blocking encode or cancellation.
        chunks=[]
        def drain():
            while True:
                data=p.stdout.read(4096)
                if not data:break
                chunks.append(data)
                if sum(map(len,chunks))>2_000_000:del chunks[:max(1,len(chunks)//2)]
        reader=threading.Thread(target=drain,daemon=True);reader.start()
        while p.poll() is None:
            if self.cancelled.is_set():
                if kill_deadline is None:
                    try:p.terminate()
                    except OSError:pass
                    kill_deadline=time.monotonic()+2
                elif time.monotonic()>kill_deadline:
                    p.kill()
            time.sleep(.08)
        reader.join(3);p.stdout.close()
        with self.lock:self.process=None
        self.check();output=b''.join(chunks).decode('utf-8',errors='replace')
        if p.returncode and not allow_failure:
            raise RuntimeError('Ошибка обработки видео:\n'+output[-3500:])
        return output

def cache_key(*things):
    return hashlib.sha256(json.dumps(things,sort_keys=True,default=str).encode()).hexdigest()[:24]

def probe(path,runner=None):
    r=runner or Runner();p=Path(path).expanduser().resolve()
    if not p.is_file():raise ValueError(f'Файл не найден: {p.name}')
    if p.suffix.lower() in ['.png','.jpg','.jpeg','.webp','.bmp','.tiff']:
        with Image.open(p) as im:
            im=ImageOps.exif_transpose(im);w,h=im.size
        return Media(str(p),5,w,h,audio=False,image=True,size=p.stat().st_size,stamp=p.stat().st_mtime)
    text=r.run(['-i',str(p)],allow_failure=True)
    v=next((line for line in text.splitlines() if re.search(r'Stream #0:\d+.*: Video:',line)),None)
    if not v:raise ValueError(f'В файле «{p.name}» не найдена видеодорожка.\n'+text[-600:])
    wh=re.search(r'\b(\d{2,5})x(\d{2,5})\b',v);dur=re.search(r'Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)',text)
    if not wh or not dur:raise ValueError(f'Не удалось определить размер или длительность «{p.name}»')
    w,h=map(int,wh.groups());duration=int(dur[1])*3600+int(dur[2])*60+float(dur[3])
    rot=re.search(r'rotation of (-?\d+(?:\.\d+)?) degrees',text) or re.search(r'rotate\s*:\s*(-?\d+)',text)
    if rot and int(round(float(rot[1])))%180:w,h=h,w
    rate=re.search(r'(\d+(?:\.\d+)?) fps',v);fps=float(rate[1]) if rate else 30
    return Media(str(p),duration,w,h,fps,audio=bool(re.search(r'Stream #0:\d+.*: Audio:',text)),size=p.stat().st_size,stamp=p.stat().st_mtime)

def thumbnail(media,folder,runner=None,at=None,width=640):
    r=runner or Runner();folder=Path(folder);folder.mkdir(parents=True,exist_ok=True)
    at=min(max(0,at if at is not None else min(1,media.duration*.1)),max(0,media.duration-.08))
    out=folder/(cache_key(media.path,media.size,media.stamp,at,width)+'.jpg')
    if out.exists():return str(out)
    if media.image:
        with Image.open(media.path) as im:
            im=ImageOps.exif_transpose(im).convert('RGB');im.thumbnail((width,width));im.save(out,quality=90)
    else:r.run(['-y','-ss',at,'-i',media.path,'-frames:v','1','-vf',f'scale={width}:-2','-q:v','2',str(out)])
    return str(out)

def silence_ranges(media,runner,threshold=-36,min_pause=.7):
    if not media.audio:return []
    out=runner.run(['-i',media.path,'-map','0:a:0','-vn','-af',f'silencedetect=noise={threshold}dB:d={min_pause}','-f','null','-'])
    ranges=[];start=None
    for line in out.splitlines():
        a=re.search(r'silence_start: ([\d.]+)',line);b=re.search(r'silence_end: ([\d.]+)',line)
        if a:start=float(a[1])
        if b and start is not None:ranges.append((start,float(b[1])));start=None
    if start is not None:ranges.append((start,media.duration))
    # Keep a little room at both sides: avoids clipping breaths/consonants.
    return [(a+.18,b-.18) for a,b in ranges if b-a>min_pause+.36]

def audio_chunk(media,start,duration,path,runner,wav=False):
    args=['-y','-ss',start,'-i',media.path,'-t',duration,'-map','0:a:0','-vn','-ac','1','-ar','16000']
    args+=['-c:a','pcm_s16le'] if wav else ['-c:a','libmp3lame','-b:a','48k']
    runner.run(args+[str(path)]);return Path(path)

def grade_filters(grade,folder=None):
    g=grade;out=[]
    if g.exposure:out.append(f'exposure=exposure={g.exposure}')
    out.append(f'eq=contrast={g.contrast}:saturation={g.saturation}:gamma={g.gamma}')
    if g.temperature:out.append(f'colorbalance=rs={g.temperature}:bs={-g.temperature}:rh={g.temperature/2}:bh={-g.temperature/2}')
    if g.denoise:out.append(f'hqdn3d={g.denoise}:{g.denoise}:{g.denoise*1.5}:{g.denoise*1.5}')
    if g.sharpen:out.append(f'unsharp=5:5:{g.sharpen}:5:5:0')
    if g.vignette:out.append('vignette=PI/5')
    if g.lut:
        lut=Path(g.lut)
        if not lut.is_file():raise ValueError('Файл LUT не найден. Выбери его снова или отключи LUT.')
        name='grade-'+cache_key(g.lut,lut.stat().st_mtime)+'.cube'
        if folder:shutil.copyfile(lut,Path(folder)/name)
        out.append(f"lut3d=file='{name}'")
    return out

def fit_filter(e,c):
    w,h=e.width,e.height
    if e.fit=='cover':
        return [f'scale={w}:{h}:force_original_aspect_ratio=increase',f'crop={w}:{h}:(iw-ow)*{c.crop_x}:(ih-oh)*{c.crop_y}','setsar=1']
    return [f'scale={w}:{h}:force_original_aspect_ratio=decrease',f'pad={w}:{h}:(ow-iw)/2:(oh-ih)/2:color=black','setsar=1']

def font(size,bold=False):
    options=[resources()/('DejaVuSans-Bold.ttf' if bold else 'DejaVuSans.ttf'),
             Path('/System/Library/Fonts/Supplemental/Arial Bold.ttf' if bold else '/System/Library/Fonts/Supplemental/Arial.ttf'),
             Path('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf' if bold else '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf')]
    for p in options:
        if p.exists():return ImageFont.truetype(str(p),size)
    return ImageFont.load_default(size=size)

def draw_overlay(overlay,width,path):
    scale=width/1000;box_w=int(width*.82);fs=max(18,int(34*scale));pad=int(28*scale)
    f=font(fs,True);small=font(max(15,int(24*scale)))
    if overlay.style=='image' and overlay.asset:
        with Image.open(overlay.asset) as im:
            im=ImageOps.exif_transpose(im).convert('RGBA');im.thumbnail((int(width*.46),int(width*.46)))
            im.save(path)
        return
    scratch=Image.new('RGBA',(box_w,1000));d=ImageDraw.Draw(scratch)
    words=overlay.text.split();lines=[];line=''
    for word in words:
        test=(line+' '+word).strip()
        if d.textlength(test,font=f)>box_w-2*pad and line:lines.append(line);line=word
        else:line=test
    if line:lines.append(line)
    lines=lines[:6] or [''];line_h=int(fs*1.35)
    rows=overlay.chart[:8] if overlay.style=='chart' else []
    height=2*pad+line_h*len(lines)+(int(57*scale)*len(rows)+pad if rows else 0)
    im=Image.new('RGBA',(box_w,height));d=ImageDraw.Draw(im)
    if overlay.style!='caption':
        d.rounded_rectangle((0,0,box_w-1,height-1),radius=int(24*scale),fill=(15,23,37,235))
        d.rounded_rectangle((0,int(18*scale),max(4,int(6*scale)),height-int(18*scale)),radius=2,fill=(103,224,211,255))
    for i,line in enumerate(lines):
        d.text((pad,pad+i*line_h),line,font=f,fill='white',stroke_width=1,stroke_fill=(0,0,0,180))
    if rows:
        maxval=max(1,max(float(r['value']) for r in rows));y=pad+line_h*len(lines)+pad//2
        for row in rows:
            d.text((pad,y),str(row['label'])[:32],font=small,fill=(199,212,229))
            label=f"{float(row['value']):g}";d.text((box_w-pad-d.textlength(label,font=small),y),label,font=small,fill='white')
            y+=int(30*scale)
            d.rounded_rectangle((pad,y,box_w-pad,y+max(4,int(8*scale))),radius=3,fill=(43,55,72))
            d.rounded_rectangle((pad,y,pad+(box_w-2*pad)*float(row['value'])/maxval,y+max(4,int(8*scale))),radius=3,fill=(103,224,211))
            y+=int(27*scale)
    im.save(path)

def audio_tempo(speed):
    filters=[]
    while speed>2:filters.append('atempo=2');speed/=2
    while speed<.5:filters.append('atempo=0.5');speed/=.5
    filters.append(f'atempo={speed}');return filters

class Renderer:
    def __init__(self,runner=None,progress=None):
        self.runner=runner or Runner();self.progress=progress or (lambda n,s:None)
    def render(self,project,output,preview=False):
        import copy
        p=copy.deepcopy(project);p.validate();clips=p.active();e=p.export
        if not clips:raise ValueError('На монтажной ленте нет включённых фрагментов')
        destination=Path(output).expanduser().resolve()
        sources={Path(m.path).resolve() for m in p.media}
        sources|={Path(e.music).resolve()} if e.music else set()
        if destination in sources:raise ValueError('Выбери другое имя: исходник нельзя перезаписать')
        for c in clips:
            if not Path(p.media_for(c).path).is_file():raise ValueError(f'Исходник не найден: {p.media_for(c).name}')
        if preview:
            ratio=e.width/e.height
            if e.width>=e.height:e.width=960;e.height=int(960/ratio)//2*2
            else:e.height=960;e.width=int(960*ratio)//2*2
            e.codec='h264';e.container='mp4';e.quality='Быстрое превью';e.bitrate_mbps=0;e.target_mb=0
        workbase=app_dir()/'renders';workbase.mkdir(exist_ok=True)
        # Temporary folder grows on disk, not in RAM; each source is streamed.
        with tempfile.TemporaryDirectory(prefix='render-',dir=workbase) as temp:
            work=Path(temp);trans=p.transition_lengths();normalized=[]
            total=len(clips);fps=e.fps
            duration_frames=[]
            for i,c in enumerate(clips):
                self.runner.check();self.progress(int(i/total*65),f'Фрагмент {i+1}/{total} · {p.media_for(c).name}')
                dst=work/f'clip-{i:05d}.mkv';frames=max(2,round(c.duration*fps));duration_frames.append(frames)
                self._normalize(p.media_for(c),c,e,dst,work,frames/fps,preview)
                normalized.append(dst)
            sequence=[]
            for i,(c,src) in enumerate(zip(clips,normalized)):
                self.runner.check();self.progress(65+int(i/total*22),f'Переходы · {i+1}/{total}')
                lead=round(trans[i]*fps) if i<len(trans) else 0
                tail=round(trans[i+1]*fps) if i+1<len(trans) else 0
                if lead or tail:
                    out=work/f'middle-{i:05d}.mkv'
                    self._trim(src,out,lead/fps,(duration_frames[i]-lead-tail)/fps,e,work,preview)
                else:out=src
                sequence.append(out)
                if tail:
                    out=work/f'bridge-{i:05d}.mkv';t=tail/fps
                    self._bridge(src,normalized[i+1],out,(duration_frames[i]-tail)/fps,t,clips[i+1].transition,e,work,preview)
                    sequence.append(out)
            listing=work/'sequence.txt';listing.write_text(''.join(f"file '{s.name}'\n" for s in sequence))
            suffix='.'+e.container
            destination.parent.mkdir(parents=True,exist_ok=True)
            # Export beside destination, then atomically replace only on success.
            fd,tmpname=tempfile.mkstemp(prefix='.oldycut-',suffix=suffix,dir=destination.parent);os.close(fd)
            tmpout=Path(tmpname)
            try:
                args=['-y','-f','concat','-safe','1','-i',str(listing)]
                audio=[]
                if e.music:
                    if not Path(e.music).is_file():raise ValueError('Музыкальный файл не найден')
                    args+=['-stream_loop','-1','-i',e.music]
                    m=f'[1:a]aformat=sample_rates=48000:channel_layouts=stereo,volume={e.music_volume}[music]'
                    if e.duck_music:
                        audio=[m,'[0:a]asplit=2[voice][side]','[music][side]sidechaincompress=threshold=0.025:ratio=8:attack=30:release=700[duck]','[voice][duck]amix=inputs=2:duration=first:normalize=0[mix]']
                    else:audio=[m,'[0:a][music]amix=inputs=2:duration=first:normalize=0[mix]']
                    audio.append('[mix]'+('loudnorm=I=-16:TP=-1.5:LRA=11,' if e.normalize_audio else '')+'alimiter=limit=0.94:level=false[outa]')
                    args+=['-filter_complex',';'.join(audio),'-map','0:v:0','-map','[outa]']
                else:
                    args+=['-map','0:v:0','-map','0:a:0']
                    args+=['-af',('loudnorm=I=-16:TP=-1.5:LRA=11,' if e.normalize_audio else '')+'alimiter=limit=0.94:level=false']
                bitrate=e.bitrate_mbps
                if e.target_mb:
                    bitrate=(e.target_mb*8/max(.1,p.duration))*.97-.192
                    if bitrate<.2:raise ValueError('Выбранный размер слишком мал для этой длительности. Увеличь размер файла.')
                if bitrate:
                    args+=self._encoder(e,preview,bitrate)
                else:args+=['-c:v','copy']
                args+=['-c:a','aac','-b:a','192k','-ar','48000','-ac','2','-map_metadata','-1']
                if e.container in ['mp4','mov']:
                    args+=['-movflags','+faststart']
                    if e.codec=='hevc':args+=['-tag:v','hvc1']
                args+=[str(tmpout)]
                self.progress(88,'Сохранение файла · сведение звука и финальная упаковка')
                self.runner.run(args,cwd=work)
                if tmpout.stat().st_size<500:raise RuntimeError('Экспорт не создал видеофайл')
                self.runner.check();os.replace(tmpout,destination)
            finally:tmpout.unlink(missing_ok=True)
        self.progress(100,'Готово');return str(destination)
    def _encoder(self,e,preview=False,bitrate=0):
        codec='libx265' if e.codec=='hevc' else 'libx264'
        args=['-c:v',codec,'-preset','ultrafast' if preview else 'fast','-threads','4','-pix_fmt','yuv420p']
        if e.codec=='hevc':args+=['-x265-params','pools=4:frame-threads=2:log-level=error']
        if bitrate:args+=['-b:v',f'{bitrate:.3f}M','-maxrate',f'{bitrate*1.15:.3f}M','-bufsize',f'{bitrate*2:.3f}M']
        else:args+=['-crf',str({'Максимальное':16,'Высокое':20,'Компактное':25,'Быстрое превью':28}.get(e.quality,20))]
        return args
    def _normalize(self,m,c,e,dst,work,duration,preview):
        args=['-y']
        if m.image:args+=['-loop','1','-framerate',str(e.fps),'-i',m.path]
        else:args+=['-ss',str(c.start),'-t',str(c.end-c.start),'-i',m.path]
        audio_index=0
        if not m.audio:
            args+=['-f','lavfi','-i','anullsrc=r=48000:cl=stereo'];audio_index=1
        filt=[f'setpts=(PTS-STARTPTS)/{c.speed}',f'fps={e.fps}',*fit_filter(e,c),*grade_filters(c.grade,work),'format=yuv420p',f'tpad=stop_mode=clone:stop_duration={1/e.fps}',f'trim=duration={duration}','settb=AVTB']
        graph=['[0:v:0]'+','.join(filt)+'[base]'];label='base';idx=2 if not m.audio else 1
        for j,o in enumerate(c.overlays):
            if o.end<=o.start or (not o.text and not o.asset and not o.chart):continue
            if o.style=='image' and not Path(o.asset).is_file():raise ValueError('Картинка для вставки не найдена')
            op=work/f'overlay-{j:02d}.png';draw_overlay(o,e.width,op);args+=['-loop','1','-i',str(op)]
            fade=min(.22,(o.end-o.start)/3)
            graph.append(f'[{idx}:v]format=rgba,fade=t=in:st={o.start}:d={fade}:alpha=1,fade=t=out:st={max(o.start,o.end-fade)}:d={fade}:alpha=1[ol{j}]')
            y={'top':'H*0.08','center':'(H-h)/2','bottom':'H-h-H*0.08'}[o.position]
            x=f'(W-w)/2-40*max(0,1-(t-{o.start})/0.22)'
            graph.append(f"[{label}][ol{j}]overlay=x='{x}':y='{y}':enable='between(t,{o.start},{o.end})':shortest=1[v{j}]")
            label=f'v{j}';idx+=1
        af=['asetpts=PTS-STARTPTS',*audio_tempo(c.speed),f'volume={c.volume}','aresample=48000','aformat=channel_layouts=stereo','apad',f'atrim=duration={duration}']
        graph.append(f'[{audio_index}:a:0]'+','.join(af)+'[aud]')
        args+=['-filter_complex_threads','1','-filter_complex',';'.join(graph),'-map',f'[{label}]','-map','[aud]','-t',str(duration)]
        args+=self._encoder(e,preview)+['-c:a','pcm_s16le','-ar','48000','-ac','2','-map_metadata','-1',str(dst)]
        self.runner.run(args,cwd=work)
    def _trim(self,src,dst,start,duration,e,work,preview):
        self.runner.run(['-y','-i',str(src),'-ss',start,'-t',duration,'-vf',f'setpts=PTS-STARTPTS,fps={e.fps}','-af','asetpts=PTS-STARTPTS',*self._encoder(e,preview),'-c:a','pcm_s16le',str(dst)],cwd=work)
    def _bridge(self,a,b,dst,start,duration,transition,e,work,preview):
        graph=f'[0:v]trim=duration={duration},setpts=PTS-STARTPTS,fps={e.fps},settb=AVTB[a];[1:v]trim=duration={duration},setpts=PTS-STARTPTS,fps={e.fps},settb=AVTB[b];[a][b]xfade=transition={transition}:duration={duration}:offset=0[v];[0:a]atrim=duration={duration},asetpts=PTS-STARTPTS[aa];[1:a]atrim=duration={duration},asetpts=PTS-STARTPTS[bb];[aa][bb]acrossfade=d={duration}:c1=tri:c2=tri[au]'
        self.runner.run(['-y','-ss',start,'-i',str(a),'-i',str(b),'-filter_complex_threads','1','-filter_complex',graph,'-map','[v]','-map','[au]','-t',duration,*self._encoder(e,preview),'-c:a','pcm_s16le',str(dst)],cwd=work)

def color_preview(media,clip,folder,runner=None):
    from .model import Export
    r=runner or Runner();folder=Path(folder);folder.mkdir(parents=True,exist_ok=True)
    key=cache_key(media.path,media.stamp,clip.start,clip.grade.__dict__,clip.crop_x,clip.crop_y)
    out=folder/(key+'.jpg')
    if out.exists():return str(out)
    with tempfile.TemporaryDirectory(dir=folder,prefix='frame-') as temp:
        filt=['scale=960:-2',*grade_filters(clip.grade,temp)]
        r.run(['-y','-ss',clip.start if not media.image else 0,'-i',media.path,'-frames:v','1','-vf',','.join(filt),'-q:v','2',str(out)],cwd=temp)
    return str(out)
