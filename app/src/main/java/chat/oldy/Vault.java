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
  if(!data.has("rooms"))data.put("rooms",new JSONObject());
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
  data.put("nick",u.getString("nick")).put("name",u.getString("name")).put("token",reply.getString("token")).put("avatar",u.optString("avatar","preset:0")).put("bio",u.optString("bio"));save();
 }
 synchronized JSONObject peer(String nick)throws Exception{return data.getJSONObject("contacts").optJSONObject(nick);}
 synchronized void pin(JSONObject peer)throws Exception{
  String n=peer.getString("nick");JSONObject old=peer(n);
  if(old!=null&&!Crypto.fingerprint(old).equals(Crypto.fingerprint(peer)))throw new Exception("Ключ @"+n+" изменился. Передача остановлена.");
  if(old==null||!old.toString().equals(peer.toString())){data.getJSONObject("contacts").put(n,peer);save();}
 }
 synchronized void profile(JSONObject u)throws Exception{data.put("name",u.getString("name")).put("avatar",u.optString("avatar","preset:0")).put("bio",u.optString("bio"));save();}
 synchronized void rooms(JSONArray rooms)throws Exception{JSONObject next=new JSONObject();for(int i=0;i<rooms.length();i++){JSONObject r=rooms.getJSONObject(i);next.put(r.getString("id"),r);}data.put("rooms",next);save();}
 synchronized JSONObject room(String id)throws Exception{return data.getJSONObject("rooms").optJSONObject(id);}
 synchronized void putRoom(JSONObject r)throws Exception{data.getJSONObject("rooms").put(r.getString("id"),r);save();}
 synchronized void queue(String to,String text)throws Exception{queuePayload(to,new JSONObject().put("kind","text").put("text",text),null);}
 synchronized String queuePayload(String to,JSONObject body,String local)throws Exception{
  String id=java.util.UUID.randomUUID().toString();long now=System.currentTimeMillis();JSONObject p=new JSONObject(body.toString());
  JSONObject m=new JSONObject().put("id",id).put("peer",to).put("text",p.optString("text")).put("kind",p.optString("kind","text")).put("time",now).put("out",true).put("status","pending");
  if(local!=null)m.put("local",local);
  for(String key:new String[]{"mime","size","name","sha256","sticker"})if(p.has(key))m.put(key,p.get(key));
  JSONObject envelopes=new JSONObject();
  if(to.startsWith("room:")){
   JSONObject r=room(to.substring(5));if(r==null)throw new Exception("Чат недоступен");
   if(r.getString("kind").equals("channel")&&!r.getString("owner").equals(nick()))throw new Exception("Публиковать может создатель канала");
   p.put("room",r.getString("id"));JSONArray members=r.getJSONArray("members");
   for(int i=0;i<members.length();i++){String n=members.getString(i);if(!n.equals(nick()))envelopes.put(n,Crypto.encrypt(nick(),n,Payload.wire(p),id,now,identity(),peer(n)));}
  }else envelopes.put(to,Crypto.encrypt(nick(),to,Payload.wire(p),id,now,identity(),peer(to)));
  m.put("envelopes",envelopes);if(envelopes.length()==0)m.put("status","delivered");
  data.getJSONArray("messages").put(m);save();return id;
 }
 synchronized JSONObject message(String id)throws Exception{JSONArray a=data.getJSONArray("messages");for(int i=0;i<a.length();i++)if(a.getJSONObject(i).getString("id").equals(id))return new JSONObject(a.getJSONObject(i).toString());return null;}
 synchronized void attachment(String id,String local)throws Exception{JSONArray a=data.getJSONArray("messages");for(int i=0;i<a.length();i++){JSONObject m=a.getJSONObject(i);if(m.getString("id").equals(id)){m.put("local",local);save();return;}}throw new Exception("Сообщение не найдено");}
 synchronized boolean has(String id)throws Exception{JSONArray a=data.getJSONArray("messages");for(int i=0;i<a.length();i++)if(a.getJSONObject(i).getString("id").equals(id))return true;return false;}
 synchronized void receive(JSONObject envelope,JSONObject peer)throws Exception{receiveDecoded(envelope,peer,Payload.parse(Crypto.decrypt(envelope,identity(),peer)));}
 synchronized void receiveDecoded(JSONObject envelope,JSONObject peer,JSONObject p)throws Exception{
  if(!envelope.getString("to").equals(nick())||!envelope.getString("from").equals(peer.getString("nick")))throw new Exception("Неверный получатель");
  if(has(envelope.getString("id")))return;
  String k=p.optString("kind","text");if(k.equals("signal"))throw new Exception("Неверное сообщение");
  String room=p.optString("room");String target=room.isEmpty()?peer.getString("nick"):"room:"+room;
  if(!room.isEmpty()){
   JSONObject r=room(room);if(r==null)throw new Exception("Чат недоступен");
   boolean member=false;JSONArray members=r.getJSONArray("members");for(int i=0;i<members.length();i++)if(members.getString(i).equals(peer.getString("nick")))member=true;
   if(!member||(r.getString("kind").equals("channel")&&!r.getString("owner").equals(peer.getString("nick"))))throw new Exception("Автор не может писать в этот чат");
  }
  JSONObject m=new JSONObject().put("id",envelope.getString("id")).put("peer",target).put("from",peer.getString("nick")).put("text",p.optString("text")).put("kind",k).put("time",envelope.getLong("time")).put("out",false).put("status","received");
  if(k.equals("file")){
   if(!p.optString("mime").matches("image/(jpeg|png|webp)|video/(mp4|webm)|audio/(mp4|ogg|mpeg)")||p.optLong("size")<1||p.optLong("size")>MediaFiles.MAX||!p.optString("sha256").matches("[a-fA-F0-9]{64}"))throw new Exception("Неподдерживаемое вложение");
   for(String key:new String[]{"mime","size","sha256"})m.put(key,p.get(key));m.put("name",p.optString("name","Медиа").replaceAll("[\\r\\n/\\\\]","_").substring(0,Math.min(100,p.optString("name","Медиа").length())));
  }
  if(k.equals("sticker"))m.put("sticker",Math.max(0,Math.min(11,p.optInt("sticker"))));
  data.getJSONArray("messages").put(m);save();
 }
 synchronized void deliveredTo(String id,String to)throws Exception{JSONArray a=data.getJSONArray("messages");for(int i=0;i<a.length();i++){JSONObject m=a.getJSONObject(i);if(!m.getString("id").equals(id))continue;JSONObject q=m.optJSONObject("envelopes");if(q!=null){q.remove(to);if(q.length()==0)m.put("status","delivered");}else{m.put("status","delivered");m.remove("envelope");}save();return;}}
 synchronized void delivered(String id)throws Exception{
  JSONArray a=data.getJSONArray("messages");for(int i=0;i<a.length();i++){JSONObject m=a.getJSONObject(i);if(m.getString("id").equals(id)){m.put("status","delivered");m.remove("envelope");save();return;}}
 }
 synchronized void restore(String plain)throws Exception{
  JSONObject next=new JSONObject(plain);JSONObject id=next.getJSONObject("identity");Crypto.fingerprint(id);id.getString("encPrivate");id.getString("sigPrivate");next.getJSONArray("messages");next.getJSONObject("contacts");if(!next.has("rooms"))next.put("rooms",new JSONObject());
  if(data.has("nick"))throw new Exception("Восстановление доступно до входа в аккаунт.");next.remove("token");data=next;save();
 }
 synchronized String backup()throws Exception{JSONObject c=copy();c.remove("token");JSONArray a=c.getJSONArray("messages");for(int i=0;i<a.length();i++)a.getJSONObject(i).remove("local");return c.toString();}
}
