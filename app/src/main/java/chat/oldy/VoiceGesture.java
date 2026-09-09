package chat.oldy;

import android.view.*;

/** Screen coordinates survive keyboard and recording-bar layout changes. */
final class VoiceGesture implements View.OnTouchListener {
 final MainActivity a;
 float startX;
 boolean holding, cancelled;
 int pointer=-1;
 VoiceGesture(MainActivity a){this.a=a;}
 public boolean onTouch(View v,MotionEvent e){
  if(!holding && a.compose!=null&&!a.compose.getText().toString().trim().isEmpty())return false;
  int action=e.getActionMasked();
  if(action==MotionEvent.ACTION_DOWN){
   pointer=e.getPointerId(0);startX=e.getRawX();cancelled=false;a.mediaTarget=a.chat;
   if(a.voice==null)a.voice=new VoiceCapture(a);
   holding=a.voice.begin();
   if(holding){v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);v.getParent().requestDisallowInterceptTouchEvent(true);}
   return true;
  }
  if(action==MotionEvent.ACTION_MOVE&&holding){
   int index=e.findPointerIndex(pointer);
   if(index<0){cancel(v);return true;}
   float raw=e.getX(index)+(e.getRawX()-e.getX(0));
   float progress=Math.max(0,Math.min(1,(startX-raw)/a.dp(88)));
   if(progress>=1&&!cancelled){cancelled=true;v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);}
   a.voice.cancelProgress(cancelled?1:progress);return true;
  }
  if(action==MotionEvent.ACTION_UP){
   if(holding){cancelled|=startX-e.getRawX()>=a.dp(88);a.voice.end(!cancelled);}
   finish(v);return true;
  }
  if(action==MotionEvent.ACTION_CANCEL||action==MotionEvent.ACTION_POINTER_UP&&e.getPointerId(e.getActionIndex())==pointer){cancel(v);return true;}
  return holding;
 }
 void cancel(View v){if(holding)a.voice.end(false);finish(v);}
 void finish(View v){holding=false;pointer=-1;if(v.getParent()!=null)v.getParent().requestDisallowInterceptTouchEvent(false);}
}
