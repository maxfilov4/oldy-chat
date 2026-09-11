package chat.oldy;
import android.content.Context;
import org.json.*;
import java.util.*;

/** Bounded schema: configuration can narrow these roots, never choose a proxy or routes. */
final class YouTubeDomainRules {
 static final Set<String> ROOTS=Collections.unmodifiableSet(new HashSet<>(Arrays.asList("youtube.com","youtube-nocookie.com","youtu.be","googlevideo.com","ytimg.com","youtubei.googleapis.com","youtube.googleapis.com","ggpht.com","accounts.google.com","accounts.google.ru","oauth2.googleapis.com","gstatic.com","googleusercontent.com")));
 final int version;final boolean enabled,tcpNoDelay;final Set<String> domains;
 YouTubeDomainRules(JSONObject config)throws Exception{
  Iterator<String> keys=config.keys();while(keys.hasNext())if(!Arrays.asList("version","enabled","domains","tcp_no_delay").contains(keys.next()))throw new IllegalArgumentException("Unsupported network configuration");
  version=config.getInt("version");if(version<1)throw new IllegalArgumentException("Invalid network version");enabled=config.getBoolean("enabled");tcpNoDelay=config.optBoolean("tcp_no_delay",true);
  JSONArray roots=config.getJSONArray("domains");if(roots.length()<1||roots.length()>ROOTS.size())throw new IllegalArgumentException("Invalid domain rules");Set<String> selected=new HashSet<>();for(int i=0;i<roots.length();i++){String root=roots.getString(i);if(!ROOTS.contains(root))throw new IllegalArgumentException("Domain not permitted");selected.add(root);}domains=Collections.unmodifiableSet(selected);
 }
 static YouTubeDomainRules load(Context c)throws Exception{java.io.File saved=new java.io.File(c.getFilesDir(),"youtube-rules.json");if(saved.isFile())try(java.io.InputStream in=new java.io.FileInputStream(saved)){YouTubeDomainRules savedRules=new YouTubeDomainRules(new JSONObject(Api.read(in,8192)));if(savedRules.version>=2)return savedRules;}catch(Exception invalid){}try(java.io.InputStream in=c.getAssets().open("youtube-network.json")){return new YouTubeDomainRules(new JSONObject(Api.read(in,8192)));}}
 static void save(Context c,JSONObject config)throws Exception{YouTubeDomainRules rules=new YouTubeDomainRules(config);if(rules.version<load(c).version)throw new IllegalArgumentException("Old configuration");java.io.File target=new java.io.File(c.getFilesDir(),"youtube-rules.json"),temp=new java.io.File(c.getFilesDir(),"youtube-rules.tmp");java.nio.file.Files.write(temp.toPath(),config.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));java.nio.file.Files.move(temp.toPath(),target.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
 static boolean allowed(String raw){String host=raw.toLowerCase(Locale.ROOT);for(String root:ROOTS)if(host.equals(root)||host.endsWith("."+root))return true;return false;}
 boolean matches(String raw){String host=raw.toLowerCase(Locale.ROOT);if(host.endsWith("."))host=host.substring(0,host.length()-1);for(String root:domains)if(host.equals(root)||host.endsWith("."+root))return true;return false;}
}
