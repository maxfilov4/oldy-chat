package chat.oldy;
import android.app.*;import android.os.*;import android.widget.*;import android.view.*;import android.net.Uri;import java.io.*;
public class RoundReviewActivity extends Activity {
 VideoView video;
 public void onCreate(Bundle b){super.onCreate(b);try{File f=new File(getIntent().getStringExtra("path"));if(!f.getCanonicalFile().getParentFile().equals(getCacheDir().getCanonicalFile())||!f.getName().startsWith("round-"))throw new IOException();video=new VideoView(this);setContentView(video);video.setVideoURI(Uri.fromFile(f));MediaController controls=new MediaController(this);controls.setAnchorView(video);video.setMediaController(controls);video.setOnPreparedListener(p->video.start());}catch(Exception e){finish();}}
 protected void onPause(){super.onPause();if(video!=null)video.pause();}
 protected void onDestroy(){if(video!=null)video.stopPlayback();super.onDestroy();}
}
