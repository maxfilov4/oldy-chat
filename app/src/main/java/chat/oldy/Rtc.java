package chat.oldy;
import android.content.Context;
import org.json.*;
import org.webrtc.*;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;

/** Direct WebRTC data channels. Only STUN discovery; no TURN / server media fallback. */
final class Rtc {
 interface Signal {void send(String peer,JSONObject payload)throws Exception;}
 final Context context;final Vault vault;final Signal signal;final PeerConnectionFactory factory;
 final ScheduledExecutorService io=Executors.newSingleThreadScheduledExecutor();
 final java.util.List<PeerConnection.IceServer> iceServers;
 final Map<String,Session> sessions=new HashMap<>();final Map<String,String> wanted=new HashMap<>();
 static final ConcurrentHashMap<String,String> status=new ConcurrentHashMap<>();
 Rtc(Context c,Vault v,Signal s){this(c,v,s,Collections.singletonList(PeerConnection.IceServer.builder("stun:5.42.102.11:3478").createIceServer()));}
 Rtc(Context c,Vault v,Signal s,java.util.List<PeerConnection.IceServer> ice){context=c;vault=v;signal=s;iceServers=ice;PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(c).setEnableInternalTracer(false).createInitializationOptions());factory=PeerConnectionFactory.builder().createPeerConnectionFactory();}
 void request(JSONObject m){io.execute(()->{try{String mid=m.getString("id"),from=m.getString("from");if(m.has("local"))return;wanted.put(mid,from);status.put(mid,"Соединяем телефоны…");signal.send(from,new JSONObject().put("op","request").put("mid",mid).put("room",Conversation.community(m.optString("peer"))?Conversation.room(m.optString("peer")):""));io.schedule(()->{if(wanted.remove(mid)!=null){status.put(mid,"Нет прямого соединения. Попробуйте Wi-Fi и откройте чат на обоих телефонах.");ChatService.changed(context);}},65,TimeUnit.SECONDS);}catch(Exception e){status.put(m.optString("id"),Api.message(e));}ChatService.changed(context);});}
 void accept(String peer,JSONObject p){io.execute(()->{try{
  String op=p.getString("op"),mid=p.getString("mid");if(!mid.matches("[a-f0-9-]{36}"))return;
  if(op.equals("request")){
   JSONObject m=vault.message(mid);if(m==null||!m.optBoolean("out")||!mayReceive(m,peer)||!m.has("local")||!m.optString("kind").equals("file"))return;
   if(sessions.size()>=3)return;for(Session x:sessions.values())if(x.mid.equals(mid)&&x.peer.equals(peer))return;
   String sid=UUID.randomUUID().toString();Session x=new Session(sid,peer,m,true);sessions.put(sid,x);x.connect();x.channel(x.pc.createDataChannel("oldy-file",new DataChannel.Init()));x.pc.createOffer(x.creator("offer"),new MediaConstraints());
  }else{
   String sid=p.getString("sid");Session x=sessions.get(sid);
   if(op.equals("offer")){
    if(!peer.equals(wanted.get(mid))||sessions.size()>=3||x!=null||!sid.matches("[a-f0-9-]{36}"))return;
    JSONObject m=vault.message(mid);if(m==null)return;x=new Session(sid,peer,m,false);sessions.put(sid,x);x.connect();x.remote("offer",p.getString("sdp"));
   }else if(x!=null&&x.peer.equals(peer)&&x.mid.equals(mid)&&op.equals("answer"))x.remote("answer",p.getString("sdp"));
  }
 }catch(Exception e){status.put(p.optString("mid"),Api.message(e));ChatService.changed(context);}});}
 boolean mayReceive(JSONObject m,String peer)throws Exception{String target=m.optString("peer");if(!Conversation.community(target))return target.equals(peer)&&!vault.isBlocked(peer);JSONObject room=vault.room(Conversation.room(target));if(room==null)return false;JSONArray a=room.getJSONArray("members");for(int i=0;i<a.length();i++)if(peer.equals(a.optString(i)))return true;return false;}
 void close(){io.execute(()->{for(Session s:new ArrayList<>(sessions.values()))s.finish(false,"Передача остановлена");factory.dispose();});io.shutdown();}
 class Session {
  final String sid,peer,mid;final JSONObject m;final boolean sending;PeerConnection pc;DataChannel dc;InputStream input;MediaFiles.Writer output;boolean ended,sentSdp;String sdpType;long received,last=System.currentTimeMillis();
  Session(String s,String p,JSONObject message,boolean send){sid=s;peer=p;m=message;mid=m.optString("id");sending=send;}
  void connect(){
   PeerConnection.RTCConfiguration config=new PeerConnection.RTCConfiguration(iceServers);config.sdpSemantics=PeerConnection.SdpSemantics.UNIFIED_PLAN;
   pc=factory.createPeerConnection(config,new PeerConnection.Observer(){
    public void onSignalingChange(PeerConnection.SignalingState s){}
    public void onIceConnectionChange(PeerConnection.IceConnectionState s){if(s==PeerConnection.IceConnectionState.FAILED)io.execute(()->finish(false,"Сеть не разрешила прямую передачу. Попробуйте Wi-Fi."));}
    public void onIceConnectionReceivingChange(boolean b){}
    public void onIceGatheringChange(PeerConnection.IceGatheringState s){if(s==PeerConnection.IceGatheringState.COMPLETE)io.execute(()->sendSdp());}
    public void onIceCandidate(IceCandidate c){}
    public void onIceCandidatesRemoved(IceCandidate[] c){}
    public void onAddStream(MediaStream s){}
    public void onRemoveStream(MediaStream s){}
    public void onDataChannel(DataChannel d){io.execute(()->channel(d));}
    public void onRenegotiationNeeded(){}
   });
   if(pc==null)throw new IllegalStateException("Не удалось соединить телефоны");
   io.schedule(()->{if(!ended&&dc!=null&&dc.state()!=DataChannel.State.OPEN)finish(false,"Нет прямого соединения. Откройте чат на обоих телефонах.");},55,TimeUnit.SECONDS);
   io.schedule(()->{if(!ended)finish(false,"Передача прервана. Нажмите ещё раз для повтора.");},8,TimeUnit.MINUTES);
  }
  SdpObserver creator(String type){return new SdpObserver(){
   public void onCreateSuccess(SessionDescription s){io.execute(()->{if(ended)return;sdpType=type;pc.setLocalDescription(new SdpObserver(){public void onSetSuccess(){io.schedule(()->sendSdp(),8,TimeUnit.SECONDS);}public void onSetFailure(String x){io.execute(()->finish(false,"Не удалось соединить телефоны"));}public void onCreateSuccess(SessionDescription s){}public void onCreateFailure(String s){}},s);});}
   public void onCreateFailure(String s){io.execute(()->finish(false,"Не удалось соединить телефоны"));}public void onSetSuccess(){}public void onSetFailure(String s){}
  };}
  void sendSdp(){if(ended||sentSdp||sdpType==null||pc.getLocalDescription()==null)return;try{String s=pc.getLocalDescription().description;if(s.length()>14000)throw new IOException();signal.send(peer,new JSONObject().put("op",sdpType).put("mid",mid).put("sid",sid).put("sdp",s).put("room",Conversation.community(m.optString("peer"))?Conversation.room(m.optString("peer")):""));sentSdp=true;}catch(Exception e){finish(false,"Не удалось отправить запрос передачи");}}
  void remote(String type,String sdp){if(sdp.length()>14000)return;pc.setRemoteDescription(new SdpObserver(){public void onSetSuccess(){if(type.equals("offer"))io.execute(()->pc.createAnswer(creator("answer"),new MediaConstraints()));}public void onSetFailure(String s){io.execute(()->finish(false,"Не удалось соединить телефоны"));}public void onCreateSuccess(SessionDescription s){}public void onCreateFailure(String s){}},new SessionDescription(type.equals("offer")?SessionDescription.Type.OFFER:SessionDescription.Type.ANSWER,sdp));}
  void channel(DataChannel d){if(ended){d.close();return;}dc=d;dc.registerObserver(new DataChannel.Observer(){
   public void onBufferedAmountChange(long amount){}
   public void onStateChange(){io.execute(()->{if(ended)return;if(dc.state()==DataChannel.State.OPEN){wanted.remove(mid);status.put(mid,"Передача…");ChatService.changed(context);if(sending)startSend();}else if(dc.state()==DataChannel.State.CLOSED)finish(false,"Передача прервана. Нажмите для повтора.");});}
   public void onMessage(DataChannel.Buffer b){byte[] bytes=new byte[b.data.remaining()];b.data.get(bytes);boolean binary=b.binary;io.execute(()->consume(bytes,binary));}
  });if(dc.state()==DataChannel.State.OPEN&&sending)startSend();}
  void startSend(){if(input!=null||ended)return;try{input=MediaFiles.open(context,m.getString("local"));pump();}catch(Exception e){finish(false,"Не удалось открыть вложение");}}
  void pump(){if(ended||dc.state()!=DataChannel.State.OPEN)return;try{
   int count=0;while(dc.bufferedAmount()<262144&&count++<8){byte[] b=new byte[16384];int n=input.read(b);if(n<0){input.close();input=null;if(!dc.send(new DataChannel.Buffer(ByteBuffer.wrap(Crypto.bytes("done")),false)))throw new IOException();return;}if(!dc.send(new DataChannel.Buffer(ByteBuffer.wrap(Arrays.copyOf(b,n)),true)))throw new IOException();received+=n;}
   status.put(mid,"Отправлено "+(received*100/Math.max(1,m.optLong("size")))+"%");io.schedule(()->pump(),25,TimeUnit.MILLISECONDS);
  }catch(Exception e){finish(false,"Передача прервана. Нажмите для повтора.");}}
  void consume(byte[] bytes,boolean binary){if(ended)return;try{
   if(sending){if(!binary&&new String(bytes,"UTF-8").equals("saved")){finish(true,"Передано");}return;}
   if(output==null)output=new MediaFiles.Writer(context,mid);
   if(binary){if(received+bytes.length>m.getLong("size"))throw new IOException();output.put(bytes);received+=bytes.length;status.put(mid,"Загрузка "+(received*100/m.getLong("size"))+"%");ChatService.changed(context);}
   else if(new String(bytes,"UTF-8").equals("done")){
    if(received!=m.getLong("size"))throw new IOException();String hash=output.finish();if(!hash.equalsIgnoreCase(m.getString("sha256"))){MediaFiles.path(context,mid).delete();throw new IOException();}
    vault.attachment(mid,mid);if(!dc.send(new DataChannel.Buffer(ByteBuffer.wrap(Crypto.bytes("saved")),false)))throw new IOException();status.put(mid,"Готово");ChatService.changed(context);io.schedule(()->finish(true,"Готово"),2,TimeUnit.SECONDS);
   }
  }catch(Exception e){finish(false,"Не удалось получить файл. Нажмите для повтора.");}}
  void finish(boolean ok,String note){if(ended)return;ended=true;status.put(mid,note);wanted.remove(mid);sessions.remove(sid);try{if(input!=null)input.close();}catch(Exception ignored){}if(output!=null)output.close();if(dc!=null){dc.unregisterObserver();dc.close();dc.dispose();}if(pc!=null){pc.close();pc.dispose();}ChatService.changed(context);}
 }
}
