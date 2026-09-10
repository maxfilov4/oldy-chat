package chat.oldy;
import android.graphics.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.ScrollView;

/** Pull only from the top of a list; horizontal gestures and ordinary scrolling stay with their child. */
final class RefreshScrollView extends ScrollView {
 final MainActivity a;final Paint paint=new Paint(3);float x,y,pull;boolean eligible,dragging,refreshing;long lastRefresh;
 RefreshScrollView(MainActivity a){super(a);this.a=a;setClipToPadding(false);}
 public boolean onInterceptTouchEvent(MotionEvent e){
  if(refreshing)return super.onInterceptTouchEvent(e);
  switch(e.getActionMasked()){
   case MotionEvent.ACTION_DOWN:x=e.getX();y=e.getY();eligible=!canScrollVertically(-1);dragging=false;pull=0;break;
   case MotionEvent.ACTION_POINTER_DOWN:eligible=false;break;
   case MotionEvent.ACTION_MOVE:float dx=Math.abs(e.getX()-x),dy=e.getY()-y;if(eligible&&dy>a.dp(18)&&dy>dx*1.5f){dragging=true;getParent().requestDisallowInterceptTouchEvent(true);return true;}break;
   case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:eligible=false;break;
  }
  return super.onInterceptTouchEvent(e);
 }
 public boolean onTouchEvent(MotionEvent e){
  if(!dragging)return super.onTouchEvent(e);
  if(e.getActionMasked()==MotionEvent.ACTION_MOVE){pull=Math.max(0,e.getY()-y);invalidate();return true;}
  if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL){
   boolean trigger=e.getActionMasked()==MotionEvent.ACTION_UP&&pull>=a.dp(82)&&SystemClock.elapsedRealtime()-lastRefresh>1500;dragging=false;eligible=false;pull=0;
   if(trigger){lastRefresh=SystemClock.elapsedRealtime();refreshing=true;announceForAccessibility(I18n.t("Обновляем подключение…"));a.connectionMonitor.probe(()->{refreshing=false;invalidate();announceForAccessibility(I18n.t(ChatService.state));});}invalidate();return true;
  }
  return true;
 }
 protected void dispatchDraw(Canvas canvas){super.dispatchDraw(canvas);if(!dragging&&!refreshing)return;
  String text=I18n.t(refreshing?"Обновляем подключение…":pull>=a.dp(82)?"Отпусти, чтобы обновить":"Потяни вниз для обновления");paint.setTextSize(a.dp(12));paint.setTypeface(Typeface.create("sans-serif-medium",0));float w=paint.measureText(text)+a.dp(40),left=(getWidth()-w)/2,top=getScrollY()+a.dp(12);
  paint.setColor(a.CARD);canvas.drawRoundRect(left,top,left+w,top+a.dp(42),a.dp(21),a.dp(21),paint);paint.setColor(a.TEXT);canvas.drawText(text,left+a.dp(20),top+a.dp(26),paint);
 }
}
