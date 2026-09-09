package chat.oldy;
import android.app.*;import android.os.*;import android.content.*;import android.graphics.*;import org.json.*;import java.io.*;import java.util.*;

/** Independent subscriber joins after publication, verifies signatures and retrieves old media. */
public class ChannelInstrumentation extends Instrumentation {
 public void onCreate(Bundle b){start();}
 void check(boolean condition,String why)throws Exception{if(!condition)throw new Exception(why);}
 public void onStart(){Bundle result=new Bundle();try{
  MainActivity a=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Vault owner=a.vault;Api api=a.api;
  JSONObject room=api.call("/room/create",new JSONObject().put("kind","channel").put("title","История канала").put("public",true).put("members",new JSONArray()),owner.token());String rid=room.getString("id");owner.putRoom(room);
  String first=owner.queuePayload("room:"+rid,new JSONObject().put("kind","text").put("text","Эта новость опубликована до подписки"),null);ChannelHistory.publish(api,owner,owner.message(first));
  Bitmap picture=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(picture);canvas.drawColor(Color.rgb(40,120,190));Paint paint=new Paint();paint.setColor(Color.YELLOW);canvas.drawCircle(64,64,30,paint);ByteArrayOutputStream bytes=new ByteArrayOutputStream();picture.compress(Bitmap.CompressFormat.JPEG,80,bytes);picture.recycle();byte[] photo=bytes.toByteArray();String local=UUID.randomUUID().toString(),sha;
  try(MediaFiles.Writer writer=new MediaFiles.Writer(a,local)){writer.put(photo);sha=writer.finish();}
  String second=owner.queuePayload("room:"+rid,new JSONObject().put("kind","file").put("mime","image/jpeg").put("name","Ранее.jpg").put("size",photo.length).put("sha256",sha).put("text","Фото из прежних публикаций"),local);ChannelHistory.publish(api,owner,owner.message(second));
  File folder=new File(a.getFilesDir(),"new-subscriber-"+UUID.randomUUID());folder.mkdirs();Context context=new ContextWrapper(a){public File getFilesDir(){return folder;}};
  Vault viewer=new Vault(context,"history_viewer",true);JSONObject identity=viewer.identity();JSONObject enrolled=FixturesInstrumentation.register(api,new JSONObject().put("nick","history_viewer").put("name","Новый подписчик").put("password","confirmed history password").put("enc",identity.getString("enc")).put("sig",identity.getString("sig")),"");viewer.account(enrolled);
  boolean denied=false;try{api.call("/channels/history?room="+rid,null,viewer.token());}catch(Api.Failure expected){denied=expected.status==404;}check(denied,"Nonmember read channel archive");
  viewer.putRoom(api.call("/room/join",new JSONObject().put("id",rid),viewer.token()));ChannelHistory.syncRoom(api,viewer,rid,8);
  check(viewer.has(first)&&viewer.has(second),"New subscriber did not receive older publications");check(viewer.message(first).optString("text").contains("до подписки")&&!viewer.message(first).optBoolean("out"),"Old post imported under wrong author");
  CloudMedia.download(context,viewer,viewer.message(second));check(Arrays.equals(photo,MediaFiles.bytes(context,viewer.message(second).getString("local"))),"Old attachment requires publisher phone or differs");
  JSONObject ownerUser=api.call("/user/"+owner.nick(),null,viewer.token());viewer.pin(ownerUser);String comment=viewer.queuePayload("thread:"+rid+":"+first,new JSONObject().put("kind","text").put("text","Вижу прежнюю новость"),null);ChannelHistory.publish(api,viewer,viewer.message(comment));ChannelHistory.syncRoom(api,owner,rid,8);check(owner.has(comment),"Historical post comments are not synchronized");
  JSONObject forged=ChannelHistory.signed(owner,owner.message(first));forged.put("record",forged.getString("record").replace("до подписки","подделка"));denied=false;try{ChannelHistory.accept(viewer,forged,ownerUser,rid);}catch(Exception expected){denied=true;}check(denied,"Subscriber accepted forged channel history");
  JSONObject removed=api.call("/posts/delete",new JSONObject().put("room",rid).put("mid",second),owner.token());owner.deletion(removed);viewer.deletion(removed);viewer.channelCursor(rid,0);ChannelHistory.syncRoom(api,viewer,rid,8);check(!viewer.has(second)&&viewer.has(first),"Deleted historical photo resurrected");
  JSONObject all=api.call("/room/delete",new JSONObject().put("id",rid),owner.token());owner.deletion(all);viewer.deletion(all);
  result.putString("stream","OLDY_CHANNEL_PASS: later subscriber sees earlier signed posts, encrypted-at-rest archive, sender-independent migrated photo download, comments, signature rejection and deletion without resurrection\n");finish(-1,result);
 }catch(Throwable error){result.putString("stream","OLDY_CHANNEL_FAIL: "+android.util.Log.getStackTraceString(error));finish(0,result);}}
}
