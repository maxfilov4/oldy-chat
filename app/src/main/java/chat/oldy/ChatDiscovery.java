package chat.oldy;

import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Shared search field: local results now, public directory after a short debounce. */
final class ChatDiscovery {
 final MainActivity a;
 final ExecutorService network=Executors.newFixedThreadPool(2);
 final LinkedHashMap<String,JSONObject> cache=new LinkedHashMap<>();
 Runnable pending;
 int generation;
 String query="",error="";
 JSONObject result;
 boolean loading;
 ChatDiscovery(MainActivity a){this.a=a;}
 static String normalized(String text){return text.toLowerCase(Locale.ROOT).replace('ё','е').trim().replaceAll("\\s+"," ");}
 static boolean matches(String value,String query){String text=normalized(value);for(String word:normalized(query).replaceFirst("^@","").split(" "))if(!text.contains(word))return false;return true;}
 void changed(){
  if(pending!=null)a.ui.removeCallbacks(pending);
  final int seq=++generation;final Vault account=a.vault;
  query=normalized(a.search);final String requested=query;error="";
  result=cache.get(account.nick()+":"+query);loading=!query.isEmpty();
  if(query.isEmpty()){loading=false;result=null;a.renderChats();return;}
  a.renderChats();
  pending=()->network.execute(()->{
   try{
    JSONObject found=a.api.call("/search?q="+java.net.URLEncoder.encode(requested,"UTF-8"),null,account.token());
    a.runOnUiThread(()->{if(a.vault!=account||seq!=generation)return;cache.put(account.nick()+":"+requested,found);if(cache.size()>24)cache.remove(cache.keySet().iterator().next());result=found;loading=false;error="";a.renderChats();});
   }catch(Exception ex){a.runOnUiThread(()->{if(a.vault!=account||seq!=generation)return;loading=false;error=Api.message(ex);a.renderChats();});}
  });
  a.ui.postDelayed(pending,160);
 }
 void render(JSONObject local,Set<String> shown)throws Exception{
  if(query.isEmpty()||!query.equals(normalized(a.search)))return;
  LinearLayout box=a.content;a.space(box,12);
  TextView heading=a.label(I18n.t("ГЛОБАЛЬНЫЙ ПОИСК"),11,a.GREEN);heading.setTypeface(null,1);box.addView(heading);a.space(box,10);
  int total=0;
  if(result!=null){
   JSONArray users=result.optJSONArray("users"),rooms=result.optJSONArray("rooms");
   if(users!=null&&(a.filter==0||a.filter==1))for(int i=0;i<users.length();i++){
    JSONObject u=users.getJSONObject(i);String nick=u.getString("nick");if(shown.contains(nick))continue;total++;
    final Vault owner=a.vault;box.addView(a.searchCard(u.optString("avatar","preset:0"),u.optString("name"),"@"+nick,I18n.t("ЧЕЛОВЕК"),()->a.task(()->{owner.pin(u);owner.revealChat(nick);a.runOnUiThread(()->{if(a.vault==owner)a.openChat(nick);});})));a.space(box,9);
   }
   if(rooms!=null&&a.filter!=1)for(int i=0;i<rooms.length();i++){
    JSONObject r=rooms.getJSONObject(i);String kind=r.optString("kind"),id="room:"+r.getString("id");
    if(a.filter==2&&!kind.equals("group")||a.filter==3&&!kind.equals("channel")||shown.contains(id))continue;total++;
    box.addView(a.searchCard(r.optString("avatar","preset:0"),r.optString("title"),"@"+r.optString("handle"),kind.equals("channel")?I18n.t("КАНАЛ"):I18n.t("ГРУППА"),()->openRoom(r)));a.space(box,9);
   }
  }
  if(loading)a.paragraph(box,I18n.t("Ищем…"));
  else if(!error.isEmpty()){
   a.paragraph(box,I18n.t("Поиск по серверу недоступен: ")+error);a.space(box,8);box.addView(a.button(I18n.t("Повторить поиск"),false,this::changed));
  }else if(total==0)a.paragraph(box,shown.isEmpty()?I18n.t("Ничего не найдено. Попробуй часть названия или @адрес."):I18n.t("Все найденные чаты уже в твоём списке."));
 }
 void openRoom(JSONObject r){
  final Vault account=a.vault;Runnable join=()->a.task(()->{
   if(a.vault!=account)return;String id=r.getString("id");
   JSONObject room=a.api.call(r.optBoolean("joined")?"/room/"+id:"/room/join",r.optBoolean("joined")?null:new JSONObject().put("id",id),account.token());
   account.putRoom(room);account.revealChat("room:"+id);a.runOnUiThread(()->{if(a.vault==account)a.openChat("room:"+id);});
  });
  if(r.optBoolean("joined"))join.run();else{LinearLayout b=a.col();a.paragraph(b,"@"+r.optString("handle"));a.space(b,14);b.addView(a.button(r.optString("kind").equals("channel")?I18n.t("Подписаться и открыть"):I18n.t("Присоединиться"),true,join));a.sheet(r.optString("title"),b);}
 }
 void close(){generation++;if(pending!=null)a.ui.removeCallbacks(pending);network.shutdownNow();}
}
