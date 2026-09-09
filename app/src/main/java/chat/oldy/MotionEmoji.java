package chat.oldy;
import android.content.*;import android.graphics.*;import android.os.SystemClock;import android.view.*;
/** Original animated vector expressions, sized for both picker tiles and messages. */
final class MotionEmoji extends View {
 static final String[] GLYPHS={"😀","😍","😂","😎","🥳","🤯","🥰","😴","😮","😉","😡","😭"};
 static final String[] NAMES={"Привет","Любовь","Хохот","Круто","Праздник","Вау","Тепло","Сон","Удивление","Подмигни","Ярость","Слёзы"};
 final int style;final Paint paint=new Paint(3);final long birth=SystemClock.uptimeMillis();
 MotionEmoji(Context c,int n){super(c);style=Math.floorMod(n,12);setContentDescription("Живой смайл · "+NAMES[style]);}
 protected void onDraw(Canvas c){super.onDraw(c);draw(c,getWidth(),getHeight(),style,(SystemClock.uptimeMillis()-birth)/1000f,paint);if(isShown()&&Notices.prefs(getContext()).getBoolean("motion",true))postInvalidateDelayed(40);}
 static void draw(Canvas c,float w,float h,int style,float t,Paint p){c.save();float scale=Math.min(w,h)/100;c.translate((w-100*scale)/2,(h-100*scale)/2);c.scale(scale,scale);c.translate(50,51+(float)Math.sin(t*3+style)*3);c.rotate((float)Math.sin(t*2)*4);c.translate(-50,-50);p.setShader(new LinearGradient(20,15,80,92,style==10?new int[]{0xffffbf77,0xffff6576}:new int[]{0xffffec9c,0xffffbe43,0xffffa233},null,Shader.TileMode.CLAMP));c.drawCircle(50,50,36,p);p.setShader(null);p.setColor(0x55ffffff);c.drawOval(new RectF(27,22,51,35),p);p.setColor(0xffff9f69);c.drawOval(new RectF(23,53,38,61),p);c.drawOval(new RectF(62,53,77,61),p);p.setColor(0xff443143);p.setStrokeWidth(4);p.setStrokeCap(Paint.Cap.ROUND);
  boolean blink=t%4>3.8||style==7;float eyeH=blink?1:6;
  if(style==3){c.drawRoundRect(new RectF(25,36,47,52),5,5,p);c.drawRoundRect(new RectF(53,36,75,52),5,5,p);c.drawLine(46,41,55,41,p);}else if(style==1||style==6){heart(c,36,44,9,p);heart(c,64,44,9,p);}else{c.drawOval(new RectF(32,43-eyeH,40,43+eyeH),p);if(style==9)c.drawLine(61,45,69,43,p);else c.drawOval(new RectF(60,43-eyeH,68,43+eyeH),p);}
  p.setColor(0xff553045);if(style==8||style==5)c.drawOval(new RectF(44,59,56,75),p);else if(style==7){p.setStyle(Paint.Style.STROKE);c.drawCircle(51,66,5,p);p.setStyle(Paint.Style.FILL);}else if(style==10||style==11){p.setStyle(Paint.Style.STROKE);c.drawArc(new RectF(36,65,64,83),200,140,false,p);p.setStyle(Paint.Style.FILL);}else{c.drawArc(new RectF(32,48,68,78),0,180,true,p);p.setColor(0xfffff9ec);c.drawRoundRect(new RectF(36,62,64,67),2,2,p);}
  if(style==2||style==11){p.setColor(0xff6dcdf7);float y=55+(float)Math.sin(t*4)*5;c.drawOval(new RectF(23,y,30,y+14),p);c.drawOval(new RectF(70,y,77,y+14),p);}
  for(int i=0;i<3;i++){double a=t*(style==4?1.8:.6)+i*2.1;float x=50+(float)Math.cos(a)*44,y=49+(float)Math.sin(a)*40;p.setColor(i%2==0?0xffa9b9ff:0xfff8cc7d);if(style==1||style==6)heart(c,x,y,4,p);else{c.drawCircle(x,y,2.5f,p);}}
  c.restore();
 }
 static void heart(Canvas c,float x,float y,float r,Paint p){p.setColor(0xffef6784);Path q=new Path();q.moveTo(x,y+r);q.cubicTo(x-r*2,y,x-r,y-r*1.5f,x,y-r/2);q.cubicTo(x+r,y-r*1.5f,x+r*2,y,x,y+r);c.drawPath(q,p);}
}
