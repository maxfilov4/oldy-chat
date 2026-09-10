package chat.oldy;

import android.content.Intent;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;

final class WallpaperPicker {
 static void show(MainActivity a){
  LinearLayout body=a.col();
  a.paragraph(body,I18n.t("Единый фон от края до края — за шапкой, сообщениями и полем ввода."));a.space(body,12);
  body.addView(a.button(I18n.t("Загрузить своё фото"),true,()->a.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE),WallpaperPhoto.REQUEST)));
  boolean photo=Notices.prefs(a).getBoolean("wallpaper_photo",false);
  if(WallpaperPhoto.file(a).isFile()){
   a.space(body,10);body.addView(a.button((photo?"✓  ":"")+I18n.t("Моё фото на весь экран"),false,()->{Notices.prefs(a).edit().putBoolean("wallpaper_photo",true).apply();a.refreshAppearance();}));
  }
  a.space(body,8);Switch glow=new Switch(a);glow.setText(I18n.t("Мягкое свечение по краям"));glow.setTextColor(a.TEXT);glow.setChecked(Notices.prefs(a).getBoolean("wallpaper_glow",true));a.pad(glow,10);
  glow.setOnCheckedChangeListener((v,on)->{Notices.prefs(a).edit().putBoolean("wallpaper_glow",on).apply();if(a.root instanceof ThemeLayout){ThemeBackdrop back=((ThemeLayout)a.root).backdrop;back.glow=on;back.invalidateSelf();}});body.addView(glow,new LinearLayout.LayoutParams(-1,a.dp(50)));
  a.paragraph(body,I18n.t("Фото хранится только на этом телефоне. Движение света зависит от настройки «Анимации»."));a.space(body,14);
  for(int row=0;row<4;row++){
   LinearLayout pair=a.row();for(int column=0;column<2;column++){
    final int style=row*2+column;boolean selected=!photo&&Notices.prefs(a).getInt("wallpaper",a.cyber?3:0)==style;
    FrameLayout card=new FrameLayout(a);card.setBackground(new Wallpaper(a.light,style));card.setContentDescription(I18n.t(Wallpaper.NAMES[style]));
    card.setOutlineProvider(new ViewOutlineProvider(){public void getOutline(View view,Outline outline){outline.setRoundRect(0,0,view.getWidth(),view.getHeight(),a.dp(18));}});card.setClipToOutline(true);
    LinearLayout captions=a.col();captions.setPadding(a.dp(10),a.dp(20),a.dp(9),a.dp(10));captions.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0x00121d31,0xe6121d31}));
    TextView name=a.label((selected?"✓  ":"")+I18n.t(Wallpaper.NAMES[style]),12,Color.WHITE);name.setTypeface(null,1);name.setMaxLines(3);captions.addView(name);card.addView(captions,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
    card.setOnClickListener(v->{Notices.prefs(a).edit().putInt("wallpaper",style).putBoolean("wallpaper_photo",false).apply();if(a.activeSheet!=null)a.activeSheet.dismiss();a.refreshAppearance();Toast.makeText(a,I18n.t("Обои сохранены"),Toast.LENGTH_SHORT).show();});
    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,a.dp(145),1);if(column==0)lp.rightMargin=a.dp(10);pair.addView(card,lp);
   }body.addView(pair);a.space(body,10);
  }
  a.sheet(I18n.t("Обои на весь экран"),body);
 }
}
