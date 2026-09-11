package chat.oldy;

import android.content.Context;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

final class Vault {
 AtomicFile file;final Context context;
 volatile boolean erased;JSONObject data;
 String committed,lastMessages="";long roomsRevision,messageRevision;
 Vault(Context c)throws Exception{this(c,null,false);}
 Vault(Context c,String account,boolean fresh)throws Exception {
  context=c;boolean openActive=account==null;File selection=new File(c.getFilesDir(),"active-account");
  if(account==null&&selection.exists())account=new String(java.nio.file.Files.readAllBytes(selection.toPath()),StandardCharsets.UTF_8).trim();
  File source=account!=null?(account.matches("[a-z0-9_]{3,24}")?accountFile(account):new File(c.getFilesDir(),"guest.vault")):new File(c.getFilesDir(),"history.vault");
  if(fresh)source=new File(c.getFilesDir(),"enroll-"+java.util.UUID.randomUUID()+".vault");
  file=new AtomicFile(source);
  if(file.getBaseFile().exists())data=new JSONObject(new String(Crypto.openLocal(file.readFully()),StandardCharsets.UTF_8));
  else data=empty();
  if(!data.has("rooms"))data.put("rooms",new JSONObject());
  if(!data.optString("nick").isEmpty()&&data.optInt("account_schema")<2){
   // Preserve the original intact; rebuild the active view from server-verified account history.
   File legacy=new File(c.getFilesDir(),"legacy-before-isolation.vault");if(!legacy.exists())java.nio.file.Files.copy(source.toPath(),legacy.toPath());
   JSONObject clean=empty();for(String k:new String[]{"identity","nick","name","token","avatar","bio","email","email_verified","creator_video"})if(data.has(k))clean.put(k,data.get(k));data=clean;
  }
  data.put("account_schema",2);committed=data.toString();
  if(!nick().isEmpty()){file=new AtomicFile(accountFile(nick()));save();if(openActive&&!selection.exists())activate();}
 }
 static JSONObject empty()throws Exception{return new JSONObject().put("contacts",new JSONObject()).put("rooms",new JSONObject()).put("messages",new JSONArray()).put("account_schema",2);}
 File accountFile(String nick)throws Exception{if(!nick.matches("[a-z0-9_]{3,24}"))throw new Exception(I18n.t("Неверный аккаунт"));File dir=new File(context.getFilesDir(),"accounts");dir.mkdirs();return new File(dir,nick+".vault");}
 synchronized void activate()throws Exception{java.nio.file.Files.write(new File(context.getFilesDir(),"active-account").toPath(),Crypto.bytes(nick().isEmpty()?"-":nick()));}
 static Vault guest(Context c)throws Exception{Vault v=new Vault(c,"",true);v.activate();return v;}
 synchronized void save()throws Exception {
  if(erased)throw new IOException("Account deleted");
  FileOutputStream out=null;
  try{byte[] bytes=Crypto.sealLocal(Crypto.bytes(data.toString()));out=file.startWrite();out.write(bytes);file.finishWrite(out);committed=data.toString();String messages=data.optJSONArray("messages").toString();if(!messages.equals(lastMessages)){lastMessages=messages;messageRevision++;}}catch(Exception e){if(out!=null)file.failWrite(out);data=new JSONObject(committed);throw e;}
 }
 synchronized void erase()throws Exception{
  String account=nick();JSONArray messages=data.optJSONArray("messages");if(messages!=null)for(int i=0;i<messages.length();i++){JSONObject m=messages.optJSONObject(i);if(m!=null&&m.has("local"))try{MediaFiles.path(context,m.getString("local")).delete();}catch(Exception ignored){}}
  erased=true;file.delete();for(String name:new String[]{"history.vault","legacy-before-isolation.vault"}){File legacy=new File(context.getFilesDir(),name);if(legacy.isFile())try{JSONObject old=new JSONObject(new String(Crypto.openLocal(java.nio.file.Files.readAllBytes(legacy.toPath())),StandardCharsets.UTF_8));if(account.equals(old.optString("nick")))new AtomicFile(legacy).delete();}catch(Exception ignored){}}
  eraseTree(new File(context.getFilesDir(),"personal-stickers/"+account));new AtomicFile(new File(context.getFilesDir(),"video-library/"+account+".vault")).delete();data=empty();
 }
 static void eraseTree(File file){File[] items=file.listFiles();if(items!=null)for(File child:items)eraseTree(child);file.delete();}
 synchronized long messageRevision(){return messageRevision;}
 synchronized JSONObject copy()throws Exception{return new JSONObject(data.toString());}
 synchronized JSONObject identity()throws Exception{
  if(!data.has("identity")){data.put("identity",Crypto.identity());save();}return data.getJSONObject("identity");
 }
 synchronized String nick(){return data.optString("nick");}
 synchronized String token(){return data.optString("token");}
 synchronized void account(JSONObject reply)throws Exception{
  JSONObject u=reply.getJSONObject("user");
  if(!nick().isEmpty()&&!nick().equals(u.getString("nick")))throw new Exception(I18n.t("Данные принадлежат другому аккаунту"));
  if(!Crypto.fingerprint(u).equals(Crypto.fingerprint(identity())))throw new Exception(I18n.t("Для этого аккаунта нужна резервная копия с прежнего телефона. Восстановите её в настройках."));
  File enrollment=file.getBaseFile();data.put("nick",u.getString("nick")).put("name",u.getString("name")).put("token",reply.getString("token")).put("avatar",u.optString("avatar","preset:0")).put("bio",u.optString("bio")).put("email",u.optString("email")).put("email_verified",u.optBoolean("email_verified")).put("creator_video",u.optBoolean("creator_video")).put("moderator",u.optBoolean("moderator")).put("account_number",u.optLong("account_number")).put("registered_at",u.optLong("registered_at")).put("pending_email",u.optString("pending_email")).put("accepted_policy",u.optString("accepted_policy"));file=new AtomicFile(accountFile(nick()));save();activate();if(enrollment.getName().startsWith("enroll-"))enrollment.delete();
 }
 synchronized boolean archived(String target)throws Exception{return bucket("archived_chats").optBoolean(target);}
 synchronized void archiveChat(String target,boolean value)throws Exception{bucket("archived_chats").put(target,value);save();}
 synchronized JSONArray chatMessageIds(String peer)throws Exception{JSONArray ids=new JSONArray(),messages=data.getJSONArray("messages");for(int i=0;i<messages.length();i++){JSONObject m=messages.getJSONObject(i);if(m.optString("peer").equals(peer))ids.put(m.getString("id"));}return ids;}
 synchronized void clearChat(String peer,long through)throws Exception{
  if(through<=bucket("chat_clears").optLong(peer))return;bucket("chat_clears").put(peer,through);JSONArray old=data.getJSONArray("messages"),keep=new JSONArray();boolean newer=false;
  for(int i=0;i<old.length();i++){JSONObject m=old.getJSONObject(i);if(m.optString("peer").equals(peer)&&m.optLong("time")<=through){String id=m.optString("id");bucket("deleted_posts").put(id,true);bucket("saved").remove(id);bucket("transcripts").remove(id);bucket("reactions").remove(id);if(m.has("local"))try{MediaFiles.path(context,m.getString("local")).delete();}catch(Exception ignored){}}else{keep.put(m);if(m.optString("peer").equals(peer))newer=true;}}
  data.put("messages",keep);bucket("drafts").remove(peer);bucket("pins").remove(peer);bucket("archived_chats").remove(peer);if(!newer)bucket("hidden_chats").put(peer,true);save();
 }
 synchronized void leaveRoom(String rid)throws Exception{String peer="room:"+rid;clearChat(peer,System.currentTimeMillis());data.getJSONObject("rooms").remove(rid);bucket("channel_cursors").remove(rid);roomsRevision++;save();}
 synchronized boolean hiddenChat(String peer)throws Exception{return bucket("hidden_chats").optBoolean(peer);}
 synchronized void revealChat(String peer)throws Exception{if(bucket("hidden_chats").has(peer)){bucket("hidden_chats").remove(peer);save();}}
 synchronized boolean clearedMessage(String peer,long when)throws Exception{return when<=bucket("chat_clears").optLong(peer);}
 synchronized JSONObject peer(String nick)throws Exception{return data.getJSONObject("contacts").optJSONObject(nick);}
 synchronized void pin(JSONObject peer)throws Exception{
  String n=peer.getString("nick");JSONObject old=peer(n);
  if(old!=null&&!Crypto.fingerprint(old).equals(Crypto.fingerprint(peer)))throw new Exception(I18n.t("Ключ @")+n+I18n.t(" изменился. Передача остановлена."));
  if(old==null||!old.toString().equals(peer.toString())){data.getJSONObject("contacts").put(n,peer);save();}
 }
 synchronized void profile(JSONObject u)throws Exception{data.put("name",u.getString("name")).put("avatar",u.optString("avatar","preset:0")).put("bio",u.optString("bio")).put("email",u.optString("email")).put("email_verified",u.optBoolean("email_verified")).put("creator_video",u.optBoolean("creator_video")).put("moderator",u.optBoolean("moderator")).put("account_number",u.optLong("account_number")).put("registered_at",u.optLong("registered_at")).put("pending_email",u.optString("pending_email")).put("accepted_policy",u.optString("accepted_policy"));save();}
 synchronized void rooms(JSONArray rooms)throws Exception{JSONObject next=new JSONObject();for(int i=0;i<rooms.length();i++){JSONObject r=rooms.getJSONObject(i);next.put(r.getString("id"),r);}data.put("rooms",next);save();roomsRevision++;}
 synchronized JSONObject room(String id)throws Exception{return data.getJSONObject("rooms").optJSONObject(id);}
 synchronized void putRoom(JSONObject r)throws Exception{data.getJSONObject("rooms").put(r.getString("id"),r);save();roomsRevision++;}
 synchronized long roomRevision(){return roomsRevision;}
 synchronized void syncRooms(JSONArray rooms,long expected)throws Exception{if(expected==roomsRevision)rooms(rooms);}
 synchronized void queue(String to,String text)throws Exception{queuePayload(to,new JSONObject().put("kind","text").put("text",text),null);}
 synchronized String queuePayload(String to,JSONObject body,String local)throws Exception{
  if(!Conversation.community(to)&&isBlocked(to))throw new Exception(I18n.t("Разблокируй собеседника, чтобы написать ему"));
  revealChat(to);String id=java.util.UUID.randomUUID().toString();long now=System.currentTimeMillis();JSONObject p=new JSONObject(body.toString());
  JSONObject m=new JSONObject().put("id",id).put("peer",to).put("text",p.optString("text")).put("kind",p.optString("kind","text")).put("time",now).put("out",true).put("status","pending");
  if(local!=null)m.put("local",local);
  for(String key:new String[]{"mime","size","name","sha256","sticker","reply","link","thumb","cloud_video","round","animated","cloud_blob","blob_key","blob_iv","duration","waveform","custom_sticker","sticker_id","sticker_author"})if(p.has(key))m.put(key,p.get(key));
  JSONObject envelopes=new JSONObject();
  if(Conversation.community(to)){
   JSONObject r=room(Conversation.room(to));if(r==null)throw new Exception(I18n.t("Чат недоступен"));
   if(r.getString("kind").equals("channel")&&!r.getString("owner").equals(nick())&&!p.optString("kind").equals("control")&&!Conversation.thread(to))throw new Exception(I18n.t("Публиковать может создатель канала"));
   p.put("room",r.getString("id"));if(Conversation.thread(to))p.put("thread",Conversation.post(to));JSONArray members=r.getJSONArray("members");
   for(int i=0;i<members.length();i++){String n=members.getString(i);if(!n.equals(nick())&&(!Conversation.thread(to)||peer(n).optInt("protocol",2)>=3)&&(!p.optString("kind").equals("control")||peer(n).optInt("protocol",2)>=3))envelopes.put(n,Crypto.encrypt(nick(),n,Payload.wire(compatible(p,peer(n))),id,now,identity(),peer(n)).put("room",r.getString("id")).put("action",p.optString("kind").equals("control")?p.optString("op"):Conversation.thread(to)?"comment":"publish").put("thread",Conversation.post(to)));}
  }else if(!p.optString("kind").equals("control")||peer(to).optInt("protocol",2)>=3)envelopes.put(to,Crypto.encrypt(nick(),to,Payload.wire(p),id,now,identity(),peer(to)));
  if(p.optString("kind").equals("control")){m.put("control",p);applyControl(to,nick(),p,now);}
  m.put("envelopes",envelopes);if(envelopes.length()==0)m.put("status","delivered");
  data.getJSONArray("messages").put(m);save();return id;
 }
 synchronized boolean isDeleted(String mid,String room,String thread)throws Exception{return bucket("deleted_posts").has(mid)||bucket("deleted_posts").has(thread)||bucket("deleted_rooms").has(room);}
 synchronized void deletion(JSONObject event)throws Exception{
  String rid=event.optString("room"),mid=event.optString("mid");boolean whole=event.optString("kind").equals("room");
  if(whole){bucket("deleted_rooms").put(rid,true);data.getJSONObject("rooms").remove(rid);roomsRevision++;}else bucket("deleted_posts").put(mid,true);
  JSONArray a=data.getJSONArray("messages"),keep=new JSONArray();java.util.ArrayList<String> removed=new java.util.ArrayList<>();
  for(int i=0;i<a.length();i++){JSONObject m=a.getJSONObject(i);String peer=m.optString("peer");boolean drop=whole&&Conversation.community(peer)&&Conversation.room(peer).equals(rid)||!whole&&(m.optString("id").equals(mid)||peer.equals("thread:"+rid+":"+mid));
   if(m.optString("kind").equals("control")&&m.optJSONObject("control")!=null&&m.optJSONObject("control").optString("mid").equals(mid))drop=true;
   if(drop){removed.add(m.optString("id"));if(m.has("local"))try{MediaFiles.path(context,m.getString("local")).delete();}catch(Exception ignored){}bucket("saved").remove(m.optString("id"));bucket("transcripts").remove(m.optString("id"));bucket("reactions").remove(m.optString("id"));}
   else{JSONObject reply=m.optJSONObject("reply");if(reply!=null&&reply.optString("id").equals(mid))reply.put("text",I18n.t("Публикация удалена"));keep.put(m);}
  }
  data.put("messages",keep);for(String id:removed)bucket("deleted_posts").put(id,true);
  JSONObject pins=bucket("pins");java.util.Iterator<String> keys=pins.keys();while(keys.hasNext()){String key=keys.next();JSONObject pin=pins.optJSONObject(key);if(pin!=null&&(removed.contains(pin.optString("id"))||whole&&Conversation.community(key)&&Conversation.room(key).equals(rid)))keys.remove();}
  bucket("drafts").remove(whole?"room:"+rid:"thread:"+rid+":"+mid);if(event.has("seq"))data.put("deletion_cursor",event.getLong("seq"));save();
 }
 synchronized long deletionCursor(){return data.optLong("deletion_cursor");}
 synchronized boolean moderator(){return data.optBoolean("moderator");}
 JSONObject compatible(JSONObject p,JSONObject user)throws Exception{if(!p.optString("cloud_video").isEmpty()&&user.optInt("protocol",2)<3)return new JSONObject().put("kind","text").put("room",p.optString("room")).put("text",p.optString("text")+I18n.t("\n▶ Видео в канале. Установи обновление OldЫ Chat, чтобы посмотреть его."));return p;}
 synchronized JSONObject message(String id)throws Exception{JSONArray a=data.getJSONArray("messages");for(int i=0;i<a.length();i++)if(a.getJSONObject(i).getString("id").equals(id))return new JSONObject(a.getJSONObject(i).toString());return null;}
 synchronized void attachment(String id,String local)throws Exception{if(bucket("deleted_posts").has(id)){MediaFiles.path(context,local).delete();throw new Exception(I18n.t("Публикация удалена"));}JSONArray a=data.getJSONArray("messages");for(int i=0;i<a.length();i++){JSONObject m=a.getJSONObject(i);if(m.getString("id").equals(id)){m.put("local",local);save();return;}}throw new Exception(I18n.t("Сообщение не найдено"));}
 synchronized boolean has(String id)throws Exception{JSONArray a=data.getJSONArray("messages");for(int i=0;i<a.length();i++)if(a.getJSONObject(i).getString("id").equals(id))return true;return false;}
 synchronized void receive(JSONObject envelope,JSONObject peer)throws Exception{receiveDecoded(envelope,peer,Payload.parse(Crypto.decrypt(envelope,identity(),peer)));}
 synchronized void receiveDecoded(JSONObject envelope,JSONObject peer,JSONObject p)throws Exception{receiveDecoded(envelope,peer,p,false);}
 private synchronized void receiveDecoded(JSONObject envelope,JSONObject peer,JSONObject p,boolean channelHistory)throws Exception{
  if(!envelope.getString("to").equals(nick())||!envelope.getString("from").equals(peer.getString("nick")))throw new Exception(I18n.t("Неверный получатель"));
  if(has(envelope.getString("id"))||isDeleted(envelope.getString("id"),p.optString("room"),p.optString("thread")))return;
  String k=p.optString("kind","text");if(k.equals("signal"))throw new Exception(I18n.t("Неверное сообщение"));
  String room=p.optString("room");String target=room.isEmpty()?peer.getString("nick"):p.optString("thread").isEmpty()?"room:"+room:"thread:"+room+":"+p.optString("thread");
  if(room.isEmpty()&&clearedMessage(target,envelope.getLong("time")))return;
  revealChat(target);
  if(!room.isEmpty()){
   JSONObject r=room(room);if(r==null)throw new Exception(I18n.t("Чат недоступен"));
   boolean member=false;JSONArray members=r.getJSONArray("members");for(int i=0;i<members.length();i++)if(members.getString(i).equals(peer.getString("nick")))member=true;
   if((!member&&!channelHistory)||(r.getString("kind").equals("channel")&&!r.getString("owner").equals(peer.getString("nick"))&&!k.equals("control")&&p.optString("thread").isEmpty()))throw new Exception(I18n.t("Автор не может писать в этот чат"));
  }
  if(envelope.has("room")&&!envelope.optString("room").equals(room))throw new Exception(I18n.t("Неверный маршрут"));
  if(envelope.has("action")&&!envelope.optString("action").equals(k.equals("control")?p.optString("op"):p.optString("thread").isEmpty()?"publish":"comment"))throw new Exception(I18n.t("Неверное действие"));
  if(envelope.has("thread")&&!envelope.optString("thread").equals(p.optString("thread")))throw new Exception(I18n.t("Неверная ветка комментариев"));
  if(room.isEmpty()&&isBlocked(peer.getString("nick")))return;
  if(k.equals("control"))applyControl(target,peer.getString("nick"),p,envelope.getLong("time"));
  JSONObject m=new JSONObject().put("id",envelope.getString("id")).put("peer",target).put("from",peer.getString("nick")).put("text",p.optString("text")).put("kind",k).put("time",envelope.getLong("time")).put("out",false).put("status","received");
  if(k.equals("file")){
   if(p.optBoolean("custom_sticker")&&(!p.optString("mime").equals("image/webp")||p.optLong("size")>350000||!p.optString("sticker_id").matches("[a-f0-9-]{36}")||!p.optString("sticker_author").equals(peer.getString("nick"))))throw new Exception(I18n.t("Неверный авторский стикер"));
   if(!p.optString("mime").matches("image/(jpeg|png|webp)|video/(mp4|webm)|audio/(mp4|ogg|mpeg|wav|x-wav|flac|opus|x-flac)")||p.optLong("size")<1||p.optLong("size")>(p.optString("cloud_video").matches("[a-f0-9-]{36}")?2147483648L:MediaFiles.MAX)||!p.optString("sha256").matches("[a-fA-F0-9]{64}"))throw new Exception(I18n.t("Неподдерживаемое вложение"));
   for(String key:new String[]{"mime","size","sha256"})m.put(key,p.get(key));m.put("name",p.optString("name",I18n.t("Медиа")).replaceAll("[\\r\\n/\\\\]","_").substring(0,Math.min(100,p.optString("name",I18n.t("Медиа")).length())));
  }
  if(k.equals("sticker")){int sticker=p.optInt("sticker");m.put("sticker",sticker>=100&&sticker<108?sticker:Math.max(0,Math.min(11,sticker)));}
  if(k.equals("control"))m.put("control",p);
  for(String extra:new String[]{"reply","link","thumb","cloud_video","round","animated","cloud_blob","blob_key","blob_iv","duration","waveform","custom_sticker","sticker_id","sticker_author"})if(p.has(extra))m.put(extra,p.get(extra));
  data.getJSONArray("messages").put(m);save();
 }
 synchronized long channelCursor(String rid)throws Exception{return bucket("channel_cursors").optLong(rid);}
 synchronized void channelCursor(String rid,long cursor)throws Exception{bucket("channel_cursors").put(rid,cursor);save();}
 synchronized void channelSaved(String mid)throws Exception{JSONArray messages=data.getJSONArray("messages");for(int i=0;i<messages.length();i++)if(messages.getJSONObject(i).optString("id").equals(mid)){messages.getJSONObject(i).put("channel_saved",true);save();return;}}
 synchronized void channelRecord(JSONObject record,JSONObject author)throws Exception{
  String rid=record.getString("room"),mid=record.getString("id");JSONObject r=room(rid),p=record.getJSONObject("payload");
  if(r==null||!r.optString("kind").equals("channel"))throw new Exception(I18n.t("Канал недоступен"));
  boolean subscribed=false;JSONArray members=r.getJSONArray("members");for(int i=0;i<members.length();i++)if(members.getString(i).equals(nick()))subscribed=true;if(!subscribed)throw new Exception(I18n.t("Сначала подпишитесь на канал"));
  if(isDeleted(mid,rid,p.optString("thread")))return;
  if(has(mid)){JSONArray existing=data.getJSONArray("messages");String target=p.optString("thread").isEmpty()?"room:"+rid:"thread:"+rid+":"+p.optString("thread");for(int i=0;i<existing.length();i++){JSONObject m=existing.getJSONObject(i);if(!m.optString("id").equals(mid))continue;if(!m.optString("peer").equals(target)||!(m.optBoolean("out")?nick():m.optString("from")).equals(record.getString("from")))throw new Exception(I18n.t("Публикация из другого чата"));for(String key:new String[]{"cloud_blob","blob_key","blob_iv","cloud_video","thumb"})if(p.has(key))m.put(key,p.get(key));m.put("channel_saved",true);save();break;}return;}
  JSONObject envelope=new JSONObject().put("id",mid).put("from",record.getString("from")).put("to",nick()).put("time",record.getLong("time")).put("room",rid);
  receiveDecoded(envelope,author,p,true);JSONArray messages=data.getJSONArray("messages");
  for(int i=0;i<messages.length();i++){JSONObject m=messages.getJSONObject(i);if(m.optString("id").equals(mid)){m.put("cloud",true).put("channel_saved",true);if(record.getString("from").equals(nick())){m.put("out",true).put("status","delivered");m.remove("from");}break;}}save();
 }
 synchronized String channelLegacyLocal(JSONObject m)throws Exception{
  if(!m.optBoolean("out")||!Conversation.community(m.optString("peer")))return "";
  if(m.has("local")&&MediaFiles.path(context,m.getString("local")).isFile())return m.getString("local");
  File source=new File(context.getFilesDir(),"legacy-before-isolation.vault");if(!source.isFile())return "";
  JSONObject old=new JSONObject(new String(Crypto.openLocal(java.nio.file.Files.readAllBytes(source.toPath())),StandardCharsets.UTF_8));
  if(!old.optString("nick").equals(nick())||!Crypto.fingerprint(old.getJSONObject("identity")).equals(Crypto.fingerprint(identity())))return "";
  JSONArray messages=old.getJSONArray("messages");for(int i=0;i<messages.length();i++){JSONObject prior=messages.getJSONObject(i);if(prior.optBoolean("out")&&prior.optString("id").equals(m.optString("id"))&&prior.optString("peer").equals(m.optString("peer"))&&prior.optString("sha256").equals(m.optString("sha256"))&&prior.has("local")&&MediaFiles.path(context,prior.getString("local")).isFile())return prior.getString("local");}return "";
 }
 synchronized void channelMedia(String mid,String vid,String key,String iv,String local)throws Exception{
  if(bucket("deleted_posts").has(mid))throw new Exception(I18n.t("Публикация удалена"));JSONArray messages=data.getJSONArray("messages");
  for(int i=0;i<messages.length();i++){JSONObject m=messages.getJSONObject(i);if(m.optString("id").equals(mid)){m.put("cloud_blob",vid).put("blob_key",key).put("blob_iv",iv).put("local",local).remove("registered");save();return;}}throw new Exception(I18n.t("Публикация недоступна"));
 }
 synchronized void deliveredTo(String id,String to)throws Exception{JSONArray a=data.getJSONArray("messages");for(int i=0;i<a.length();i++){JSONObject m=a.getJSONObject(i);if(!m.getString("id").equals(id))continue;JSONObject q=m.optJSONObject("envelopes");if(q!=null){q.remove(to);if(q.length()==0)m.put("status","delivered");}else{m.put("status","delivered");m.remove("envelope");}save();return;}}
 synchronized void delivered(String id)throws Exception{
  JSONArray a=data.getJSONArray("messages");for(int i=0;i<a.length();i++){JSONObject m=a.getJSONObject(i);if(m.getString("id").equals(id)){m.put("status","delivered");m.remove("envelope");save();return;}}
 }

