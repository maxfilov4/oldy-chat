package chat.oldy;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.security.*;
import java.security.cert.*;
import javax.net.ssl.*;

final class Api {
 final SharedPreferences prefs;
 Api(Context c){prefs=c.getSharedPreferences("connection",0);}
 String url(){return prefs.getString("url","https://5.42.102.11:8443");}
 String pin(){return prefs.getString("pin","");}
 static String clean(String value)throws Exception{
  URI u=new URI(value.trim());if(!"https".equals(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null||!(u.getPath()==null||u.getPath().isEmpty()||u.getPath().equals("/")))throw new Exception("Введите HTTPS-адрес без пути, например https://5.42.102.11:8443");
  return value.trim().replaceAll("/+$","");
 }
 void configure(String url,String fingerprint)throws Exception{
  String p=fingerprint.replaceAll("[\\s:]","").toUpperCase(java.util.Locale.ROOT);if(!p.matches("[A-F0-9]{64}"))throw new Exception("Отпечаток SHA-256 должен содержать 64 символа.");
  prefs.edit().putString("url",clean(url)).putString("pin",p).commit();
 }
 SSLSocketFactory factory()throws Exception{
  final String expected=pin();if(expected.isEmpty())throw new Exception("Сначала подключите сервер в настройках.");
  TrustManager[] tm={new X509TrustManager(){
   public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}
   public void checkClientTrusted(X509Certificate[] c,String a)throws CertificateException{throw new CertificateException("Client certificate unsupported");}
   public void checkServerTrusted(X509Certificate[] c,String a)throws CertificateException{
    try{if(c.length==0)throw new CertificateException();c[0].checkValidity();String got=Crypto.hex(MessageDigest.getInstance("SHA-256").digest(c[0].getEncoded()));if(!MessageDigest.isEqual(Crypto.bytes(expected),Crypto.bytes(got)))throw new CertificateException("Отпечаток сервера не совпал");}catch(CertificateException e){throw e;}catch(Exception e){throw new CertificateException(e);}
   }
  }};SSLContext s=SSLContext.getInstance("TLS");s.init(null,tm,new SecureRandom());return s.getSocketFactory();
 }
 JSONObject call(String path,JSONObject body,String token)throws Exception{
  HttpsURLConnection c=(HttpsURLConnection)new URL(url()+path).openConnection();c.setSSLSocketFactory(factory());c.setConnectTimeout(10000);c.setReadTimeout(30000);c.setInstanceFollowRedirects(false);c.setRequestProperty("Accept","application/json");
  if(!token.isEmpty())c.setRequestProperty("Authorization","Bearer "+token);
  try{
   if(body!=null){c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");byte[] raw=Crypto.bytes(body.toString());c.setFixedLengthStreamingMode(raw.length);try(OutputStream o=c.getOutputStream()){o.write(raw);}}
   int status=c.getResponseCode();InputStream stream=status>=400?c.getErrorStream():c.getInputStream();String response=read(stream,2000000);JSONObject j;
   try{j=new JSONObject(response);}catch(Exception e){throw new Exception("Сервер вернул неверный ответ ("+status+")");}
   if(status!=200)throw new Exception(j.optString("error","Ошибка сервера "+status));return j;
  }finally{c.disconnect();}
 }
 static String read(InputStream in,int max)throws Exception{
  if(in==null)return "";try(InputStream s=in;ByteArrayOutputStream o=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=s.read(b))!=-1){if(o.size()+n>max)throw new IOException("Файл слишком большой");o.write(b,0,n);}return new String(o.toByteArray(),java.nio.charset.StandardCharsets.UTF_8);}
 }
}
