"""Bounded UI capture; recover only the known emulator launcher ANR, never an app ANR."""
import re,subprocess,sys,time,xml.etree.ElementTree as ET
from pathlib import Path
out=Path(sys.argv[1]);expected=sys.argv[2] if len(sys.argv)>2 else ''
for attempt in range(5):
 subprocess.run(['adb','shell','rm','-f','/sdcard/oldy-ui.xml'],stdout=subprocess.DEVNULL,check=True)
 subprocess.run(['adb','shell','uiautomator','dump','/sdcard/oldy-ui.xml'],capture_output=True,timeout=25)
 raw=subprocess.run(['adb','shell','cat','/sdcard/oldy-ui.xml'],capture_output=True,text=True).stdout
 if raw.lstrip().startswith('<?xml'):
  nodes=list(ET.fromstring(raw).iter('node'))
  launcher=any("Quickstep isn't responding" in n.get('text','') and n.get('package')=='android' for n in nodes)
  if launcher:
   close=next(n for n in nodes if n.get('resource-id')=='android:id/aerr_close')
   x,y,r,b=map(int,re.findall(r'\d+',close.get('bounds')))
   print('Recovered emulator Quickstep launcher ANR (not Oldy Chat).')
   subprocess.run(['adb','shell','input','tap',str((x+r)//2),str((y+b)//2)],check=True)
   subprocess.run(['adb','shell','am','start','-W','-n','chat.oldy/.MainActivity'],capture_output=True,check=True)
  elif not expected or expected in raw:
   out.write_text(raw);print('Captured',out.name);break
  else:out.write_text(raw)
 time.sleep(2)
else:raise RuntimeError('Expected application UI not available; see '+str(out))
