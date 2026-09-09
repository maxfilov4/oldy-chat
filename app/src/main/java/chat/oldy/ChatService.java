package chat.oldy;
import android.app.*;
import android.content.*;
import android.os.*;
import android.net.*;
import org.json.*;
import java.util.concurrent.*;
public class ChatService extends Service {
 static volatile ChatService instance;static volatile String state="Нет подключения";static volatile String visibleChat="";
 volatile boolean running;Thread incoming,outgoing;Vault vault;Api api;Rtc rtc;PowerManager.WakeLock wake;ConnectivityManager.NetworkCallback callback;
 final ExecutorService signals=Executors.newFixedThreadPool(3);static Vault shared;
 static synchronized Vault vault(Context c)throws Exception{if(shared==null)shared=new Vault(c.getApplicationContext());return shared;}
 static void changed(Context c){c.sendBroadcast(new Intent("chat.oldy.CHANGED").setPackage(c.getPackageName()));}
 public void onCreate(){super.onCreate();instance=this;api=new Api(this);try{vault=vault(this);}catch(Exception e){stopSelf();return;}
  NotificationManager nm=getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("connection","Фоновая доставка",NotificationManager.IMPORTANCE_LOW));
  callForeground(false,"");
  wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"oldy:delivery");wake.setReferenceCounted(false);
  rtc=new Rtc(this,vault,(peer,body)->signal(peer,body));
  callback=new ConnectivityManager.NetworkCallback(){public void onAvailable(Network n){Api.live="";changed(ChatService.this);}};
  getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(callback);
  running=true;incoming=new Thread(this::receive,"oldy-in");outgoing=new Thread(this::send,"oldy-out");incoming.start();outgoing.start();
 }
 void callForeground(boolean calling,String peer){
  PendingIntent open=PendingIntent.getActivity(this,1,new Intent(this,calling?CallActivity.class:MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
  Notification.Builder b=new Notification.Builder(this,"connection").setSmallIcon(R.drawable.ic_launcher).setContentTitle(calling?"Звонок · @"+peer:"OldЫ Chat").setContentText(calling?"Микрофон используется для звонка":"Ожидаем сообщения").setContentIntent(open).setOngoing(true);
  LiveCall.Session call=LiveCall.current;if(calling&&call!=null){PendingIntent end=PendingIntent.getBroadcast(this,412,new Intent(this,CallActionReceiver.class).putExtra("sid",call.sid),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);b.addAction(new Notification.Action.Builder(null,"Завершить",end).build());}
  if(Build.VERSION.SDK_INT>=34)startForeground(1,b.build(),android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING|(calling?android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE:0));
  else if(Build.VERSION.SDK_INT>=30)startForeground(1,b.build(),calling?android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE:0);
  else startForeground(1,b.build());
 }
 public int onStartCommand(Intent i,int f,int id){return START_STICKY;}
 void pause(long n){try{Thread.sleep(n);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
 void signal(String peer,JSONObject body){if(!running||signals.isShutdown())return;signals.execute(()->{try{JSONObject user=api.call("/user/"+peer,null,vault.token());vault.pin(user);JSONObject payload=new JSONObject(body.toString()).put("kind","signal");JSONObject envelope=Crypto.encrypt(vault.nick(),peer,Payload.wire(payload),java.util.UUID.randomUUID().toString(),System.currentTimeMillis(),vault.identity(),user).put("action","signal");if(!payload.optString("room").isEmpty())envelope.put("room",payload.optString("room")).put("action","signal");api.call("/send",envelope,vault.token());}catch(Exception e){if(body.optString("op").equals("call_offer")){LiveCall.Session call=LiveCall.current;if(call!=null&&call.sid.equals(body.optString("sid")))call.end("Собеседник сейчас недоступен",false);}Rtc.status.put(body.optString("mid"),"Не удалось связаться. Откройте чат на обоих телефонах.");changed(this);}});}
 void receive(){boolean checked=false;while(running){try{
   if(vault.token().isEmpty()){pause(3000);continue;}
   if(!checked||Api.live.isEmpty()){api.check();checked=true;state="Подключено";changed(this);}
   // Keeps a user-enabled messaging connection alive during screen-off. Doze exemption is offered in settings.
   wake.acquire(60000);
   JSONArray a=api.call("/poll",null,vault.token()).getJSONArray("messages");if(!running)break;state="Подключено";
   for(int i=0;i<a.length();i++){
    JSONObject e=a.getJSONObject(i);String from=e.getString("from");
    try{
     JSONObject peer=api.call("/user/"+from,null,vault.token());vault.pin(peer);
     JSONObject payload=Payload.parse(Crypto.decrypt(e,vault.identity(),peer));String room=payload.optString("room");
     if(e.has("room")&&!e.optString("room").equals(room))throw new java.security.GeneralSecurityException("Route mismatch");
     if(!room.isEmpty())vault.putRoom(api.call("/room/"+room,null,vault.token()));
     if(payload.optString("kind").equals("signal")){
      if(!room.isEmpty()){JSONObject r=vault.room(room);boolean member=false;JSONArray members=r.getJSONArray("members");for(int j=0;j<members.length();j++)if(from.equals(members.optString(j)))member=true;if(!member)throw new java.security.GeneralSecurityException("Sender left room");}
      if(payload.optString("op").startsWith("call_")){if(room.isEmpty()&&!vault.isBlocked(from))LiveCall.receive(this,from,payload);}else if(!vault.isBlocked(from)||!room.isEmpty())rtc.accept(from,payload);
     }else{
      boolean fresh=!vault.has(e.getString("id"));vault.receiveDecoded(e,peer,payload);JSONObject m=vault.message(e.getString("id"));
      if(running&&fresh&&m!=null&&!m.optString("kind").equals("control")&&!visibleChat.equals(m.optString("peer")))Notices.show(this,m,peer);
     }
    }catch(Api.Failure f){if(f.status!=403&&f.status!=404)throw f;}
    catch(java.io.IOException io){throw io;}
    catch(Exception rejected){/* Invalid or no-longer-authorized envelopes do not stop delivery of others. */}
    api.call("/ack",new JSONObject().put("from",from).put("id",e.getString("id")),vault.token());changed(this);
   }
  }catch(Exception e){checked=false;state=e instanceof Api.Failure&&((Api.Failure)e).status==401?"Нужно войти снова":"Нет подключения";changed(this);pause(3000);}finally{if(wake.isHeld())wake.release();}}
 }
 void sync()throws Exception{
  try{api.call("/capabilities",new JSONObject().put("protocol",4),vault.token());long revision=vault.roomRevision();vault.syncRooms(api.call("/rooms",null,vault.token()).getJSONArray("rooms"),revision);vault.profile(api.call("/me",null,vault.token()));vault.blocks(api.call("/blocks",null,vault.token()).getJSONArray("blocked"));deletionSync();cloudSync();JSONObject rooms=vault.copy().getJSONObject("rooms");java.util.Iterator<String> roomIds=rooms.keys();while(roomIds.hasNext()){String rid=roomIds.next();try{ChannelHistory.syncRoom(api,vault,rid,4);}catch(Api.Failure e){if(e.status!=403&&e.status!=404)throw e;}}}catch(Api.Failure e){if(e.status!=404)throw e;}
 }
 void send(){long lastSync=0;while(running){try{
   if(vault.token().isEmpty()){pause(3000);continue;}
   if(System.currentTimeMillis()-lastSync>10000){sync();lastSync=System.currentTimeMillis();changed(this);}
   JSONArray a=vault.copy().getJSONArray("messages");int archived=0,channelArchived=0;
   for(int i=0;i<a.length()&&running;i++){JSONObject m=a.getJSONObject(i);if(!vault.has(m.optString("id")))continue;if(m.optBoolean("out")&&!m.optBoolean("registered")&&!m.optString("kind").equals("control")){try{register(api,vault,m);vault.markRegistered(m.getString("id"));}catch(Api.Failure e){if(e.status==410){deletionSync();continue;}if(e.status!=404)continue;}}if(!m.optBoolean("channel_saved")&&ChannelHistory.belongs(vault,m)&&channelArchived++<20){try{ChannelHistory.publish(api,vault,m);}catch(Exception retryLater){}}if(!m.optBoolean("cloud")&&!m.optString("status").equals("pending")&&archived++<10){try{api.call("/history/store",new JSONObject().put("envelope",vault.archiveEnvelope(m)),vault.token());vault.cloudSaved(m.getString("id"));}catch(Exception ignored){}}if(!m.optString("status").equals("pending"))continue;
    JSONObject envelopes=m.optJSONObject("envelopes");if(envelopes==null){envelopes=new JSONObject();if(m.has("envelope"))envelopes.put(m.getString("peer"),m.getJSONObject("envelope"));}
    java.util.Iterator<String> it=envelopes.keys();while(it.hasNext()&&running){String target=it.next();try{
     if(Conversation.community(m.optString("peer"))){JSONObject room=api.call("/room/"+Conversation.room(m.getString("peer")),null,vault.token());boolean found=false;JSONArray members=room.getJSONArray("members");for(int k=0;k<members.length();k++)if(members.getString(k).equals(target))found=true;if(!found){vault.deliveredTo(m.getString("id"),target);continue;}}
     JSONObject peer=api.call("/user/"+target,null,vault.token());vault.pin(peer);JSONObject r=api.call("/send",envelopes.getJSONObject(target),vault.token());if(r.optBoolean("delivered")){vault.deliveredTo(m.getString("id"),target);changed(this);}else if(r.optBoolean("stored")){vault.storedTo(m.getString("id"),target);changed(this);}
    }catch(Exception ignored){}}
   }
  }catch(Exception ignored){}pause(2500);}}

 void deletionSync()throws Exception{try{JSONArray events=api.call("/deletions?after="+vault.deletionCursor(),null,vault.token()).getJSONArray("items");for(int i=0;i<events.length();i++)vault.deletion(events.getJSONObject(i));}catch(Api.Failure e){if(e.status!=404)throw e;}}
 static void register(Api api,Vault vault,JSONObject m)throws Exception{if(m==null||!m.optBoolean("out")||m.optString("kind").equals("control"))return;String peer=m.optString("peer");api.call("/posts/register",new JSONObject().put("mid",m.getString("id")).put("room",Conversation.community(peer)?Conversation.room(peer):"").put("thread",Conversation.post(peer)).put("video",m.optString("cloud_video",m.optString("cloud_blob"))),vault.token());}
 void cloudSync()throws Exception{
  JSONObject history=api.call("/history?after="+vault.historyCursor(),null,vault.token());JSONArray items=history.getJSONArray("items");
  for(int i=0;i<items.length();i++){JSONObject item=items.getJSONObject(i),e=item.getJSONObject("envelope");String from=e.getString("from");try{
   if(from.equals(vault.nick())){if(!item.optBoolean("verified_snapshot")){vault.historyCursor(item.getLong("seq"));continue;}vault.restoreSnapshot(e);JSONObject m=vault.message(e.getString("id"));if(m!=null&&!Conversation.community(m.optString("peer"))&&!m.optString("peer").equals(vault.nick()))vault.pin(api.call("/user/"+m.optString("peer"),null,vault.token()));}
   else if(item.optBoolean("delivered")){JSONObject peer=api.call("/user/"+from,null,vault.token());vault.pin(peer);JSONObject payload=Payload.parse(Crypto.decrypt(e,vault.identity(),peer));if(!payload.optString("room").isEmpty())vault.putRoom(api.call("/room/"+payload.optString("room"),null,vault.token()));if(!payload.optString("kind").equals("signal"))vault.receiveDecoded(e,peer,payload);}
  }catch(Api.Failure f){if(f.status!=403&&f.status!=404)throw f;}catch(java.io.IOException eio){throw eio;}catch(Exception rejected){}
   vault.historyCursor(item.getLong("seq"));
  }
  JSONArray messages=vault.copy().getJSONArray("messages"),ids=new JSONArray();for(int i=0;i<messages.length()&&ids.length()<100;i++){JSONObject m=messages.getJSONObject(i);if(m.optBoolean("out")&&m.optString("status").equals("stored"))ids.put(m.optString("id"));}if(ids.length()>0){JSONArray delivered=api.call("/receipts",new JSONObject().put("ids",ids),vault.token()).getJSONArray("delivered");for(int i=0;i<delivered.length();i++)vault.delivered(delivered.getString(i));}
 }
 public void onDestroy(){LiveCall.Session call=LiveCall.current;if(call!=null&&call.vault==vault)call.end("Звонок завершён",false);running=false;if(incoming!=null)incoming.interrupt();if(outgoing!=null)outgoing.interrupt();if(wake!=null&&wake.isHeld())wake.release();if(callback!=null)getSystemService(ConnectivityManager.class).unregisterNetworkCallback(callback);signals.shutdownNow();if(rtc!=null)rtc.close();if(instance==this)instance=null;state="Нет подключения";super.onDestroy();}
 public IBinder onBind(Intent i){return null;}
}
