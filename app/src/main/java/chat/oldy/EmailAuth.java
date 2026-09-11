package chat.oldy;

import android.content.*;
import android.text.InputFilter;
import android.view.*;
import android.widget.*;
import org.json.*;

/** Mail proves account ownership. Private conversation keys remain on the device. */
final class EmailAuth {
 static void show(MainActivity a,boolean signup){
  a.base("auth");ScrollView scroll=new ScrollView(a);LinearLayout b=a.col();scroll.addView(b);a.root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
  BrandMark brand=new BrandMark(a,a.light);b.addView(brand,new LinearLayout.LayoutParams(-1,a.dp(72)));a.space(b,20);
  TextView title=a.label(signup?I18n.t("Добро пожаловать"):I18n.t("Снова на связи"),28,a.TEXT);title.setTypeface(null,1);b.addView(title);a.space(b,8);a.paragraph(b,signup?I18n.t("Твой профиль, свои люди и любимые каналы."):I18n.t("Введи почту. Пришлём шестизначный код входа."));a.space(b,22);
  LinearLayout tabs=a.row();tabs.addView(a.button(I18n.t("Войти"),!signup,()->show(a,false)),new LinearLayout.LayoutParams(0,a.dp(46),1));tabs.addView(a.button(I18n.t("Регистрация"),signup,()->show(a,true)),new LinearLayout.LayoutParams(0,a.dp(46),1));b.addView(tabs);a.space(b,18);
  EditText name=signup?a.field(b,I18n.t("Как тебя зовут?"),false):null;
  EditText nick=signup?a.field(b,I18n.t("@ник · латиница, цифры, _"),false):null;
  TextView available=signup?a.label("",12,a.MUTED):null;if(available!=null)b.addView(available);
  EditText email=a.field(b,I18n.t("Электронная почта"),false);email.setInputType(33);email.setAutofillHints(View.AUTOFILL_HINT_EMAIL_ADDRESS);
  EditText backup=signup?a.field(b,I18n.t("Пароль резервной копии · от 10 символов"),true):null;
  if(signup){a.paragraph(b,I18n.t("Этот пароль нужен только для восстановления защищённой переписки на другом телефоне. Вход — по коду из письма."));a.space(b,12);}
  CheckBox agreement=signup?LegalCenter.signup(a,b):null;
  TextView submit=a.button(I18n.t("Получить код на почту"),true,()->{});b.addView(submit);
  if(signup)SignupHandle.bind(a,nick,available,submit);
  submit.setOnClickListener(v->{
   if(signup&&!agreement.isChecked()){a.error(LegalCenter.tr("Прими условия и правила сообщества","Accept the terms and community rules"));return;}
   String mail=email.getText().toString().trim();if(!android.util.Patterns.EMAIL_ADDRESS.matcher(mail).matches()){email.setError(I18n.t("Проверь адрес почты"));return;}
   String handle=signup?nick.getText().toString().trim().replaceFirst("^@","").toLowerCase(java.util.Locale.ROOT):"",pass=signup?backup.getText().toString():"",display=signup?name.getText().toString().trim():"";
   if(signup&&(!handle.matches("[a-z0-9_]{3,24}")||pass.length()<10||pass.length()>128||display.isEmpty())){a.error(I18n.t("Укажи имя, свободный @ник и пароль резервной копии от 10 до 128 символов."));return;}
   submit.setEnabled(false);final LinearLayout view=a.root;
   a.task(()->{try{a.api.check();JSONObject body=new JSONObject().put("email",mail);if(signup)body.put("nick",handle);JSONObject r=a.api.call(signup?"/signup/request":"/login/request",body,"");a.runOnUiThread(()->{if(a.root!=view)return;if(signup)a.verifySignup(handle,pass,display,mail,r.optString("ticket"));else code(a,mail,r.optString("ticket"));});}finally{a.runOnUiThread(()->submit.setEnabled(true));}});
  });
  a.space(b,16);a.paragraph(b,I18n.t("Вход сохраняется после закрытия приложения. Новый код понадобится после выхода из аккаунта."));
  if(!signup){a.space(b,16);b.addView(a.button(I18n.t("Старый аккаунт без почты"),false,()->legacy(a)));}
  a.space(b,16);b.addView(a.button(I18n.t("Настройки подключения"),false,a::settings));
 }
 static void code(MainActivity a,String email,String ticket){
  a.base("verify");a.title(I18n.t("Код входа"),()->show(a,false));a.paragraph(a.root,I18n.t("Если эта почта подтверждена в аккаунте, код уже отправлен на ")+email+I18n.t(". Он действует 10 минут."));a.space(a.root,18);
  EditText input=a.field(a.root,I18n.t("6 цифр из письма"),false);input.setInputType(2);input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
  TextView submit=a.button(I18n.t("Войти"),true,()->{});a.root.addView(submit);final LinearLayout view=a.root;
  submit.setOnClickListener(v->{String value=input.getText().toString().trim();if(!value.matches("[0-9]{6}")){input.setError(I18n.t("Введи шесть цифр"));return;}submit.setEnabled(false);a.task(()->{try{JSONObject reply=a.api.call("/login/verify",new JSONObject().put("email",email).put("ticket",ticket).put("code",value),"");JSONObject user=reply.getJSONObject("user");Vault next=new Vault(a,user.getString("nick"),false);if(!Crypto.fingerprint(user).equals(Crypto.fingerprint(next.identity()))){a.runOnUiThread(()->{if(a.root==view)restore(a,reply,next);});}else activate(a,reply,next);}finally{a.runOnUiThread(()->submit.setEnabled(true));}});});
  a.space(a.root,14);TextView resend=a.button(I18n.t("Прислать новый код"),false,()->{});a.root.addView(resend);resend.setOnClickListener(v->{resend.setEnabled(false);a.task(()->{try{JSONObject r=a.api.call("/login/request",new JSONObject().put("email",email),"");a.runOnUiThread(()->{if(a.root==view)code(a,email,r.optString("ticket"));});}finally{a.runOnUiThread(()->resend.setEnabled(true));}});});
 }
 static void activate(MainActivity a,JSONObject reply,Vault next)throws Exception{
  next.account(reply);next.rooms(a.api.call("/rooms",null,next.token()).getJSONArray("rooms"));a.api.call("/account/email-only",new JSONObject(),next.token());
  a.runOnUiThread(()->{if(ChatService.instance!=null)ChatService.instance.running=false;a.stopService(new Intent(a,ChatService.class));ChatService.shared=next;a.vault=next;a.mediaCache.evictAll();a.search="";a.filter=0;a.chat="";a.chats();a.startChat();});
 }
 static void restore(MainActivity a,JSONObject reply,Vault next){
  a.base("verify");a.title(I18n.t("Перенести защищённую историю"),()->show(a,false));a.paragraph(a.root,I18n.t("Почта подтверждена. На этом телефоне ещё нет ключей твоей переписки. Введи пароль резервной копии. Для аккаунтов старой версии это прежний пароль аккаунта."));a.space(a.root,18);EditText secret=a.field(a.root,I18n.t("Пароль резервной копии"),true);
  TextView submit=a.button(I18n.t("Восстановить и открыть чат"),true,()->{});a.root.addView(submit);submit.setOnClickListener(v->{String password=secret.getText().toString();submit.setEnabled(false);a.task(()->{try{String blob=a.api.call("/key-backup",null,reply.getString("token")).getString("backup");next.restoreKeys(Crypto.importBackup(blob,password.toCharArray()),reply.getJSONObject("user"));activate(a,reply,next);}finally{a.runOnUiThread(()->submit.setEnabled(true));}});});
  a.space(a.root,14);a.paragraph(a.root,I18n.t("Если копия не настроена, открой старый телефон → Настройки → Защита и резервная копия. Создай копию и повтори восстановление здесь."));
 }
 static void legacy(MainActivity a){a.base("verify");a.title(I18n.t("Привязать старый аккаунт"),()->show(a,false));a.paragraph(a.root,I18n.t("Один раз войди по прежним данным и привяжи почту в своём профиле. Затем вход будет по e-mail и коду."));a.space(a.root,14);EditText nick=a.field(a.root,I18n.t("Старый @ник"),false),password=a.field(a.root,I18n.t("Прежний пароль"),true);a.root.addView(a.button(I18n.t("Открыть старый аккаунт"),true,()->{String n=nick.getText().toString().trim().replaceFirst("^@","").toLowerCase(java.util.Locale.ROOT),p=password.getText().toString();a.task(()->a.loginAccount(n,p,null));}));}
 static void link(MainActivity a){try{
  final Vault owner=a.vault;JSONObject me=owner.copy();LinearLayout b=a.col();a.paragraph(b,me.optBoolean("email_verified")?I18n.t("✓ Текущая почта: ")+me.optString("email")+I18n.t(". Она останется действующей до подтверждения новой."):I18n.t("Привяжи свою почту к этому аккаунту. Ник, номер и переписка сохранятся."));a.space(b,14);EditText email=a.field(b,I18n.t("Электронная почта"),false);email.setInputType(33);email.setText(me.optString("pending_email",me.optString("email")));
  TextView submit=a.button(I18n.t("Отправить код подтверждения"),true,()->{});b.addView(submit);submit.setOnClickListener(v->{String value=email.getText().toString().trim();if(!android.util.Patterns.EMAIL_ADDRESS.matcher(value).matches()){email.setError(I18n.t("Проверь адрес"));return;}submit.setEnabled(false);a.task(()->{try{owner.profile(a.api.call("/email/link",new JSONObject().put("email",value),owner.token()));a.api.call("/email/request",new JSONObject(),owner.token());a.runOnUiThread(()->{if(a.vault==owner)confirmLink(a,owner,value);});}finally{a.runOnUiThread(()->submit.setEnabled(true));}});});a.sheet(I18n.t("Почта и вход"),b);
 }catch(Exception e){a.error(Api.message(e));}}
 static void confirmLink(MainActivity a,Vault owner,String email){LinearLayout b=a.col();a.paragraph(b,I18n.t("Код отправлен на ")+email);a.space(b,12);EditText code=a.field(b,I18n.t("6 цифр из письма"),false);code.setInputType(2);code.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});TextView submit=a.button(I18n.t("Подтвердить почту"),true,()->{});b.addView(submit);submit.setOnClickListener(v->{String value=code.getText().toString().trim();if(!value.matches("[0-9]{6}")){code.setError(I18n.t("Введи шесть цифр"));return;}submit.setEnabled(false);a.task(()->{try{owner.profile(a.api.call("/email/verify",new JSONObject().put("code",value),owner.token()));a.runOnUiThread(()->{if(a.vault==owner){Toast.makeText(a,I18n.t("Почта подтверждена. Теперь вход по коду."),Toast.LENGTH_LONG).show();ProfileEditor.show(a);}});}finally{a.runOnUiThread(()->submit.setEnabled(true));}});});a.space(b,10);b.addView(a.button(I18n.t("Прислать код ещё раз"),false,()->a.task(()->{a.api.call("/email/request",new JSONObject(),owner.token());a.runOnUiThread(()->{if(a.vault==owner)confirmLink(a,owner,email);});})));a.sheet(I18n.t("Подтверди почту"),b);}
}
