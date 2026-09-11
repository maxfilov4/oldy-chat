package chat.oldy;
import android.animation.*;
import android.content.res.ColorStateList;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;
import java.util.WeakHashMap;

/** Native pressed states: no touch listener, so clicks, scrolling and microphone gestures keep working. */
final class PressFeedback {
 private static final WeakHashMap<View,Boolean> installed=new WeakHashMap<>();
 static void walk(View view){
  if(view.isClickable()&&(view instanceof TextView||view instanceof UiIcon||view instanceof LinearLayout)&&!(view instanceof EditText)&&!(view instanceof CompoundButton))apply(view);
  if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)walk(group.getChildAt(i));}
 }
 static void apply(View view){
  if(installed.containsKey(view))return;installed.put(view,true);
  GradientDrawable mask=new GradientDrawable();mask.setColor(0xffffffff);mask.setCornerRadius(18*view.getResources().getDisplayMetrics().density);
  view.setForeground(new RippleDrawable(ColorStateList.valueOf(0x447abce8),null,mask));
  if(Notices.prefs(view.getContext()).getBoolean("animations",true)&&ValueAnimator.areAnimatorsEnabled()){
   StateListAnimator states=new StateListAnimator();
   AnimatorSet down=new AnimatorSet();down.playTogether(ObjectAnimator.ofFloat(view,"scaleX",.975f),ObjectAnimator.ofFloat(view,"scaleY",.975f));down.setDuration(80);
   AnimatorSet up=new AnimatorSet();up.playTogether(ObjectAnimator.ofFloat(view,"scaleX",1f),ObjectAnimator.ofFloat(view,"scaleY",1f));up.setDuration(130);
   states.addState(new int[]{android.R.attr.state_pressed,android.R.attr.state_enabled},down);states.addState(new int[]{},up);view.setStateListAnimator(states);
  }
 }
}