 static final String[] REACTIONS={"👍","❤️","🔥","😂","🤯","🎮"};
 synchronized boolean isBlocked(String n){JSONArray a=data.optJSONArray("blocked");if(a!=null)for(int i=0;i<a.length();i++)if(a.optString(i).equals(n))return true;return false;}
 synchronized void blocks(JSONArray a)throws Exception{data.put("blocked",a);save();}
 synchronized JSONObject bucket(String name)throws Exception{JSONObject b=data.optJSONObject(name);if(b==null){b=new JSONObject();data.put(name,b);}return b;}
 synchronized void draft(String peer,String value)throws Exception{bucket("drafts").put(peer,value);save();}
 synchronized String draft(String peer)throws Exception{return bucket("drafts").optString(peer);}
 synchronized void bookmark(String id)throws Exception{JSONObject b=bucket("saved");if(b.has(id))b.remove(id);else b.put(id,true);save();}
 synchronized boolean saved(String id)throws Exception{return bucket("saved").has(id);}
 synchronized JSONObject reactions(String id)throws Exception{JSONObject r=bucket("reactions").optJSONObject(id);return r==null?new JSONObject():new JSONObject(r.toString());}
 synchronized String pinned(String peer)throws Exception{return bucket("pins").optJSONObject(peer)==null?"":bucket("pins").getJSONObject(peer).optString("id");}
 synchronized void applyControl(String peer,String sender,JSONObject p,long time)throws Exception{
  String op=p.optString("op"),id=p.optString("mid");if(!id.matches("[a-f0-9-]{36}"))throw new Exception(I18n.t("Неверное сообщение"));
  JSONObject target=message(id);if(target!=null&&!target.optString("peer").equals(peer))throw new Exception(I18n.t("Сообщение из другого чата"));
  if(op.equals("reaction")){
   String emoji=p.optString("emoji");if(!emoji.isEmpty()&&!java.util.Arrays.asList(REACTIONS).contains(emoji))throw new Exception(I18n.t("Неизвестная реакция"));
   JSONObject all=bucket("reactions"),r=all.optJSONObject(id);if(r==null){r=new JSONObject();all.put(id,r);}
   JSONObject old=r.optJSONObject(sender);if(old==null||old.optLong("time")<=time)r.put(sender,new JSONObject().put("emoji",emoji).put("time",time));
  }else if(op.equals("pin")||op.equals("unpin")){
   if(Conversation.community(peer)){JSONObject room=room(Conversation.room(peer));if(room==null||!room.optString("owner").equals(sender))throw new Exception(I18n.t("Закрепляет владелец чата"));}
   JSONObject pins=bucket("pins"),old=pins.optJSONObject(peer);if(old==null||old.optLong("time")<=time)pins.put(peer,new JSONObject().put("id",op.equals("unpin")?"":id).put("time",time));
  }else throw new Exception(I18n.t("Неизвестное действие"));
 }

