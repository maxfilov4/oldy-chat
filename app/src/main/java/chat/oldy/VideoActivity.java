package chat.oldy;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.media3.common.*;
import androidx.media3.datasource.*;
import androidx.media3.exoplayer.*;
import androidx.media3.exoplayer.mediacodec.*;
import androidx.media3.exoplayer.source.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

/** Streaming player: a continuous pinned HTTPS range, buffered ahead, no full-file download. */
public class VideoActivity extends Activity {
 ExoPlayer player;FrameLayout root;TextureView surface;TextView status,play;SeekBar seek;
 final Handler handler=new Handler(Looper.getMainLooper());final ExecutorService io=Executors.newSingleThreadExecutor();
 volatile boolean prepared,rendering;volatile long frames,lastFrameAt;volatile String playerError="";
 String video,local;boolean seeking,compatible,converting,destroyed;long resumePosition;int vw=16,vh=9;
 public void onCreate(Bundle b){
  super.onCreate(b);video=getIntent().getStringExtra("video");local=getIntent().getStringExtra("local");
  getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
  root=new FrameLayout(this);root.setBackgroundColor(Color.BLACK);surface=new TextureView(this);root.addView(surface,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
  LinearLayout controls=new LinearLayout(this);controls.setOrientation(1);controls.setPadding(16,12,16,24);controls.setBackgroundColor(0xdd101720);
  LinearLayout top=new LinearLayout(this);TextView back=text("‹ Назад");back.setOnClickListener(v->finish());top.addView(back);
  status=text("Загружаем видео…");top.addView(status,new LinearLayout.LayoutParams(0,-2,1));play=text("Ⅱ");play.setOnClickListener(v->{if(player==null)return;if(player.isPlaying()){player.pause();play.setText("▶");}else{player.play();play.setText("Ⅱ");}});top.addView(play);controls.addView(top);
  seek=new SeekBar(this);controls.addView(seek);LinearLayout tools=new LinearLayout(this);
  TextView speed=text("1×");speed.setOnClickListener(v->{if(player==null)return;float n=player.getPlaybackParameters().speed>=2?1:player.getPlaybackParameters().speed+.5f;player.setPlaybackSpeed(n);speed.setText(n+"×");});tools.addView(speed);
  TextView retry=text("↻ Повторить");retry.setOnClickListener(v->startPlayer(false));tools.addView(retry);
  if(video!=null){TextView format=text("Совместимый формат");format.setOnClickListener(v->prepareCompatible());tools.addView(format);}
  controls.addView(tools);root.addView(controls,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));setContentView(root);
  seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int n,boolean u){}public void onStartTrackingTouch(SeekBar s){seeking=true;}public void onStopTrackingTouch(SeekBar s){if(player!=null)player.seekTo(s.getProgress());seeking=false;}});
  startPlayer(false);handler.post(this::tick);
 }
 void startPlayer(boolean software){
  if(destroyed)return;
  try{
   if(player!=null){resumePosition=player.getCurrentPosition();player.release();}
   prepared=false;rendering=false;frames=0;lastFrameAt=SystemClock.elapsedRealtime();playerError="";
   DefaultRenderersFactory renderers=new DefaultRenderersFactory(this).setEnableDecoderFallback(true);
   if(software)renderers.setMediaCodecSelector((mime,secure,tunnel)->{List<MediaCodecInfo> list=new ArrayList<>(MediaCodecSelector.DEFAULT.getDecoderInfos(mime,secure,tunnel));list.sort((a,b)->Boolean.compare(isSoftware(b.name),isSoftware(a.name)));return list;});
   DefaultLoadControl buffer=new DefaultLoadControl.Builder().setBufferDurationsMs(15000,45000,1800,3500).setTargetBufferBytes(32*1024*1024).setPrioritizeTimeOverSizeThresholds(false).build();
   player=new ExoPlayer.Builder(this,renderers).setLoadControl(buffer).build();player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),true);
   player.setVideoTextureView(surface);
   player.setVideoFrameMetadataListener((presentation,release,format,media)->{frames++;lastFrameAt=SystemClock.elapsedRealtime();});
   player.addListener(new Player.Listener(){
    public void onPlaybackStateChanged(int state){prepared=state==Player.STATE_READY||state==Player.STATE_ENDED;if(state==Player.STATE_ENDED)play.setText("▶");}
    public void onRenderedFirstFrame(){rendering=true;}
    public void onVideoSizeChanged(VideoSize size){vw=size.width;vh=size.height;fit();}
    public void onPlayerError(PlaybackException e){playerError=e.getErrorCodeName();prepared=false;status.setText("Не удалось открыть видео · "+playerError);}
   });
   DataSource.Factory factory=()->new Source(this,video,local,compatible);
   Uri uri=Uri.parse(video==null?"oldylocal:"+local:"https://oldy.invalid/video.mp4");
   player.setMediaSource(new ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(uri)));
   if(resumePosition>0)player.seekTo(resumePosition);player.prepare();player.play();play.setText("Ⅱ");
  }catch(Exception e){playerError=Api.message(e);status.setText(playerError);}
 }
 static boolean isSoftware(String name){return name.startsWith("c2.android.")||name.startsWith("OMX.google.");}
 void prepareCompatible(){
  if(video==null||converting||destroyed)return;converting=true;resumePosition=player==null?0:player.getCurrentPosition();if(player!=null)player.pause();status.setText("Готовим совместимый формат…");
  io.execute(()->{try{JSONObject r=new Api(this).call("/videos/compatible",new JSONObject().put("id",video),ChatService.vault(this).token());String state=r.optString("state");handler.post(()->{if(destroyed)return;if(state.equals("ready")){converting=false;compatible=true;startPlayer(false);}else if(state.equals("error")){converting=false;status.setText("Не удалось обработать файл. Попробуй другой MP4.");}else{status.setText(state.equals("busy")?"Сервер обрабатывает другое видео…":"Обработка видео · можно вернуться в чат");handler.postDelayed(()->{converting=false;prepareCompatible();},4000);}});}catch(Exception e){handler.post(()->{converting=false;status.setText(Api.message(e));});}});
 }
 TextView text(String s){TextView t=new TextView(this);t.setText(s);t.setTextColor(Color.WHITE);t.setTextSize(13);t.setPadding(12,14,12,14);return t;}
 void fit(){root.post(()->{if(vw<1||vh<1)return;int w=root.getWidth(),h=root.getHeight();if((long)w*vh>(long)h*vw)w=h*vw/vh;else h=w*vh/vw;surface.setLayoutParams(new FrameLayout.LayoutParams(w,h,Gravity.CENTER));});}
 String time(long ms){long sec=Math.max(0,ms)/1000;return String.format(Locale.ROOT,"%d:%02d",sec/60,sec%60);}
 void tick(){if(destroyed)return;if(player!=null&&!converting){long duration=player.getDuration();if(duration>0)seek.setMax((int)Math.min(Integer.MAX_VALUE,duration));if(!seeking)seek.setProgress((int)player.getCurrentPosition());seek.setSecondaryProgress((int)player.getBufferedPosition());if(playerError.isEmpty())status.setText(player.getPlaybackState()==Player.STATE_BUFFERING?"Подгружаем…":time(player.getCurrentPosition())+" / "+time(duration));if(player.isPlaying()&&frames>0&&SystemClock.elapsedRealtime()-lastFrameAt>6500&&!compatible&&video!=null)prepareCompatible();}handler.postDelayed(this::tick,400);}
 public void onConfigurationChanged(Configuration c){super.onConfigurationChanged(c);fit();}
 protected void onPause(){super.onPause();if(player!=null){player.pause();play.setText("▶");}}
 protected void onDestroy(){destroyed=true;handler.removeCallbacksAndMessages(null);if(player!=null){player.release();player=null;}io.shutdownNow();super.onDestroy();}

 static final class Source extends BaseDataSource implements AutoCloseable {
  final Context context;final String id,local;final boolean compatible;HttpsURLConnection connection;InputStream input;Uri uri;long left;
  Source(Context c,String video,String key,boolean variant){super(video!=null);context=c.getApplicationContext();id=video;local=key;compatible=variant;}
  public long open(DataSpec spec)throws IOException{
   transferInitializing(spec);
   try{
    if(id==null){byte[] data=MediaFiles.bytes(context,local);if(spec.position>data.length)throw new EOFException();left=spec.length==C.LENGTH_UNSET?data.length-spec.position:Math.min(spec.length,data.length-spec.position);input=new ByteArrayInputStream(data,(int)spec.position,(int)left);uri=spec.uri;}
    else{
     if(!id.matches("[a-f0-9-]{36}"))throw new IOException("Invalid video");Api api=new Api(context);String base=Api.live.isEmpty()?api.url():Api.live;
     uri=Uri.parse(base+"/video-stream/"+id+(compatible?"?compatible=1":""));connection=(HttpsURLConnection)new URL(uri.toString()).openConnection();connection.setSSLSocketFactory(api.factory());connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(15000);connection.setReadTimeout(20000);connection.setRequestProperty("Authorization","Bearer "+ChatService.vault(context).token());connection.setRequestProperty("Accept-Encoding","identity");connection.setRequestProperty("Range","bytes="+spec.position+"-"+(spec.length==C.LENGTH_UNSET?"":Long.toString(spec.position+spec.length-1)));
     if(connection.getResponseCode()!=206)throw new IOException("Video HTTP "+connection.getResponseCode());String range=connection.getHeaderField("Content-Range");if(range==null||!range.startsWith("bytes "+spec.position+"-"))throw new IOException("Invalid video range");left=connection.getContentLengthLong();if(left<0||left>2147483648L)throw new IOException("Invalid media length");input=connection.getInputStream();
    }
    transferStarted(spec);return left;
   }catch(Exception e){if(connection!=null)connection.disconnect();connection=null;throw new IOException("Video loading failed",e);}
  }
  public int read(byte[] buffer,int offset,int length)throws IOException{if(length==0)return 0;if(left==0)return C.RESULT_END_OF_INPUT;int n=input.read(buffer,offset,(int)Math.min(length,left));if(n<0)throw new EOFException("Incomplete video");left-=n;bytesTransferred(n);return n;}
  public Uri getUri(){return uri;}
  public Map<String,List<String>> getResponseHeaders(){return connection==null?Collections.emptyMap():connection.getHeaderFields();}
  public void close(){boolean opened=input!=null;try{if(input!=null)input.close();}catch(IOException ignored){}input=null;if(connection!=null)connection.disconnect();connection=null;if(opened)transferEnded();}
 }
}
