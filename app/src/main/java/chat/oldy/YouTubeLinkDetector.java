package chat.oldy;

import java.net.URI;
import java.net.URLDecoder;
import java.util.*;
import java.util.regex.*;

/** Only canonical public YouTube identifiers cross the player boundary. */
final class YouTubeLinkDetector {
 static final Set<String> HOSTS=Collections.unmodifiableSet(new HashSet<>(Arrays.asList("youtube.com","www.youtube.com","m.youtube.com","music.youtube.com","youtu.be")));
 static final Pattern LINKS=Pattern.compile("(?i)(?<![\\w.@/-])(?:https?://)?(?:(?:www\\.|m\\.|music\\.)?youtube\\.com|youtu\\.be)/[^\\s<>\"\\u0000-\\u001f]+?");
 static final Pattern CANDIDATES=Pattern.compile("(?i)(?<![\\w.@/-])(?:https?://)?(?:(?:www\\.|m\\.|music\\.)?youtube\\.com|youtu\\.be)/[^\\s<>\"\\u0000-\\u001f]+");
 static final class Target {
  final String videoId,playlistId;final int startSeconds;
  Target(String v,String p,int s){videoId=v;playlistId=p;startSeconds=s;}
  String url(){return "https://www.youtube.com/"+(videoId.isEmpty()?"playlist?list="+playlistId:"watch?v="+videoId+(playlistId.isEmpty()?"":"&list="+playlistId))+(startSeconds>0?"&t="+startSeconds:"");}
 }
 static boolean video(String id){return id!=null&&id.matches("[A-Za-z0-9_-]{11}");}
 static boolean playlist(String id){return id!=null&&id.matches("[A-Za-z0-9_-]{10,160}");}
 static Target first(String text){if(text==null||text.length()>100000)return null;Matcher m=CANDIDATES.matcher(text);while(m.find()){Target t=parse(m.group().replaceAll("[.,!?)\\]}>]+$",""));if(t!=null)return t;}return null;}
 static Target parse(String raw){try{
  if(raw==null||raw.length()>2048)return null;String clean=raw.trim();if(!clean.contains("://"))clean="https://"+clean;
  URI u=new URI(clean);String scheme=u.getScheme(),host=u.getHost();if(!("https".equalsIgnoreCase(scheme)||"http".equalsIgnoreCase(scheme))||host==null||u.getUserInfo()!=null||u.getPort()!=-1)return null;
  host=host.toLowerCase(Locale.ROOT);if(!HOSTS.contains(host))return null;String path=u.getPath();Map<String,String> query=new HashMap<>();
  if(u.getRawQuery()!=null)for(String pair:u.getRawQuery().split("&")){String[] kv=pair.split("=",2);String key=URLDecoder.decode(kv[0],"UTF-8"),value=kv.length==2?URLDecoder.decode(kv[1],"UTF-8"):"";if(query.containsKey(key)&&!query.get(key).equals(value))return null;query.put(key,value);}
  String v="",p=query.getOrDefault("list","");if(!p.isEmpty()&&!playlist(p))return null;
  if(host.equals("youtu.be")){if(!path.matches("/[A-Za-z0-9_-]{11}/?"))return null;v=path.substring(1,12);}
  else if(path.equals("/watch"))v=query.getOrDefault("v","");
  else if(path.matches("/(shorts|embed|live)/[A-Za-z0-9_-]{11}/?"))v=path.split("/")[2];
  else if(!path.equals("/playlist"))return null;
  if((!v.isEmpty()&&!video(v))||(v.isEmpty()&&p.isEmpty())||(!path.equals("/playlist")&&v.isEmpty()))return null;
  String time=query.getOrDefault("t",query.getOrDefault("start",""));if(time.isEmpty()&&u.getFragment()!=null&&u.getFragment().startsWith("t="))time=u.getFragment().substring(2);
  return new Target(v,p,seconds(time));
 }catch(Exception invalid){return null;}}
 static int seconds(String text){try{if(text.matches("[0-9]{1,7}"))return Math.min(86400,Integer.parseInt(text));Matcher m=Pattern.compile("(?:(\\d{1,3})h)?(?:(\\d{1,4})m)?(?:(\\d{1,6})s)?").matcher(text);if(!text.isEmpty()&&m.matches())return Math.min(86400,(m.group(1)==null?0:Integer.parseInt(m.group(1))*3600)+(m.group(2)==null?0:Integer.parseInt(m.group(2))*60)+(m.group(3)==null?0:Integer.parseInt(m.group(3))));}catch(Exception ignored){}return 0;}
}
