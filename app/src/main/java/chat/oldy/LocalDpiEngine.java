package chat.oldy;

import android.net.*;import android.os.*;import android.system.Os;
import java.io.*;import java.net.*;import java.util.*;import java.util.concurrent.*;

/** Pinned MIT ByeDPI process. Only the authenticated classifier can reach its private
 * Unix socket. Outbound descriptors must be protected AND bound before connect. */
final class LocalDpiEngine implements Closeable {
 final LocalTunnelService service;final File endpoint,protection;final ExecutorService workers=Executors.newCachedThreadPool();
 final Set<Closeable> clients=ConcurrentHashMap.newKeySet();LocalSocket listener;LocalServerSocket protector;java.lang.Process process;volatile boolean closed;volatile long protectedSockets;
 LocalDpiEngine(LocalTunnelService s)throws Exception{
  service=s;File dir=new File(s.getFilesDir(),"dpi");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("DPI_DIRECTORY");
  endpoint=new File(dir,"data.sock");protection=new File(dir,"protect.sock");endpoint.delete();protection.delete();
  try{
   listener=new LocalSocket();listener.bind(new LocalSocketAddress(protection.getAbsolutePath(),LocalSocketAddress.Namespace.FILESYSTEM));protector=new LocalServerSocket(listener.getFileDescriptor());workers.execute(this::accept);
   String mode=s.strategy;List<String> command=new ArrayList<>(Arrays.asList(new File(s.getApplicationInfo().nativeLibraryDir,"liboldi_dpi.so").getAbsolutePath(),"--ip","127.0.0.1","--no-domain","--no-udp","--max-conn","96","--timeout","4","--cache-ttl","600","--protect-path",protection.getAbsolutePath(),"--debug","0"));
   if(mode.equals("disorder"))Collections.addAll(command,"--disorder","1","--tlsrec","1+s");
   else if(mode.equals("split"))Collections.addAll(command,"--split","1+s","--tlsrec","1+s");
   else if(mode.equals("fake4")||mode.equals("fake8"))Collections.addAll(command,"--fake","-1","--ttl",mode.equals("fake4")?"4":"8");
   else Collections.addAll(command,"--tlsrec","1+s","--split","1+s","--auto","torst,ssl_err","--disorder","1","--tlsrec","1+s","--auto","torst,ssl_err","--split","2","--auto","torst,ssl_err","--fake","-1","--ttl","4","--auto","torst,ssl_err","--fake","-1","--ttl","8");
   ProcessBuilder builder=new ProcessBuilder(command);builder.environment().remove("SS_PLUGIN_OPTIONS");builder.environment().remove("SS_LOCAL_PORT");builder.environment().put("OLDI_DPI_SOCKET",endpoint.getAbsolutePath());builder.redirectOutput(new File("/dev/null"));builder.redirectError(new File("/dev/null"));process=builder.start();
   long until=SystemClock.elapsedRealtime()+4000;while(!endpoint.exists()&&process.isAlive()&&SystemClock.elapsedRealtime()<until)Thread.sleep(25);
   if(!endpoint.exists()||!process.isAlive())throw new IOException("DPI_START_FAILED");
  }catch(Exception e){close();throw e;}
 }
 void accept(){while(!closed)try{LocalSocket socket=protector.accept();clients.add(socket);workers.execute(()->{try{socket.setSoTimeout(1200);if(socket.getPeerCredentials().getUid()!=android.os.Process.myUid())return;int flag=socket.getInputStream().read();FileDescriptor[] fds=socket.getAncillaryFileDescriptors();boolean ok=false;try{Network network=NetworkDiagnostics.physical(service);if(flag>=0&&fds!=null&&fds.length==1&&network!=null){try(ParcelFileDescriptor copy=ParcelFileDescriptor.dup(fds[0])){ok=service.protect(copy.getFd());if(ok)network.bindSocket(copy.getFileDescriptor());}if(ok)protectedSockets++;}}finally{if(fds!=null)for(FileDescriptor fd:fds)try{Os.close(fd);}catch(Exception ignored){}}
    socket.getOutputStream().write(ok?1:0);
   }catch(Exception ignored){}finally{clients.remove(socket);try{socket.close();}catch(Exception ignored){}}});}catch(Exception error){if(!closed)service.relayFailed();return;}}
 LocalSocket connect(InetAddress address)throws Exception{
  if(!TrafficClassifier.publicAddress(address)||closed||process==null||!process.isAlive())throw new IOException("DPI_UNAVAILABLE");
  LocalSocket s=new LocalSocket();try{s.connect(new LocalSocketAddress(endpoint.getAbsolutePath(),LocalSocketAddress.Namespace.FILESYSTEM));s.setSoTimeout(15000);DataInputStream in=new DataInputStream(s.getInputStream());OutputStream out=s.getOutputStream();out.write(new byte[]{5,1,0});if(in.readUnsignedByte()!=5||in.readUnsignedByte()!=0)throw new IOException("DPI_HANDSHAKE");ByteArrayOutputStream request=new ByteArrayOutputStream();request.write(new byte[]{5,1,0,(byte)(address instanceof Inet6Address?4:1)});request.write(address.getAddress());request.write(new byte[]{1,(byte)187});out.write(request.toByteArray());if(in.readUnsignedByte()!=5||in.readUnsignedByte()!=0)throw new IOException("DPI_CONNECT");if(in.readUnsignedByte()!=0)throw new IOException("DPI_PROTOCOL");int type=in.readUnsignedByte(),n=type==1?4:type==4?16:-1;if(n<0)throw new IOException("DPI_PROTOCOL");byte[] tail=new byte[n+2];in.readFully(tail);s.setSoTimeout(120000);return s;}catch(Exception e){s.close();throw e;}
 }
 boolean alive(){return !closed&&process!=null&&process.isAlive();}
 public void close(){if(closed)return;closed=true;if(process!=null){process.destroy();if(process.isAlive())process.destroyForcibly();}if(protector!=null)try{protector.close();}catch(Exception ignored){}if(listener!=null)try{listener.close();}catch(Exception ignored){}for(Closeable c:clients)try{c.close();}catch(Exception ignored){}workers.shutdownNow();endpoint.delete();protection.delete();}
}
