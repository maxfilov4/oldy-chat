package chat.oldy;
import android.app.*;import android.content.*;import android.os.*;import java.io.*;import java.nio.*;

public class SpeechInstrumentation extends Instrumentation {
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 public void onCreate(Bundle args){super.onCreate(args);start();}
 byte[] fixture(String path)throws Exception{try(InputStream in=getContext().getAssets().open(path);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toByteArray();}}
 public void onStart(){Bundle result=new Bundle();try{Context c=getTargetContext();SpeechModels.Progress progress=new SpeechModels.Progress(){public void status(String s){}public boolean cancelled(){return false;}};
  for(String language:new String[]{"en","ru"}){
   try(InputStream in=getContext().getAssets().open("speech/model-"+language+".zip")){SpeechModels.install(c,language,in,progress);}
   check(SpeechModels.ready(c,language),"Model installation did not complete");String text=SpeechNotes.recognize(SpeechModels.directory(c,language),fixture("speech/"+language+".wav"),progress);
   if(language.equals("en"))check(text.contains("one")&&text.contains("zero"),"English audio was not recognized: "+text);
   else{int found=0;for(String token:new String[]{"привет","подумала","встретиться","годы"})if(text.contains(token))found++;check(found>=3,"Russian audio was not recognized: "+text);}
  }
  for(int rate:new int[]{8000,44100,48000}){String actual=SpeechNotes.recognize(SpeechModels.directory(c,"en"),fixture("speech/en-"+rate+".wav"),progress);check(actual.contains("one")&&actual.contains("zero"),"Audio resampling failed at "+rate);}
  short[] samples=new short[601*16000];File tooLong=new File(c.getCacheDir(),"speech-limit.wav");AudioCodec.wav(tooLong,samples,16000);byte[] bytes=java.nio.file.Files.readAllBytes(tooLong.toPath());boolean refused=false;try{SpeechDecoder.validateDuration(bytes);}catch(IOException expected){refused=expected.getMessage().equals(SpeechDecoder.limit());}tooLong.delete();check(refused,"Audio over 10 minutes was accepted");
  boolean cancelled=false;try{SpeechDecoder.decode(fixture("speech/en.wav"),new SpeechDecoder.Consumer(){public boolean cancelled(){return true;}public void accept(byte[] b,int n,double s){throw new AssertionError("Cancelled audio consumed");}});}catch(java.util.concurrent.CancellationException expected){cancelled=true;}check(cancelled,"Recognition cancellation was ignored");
  result.putString("stream","OLDI_SPEECH_PASS: actual Russian and English speech recognized by on-device Vosk; 601-second file rejected; cancellation verified\n");finish(-1,result);
 }catch(Throwable e){result.putString("stream","OLDI_SPEECH_FAIL: "+android.util.Log.getStackTraceString(e));finish(0,result);}}
}
