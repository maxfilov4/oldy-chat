package chat.oldy;
import android.content.*;
import android.graphics.*;
import android.view.*;
import org.json.*;
import java.util.concurrent.*;
/** Original illustrated avatar atlas, animated mascot sprite atlas and vector stickers. */
final class Art extends View {
 static Bitmap mascot,avatars;float testTime=-1;static final android.util.LruCache<String,Bitmap> photos=new android.util.LruCache<>(40);
 static final ExecutorService fetch=Executors.newFixedThreadPool(2);
 final Paint p=new Paint(3);final String key;final int type,index;boolean requested;Bitmap photo;
 Art(Context c,String k){super(c);key=k==null?"preset:0":k;type=key.equals("mascot")?2:key.startsWith("sticker:")?1:0;int n=0;try{n=Integer.parseInt(key.substring(key.indexOf(':')+1));}catch(Exception ignored){}index=Math.max(0,Math.min(11,n));setContentDescription(type==2?"Старый геймер: гуляет, приседает и пишет":type==1?"Анимированный эмодзи":"Аватар");
  if(type==2&&mascot==null)try{BitmapFactory.Options opt=new BitmapFactory.Options();opt.inSampleSize=1;mascot=BitmapFactory.decodeStream(c.getAssets().open("mascot-v3.webp"),null,opt);}catch(Exception ignored){}
  if(type==0&&!key.startsWith("photo:")&&avatars==null)try{avatars=BitmapFactory.decodeStream(c.getAssets().open("avatars-v3.webp"));}catch(Exception ignored){}
 }
 void oval(Canvas c,int color,float l,float t,float r,float b){p.setColor(color);c.drawOval(l,t,r,b,p);}
 void rect(Canvas c,int color,float l,float t,float r,float b,float radius){p.setColor(color);c.drawRoundRect(l,t,r,b,radius,radius,p);}
 protected void onDraw(Canvas c){super.onDraw(c);float w=getWidth(),h=getHeight();boolean moving=Notices.prefs(getContext()).getBoolean("animations",true);float t=testTime>=0?testTime:moving?(android.os.SystemClock.uptimeMillis()%18000)/1000f:9;
  c.save();Path clip=new Path();clip.addRoundRect(0,0,w,h,Math.min(w,h)*(type==2?.25f:.5f),Math.min(w,h)*(type==2?.25f:.5f),Path.Direction.CW);c.clipPath(clip);
  if(type==2&&mascot!=null){
   int frame=4;float left=w*.2f;boolean backwards=false;float phase=t%18;
   if(phase<8){frame=((int)(phase*7))%4;backwards=phase>=4;left=(backwards?(8-phase)/4:phase/4)*w*.4f;}
   else if(phase<10)frame=4;else if(phase<13)frame=5;else if(phase<17)frame=6+((int)(phase*3)%2);
   int sw=mascot.getWidth()/4,sh=mascot.getHeight()/2;Rect source=new Rect((frame%4)*sw,(frame/4)*sh,(frame%4+1)*sw,(frame/4+1)*sh);float width=Math.min(w*.67f,h*(float)sw/sh);float x=Math.min(left,w-width);c.save();if(backwards)c.scale(-1,1,x+width/2,h/2);c.drawBitmap(mascot,source,new RectF(x,0,x+width,h),p);c.restore();
   if(phase>=13&&phase<17){p.setColor(0xff83b8ee);p.setStrokeWidth(Math.max(1,w*.012f));p.setStyle(Paint.Style.STROKE);Path scribble=new Path();float start=w*.66f,yy=h*.35f;scribble.moveTo(start,yy);int steps=(int)((phase-13)*10)%20;for(int i=0;i<steps;i++)scribble.lineTo(start+i*w*.012f,yy+(float)Math.sin(i*2.7f)*h*.02f+(i/7)*h*.08f);c.drawPath(scribble,p);p.setStyle(Paint.Style.FILL);}
  }else if(type==0&&avatars!=null&&!key.startsWith("photo:")){
   int cell=avatars.getWidth()/4;int cy=(int)((index/4+.5f)*avatars.getHeight()/3);Rect src=new Rect((index%4)*cell,cy-cell/2,(index%4+1)*cell,cy+cell/2);c.drawBitmap(avatars,src,new RectF(0,0,w,h),p);
  }else if(key.startsWith("photo:")){
   if(photo==null)photo=photos.get(key);
   if(photo!=null){float scale=Math.max(w/photo.getWidth(),h/photo.getHeight());float bw=photo.getWidth()*scale,bh=photo.getHeight()*scale;c.drawBitmap(photo,null,new RectF((w-bw)/2,(h-bh)/2,(w+bw)/2,(h+bh)/2),p);}
   else{c.drawColor(0xff466359);if(!requested){requested=true;fetch.execute(()->{try{Vault v=ChatService.vault(getContext());JSONObject r=new Api(getContext()).call("/avatar/"+key.substring(6),null,v.token());byte[] b=Crypto.un64(r.getString("photo"));Bitmap image=BitmapFactory.decodeByteArray(b,0,b.length);if(image!=null){photos.put(key,image);photo=image;postInvalidate();}}catch(Exception ignored){}});}}
  }else{c.scale(w/100,h/100);if(type==1)emoji(c,t);else avatar(c,t);}
  c.restore();if(moving&&isAttachedToWindow()&&getWindowVisibility()==VISIBLE&&(type!=0||avatars==null)&&(!key.startsWith("photo:")))postInvalidateDelayed(50);
 }
 void avatar(Canvas c,float t){
  int[] bg={0xffc9f3ac,0xffffd3ce,0xffcbbdfa,0xffbce8f6,0xfff4d78f,0xffb1e4ce,0xffefbfe8,0xffbccdfe,0xffecd0ba,0xff8accc0,0xffffb9c9,0xffdddba7};
  int[] skin={0xffefb58c,0xffa56b4a,0xfff5cdaa,0xffd89c79,0xff8c543d,0xfff0ba96};int[] hair={0xff392b30,0xff9b5133,0xfff0d79a,0xff302b40,0xff615563,0xff75432c};
  int sc=skin[index%6],hc=hair[index%6];c.drawColor(bg[index]);float bob=(float)Math.sin(t*2+index)*1.4f;c.translate(0,bob);boolean woman=index%2==1;
  if(woman)rect(c,hc,22,18,80,83,23);
  oval(c,0xff303c57,14,70,88,126);rect(c,sc,43,58,59,81,7);oval(c,sc,25,23,76,73);oval(c,sc,21,41,33,55);oval(c,sc,70,41,81,55);
  oval(c,hc,24,17,75,45);oval(c,sc,29,31,73,68);if(woman)oval(c,hc,20,18,47,53);else oval(c,hc,25,14,76,36);
  float blink=((int)(t*10+index*7)%47==0)?1:4;
  oval(c,0xff272b36,37,44-blink,42,44+blink);oval(c,0xff272b36,59,44-blink,64,44+blink);
  oval(c,0x44813f30,45,48,56,56);p.setColor(0xff7e3e39);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2.5f);c.drawArc(40,49,62,63,15,150,false,p);p.setStyle(Paint.Style.FILL);
  oval(c,0x44f27180,30,51,39,57);oval(c,0x44f27180,64,51,72,57);
  if(index==0||index==4||index==8){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2);rect(c,0xff283140,31,36,47,50,6);rect(c,0xff283140,54,36,70,50,6);p.setColor(0xff283140);c.drawLine(47,42,54,42,p);p.setStyle(Paint.Style.FILL);}
  if(index==2||index==6){rect(c,0xff5a77bc,24,22,78,29,4);oval(c,0xff5a77bc,27,12,71,30);}
  if(index==8){oval(c,0xffdfd5c9,31,56,50,67);oval(c,0xffdfd5c9,48,56,68,67);}
  if(woman){oval(c,0xfff4dd72,25,56,31,64);oval(c,0xfff4dd72,71,56,77,64);}
  p.setColor(0x66ffffff);c.drawCircle(17,18+(float)Math.sin(t*3)*3,3,p);c.drawCircle(84,66-(float)Math.sin(t*2)*3,2,p);
 }
 void emoji(Canvas c,float t){
  float pulse=1+(float)Math.sin(t*4)*.025f;c.translate(50,50);c.scale(pulse,pulse);c.translate(-50,-50);oval(c,0x18000000,17,82,84,93);
  int[] colors={0xffffd467,0xffc2f68b,0xffffaaa8,0xffb9b1ff,0xffffcf70,0xff8ee0d1};
  oval(c,colors[index%6],8,8,92,92);oval(c,0x22ffffff,17,13,74,41);
  p.setColor(0xff343047);float eye=(float)Math.sin(t*3)*1.1f;
  if(index==2||index==9){p.setTextSize(26);p.setTextAlign(Paint.Align.CENTER);c.drawText("♥",33,47,p);c.drawText("♥",66,47,p);}
  else if(index==3){rect(c,0xff343047,18,32,45,48,6);rect(c,0xff343047,55,32,82,48,6);p.setStrokeWidth(4);c.drawLine(44,37,56,37,p);}
  else{oval(c,0xff343047,28,34,36,45+eye);oval(c,0xff343047,64,34,72,45-eye);}
  if(index==4||index==7){oval(c,0xff343047,40,54,62,77);oval(c,0xffff8b94,44,67,59,77);}
  else{p.setColor(0xff343047);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(4);c.drawArc(31,42,70,69,15,150,false,p);p.setStyle(Paint.Style.FILL);}
  oval(c,0x55ec7277,17,48,32,57);oval(c,0x55ec7277,69,48,84,57);
  String[] marks={"✦","✦","♥","✦","!","☕","✦","!","♫","♥","★","✦"};p.setTextSize(23);p.setTextAlign(Paint.Align.CENTER);p.setColor(0xffb5f779);c.drawText(marks[index],84,19+(float)Math.sin(t*4)*5,p);
 }
}
