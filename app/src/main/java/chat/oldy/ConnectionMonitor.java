package chat.oldy;
import android.os.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One bounded foreground health probe every five seconds. Sync runs in the existing service. */
final class ConnectionMonitor {
 final MainActivity a;final ExecutorService io=Executors.newSingleThreadExecutor();final AtomicBoolean busy=new AtomicBoolean();boolean active;Runnable pending;
 final Runnable tick=new Runnable(){public void run(){if(!active)return;probe(null);a.ui.postDelayed(this,5000);}};
 ConnectionMonitor(MainActivity a){this.a=a;}
 void start(){active=true;a.ui.removeCallbacks(tick);a.ui.post(tick);}
 void stop(){active=false;a.ui.removeCallbacks(tick);}
 void close(){stop();io.shutdownNow();}
 void probe(Runnable finished){
  if(a.vault==null||a.vault.token().isEmpty()||io.isShutdown()){if(finished!=null)finished.run();return;}if(finished!=null)pending=finished;if(!busy.compareAndSet(false,true))return;
  io.execute(()->{boolean ok=false;try{a.api.quickHealth();ok=true;}catch(Exception ignored){}finally{busy.set(false);}final boolean connected=ok;
   a.runOnUiThread(()->{if(a.isDestroyed())return;String next=connected?"Подключено":"Нет подключения";
    if(!ChatService.state.equals("Нужно войти снова")&&!next.equals(ChatService.state)){ChatService.state=next;ChatService.changed(a);}
    Runnable completion=pending;pending=null;if(completion!=null){if(connected){ChatService service=ChatService.instance;if(service!=null)service.requestSync();else a.startChat();}completion.run();}
   });
  });
 }
}
