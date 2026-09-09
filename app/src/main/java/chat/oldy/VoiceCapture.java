package chat.oldy;
import android.*;import android.content.pm.PackageManager;import android.graphics.*;import android.media.*;import android.net.Uri;import android.os.*;import android.view.*;import android.widget.*;import java.io.*;import java.util.*;import org.json.*;
/** Recorder, visible input meter and a retryable upload. Only encoded audio can be sent. */
final class VoiceCapture {
 final MainActivity a;MediaRecorder recorder;File file;long started;String target;Vault account;LinearLayout bar;TextView timer,hint;Wave wave;boolean running,silenced,uploading;int ticks;final ArrayList<Integer> levels=new ArrayList<>();
 File pending;JSONObject pendingMetadata;String pendingTarget;Vault pendingAccount;final Runnable sample=this::tick;
 VoiceCapture(MainActivity activity){a=activity;}
 boolean begin(){
  if(running)return true;if(uploading||pending!=null){showPending();return false;}
  if(a.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){a.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},11);return false;}
  if(LiveCall.current!=null){a.error("Заверши звонок перед записью голосового");return false;}
  AudioManager audio=a.getSystemService(AudioManager.class);if(audio!=null&&audio.isMicrophoneMute()){a.error("Включи доступ к микрофону в настройках Android");return false;}
  try{
   if(a.voicePlayer!=null)a.voicePlayer.release();target=a.chat;account=a.vault;levels.clear();ticks=0;silenced=false;
   file=new File(a.getCacheDir(),"voice-"+System.currentTimeMillis()+".m4a");recorder=new MediaRecorder();recorder.setAudioSource(MediaRecorder.AudioSource.MIC);recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);recorder.setAudioEncodingBitRate(96000);recorder.setAudioSamplingRate(44100);recorder.setAudioChannels(1);recorder.setMaxDuration(180000);recorder.setOutputFile(file.getAbsolutePath());
   recorder.setOnInfoListener((r,what,extra)->{if(what==MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED)end(true);});recorder.setOnErrorListener((r,what,extra)->{end(false);a.error("Микрофон прервал запись. Попробуй снова.");});recorder.prepare();recorder.start();recorder.getMaxAmplitude();started=SystemClock.elapsedRealtime();running=true;
   bar=a.col();a.pad(bar,10);bar.setBackground(a.shape(a.CARD,18));LinearLayout controls=a.row();timer=a.label("● 0:00",14,0xffe85879);timer.setTypeface(null,1);controls.addView(timer,new LinearLayout.LayoutParams(0,a.dp(40),1));timer.setGravity(Gravity.CENTER_VERTICAL);
   controls.addView(icon("close","Отменить запись",()->end(false)),new LinearLayout.LayoutParams(a.dp(44),a.dp(44)));controls.addView(icon("send","Отправить голосовое",()->end(true)),new LinearLayout.LayoutParams(a.dp(44),a.dp(44)));bar.addView(controls,new LinearLayout.LayoutParams(-1,-2));
   wave=new Wave(a);wave.setContentDescription("Громкость микрофона в реальном времени");bar.addView(wave,new LinearLayout.LayoutParams(-1,a.dp(50)));hint=a.label("Говори · отпусти для отправки · влево для отмены",11,a.MUTED);hint.setPadding(0,a.dp(4),0,0);bar.addView(hint,new LinearLayout.LayoutParams(-1,-2));a.replyBar.removeAllViews();a.replyBar.addView(bar,new LinearLayout.LayoutParams(-1,-2));tick();return true;
  }catch(Exception e){end(false);a.error("Не удалось включить микрофон. Проверь разрешение и доступ к микрофону в Android.");return false;}
 }
 View icon(String name,String description,Runnable action){UiIcon v=new UiIcon(a,name,a.light?0xff176aab:0xff83d9ff);v.setContentDescription(description);v.setBackground(a.shape(a.light?0xffe6f2ff:0xff263b50,14));v.setOnClickListener(x->action.run());return v;}
 void tick(){if(!running)return;long s=(SystemClock.elapsedRealtime()-started)/1000;timer.setText(String.format(Locale.ROOT,"● %d:%02d",s/60,s%60));try{int amplitude=recorder.getMaxAmplitude();levels.add(amplitude);wave.level(amplitude);ticks++;if(Build.VERSION.SDK_INT>=29){AudioRecordingConfiguration config=recorder.getActiveRecordingConfiguration();silenced=config!=null&&config.isClientSilenced();}hint.setText(silenced?"Android отключил микрофон для этой записи":s>=2&&Collections.max(levels)==0?"Микрофон не получает звук. Проверь доступ к нему.":"Говори · отпусти для отправки · влево для отмены");}catch(Exception ignored){}a.ui.postDelayed(sample,65);}
 void end(boolean send){
  if(recorder==null)return;running=false;a.ui.removeCallbacks(sample);long elapsed=SystemClock.elapsedRealtime()-started;boolean valid=false;try{recorder.stop();valid=file!=null&&file.length()>500&&elapsed>=700&&!silenced;}catch(Exception ignored){}try{recorder.release();}catch(Exception ignored){}recorder=null;if(a.replyBar!=null)a.replyBar.removeAllViews();File finished=file;file=null;
  if(!send||!valid){if(finished!=null)finished.delete();if(send)a.error(silenced?"Android не передал звук микрофона. Запись не отправлена.":"Запись не сохранилась. Удерживай микрофон и говори хотя бы секунду.");return;}
  try{byte[] points=new byte[48];for(int i=0;i<points.length;i++){int lo=i*levels.size()/48,hi=Math.max(lo+1,(i+1)*levels.size()/48),peak=0;for(int j=lo;j<hi&&j<levels.size();j++)peak=Math.max(peak,levels.get(j));points[i]=(byte)Math.round(Wave.normalized(peak)*255);}
   pending=finished;pendingTarget=target;pendingAccount=account;pendingMetadata=new JSONObject().put("duration",elapsed).put("waveform",Crypto.b64(points));uploadPending();
  }catch(Exception e){finished.delete();a.error("Не удалось сохранить запись");}
 }
 void uploadPending(){if(pending==null||uploading)return;if(a.vault!=pendingAccount){discard();return;}uploading=true;showPending();final File saved=pending;final Vault owner=pendingAccount;final String dest=pendingTarget;final JSONObject meta=pendingMetadata;
  a.work.execute(()->{try{MediaExtractor ex=new MediaExtractor();try{ex.setDataSource(saved.getAbsolutePath());int track=-1;for(int i=0;i<ex.getTrackCount();i++)if(ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME).startsWith("audio/")){track=i;break;}if(track<0)throw new IOException("В записи нет аудиодорожки");MediaFormat format=ex.getTrackFormat(track);ex.selectTrack(track);int samples=0;while(ex.getSampleTime()>=0&&samples<8){samples++;ex.advance();}if(samples<8)throw new IOException("Аудиозапись повреждена");if(format.containsKey(MediaFormat.KEY_DURATION))meta.put("duration",format.getLong(MediaFormat.KEY_DURATION)/1000);}finally{ex.release();}
   if(a.vault!=owner)throw new IOException("Аккаунт изменился");CloudMedia.attach(a,Uri.fromFile(saved),"audio/mp4","Голосовое сообщение",dest,"",meta);saved.delete();a.runOnUiThread(()->{uploading=false;pending=null;if(a.vault==owner&&a.chat.equals(dest)&&a.replyBar!=null)a.renderReply();});
  }catch(Exception error){a.runOnUiThread(()->{uploading=false;if(a.vault!=owner){discard();return;}showPending();a.error("Голосовое не отправлено. Запись сохранена для повторной отправки. "+Api.message(error));});}});
 }
 void discard(){if(uploading)return;if(pending!=null)pending.delete();pending=null;if(a.replyBar!=null)a.renderReply();}
 void showPending(){if(pending==null||a.replyBar==null||a.vault!=pendingAccount||!a.chat.equals(pendingTarget))return;LinearLayout box=a.col();a.pad(box,8);box.setBackground(a.shape(a.CARD,16));box.addView(a.label(uploading?"Отправляем голосовое…":"Голосовое сохранено · отправка не завершена",13,a.TEXT),new LinearLayout.LayoutParams(-1,-2));if(!uploading){LinearLayout actions=a.row();actions.addView(a.button("Повторить отправку",true,this::uploadPending),new LinearLayout.LayoutParams(0,a.dp(44),1));actions.addView(icon("close","Удалить неотправленную запись",this::discard),new LinearLayout.LayoutParams(a.dp(44),a.dp(44)));box.addView(actions);}a.replyBar.removeAllViews();a.replyBar.addView(box,new LinearLayout.LayoutParams(-1,-2));}
 static class Wave extends View{
  final float[] values=new float[40];final Paint p=new Paint(3);Wave(android.content.Context c){super(c);}
  static float normalized(int amplitude){if(amplitude<=0)return 0;double db=20*Math.log10(Math.min(32767,amplitude)/32767.0);return (float)Math.pow(Math.max(0,Math.min(1,(db+60)/60)),1.7);}
  void level(int amplitude){System.arraycopy(values,1,values,0,values.length-1);values[values.length-1]=normalized(amplitude);invalidate();}
  protected void onDraw(Canvas c){float gap=getWidth()/40f;p.setStrokeWidth(Math.max(2,gap*.52f));p.setStrokeCap(Paint.Cap.ROUND);for(int i=0;i<40;i++){float level=values[i],h=Math.max(getResources().getDisplayMetrics().density*2,level*getHeight()*.9f);p.setColor(Color.rgb((int)(55+90*level),(int)(130+100*level),240));c.drawLine(i*gap+gap/2,(getHeight()-h)/2,i*gap+gap/2,(getHeight()+h)/2,p);}}
 }
}
