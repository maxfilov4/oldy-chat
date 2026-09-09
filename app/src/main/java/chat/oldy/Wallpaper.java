package chat.oldy;
import android.graphics.*;
import android.graphics.drawable.Drawable;
/** Original repeating line-art wallpaper, rendered at device resolution. */
final class Wallpaper extends Drawable {
 final boolean light;final int style;final Paint p=new Paint(3);
 Wallpaper(boolean l,int s){light=l;style=s;}
 public void draw(Canvas c){Rect b=getBounds();int top=light?0xffe7edf9:0xff172131,bottom=light?0xffe9f3ef:0xff172a32;if(style==1){top=light?0xffeee9fa:0xff231f38;bottom=light?0xffe6eefb:0xff19283b;}if(style==2){top=light?0xfff6f0e7:0xff2a2625;bottom=light?0xfff1e8dd:0xff242a30;}p.setShader(new LinearGradient(0,0,b.width(),b.height(),top,bottom,Shader.TileMode.CLAMP));c.drawRect(b,p);p.setShader(null);float d=android.content.res.Resources.getSystem().getDisplayMetrics().density;float cell=80*d;p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.1f*d);p.setColor(light?0x18637a98:0x188cbbdf);
  for(int y=0;y<=b.height()/cell+1;y++)for(int x=0;x<=b.width()/cell+1;x++){c.save();c.translate(x*cell+(y%2)*cell/2,y*cell);c.rotate((x+y)%2==0?-18:16);c.scale(d,d);int n=(x*3+y*5+style)%5;
   if(n==0){c.drawRoundRect(-20,-12,20,12,8,8,p);c.drawLine(-13,0,-3,0,p);c.drawLine(-8,-5,-8,5,p);c.drawCircle(9,-3,2,p);c.drawCircle(14,3,2,p);}
   else if(n==1){Path star=new Path();for(int j=0;j<10;j++){double a=j*Math.PI/5-Math.PI/2;float r=j%2==0?16:7;if(j==0)star.moveTo((float)Math.cos(a)*r,(float)Math.sin(a)*r);else star.lineTo((float)Math.cos(a)*r,(float)Math.sin(a)*r);}star.close();c.drawPath(star,p);}
   else if(n==2){c.drawOval(-18,-7,18,7,p);c.drawCircle(0,0,11,p);c.drawCircle(23,-17,2,p);}
   else if(n==3){c.drawRoundRect(-12,-11,10,12,3,3,p);c.drawArc(7,-7,22,7,-80,210,false,p);c.drawLine(-17,16,17,16,p);c.drawLine(-4,-15,-1,-21,p);}
   else{c.drawRoundRect(-16,-17,16,17,4,4,p);c.drawRect(-11,-11,11,2,p);c.drawLine(-10,10,-2,10,p);c.drawLine(-6,6,-6,14,p);c.drawCircle(9,9,2,p);}
   c.restore();}
  p.setStyle(Paint.Style.FILL);
 }
 public void setAlpha(int a){}public void setColorFilter(ColorFilter f){}public int getOpacity(){return PixelFormat.OPAQUE;}
}
