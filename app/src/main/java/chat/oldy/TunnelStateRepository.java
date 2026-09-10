package chat.oldy;
import android.content.Context;
import android.os.SystemClock;

/** Runtime state is not a persisted 'ON' flag: a dead service is always OFF. */
final class TunnelStateRepository {
 static volatile String state="OFF",error="";static volatile long heartbeat,txBytes,rxBytes;static Context context;
 static void init(Context c){context=c.getApplicationContext();}
 static String mode(Context c){String m=Notices.prefs(c).getString("youtube_mode","ask");return m.matches("auto|ask|off")?m:"ask";}
 static void mode(Context c,String mode){if(!mode.matches("auto|ask|off"))throw new IllegalArgumentException();Notices.prefs(c).edit().putString("youtube_mode",mode).apply();}
 static void set(String next){state=next;heartbeat=SystemClock.elapsedRealtime();if(context!=null)try{java.io.File dir=context.getFilesDir(),temp=new java.io.File(dir,"youtube-state-"+android.os.Process.myPid()+".tmp"),target=new java.io.File(dir,"youtube-state");String data=next+"\n"+heartbeat+"\n"+error+"\n"+txBytes+"\n"+rxBytes;java.nio.file.Files.write(temp.toPath(),data.getBytes(java.nio.charset.StandardCharsets.UTF_8));java.nio.file.Files.move(temp.toPath(),target.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);}catch(Exception ignored){}}
 static void refresh(){if(context!=null)try{java.io.File f=new java.io.File(context.getFilesDir(),"youtube-state");if(f.length()>1024)return;String[] parts=new String(java.nio.file.Files.readAllBytes(f.toPath()),java.nio.charset.StandardCharsets.UTF_8).split("\n",-1);state=parts[0];heartbeat=Long.parseLong(parts[1]);if(parts.length>2&&!parts[2].isEmpty())error=parts[2];if(parts.length>4){txBytes=Long.parseLong(parts[3]);rxBytes=Long.parseLong(parts[4]);}}catch(Exception ignored){}}
 static boolean on(){refresh();return state.equals("ON")&&SystemClock.elapsedRealtime()-heartbeat>=0&&SystemClock.elapsedRealtime()-heartbeat<7000;}
 static void fail(Context c,String reason){error=reason;Notices.prefs(c).edit().putString("youtube_last_error",reason).apply();set("OFF");}
 static String lastError(Context c){refresh();return error.isEmpty()?Notices.prefs(c).getString("youtube_last_error",""):error;}
}
