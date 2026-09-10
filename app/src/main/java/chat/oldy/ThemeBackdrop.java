package chat.oldy;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;

/** A single continuous surface, including content padding and system-bar areas. */
final class ThemeBackdrop extends Drawable {
 final boolean light;final int style;final Bitmap photo;final Paint paint=new Paint(3);
 final int accent,secondary;Bitmap surface;float phase;boolean glow=true;int alpha=255;
 ThemeBackdrop(Context c,boolean light,boolean cyber){
  this.light=light;style=Math.floorMod(Notices.prefs(c).getInt("wallpaper",cyber?3:0),Wallpaper.NAMES.length);
  photo=Notices.prefs(c).getBoolean("wallpaper_photo",false)?WallpaperPhoto.load(c):null;
  int[] colors={0xff63b7ff,0xffb79aff,0xffe2b579,0xff61e9e7,0xffe191ac,0xff70d4b0,0xffffab7c,0xff72dbe1};
  accent=colors[style];secondary=style==2||style==6?0xffffd8a3:0xffb59bff;
  glow=Notices.prefs(c).getBoolean("wallpaper_glow",true);
 }
 static RectF coverRect(int imageWidth,int imageHeight,float width,float height){
  float scale=Math.max(width/imageWidth,height/imageHeight),w=imageWidth*scale,h=imageHeight*scale;
  return new RectF((width-w)/2,(height-h)/2,(width+w)/2,(height+h)/2);
 }
 void release(){if(surface!=null){surface.recycle();surface=null;}}
 void radial(Canvas c,float x,float y,float radius,int color,int opacity){
  paint.setShader(new RadialGradient(x,y,Math.max(1,radius),new int[]{(color&0xffffff)|(opacity<<24),color&0xffffff},null,Shader.TileMode.CLAMP));c.drawRect(0,0,getBounds().width(),getBounds().height(),paint);paint.setShader(null);
 }
 void build(int w,int h){
  release();surface=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(surface);
  if(photo!=null&&!photo.isRecycled()){
   canvas.drawBitmap(photo,null,coverRect(photo.getWidth(),photo.getHeight(),w,h),paint);
   canvas.drawColor(light?0x65f2f6ff:0x5510192b);
  }else{Wallpaper wallpaper=new Wallpaper(light,style);wallpaper.setBounds(0,0,w,h);wallpaper.draw(canvas);}
  radial(canvas,w*.04f,h*.17f,w*.94f,accent,light?78:45);
  radial(canvas,w*.96f,h*.77f,w*1.05f,secondary,light?66:40);
  paint.setShader(new LinearGradient(0,0,w,h,new int[]{light?0x75ffffff:0x38020815,0x00ffffff,light?0x32edf8ff:0x40010717},new float[]{0,.46f,1},Shader.TileMode.CLAMP));canvas.drawRect(0,0,w,h,paint);paint.setShader(null);
 }
 public void draw(Canvas c){
  Rect b=getBounds();if(b.isEmpty())return;int w=b.width(),h=b.height();if(surface==null||surface.getWidth()!=w||surface.getHeight()!=h)build(w,h);
  c.save();c.translate(b.left,b.top);paint.setStyle(Paint.Style.FILL);paint.setAlpha(alpha);c.drawBitmap(surface,0,0,paint);
  if(glow){
   float drift=(float)Math.sin(phase)*.055f;
   radial(c,-w*.10f,h*(.25f+drift),w*.63f,accent,light?52:68);
   radial(c,w*1.09f,h*(.80f-drift),w*.67f,secondary,light?42:60);
   paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(Math.max(1,w/430f));
   paint.setShader(new LinearGradient(0,0,w,h,new int[]{(accent&0xffffff)|0x88_000000,0x00ffffff,(secondary&0xffffff)|0x77_000000},new float[]{0,.46f,1},Shader.TileMode.CLAMP));
   c.drawRoundRect(1,1,w-1,h-1,Math.min(34,w*.055f),Math.min(34,w*.055f),paint);paint.setShader(null);paint.setStyle(Paint.Style.FILL);
  }c.restore();
 }
 public void setAlpha(int value){alpha=value;invalidateSelf();}public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}public int getOpacity(){return PixelFormat.OPAQUE;}
}
