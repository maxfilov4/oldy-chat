package chat.oldy;

import android.app.Instrumentation;
import android.os.Bundle;
import org.json.*;
import java.util.concurrent.*;

public class CryptoInstrumentation extends Instrumentation {
 Bundle args;
 public void onCreate(Bundle args){this.args=args;start();}
 void require(boolean b,String message)throws Exception{if(!b)throw new Exception(message);}
 public void onStart(){Bundle result=new Bundle();try{
  JSONObject alice=Crypto.identity(),bob=Crypto.identity();String plain="Привет, OldЫ! 😎 💚";
  JSONObject e=Crypto.encrypt("alice","bobby",plain,java.util.UUID.randomUUID().toString(),System.currentTimeMillis(),alice,bob);
  require(Crypto.decrypt(e,bob,alice).equals(plain),"Message roundtrip");
  JSONObject modified=new JSONObject(e.toString()).put("to","eve");boolean rejected=false;try{Crypto.decrypt(modified,bob,alice);}catch(Exception expected){rejected=true;}require(rejected,"Tampered envelope accepted");
  rejected=false;try{Crypto.decrypt(e,alice,alice);}catch(Exception expected){rejected=true;}require(rejected,"Wrong private key accepted");
  byte[] local=Crypto.sealLocal(Crypto.bytes(plain));require(new String(Crypto.openLocal(local),"UTF-8").equals(plain),"Local vault roundtrip");local[local.length-1]^=1;rejected=false;try{Crypto.openLocal(local);}catch(Exception expected){rejected=true;}require(rejected,"Tampered vault accepted");
  String backup=Crypto.exportBackup(plain,"long backup password".toCharArray());require(Crypto.importBackup(backup,"long backup password".toCharArray()).equals(plain),"Backup roundtrip");rejected=false;try{Crypto.importBackup(backup,"incorrect password".toCharArray());}catch(Exception expected){rejected=true;}require(rejected,"Wrong backup password accepted");
  Api api=new Api(getContext());api.configure("https://10.0.2.2:8444",args.getString("pin"));
  JSONObject a=FixturesInstrumentation.register(api,new JSONObject().put("nick","alice").put("password","correct battery staple").put("name","Алиса").put("enc",alice.getString("enc")).put("sig",alice.getString("sig")),"");
  JSONObject b=FixturesInstrumentation.register(api,new JSONObject().put("nick","bobby").put("password","correct battery staple").put("name","Борис").put("enc",bob.getString("enc")).put("sig",bob.getString("sig")),"");
  ExecutorService pool=Executors.newFixedThreadPool(2);
  try{
   Future<JSONObject> receive=pool.submit(()->api.call("/poll",null,b.getString("token")));
   Thread.sleep(300);
   Future<JSONObject> send=pool.submit(()->api.call("/send",e,a.getString("token")));
   JSONObject got=receive.get(25,TimeUnit.SECONDS).getJSONArray("messages").getJSONObject(0);require(Crypto.decrypt(got,bob,alice).equals(plain),"TLS relay roundtrip");
   api.call("/ack",new JSONObject().put("from","alice").put("id",e.getString("id")),b.getString("token"));require(send.get(25,TimeUnit.SECONDS).getBoolean("stored"),"Durable storage acknowledgement");require(api.call("/receipts",new JSONObject().put("ids",new JSONArray().put(e.getString("id"))),a.getString("token")).getJSONArray("delivered").length()==1,"Recipient acknowledgement");
  }finally{pool.shutdownNow();}
  api.configure("https://10.0.2.2:8444",new String(new char[64]).replace('\0','0'));rejected=false;try{api.call("/me",null,a.getString("token"));}catch(Exception expected){rejected=true;}require(rejected,"Wrong certificate pin accepted");
  // Emulator-only fixtures for testing real activity layouts and encrypted persistence.
  Vault v=ChatService.vault(getTargetContext());
  synchronized(v){v.data.put("identity",alice);v.account(a);v.pin(b.getJSONObject("user"));}
  JSONObject incoming=Crypto.encrypt("bobby","alice","Привет! Уже проверяю наш чат 😎",java.util.UUID.randomUUID().toString(),System.currentTimeMillis(),bob,alice);
  v.receive(incoming,b.getJSONObject("user"));v.receive(incoming,b.getJSONObject("user"));require(v.copy().getJSONArray("messages").length()==1,"Duplicate persisted twice");
  v.queue("bobby","Привет! Как тебе OldЫ Chat? 💚");String fixtureId=v.copy().getJSONArray("messages").getJSONObject(1).getString("id");v.delivered(fixtureId);
  Vault reopened=new Vault(getTargetContext());require(reopened.copy().getJSONArray("messages").length()==2,"Saved vault not recovered");
  new Api(getTargetContext()).configure("https://10.0.2.2:8444",args.getString("pin"));
  result.putString("stream","OLDY_CRYPTO_PASS: local vault, authenticated encryption, tamper rejection, backup, pinned TLS, actual relay delivery\n");finish(-1,result);
 }catch(Throwable e){result.putString("stream","OLDY_CRYPTO_FAIL: "+e.toString()+"\n"+android.util.Log.getStackTraceString(e));finish(0,result);}}
}
