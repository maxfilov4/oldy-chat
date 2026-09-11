package chat.oldy;
import android.graphics.Color;import android.view.*;import android.widget.*;

/** Persistent primary navigation and a single compact row of content destinations. */
final class SideNavigation {
 static void show(MainActivity a){a.settings();}
 static void anchor(MainActivity a){navigation(a,a.screen.equals("contacts")?1:a.screen.equals("settings")?2:a.screen.equals("own")?3:0);}
 static void navigation(MainActivity a,int selected){if(a.vault.token().isEmpty())return;LinearLayout bar=a.row();bar.setTag("primary-navigation");bar.setBackground(a.glass(24));bar.setPadding(a.dp(4),a.dp(3),a.dp(4),a.dp(3));String[] labels={"Чаты","Контакты","Настройки","Моё"},icons={"chat","contacts","settings","profile"};Runnable[] actions={()->{a.showArchive=false;a.chats();},()->ContactDiscovery.show(a),a::settings,()->SettingsHome.own(a)};
  for(int i=0;i<labels.length;i++){int at=i;int color=selected==i?(a.cyber?0xff42fff0:a.light?0xff28798d:0xff8edee9):a.MUTED;LinearLayout item=a.col();item.setGravity(Gravity.CENTER);item.setContentDescription(I18n.t(labels[i]));UiIcon icon=new UiIcon(a,icons[i],color);icon.setFocusable(false);item.addView(icon,new LinearLayout.LayoutParams(a.dp(28),a.dp(28)));TextView name=a.label(I18n.t(labels[i]),11,color);name.setGravity(Gravity.CENTER);item.addView(name);item.setOnClickListener(v->actions[at].run());bar.addView(item,new LinearLayout.LayoutParams(0,a.dp(57),1));}a.space(a.root,8);a.root.addView(bar,new LinearLayout.LayoutParams(-1,a.dp(64)));
 }
 static void conversations(MainActivity a){LinearLayout tabs=a.row();tabs.setTag("content-navigation");tabs.setBackground(a.glass(19));String[] names={"▶ YouTube","Чаты","Группы","Каналы"};int[] filters={-1,0,2,3};
  for(int i=0;i<names.length;i++){int at=i;TextView tab=a.button(I18n.t(names[i]),i>0&&(a.filter==filters[i]||i==1&&a.filter==1),()->{if(at==0)YouTubeBrowser.open(a,"https://m.youtube.com/");else{a.filter=filters[at];a.chats();}});tab.setTextSize(11);tab.setSingleLine(true);tab.setPadding(a.dp(2),a.dp(10),a.dp(2),a.dp(10));if(i==0){tab.setTextColor(Color.WHITE);tab.setBackground(a.shape(0xffce3045,16));tab.setContentDescription(I18n.t("Просмотр YouTube"));}LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,a.dp(46),1);if(i>0)lp.leftMargin=a.dp(3);tabs.addView(tab,lp);}a.root.addView(tabs);a.space(a.root,10);
 }
}
