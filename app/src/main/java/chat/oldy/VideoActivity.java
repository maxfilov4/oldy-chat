package chat.oldy;
import android.app.*;import android.content.*;import android.content.pm.ActivityInfo;import android.content.res.Configuration;import android.graphics.*;import android.graphics.drawable.GradientDrawable;import android.net.Uri;import android.os.*;import android.view.*;import android.widget.*;import androidx.media3.common.*;import androidx.media3.datasource.*;import androidx.media3.exoplayer.*;import androidx.media3.exoplayer.mediacodec.*;import androidx.media3.exoplayer.source.*;import org.json.*;import java.io.*;import java.net.*;import java.util.*;import java.util.concurrent.*;import javax.net.ssl.*;

/** Authenticated streaming, large overlay controls, and explicit landscape fullscreen. */
public class VideoActivity extends Activity {
 ExoPlayer player;FrameLayout root;TextureView surface;TextView status,play,settings;SeekBar seek;LinearLayout controls,top;RotatePhone rotate;
 final Handler handler=new Handler(Looper.getMainLooper());final ExecutorService io=Executors.newSingleThreadExecutor();
 volatile boolean prepared,rendering;volatile long frames,lastFrameAt;volatile String playerError="";
 String video,local;boolean seeking,compatible,converting,destroyed,uiVisible=true,landscape;long resumePosition,lastInteraction;int vw=16,vh=9,quality,requestedQuality,conversionGeneration;float pixelRatio=1,speed=1;
 JSONArray qualityOptions=new JSONArray();String conversionMessage="";
 public void onCreate(Bundle b){
  super.onCreate(b);video=getIntent().getStringExtra("video");local=getIntent().getStringExtra("local");
  if(b!=null){resumePosition=b.getLong("position");speed=b.getFloat("speed",1);quality=b.getInt("quality");compatible=b.getBoolean("compatible");}
  getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);immersive();
  root=new FrameLayout(this);root.setBackgroundColor(Color.BLACK);surface=new TextureView(this);root.addView(surface,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));surface.setOnClickListener(v->setControls(!uiVisible));root.addOnLayoutChangeListener((view,left,topEdge,right,bottom,oldLeft,oldTop,oldRight,oldBottom)->{if(right-left!=oldRight-oldLeft||bottom-topEdge!=oldBottom-oldTop)fit();});
  top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);top.setPadding(dp(8),dp(4),dp(8),dp(4));top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0xcc080d16,0x00080d16}));
  TextView back=action("‹",I18n.t("Назад"));back.setOnClickListener(v->finish());top.addView(back,new LinearLayout.LayoutParams(dp(52),dp(52)));status=text(I18n.t("Загружаем видео…"),13);status.setMaxLines(2);top.addView(status,new LinearLayout.LayoutParams(0,-2,1));root.addView(top,new FrameLayout.LayoutParams(-1,dp(68),Gravity.TOP));
  controls=new LinearLayout(this);controls.setOrientation(1);controls.setPadding(dp(12),0,dp(12),dp(8));controls.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,new int[]{0xcc080d16,0x00080d16}));
  seek=new SeekBar(this);seek.setContentDescription(I18n.t("Перемотка видео"));seek.setProgressTintList(android.content.res.ColorStateList.valueOf(0xff63e5ed));seek.setThumbTintList(android.content.res.ColorStateList.valueOf(0xff63e5ed));controls.addView(seek,new LinearLayout.LayoutParams(-1,dp(40)));
  LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);play=action("Ⅱ",I18n.t("Воспроизведение или пауза"));play.setOnClickListener(v->{touch();if(player==null)return;if(player.isPlaying())player.pause();else{if(player.getPlaybackState()==Player.STATE_ENDED)player.seekTo(0);player.play();}updatePlay();});row.addView(play,new LinearLayout.LayoutParams(dp(52),dp(52)));
  TextView back10=action("↶ 10",I18n.t("Назад на 10 секунд"));back10.setTextSize(16);back10.setOnClickListener(v->{touch();if(player!=null)player.seekTo(Math.max(0,player.getCurrentPosition()-10000));});row.addView(back10,new LinearLayout.LayoutParams(dp(60),dp(52)));
  row.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));settings=action("⚙",I18n.t("Качество и скорость"));settings.setOnClickListener(v->showSettings());row.addView(settings,new LinearLayout.LayoutParams(dp(56),dp(52)));
  rotate=new RotatePhone(this);rotate.setContentDescription(I18n.t("Повернуть видео на весь экран"));rotate.setOnClickListener(v->toggleOrientation());row.addView(rotate,new LinearLayout.LayoutParams(dp(60),dp(52)));controls.addView(row);root.addView(controls,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));setContentView(root);
  root.setOnApplyWindowInsetsListener((v,insets)->{int left=0,topEdge=0,right=0,bottom=0;if(Build.VERSION.SDK_INT>=28&&insets.getDisplayCutout()!=null){android.view.DisplayCutout c=insets.getDisplayCutout();left=c.getSafeInsetLeft();topEdge=c.getSafeInsetTop();right=c.getSafeInsetRight();bottom=c.getSafeInsetBottom();}root.setPadding(left,topEdge,right,bottom);fit();return insets;});
  seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int n,boolean u){if(u)touch();}public void onStartTrackingTouch(SeekBar s){seeking=true;touch();}public void onStopTrackingTouch(SeekBar s){if(player!=null)player.seekTo(s.getProgress());seeking=false;touch();}});
  touch();startPlayer(false);loadQualities();handler.post(this::tick);
 }
 int dp(float x){return Math.round(x*getResources().getDisplayMetrics().density);}
 void immersive(){getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);if(Build.VERSION.SDK_INT>=30){WindowInsetsController c=getWindow().getInsetsController();if(c!=null){c.hide(WindowInsets.Type.systemBars());c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);}}}
 void toggleOrientation(){touch();landscape=getResources().getConfiguration().orientation!=Configuration.ORIENTATION_LANDSCAPE;setRequestedOrientation(landscape?ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE:ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);immersive();}
 void touch(){lastInteraction=SystemClock.elapsedRealtime();}
 void setControls(boolean visible){uiVisible=visible;top.setVisibility(visible?View.VISIBLE:View.GONE);controls.setVisibility(visible?View.VISIBLE:View.GONE);if(visible)touch();}
 TextView text(String s,int size){TextView t=new TextView(this);t.setText(s);t.setTextColor(Color.WHITE);t.setTextSize(size);t.setGravity(Gravity.CENTER_VERTICAL);return t;}
 TextView action(String s,String description){TextView t=text(s,25);t.setGravity(Gravity.CENTER);t.setContentDescription(description);t.setMinHeight(dp(48));t.setMinWidth(dp(48));android.util.TypedValue value=new android.util.TypedValue();getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless,value,true);t.setBackgroundResource(value.resourceId);return t;}
 void updatePlay(){if(player!=null)play.setText(player.isPlaying()?"Ⅱ":"▶");}
 void startPlayer(boolean software){
  if(destroyed)return;
  try{
   if(player!=null){resumePosition=player.getCurrentPosition();player.release();}
   prepared=false;rendering=false;frames=0;lastFrameAt=SystemClock.elapsedRealtime();playerError="";
   DefaultRenderersFactory renderers=new DefaultRenderersFactory(this).setEnableDecoderFallback(true);
   if(software)renderers.setMediaCodecSelector((mime,secure,tunnel)->{List<MediaCodecInfo> list=new ArrayList<>(MediaCodecSelector.DEFAULT.getDecoderInfos(mime,secure,tunnel));list.sort((a,b)->Boolean.compare(isSoftware(b.name),isSoftware(a.name)));return list;});
   DefaultLoadControl buffer=new DefaultLoadControl.Builder().setBufferDurationsMs(15000,45000,1800,3500).setTargetBufferBytes(32*1024*1024).setPrioritizeTimeOverSizeThresholds(false).build();
   player=new ExoPlayer.Builder(this,renderers).setLoadControl(buffer).build();player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),true);player.setPlaybackSpeed(speed);player.setVideoTextureView(surface);
   player.setVideoFrameMetadataListener((presentation,release,format,media)->{frames++;lastFrameAt=SystemClock.elapsedRealtime();});
   player.addListener(new Player.Listener(){public void onPlaybackStateChanged(int state){prepared=state==Player.STATE_READY||state==Player.STATE_ENDED;updatePlay();}public void onIsPlayingChanged(boolean playing){updatePlay();}public void onRenderedFirstFrame(){rendering=true;}public void onVideoSizeChanged(VideoSize size){vw=size.width;vh=size.height;pixelRatio=size.pixelWidthHeightRatio;fit();}public void onPlayerError(PlaybackException e){playerError=e.getErrorCodeName();prepared=false;setControls(true);}});
   final int selectedQuality=quality;final boolean selectedCompatible=compatible;
   DataSource.Factory factory=()->new Source(this,video,local,selectedCompatible,selectedQuality);
   Uri uri=Uri.parse(video==null?"oldylocal:"+local:"https://oldy.invalid/video.mp4");player.setMediaSource(new ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(uri)));
   if(resumePosition>0)player.seekTo(resumePosition);player.prepare();player.play();
  }catch(Exception e){playerError=Api.message(e);status.setText(playerError);}
 }
 static boolean isSoftware(String name){return name.startsWith("c2.android.")||name.startsWith("OMX.google.");}
 void loadQualities(){if(video==null)return;io.execute(()->{try{JSONObject r=new Api(this).call("/videos/qualities?id="+video,null,ChatService.vault(this).token());handler.post(()->qualityOptions=r.optJSONArray("items"));}catch(Exception ignored){}});}
 void showSettings(){touch();setControls(true);LinearLayout list=new LinearLayout(this);list.setOrientation(1);list.setPadding(dp(16),dp(8),dp(16),dp(16));list.setBackgroundColor(0xff151d2c);AlertDialog dialog=new AlertDialog.Builder(this,android.R.style.Theme_Material_Dialog_NoActionBar).setView(list).create();
  menuItem(list,I18n.t("Скорость · ")+speed+"×",()->{dialog.dismiss();showSpeed();});menuItem(list,I18n.t("Качество · ")+(quality>0?quality+"p":compatible?I18n.t("Совместимое"):I18n.t("Оригинал")),()->{dialog.dismiss();showQuality();});menuItem(list,I18n.t("Повторить загрузку"),()->{dialog.dismiss();startPlayer(false);});if(video!=null)menuItem(list,I18n.t("Совместимый формат"),()->{dialog.dismiss();prepareCompatible();});menuItem(list,I18n.t("Закрыть"),dialog::dismiss);dialog.show();Window w=dialog.getWindow();if(w!=null){w.setGravity(Gravity.BOTTOM);w.setLayout(-1,-2);}}
 void menuItem(LinearLayout list,String title,Runnable action){TextView t=text(title,16);t.setPadding(dp(16),dp(12),dp(16),dp(12));t.setMinHeight(dp(56));t.setOnClickListener(v->{touch();action.run();});list.addView(t,new LinearLayout.LayoutParams(-1,-2));}
 void showSpeed(){String[] labels={"0.5×","0.75×","1×","1.25×","1.5×","1.75×","2×"};float[] values={.5f,.75f,1,1.25f,1.5f,1.75f,2};new AlertDialog.Builder(this,android.R.style.Theme_Material_Dialog_Alert).setTitle(I18n.t("Скорость видео")).setItems(labels,(d,n)->{speed=values[n];if(player!=null)player.setPlaybackSpeed(speed);touch();}).show();}
 void showQuality(){ArrayList<String> labels=new ArrayList<>();ArrayList<Integer> values=new ArrayList<>();labels.add(I18n.t("Оригинал"));values.add(-1);if(video!=null&&qualityOptions!=null)for(int n=0;n<qualityOptions.length();n++){JSONObject q=qualityOptions.optJSONObject(n);if(q==null)continue;int h=q.optInt("quality");labels.add(h+"p"+(q.optString("state").equals("ready")?"":I18n.t(" · подготовить")));values.add(h);}new AlertDialog.Builder(this,android.R.style.Theme_Material_Dialog_Alert).setTitle(I18n.t("Качество видео")).setItems(labels.toArray(new String[0]),(d,n)->{int q=values.get(n);if(q<0){conversionGeneration++;converting=false;conversionMessage="";quality=0;compatible=false;startPlayer(false);}else prepareQuality(q);}).setNegativeButton(I18n.t("Отмена"),null).show();loadQualities();}
 void prepareCompatible(){prepareQuality(0);}
 void prepareQuality(int height){if(video==null||destroyed)return;int generation=++conversionGeneration;requestedQuality=height;converting=true;conversionMessage=I18n.t("Готовим видео · текущий ролик можно смотреть");setControls(true);pollConversion(generation,height,true);}
 void pollConversion(int generation,int height,boolean retry){if(destroyed||generation!=conversionGeneration)return;io.execute(()->{try{JSONObject data=new JSONObject().put("id",video);if(retry)data.put("retry",true);if(height>0)data.put("quality",height);JSONObject r=new Api(this).call(height>0?"/videos/quality":"/videos/compatible",data,ChatService.vault(this).token());String state=r.optString("state");handler.post(()->{if(destroyed||generation!=conversionGeneration)return;if(state.equals("ready")){converting=false;conversionMessage="";quality=height;compatible=height==0;startPlayer(false);loadQualities();}else if(state.equals("error")){converting=false;conversionMessage=I18n.t("Не удалось подготовить качество. Оригинал доступен.");}else{conversionMessage=I18n.t("Подготовка качества · можно продолжать просмотр");handler.postDelayed(()->pollConversion(generation,height,false),4000);}});}catch(Exception e){handler.post(()->{if(generation!=conversionGeneration||destroyed)return;converting=false;conversionMessage=Api.message(e);});}});}
 void fit(){root.post(()->{if(destroyed||vw<1||vh<1)return;int w=root.getWidth()-root.getPaddingLeft()-root.getPaddingRight(),h=root.getHeight()-root.getPaddingTop()-root.getPaddingBottom();if(w<1||h<1)return;float ratio=vw*Math.max(.1f,pixelRatio)/vh;if(w>h*ratio)w=Math.round(h*ratio);else h=Math.round(w/ratio);android.view.ViewGroup.LayoutParams current=surface.getLayoutParams();if(current.width!=w||current.height!=h)surface.setLayoutParams(new FrameLayout.LayoutParams(w,h,Gravity.CENTER));});}
 String time(long ms){long sec=Math.max(0,ms)/1000;return String.format(Locale.ROOT,"%d:%02d",sec/60,sec%60);}
 void tick(){if(destroyed)return;if(player!=null){long duration=player.getDuration();if(duration>0)seek.setMax((int)Math.min(Integer.MAX_VALUE,duration));if(!seeking)seek.setProgress((int)player.getCurrentPosition());seek.setSecondaryProgress((int)player.getBufferedPosition());String state=!playerError.isEmpty()?I18n.t("Не удалось открыть видео. Нажми ⚙ для повторной загрузки"):player.getPlaybackState()==Player.STATE_BUFFERING?I18n.t("Подгружаем…"):time(player.getCurrentPosition())+" / "+time(duration);status.setText(state+(conversionMessage.isEmpty()?"":"\n"+conversionMessage));if(uiVisible&&player.isPlaying()&&!seeking&&!converting&&SystemClock.elapsedRealtime()-lastInteraction>4500)setControls(false);}handler.postDelayed(this::tick,400);}
 public void onConfigurationChanged(Configuration c){super.onConfigurationChanged(c);landscape=c.orientation==Configuration.ORIENTATION_LANDSCAPE;immersive();fit();setControls(true);}
 public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);if(focus)immersive();}
 protected void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);b.putLong("position",player==null?resumePosition:player.getCurrentPosition());b.putFloat("speed",speed);b.putInt("quality",quality);b.putBoolean("compatible",compatible);}
 protected void onPause(){super.onPause();if(player!=null)player.pause();}
 protected void onDestroy(){destroyed=true;handler.removeCallbacksAndMessages(null);if(player!=null){player.release();player=null;}io.shutdownNow();super.onDestroy();}
 static class RotatePhone extends View {
  final Paint p=new Paint(3);final long started=SystemClock.uptimeMillis();RotatePhone(Context c){super(c);setClickable(true);setFocusable(true);}
  protected void onDraw(Canvas c){super.onDraw(c);float s=Math.min(getWidth(),getHeight())/52f;c.save();c.translate(getWidth()/2f,getHeight()/2f);c.scale(s,s);p.setStyle(Paint.Style.FILL);p.setColor(0x443ad8ed);c.drawCircle(0,0,24,p);float phase=(SystemClock.uptimeMillis()-started)%3300/3300f;float angle=phase<.5f?-90*(float)(.5-.5*Math.cos(phase*2*Math.PI)):-90;c.save();c.rotate(angle);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2);p.setColor(0xff9af8ff);c.drawRoundRect(-7,-12,7,12,3,3,p);c.drawLine(-2,8,2,8,p);c.restore();p.setStrokeWidth(1.6f);c.drawArc(-19,-19,19,19,-85,70,false,p);c.drawLine(18,-6,12,-7,p);c.drawLine(18,-6,18,-12,p);c.restore();if(isShown())postInvalidateDelayed(40);}
 }

 static final class Source extends BaseDataSource implements AutoCloseable {
  final Context context;final String id,local;final boolean compatible;final int quality;HttpsURLConnection connection;InputStream input;Uri uri;long left;
  Source(Context c,String video,String key,boolean variant){this(c,video,key,variant,0);}
  Source(Context c,String video,String key,boolean variant,int height){super(video!=null);context=c.getApplicationContext();id=video;local=key;compatible=variant;quality=height;}
  public long open(DataSpec spec)throws IOException{
   transferInitializing(spec);
   try{
    if(id==null){byte[] data=MediaFiles.bytes(context,local);if(spec.position>data.length)throw new EOFException();left=spec.length==C.LENGTH_UNSET?data.length-spec.position:Math.min(spec.length,data.length-spec.position);input=new ByteArrayInputStream(data,(int)spec.position,(int)left);uri=spec.uri;}
    else{
     if(!id.matches("[a-f0-9-]{36}"))throw new IOException("Invalid video");Api api=new Api(context);String base=Api.live.isEmpty()?api.url():Api.live;
     uri=Uri.parse(base+"/video-stream/"+id+(quality>0?"?quality="+quality:compatible?"?compatible=1":""));connection=(HttpsURLConnection)new URL(uri.toString()).openConnection();connection.setSSLSocketFactory(api.factory());connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(15000);connection.setReadTimeout(20000);connection.setRequestProperty("Authorization","Bearer "+ChatService.vault(context).token());connection.setRequestProperty("Accept-Encoding","identity");connection.setRequestProperty("Range","bytes="+spec.position+"-"+(spec.length==C.LENGTH_UNSET?"":Long.toString(spec.position+spec.length-1)));
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
