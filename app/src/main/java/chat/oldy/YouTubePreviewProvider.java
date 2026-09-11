package chat.oldy;
import org.json.JSONObject;
import android.graphics.Bitmap;
import java.net.URLEncoder;

/** Metadata and images are fetched on the viewer's connection, never by Api/backend. */
final class YouTubePreviewProvider {
 static final android.util.LruCache<String,JSONObject> cache=new android.util.LruCache<>(100);
 static JSONObject describe(YouTubeLinkDetector.Target t){JSONObject j=new JSONObject();try{j.put("url",t.url()).put("site","YouTube").put("video",t.videoId).put("playlist",t.playlistId).put("title",I18n.t(t.videoId.isEmpty()?"Плейлист YouTube":"Видео на YouTube"));}catch(Exception ignored){}return j;}
 static JSONObject metadata(String id)throws Exception{if(!YouTubeLinkDetector.video(id))return new JSONObject();JSONObject found=cache.get(id);if(found!=null)return found;JSONObject result=new JSONObject(LinkCards.get("https://www.youtube.com/oembed?format=json&url="+URLEncoder.encode("https://www.youtube.com/watch?v="+id,"UTF-8"),60000));cache.put(id,result);return result;}
 static Bitmap thumbnail(String id)throws Exception{return LinkCards.thumbnail(id);}
}
