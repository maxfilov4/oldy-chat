package chat.oldy;
import android.app.*;import android.content.*;import android.os.*;import org.webrtc.*;import org.json.*;import java.util.*;import java.util.concurrent.*;
/** Real Android WebRTC <-> independent aiortc peer, including the authenticated TURN relay. */
public class CallInstrumentation extends Instrumentation {
 public void onCreate(Bundle b){start();}
 void check(boolean b,String reason)throws Exception{if(!b)throw new Exception(reason);}
 interface Condition{boolean value()throws Exception;}
 void until(Condition c,long timeout,String reason)throws Exception{long end=SystemClock.elapsedRealtime()+timeout;while(SystemClock.elapsedRealtime()<end){if(c.value())return;Thread.sleep(180);}throw new Exception(reason+" / "+(LiveCall.current==null?"no active call":LiveCall.current.state));}
 JSONObject peer(Api api)throws Exception{JSONObject j=api.call("/test-call/status",null,"");check(j.optString("error").isEmpty(),"Independent peer failed: "+j.optString("error"));return j;}
 double[] stats(LiveCall.Session call)throws Exception{
  CountDownLatch done=new CountDownLatch(1);double[] values=new double[4];
  call.execute(()->call.pc.getStats(report->{Map<String,RTCStats> all=report.getStatsMap();for(RTCStats row:all.values()){
   Map<String,Object> m=row.getMembers();String type=row.getType();
   if(type.equals("inbound-rtp")){Object packets=m.get("packetsReceived"),energy=m.get("totalAudioEnergy");if(packets instanceof Number)values[0]+=((Number)packets).doubleValue();if(energy instanceof Number)values[3]+=((Number)energy).doubleValue();}
   if(type.equals("outbound-rtp")&&m.get("packetsSent") instanceof Number)values[1]+=((Number)m.get("packetsSent")).doubleValue();
   if(type.equals("candidate-pair")&&"succeeded".equals(m.get("state"))){RTCStats local=all.get(m.get("localCandidateId"));if(local!=null&&"relay".equals(local.getMembers().get("candidateType")))values[2]=1;}
  }done.countDown();}));check(done.await(5,TimeUnit.SECONDS),"WebRTC statistics unavailable");return values;
 }
 void hangup(Context c,LiveCall.Session s)throws Exception{runOnMainSync(()->new CallActionReceiver().onReceive(c,new Intent(c,CallActionReceiver.class).putExtra("sid",s.sid)));until(()->LiveCall.current==null,7000,"Call did not release microphone/session");}
 public void onStart(){Bundle result=new Bundle();try{
  MainActivity a=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Api api=new Api(a);until(()->ChatService.instance!=null&&ChatService.instance.running,7000,"Delivery service unavailable");
  api.call("/test-call/start",new JSONObject(),"");until(()->peer(api).optBoolean("ready"),15000,"Independent peer not ready");a.vault.pin(api.call("/user/voice_test",null,a.vault.token()));Thread.sleep(700);
  runOnMainSync(()->LiveCall.dial(a,"voice_test"));until(()->LiveCall.current!=null&&LiveCall.current.connected,45000,"Outgoing call not connected");LiveCall.Session outgoing=LiveCall.current;Thread.sleep(3000);double[] connected=stats(outgoing);check(connected[0]>15&&connected[1]>15,"Two-way RTP did not flow: "+Arrays.toString(connected));check(connected[2]==1,"TURN relay was not used");check(connected[3]>0,"Incoming tone had no audio energy");check(peer(api).getInt("received")>15,"Independent peer received no Android audio frames");
  runOnMainSync(outgoing::mute);Thread.sleep(300);check(outgoing.muted&&!outgoing.audio.enabled(),"Mute did not disable microphone track");runOnMainSync(outgoing::mute);Thread.sleep(300);check(!outgoing.muted&&outgoing.audio.enabled(),"Microphone did not resume");runOnMainSync(outgoing::speaker);check(outgoing.speaker,"Speaker toggle failed");
  getUiAutomation().performGlobalAction(2);Thread.sleep(3500);double[] background=stats(outgoing);check(background[0]>connected[0]+20&&background[1]>connected[1]+20,"Audio stopped with activity in background");hangup(a,outgoing);until(()->peer(api).getInt("ends")>=1,7000,"Hangup was not delivered");
  api.call("/test-call/ring",new JSONObject(),"");until(()->LiveCall.current!=null&&LiveCall.current.incoming,15000,"Incoming call absent");LiveCall.Session rejected=LiveCall.current;check(!rejected.accepted,"Incoming call auto-accepted before user action");hangup(a,rejected);until(()->peer(api).getInt("ends")>=2,7000,"Reject was not delivered");
  api.call("/test-call/ring",new JSONObject(),"");until(()->LiveCall.current!=null&&LiveCall.current.incoming,15000,"Second incoming call absent");LiveCall.Session incoming=LiveCall.current;startActivitySync(new Intent(a,CallActivity.class).putExtra("sid",incoming.sid).putExtra("accept",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));until(()->incoming.connected,35000,"Accepted incoming call not connected");Thread.sleep(1800);double[] incomingStats=stats(incoming);check(incomingStats[0]>15&&incomingStats[1]>15&&incomingStats[2]==1,"Incoming two-way TURN audio failed");hangup(a,incoming);
  result.putString("stream","OLDY_CALL_PASS: independent encrypted signalling, outgoing/incoming WebRTC, real two-way audio RTP and tone energy, authenticated TURN, mute/unmute, reject/hangup, background microphone service\n");finish(-1,result);
 }catch(Throwable e){LiveCall.Session s=LiveCall.current;if(s!=null)s.end("Тест завершён",true);result.putString("stream","OLDY_CALL_FAIL: "+android.util.Log.getStackTraceString(e));finish(0,result);}}
}
