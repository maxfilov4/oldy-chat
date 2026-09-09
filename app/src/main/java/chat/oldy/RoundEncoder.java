package chat.oldy;

import android.media.*;
import android.view.Surface;
import org.webrtc.*;
import java.io.*;
import java.nio.*;
import java.util.*;

/** One video encoder and one audio timeline survive camera changes. */
final class RoundEncoder {
 MediaCodec video,audio;MediaMuxer mux;AudioRecord mic;EglRenderer renderer;Surface input;
 final long started=System.nanoTime();Thread videoThread,audioThread;final File file;volatile long drainUntil;
 volatile boolean recording=true;volatile Exception failure;int videoTrack=-1,audioTrack=-1;boolean muxStarted;volatile int videoSamples,audioSamples;long pendingBytes;
 final List<Packet> pending=new ArrayList<>();
 static class Packet {boolean video;byte[] bytes;long pts;int flags;Packet(boolean v,byte[] b,long p,int f){video=v;bytes=b;pts=p;flags=f;}}
 RoundEncoder(File target,EglBase.Context shared)throws Exception{
  file=target;try{mux=new MediaMuxer(file.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
  video=MediaCodec.createEncoderByType("video/avc");MediaFormat vf=MediaFormat.createVideoFormat("video/avc",480,480);vf.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);vf.setInteger(MediaFormat.KEY_BIT_RATE,1800000);vf.setInteger(MediaFormat.KEY_FRAME_RATE,30);vf.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1);video.configure(vf,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);input=video.createInputSurface();video.start();
  audio=MediaCodec.createEncoderByType("audio/mp4a-latm");MediaFormat af=MediaFormat.createAudioFormat("audio/mp4a-latm",44100,1);af.setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC);af.setInteger(MediaFormat.KEY_BIT_RATE,96000);af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,8192);audio.configure(af,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);audio.start();
  int min=AudioRecord.getMinBufferSize(44100,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);mic=new AudioRecord(MediaRecorder.AudioSource.CAMCORDER,44100,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(min,16384));if(mic.getState()!=AudioRecord.STATE_INITIALIZED)throw new IOException("Не удалось включить микрофон");
  renderer=new EglRenderer("oldy-round-encoder");renderer.init(shared,EglBase.CONFIG_RECORDABLE,new GlRectDrawer(),true);renderer.setLayoutAspectRatio(1);renderer.createEglSurface(input);renderer.setFpsReduction(30);
  mic.startRecording();videoThread=new Thread(()->videoLoop(),"oldy-round-video");audioThread=new Thread(()->audioLoop(),"oldy-round-audio");videoThread.start();audioThread.start();}catch(Exception error){recording=false;cleanup();file.delete();throw error;}
 }
 void frame(VideoFrame original){EglRenderer sink=renderer;if(!recording||sink==null)return;original.getBuffer().retain();VideoFrame f=new VideoFrame(original.getBuffer(),original.getRotation(),Math.max(0,System.nanoTime()-started));try{sink.onFrame(f);}finally{f.release();}}
 synchronized void format(boolean v,MediaFormat f)throws IOException{if(v)videoTrack=mux.addTrack(f);else audioTrack=mux.addTrack(f);if(videoTrack>=0&&audioTrack>=0&&!muxStarted){mux.start();muxStarted=true;for(Packet p:pending)write(p);pending.clear();pendingBytes=0;}}
 synchronized void packet(boolean v,ByteBuffer buffer,MediaCodec.BufferInfo info)throws IOException{if(info.size==0||(info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)!=0)return;byte[] bytes=new byte[info.size];buffer.position(info.offset);buffer.limit(info.offset+info.size);buffer.get(bytes);Packet p=new Packet(v,bytes,Math.max(0,info.presentationTimeUs),info.flags);if(muxStarted)write(p);else{pendingBytes+=bytes.length;if(pendingBytes>4*1024*1024)throw new IOException("Не удалось начать запись звука");pending.add(p);}}
 void write(Packet p){MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();info.set(0,p.bytes.length,p.pts,p.flags);mux.writeSampleData(p.video?videoTrack:audioTrack,ByteBuffer.wrap(p.bytes),info);if(p.video)videoSamples++;else audioSamples++;}
 boolean drain(MediaCodec codec,boolean v,long timeout)throws Exception{MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();int n=codec.dequeueOutputBuffer(info,timeout);if(n==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED)format(v,codec.getOutputFormat());else if(n>=0){try{packet(v,codec.getOutputBuffer(n),info);}finally{codec.releaseOutputBuffer(n,false);}if((info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0)return true;}return false;}
 void videoLoop(){try{while((recording||System.currentTimeMillis()<drainUntil)&&!drain(video,true,10000)){} }catch(Exception e){failure=e;}}
 void audioLoop(){byte[] pcm=new byte[2048];long samples=0,baseUs=Math.max(0,(System.nanoTime()-started)/1000);try{while(recording){int n=mic.read(pcm,0,pcm.length);if(n<=0){if(recording)throw new IOException("Микрофон недоступен");break;}int index=audio.dequeueInputBuffer(10000);if(index>=0){ByteBuffer b=audio.getInputBuffer(index);b.clear();b.put(pcm,0,n);audio.queueInputBuffer(index,0,n,baseUs+samples*1000000/44100,0);samples+=n/2;}drain(audio,false,0);}int i;long until=System.currentTimeMillis()+3000;while((i=audio.dequeueInputBuffer(10000))<0&&System.currentTimeMillis()<until)drain(audio,false,0);if(i>=0)audio.queueInputBuffer(i,0,0,baseUs+samples*1000000/44100,MediaCodec.BUFFER_FLAG_END_OF_STREAM);while(System.currentTimeMillis()<until&&!drain(audio,false,10000)){} }catch(Exception e){failure=e;}}
 void finish()throws Exception{
  drainUntil=System.currentTimeMillis()+4500;recording=false;renderer.release();renderer=null;video.signalEndOfInputStream();try{mic.stop();}catch(Exception ignored){}
  audioThread.join(5000);videoThread.join(5000);
  boolean complete=!audioThread.isAlive()&&!videoThread.isAlive();
  try{if(complete&&muxStarted)mux.stop();}catch(Exception e){failure=e;}finally{cleanup();}
  if(!complete||failure!=null||videoSamples<8||audioSamples<4||file.length()<1000){file.delete();throw new IOException("Не удалось сохранить кружок. Попробуй записать ещё раз.",failure);}
 }
 void cleanup(){
  if(renderer!=null)try{renderer.release();}catch(Exception ignored){}renderer=null;
  if(mic!=null){try{mic.stop();}catch(Exception ignored){}mic.release();mic=null;}
  for(MediaCodec codec:new MediaCodec[]{audio,video})if(codec!=null){try{codec.stop();}catch(Exception ignored){}try{codec.release();}catch(Exception ignored){}}
  if(mux!=null)try{mux.release();}catch(Exception ignored){}mux=null;
  if(input!=null){input.release();input=null;}
 }
}
