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
  PendingIntent p=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
  startForeground(1,new Notification.Builder(this,"connection").setSmallIcon(R.drawable.ic_launcher).setContentTitle("OldЫ Chat").setContentText("Ожидаем сообщения").setContentIntent(p).setOngoing(true).build());
  wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"oldy:delivery");wake.setReferenceCounted(false);
  rtc=new Rtc(this,vault,(peer,body)->signal(peer,body));
  callback=new ConnectivityManager.NetworkCallback(){public void onAvailable(Network n){Api.live="";changed(ChatService.this);}};
  getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(callback);
  running=true;incoming=new Thread(this::receive,"oldy-in");outgoing=new Thread(this::send,"oldy-out");incoming.start();outgoing.start();
 }
 public int onStartCommand(Intent i,int f,int id){return START_STICKY;}
 void pause(long n){try{Thread.sleep(n);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
 void signal(String peer,JSONObject body){signals.execute(()->{try{JSONObject user=api.call("/user/"+peer,null,vault.token());vault.pin(user);JSONObject payload=new JSONObject(body.toString()).put("kind","signal");JSONObject envelope=Crypto.encrypt(vault.nick(),peer,Payload.wire(payload),java.util.UUID.randomUUID().toString(),System.currentTimeMillis(),vault.identity(),user);api.call("/send",envelope,vault.token());}catch(Exception e){Rtc.status.put(body.optString("mid"),"Не удалось связаться. Откройте чат на обоих телефонах.");changed(this);}});}
 void receive(){boolean checked=false;while(running){try{
   if(vault.token().isEmpty()){pause(3000);continue;}
   if(!checked||Api.live.isEmpty()){api.check();checked=true;state="Подключено";changed(this);}
   // Keeps a user-enabled messaging connection alive during screen-off. Doze exemption is offered in settings.
   wake.acquire(60000);
   JSONArray a=api.call("/poll",null,vault.token()).getJSONArray("messages");state="Подключено";
   for(int i=0;i<a.length();i++){
    JSONObject e=a.getJSONObject(i);String from=e.getString("from");JSONObject peer=api.call("/user/"+from,null,vault.token());vault.pin(peer);
    JSONObject payload=Payload.parse(Crypto.decrypt(e,vault.identity(),peer));
    if(payload.optString("kind").equals("signal")){rtc.accept(from,payload);}else{
     String room=payload.optString("room");if(!room.isEmpty())vault.putRoom(api.call("/room/"+room,null,vault.token()));
     boolean fresh=!vault.has(e.getString("id"));vault.receiveDecoded(e,peer,payload);JSONObject m=vault.message(e.getString("id"));
     if(fresh&&!visibleChat.equals(m.optString("peer")))Notices.show(this,m,peer);
    }
    api.call("/ack",new JSONObject().put("from",from).put("id",e.getString("id")),vault.token());changed(this);
   }
  }catch(Exception e){checked=false;state=e instanceof Api.Failure&&((Api.Failure)e).status==401?"Нужно войти снова":"Нет подключения";changed(this);pause(3000);}finally{if(wake.isHeld())wake.release();}}
 }
 void sync()throws Exception{
  try{vault.rooms(api.call("/rooms",null,vault.token()).getJSONArray("rooms"));vault.profile(api.call("/me",null,vault.token()));}catch(Api.Failure e){if(e.status!=404)throw e;}
 }
 void send(){long lastSync=0;while(running){try{
   if(vault.token().isEmpty()){pause(3000);continue;}
   if(System.currentTimeMillis()-lastSync>30000){sync();lastSync=System.currentTimeMillis();changed(this);}
   JSONArray a=vault.copy().getJSONArray("messages");
   for(int i=0;i<a.length()&&running;i++){JSONObject m=a.getJSONObject(i);if(!m.optString("status").equals("pending"))continue;
    JSONObject envelopes=m.optJSONObject("envelopes");if(envelopes==null){envelopes=new JSONObject();if(m.has("envelope"))envelopes.put(m.getString("peer"),m.getJSONObject("envelope"));}
    java.util.Iterator<String> it=envelopes.keys();while(it.hasNext()&&running){String target=it.next();try{
     if(m.optString("peer").startsWith("room:")){JSONObject room=api.call("/room/"+m.getString("peer").substring(5),null,vault.token());boolean found=false;JSONArray members=room.getJSONArray("members");for(int k=0;k<members.length();k++)if(members.getString(k).equals(target))found=true;if(!found){vault.deliveredTo(m.getString("id"),target);continue;}}
     JSONObject peer=api.call("/user/"+target,null,vault.token());vault.pin(peer);JSONObject r=api.call("/send",envelopes.getJSONObject(target),vault.token());if(r.optBoolean("delivered")){vault.deliveredTo(m.getString("id"),target);changed(this);}
    }catch(Exception ignored){}}
   }
  }catch(Exception ignored){}pause(2500);}}
 public void onDestroy(){running=false;if(incoming!=null)incoming.interrupt();if(outgoing!=null)outgoing.interrupt();if(wake!=null&&wake.isHeld())wake.release();if(callback!=null)getSystemService(ConnectivityManager.class).unregisterNetworkCallback(callback);signals.shutdownNow();if(rtc!=null)rtc.close();instance=null;state="Нет подключения";super.onDestroy();}
 public IBinder onBind(Intent i){return null;}
}
