package chat.oldy;
import android.app.*;
import android.os.*;
import android.content.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class FeatureInstrumentation extends Instrumentation {
 void check(boolean ok,String why)throws Exception{if(!ok)throw new Exception(why);}
 public void onCreate(Bundle b){start();}
 Context isolated(String n){File dir=new File(getTargetContext().getFilesDir(),n);dir.mkdirs();return new ContextWrapper(getTargetContext()) {public File getFilesDir(){return dir;}};}
 public void onStart(){Bundle out=new Bundle();Rtc[] rtc=new Rtc[2];try{
  Context ac=isolated("rtc-alice"),bc=isolated("rtc-bob");Vault a=new Vault(ac),b=new Vault(bc);
  JSONObject ai=a.identity(),bi=b.identity();JSONObject au=Crypto.publicPart(ai).put("nick","sender").put("name","Отправитель"),bu=Crypto.publicPart(bi).put("nick","receiver").put("name","Получатель");
  a.account(new JSONObject().put("user",au).put("token","local-test-a"));b.account(new JSONObject().put("user",bu).put("token","local-test-b"));a.pin(bu);b.pin(au);
  byte[] file=Crypto.random(350123);String key=UUID.randomUUID().toString(),sha;
  try(MediaFiles.Writer w=new MediaFiles.Writer(ac,key)){for(int i=0;i<file.length;i+=16000)w.put(Arrays.copyOfRange(file,i,Math.min(file.length,i+16000)));sha=w.finish();}
  check(Arrays.equals(file,MediaFiles.bytes(ac,key)),"Encrypted media file roundtrip");
  byte[] disk=java.nio.file.Files.readAllBytes(MediaFiles.path(ac,key).toPath());check(!Arrays.equals(disk,file),"Media was plaintext on disk");
  JSONObject body=new JSONObject().put("kind","file").put("mime","video/mp4").put("name","Тест.mp4").put("size",file.length).put("sha256",sha);
  String mid=a.queuePayload("receiver",body,key);JSONObject m=a.message(mid);b.receive(m.getJSONObject("envelopes").getJSONObject("receiver"),au);
  rtc[0]=new Rtc(ac,a,(peer,p)->{JSONObject e=Crypto.encrypt("sender","receiver",Payload.wire(p.put("kind","signal")),UUID.randomUUID().toString(),System.currentTimeMillis(),ai,bi);rtc[1].accept("sender",Payload.parse(Crypto.decrypt(e,bi,ai)));},Collections.emptyList());
  rtc[1]=new Rtc(bc,b,(peer,p)->{JSONObject e=Crypto.encrypt("receiver","sender",Payload.wire(p.put("kind","signal")),UUID.randomUUID().toString(),System.currentTimeMillis(),bi,ai);rtc[0].accept("receiver",Payload.parse(Crypto.decrypt(e,ai,bi)));},Collections.emptyList());
  rtc[1].request(b.message(mid));long deadline=System.currentTimeMillis()+65000;while(!b.message(mid).has("local")&&System.currentTimeMillis()<deadline)Thread.sleep(200);
  check(b.message(mid).has("local"),"Direct WebRTC file not received: "+Rtc.status.get(mid));check(Arrays.equals(file,MediaFiles.bytes(bc,b.message(mid).getString("local"))),"Direct WebRTC bytes differ");
  String corruptKey=UUID.randomUUID().toString();File corrupt=MediaFiles.path(ac,corruptKey);disk[disk.length-1]^=1;java.nio.file.Files.write(corrupt.toPath(),disk);boolean rejected=false;try{MediaFiles.bytes(ac,corruptKey);}catch(Exception e){rejected=true;}check(rejected,"Corrupted media accepted");
  String rid=UUID.randomUUID().toString();JSONObject room=new JSONObject().put("id",rid).put("title","Канал").put("kind","channel").put("owner","sender").put("members",new JSONArray().put("sender").put("receiver"));a.putRoom(room);b.putRoom(room);
  rejected=false;try{b.queue("room:"+rid,"Взлом канала");}catch(Exception e){rejected=true;}check(rejected,"Channel subscriber can publish");
  String gid=a.queuePayload("room:"+rid,new JSONObject().put("kind","sticker").put("sticker",3).put("text","Oldy Pop"),null);b.receive(a.message(gid).getJSONObject("envelopes").getJSONObject("receiver"),au);check(b.message(gid).getString("peer").equals("room:"+rid),"Group message was misrouted");
  Notices.prefs(getTargetContext()).edit().putBoolean("notifications",true).putBoolean("sound",true).putBoolean("preview",false).commit();Notices.show(getTargetContext(),b.message(gid),au);NotificationManager nm=getTargetContext().getSystemService(NotificationManager.class);check(nm.getActiveNotifications().length>0,"No message notification");check(nm.getNotificationChannel("messages_2_sv").getSound()!=null,"Notification has no sound");
  out.putString("stream","OLDY_FEATURES_PASS: authenticated native direct WebRTC transfer, encrypted media, corrupted media rejection, channel publishing permissions, group routing, notifications and custom sound\n");finish(-1,out);
 }catch(Throwable e){out.putString("stream","OLDY_FEATURES_FAIL: "+e+"\n"+android.util.Log.getStackTraceString(e));finish(0,out);}finally{for(Rtc r:rtc)if(r!=null)r.close();}}
}
