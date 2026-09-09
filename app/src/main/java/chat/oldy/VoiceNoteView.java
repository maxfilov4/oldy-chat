package chat.oldy;

import android.graphics.*;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;

/** Explicit media width avoids collapsed wrap-content voice bubbles on Android. */
final class VoiceNoteView extends LinearLayout {
 final MainActivity a;final JSONObject message;final String mid;final UiIcon play;final TextView time;final Timeline timeline;
 VoiceNoteView(MainActivity a,JSONObject message){super(a);this.a=a;this.message=message;mid=message.optString("id");setOrientation(HORIZONTAL);setGravity(Gravity.CENTER_VERTICAL);setMinimumWidth(a.dp(215));setPadding(0,a.dp(4),0,a.dp(4));setContentDescription("Голосовое сообщение");
  play=new UiIcon(a,"play",a.light?0xff146c9c:0xff98e5ff);play.setBackground(a.shape(a.light?0xffc9e7fb:0xff194b66,18));play.setContentDescription("Воспроизвести голосовое сообщение");play.setOnClickListener(v->{if(a.voicePlayer==null)a.voicePlayer=new VoicePlayer(a);a.voicePlayer.toggle(message);refresh();});addView(play,new LayoutParams(a.dp(44),a.dp(44)));
  LinearLayout info=a.col();info.setPadding(a.dp(11),0,0,0);timeline=new Timeline(a,message.optString("waveform"));info.addView(timeline,new LayoutParams(-1,a.dp(32)));time=a.label("Голосовое сообщение",11,a.MUTED);info.addView(time,new LayoutParams(-1,-2));addView(info,new LayoutParams(0,-2,1));
  timeline.setOnTouchListener((v,e)->{if(e.getAction()==MotionEvent.ACTION_UP){if(a.voicePlayer!=null)a.voicePlayer.seek(mid,e.getX()/Math.max(1,v.getWidth()));return true;}return true;});
 }
 final Runnable tick=new Runnable(){public void run(){if(!isAttachedToWindow())return;refresh();postDelayed(this,160);}};
 protected void onAttachedToWindow(){super.onAttachedToWindow();removeCallbacks(tick);post(tick);}
 protected void onDetachedFromWindow(){removeCallbacks(tick);super.onDetachedFromWindow();}
 void refresh(){VoicePlayer p=a.voicePlayer;boolean current=p!=null&&mid.equals(p.id);int duration=current?p.duration(mid):0;if(duration==0)duration=message.optInt("duration");int position=current?p.position(mid):0;
  boolean playing=p!=null&&p.playing(mid);play.symbol(playing?"pause":"play");play.setContentDescription(playing?"Пауза голосового сообщения":"Воспроизвести голосовое сообщение");
  time.setText(current&&p.loading?"Загружаем аудио…":duration>0?(position>0?clock(position)+" / ":"")+clock(duration):"Голосовое · "+a.size(message.optLong("size")));
  timeline.progress=duration>0?(float)position/duration:0;timeline.invalidate();
 }
 static String clock(int ms){int seconds=Math.max(0,ms/1000);return String.format(java.util.Locale.ROOT,"%d:%02d",seconds/60,seconds%60);}
 static final class Timeline extends View{
  final Paint p=new Paint(3);byte[] values;float progress;final boolean light;
  Timeline(MainActivity a,String encoded){super(a);light=a.light;try{values=Crypto.un64(encoded);if(values.length>96)values=new byte[0];}catch(Exception ignored){values=new byte[0];}setContentDescription("Прогресс голосового сообщения");}
  protected void onDraw(Canvas c){float gap=getWidth()/40f;p.setStrokeWidth(Math.max(2,gap*.5f));p.setStrokeCap(Paint.Cap.ROUND);for(int i=0;i<40;i++){float value=values.length==0?.035f:(values[Math.min(values.length-1,i*values.length/40)]&255)/255f;float h=Math.max(3,value*getHeight()*.86f);p.setColor(i/40f<progress?(light?0xff16669e:0xff8ee5ff):(light?0xff779fbd:0xff789fb9));float x=(i+.5f)*gap;c.drawLine(x,(getHeight()-h)/2,x,(getHeight()+h)/2,p);}}
 }
}
