package chat.oldy;
import android.app.*;
import android.content.*;
import android.os.*;
import org.json.*;
import java.util.concurrent.*;
/** Actual service delivery while the display is off, against the local TLS relay only. */
public class ScreenOffInstrumentation extends Instrumentation {
 Bundle args;public void onCreate(Bundle b){args=b;start();}
 void shell(String command)throws Exception{try(java.io.InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand(command))){byte[] b=new byte[1024];while(in.read(b)>=0){}}}
 public void onStart(){Bundle out=new Bundle();try{
  Api api=new Api(getTargetContext());api.configure("https://10.0.2.2:8444",args.getString("pin"));
  JSONObject identity=Crypto.identity();JSONObject registered=api.call("/register",new JSONObject().put("nick","screen_test").put("password","only a test password").put("name","Ночной тест").put("enc",identity.getString("enc")).put("sig",identity.getString("sig")),"");
  JSONObject alice=api.call("/user/alice",null,registered.getString("token"));Vault v=ChatService.vault(getTargetContext());
  getTargetContext().startActivity(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));long ready=System.currentTimeMillis()+20000;
  while((ChatService.instance==null||!ChatService.state.equals("Подключено"))&&System.currentTimeMillis()<ready)Thread.sleep(200);
  if(ChatService.instance==null||!ChatService.state.equals("Подключено"))throw new Exception("Receiver did not become ready: "+ChatService.state);
  Thread.sleep(500);
  shell("dumpsys deviceidle whitelist +chat.oldy");shell("input keyevent 223");Thread.sleep(500);
  if(getTargetContext().getSystemService(PowerManager.class).isInteractive())throw new Exception("Display did not turn off");
  String mid=java.util.UUID.randomUUID().toString();JSONObject e=Crypto.encrypt("screen_test","alice","Сообщение при выключенном экране",mid,System.currentTimeMillis(),identity,alice);
  JSONObject delivered=null;long until=System.currentTimeMillis()+12000;
  while(delivered==null){try{delivered=api.call("/send",e,registered.getString("token"));}catch(Api.Failure offline){if(offline.status!=409||System.currentTimeMillis()>=until)throw offline;Thread.sleep(700);}}
  if(!delivered.optBoolean("delivered")||!v.has(mid))throw new Exception("No delivery with screen off");
  boolean found=false;for(android.service.notification.StatusBarNotification n:getTargetContext().getSystemService(NotificationManager.class).getActiveNotifications())if(n.getId()=="screen_test".hashCode())found=true;
  if(!found)throw new Exception("No message notification with screen off");
  out.putString("stream","OLDY_SCREEN_OFF_PASS: actual encrypted message delivered to foreground service and notification posted while display off with battery exemption\n");finish(-1,out);
 }catch(Throwable e){out.putString("stream","OLDY_SCREEN_OFF_FAIL: "+e+"\n"+android.util.Log.getStackTraceString(e));finish(0,out);}finally{try{shell("input keyevent 224");shell("wm dismiss-keyguard");}catch(Exception ignored){}}}
}
