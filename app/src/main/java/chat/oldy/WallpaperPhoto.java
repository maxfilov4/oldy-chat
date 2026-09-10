package chat.oldy;

import android.content.Context;
import android.graphics.*;
import android.media.ExifInterface;
import android.net.Uri;
import java.io.*;

/** One private, bounded wallpaper copy; selecting a photo never uploads it. */
final class WallpaperPhoto {
 static final int REQUEST=47;
 private static Bitmap cached;
 private static long stamp;
 static File file(Context c){return new File(c.getFilesDir(),"wallpaper-photo.jpg");}
 static synchronized Bitmap load(Context c){
  File f=file(c);if(!f.isFile())return null;
  if(cached==null||stamp!=f.lastModified()){cached=BitmapFactory.decodeFile(f.getPath());stamp=f.lastModified();}
  return cached;
 }
 static void importPhoto(Context c,Uri uri)throws Exception{
  BitmapFactory.Options opt=new BitmapFactory.Options();opt.inJustDecodeBounds=true;
  try(InputStream in=c.getContentResolver().openInputStream(uri)){BitmapFactory.decodeStream(in,null,opt);}
  if(opt.outWidth<1||opt.outHeight<1)throw new IOException(I18n.t("Не удалось открыть фото"));
  opt.inSampleSize=1;while(Math.max(opt.outWidth,opt.outHeight)/opt.inSampleSize>2048)opt.inSampleSize*=2;
  opt.inJustDecodeBounds=false;Bitmap image;
  try(InputStream in=c.getContentResolver().openInputStream(uri)){image=BitmapFactory.decodeStream(in,null,opt);}
  if(image==null)throw new IOException(I18n.t("Не удалось открыть фото"));
  Matrix matrix=new Matrix();int orientation=1;
  try(InputStream in=c.getContentResolver().openInputStream(uri)){orientation=new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION,1);}catch(IOException ignored){}
  switch(orientation){case 2:matrix.setScale(-1,1);break;case 3:matrix.setRotate(180);break;case 4:matrix.setScale(1,-1);break;case 5:matrix.setRotate(90);matrix.postScale(-1,1);break;case 6:matrix.setRotate(90);break;case 7:matrix.setRotate(-90);matrix.postScale(-1,1);break;case 8:matrix.setRotate(-90);break;}
  if(!matrix.isIdentity()){Bitmap rotated=Bitmap.createBitmap(image,0,0,image.getWidth(),image.getHeight(),matrix,true);if(rotated!=image)image.recycle();image=rotated;}
  File target=file(c),pending=new File(c.getFilesDir(),"wallpaper-photo.pending");
  try{
   try(FileOutputStream out=new FileOutputStream(pending)){if(!image.compress(Bitmap.CompressFormat.JPEG,94,out))throw new IOException("Image encoding failed");out.getFD().sync();}
   android.system.Os.rename(pending.getPath(),target.getPath());
   synchronized(WallpaperPhoto.class){cached=null;stamp=0;}
  }finally{image.recycle();pending.delete();}
 }
}
