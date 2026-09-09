package chat.oldy;
import android.app.*;
import android.content.*;
import android.media.AudioManager;
import android.os.*;
import org.json.*;
import org.webrtc.*;
import java.util.*;
import java.util.concurrent.*;

/** Audio uses WebRTC DTLS-SRTP; signalling uses the existing authenticated E2E envelope. */
final class LiveCall {
 static volatile Session current;
 static final ConcurrentLinkedQueue<String> endedCalls=new ConcurrentLinkedQueue<>();
 static synchronized void dial(Context c,String peer){
  if(current!=null){c.startActivity(new Intent(c,CallActivity.class));return;}
  try{Vault v=ChatService.vault(c);if(peer.equals(v.nick())||!peer.matches("[a-z0-9_]{3,24}"))return;
   if(v.isBlocked(peer))throw new Exception(I18n.t("Собеседник заблокирован"));
   current=new Session(c,v,peer,UUID.randomUUID().toString(),false,"");
   c.startActivity(new Intent(c,CallActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  }catch(Exception e){note(c,Api.message(e));}
 }
 static synchronized void receive(ChatService service,String peer,JSONObject payload){
  if(service.vault!=ChatService.shared||!service.running)return;
  String op=payload.optString("op"),sid=payload.optString("sid");
  if(!sid.matches("[a-f0-9-]{36}")||endedCalls.contains(sid))return;
  Session s=current;
  if(op.equals("call_offer")){
   if(Math.abs(System.currentTimeMillis()-payload.optLong("sent_at"))>90000||payload.optString("sdp").length()>14000)return;
   if(s!=null){if(!s.sid.equals(sid))try{service.signal(peer,new JSONObject().put("op","call_busy").put("sid",sid));}catch(Exception ignored){}return;}
   s=new Session(service,service.vault,peer,sid,true,payload.optString("sdp"));current=s;s.incomingNotice();
   Session ringing=s;s.timer.schedule(()->{if(!ringing.accepted)ringing.end(I18n.t("Пропущенный звонок"),true);},45,TimeUnit.SECONDS);
  }else if(s!=null&&s.peer.equals(peer)&&s.sid.equals(sid)){
   Session call=s;if(op.equals("call_answer")&&!call.incoming)call.execute(()->call.answer(payload.optString("sdp")));
   if(op.equals("call_end")||op.equals("call_busy"))call.end(op.equals("call_busy")?I18n.t("Собеседник занят"):I18n.t("Звонок завершён"),false);
  }
 }
 static void note(Context c,String s){new Handler(Looper.getMainLooper()).post(()->android.widget.Toast.makeText(c,s,android.widget.Toast.LENGTH_LONG).show());}
 static final class Session {
  final Context c;final Vault vault;final String peer,sid,offer;final boolean incoming;
  final ExecutorService io=Executors.newSingleThreadExecutor();final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
  PeerConnectionFactory factory;PeerConnection pc;AudioSource audioSource;AudioTrack audio;
  volatile boolean accepted,connected,ended,muted,speaker,sent;volatile String state;volatile long connectedAt;
  Session(Context context,Vault v,String p,String id,boolean in,String sdp){c=context.getApplicationContext();vault=v;peer=p;sid=id;incoming=in;offer=sdp;state=in?I18n.t("Входящий звонок"):I18n.t("Вызываем…");}
  void execute(Runnable r){if(!ended)try{io.execute(()->{if(!ended)r.run();});}catch(RejectedExecutionException ignored){}}
  synchronized void start(){
   if(ended||accepted)return;
   try{ChatService service=ChatService.instance;if(service==null||!service.running||service.vault!=vault)throw new Exception(I18n.t("Откройте чат и повторите звонок"));
    service.callForeground(true,peer);accepted=true;c.getSystemService(NotificationManager.class).cancel(410);
   }catch(Exception e){end(I18n.t("Не удалось включить микрофон для звонка"),true);return;}
   execute(()->{try{
    JSONObject config=new Api(c).call("/call-config",new JSONObject().put("peer",peer),vault.token());if(ended)return;
    List<PeerConnection.IceServer> ice=new ArrayList<>();JSONArray servers=config.getJSONArray("servers");
    for(int i=0;i<servers.length();i++){JSONObject server=servers.getJSONObject(i);PeerConnection.IceServer.Builder b=PeerConnection.IceServer.builder(server.getString("url"));if(server.has("username"))b.setUsername(server.getString("username")).setPassword(server.getString("credential"));ice.add(b.createIceServer());}
    PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(c).createInitializationOptions());factory=PeerConnectionFactory.builder().createPeerConnectionFactory();
    PeerConnection.RTCConfiguration rtc=new PeerConnection.RTCConfiguration(ice);rtc.sdpSemantics=PeerConnection.SdpSemantics.UNIFIED_PLAN;if(config.optBoolean("relay_only"))rtc.iceTransportsType=PeerConnection.IceTransportsType.RELAY;
    pc=factory.createPeerConnection(rtc,new PeerConnection.Observer(){
     public void onSignalingChange(PeerConnection.SignalingState s){}
     public void onIceConnectionChange(PeerConnection.IceConnectionState s){
      if(ended)return;if(s==PeerConnection.IceConnectionState.CONNECTED||s==PeerConnection.IceConnectionState.COMPLETED){if(!connected){connectedAt=SystemClock.elapsedRealtime();connected=true;}state=I18n.t("На связи");ChatService.changed(c);}
      if(s==PeerConnection.IceConnectionState.DISCONNECTED){state=I18n.t("Восстанавливаем соединение…");try{timer.schedule(()->{if(!ended&&state.equals(I18n.t("Восстанавливаем соединение…")))end(I18n.t("Связь прервалась"),true);},20,TimeUnit.SECONDS);}catch(RejectedExecutionException ignored){}}
      if(s==PeerConnection.IceConnectionState.FAILED)end(I18n.t("Не удалось соединить звонок"),true);
     }
     public void onIceConnectionReceivingChange(boolean b){}
     public void onIceGatheringChange(PeerConnection.IceGatheringState s){if(s==PeerConnection.IceGatheringState.COMPLETE)execute(()->sendSdp());}
     public void onIceCandidate(IceCandidate i){}public void onIceCandidatesRemoved(IceCandidate[] a){}
     public void onAddStream(MediaStream stream){for(AudioTrack track:stream.audioTracks)track.setEnabled(true);}
     public void onAddTrack(RtpReceiver receiver,MediaStream[] streams){if(receiver.track() instanceof AudioTrack)((AudioTrack)receiver.track()).setEnabled(true);}
     public void onRemoveStream(MediaStream s){}public void onDataChannel(DataChannel d){d.close();}public void onRenegotiationNeeded(){}
    });
    if(pc==null)throw new Exception(I18n.t("Не удалось включить звонок"));audioSource=factory.createAudioSource(new MediaConstraints());audio=factory.createAudioTrack("oldy-mic",audioSource);audio.setEnabled(!muted);pc.addTrack(audio,Collections.singletonList("oldy-call"));
    AudioManager manager=c.getSystemService(AudioManager.class);manager.setMode(AudioManager.MODE_IN_COMMUNICATION);manager.setSpeakerphoneOn(speaker);
    if(incoming)pc.setRemoteDescription(observer(()->pc.createAnswer(description(),new MediaConstraints())),new SessionDescription(SessionDescription.Type.OFFER,offer));else pc.createOffer(description(),new MediaConstraints());
    timer.schedule(()->{if(!connected)end(I18n.t("Нет ответа"),true);},65,TimeUnit.SECONDS);
   }catch(Exception e){end(Api.message(e),true);}});
  }
  SdpObserver description(){return new SdpObserver(){
   public void onCreateSuccess(SessionDescription s){execute(()->pc.setLocalDescription(observer(()->{try{timer.schedule(()->execute(()->sendSdp()),10,TimeUnit.SECONDS);}catch(RejectedExecutionException ignored){}}),s));}
   public void onSetSuccess(){}public void onCreateFailure(String e){end(I18n.t("Ошибка соединения"),true);}public void onSetFailure(String e){end(I18n.t("Ошибка соединения"),true);}
  };}
  SdpObserver observer(Runnable success){return new SdpObserver(){public void onSetSuccess(){execute(success);}public void onSetFailure(String s){end(I18n.t("Ошибка соединения"),true);}public void onCreateSuccess(SessionDescription s){}public void onCreateFailure(String s){end(I18n.t("Ошибка соединения"),true);}};}
  void sendSdp(){if(sent||pc==null||pc.getLocalDescription()==null)return;String sdp=pc.getLocalDescription().description;if(sdp.length()>14000){end(I18n.t("Не удалось согласовать соединение"),true);return;}sent=true;send(incoming?"call_answer":"call_offer",sdp);state=incoming?I18n.t("Соединяем…"):I18n.t("Ожидаем ответа…");ChatService.changed(c);}
  void answer(String sdp){if(pc==null||sdp.length()>14000||pc.getRemoteDescription()!=null)return;pc.setRemoteDescription(observer(()->{state=I18n.t("Соединяем…");ChatService.changed(c);}),new SessionDescription(SessionDescription.Type.ANSWER,sdp));}
  void send(String op,String sdp){try{JSONObject p=new JSONObject().put("op",op).put("sid",sid).put("sent_at",System.currentTimeMillis());if(!sdp.isEmpty())p.put("sdp",sdp);ChatService service=ChatService.instance;if(service!=null&&service.vault==vault)service.signal(peer,p);}catch(Exception ignored){}}
  void mute(){muted=!muted;execute(()->{if(audio!=null)audio.setEnabled(!muted);});}
  void speaker(){speaker=!speaker;if(accepted)c.getSystemService(AudioManager.class).setSpeakerphoneOn(speaker);}
  void incomingNotice(){
   NotificationManager nm=c.getSystemService(NotificationManager.class);NotificationChannel ch=new NotificationChannel("incoming_calls",I18n.t("Входящие звонки"),NotificationManager.IMPORTANCE_HIGH);
   ch.setSound(android.provider.Settings.System.DEFAULT_RINGTONE_URI,new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build());ch.enableVibration(true);nm.createNotificationChannel(ch);
   Intent open=new Intent(c,CallActivity.class).putExtra("sid",sid).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
   PendingIntent view=PendingIntent.getActivity(c,409,open,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
   PendingIntent accept=PendingIntent.getActivity(c,410,new Intent(open).putExtra("accept",true),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
   PendingIntent reject=PendingIntent.getBroadcast(c,411,new Intent(c,CallActionReceiver.class).putExtra("sid",sid),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
   nm.notify(410,new Notification.Builder(c,"incoming_calls").setSmallIcon(R.drawable.ic_launcher).setContentTitle(I18n.t("Звонок от @")+peer).setContentText(I18n.t("Голосовой звонок OldЫ Chat")).setCategory(Notification.CATEGORY_CALL).setOngoing(true).setContentIntent(view).addAction(new Notification.Action.Builder(null,I18n.t("Ответить"),accept).build()).addAction(new Notification.Action.Builder(null,I18n.t("Отклонить"),reject).build()).build());ChatService.changed(c);
  }
  synchronized void end(String reason,boolean notify){
   if(ended)return;ended=true;state=reason;if(notify)send("call_end","");
   endedCalls.add(sid);while(endedCalls.size()>64)endedCalls.poll();
   timer.shutdownNow();c.getSystemService(NotificationManager.class).cancel(410);
   io.execute(()->{try{if(pc!=null){pc.close();pc.dispose();}if(audio!=null)audio.dispose();if(audioSource!=null)audioSource.dispose();if(factory!=null)factory.dispose();}finally{
    AudioManager manager=c.getSystemService(AudioManager.class);manager.setSpeakerphoneOn(false);manager.setMode(AudioManager.MODE_NORMAL);
    new Handler(Looper.getMainLooper()).post(()->{if(current==this){current=null;ChatService service=ChatService.instance;if(service!=null&&service.vault==vault)service.callForeground(false,peer);}});
   }});io.shutdown();ChatService.changed(c);note(c,reason);
  }
 }
}
