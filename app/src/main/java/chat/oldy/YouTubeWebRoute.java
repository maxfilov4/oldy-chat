package chat.oldy;
import android.os.Build;import android.os.Handler;import android.os.Looper;import androidx.webkit.*;

/** Explicit WebView routing avoids Chromium DNS/connection caches bypassing split TUN.
 * Reverse bypass keeps every unrelated origin direct, including other Oldi WebViews. */
final class YouTubeWebRoute {
 static final Handler ui=new Handler(Looper.getMainLooper());static int activePort;static int generation;
 static boolean supported(){return Build.VERSION.SDK_INT>=29&&WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)&&WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE_REVERSE_BYPASS);}
 static void apply(Runnable ready){TunnelStateRepository.refresh();int port=TunnelStateRepository.on()?TunnelStateRepository.webPort:0;if(!supported()||port<=0){clear(ready);return;}if(port==activePort){ready.run();return;}int token=++generation;
  try{ProxyConfig.Builder b=new ProxyConfig.Builder().addProxyRule("http://127.0.0.1:"+port).setReverseBypassEnabled(true);for(String host:YouTubeDomainRules.ROOTS){b.addBypassRule(host);b.addBypassRule("*."+host);}ProxyController.getInstance().setProxyOverride(b.build(),r->ui.post(r),()->{if(token!=generation)return;activePort=port;ready.run();});}catch(Exception e){activePort=0;clear(ready);}
 }
 static void clear(Runnable ready){activePort=0;int token=++generation;if(!supported()){activePort=0;ready.run();return;}try{ProxyController.getInstance().clearProxyOverride(r->ui.post(r),()->{if(token!=generation)return;activePort=0;ready.run();});}catch(Exception ignored){activePort=0;ready.run();}}
}
