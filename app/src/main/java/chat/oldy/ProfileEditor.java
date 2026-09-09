package chat.oldy;

import android.content.*;
import android.graphics.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import org.json.*;

final class ProfileEditor {
 static final java.util.WeakHashMap<Vault,String[]> drafts=new java.util.WeakHashMap<>();
 static void show(MainActivity a){try{
  final Vault owner=a.vault;JSONObject data=owner.copy();String[] draft=drafts.remove(owner);if(draft!=null){data.put("name",draft[0]);data.put("bio",draft[1]);}LinearLayout b=a.col();
  LinearLayout hero=a.row();a.pad(hero,14);hero.setBackground(a.shape(a.CARD,22));
  Art avatar=a.art(data.optString("avatar","preset:0"),80,hero);avatar.setOnClickListener(v->a.avatarPicker(""));
  LinearLayout identity=a.col();identity.setPadding(a.dp(16),0,0,0);TextView title=a.label(data.optString("name",owner.nick()),23,a.TEXT);title.setTypeface(null,1);identity.addView(title);
  a.space(identity,5);TextView number=a.label(number(data),13,a.GREEN);number.setTypeface(Typeface.MONOSPACE);identity.addView(number);hero.addView(identity,new LinearLayout.LayoutParams(0,-2,1));b.addView(hero);
  a.space(b,12);TextView chooseAvatar=a.button(I18n.t("Выбрать аватар"),false,()->a.avatarPicker(""));b.addView(chooseAvatar);
  section(a,b,I18n.t("ИМЯ"));EditText name=a.field(b,I18n.t("Как тебя показывать в чате"),false);name.setText(data.optString("name"));name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(40)});
  section(a,b,I18n.t("ИДЕНТИФИКАТОР"));TextView handle=a.button("@"+owner.nick()+"   ⧉",false,()->{a.getSystemService(android.content.ClipboardManager.class).setPrimaryClip(ClipData.newPlainText(I18n.t("Ник Oldy Chat"),"@"+owner.nick()));Toast.makeText(a,I18n.t("Идентификатор скопирован"),Toast.LENGTH_SHORT).show();});handle.setOnClickListener(v->{a.getSystemService(android.content.ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Oldy username","@"+owner.nick()));Toast.makeText(a,I18n.t("Идентификатор скопирован"),Toast.LENGTH_SHORT).show();});b.addView(handle);a.space(b,6);a.paragraph(b,I18n.t("По этому адресу тебя находят другие участники."));
  section(a,b,I18n.t("О СЕБЕ"));EditText bio=a.field(b,I18n.t("Игры, увлечения, пара слов о себе…"),false);bio.setSingleLine(false);bio.setInputType(1|131072|16384);bio.setMinLines(3);bio.setMaxLines(5);bio.setLayoutParams(new LinearLayout.LayoutParams(-1,a.dp(116)));bio.setFilters(new InputFilter[]{new InputFilter.LengthFilter(160)});bio.setGravity(Gravity.TOP);bio.setText(data.optString("bio"));
  Runnable choose=()->{drafts.put(owner,new String[]{name.getText().toString(),bio.getText().toString()});a.avatarPicker("");};chooseAvatar.setOnClickListener(v->choose.run());avatar.setOnClickListener(v->choose.run());
  TextView count=a.label(bio.length()+" / 160",11,a.MUTED);count.setGravity(Gravity.RIGHT);b.addView(count);bio.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int f){}public void onTextChanged(CharSequence s,int st,int before,int c){count.setText(s.length()+" / 160");}public void afterTextChanged(Editable e){}});
  a.space(b,14);TextView save=a.button(I18n.t("Сохранить профиль"),true,()->{});save.setOnClickListener(v->{String n=name.getText().toString().trim(),about=bio.getText().toString().trim();if(n.isEmpty()){name.setError(I18n.t("Укажи имя"));return;}save.setEnabled(false);a.task(()->{try{owner.profile(a.api.call("/profile",new JSONObject().put("name",n).put("bio",about),owner.token()));a.runOnUiThread(()->{if(a.vault==owner){Toast.makeText(a,I18n.t("Профиль сохранён"),Toast.LENGTH_SHORT).show();show(a);}});}finally{a.runOnUiThread(()->save.setEnabled(true));}});});b.addView(save);
  section(a,b,I18n.t("ПОЧТА И ВХОД"));a.paragraph(b,data.optBoolean("email_verified")?"✓ "+data.optString("email")+I18n.t(" · подтверждена"):I18n.t("Привяжи почту, чтобы входить по коду из письма."));a.space(b,10);b.addView(a.button(data.optBoolean("email_verified")?I18n.t("Изменить e-mail"):I18n.t("Привязать и подтвердить e-mail"),false,()->a.emailSettings()));
  a.sheet(I18n.t("Твой профиль"),b);
  a.work.execute(()->{try{JSONObject fresh=a.api.call("/me",null,owner.token());owner.profile(fresh);a.runOnUiThread(()->{if(a.vault==owner)number.setText(number(fresh));});}catch(Exception ignored){}});
 }catch(Exception e){a.error(Api.message(e));}}
 static String number(JSONObject d){long n=d.optLong("account_number");return n>0?I18n.t("АККАУНТ № ")+String.format(java.util.Locale.ROOT,"%06d",n):I18n.t("Номер появится после синхронизации");}
 static void section(MainActivity a,LinearLayout b,String title){a.space(b,20);TextView name=a.label(title,11,a.GREEN);name.setTypeface(null,1);name.setLetterSpacing(.12f);b.addView(name);a.space(b,8);View line=new View(a);line.setBackgroundColor(a.light?0xffdbe5ef:0xff364458);b.addView(line,new LinearLayout.LayoutParams(-1,a.dp(1)));a.space(b,10);}
}
