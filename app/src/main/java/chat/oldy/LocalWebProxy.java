package chat.oldy;
import android.net.*;import android.os.Build;import android.system.OsConstants;
import java.io.*;import java.net.*;import java.nio.charset.StandardCharsets;import java.util.*;import java.util.concurrent.*;

/** HTTPS CONNECT only, same-UID clients only, compiled YouTube domain roots only.
 * TLS bytes stay opaque. No URLs, headers or payloads are logged or persisted. */
final class LocalWebProxy implements Closeable {
 final LocalTunnelService service;final LocalSocksRelay relay;final ServerSocket listener;
 final Set<Closeable> clients=ConcurrentHashMap.newKeySet();final Semaphore slots=new Semaphore(48);
 final ExecutorService workers=new ThreadPoolExecutor(0,100,30,TimeUnit.SECONDS,new SynchronousQueue<>());
 volatile boolean closed;volatile long connections,transmitted,received;
 LocalWebProxy(LocalTunnelService s,LocalSocksRelay r)throws Exception{service=s;relay=r;listener=new ServerSocket();listener.bind(new InetSocketAddress("127.0.0.1",0),32);listener.setSoTimeout(1000);workers.execute(this::accept);}
 int port(){return listener.getLocalPort();}
 boolean owned(Socket socket){if(Build.VERSION.SDK_INT<29)return false;try{ConnectivityManager cm=service.getSystemService(ConnectivityManager.class);int owner=cm.getConnectionOwnerUid(OsConstants.IPPROTO_TCP,(InetSocketAddress)socket.getRemoteSocketAddress(),(InetSocketAddress)socket.getLocalSocketAddress());return owner==android.os.Process.myUid();}catch(Exception denied){return false;}}
 void accept(){while(!closed)try{Socket socket=listener.accept();if(!owned(socket)||!slots.tryAcquire()){socket.close();continue;}clients.add(socket);try{workers.execute(()->{try{connect(socket);}catch(Exception ignored){}finally{discard(socket);slots.release();}});}catch(RejectedExecutionException e){discard(socket);slots.release();}}catch(SocketTimeoutException timeout){}catch(Exception e){if(!closed)service.relayFailed();return;}}
 static String authority(InputStream input)throws IOException{ByteArrayOutputStream bytes=new ByteArrayOutputStream();int tail=0;while(bytes.size()<12288){int b=input.read();if(b<0)throw new EOFException();bytes.write(b);tail=(tail<<8)|b;if(tail==0x0d0a0d0a)break;}if(tail!=0x0d0a0d0a)throw new IOException("HEADER_SIZE");String[] first=new String(bytes.toByteArray(),StandardCharsets.US_ASCII).split("\r\n",2)[0].split(" ");if(first.length!=3||!first[0].equals("CONNECT")||!first[2].matches("HTTP/1\\.[01]"))throw new IOException("HTTPS_ONLY");String value=first[1].toLowerCase(Locale.ROOT);if(!value.matches("[a-z0-9.-]{1,253}:443"))throw new IOException("DESTINATION");String host=value.substring(0,value.length()-4);if(!YouTubeDomainRules.allowed(host))throw new IOException("DOMAIN_DENIED");return host;}
 void connect(Socket client)throws Exception{client.setSoTimeout(8000);String host=authority(client.getInputStream());if(!relay.classifier.rules.matches(host))throw new IOException("DOMAIN_DISABLED");LocalSocket remote=relay.connect(host);clients.add(remote);connections++;try{client.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));client.getOutputStream().flush();client.setSoTimeout(120000);workers.execute(()->{try{pipe(remote.getInputStream(),client.getOutputStream(),false);}catch(Exception ignored){}finally{discard(client);discard(remote);}});pipe(client.getInputStream(),remote.getOutputStream(),true);}finally{discard(remote);}}
 void pipe(InputStream in,OutputStream out,boolean upload)throws IOException{byte[] b=new byte[32768];for(int n;(n=in.read(b))!=-1;){out.write(b,0,n);if(upload)transmitted+=n;else received+=n;}}
 void discard(Closeable c){clients.remove(c);try{c.close();}catch(Exception ignored){}}
 public void close(){closed=true;try{listener.close();}catch(Exception ignored){}for(Closeable c:clients)discard(c);workers.shutdownNow();}
}
