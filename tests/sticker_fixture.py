"""Synthetic articulated animation for transport/decoder tests, not AI output."""
import io
from PIL import Image,ImageDraw

def sheet():
 image=Image.new('RGBA',(1536,1024))
 for i in range(6):
  frame=Image.new('RGBA',(512,512));d=ImageDraw.Draw(frame)
  d.rounded_rectangle((140,240,340,470),radius=55,fill='#3997bc',outline='white',width=5)
  d.ellipse((163,65,324,260),fill='#ffc595',outline='#512d36',width=7)
  d.arc((174,68,316,200),175,350,fill='#593d41',width=22)
  d.ellipse((206,145,219,160),fill='#283547');d.ellipse((264,145,277,160),fill='#283547')
  d.arc((222,180,270,210),0,180,fill='#662a3d',width=5)
  handx,handy=[(342,279),(381,227),(405,181),(367,168),(405,198),(345,275)][i]
  d.line([(323,270),(365,290),(handx,handy)],fill='white',width=41)
  d.line([(323,270),(365,290),(handx,handy)],fill='#ffc595',width=32)
  d.ellipse((handx-20,handy-23,handx+20,handy+23),fill='#ffc595',outline='#512d36',width=3)
  image.alpha_composite(frame,((i%3)*512,(i//3)*512))
 output=io.BytesIO();image.save(output,format='PNG');return output.getvalue()
