package chat.oldy;
import org.json.*;
final class FixturesInstrumentation {
 static JSONObject register(Api api,JSONObject body,String ignored)throws Exception{String mail=body.optString("email",body.getString("nick")+"@example.test");JSONObject ticket=api.call("/signup/request",new JSONObject().put("email",mail),"");String code=api.call("/test-code?email="+java.net.URLEncoder.encode(mail,"UTF-8"),null,"").getString("code");return api.call("/register",body.put("email",mail).put("ticket",ticket.getString("ticket")).put("code",code),"");}
}
