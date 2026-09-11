package chat.oldy;
import android.graphics.*;
import org.json.*;
import java.net.*;
import java.io.*;
import java.util.regex.*;
final class LinkCards {
 static final Pattern URLS=Pattern.compile("https?://[^\\s<>]+",Pattern.CASE_INSENSITIVE);
 static String first(String text){Matcher m=URLS.matcher(text);if(m.find())return m.group().replaceAll("[.,!)]+$","");YouTubeLinkDetector.Target t=YouTubeLinkDetector.first(text);return t==null?"":t.url();}
 static String youtube(String raw){YouTubeLinkDetector.Target t=YouTubeLinkDetector.parse(raw);return t==null?"":t.videoId;}
 static String get(String url,int max)throws Exception{HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(4000);c.setReadTimeout(4000);c.setInstanceFollowRedirects(false);try{if(c.getResponseCode()!=200)throw new IOException();return Api.read(c.getInputStream(),max);}finally{c.disconnect();}}
 static JSONObject describe(String text)throws Exception{YouTubeLinkDetector.Target t=YouTubeLinkDetector.first(text);if(t!=null)return YouTubePreviewProvider.describe(t);String url=first(text);if(url.isEmpty())return null;URI u=new URI(url);if(u.getUserInfo()!=null||u.getHost()==null)return null;return new JSONObject().put("url",url).put("site",u.getHost()).put("title",u.getHost());}
 static Bitmap thumbnail(String id)throws Exception{if(!id.matches("[a-zA-Z0-9_-]{11}"))return null;HttpURLConnection c=(HttpURLConnection)new URL("https://i.ytimg.com/vi/"+id+"/hqdefault.jpg").openConnection();c.setConnectTimeout(5000);c.setReadTimeout(5000);c.setInstanceFollowRedirects(false);try{if(c.getResponseCode()!=200)return null;try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(out.size()+n>500000)return null;out.write(buf,0,n);}byte[] b=out.toByteArray();return BitmapFactory.decodeByteArray(b,0,b.length);}}finally{c.disconnect();}}
}
