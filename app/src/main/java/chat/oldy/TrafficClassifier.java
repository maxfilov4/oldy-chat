package chat.oldy;
import java.net.*;import java.util.*;

/** Session-only synthetic destinations, not a static list of Google's addresses. */
final class TrafficClassifier {
 static final String DNS="198.18.0.1";final YouTubeDomainRules rules;
 private final Map<String,InetAddress[]> names=new HashMap<>();private final Map<String,String> reverse=new HashMap<>();int next=258;
 TrafficClassifier(YouTubeDomainRules r){rules=r;}
 synchronized InetAddress address(String host,boolean ipv6)throws Exception{
  host=host.toLowerCase(Locale.ROOT);if(!rules.matches(host))throw new UnknownHostException();InetAddress[] pair=names.get(host);
  if(pair==null){if(names.size()>=8192)throw new UnknownHostException("Domain capacity reached");int i=next++;byte[] v4={(byte)198,18,(byte)(i>>8),(byte)i};byte[] v6=new byte[16];v6[0]=(byte)0xfd;v6[1]=0x66;v6[2]=0x6f;v6[3]=0x6c;v6[4]=0x64;v6[5]=0x79;v6[12]=(byte)(i>>24);v6[13]=(byte)(i>>16);v6[14]=(byte)(i>>8);v6[15]=(byte)i;pair=new InetAddress[]{InetAddress.getByAddress(v4),InetAddress.getByAddress(v6)};names.put(host,pair);for(InetAddress a:pair)reverse.put(a.getHostAddress(),host);}
  return pair[ipv6?1:0];
 }
 synchronized String host(InetAddress address){return reverse.get(address.getHostAddress());}
 static boolean publicAddress(InetAddress a){byte[] b=a.getAddress();if(a.isAnyLocalAddress()||a.isLoopbackAddress()||a.isLinkLocalAddress()||a.isSiteLocalAddress()||a.isMulticastAddress())return false;if(b.length==4){int x=b[0]&255,y=b[1]&255;return x!=0&&x!=127&&x<224&&!(x==100&&y>=64&&y<=127)&&!(x==198&&(y==18||y==19));}return (b[0]&0xfe)!=0xfc;}
}
