package chat.oldy;
import org.json.*;
final class Payload {
 static final String PREFIX="\u001eoldy2:";
 static String wire(JSONObject p){return PREFIX+p.toString();}
 static JSONObject parse(String s)throws Exception{if(!s.startsWith(PREFIX))return new JSONObject().put("kind","text").put("text",s);JSONObject p=new JSONObject(s.substring(PREFIX.length()));String k=p.optString("kind");if(!k.matches("text|sticker|file|signal|control|archive"))throw new Exception(I18n.t("Обновите приложение, чтобы прочитать сообщение"));return p;}
 static String preview(JSONObject m){String k=m.optString("kind","text");if(k.equals("file")){String mime=m.optString("mime");return mime.startsWith("audio/")?I18n.t("🎙 Голосовое сообщение"):mime.startsWith("video/")?I18n.t("▶ Видео"):I18n.t("📷 Фото");}if(k.equals("sticker"))return "✨ "+m.optString("text",I18n.t("Анимированный эмодзи"));return m.optString("text");}
}