 synchronized void storedTo(String id,String target)throws Exception{JSONArray a=data.getJSONArray("messages");for(int i=0;i<a.length();i++){JSONObject m=a.getJSONObject(i);if(m.optString("id").equals(id)){JSONObject envelopes=m.optJSONObject("envelopes");if(envelopes!=null){envelopes.remove(target);if(envelopes.length()==0)m.put("status","stored");}save();return;}}}
 synchronized void markRegistered(String id)throws Exception{JSONArray a=data.getJSONArray("messages");for(int i=0;i<a.length();i++)if(a.getJSONObject(i).optString("id").equals(id)){a.getJSONObject(i).put("registered",true);save();return;}}
 synchronized void cloudSaved(String id)throws Exception{JSONArray a=data.getJSONArray("messages");for(int i=0;i<a.length();i++){JSONObject m=a.getJSONObject(i);if(m.optString("id").equals(id)){m.put("cloud",true);save();return;}}}
 synchronized JSONObject archiveEnvelope(JSONObject m)throws Exception{JSONObject snapshot=new JSONObject(m.toString());for(String k:new String[]{"local","envelopes","envelope","cloud"})snapshot.remove(k);JSONObject p=new JSONObject().put("kind","archive").put("snapshot",snapshot);return Crypto.encrypt(nick(),nick(),Payload.wire(p),m.getString("id"),m.getLong("time"),identity(),identity());}
 synchronized void restoreSnapshot(JSONObject envelope)throws Exception{JSONObject p=Payload.parse(Crypto.decrypt(envelope,identity(),identity()));if(!p.optString("kind").equals("archive"))return;JSONObject m=p.getJSONObject("snapshot");if(!m.getString("id").equals(envelope.getString("id"))||!m.optString("peer").matches("[a-z0-9_]{3,24}|room:[a-f0-9-]{36}|thread:[a-f0-9-]{36}:[a-f0-9-]{36}"))throw new Exception(I18n.t("Неверная копия истории"));if(clearedMessage(m.optString("peer"),m.optLong("time"))||has(m.getString("id"))||isDeleted(m.getString("id"),Conversation.community(m.optString("peer"))?Conversation.room(m.optString("peer")):"",Conversation.post(m.optString("peer"))))return;for(String k:new String[]{"local","envelopes","envelope"})m.remove(k);m.put("cloud",true);if(m.optBoolean("out")&&m.optString("status").equals("pending"))m.put("status","stored");if(m.optString("kind").equals("control")&&m.has("control"))applyControl(m.optString("peer"),m.optBoolean("out")?nick():m.optString("from"),m.getJSONObject("control"),m.getLong("time"));data.getJSONArray("messages").put(m);save();}
 synchronized String keyBackup()throws Exception{return new JSONObject().put("identity",identity()).put("nick",nick()).put("name",data.optString("name")).put("contacts",new JSONObject()).put("rooms",new JSONObject()).put("messages",new JSONArray()).toString();}
 synchronized void restoreKeys(String backup,JSONObject user)throws Exception{JSONObject b=new JSONObject(backup),keys=b.getJSONObject("identity");if(!Crypto.fingerprint(keys).equals(Crypto.fingerprint(user)))throw new Exception(I18n.t("Ключи копии не совпали"));if(!nick().isEmpty()&&!nick().equals(user.getString("nick")))throw new Exception(I18n.t("На этом телефоне другой аккаунт. Сначала сохраните его резервную копию."));data.put("identity",keys);save();}
 synchronized long historyCursor(){return data.optLong("history_cursor");}
 synchronized void historyCursor(long next)throws Exception{data.put("history_cursor",next);save();}
 synchronized void restore(String plain)throws Exception{
  JSONObject next=new JSONObject(plain);JSONObject id=next.getJSONObject("identity");Crypto.fingerprint(id);id.getString("encPrivate");id.getString("sigPrivate");next.getJSONArray("messages");next.getJSONObject("contacts");if(!next.has("rooms"))next.put("rooms",new JSONObject());
  if(data.has("nick"))throw new Exception(I18n.t("Восстановление доступно до входа в аккаунт."));next.remove("token");data=next;save();
 }
 synchronized String backup()throws Exception{JSONObject c=copy();c.remove("token");JSONArray a=c.getJSONArray("messages");for(int i=0;i<a.length();i++)a.getJSONObject(i).remove("local");return c.toString();}
}
