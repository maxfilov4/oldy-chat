package chat.oldy;

import android.net.Network;import android.net.LocalSocket;
import java.io.*;import java.net.*;import java.nio.charset.StandardCharsets;import java.security.MessageDigest;import java.util.*;import java.util.concurrent.*;

/** Authenticated loopback-only SOCKS5 endpoint for the native TCP/IP stack.
 * Only virtual DNS and already classified YouTube:443 destinations are accepted.
 * Every outbound socket is protected and explicitly bound to a physical network.
 * TLS/HTTP2 stay encrypted; ByeDPI adjusts initial handshake transport. QUIC uses TCP fallback. */
final class LocalSocksRelay implements Closeable {
 final LocalTunnelService service;final TrafficClassifier classifier;final ServerSocket server;final LocalDpiEngine dpi;
 final String password=UUID.randomUUID().toString();final Set<Closeable> resources=ConcurrentHashMap.newKeySet();
 final ExecutorService pool=new ThreadPoolExecutor(0,200,30,TimeUnit.SECONDS,new SynchronousQueue<>());
 final Semaphore sessions=new Semaphore(80);volatile boolean closed;
 LocalSocksRelay(LocalTunnelService s,TrafficClassifier c,LocalDpiEngine d)throws Exception{service=s;classifier=c;dpi=d;server=new ServerSocket();server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),32);server.setSoTimeout(1000);resources.add(server);pool.execute(this::accept);}
 int port(){return server.getLocalPort();}
 void accept(){while(!closed)try{Socket local=server.accept();if(!sessions.tryAcquire()){local.close();continue;}resources.add(local);try{pool.execute(()->{try{client(local);}catch(Exception ignored){}finally{discard(local);sessions.release();}});}catch(RejectedExecutionException rejected){discard(local);sessions.release();}}catch(SocketTimeoutException ignored){}catch(IOException failed){if(!closed)service.relayFailed();return;}}
 void client(Socket local)throws Exception{
  local.setSoTimeout(15000);DataInputStream in=new DataInputStream(local.getInputStream());DataOutputStream out=new DataOutputStream(local.getOutputStream());
  if(in.readUnsignedByte()!=5)return;int count=in.readUnsignedByte();if(count<1||count>16)return;boolean auth=false;for(int i=0;i<count;i++)auth|=in.readUnsignedByte()==2;out.write(new byte[]{5,(byte)(auth?2:255)});out.flush();if(!auth)return;
  if(in.readUnsignedByte()!=1)return;byte[] user=new byte[in.readUnsignedByte()];in.readFully(user);byte[] pass=new byte[in.readUnsignedByte()];in.readFully(pass);
  boolean ok=MessageDigest.isEqual(user,"oldy".getBytes(StandardCharsets.US_ASCII))&&MessageDigest.isEqual(pass,password.getBytes(StandardCharsets.US_ASCII));Arrays.fill(pass,(byte)0);out.write(new byte[]{1,(byte)(ok?0:1)});out.flush();if(!ok)return;
  if(in.readUnsignedByte()!=5)return;int command=in.readUnsignedByte();if(in.readUnsignedByte()!=0)return;Address target=readAddress(in);
  if(command==3){udp(local,in,out);return;}if(command!=1){reply(out,7,0);return;}
  if(target.ip.getHostAddress().equals(TrafficClassifier.DNS)&&target.port==53){reply(out,0,0);while(!closed){int n=in.readUnsignedShort();if(n<17||n>4096)return;byte[] q=new byte[n];in.readFully(q);byte[] a=LocalDns.answer(q,classifier,NetworkDiagnostics.physical(service));out.writeShort(a.length);out.write(a);out.flush();}return;}
  String host=classifier.host(target.ip);if(host==null||target.port!=443){reply(out,2,0);return;}
  LocalSocket remote=null;try{remote=connect(host);resources.add(remote);reply(out,0,0);LocalSocket connected=remote;local.setSoTimeout(120000);pool.execute(()->{try{copy(connected.getInputStream(),local.getOutputStream());}catch(Exception ignored){}finally{discard(local);discard(connected);}});copy(in,connected.getOutputStream());}finally{if(remote!=null)discard(remote);}
 }
 LocalSocket connect(String host)throws Exception{
  Network network=NetworkDiagnostics.physical(service);if(network==null)throw new IOException("NO_NETWORK");Exception failure=new IOException("DPI_DNS");
  for(InetAddress address:DirectDns.resolve(network,host)){if(!TrafficClassifier.publicAddress(address))continue;try{return dpi.connect(address);}catch(Exception e){failure=e;}}
  TunnelStateRepository.error="DPI_TCP_"+failure.getClass().getSimpleName();throw failure;
 }

 static void copy(InputStream in,OutputStream out)throws IOException{byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
 static void reply(DataOutputStream out,int code,int port)throws IOException{out.write(new byte[]{5,(byte)code,0,1,127,0,0,1,(byte)(port>>8),(byte)port});out.flush();}
 static final class Address {final InetAddress ip;final int port;Address(InetAddress a,int p){ip=a;port=p;}}
 static Address readAddress(DataInputStream in)throws IOException{int type=in.readUnsignedByte();if(type!=1&&type!=4)throw new IOException("Only classified IP destinations permitted");byte[] address=new byte[type==1?4:16];in.readFully(address);return new Address(InetAddress.getByAddress(address),in.readUnsignedShort());}
 static byte[] udpPacket(Address a,byte[] payload,int length)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(b);out.write(new byte[]{0,0,0,(byte)(a.ip instanceof Inet6Address?4:1)});out.write(a.ip.getAddress());out.writeShort(a.port);out.write(payload,0,length);return b.toByteArray();}
 void udp(Socket control,DataInputStream in,DataOutputStream out)throws Exception{
  DatagramSocket local=new DatagramSocket(new InetSocketAddress("127.0.0.1",0));resources.add(local);local.setSoTimeout(1000);Map<String,DatagramSocket> flows=new ConcurrentHashMap<>();final InetSocketAddress[] sender={null};reply(out,0,local.getLocalPort());
  pool.execute(()->{try{byte[] buffer=new byte[65535];while(!closed&&!control.isClosed()){
   DatagramPacket packet=new DatagramPacket(buffer,buffer.length);try{local.receive(packet);}catch(SocketTimeoutException timeout){continue;}if(!packet.getAddress().isLoopbackAddress()||packet.getLength()<10)continue;
   InetSocketAddress from=(InetSocketAddress)packet.getSocketAddress();if(sender[0]==null)sender[0]=from;else if(!sender[0].equals(from))continue;
   DataInputStream data=new DataInputStream(new ByteArrayInputStream(buffer,0,packet.getLength()));if(data.readUnsignedShort()!=0||data.readUnsignedByte()!=0)continue;Address target=readAddress(data);byte[] raw=new byte[data.available()];data.readFully(raw);
   if(target.ip.getHostAddress().equals(TrafficClassifier.DNS)&&target.port==53){try{byte[] answer=LocalDns.answer(raw,classifier,NetworkDiagnostics.physical(service));byte[] framed=udpPacket(target,answer,answer.length);local.send(new DatagramPacket(framed,framed.length,from));}catch(Exception ignored){}continue;}
   String host=classifier.host(target.ip);if(host==null||target.port!=443)continue;if(dpi!=null)continue;String key=target.ip.getHostAddress();DatagramSocket remote=flows.get(key);
   if(remote==null){if(flows.size()>=24)continue;Network physical=NetworkDiagnostics.physical(service);if(physical==null)continue;InetAddress real=null;for(InetAddress ip:physical.getAllByName(host))if(TrafficClassifier.publicAddress(ip)){real=ip;break;}if(real==null)continue;
    remote=new DatagramSocket(null);try{if(!service.protect(remote))throw new IOException();physical.bindSocket(remote);remote.bind(new InetSocketAddress(0));remote.connect(real,443);remote.setSoTimeout(30000);}catch(Exception failure){remote.close();continue;}resources.add(remote);flows.put(key,remote);DatagramSocket outbound=remote;
    pool.execute(()->{try{byte[] incoming=new byte[65535];while(!closed&&!control.isClosed()){DatagramPacket response=new DatagramPacket(incoming,incoming.length);outbound.receive(response);byte[] framed=udpPacket(target,response.getData(),response.getLength());local.send(new DatagramPacket(framed,framed.length,from));}}catch(Exception ignored){}finally{flows.remove(key,outbound);discard(outbound);}});
   }
   try{remote.send(new DatagramPacket(raw,raw.length));}catch(Exception failure){flows.remove(key,remote);discard(remote);}
  }}catch(Exception ignored){}finally{discard(control);discard(local);for(DatagramSocket socket:flows.values())discard(socket);}});
  control.setSoTimeout(0);try{while(in.read()!=-1&&!closed){}}finally{discard(local);for(DatagramSocket socket:flows.values())discard(socket);}
 }
 void discard(Closeable c){resources.remove(c);try{c.close();}catch(Exception ignored){}}
 public void close(){closed=true;for(Closeable c:resources)discard(c);pool.shutdownNow();}
}
