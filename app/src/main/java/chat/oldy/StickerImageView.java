package chat.oldy;
import android.content.Context;import android.graphics.*;import android.graphics.drawable.*;import android.os.Build;import android.view.View;import android.widget.ImageView;import java.nio.ByteBuffer;

/** Animated WebP is decoded locally; playback stops when detached or reduced motion is enabled. */
final class StickerImageView extends ImageView {
 StickerImageView(Context c){super(c);setScaleType(ScaleType.FIT_CENTER);}
 void bytes(byte[] value)throws Exception{if(value.length<16||value.length>AnimatedStickerCodec.LIMIT)throw new IllegalArgumentException();if(Build.VERSION.SDK_INT>=28){Drawable decoded=ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(value)),(d,i,s)->{if(i.getSize().getWidth()>512||i.getSize().getHeight()>512)throw new IllegalArgumentException();});setImageDrawable(decoded);play();}else{Bitmap bitmap=BitmapFactory.decodeByteArray(value,0,value.length);setImageBitmap(bitmap);}}
 void play(){Drawable d=getDrawable();if(d instanceof Animatable){boolean on=isAttachedToWindow()&&getWindowVisibility()==View.VISIBLE&&Notices.prefs(getContext()).getBoolean("animations",true);if(on)((Animatable)d).start();else ((Animatable)d).stop();}}
 protected void onAttachedToWindow(){super.onAttachedToWindow();play();}protected void onDetachedFromWindow(){Drawable d=getDrawable();if(d instanceof Animatable)((Animatable)d).stop();super.onDetachedFromWindow();}protected void onWindowVisibilityChanged(int v){super.onWindowVisibilityChanged(v);play();}
}
