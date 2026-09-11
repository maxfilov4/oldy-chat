package chat.oldy;
import android.content.Context;import android.graphics.*;import android.view.View;
/** Neon vector wordmark, drawn sharply at every Android density. */
final class BrandMark extends View {
 final Paint p=new Paint(3);final boolean light;final long start=android.os.SystemClock.uptimeMillis();
 BrandMark(Context c,boolean l){super(c);light=l;setContentDescription(I18n.t("OldЫ Chat"));setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);}
 void fill(int color){p.setShader(null);p.setColor(color);p.setStyle(Paint.Style.FILL);}
 protected void onDraw(Canvas c){super.onDraw(c);float scale=Math.min(getWidth()/224f,getHeight()/56f);c.save();c.translate((getWidth()-224*scale)/2,(getHeight()-56*scale)/2);c.scale(scale,scale);boolean animate=Notices.prefs(getContext()).getBoolean("animations",true)&&android.animation.ValueAnimator.areAnimatorsEnabled();float t=(android.os.SystemClock.uptimeMillis()-start)/1000f;
  fill(light?0xff182d55:0xffe5f6ff);p.setTypeface(Typeface.create("sans-serif-condensed",Typeface.BOLD_ITALIC));p.setTextSize(40);c.drawText("Old",4,40,p);
  p.setShader(new LinearGradient(80,10,124,46,new int[]{light?0xff216db0:0xff53d7ff,light?0xff7659d4:0xffaf8cff},null,Shader.TileMode.CLAMP));c.drawRoundRect(78,10,85,43,3,3,p);c.drawRoundRect(83,23,109,43,9,9,p);c.drawRoundRect(114,10,121,43,3,3,p);fill(light?0xffeafdff:0xff12263e);p.setStrokeWidth(1.8f);p.setStyle(Paint.Style.STROKE);p.setStrokeCap(Paint.Cap.ROUND);c.drawLine(88,29,91,27,p);c.drawLine(91,27,94,29,p);c.drawLine(99,29,102,27,p);c.drawLine(102,27,105,29,p);p.setStyle(Paint.Style.FILL);c.drawArc(91,31,103,41,0,180,true,p);fill(0xfffc88c1);c.drawOval(95,37,100,40,p);
  fill(light?0xff4b5c82:0xffa8f0e3);c.drawRoundRect(110,5,126,12,3,3,p);c.drawRoundRect(117,10,133,13,1.5f,1.5f,p);fill(light?0xff367ba8:0xff69f5dc);c.drawCircle(81,6,2.2f,p);
  float pulse=animate?(float)(.5+.5*Math.sin(t*1.9)):.5f;fill(light?0xffe5ecfd:0xff213452);c.drawRoundRect(132,10,220,43,7,7,p);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1);p.setColor(light?0xffb6c7e9:Color.argb((int)(125+60*pulse),100,183,250));c.drawRoundRect(132,10,220,43,7,7,p);p.setStyle(Paint.Style.FILL);fill(light?0xff31547c:0xffcaedff);p.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));p.setTextSize(12);p.setTextAlign(Paint.Align.CENTER);for(int i=0;i<4;i++){float y=animate?(float)Math.sin(t*2.5-i*.45)*.6f:0;c.drawText("CHAT".substring(i,i+1),144+i*20,32+y,p);}p.setTextAlign(Paint.Align.LEFT);fill(light?0xff427cc0:0xff78d9fa);c.drawRoundRect(136,47,136+18+9*pulse,48.4f,1,1,p);fill(light?0xff996bce:0xffad99fd);c.drawRoundRect(166,47,216,48.4f,1,1,p);
  fill(light?0xffb6c7e9:0xff64dbe4);Path tail=new Path();tail.moveTo(202,43);tail.lineTo(212,43);tail.lineTo(202,51);tail.close();c.drawPath(tail,p);
  c.restore();if(animate&&isShown())postInvalidateDelayed(80);
 }
}
