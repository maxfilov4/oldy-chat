package chat.oldy;
import android.content.Context;import android.graphics.*;import android.os.SystemClock;import android.view.View;
/** Short, local launch animation: no network request or artificial loading progress. */
final class WelcomeArt extends View {
 final Paint paint=new Paint(3);final Bitmap portrait;final long began=SystemClock.uptimeMillis();final boolean motion=android.animation.ValueAnimator.areAnimatorsEnabled();
 WelcomeArt(Context context){super(context);portrait=BitmapFactory.decodeResource(getResources(),R.drawable.launcher_art);setContentDescription(I18n.t("Старый геймер в кепке переписывается на телефоне"));}
 protected void onDraw(Canvas c){super.onDraw(c);float t=motion?(SystemClock.uptimeMillis()-began)/1000f:1.4f,w=getWidth(),h=getHeight(),size=Math.min(w,h)*.84f,left=(w-size)/2,top=(h-size)/2;float bob=motion?(float)Math.sin(t*4)*h*.008f:0;
  paint.setShader(new RadialGradient(w*.5f,h*.53f,w*.5f,new int[]{0x557ccfff,0x001d5275},null,Shader.TileMode.CLAMP));c.drawCircle(w*.5f,h*.53f,w*.5f,paint);paint.setShader(null);
  c.save();c.translate(0,bob);c.rotate(motion?(float)Math.sin(t*3)*1.3f:0,w*.5f,h*.5f);Path mask=new Path();mask.addCircle(w*.5f,h*.5f,size*.5f,Path.Direction.CW);c.clipPath(mask);if(portrait!=null)c.drawBitmap(portrait,null,new RectF(left,top,left+size,top+size),paint);c.restore();
  float enter=Math.min(1,Math.max(0,(t-.15f)*2));bubble(c,w*.04f,h*.22f+(1-enter)*14,w*.32f,h*.14f,I18n.t("Привет!"),enter,0xffd5edff,0xff203e55);
  enter=Math.min(1,Math.max(0,(t-.65f)*2));bubble(c,w*.67f,h*.62f+(1-enter)*14,w*.28f,h*.15f,"",enter,0xffa7d8ff,0xff203e55);
  for(int i=0;i<3;i++){paint.setColor(0xff254e70);paint.setAlpha((int)(enter*(120+110*(.5+.5*Math.sin(t*8-i)))));c.drawCircle(w*(.755f+i*.035f),h*.695f,w*.009f,paint);}paint.setAlpha(255);
  if(motion&&isAttachedToWindow()&&t<2.2f)postInvalidateOnAnimation();
 }
 void bubble(Canvas c,float x,float y,float width,float height,String text,float alpha,int bg,int fg){paint.setColor(bg);paint.setAlpha((int)(255*alpha));c.drawRoundRect(x,y,x+width,y+height,height*.42f,height*.42f,paint);paint.setColor(fg);paint.setAlpha((int)(255*alpha));paint.setTextSize(height*.35f);paint.setTypeface(Typeface.create("sans-serif-rounded",Typeface.BOLD));paint.setTextAlign(Paint.Align.CENTER);c.drawText(text,x+width*.5f,y+height*.61f,paint);paint.setAlpha(255);}
}
