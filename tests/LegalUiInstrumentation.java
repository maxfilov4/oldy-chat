package chat.oldy;
import android.app.*;import android.content.*;import android.os.*;import android.view.*;import android.widget.*;
public class LegalUiInstrumentation extends Instrumentation {
 void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 View description(View v,String text){if(text.contentEquals(v.getContentDescription()==null?"":v.getContentDescription()))return v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){View found=description(g.getChildAt(i),text);if(found!=null)return found;}}return null;}
 public void onCreate(Bundle args){super.onCreate(args);start();}
 public void onStart(){Bundle result=new Bundle();try{MainActivity a=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Thread.sleep(2900);runOnMainSync(()->SettingsHome.show(a));waitForIdleSync();runOnMainSync(()->{View back=description(a.root,I18n.t("Назад"));check(back!=null,"Settings back control missing");check(back.getForeground() instanceof android.graphics.drawable.RippleDrawable,"Button has no visible pressed feedback");back.performClick();check(a.screen.equals("chats"),"Settings back returns to settings instead of chats");SettingsHome.appearance(a);});waitForIdleSync();runOnMainSync(()->{description(a.root,I18n.t("Назад")).performClick();check(a.screen.equals("settings")&&a.settingsSection.isEmpty(),"Subsection back does not return to settings");try{check(LegalCenter.documents(a).has("privacy"),"Privacy document missing");check(LegalCenter.documents(a).has("community"),"Community rules missing");}catch(Exception e){throw new RuntimeException(e);}});
  result.putString("stream","OLDI_LEGAL_UI_PASS: settings/subsection back, native ripple feedback, offline policy documents\n");finish(-1,result);
 }catch(Throwable e){result.putString("stream","OLDI_LEGAL_UI_FAIL: "+android.util.Log.getStackTraceString(e));finish(0,result);}}
}
