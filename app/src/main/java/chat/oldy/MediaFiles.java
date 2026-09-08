package chat.oldy;
import android.content.*;
import android.net.Uri;
import org.json.*;
import java.io.*;
import java.security.*;

final class MediaFiles {
 static final long MAX=25L*1024*1024;
 static File folder(Context c){File d=new File(c.getFilesDir(),"media");d.mkdirs();return d;}
 static File path(Context c,String key)throws Exception{if(!key.matches("[a-f0-9-]{36}"))throw new IOException("Файл недоступен");return new File(folder(c),key+".oldym");}
 static class Writer implements AutoCloseable{
  final File target,temp;final DataOutputStream out;final FileOutputStream disk;final MessageDigest digest;long size;boolean finished;
  Writer(Context c,String key)throws Exception{target=path(c,key);temp=new File(target+".part");disk=new FileOutputStream(temp);out=new DataOutputStream(disk);digest=MessageDigest.getInstance("SHA-256");}
  void put(byte[] b)throws Exception{if(b.length>32768||size+b.length>MAX)throw new IOException("Лимит вложения — 25 МБ");byte[] encrypted=Crypto.sealLocal(b);out.writeInt(encrypted.length);out.write(encrypted);digest.update(b);size+=b.length;}
  String finish()throws Exception{out.flush();disk.getFD().sync();out.close();if(!temp.renameTo(target))throw new IOException("Не удалось сохранить файл");finished=true;return Crypto.hex(digest.digest());}
  public void close(){try{out.close();}catch(Exception ignored){}if(!finished)temp.delete();}
 }
 static InputStream open(Context c,String key)throws Exception{return new InputStream(){final DataInputStream in=new DataInputStream(new FileInputStream(path(c,key)));byte[] chunk=new byte[0];int at;boolean eof;
  public int read()throws IOException{byte[] b=new byte[1];return read(b,0,1)<0?-1:b[0]&255;}
  public int read(byte[] b,int off,int len)throws IOException{if(len==0)return 0;if(eof)return -1;if(at==chunk.length){int first=in.read();if(first<0){eof=true;return -1;}try{int n=(first<<24)|(in.readUnsignedByte()<<16)|(in.readUnsignedByte()<<8)|in.readUnsignedByte();if(n<28||n>32796)throw new IOException("Файл повреждён");byte[] enc=new byte[n];in.readFully(enc);chunk=Crypto.openLocal(enc);at=0;}catch(Exception e){throw new IOException("Файл повреждён",e);}}int n=Math.min(len,chunk.length-at);System.arraycopy(chunk,at,b,off,n);at+=n;return n;}
  public void close()throws IOException{in.close();}
 };}
 static JSONObject importFile(Context c,Uri uri,String mime,String name)throws Exception{
  String key=java.util.UUID.randomUUID().toString();try(InputStream in=c.getContentResolver().openInputStream(uri);Writer w=new Writer(c,key)){byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1)w.put(java.util.Arrays.copyOf(b,n));if(w.size==0)throw new IOException("Пустой файл");String sha=w.finish();return new JSONObject().put("kind","file").put("mime",mime).put("name",name).put("size",w.size).put("sha256",sha).put("local",key);}
 }
 static byte[] bytes(Context c,String key)throws Exception{try(InputStream in=open(c,key);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1){if(out.size()+n>MAX)throw new IOException("Файл слишком большой");out.write(b,0,n);}return out.toByteArray();}}
}
