package chat.oldy;

import android.content.Context;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.util.*;

/** Separate per-account, Keystore-encrypted local library. Never included in cloud sync. */
final class VideoLibraryStore {
 private static final Object LOCK=new Object();final AtomicFile file;
 VideoLibraryStore(Context c,String account)throws IOException{if(!account.matches("[a-z0-9_]{3,24}"))throw new IOException("Account required");File dir=new File(c.getFilesDir(),"video-library");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Storage unavailable");file=new AtomicFile(new File(dir,account+".vault"));}
 private JSONObject read()throws Exception{return file.getBaseFile().exists()?new JSONObject(new String(Crypto.openLocal(file.readFully()),java.nio.charset.StandardCharsets.UTF_8)):new JSONObject();}
 private void write(JSONObject j)throws Exception{FileOutputStream out=null;try{out=file.startWrite();out.write(Crypto.sealLocal(Crypto.bytes(j.toString())));file.finishWrite(out);}catch(Exception e){if(out!=null)file.failWrite(out);throw e;}}
 JSONObject get(String id)throws Exception{synchronized(LOCK){JSONObject j=read().optJSONObject(id);return j==null?new JSONObject():j;}}
 void update(String id,String title,double position,double duration,double watched)throws Exception{
  if(!YouTubeLinkDetector.video(id)||!Double.isFinite(position)||!Double.isFinite(duration)||!Double.isFinite(watched))return;
  synchronized(LOCK){JSONObject all=read(),j=all.optJSONObject(id);if(j==null)j=new JSONObject();j.put("videoId",id).put("thumbnail","https://i.ytimg.com/vi/"+id+"/hqdefault.jpg");if(title!=null&&!title.trim().isEmpty())j.put("title",title.substring(0,Math.min(300,title.length())));
   j.put("position",Math.max(0,Math.min(86400,position))).put("duration",Math.max(0,Math.min(86400,duration))).put("watched",j.optDouble("watched",0)+Math.max(0,Math.min(10,watched)));
   if(watched>0)j.put("viewed_at",System.currentTimeMillis());all.put(id,j);trim(all);write(all);
  }
 }
 void toggle(String id,String field)throws Exception{if(!YouTubeLinkDetector.video(id)||!field.matches("favorite|later"))return;synchronized(LOCK){JSONObject all=read(),j=all.optJSONObject(id);if(j==null)j=new JSONObject().put("videoId",id).put("thumbnail","https://i.ytimg.com/vi/"+id+"/hqdefault.jpg");j.put(field,!j.optBoolean(field));all.put(id,j);trim(all);write(all);}}
 void remove(String id)throws Exception{synchronized(LOCK){JSONObject all=read();all.remove(id);write(all);}}
 void clearHistory()throws Exception{synchronized(LOCK){JSONObject all=read();List<String> ids=new ArrayList<>();all.keys().forEachRemaining(ids::add);for(String id:ids){JSONObject j=all.getJSONObject(id);if(j.optBoolean("favorite")||j.optBoolean("later")){j.remove("viewed_at");j.remove("position");j.remove("watched");}else all.remove(id);}write(all);}}
 List<JSONObject> list(String tab)throws Exception{synchronized(LOCK){JSONObject all=read();List<JSONObject> items=new ArrayList<>();Iterator<String> keys=all.keys();while(keys.hasNext()){JSONObject j=all.getJSONObject(keys.next());if(tab.equals("history")?j.optLong("viewed_at")>0:j.optBoolean(tab))items.add(j);}items.sort((a,b)->Long.compare(b.optLong("viewed_at"),a.optLong("viewed_at")));return items;}}
 static void trim(JSONObject all)throws Exception{if(all.length()<=1000)return;List<String> ids=new ArrayList<>();all.keys().forEachRemaining(ids::add);ids.sort((a,b)->Long.compare(all.optJSONObject(a).optLong("viewed_at"),all.optJSONObject(b).optLong("viewed_at")));for(String id:ids){JSONObject j=all.getJSONObject(id);if(!j.optBoolean("favorite")&&!j.optBoolean("later"))all.remove(id);if(all.length()<=1000)return;}throw new IOException("Video library is full");}
 static String time(double value){long s=Math.max(0,(long)value);return s>=3600?String.format(Locale.ROOT,"%d:%02d:%02d",s/3600,s/60%60,s%60):String.format(Locale.ROOT,"%d:%02d",s/60,s%60);}
}
