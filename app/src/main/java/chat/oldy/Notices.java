package chat.oldy;
import android.app.*;
import android.content.*;
import android.media.AudioAttributes;
import android.net.Uri;
import org.json.*;
final class Notices {
 static android.content.SharedPreferences prefs(Context c){return c.getSharedPreferences("appearance",0);}
 static final String[] TONE_NAMES={"Oldy Pop","Аркада","Кристалл","Ретро-пейджер","Двойной звонок","Космос"};
 static final int[] TONES={R.raw.oldy_chime,R.raw.tone_arcade,R.raw.tone_crystal,R.raw.tone_pager,R.raw.tone_bell,R.raw.tone_space};
 static int tone(Context c){return Math.max(0,Math.min(TONES.length-1,prefs(c).getInt("tone",1)));}
 static String channelId(Context c){return "messages_3_"+tone(c)+(prefs(c).getBoolean("sound",true)?"s":"q")+(prefs(c).getBoolean("vibration",true)?"v":"n");}
 static void show(Context c,JSONObject m,JSONObject user){
  if(!prefs(c).getBoolean("notifications",true))return;
  boolean focus=prefs(c).getLong("focus_until",0)>System.currentTimeMillis();boolean sound=!focus&&prefs(c).getBoolean("sound",true),vibrate=!focus&&prefs(c).getBoolean("vibration",true),preview=prefs(c).getBoolean("preview",false);
  String ch=focus?"messages_focus":channelId(c);
  NotificationManager nm=c.getSystemService(NotificationManager.class);NotificationChannel channel=new NotificationChannel(ch,"Сообщения"+(sound?" · "+TONE_NAMES[tone(c)]:" · без звука"),NotificationManager.IMPORTANCE_HIGH);
  channel.setSound(sound?Uri.parse("android.resource://"+c.getPackageName()+"/"+TONES[tone(c)]):null,new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build());if(vibrate)channel.setVibrationPattern(new long[]{0,80,70,80});channel.enableVibration(vibrate);nm.createNotificationChannel(channel);
  String peer=m.optString("peer");Intent i=new Intent(c,MainActivity.class).putExtra("chat",peer).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
  PendingIntent p=PendingIntent.getActivity(c,peer.hashCode(),i,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
  Notification n=new Notification.Builder(c,ch).setSmallIcon(R.drawable.ic_launcher).setContentTitle(user.optString("name","OldЫ Chat")).setContentText(preview?Payload.preview(m):"Новое сообщение").setContentIntent(p).setAutoCancel(true).setCategory(Notification.CATEGORY_MESSAGE).setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(new Notification.Builder(c,ch).setSmallIcon(R.drawable.ic_launcher).setContentTitle("OldЫ Chat").setContentText("Новое сообщение").build()).build();
  try{nm.notify(peer.hashCode(),n);}catch(SecurityException ignored){}
 }
}
