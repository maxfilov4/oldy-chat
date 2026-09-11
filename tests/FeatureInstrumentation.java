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
  JSONObject ai=a.identity(),bi=b.identity();JSONObject au=Crypto.publicPart(ai).put("nick","sender").put("protocol",3).put("name","Отправитель"),bu=Crypto.publicPart(bi).put("nick","receiver").put("protocol",3).put("name","Получатель");
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
  String reaction=b.queuePayload("room:"+rid,new JSONObject().put("kind","control").put("op","reaction").put("mid",gid).put("emoji","🔥"),null);a.receive(b.message(reaction).getJSONObject("envelopes").getJSONObject("sender"),bu);check(a.reactions(gid).getJSONObject("receiver").getString("emoji").equals("🔥"),"Reaction did not arrive");
  rejected=false;try{b.queuePayload("room:"+rid,new JSONObject().put("kind","control").put("op","pin").put("mid",gid),null);}catch(Exception e){rejected=true;}check(rejected,"Subscriber could pin");
  String pin=a.queuePayload("room:"+rid,new JSONObject().put("kind","control").put("op","pin").put("mid",gid),null);b.receive(a.message(pin).getJSONObject("envelopes").getJSONObject("receiver"),au);check(b.pinned("room:"+rid).equals(gid),"Pin did not arrive");
  String groupFile=a.queuePayload("room:"+rid,body,key);b.receive(a.message(groupFile).getJSONObject("envelopes").getJSONObject("receiver"),au);rtc[1].request(b.message(groupFile));deadline=System.currentTimeMillis()+65000;while(!b.message(groupFile).has("local")&&System.currentTimeMillis()<deadline)Thread.sleep(200);check(b.message(groupFile).has("local"),"Channel media transfer failed: "+Rtc.status.get(groupFile));check(Arrays.equals(file,MediaFiles.bytes(bc,b.message(groupFile).getString("local"))),"Channel media differs");
  b.blocks(new JSONArray().put("sender"));rejected=false;try{b.queue("sender","blocked");}catch(Exception e){rejected=true;}check(rejected,"Blocked personal send accepted");
  String groupAfterBlock=a.queuePayload("room:"+rid,new JSONObject().put("kind","text").put("text","Still in channel"),null);b.receive(a.message(groupAfterBlock).getJSONObject("envelopes").getJSONObject("receiver"),au);check(b.has(groupAfterBlock),"Personal block incorrectly banned channel");b.blocks(new JSONArray());
  String comment=b.queuePayload("thread:"+rid+":"+gid,new JSONObject().put("kind","text").put("text","Отличный обзор!"),null);a.receive(b.message(comment).getJSONObject("envelopes").getJSONObject("sender"),bu);check(a.message(comment).getString("peer").equals("thread:"+rid+":"+gid),"Comment leaked into channel feed");
  String raw=Crypto.exportBackup(a.keyBackup(),"restore test password".toCharArray());Vault restored=new Vault(isolated("restored-device"));restored.restoreKeys(Crypto.importBackup(raw,"restore test password".toCharArray()),au);restored.account(new JSONObject().put("user",au).put("token","local-restore"));restored.putRoom(room);restored.restoreSnapshot(a.archiveEnvelope(a.message(gid)));check(restored.has(gid)&&restored.message(gid).getString("peer").equals("room:"+rid),"Encrypted cloud snapshot did not restore");restored.restoreSnapshot(a.archiveEnvelope(a.message(pin)));check(restored.pinned("room:"+rid).equals(gid),"Restored pin missing");
  long revision=a.roomRevision();String newRoomId=UUID.randomUUID().toString();JSONObject newlyCreated=new JSONObject(room.toString()).put("id",newRoomId);a.putRoom(newlyCreated);a.syncRooms(new JSONArray().put(room),revision);check(a.room(newRoomId)!=null,"Stale sync removed a newly created room");a.syncRooms(new JSONArray().put(room),a.roomRevision());check(a.room(newRoomId)==null,"Current membership removal was ignored");
  a.bookmark(gid);check(a.saved(gid),"Bookmark not saved");a.draft("room:"+rid,"A draft");check(a.draft("room:"+rid).equals("A draft"),"Draft not persisted");
  check(LinkCards.youtube("https://www.youtube.com/shorts/AbCdEf_1234?feature=share").equals("AbCdEf_1234"),"Shorts not recognized");check(LinkCards.youtube("https://youtube.com.evil.example/watch?v=AbCdEf_1234").isEmpty(),"Spoofed YouTube host accepted");
  Notices.prefs(getTargetContext()).edit().putBoolean("notifications",true).putBoolean("sound",true).putBoolean("preview",false).commit();Notices.show(getTargetContext(),b,b.message(gid),au);NotificationManager nm=getTargetContext().getSystemService(NotificationManager.class);deadline=System.currentTimeMillis()+4000;while(nm.getActiveNotifications().length==0&&System.currentTimeMillis()<deadline)Thread.sleep(50);check(nm.getActiveNotifications().length>0,"No message notification (enabled="+nm.areNotificationsEnabled()+")");check(nm.getNotificationChannel(Notices.channelId(getTargetContext())).getSound()!=null,"Notification has no sound");
  out.putString("stream","OLDY_FEATURES_PASS: authenticated native direct WebRTC transfer, encrypted media, corrupted media rejection, channel publishing permissions, group routing, notifications and custom sound\n");finish(-1,out);
 }catch(Throwable e){out.putString("stream","OLDY_FEATURES_FAIL: "+e+"\n"+android.util.Log.getStackTraceString(e));finish(0,out);}finally{for(Rtc r:rtc)if(r!=null)r.close();}}
}
