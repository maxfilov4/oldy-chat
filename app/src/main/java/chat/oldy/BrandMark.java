package chat.oldy;
import android.content.Context;
import android.graphics.*;
import android.view.View;

/** Resolution-independent wordmark: playful retro lettering, a laughing Ы and chat keys. */
final class BrandMark extends View {
 final Paint p=new Paint(3);final boolean light;final long start=android.os.SystemClock.uptimeMillis();
 BrandMark(Context c,boolean l){super(c);light=l;setContentDescription("OldЫ Chat");setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);}
 void fill(int color){p.setColor(color);p.setStyle(Paint.Style.FILL);}
 protected void onDraw(Canvas c){super.onDraw(c);float scale=Math.min(getWidth()/220f,getHeight()/54f);c.save();c.translate((getWidth()-220*scale)/2,(getHeight()-54*scale)/2);c.scale(scale,scale);
  fill(light?0xff263d58:0xffedf5ff);p.setTypeface(Typeface.create("serif",Typeface.BOLD_ITALIC));p.setTextSize(39);c.drawText("Old",9,40,p);
  fill(light?0xffddb46a:0xffeed49c);c.drawRoundRect(83,11,90,43,3,3,p);c.drawRoundRect(86,23,110,43,10,10,p);c.drawRoundRect(115,11,122,43,3,3,p);
  fill(0xff684629);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.7f);p.setStrokeCap(Paint.Cap.ROUND);c.drawLine(94,29,97,27,p);c.drawLine(97,27,99,29,p);c.drawLine(102,29,105,27,p);c.drawLine(105,27,107,29,p);p.setStyle(Paint.Style.FILL);c.drawArc(96,30,106,40,0,180,true,p);fill(0xffdf7a70);c.drawOval(99,35,104,38,p);
  boolean animate=android.animation.ValueAnimator.areAnimatorsEnabled();long t=android.os.SystemClock.uptimeMillis()-start;int active=animate?(int)((t/400)%16):-1;
  for(int i=0;i<4;i++){float x=132+i*20,press=active==i?1.6f:0;fill(light?0xffbfd1e4:0xff101d2d);c.drawRoundRect(x,21,x+18,43,4,4,p);fill(light?0xfff6faff:0xff34516d);c.drawRoundRect(x,17+press,x+18,39+press,4,4,p);fill(light?0xff344e6d:0xffeaf3ff);p.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));p.setTextSize(12);p.setTextAlign(Paint.Align.CENTER);c.drawText("CHAT".substring(i,i+1),x+9,32+press,p);p.setTextAlign(Paint.Align.LEFT);}
  c.restore();if(animate&&isShown())postInvalidateDelayed(120);
 }
}
