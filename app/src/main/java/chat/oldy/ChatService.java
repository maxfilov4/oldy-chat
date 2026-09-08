package chat.oldy;

import android.app.*;
import android.content.*;
import android.os.*;
import org.json.*;

public class ChatService extends Service {
 static volatile ChatService instance;
 static volatile String state="Не подключён";
 volatile boolean running;
 Thread incoming,outgoing;
 Vault vault;Api api;
 static Vault shared;
 static synchronized Vault vault(Context c)throws Exception{if(shared==null)shared=new Vault(c.getApplicationContext());return shared;}
 static void changed(Context c){c.sendBroadcast(new Intent("chat.oldy.CHANGED").setPackage(c.getPackageName()));}
 public void onCreate(){super.onCreate();instance=this;api=new Api(this);try{vault=vault(this);}catch(Exception e){stopSelf();return;}
  NotificationManager nm=getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("connection","Доставка сообщений",NotificationManager.IMPORTANCE_LOW));
  PendingIntent p=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
  Notification n=new Notification.Builder(this,"connection").setSmallIcon(R.drawable.ic_launcher).setContentTitle("OldЫ Chat").setContentText("Приём сообщений включён").setContentIntent(p).setOngoing(true).build();startForeground(1,n);
  running=true;incoming=new Thread(this::receive,"oldy-in");outgoing=new Thread(this::send,"oldy-out");incoming.start();outgoing.start();
 }
 public int onStartCommand(Intent i,int f,int id){return START_STICKY;}
 void pause(long millis){try{Thread.sleep(millis);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
 void receive(){while(running){try{
   if(vault.token().isEmpty()||api.pin().isEmpty()){pause(3000);continue;}
   JSONArray a=api.call("/poll",null,vault.token()).getJSONArray("messages");
   if(!state.equals("Подключён")){state="Подключён";changed(this);}
   for(int i=0;i<a.length();i++){JSONObject e=a.getJSONObject(i);String from=e.getString("from");JSONObject peer=api.call("/user/"+from,null,vault.token());vault.pin(peer);vault.receive(e,peer);api.call("/ack",new JSONObject().put("from",from).put("id",e.getString("id")),vault.token());changed(this);}
  }catch(Exception e){state=e.getMessage()==null?"Нет соединения":e.getMessage();changed(this);pause(5000);}}
 }
 void send(){while(running){try{
   if(vault.token().isEmpty()){pause(3000);continue;}
   JSONArray a=vault.copy().getJSONArray("messages");
   for(int i=0;i<a.length()&&running;i++){JSONObject m=a.getJSONObject(i);if(!m.optString("status").equals("pending"))continue;
    try{JSONObject peer=api.call("/user/"+m.getString("peer"),null,vault.token());vault.pin(peer);JSONObject r=api.call("/send",m.getJSONObject("envelope"),vault.token());if(r.optBoolean("delivered")){vault.delivered(m.getString("id"));changed(this);}}catch(Exception e){/* Keep encrypted outbox for retry. */}
   }
  }catch(Exception e){state="Не удалось прочитать очередь";changed(this);}pause(4000);}}
 public void onDestroy(){running=false;if(incoming!=null)incoming.interrupt();if(outgoing!=null)outgoing.interrupt();instance=null;state="Приём остановлен";super.onDestroy();}
 public IBinder onBind(Intent i){return null;}
}
