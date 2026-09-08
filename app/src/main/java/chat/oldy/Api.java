package chat.oldy;
import android.content.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.security.cert.*;
import javax.net.ssl.*;

final class Api {
 static final String DEFAULT_URL="https://5.42.102.11";
 static final String LEGACY_URL=DEFAULT_URL+":8443";
 static final String DEFAULT_PIN="A84186AD26B22E7D69F23D1838A9DB5A2B277A6D567D6E49249C7714153E5D4A";
 static volatile String live="";
 final SharedPreferences prefs;
 Api(Context c){prefs=c.getSharedPreferences("connection",0);}
 String url(){String u=prefs.getString("url",DEFAULT_URL);return u.equals(LEGACY_URL)?DEFAULT_URL:u;}
 String pin(){return url().equals(DEFAULT_URL)?DEFAULT_PIN:prefs.getString("pin","");}
 static String clean(String value)throws Exception{URI u=new URI(value.trim());if(!"https".equals(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null||!(u.getPath()==null||u.getPath().isEmpty()||u.getPath().equals("/")))throw new Exception("Некорректное подключение");return value.trim().replaceAll("/+$","");}
 void configure(String url,String fingerprint)throws Exception{String p=fingerprint.replaceAll("[\\s:]","").toUpperCase(java.util.Locale.ROOT);if(!p.matches("[A-F0-9]{64}"))throw new Exception("Некорректное подключение");prefs.edit().putString("url",clean(url)).putString("pin",p).commit();live="";}
 SSLSocketFactory factory()throws Exception{
  final String expected=pin();TrustManager[] tm={new X509TrustManager(){
   public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}
   public void checkClientTrusted(X509Certificate[] c,String a)throws CertificateException{throw new CertificateException();}
   public void checkServerTrusted(X509Certificate[] c,String a)throws CertificateException{try{if(c.length==0)throw new CertificateException();c[0].checkValidity();String got=Crypto.hex(MessageDigest.getInstance("SHA-256").digest(c[0].getEncoded()));if(!MessageDigest.isEqual(Crypto.bytes(expected),Crypto.bytes(got)))throw new CertificateException("Проверка подключения не пройдена");}catch(CertificateException e){throw e;}catch(Exception e){throw new CertificateException(e);}}
  }};SSLContext s=SSLContext.getInstance("TLS");s.init(null,tm,new SecureRandom());return s.getSocketFactory();
 }
 static class Failure extends Exception {final int status;Failure(int s,String m){super(m);status=s;}}
 JSONObject call(String path,JSONObject body,String token)throws Exception{
  String home=url(),primary=home.equals(DEFAULT_URL)&&!live.isEmpty()?live:home;
  try{return request(primary,path,body,token);}catch(ConnectException|SocketTimeoutException|NoRouteToHostException e){
   // Only connection failures before request transmission permit endpoint fallback.
   // Read timeouts and failures after writing a POST are deliberately not retried here.
   throw e;
  }
 }
 JSONObject request(String base,String path,JSONObject body,String token)throws Exception{
  HttpsURLConnection c=(HttpsURLConnection)new URL(base+path).openConnection();c.setSSLSocketFactory(factory());c.setConnectTimeout(18000);c.setReadTimeout(40000);c.setInstanceFollowRedirects(false);c.setRequestProperty("Accept","application/json");c.setRequestProperty("Connection","close");
  if(!token.isEmpty())c.setRequestProperty("Authorization","Bearer "+token);
  try{
   if(body!=null){c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");byte[] raw=Crypto.bytes(body.toString());c.setFixedLengthStreamingMode(raw.length);try(OutputStream o=c.getOutputStream()){o.write(raw);}}
   int status=c.getResponseCode();String response=read(status>=400?c.getErrorStream():c.getInputStream(),2000000);JSONObject j;
   try{j=new JSONObject(response);}catch(Exception e){throw new Exception("Не удалось получить ответ. Повторите позже.");}
   if(status!=200)throw new Failure(status,j.optString("error","Не удалось выполнить действие"));return j;
  }finally{c.disconnect();}
 }
 JSONObject check()throws Exception{
  if(!url().equals(DEFAULT_URL))return request(url(),"/health",null,"");
  try{JSONObject h=request(DEFAULT_URL,"/health",null,"");live=DEFAULT_URL;return h;}
  catch(ConnectException|SocketTimeoutException|NoRouteToHostException e){JSONObject h=request(LEGACY_URL,"/health",null,"");live=LEGACY_URL;return h;}
 }
 static String message(Throwable e){
  if(e instanceof Failure)return e.getMessage();
  for(Throwable t=e;t!=null;t=t.getCause())if(t instanceof SSLException||t instanceof CertificateException)return "Проверка подключения не пройдена. Проверьте дату на телефоне и обновление приложения.";
  if(e instanceof IOException)return "Нет подключения. Проверьте интернет и повторите попытку.";
  String m=e.getMessage();return m==null?"Не удалось выполнить действие":m.replaceAll("https?://\\S+","подключение").replaceAll("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b","подключение");
 }
 static String read(InputStream in,int max)throws Exception{if(in==null)return "";try(InputStream s=in;ByteArrayOutputStream o=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=s.read(b))!=-1){if(o.size()+n>max)throw new IOException("Файл слишком большой");o.write(b,0,n);}return new String(o.toByteArray(),java.nio.charset.StandardCharsets.UTF_8);}}
}
