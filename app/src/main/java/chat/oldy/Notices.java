package chat.oldy;
import android.app.*;import android.content.*;import android.media.AudioAttributes;import android.net.Uri;import android.os.Bundle;import org.json.*;
/** Notification policy never controls transport, persistence or visible chat updates. */
final class Notices {
 static SharedPreferences prefs(Context c){return c.getSharedPreferences("appearance",0);}
 static SharedPreferences account(Context c,Vault v){return c.getSharedPreferences("notices_v4_"+v.nick(),0);}
 static String roomKey(String peer){return Conversation.thread(peer)?"room:"+Conversation.room(peer):peer;}
 static long quietUntil(Context c,Vault v){return account(c,v).getLong("quiet_until",0);}
 static void quiet(Context c,Vault v,long until){account(c,v).edit().putLong("quiet_until",until).apply();prefs(c).edit().remove("focus_until").apply();}
 static long muteUntil(Context c,Vault v,String peer){return account(c,v).getLong("mute:"+roomKey(peer),0);}
 static boolean muted(Context c,Vault v,String peer,long now){long t=muteUntil(c,v,peer);return t==-1||t>now;}
 static void mute(Context c,Vault v,String peer,long until){account(c,v).edit().putLong("mute:"+roomKey(peer),until).apply();if(until!=0){NotificationManager nm=c.getSystemService(NotificationManager.class);for(android.service.notification.StatusBarNotification item:nm.getActiveNotifications())if(roomKey(peer).equals(roomKey(item.getNotification().extras.getString("oldy_peer",""))))nm.cancel(item.getTag(),item.getId());}}
 static final String[] TONE_NAMES={"Oldy Pop","Аркада","Кристалл","Ретро-пейджер","Двойной звонок","Космос"};
 static final int[] TONES={R.raw.oldy_chime,R.raw.tone_arcade,R.raw.tone_crystal,R.raw.tone_pager,R.raw.tone_bell,R.raw.tone_space};
 static int tone(Context c){return Math.max(0,Math.min(TONES.length-1,prefs(c).getInt("tone",1)));}
 static String channelId(Context c){return channelId(c,prefs(c).getBoolean("sound",true),prefs(c).getBoolean("vibration",true));}
 static String channelId(Context c,boolean sound,boolean vibrate){return "messages_4_"+tone(c)+(sound?"s":"q")+(vibrate?"v":"n");}
 static final class Policy{final boolean visible,sound,vibrate;Policy(boolean v,boolean s,boolean b){visible=v;sound=s;vibrate=b;}}
 static Policy policy(Context c,Vault v,String peer,long now){boolean visible=prefs(c).getBoolean("notifications",true)&&!muted(c,v,peer,now),quiet=quietUntil(c,v)>now;return new Policy(visible,visible&&!quiet&&prefs(c).getBoolean("sound",true),visible&&!quiet&&prefs(c).getBoolean("vibration",true));}
 static void show(Context c,Vault v,JSONObject m,JSONObject user){
  String peer=m.optString("peer");Policy rule=policy(c,v,peer,System.currentTimeMillis());if(!rule.visible)return;
  boolean preview=prefs(c).getBoolean("preview",false);String ch=channelId(c,rule.sound,rule.vibrate);
  NotificationManager nm=c.getSystemService(NotificationManager.class);NotificationChannel channel=new NotificationChannel(ch,"Сообщения"+(rule.sound?" · "+TONE_NAMES[tone(c)]:" · без звука"),NotificationManager.IMPORTANCE_HIGH);
  channel.setSound(rule.sound?Uri.parse("android.resource://"+c.getPackageName()+"/"+TONES[tone(c)]):null,new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build());if(rule.vibrate)channel.setVibrationPattern(new long[]{0,80,70,80});channel.enableVibration(rule.vibrate);nm.createNotificationChannel(channel);
  Intent i=new Intent(c,MainActivity.class).putExtra("chat",peer).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);PendingIntent p=PendingIntent.getActivity(c,peer.hashCode(),i,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);Bundle extras=new Bundle();extras.putString("oldy_peer",peer);
  Notification n=new Notification.Builder(c,ch).setSmallIcon(R.drawable.ic_launcher).setContentTitle(user.optString("name","OldЫ Chat")).setContentText(preview?Payload.preview(m):"Новое сообщение").setContentIntent(p).setAutoCancel(true).setCategory(Notification.CATEGORY_MESSAGE).setExtras(extras).setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(new Notification.Builder(c,ch).setSmallIcon(R.drawable.ic_launcher).setContentTitle("OldЫ Chat").setContentText("Новое сообщение").build()).build();try{nm.notify(peer.hashCode(),n);}catch(SecurityException ignored){}
 }
}
