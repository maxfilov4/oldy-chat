package chat.oldy;
import android.graphics.*;
import org.json.*;
import java.net.*;
import java.io.*;
import java.util.regex.*;
final class LinkCards {
 static final Pattern URLS=Pattern.compile("https?://[^\\s<>]+",Pattern.CASE_INSENSITIVE);
 static String first(String text){Matcher m=URLS.matcher(text);return m.find()?m.group().replaceAll("[.,!)]+$",""):"";}
 static String youtube(String raw){try{URI u=new URI(raw);String host=u.getHost();if(host==null||u.getUserInfo()!=null||u.getPort()!=-1)return "";host=host.toLowerCase(java.util.Locale.ROOT);String path=u.getPath(),id="";if(host.equals("youtu.be"))id=path.substring(1).split("/")[0];else if(host.equals("youtube.com")||host.equals("www.youtube.com")||host.equals("m.youtube.com")){if(path.startsWith("/shorts/")||path.startsWith("/embed/")||path.startsWith("/live/"))id=path.split("/")[2];else if(path.equals("/watch")&&u.getRawQuery()!=null)for(String pair:u.getRawQuery().split("&"))if(pair.startsWith("v="))id=pair.substring(2);}return id.matches("[a-zA-Z0-9_-]{11}")?id:"";}catch(Exception e){return "";}}
 static String get(String url,int max)throws Exception{HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(4000);c.setReadTimeout(4000);c.setInstanceFollowRedirects(false);try{if(c.getResponseCode()!=200)throw new IOException();return Api.read(c.getInputStream(),max);}finally{c.disconnect();}}
 static JSONObject describe(String text)throws Exception{String url=first(text);if(url.isEmpty())return null;URI u=new URI(url);if(u.getUserInfo()!=null||u.getHost()==null)return null;JSONObject card=new JSONObject().put("url",url).put("site",u.getHost()).put("title",u.getHost());String id=youtube(url);if(!id.isEmpty()){card.put("site","YouTube").put("video",id).put("title",I18n.t("Видео на YouTube"));try{JSONObject meta=new JSONObject(get("https://www.youtube.com/oembed?format=json&url="+URLEncoder.encode("https://www.youtube.com/watch?v="+id,"UTF-8"),60000));card.put("title",meta.optString("title",I18n.t("Видео на YouTube"))).put("author",meta.optString("author_name"));}catch(Exception ignored){}}return card;}
 static Bitmap thumbnail(String id)throws Exception{if(!id.matches("[a-zA-Z0-9_-]{11}"))return null;HttpURLConnection c=(HttpURLConnection)new URL("https://i.ytimg.com/vi/"+id+"/hqdefault.jpg").openConnection();c.setConnectTimeout(5000);c.setReadTimeout(5000);c.setInstanceFollowRedirects(false);try{if(c.getResponseCode()!=200)return null;try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(out.size()+n>500000)return null;out.write(buf,0,n);}byte[] b=out.toByteArray();return BitmapFactory.decodeByteArray(b,0,b.length);}}finally{c.disconnect();}}
}
