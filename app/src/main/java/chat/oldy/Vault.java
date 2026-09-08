package chat.oldy;

import android.content.Context;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

final class Vault {
 final AtomicFile file;
 JSONObject data;
 String committed;
 Vault(Context c)throws Exception {
  file=new AtomicFile(new File(c.getFilesDir(),"history.vault"));
  if(file.getBaseFile().exists())data=new JSONObject(new String(Crypto.openLocal(file.readFully()),StandardCharsets.UTF_8));
  else data=new JSONObject().put("contacts",new JSONObject()).put("messages",new JSONArray());
  committed=data.toString();
 }
 synchronized void save()throws Exception {
  FileOutputStream out=null;
  try{byte[] bytes=Crypto.sealLocal(Crypto.bytes(data.toString()));out=file.startWrite();out.write(bytes);file.finishWrite(out);committed=data.toString();}catch(Exception e){if(out!=null)file.failWrite(out);data=new JSONObject(committed);throw e;}
 }
 synchronized JSONObject copy()throws Exception{return new JSONObject(data.toString());}
 synchronized JSONObject identity()throws Exception{
  if(!data.has("identity")){data.put("identity",Crypto.identity());save();}return data.getJSONObject("identity");
 }
 synchronized String nick(){return data.optString("nick");}
 synchronized String token(){return data.optString("token");}
 synchronized void account(JSONObject reply)throws Exception{
  JSONObject u=reply.getJSONObject("user");
  if(!Crypto.fingerprint(u).equals(Crypto.fingerprint(identity())))throw new Exception("Для этого аккаунта нужна резервная копия с прежнего телефона. Восстановите её в настройках.");
  data.put("nick",u.getString("nick")).put("name",u.getString("name")).put("token",reply.getString("token"));save();
 }
 synchronized JSONObject peer(String nick)throws Exception{return data.getJSONObject("contacts").optJSONObject(nick);}
 synchronized void pin(JSONObject peer)throws Exception{
  String n=peer.getString("nick");JSONObject old=peer(n);
  if(old!=null&&!Crypto.fingerprint(old).equals(Crypto.fingerprint(peer)))throw new Exception("Ключ @"+n+" изменился. Передача остановлена.");
  if(old==null){data.getJSONObject("contacts").put(n,peer);save();}
 }
 synchronized void queue(String to,String text)throws Exception{
  String id=java.util.UUID.randomUUID().toString();long now=System.currentTimeMillis();
  JSONObject envelope=Crypto.encrypt(nick(),to,text,id,now,identity(),peer(to));
  data.getJSONArray("messages").put(new JSONObject().put("id",id).put("peer",to).put("text",text).put("time",now).put("out",true).put("status","pending").put("envelope",envelope));save();
 }
 synchronized boolean has(String id)throws Exception{JSONArray a=data.getJSONArray("messages");for(int i=0;i<a.length();i++)if(a.getJSONObject(i).getString("id").equals(id))return true;return false;}
 synchronized void receive(JSONObject envelope,JSONObject peer)throws Exception{
  if(!envelope.getString("to").equals(nick())||!envelope.getString("from").equals(peer.getString("nick")))throw new Exception("Неверный получатель");
  if(has(envelope.getString("id")))return;
  String text=Crypto.decrypt(envelope,identity(),peer);
  data.getJSONArray("messages").put(new JSONObject().put("id",envelope.getString("id")).put("peer",peer.getString("nick")).put("text",text).put("time",envelope.getLong("time")).put("out",false).put("status","received"));save();
 }
 synchronized void delivered(String id)throws Exception{
  JSONArray a=data.getJSONArray("messages");for(int i=0;i<a.length();i++){JSONObject m=a.getJSONObject(i);if(m.getString("id").equals(id)){m.put("status","delivered");m.remove("envelope");save();return;}}
 }
 synchronized void restore(String plain)throws Exception{
  JSONObject next=new JSONObject(plain);JSONObject id=next.getJSONObject("identity");Crypto.fingerprint(id);id.getString("encPrivate");id.getString("sigPrivate");next.getJSONArray("messages");next.getJSONObject("contacts");
  if(data.has("nick"))throw new Exception("Восстановление доступно до входа в аккаунт.");next.remove("token");data=next;save();
 }
 synchronized String backup()throws Exception{JSONObject c=copy();c.remove("token");return c.toString();}
}
