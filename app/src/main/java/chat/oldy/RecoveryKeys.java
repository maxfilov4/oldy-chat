package chat.oldy;
import android.app.*;import android.content.*;import android.os.*;import android.view.*;import android.widget.*;import org.json.*;

/** Per-account recovery secret created on the phone, never an administrator key. */
final class RecoveryKeys {
 static String create(){String hex=Crypto.hex(Crypto.random(32));StringBuilder key=new StringBuilder("OLDY");for(int i=0;i<hex.length();i+=8)key.append('-').append(hex.substring(i,i+8));return key.toString();}
 static void show(MainActivity a,Vault owner){
  final String key=create();LinearLayout body=a.col();a.pad(body,18);a.paragraph(body,I18n.t("Сохрани этот ключ в своём менеджере паролей. Он заменит пароль облачной копии и понадобится после входа по e-mail на новом телефоне. Без него восстановить эту копию нельзя."));a.space(body,14);
  TextView secret=a.label(key,16,a.TEXT);secret.setTypeface(android.graphics.Typeface.MONOSPACE);secret.setTextIsSelectable(true);a.pad(secret,12);secret.setBackground(a.shape(a.CARD,14));body.addView(secret);a.space(body,12);
  TextView copy=a.button(I18n.t("Скопировать ключ"),false,()->{ClipData clip=ClipData.newPlainText("Oldy recovery key",key);PersistableBundle extras=new PersistableBundle();extras.putBoolean("android.content.extra.IS_SENSITIVE",true);clip.getDescription().setExtras(extras);((ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(clip);Toast.makeText(a,I18n.t("Ключ скопирован"),Toast.LENGTH_SHORT).show();});body.addView(copy);a.space(body,12);
  CheckBox saved=new CheckBox(a);saved.setText(I18n.t("Я сохранил ключ в надёжном месте"));saved.setTextColor(a.TEXT);body.addView(saved);
  AlertDialog dialog=a.dialog().setTitle(I18n.t("Личный ключ восстановления")).setView(body).setNegativeButton(I18n.t("Отмена"),null).setPositiveButton(I18n.t("Сохранить защищённую копию"),null).create();dialog.show();if(dialog.getWindow()!=null)dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
  dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);saved.setOnCheckedChangeListener((v,on)->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(on));
  dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);a.task(()->{try{if(a.vault!=owner)throw new IllegalStateException(I18n.t("Аккаунт изменился. Открой настройку заново."));String backup=Crypto.exportBackup(owner.keyBackup(),key.toCharArray());a.api.call("/key-backup",new JSONObject().put("backup",backup),owner.token());a.runOnUiThread(()->{dialog.dismiss();Toast.makeText(a,I18n.t("Личный ключ включён. Используй его вместо пароля резервной копии."),Toast.LENGTH_LONG).show();});}finally{a.runOnUiThread(()->{if(dialog.isShowing())dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(saved.isChecked());});}});});
 }
}
