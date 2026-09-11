package chat.oldy;
import android.widget.*;import org.json.*;

final class ChatActions {
 static void show(MainActivity a,String target){try{
  final Vault account=a.vault;boolean archived=account.archived(target);JSONObject room=target.startsWith("room:")?account.room(Conversation.room(target)):null;boolean owner=room!=null&&room.optString("owner").equals(account.nick());LinearLayout b=a.col();
  b.addView(a.button(archived?I18n.t("Вернуть из архива"):I18n.t("В архив"),false,()->a.task(()->{account.archiveChat(target,!archived);a.runOnUiThread(a::renderChats);})));a.space(b,10);
  String label=room==null?I18n.t("Удалить чат и переписку"):owner?(room.optString("kind").equals("channel")?I18n.t("Удалить канал"):I18n.t("Удалить группу")):I18n.t("Выйти и удалить чат");
  String detail=room==null?I18n.t("Переписка удалится у тебя на устройствах этого аккаунта. Собеседник останется пользователем приложения."):owner?I18n.t("Сообщество и его публикации будут удалены для всех участников. Адрес @")+room.optString("handle")+I18n.t(" освободится."):I18n.t("Ты выйдешь из сообщества, и оно исчезнет из твоего списка.");
  b.addView(a.button(label,false,()->a.dialog().setTitle(label+"?").setMessage(detail).setNegativeButton(I18n.t("Отмена"),null).setPositiveButton(I18n.t("Удалить"),(d,w)->a.task(()->{
   if(room!=null){String id=room.getString("id");a.api.call(owner?"/room/delete":"/room/leave",new JSONObject().put("id",id),account.token());if(owner)account.deletion(new JSONObject().put("kind","room").put("room",id));else account.leaveRoom(id);}
   else{JSONArray ids=account.chatMessageIds(target);long through=0;for(int start=0;start<Math.max(1,ids.length());start+=1000){JSONArray batch=new JSONArray();for(int i=start;i<Math.min(ids.length(),start+1000);i++)batch.put(ids.get(i));JSONObject result=a.api.call("/chats/clear",new JSONObject().put("peer",target).put("mids",batch),account.token());through=result.getLong("through_ms");}account.clearChat(target,through);}
   a.runOnUiThread(()->{if(a.vault==account){a.mediaCache.evictAll();a.renderChats();}});
  })).show()));a.sheet(I18n.t("Действия с чатом"),b);
 }catch(Exception e){a.error(Api.message(e));}}
}
