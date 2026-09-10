package chat.oldy;
import android.graphics.Bitmap;import java.io.*;import java.nio.charset.StandardCharsets;

/** WebP animation container around Android's real WebP encoder, per Google's RIFF spec.
 * Frames replace the complete canvas; no proprietary recipient-only animation metadata. */
final class AnimatedStickerCodec {
 static final int LIMIT=350000;
 static byte[] encode(Bitmap image,int motion)throws Exception{for(int size:new int[]{384,320,256}){byte[] value=encode(image,motion,size,size==384?68:55);if(value.length<=LIMIT)return value;}throw new IOException(I18n.t("Стикер слишком большой. Вырежьте персонажа из фона."));}
 static byte[] encode(Bitmap image,int motion,int size,int quality)throws Exception{ByteArrayOutputStream data=new ByteArrayOutputStream();ByteArrayOutputStream features=new ByteArrayOutputStream();features.write(new byte[]{0x12,0,0,0});le(features,size-1,3);le(features,size-1,3);chunk(data,"VP8X",features.toByteArray());chunk(data,"ANIM",new byte[6]);
  for(int i=0;i<16;i++){Bitmap frame=CartoonSticker.frame(image,size,i,16,motion);ByteArrayOutputStream encoded=new ByteArrayOutputStream();try{if(!frame.compress(Bitmap.CompressFormat.WEBP,quality,encoded))throw new IOException();}finally{frame.recycle();}byte[] bytes=encoded.toByteArray();ByteArrayOutputStream anim=new ByteArrayOutputStream();le(anim,0,3);le(anim,0,3);le(anim,size-1,3);le(anim,size-1,3);le(anim,90,3);anim.write(2);boolean pixels=false;for(int at=12;at+8<=bytes.length;){String tag=new String(bytes,at,4,StandardCharsets.US_ASCII);int n=read(bytes,at+4,4);if(n<0||n>bytes.length-at-8)throw new IOException("WEBP_CHUNK");if(tag.equals("ALPH")||tag.equals("VP8 ")||tag.equals("VP8L")){anim.write(bytes,at,8+n+(n&1));if(!tag.equals("ALPH"))pixels=true;}at+=8+n+(n&1);}if(!pixels)throw new IOException("WEBP_FRAME");chunk(data,"ANMF",anim.toByteArray());}
  ByteArrayOutputStream out=new ByteArrayOutputStream();out.write("RIFF".getBytes(StandardCharsets.US_ASCII));le(out,data.size()+4,4);out.write("WEBP".getBytes(StandardCharsets.US_ASCII));data.writeTo(out);return out.toByteArray();
 }
 static void chunk(ByteArrayOutputStream b,String name,byte[] value)throws IOException{b.write(name.getBytes(StandardCharsets.US_ASCII));le(b,value.length,4);b.write(value);if(value.length%2!=0)b.write(0);}
 static void le(ByteArrayOutputStream b,int value,int count){for(int i=0;i<count;i++)b.write((value>>>(8*i))&255);}static int read(byte[] b,int at,int n){int v=0;for(int i=0;i<n;i++)v|=(b[at+i]&255)<<(8*i);return v;}
 static boolean animated(byte[] b){return b.length>=30&&b[0]=='R'&&b[8]=='W'&&b[12]=='V'&&b[15]=='X'&&(b[20]&2)!=0;}
}
