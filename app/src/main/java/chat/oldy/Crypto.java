package chat.oldy;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Beta protocol: RSA-OAEP + fresh AES-GCM per message, ECDSA signatures.
 * No forward secrecy. Verify identity fingerprints out of band. */
public final class Crypto {
 static final SecureRandom RANDOM=new SecureRandom();
 static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
 static String b64(byte[] b){return Base64.encodeToString(b,Base64.NO_WRAP);}
 static byte[] un64(String s){return Base64.decode(s,Base64.NO_WRAP);}
 static byte[] random(int n){byte[] b=new byte[n];RANDOM.nextBytes(b);return b;}
 static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format("%02X",v&255));return s.toString();}
 static String fingerprint(JSONObject p)throws Exception{return hex(MessageDigest.getInstance("SHA-256").digest(bytes(p.getString("enc")+"\n"+p.getString("sig"))));}
 static JSONObject identity()throws Exception{
  KeyPairGenerator r=KeyPairGenerator.getInstance("RSA");r.initialize(3072);KeyPair enc=r.generateKeyPair();
  KeyPairGenerator e=KeyPairGenerator.getInstance("EC");e.initialize(new ECGenParameterSpec("secp256r1"));KeyPair sig=e.generateKeyPair();
  return new JSONObject().put("enc",b64(enc.getPublic().getEncoded())).put("sig",b64(sig.getPublic().getEncoded())).put("encPrivate",b64(enc.getPrivate().getEncoded())).put("sigPrivate",b64(sig.getPrivate().getEncoded()));
 }
 static JSONObject publicPart(JSONObject id)throws Exception{return new JSONObject().put("enc",id.getString("enc")).put("sig",id.getString("sig"));}
 static SecretKey vaultKey()throws Exception{
  KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);String alias="oldy-vault-v1";
  if(!ks.containsAlias(alias)){KeyGenerator g=KeyGenerator.getInstance("AES","AndroidKeyStore");g.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());g.generateKey();}
  return (SecretKey)ks.getKey(alias,null);
 }
 static byte[] sealLocal(byte[] plain)throws Exception{Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,vaultKey());byte[] out=c.doFinal(plain),iv=c.getIV(),all=new byte[12+out.length];System.arraycopy(iv,0,all,0,12);System.arraycopy(out,0,all,12,out.length);return all;}
 static byte[] openLocal(byte[] data)throws Exception{if(data.length<28)throw new GeneralSecurityException("Invalid vault");Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,vaultKey(),new GCMParameterSpec(128,data,0,12));return c.doFinal(data,12,data.length-12);}
 static String header(JSONObject e)throws Exception{return "oldy-v1\n"+e.getString("id")+"\n"+e.getString("from")+"\n"+e.getString("to")+"\n"+e.getLong("time");}
 static byte[] signedBytes(JSONObject e)throws Exception{return bytes(header(e)+"\n"+e.getString("key")+"\n"+e.getString("iv")+"\n"+e.getString("body"));}
 static JSONObject encrypt(String from,String to,String text,String id,long time,JSONObject mine,JSONObject peer)throws Exception{
  JSONObject e=new JSONObject().put("v",1).put("id",id).put("from",from).put("to",to).put("time",time);
  byte[] aes=random(32),iv=random(12);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(aes,"AES"),new GCMParameterSpec(128,iv));c.updateAAD(bytes(header(e)));
  e.put("body",b64(c.doFinal(bytes(text)))).put("iv",b64(iv));
  Cipher rsa=Cipher.getInstance("RSA/ECB/OAEPPadding");rsa.init(Cipher.ENCRYPT_MODE,KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(un64(peer.getString("enc")))),new OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256,PSource.PSpecified.DEFAULT));
  e.put("key",b64(rsa.doFinal(aes)));Signature s=Signature.getInstance("SHA256withECDSA");s.initSign(KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(un64(mine.getString("sigPrivate")))));s.update(signedBytes(e));e.put("signature",b64(s.sign()));return e;
 }
 static String decrypt(JSONObject e,JSONObject mine,JSONObject peer)throws Exception{
  if(e.getInt("v")!=1)throw new GeneralSecurityException("Unknown protocol");Signature s=Signature.getInstance("SHA256withECDSA");s.initVerify(KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(un64(peer.getString("sig")))));s.update(signedBytes(e));if(!s.verify(un64(e.getString("signature"))))throw new GeneralSecurityException(I18n.t("Подпись сообщения не совпала"));
  Cipher rsa=Cipher.getInstance("RSA/ECB/OAEPPadding");rsa.init(Cipher.DECRYPT_MODE,KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(un64(mine.getString("encPrivate")))),new OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256,PSource.PSpecified.DEFAULT));byte[] aes=rsa.doFinal(un64(e.getString("key")));
  Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(aes,"AES"),new GCMParameterSpec(128,un64(e.getString("iv"))));c.updateAAD(bytes(header(e)));return new String(c.doFinal(un64(e.getString("body"))),StandardCharsets.UTF_8);
 }
 static SecretKey backupKey(char[] password,byte[] salt)throws Exception{PBEKeySpec spec=new PBEKeySpec(password,salt,310000,256);try{return new SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(),"AES");}finally{spec.clearPassword();}}
 static String exportBackup(String data,char[] password)throws Exception{byte[] salt=random(16),iv=random(12);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,backupKey(password,salt),new GCMParameterSpec(128,iv));c.updateAAD(bytes("oldy-backup-v1"));return new JSONObject().put("format","oldy-backup-v1").put("salt",b64(salt)).put("iv",b64(iv)).put("data",b64(c.doFinal(bytes(data)))).toString();}
 static String importBackup(String data,char[] password)throws Exception{JSONObject j=new JSONObject(data);if(!j.getString("format").equals("oldy-backup-v1"))throw new GeneralSecurityException(I18n.t("Неизвестный формат копии"));Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,backupKey(password,un64(j.getString("salt"))),new GCMParameterSpec(128,un64(j.getString("iv"))));c.updateAAD(bytes("oldy-backup-v1"));return new String(c.doFinal(un64(j.getString("data"))),StandardCharsets.UTF_8);}
}
