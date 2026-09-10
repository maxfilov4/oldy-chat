package hev.htproxy;
/** Exact public JNI contract of the pinned MIT-licensed hev-socks5-tunnel. */
public final class TProxyService {
 public static native boolean TProxyStartService(String configPath,int fd);
 public static native boolean TProxyStopService();
 public static native boolean TProxyIsRunning();
 public static native long[] TProxyGetStats();
 static {System.loadLibrary("hev-socks5-tunnel");}
 private TProxyService(){}
}
