package chat.oldy;
import android.graphics.*;
import android.os.SystemClock;
import android.text.*;
import android.text.style.ReplacementSpan;
import android.view.View;
import android.widget.TextView;
/** Inline expressions keep mixed text and emoji visible in the same message. */
final class MotionText {
 static void apply(TextView view,int requested){int style=Math.floorMod(requested,12);String text=view.getText().toString(),glyph=MotionEmoji.GLYPHS[style];SpannableString formatted=new SpannableString(text);boolean found=false;
  for(int at=text.indexOf(glyph);at>=0;at=text.indexOf(glyph,at+glyph.length())){formatted.setSpan(new Face(style),at,at+glyph.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);found=true;}
  if(!found)return;view.setText(formatted);if(!Notices.prefs(view.getContext()).getBoolean("motion",true))return;
  final Runnable tick=new Runnable(){public void run(){if(!view.isAttachedToWindow())return;if(view.getGlobalVisibleRect(new Rect()))view.invalidate();view.postDelayed(this,60);}};
  view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){public void onViewAttachedToWindow(View v){view.post(tick);}public void onViewDetachedFromWindow(View v){view.removeCallbacks(tick);}});
 }
 static class Face extends ReplacementSpan{final int style;final Paint paint=new Paint(3);Face(int n){style=n;}
  public int getSize(Paint p,CharSequence s,int start,int end,Paint.FontMetricsInt fm){int size=Math.round(p.getTextSize()*1.7f);if(fm!=null){fm.ascent=-size;fm.top=-size;fm.descent=0;fm.bottom=0;}return size;}
  public void draw(Canvas c,CharSequence text,int start,int end,float x,int top,int y,int bottom,Paint p){float size=p.getTextSize()*1.7f;c.save();c.translate(x,y-size);MotionEmoji.draw(c,size,size,style,SystemClock.uptimeMillis()/1000f,paint);c.restore();}
 }
}
