package chat.oldy;
import android.Manifest;import android.content.*;import android.content.pm.PackageManager;import android.database.Cursor;import android.provider.ContactsContract;import android.view.*;import android.widget.*;import java.nio.charset.StandardCharsets;import java.security.MessageDigest;import java.util.*;import org.json.*;

/** Explicit, opt-in email matching; contact names and phone numbers stay local. */
final class ContactDiscovery {
 static final int PERMISSION=61;
 static void tabs(MainActivity a,boolean contacts){LinearLayout tabs=a.row();tabs.addView(a.button(I18n.t("@ По нику"),!contacts,a::findPeer),new LinearLayout.LayoutParams(0,a.dp(46),1));tabs.addView(a.button(I18n.t("Контакты телефона"),contacts,()->show(a)),new LinearLayout.LayoutParams(0,a.dp(46),1));a.root.addView(tabs);a.space(a.root,14);}
 static void show(MainActivity a){
  a.base("contacts");a.title(I18n.t("Найти знакомых"),a::findPeer);tabs(a,true);
  final Vault owner=a.vault;final View root=a.root;
  a.paragraph(a.root,I18n.t("Найдём аккаунты по подтверждённым e-mail, если люди разрешили находить себя. Поиск по номеру телефона пока недоступен."));a.space(a.root,12);
  Switch visible=new Switch(a);visible.setText(I18n.t("Разрешить находить меня по e-mail"));visible.setTextColor(a.TEXT);visible.setEnabled(false);a.pad(visible,8);a.root.addView(visible,new LinearLayout.LayoutParams(-1,a.dp(60)));
  a.task(()->{JSONObject current=a.api.call("/contacts/settings",null,owner.token());a.runOnUiThread(()->{if(a.root!=root||a.vault!=owner)return;visible.setChecked(current.optBoolean("discoverable"));visible.setEnabled(true);visible.setOnCheckedChangeListener((v,on)->{visible.setEnabled(false);a.task(()->{boolean saved=false;try{a.api.call("/contacts/settings",new JSONObject().put("discoverable",on),owner.token());saved=true;}finally{final boolean ok=saved;a.runOnUiThread(()->{if(a.root!=root)return;visible.setEnabled(true);if(!ok){show(a);}});}});});});});
  a.space(a.root,8);a.paragraph(a.root,I18n.t("По нажатию будут прочитаны e-mail из адресной книги. На сервер отправятся их хеши; имена и номера телефонов останутся на устройстве."));a.space(a.root,14);
  TextView scan=a.button(I18n.t("Найти в контактах"),true,()->{if(a.checkSelfPermission(Manifest.permission.READ_CONTACTS)!=PackageManager.PERMISSION_GRANTED)a.requestPermissions(new String[]{Manifest.permission.READ_CONTACTS},PERMISSION);else scan(a);});scan.setTag("contacts-scan");a.root.addView(scan);a.space(a.root,16);
  ScrollView scroll=new ScrollView(a);LinearLayout results=a.col();results.setTag("contacts-results");scroll.addView(results);a.root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
 }
 static String hash(String email)throws Exception{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(email.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
 static LinkedHashMap<String,String> addresses(Context context)throws Exception{
  LinkedHashMap<String,String> values=new LinkedHashMap<>();String[] columns={ContactsContract.CommonDataKinds.Email.ADDRESS,ContactsContract.CommonDataKinds.Email.DISPLAY_NAME};
  try(Cursor rows=context.getContentResolver().query(ContactsContract.CommonDataKinds.Email.CONTENT_URI,columns,null,null,null)){
   if(rows!=null)while(rows.moveToNext()&&values.size()<2048){String email=rows.getString(0);if(email==null||!android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches())continue;String name=rows.getString(1);values.put(hash(email),name==null?I18n.t("Контакт"):name);}
  }return values;
 }
 static void scan(MainActivity a){
  if(!a.screen.equals("contacts"))return;final View root=a.root;final Vault owner=a.vault;LinearLayout results=a.root.findViewWithTag("contacts-results");View button=a.root.findViewWithTag("contacts-scan");if(results==null||button==null||!button.isEnabled())return;
  button.setEnabled(false);results.removeAllViews();a.paragraph(results,I18n.t("Проверяем контакты…"));
  a.task(()->{try{
   LinkedHashMap<String,String> names=addresses(a);ArrayList<String> hashes=new ArrayList<>(names.keySet());JSONArray matches=new JSONArray();
   for(int from=0;from<hashes.size();from+=256){if(a.vault!=owner||a.root!=root)return;JSONArray batch=new JSONArray();for(int i=from;i<Math.min(from+256,hashes.size());i++)batch.put(hashes.get(i));JSONArray found=a.api.call("/contacts/discover",new JSONObject().put("hashes",batch),owner.token()).getJSONArray("matches");for(int i=0;i<found.length();i++)matches.put(found.getJSONObject(i));}
   a.runOnUiThread(()->{if(a.root!=root||a.vault!=owner)return;results.removeAllViews();if(matches.length()==0)a.paragraph(results,I18n.t(names.isEmpty()?"В контактах нет адресов e-mail. Добавь человека по @нику.":"Совпадений нет. Человек должен подтвердить почту и разрешить поиск по e-mail."));
    for(int i=0;i<matches.length();i++){JSONObject match=matches.optJSONObject(i),user=match.optJSONObject("user");String local=names.get(match.optString("hash"));if(local==null||user==null)continue;View card=a.searchCard(user.optString("avatar"),local,user.optString("name")+" · @"+user.optString("nick"),I18n.t("ОТКРЫТЬ ЧАТ"),()->a.task(()->{if(a.vault!=owner)return;owner.pin(user);a.runOnUiThread(()->{if(a.vault==owner)a.openChat(user.optString("nick"));});}));results.addView(card);a.space(results,10);}
   });
  }finally{a.runOnUiThread(()->{if(a.root==root)button.setEnabled(true);});}});
 }
}
