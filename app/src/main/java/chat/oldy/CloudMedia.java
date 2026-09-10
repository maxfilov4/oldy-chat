package chat.oldy;
import android.content.*;import android.net.Uri;import org.json.*;import java.io.*;import java.net.*;import javax.net.ssl.*;import javax.crypto.*;import javax.crypto.spec.*;import java.util.*;
/** Small attachments are encrypted before upload; only participants receive the key. */
final class CloudMedia {
 static void attach(MainActivity a,Uri uri,String mime,String name,String target,String caption)throws Exception{attach(a,uri,mime,name,target,caption,null);}
 static void attach(MainActivity a,Uri uri,String mime,String name,String target,String caption,JSONObject extra)throws Exception{
  Vault vault=a.vault;String vid="";boolean queued=false;JSONObject body=MediaFiles.importFile(a,uri,mime,name);String key=body.getString("local");if(extra!=null)for(String field:new String[]{"duration","waveform","custom_sticker","sticker_id","sticker_author"})if(extra.has(field))body.put(field,extra.get(field));
  try{
   byte[] plain=MediaFiles.bytes(a,key),aes=Crypto.random(32),iv=Crypto.random(12);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(aes,"AES"),new GCMParameterSpec(128,iv));byte[] encrypted=c.doFinal(plain);Arrays.fill(plain,(byte)0);
   vid=upload(a.api,vault,target,encrypted,"blob");body.put("cloud_blob",vid).put("blob_key",Crypto.b64(aes)).put("blob_iv",Crypto.b64(iv)).put("text",caption);Arrays.fill(aes,(byte)0);
   if(mime.startsWith("image/"))body.put("thumb",a.makeThumb(key));body.remove("local");if(a.vault!=vault)throw new IOException(I18n.t("Аккаунт изменился"));a.prepareRoom(target);String mid=vault.queuePayload(target,body,key);queued=true;registerLater(a.api,vault,mid,target);
   a.runOnUiThread(()->{if(a.vault==vault&&a.chat.equals(target)){if(a.compose!=null&&a.compose.getText().toString().equals(caption))a.compose.setText("");a.renderMessages();}});
  }catch(Exception e){if(!queued){MediaFiles.path(a,key).delete();cancel(a.api,vault,vid);}throw e;}
 }
 static String upload(Api api,Vault vault,String target,byte[] bytes,String kind)throws Exception{
  JSONObject request=new JSONObject().put("kind",kind).put("name",I18n.t("Медиа.mp4")).put("room",Conversation.community(target)?Conversation.room(target):"").put("recipient",Conversation.community(target)?"":target).put("thread",Conversation.post(target)).put("size",bytes.length);
  String vid=api.call("/videos/start",request,vault.token()).getString("id");try{for(int at=0;at<bytes.length;at+=524288){int length=Math.min(524288,bytes.length-at);byte[] chunk=Arrays.copyOfRange(bytes,at,at+length);CreatorVideo.chunk(api,vault.token(),vid,at,chunk,length);}api.call("/videos/finish",new JSONObject().put("id",vid),vault.token());return vid;}catch(Exception e){try{api.call("/videos/cancel",new JSONObject().put("id",vid),vault.token());}catch(Exception ignored){}throw e;}
 }
 static void download(Context context,Vault vault,JSONObject message)throws Exception{
  String id=message.getString("cloud_blob");if(!id.matches("[a-f0-9-]{36}"))throw new IOException();Api api=new Api(context);String base=Api.live.isEmpty()?api.url():Api.live;HttpsURLConnection c=(HttpsURLConnection)new URL(base+"/video-stream/"+id).openConnection();c.setSSLSocketFactory(api.factory());c.setInstanceFollowRedirects(false);c.setConnectTimeout(15000);c.setReadTimeout(25000);c.setRequestProperty("Authorization","Bearer "+vault.token());
  byte[] encrypted;try{if(c.getResponseCode()!=200)throw new IOException(I18n.t("Вложение недоступно или удалено"));try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1){if(out.size()+n>MediaFiles.MAX+16)throw new IOException(I18n.t("Неверный размер"));out.write(b,0,n);}encrypted=out.toByteArray();}}finally{c.disconnect();}
  Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(Crypto.un64(message.getString("blob_key")),"AES"),new GCMParameterSpec(128,Crypto.un64(message.getString("blob_iv"))));byte[] plain=cipher.doFinal(encrypted);if(plain.length!=message.getLong("size"))throw new IOException(I18n.t("Размер не совпал"));String mid=message.getString("id");
  try(MediaFiles.Writer w=new MediaFiles.Writer(context,mid)){for(int at=0;at<plain.length;at+=16384)w.put(Arrays.copyOfRange(plain,at,Math.min(plain.length,at+16384)));String hash=w.finish();if(!hash.equalsIgnoreCase(message.getString("sha256"))){MediaFiles.path(context,mid).delete();throw new IOException(I18n.t("Проверка вложения не пройдена"));}vault.attachment(mid,mid);}finally{Arrays.fill(plain,(byte)0);}
 }
 static void cancel(Api api,Vault vault,String id){if(!id.isEmpty())try{api.call("/videos/cancel",new JSONObject().put("id",id),vault.token());}catch(Exception ignored){}}
 static void registerLater(Api api,Vault vault,String mid,String target){try{ChatService.register(api,vault,vault.message(mid));vault.markRegistered(mid);if(target.startsWith("room:")){JSONObject room=vault.room(Conversation.room(target));if(room!=null&&room.optString("kind").equals("channel"))api.call("/threads",new JSONObject().put("room",Conversation.room(target)).put("post",mid),vault.token());}}catch(Exception deferred){/* Pending message is retried by its account's delivery service. */}}
 static void round(MainActivity a,File file,String target)throws Exception{
  Vault vault=a.vault;String vid="";boolean queued=false;
  try{byte[] bytes=java.nio.file.Files.readAllBytes(file.toPath());if(bytes.length>33554432)throw new IOException(I18n.t("Кружок слишком большой"));vid=upload(a.api,vault,target,bytes,"round");
   JSONObject p=new JSONObject().put("kind","file").put("round",true).put("mime","video/mp4").put("name",I18n.t("Видеокружок")).put("size",bytes.length).put("sha256",Crypto.hex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))).put("cloud_video",vid).put("thumb",CreatorVideo.thumbnail(a,Uri.fromFile(file)));
   if(a.vault!=vault)throw new IOException(I18n.t("Аккаунт изменился"));a.prepareRoom(target);String mid=vault.queuePayload(target,p,null);queued=true;registerLater(a.api,vault,mid,target);a.runOnUiThread(()->{if(a.vault==vault&&a.chat.equals(target))a.renderMessages();});
  }catch(Exception e){if(!queued)cancel(a.api,vault,vid);throw e;}finally{file.delete();}
 }
}
