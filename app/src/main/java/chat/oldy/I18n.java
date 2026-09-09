package chat.oldy;
import android.content.Context;import java.io.*;import java.nio.charset.StandardCharsets;import java.util.Locale;import org.json.JSONObject;

/** Explicit translations of application copy; content written by people stays verbatim. */
final class I18n {
 private static volatile String language="ru";private static JSONObject catalog=new JSONObject();
 static void init(Context c){language=Notices.prefs(c).getString("language","ru");try(InputStream in=c.getAssets().open("en.json")){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))>=0)out.write(buffer,0,n);catalog=new JSONObject(out.toString("UTF-8"));}catch(Exception ignored){}}
 static boolean english(){return language.equals("en");}static String language(){return language;}static Locale locale(){return english()?Locale.ENGLISH:new Locale("ru");}
 static void setLanguage(Context c,String value){language=value.equals("en")?"en":"ru";Notices.prefs(c).edit().putString("language",language).apply();}
 static String t(String source){return english()?catalog.optString(source,source):source;}
 static String error(String source){String result=t(source);if(english()&&result.matches("(?s).*[А-Яа-яЁё].*"))return "Could not complete the request. Please try again.";return result;}
}
