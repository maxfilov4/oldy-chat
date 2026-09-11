package chat.oldy;
import android.net.Network;
import java.io.*;import java.net.*;import java.util.*;

/** A/AAAA through Android's underlying-network resolver (including configured Private DNS).
 * Unsupported record types return NODATA, permitting normal A/AAAA fallback. */
final class LocalDns {
 static byte[] answer(byte[] query,TrafficClassifier classifier,Network network)throws Exception{
  if(query.length<17||query.length>4096||query[4]!=0||query[5]!=1||(query[2]&0x80)!=0)throw new IOException("Invalid DNS question");
  int at=12;StringBuilder host=new StringBuilder();while(true){if(at>=query.length)throw new IOException();int n=query[at++]&255;if(n==0)break;if(n>63||at+n>query.length||host.length()+n>253)throw new IOException();if(host.length()>0)host.append('.');for(int i=0;i<n;i++){int ch=query[at++]&255;if(!(ch>='a'&&ch<='z'||ch>='A'&&ch<='Z'||ch>='0'&&ch<='9'||ch=='-'||ch=='_'))throw new IOException();host.append((char)ch);}}
  if(at+4>query.length)throw new IOException();int type=((query[at]&255)<<8)|(query[at+1]&255),cls=((query[at+2]&255)<<8)|(query[at+3]&255);int end=at+4,rcode=0;List<InetAddress> addresses=new ArrayList<>();
  if(cls!=1)rcode=4;else if(type==1||type==28){try{if(classifier.rules.matches(host.toString()))addresses.add(classifier.address(host.toString(),type==28));else{if(network==null)throw new UnknownHostException();for(InetAddress ip:network.getAllByName(host.toString()))if((type==28)==(ip instanceof Inet6Address)&&addresses.size()<12)addresses.add(ip);}}catch(UnknownHostException failure){rcode=2;}}
  ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);out.write(query,0,2);out.writeShort(0x8080|((query[2]&1)<<8)|rcode);out.writeShort(1);out.writeShort(addresses.size());out.writeInt(0);out.write(query,12,end-12);
  for(InetAddress ip:addresses){out.writeShort(0xc00c);out.writeShort(type);out.writeShort(1);out.writeInt(5);byte[] raw=ip.getAddress();out.writeShort(raw.length);out.write(raw);}return bytes.toByteArray();
 }
}
