package chat.oldy;

import android.animation.ValueAnimator;
import android.content.Context;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.View;
import android.widget.LinearLayout;

/** Slow edge light, suspended off-screen, with reduced-motion and battery support. */
final class ThemeLayout extends LinearLayout {
 final ThemeBackdrop backdrop;boolean running;
 final Runnable tick=new Runnable(){public void run(){
  if(!isAttachedToWindow()||getWindowVisibility()!=View.VISIBLE||!running)return;
  PowerManager power=(PowerManager)getContext().getSystemService(Context.POWER_SERVICE);
  if(backdrop.glow&&Notices.prefs(getContext()).getBoolean("animations",true)&&ValueAnimator.areAnimatorsEnabled()&&(power==null||!power.isPowerSaveMode())){
   backdrop.phase=(SystemClock.uptimeMillis()%24000)/24000f*(float)(2*Math.PI);backdrop.invalidateSelf();postDelayed(this,66);
  }else postDelayed(this,1000);
 }};
 ThemeLayout(Context c){this(c,Notices.prefs(c).getString("theme","dark").equals("light"),Notices.prefs(c).getString("theme","dark").equals("cyber"));}
 ThemeLayout(Context c,boolean light,boolean cyber){super(c);setOrientation(VERTICAL);backdrop=new ThemeBackdrop(c,light,cyber);setBackground(backdrop);}
 void animateSurface(boolean active){running=active;removeCallbacks(tick);if(active&&isAttachedToWindow())post(tick);}
 protected void onAttachedToWindow(){super.onAttachedToWindow();animateSurface(true);}
 protected void onDetachedFromWindow(){animateSurface(false);backdrop.release();super.onDetachedFromWindow();}
 protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);if(backdrop!=null)animateSurface(visibility==View.VISIBLE);}
}
