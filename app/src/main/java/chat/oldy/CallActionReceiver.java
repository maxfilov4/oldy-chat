package chat.oldy;
import android.content.*;
public class CallActionReceiver extends BroadcastReceiver {public void onReceive(Context c,Intent i){LiveCall.Session call=LiveCall.current;if(call!=null&&call.sid.equals(i.getStringExtra("sid")))call.end("Звонок отклонён",true);}}
