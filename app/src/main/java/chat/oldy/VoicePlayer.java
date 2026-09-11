package chat.oldy;

import android.media.*;
import org.json.JSONObject;
import java.io.IOException;

/** One audio player per chat screen; message redraws never interrupt playback. */
final class VoicePlayer {
 final MainActivity a;MediaPlayer player;String id="";boolean loading,ready;int generation;String failure="";
 VoicePlayer(MainActivity a){this.a=a;}
 void toggle(JSONObject message){String next=message.optString("id");
  if(next.equals(id)){if(loading)return;if(player!=null&&ready){try{if(player.isPlaying())player.pause();else player.start();return;}catch(Exception ignored){}}}
  release();id=next;loading=true;failure="";int serial=generation;Vault account=a.vault;
  a.work.execute(()->{try{JSONObject m=account.message(next);if(m==null)throw new IOException(I18n.t("Сообщение удалено"));
    if(!m.has("local")||!MediaFiles.path(a,m.optString("local")).isFile()){
     if(m.optString("cloud_blob").isEmpty())throw new IOException(I18n.t("Запись недоступна. Открой вложение через меню сообщения."));
     CloudMedia.download(a,account,m);m=account.message(next);
    }
    final byte[] bytes=MediaFiles.bytes(a,m.getString("local"));if(bytes.length<100)throw new IOException(I18n.t("Запись не содержит аудио"));
    a.runOnUiThread(()->{if(serial!=generation||account!=a.vault)return;try{
      player=new MediaPlayer();MediaPlayer current=player;
      current.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
      current.setDataSource(new MediaDataSource(){public long getSize(){return bytes.length;}public int readAt(long p,byte[] out,int off,int n){if(p<0)return -1;if(p>=bytes.length)return -1;int count=Math.min(n,bytes.length-(int)p);System.arraycopy(bytes,(int)p,out,off,count);return count;}public void close(){}});
      current.setOnPreparedListener(p->{if(serial!=generation)return;loading=false;ready=true;p.start();});
      current.setOnCompletionListener(p->{if(serial==generation)p.seekTo(0);});
      current.setOnErrorListener((p,w,e)->{if(serial==generation)fail(I18n.t("Не удалось воспроизвести запись. Нажми, чтобы повторить."));return true;});
      current.prepareAsync();
     }catch(Exception e){fail(I18n.t("Не удалось открыть голосовое сообщение"));}});
   }catch(Exception e){a.runOnUiThread(()->{if(serial==generation)fail(Api.message(e));});}});
 }
 void fail(String message){loading=false;ready=false;failure=message;if(player!=null){player.release();player=null;}a.error(message);}
 boolean playing(String mid){try{return id.equals(mid)&&ready&&player!=null&&player.isPlaying();}catch(Exception ignored){return false;}}
 int position(String mid){try{return id.equals(mid)&&ready&&player!=null?player.getCurrentPosition():0;}catch(Exception ignored){return 0;}}
 int duration(String mid){try{return id.equals(mid)&&ready&&player!=null?player.getDuration():0;}catch(Exception ignored){return 0;}}
 void seek(String mid,float part){try{if(id.equals(mid)&&ready&&player!=null)player.seekTo((int)(Math.max(0,Math.min(1,part))*player.getDuration()));}catch(Exception ignored){}}
 void release(){generation++;loading=false;ready=false;if(player!=null){try{player.release();}catch(Exception ignored){}player=null;}id="";}
}
