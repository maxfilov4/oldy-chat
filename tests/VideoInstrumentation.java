package chat.oldy;
import android.app.*;import android.os.*;import android.content.*;import android.content.pm.ActivityInfo;import android.content.res.Configuration;import android.graphics.*;import android.net.Uri;import org.json.*;import java.io.*;import java.util.*;
/** Upload, authenticated streaming, real MP4 decoder, rotation and per-post comments. */
public class VideoInstrumentation extends Instrumentation {
 public void onCreate(Bundle b){start();}
 void check(boolean value,String why)throws Exception{if(!value)throw new Exception(why);}
 void shot(String name)throws Exception{Thread.sleep(400);Bitmap b=getUiAutomation().takeScreenshot();File dir=new File(getTargetContext().getExternalFilesDir(null),"review");dir.mkdirs();try(FileOutputStream out=new FileOutputStream(new File(dir,name+".png"))){b.compress(Bitmap.CompressFormat.PNG,100,out);}}
 public void onStart(){Bundle result=new Bundle();VideoActivity[] video={null};try{
  MainActivity a=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Vault v=ChatService.vault(getTargetContext());Api api=new Api(getTargetContext());
  JSONObject room=api.call("/room/create",new JSONObject().put("kind","channel").put("title","Старый Геймер · обзоры").put("handle","oldy_video_test").put("public",true).put("members",new JSONArray()),v.token());v.putRoom(room);String rid=room.getString("id");
  ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream in=getContext().getAssets().open("player-test.mp4")){byte[] buffer=new byte[16000];int n;while((n=in.read(buffer))!=-1)bytes.write(buffer,0,n);}byte[] file=bytes.toByteArray();
  JSONObject item=api.call("/videos/start",new JSONObject().put("room",rid).put("name","Обзор.mp4").put("size",file.length),v.token());String id=item.getString("id");
  for(int at=0;at<file.length;at+=65536){byte[] chunk=Arrays.copyOfRange(file,at,Math.min(file.length,at+65536));check(CreatorVideo.chunk(api,v.token(),id,at,chunk,chunk.length)==at+chunk.length,"Chunk offset mismatch");}
  api.call("/videos/finish",new JSONObject().put("id",id),v.token());
  try(VideoActivity.Source source=new VideoActivity.Source(getTargetContext(),id,file.length)){byte[] got=new byte[500];check(source.readAt(157,got,0,500)==500&&Arrays.equals(got,Arrays.copyOfRange(file,157,657)),"Authenticated range differs");}
  File local=new File(getTargetContext().getCacheDir(),"player-test.mp4");try(FileOutputStream out=new FileOutputStream(local)){out.write(file);}String thumb=CreatorVideo.thumbnail(a,Uri.fromFile(local));check(!thumb.isEmpty(),"MP4 thumbnail missing");
  String mid=v.queuePayload("room:"+rid,new JSONObject().put("kind","file").put("mime","video/mp4").put("cloud_video",id).put("size",file.length).put("sha256",Crypto.hex(java.security.MessageDigest.getInstance("SHA-256").digest(file))).put("thumb",thumb).put("text","Добрый день, подписчики! 🎮\nНовый видеообзор уже здесь. Смотрите в полном экране, а впечатлениями делитесь в комментариях."),null);
  String link=api.call("/threads",new JSONObject().put("room",rid).put("post",mid),v.token()).getString("link");check(link.equals("oldy://comments/"+rid+"/"+mid),"Wrong unique comments link");
  runOnMainSync(()->{Notices.prefs(a).edit().putString("theme","light").commit();a.openChat("room:"+rid);});shot("10-video-post");
  video[0]=(VideoActivity)startActivitySync(new Intent(getTargetContext(),VideoActivity.class).putExtra("video",id).putExtra("size",(long)file.length).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  long deadline=System.currentTimeMillis()+25000;while(!video[0].prepared&&System.currentTimeMillis()<deadline)Thread.sleep(200);check(video[0].prepared,"Real MP4 did not prepare: "+video[0].status.getText());Thread.sleep(600);check(video[0].player.getCurrentPosition()>100,"MP4 playback did not advance");shot("11-player-portrait");
  runOnMainSync(()->{video[0].player.seekTo(4000L,android.media.MediaPlayer.SEEK_CLOSEST);video[0].setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);});Thread.sleep(1300);check(video[0].player.getCurrentPosition()>=3500,"Seeking did not advance");check(video[0].getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE,"Landscape did not apply");shot("12-player-landscape");
  runOnMainSync(()->video[0].finish());Thread.sleep(500);runOnMainSync(()->a.openComments(rid,mid));deadline=System.currentTimeMillis()+10000;while(!a.chat.equals("thread:"+rid+":"+mid)&&System.currentTimeMillis()<deadline)Thread.sleep(150);check(a.chat.equals("thread:"+rid+":"+mid),"Unique comments page did not open");
  v.queue("thread:"+rid+":"+mid,"Какой момент обзора понравился больше всего?");runOnMainSync(()->a.renderMessages());shot("13-comments");
  result.putString("stream","OLDY_VIDEO_PASS: chunk upload, authenticated ranges, thumbnail, actual H.264/AAC playback, seeking, portrait/landscape, unique comment page\n");finish(-1,result);
 }catch(Throwable e){result.putString("stream","OLDY_VIDEO_FAIL: "+android.util.Log.getStackTraceString(e));finish(0,result);}}
}
