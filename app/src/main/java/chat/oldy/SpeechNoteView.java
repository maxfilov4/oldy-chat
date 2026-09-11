package chat.oldy;
import android.content.*;import android.view.*;import android.widget.*;import org.json.*;

/** Compact, native animated action below every audio attachment. */
final class SpeechNoteView extends LinearLayout {
 final MainActivity a;final Vault owner;final JSONObject message;final TextView button,transcript;final ProgressBar progress;final String mid;boolean expanded;
 final Runnable tick=new Runnable(){public void run(){update();if(isAttachedToWindow()&&SpeechNotes.job!=null)postDelayed(this,300);}};
 SpeechNoteView(MainActivity a,JSONObject m){super(a);this.a=a;owner=a.vault;message=m;mid=m.optString("id");setOrientation(VERTICAL);LinearLayout row=a.row();progress=new ProgressBar(a);row.addView(progress,new LinearLayout.LayoutParams(a.dp(22),a.dp(22)));button=a.button("",false,()->{String text=SpeechNotes.saved(owner,mid);if(text.isEmpty())SpeechNotes.choose(a,message);else{expanded=!expanded;update();}});button.setTextSize(12);button.setGravity(Gravity.CENTER_VERTICAL);row.addView(button,new LinearLayout.LayoutParams(0,a.dp(44),1));addView(row);transcript=a.label("",14,a.TEXT);transcript.setTextIsSelectable(true);transcript.setLineSpacing(a.dp(3),1);transcript.setPadding(a.dp(8),a.dp(8),a.dp(8),a.dp(8));addView(transcript);update();}
 void update(){String saved=SpeechNotes.saved(owner,mid);SpeechNotes.Job work=SpeechNotes.job;boolean busy=work!=null&&work.account.equals(owner.nick())&&work.mid.equals(mid);progress.setVisibility(busy?VISIBLE:GONE);boolean tooLong=message.optLong("duration")>600000;button.setEnabled(!tooLong);button.setAlpha(tooLong?.6f:1f);button.setText(tooLong?SpeechDecoder.limit():busy?work.status+" · "+LegalCenter.tr("Отмена","Cancel"):saved.isEmpty()?LegalCenter.tr("Aa  В текст · до 10 минут","Aa  To text · up to 10 minutes"):LegalCenter.tr(expanded?"Aa  Скрыть текст":"Aa  Показать текст",expanded?"Aa  Hide text":"Aa  Show text"));transcript.setVisibility(!saved.isEmpty()&&expanded?VISIBLE:GONE);transcript.setText(saved);}
 protected void onAttachedToWindow(){super.onAttachedToWindow();post(tick);}
 protected void onDetachedFromWindow(){removeCallbacks(tick);super.onDetachedFromWindow();}
}
