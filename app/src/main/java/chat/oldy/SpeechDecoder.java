package chat.oldy;
import android.media.*;import android.os.SystemClock;import java.io.*;import java.nio.*;

/** Bounded streaming PCM conversion. Duration metadata and decoded sample counts both enforce 10 minutes. */
final class SpeechDecoder {
 static final int RATE=16000,MAX_SECONDS=600;
 interface Consumer{void accept(byte[] pcm,int length,double seconds)throws Exception;boolean cancelled();}
 static MediaDataSource source(byte[] bytes){return new MediaDataSource(){public long getSize(){return bytes.length;}public int readAt(long pos,byte[] out,int offset,int length){if(pos<0||pos>=bytes.length)return -1;int n=(int)Math.min(length,bytes.length-pos);System.arraycopy(bytes,(int)pos,out,offset,n);return n;}public void close(){}};}
 static String limit(){return LegalCenter.tr("Распознавание доступно для аудио до 10 минут","Transcription is available for audio up to 10 minutes");}
 static void validateDuration(byte[] input)throws Exception{MediaExtractor e=new MediaExtractor();MediaDataSource data=source(input);try{e.setDataSource(data);boolean found=false;for(int i=0;i<e.getTrackCount();i++){MediaFormat f=e.getTrackFormat(i);if(f.getString(MediaFormat.KEY_MIME).startsWith("audio/")){found=true;if(f.containsKey(MediaFormat.KEY_DURATION)&&f.getLong(MediaFormat.KEY_DURATION)>MAX_SECONDS*1000000L)throw new IOException(limit());}}if(!found)throw new IOException("No audio track");}finally{e.release();data.close();}}
 static void decode(byte[] input,Consumer consumer)throws Exception{
  MediaExtractor extract=new MediaExtractor();MediaCodec codec=null;MediaDataSource source=source(input);
  try{extract.setDataSource(source);MediaFormat format=null;for(int i=0;i<extract.getTrackCount();i++){MediaFormat f=extract.getTrackFormat(i);if(f.getString(MediaFormat.KEY_MIME).startsWith("audio/")){format=f;extract.selectTrack(i);break;}}if(format==null)throw new IOException(LegalCenter.tr("В файле нет аудиодорожки","No audio track in this file"));
   if(format.containsKey(MediaFormat.KEY_DURATION)&&format.getLong(MediaFormat.KEY_DURATION)>MAX_SECONDS*1000000L)throw new IOException(limit());
   codec=MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));codec.configure(format,null,null,0);codec.start();boolean inputDone=false,outputDone=false;MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();int rate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE),channels=format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),encoding=AudioFormat.ENCODING_PCM_16BIT;long frames=0,outFrames=0,deadline=SystemClock.elapsedRealtime()+20*60000L;byte[] block=new byte[3200];int used=0;
   while(!outputDone){if(consumer.cancelled()||Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();if(SystemClock.elapsedRealtime()>deadline)throw new IOException(LegalCenter.tr("Распознавание заняло слишком много времени","Transcription took too long"));
    if(!inputDone){int index=codec.dequeueInputBuffer(10000);if(index>=0){ByteBuffer buffer=codec.getInputBuffer(index);int size=extract.readSampleData(buffer,0);if(size<0){codec.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true;}else{if(extract.getSampleTime()>MAX_SECONDS*1000000L)throw new IOException(limit());codec.queueInputBuffer(index,0,size,extract.getSampleTime(),0);extract.advance();}}}
    int index=codec.dequeueOutputBuffer(info,10000);if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){MediaFormat out=codec.getOutputFormat();rate=out.getInteger(MediaFormat.KEY_SAMPLE_RATE);channels=out.getInteger(MediaFormat.KEY_CHANNEL_COUNT);encoding=out.containsKey(MediaFormat.KEY_PCM_ENCODING)?out.getInteger(MediaFormat.KEY_PCM_ENCODING):AudioFormat.ENCODING_PCM_16BIT;if(encoding!=AudioFormat.ENCODING_PCM_16BIT&&encoding!=AudioFormat.ENCODING_PCM_FLOAT)throw new IOException("Unsupported PCM format");}
    else if(index>=0){if(channels<1||channels>8||rate<8000||rate>192000)throw new IOException("Unsupported audio format");ByteBuffer buffer=codec.getOutputBuffer(index);buffer.position(info.offset);buffer.limit(info.offset+info.size);buffer.order(ByteOrder.LITTLE_ENDIAN);int bytes=encoding==AudioFormat.ENCODING_PCM_FLOAT?4:2;
     while(buffer.remaining()>=channels*bytes){double mono=0;for(int c=0;c<channels;c++)mono+=encoding==AudioFormat.ENCODING_PCM_FLOAT?buffer.getFloat():buffer.getShort()/32768.0;mono/=channels;frames++;while(outFrames*rate<frames*(long)RATE){int sample=(int)(Math.max(-1,Math.min(1,mono))*32767);block[used++]=(byte)sample;block[used++]=(byte)(sample>>8);if(++outFrames>MAX_SECONDS*(long)RATE)throw new IOException(limit());if(used==block.length){consumer.accept(block,used,outFrames/(double)RATE);used=0;}}}
     outputDone=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;codec.releaseOutputBuffer(index,false);}
   }
   if(used>0)consumer.accept(block,used,outFrames/(double)RATE);if(outFrames<RATE/10)throw new IOException(LegalCenter.tr("В записи слишком мало звука","Too little audio in the recording"));
  }finally{if(codec!=null){try{codec.stop();}catch(Exception ignored){}codec.release();}extract.release();source.close();}
 }
}
