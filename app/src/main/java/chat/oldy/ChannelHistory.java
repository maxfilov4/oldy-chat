package chat.oldy;

import org.json.*;
import java.io.*;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Signed channel publications, including history for people who subscribe later.
 * The authenticated server encrypts this shared archive at rest. Personal chats
 * and group envelopes retain their separate end-to-end encrypted history. */
final class ChannelHistory {
 static final String DOMAIN="oldy-channel-v1\n";
 static final String[] FIELDS={"kind","text","mime","size","name","sha256","sticker","reply","link","thumb","cloud_video","round","animated","cloud_blob","blob_key","blob_iv","duration","waveform","op","mid","emoji"};
 static JSONObject signed(Vault vault,JSONObject m)throws Exception{
  JSONObject source=m.optString("kind").equals("control")?m.getJSONObject("control"):m,p=new JSONObject();
  for(String k:FIELDS)if(source.has(k))p.put(k,source.get(k));
  String target=m.getString("peer");p.put("room",Conversation.room(target));if(Conversation.thread(target))p.put("thread",Conversation.post(target));
  String raw=new JSONObject().put("v",1).put("id",m.getString("id")).put("room",Conversation.room(target)).put("from",vault.nick()).put("time",m.getLong("time")).put("payload",p).toString();
  Signature sig=Signature.getInstance("SHA256withECDSA");sig.initSign(KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Crypto.un64(vault.identity().getString("sigPrivate")))));sig.update(Crypto.bytes(DOMAIN+raw));
  return new JSONObject().put("record",raw).put("signature",Crypto.b64(sig.sign()));
 }
 static void accept(Vault vault,JSONObject item,JSONObject author,String rid)throws Exception{
  String raw=item.getString("record");JSONObject record=new JSONObject(raw);
  if(record.getInt("v")!=1||!record.getString("from").equals(author.getString("nick"))||!record.getString("room").equals(rid)||!record.getJSONObject("payload").optString("room").equals(rid))throw new GeneralSecurityException("Неверный автор или канал");
  Signature signature=Signature.getInstance("SHA256withECDSA");signature.initVerify(KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Crypto.un64(author.getString("sig")))));signature.update(Crypto.bytes(DOMAIN+raw));
  if(!signature.verify(Crypto.un64(item.getString("signature"))))throw new GeneralSecurityException("Подпись публикации не совпала");
  vault.channelRecord(record,author);
 }
 static boolean belongs(Vault vault,JSONObject m)throws Exception{
  String peer=m.optString("peer");if(!m.optBoolean("out")||!Conversation.community(peer))return false;
  JSONObject room=vault.room(Conversation.room(peer));return room!=null&&room.optString("kind").equals("channel");
 }
 static synchronized void publish(Api api,Vault vault,JSONObject message)throws Exception{
  message=vault.message(message.getString("id"));if(message==null)return;
  if(!belongs(vault,message)||message.optBoolean("channel_saved"))return;
  JSONObject m=migrateMedia(api,vault,message);
  try{api.call("/channels/history/store",signed(vault,m),vault.token());vault.channelSaved(m.getString("id"));}
  catch(Api.Failure error){if(error.status==410&&!m.optString("cloud_blob").isEmpty())CloudMedia.cancel(api,vault,m.optString("cloud_blob"));throw error;}
 }
 static JSONObject migrateMedia(Api api,Vault vault,JSONObject m)throws Exception{
  if(!m.optString("kind").equals("file")||!m.optString("cloud_blob").isEmpty()||!m.optString("cloud_video").isEmpty())return m;
  String local=vault.channelLegacyLocal(m);if(local.isEmpty())return m;
  byte[] plain=MediaFiles.bytes(vault.context,local);
  if(plain.length!=m.optLong("size")||!Crypto.hex(MessageDigest.getInstance("SHA-256").digest(plain)).equalsIgnoreCase(m.optString("sha256")))throw new IOException("Проверка старого вложения не пройдена");
  byte[] aes=Crypto.random(32),iv=Crypto.random(12);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(aes,"AES"),new GCMParameterSpec(128,iv));byte[] encrypted=cipher.doFinal(plain);Arrays.fill(plain,(byte)0);
  String vid=CloudMedia.upload(api,vault,m.getString("peer"),encrypted,"blob");
  try{vault.channelMedia(m.getString("id"),vid,Crypto.b64(aes),Crypto.b64(iv),local);return vault.message(m.getString("id"));}
  catch(Exception error){CloudMedia.cancel(api,vault,vid);throw error;}finally{Arrays.fill(aes,(byte)0);}
 }
 static void syncRoom(Api api,Vault vault,String rid,int pages)throws Exception{
  JSONObject room=vault.room(rid);if(room==null||!room.optString("kind").equals("channel"))return;
  for(int page=0;page<pages;page++){
   JSONObject reply=api.call("/channels/history?room="+rid+"&after="+vault.channelCursor(rid),null,vault.token());JSONArray items=reply.getJSONArray("items");
   for(int i=0;i<items.length();i++){
    JSONObject item=items.getJSONObject(i),record=new JSONObject(item.getString("record"));String from=record.getString("from");JSONObject author=api.call("/user/"+from,null,vault.token());
    if(!from.equals(vault.nick()))vault.pin(author);accept(vault,item,author,rid);vault.channelCursor(rid,item.getLong("seq"));
   }
   if(!reply.optBoolean("more"))break;
  }
 }
}
