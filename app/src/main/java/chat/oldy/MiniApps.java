package chat.oldy;
import android.content.*;import android.widget.*;

final class MiniApps {
 static void show(MainActivity a){LinearLayout b=a.col();ProfileEditor.section(a,b,I18n.t("ПРИЛОЖЕНИЯ"));b.addView(a.menuTile("♫",I18n.t("Аудиомастерская"),I18n.t("Убрать шум · радио · робот · эхо"),()->a.startActivity(new Intent(a,AudioLabActivity.class))));ProfileEditor.section(a,b,I18n.t("ИГРЫ"));a.paragraph(b,I18n.t("Каталог игр пока пуст."));a.sheet(I18n.t("Приложения и игры"),b);}
}
