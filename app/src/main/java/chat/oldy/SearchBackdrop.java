package chat.oldy;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
/** Original, quiet constellation background. No bitmaps or network requests. */
final class SearchBackdrop extends Drawable implements Runnable {
 final boolean light,motion; final Paint p=new Paint(3); final float density;
 SearchBackdrop(MainActivity a){light=a.light;motion=Notices.prefs(a).getBoolean("motion",true);density=a.getResources().getDisplayMetrics().density;}
 public void draw(Canvas c){Rect b=getBounds();float w=b.width(),h=b.height();
  p.setShader(new LinearGradient(0,0,w,h,light?0xffedf3ff:0xff131e33,light?0xfff1eaf8:0xff251d35,Shader.TileMode.CLAMP));c.drawRect(b,p);
  p.setShader(new RadialGradient(w*.9f,h*.38f,Math.max(1,w*.9f),new int[]{light?0x5579b9ec:0x383b98c2,0x00000000},null,Shader.TileMode.CLAMP));c.drawRect(b,p);p.setShader(null);
  float t=motion?SystemClock.uptimeMillis()/24000f:0;p.setStrokeWidth(density);p.setStyle(Paint.Style.STROKE);p.setColor(light?0x226b77b1:0x287fa8d9);
  for(int i=0;i<7;i++){float x=w*(.1f+(i*37%83)/100f),y=h*(.2f+(i*19%71)/100f),r=(12+i%3*7)*density;float dy=(float)Math.sin(t+i)*7*density;c.drawCircle(x,y+dy,r,p);if(i%2==0)c.drawRoundRect(x-r*2,y+r*3,x+r,y+r*4.6f,8*density,8*density,p);}
  p.setStyle(Paint.Style.FILL);p.setColor(light?0x456c8bae:0x4ba6c6ee);for(int i=0;i<28;i++)c.drawCircle(w*((i*31%97)/100f),h*((i*17%101)/100f),(i%3==0?1.8f:.8f)*density,p);
  if(motion&&isVisible())scheduleSelf(this,SystemClock.uptimeMillis()+80);
 }
 public void run(){invalidateSelf();}
 public void setAlpha(int a){} public void setColorFilter(ColorFilter f){} public int getOpacity(){return PixelFormat.OPAQUE;}
}
