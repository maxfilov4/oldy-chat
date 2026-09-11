package chat.oldy;
import android.net.Network;import android.os.SystemClock;import org.json.*;
import java.io.*;import java.net.*;import java.util.*;import javax.net.ssl.HttpsURLConnection;

/** Physical-network DNS with bounded HTTPS fallback for compiled YouTube roots only.
 * No message text, URL, token or cookies are sent to the DNS provider. */
final class DirectDns {
 static final Map<String,Entry> cache=Collections.synchronizedMap(new LinkedHashMap<String,Entry>(){protected boolean removeEldestEntry(Map.Entry<String,DirectDns.Entry> e){return size()>256;}});
 static final class Entry{final long time=SystemClock.elapsedRealtime();final InetAddress[] values;Entry(InetAddress[] a){values=a;}}
 static InetAddress[] resolve(Network network,String host)throws Exception{
  String key=network.getNetworkHandle()+":"+host;Entry old=cache.get(key);if(old!=null&&SystemClock.elapsedRealtime()-old.time<60000)return old.values;
  List<InetAddress> out=new ArrayList<>();try{for(InetAddress a:network.getAllByName(host))if(TrafficClassifier.publicAddress(a))out.add(a);}catch(Exception ignored){}
  if(out.isEmpty()&&YouTubeDomainRules.allowed(host))for(int type:new int[]{1,28}){
   HttpsURLConnection c=null;try{c=(HttpsURLConnection)network.openConnection(new URL("https://dns.google/resolve?name="+URLEncoder.encode(host,"UTF-8")+"&type="+type+"&edns_client_subnet=0.0.0.0%2F0"));c.setConnectTimeout(3000);c.setReadTimeout(3000);c.setInstanceFollowRedirects(false);if(c.getResponseCode()!=200)continue;JSONObject j=new JSONObject(Api.read(c.getInputStream(),32768));if(j.optInt("Status",-1)!=0)continue;JSONArray answers=j.optJSONArray("Answer");if(answers==null)continue;for(int i=0;i<answers.length()&&out.size()<12;i++){JSONObject a=answers.optJSONObject(i);if(a==null||a.optInt("type")!=type)continue;String ip=a.optString("data");if(!ip.matches(type==1?"[0-9.]{7,15}":"[0-9a-fA-F:]{3,45}"))continue;InetAddress addr=InetAddress.getByName(ip);if(TrafficClassifier.publicAddress(addr))out.add(addr);}}catch(Exception ignored){}finally{if(c!=null)c.disconnect();}
  }
  if(out.isEmpty())throw new UnknownHostException("YOUTUBE_DNS_UNAVAILABLE");InetAddress[] values=out.toArray(new InetAddress[0]);cache.put(key,new Entry(values));return values;
 }
}
