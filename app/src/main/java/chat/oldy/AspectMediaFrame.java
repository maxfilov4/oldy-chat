package chat.oldy;
import android.content.Context;import android.graphics.*;import android.widget.*;import android.view.*;
/** Media defines the card height, never the other way around. */
final class AspectMediaFrame extends FrameLayout {
 float ratio=9f/16;boolean round;
 AspectMediaFrame(Context c,boolean round){super(c);this.round=round;setContentDescription(round?I18n.t("Видеокружок"):I18n.t("Превью видео"));setBackgroundColor(0xff162538);setOutlineProvider(new ViewOutlineProvider(){public void getOutline(View v,Outline o){if(round)o.setOval(0,0,v.getWidth(),v.getHeight());else o.setRoundRect(0,0,v.getWidth(),v.getHeight(),12*getResources().getDisplayMetrics().density);}});setClipToOutline(true);}
 void dimensions(Bitmap image){if(image==null||round)return;float next=Math.max(.25f,Math.min(3f,(float)image.getWidth()/image.getHeight()));if(Math.abs(ratio-next)>.005f){ratio=next;requestLayout();}}
 protected void onMeasure(int width,int height){int w=MeasureSpec.getSize(width);if(!round)w=Math.min(w,Math.round(320*getResources().getDisplayMetrics().density*ratio));int h=round?w:Math.round(w/ratio);super.onMeasure(MeasureSpec.makeMeasureSpec(w,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(h,MeasureSpec.EXACTLY));}
 static final class Cover extends ImageView{final AspectMediaFrame frame;Cover(Context c,AspectMediaFrame frame){super(c);this.frame=frame;setScaleType(ScaleType.CENTER_CROP);}public void setImageBitmap(Bitmap b){super.setImageBitmap(b);frame.dimensions(b);}}
}
