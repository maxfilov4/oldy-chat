package chat.oldy;
import android.content.*;import android.graphics.*;import android.view.*;

/** Eight original vector stickers: Oldy with a cap, headphones and silver moustache. */
final class GamerSticker extends View {
 static final String[] CAPTIONS={"НА СВЯЗИ","GG!","ЕЩЁ КАТОЧКУ","БЕЗ ЛАГОВ","ПАУЗА НА КОФЕ","РЕТРО НАВСЕГДА","ВОТ ЭТО FPS!","ОБНЯЛ"};
 final int style;final Paint p=new Paint(3);
 GamerSticker(Context c,int n){super(c);style=Math.floorMod(n,CAPTIONS.length);setContentDescription(I18n.t(CAPTIONS[style]));}
 void color(int c){p.setColor(c);p.setStyle(Paint.Style.FILL);}
 protected void onDraw(Canvas c){float z=Math.min(getWidth()/140f,getHeight()/140f);c.save();c.translate((getWidth()-140*z)/2,(getHeight()-140*z)/2);c.scale(z,z);
  float t=Notices.prefs(getContext()).getBoolean("animations",true)?(android.os.SystemClock.uptimeMillis()%8000)/1000f:0;float bob=(float)Math.sin(t*2)*1.2f;c.translate(0,bob);
  color(0xff343c63);c.drawRoundRect(14,15,126,118,25,25,p);color(style%2==0?0xff5cf0e1:0xffc095ff);c.drawRoundRect(19,20,121,111,23,23,p);color(0xfff0be97);c.drawOval(37,31,105,105,p);
  color(0xffeee5d9);c.drawOval(35,61,49,89,p);c.drawOval(95,61,109,89,p);color(0xff22324e);c.drawRoundRect(33,44,44,85,5,5,p);c.drawRoundRect(98,44,109,85,5,5,p);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(5);c.drawArc(38,15,104,88,188,166,false,p);
  color(0xff374269);c.drawRoundRect(38,20,103,47,16,16,p);color(0xff202a45);c.drawRoundRect(28,39,108,50,5,5,p);color(0xff5cf0e1);p.setTypeface(Typeface.create("monospace",1));p.setTextSize(13);c.drawText("OLDY",52,37,p);
  color(0xff29324b);c.drawRoundRect(42,54,66,70,5,5,p);c.drawRoundRect(76,54,100,70,5,5,p);p.setStrokeWidth(3);c.drawLine(66,59,76,59,p);color(0xffb5e9ff);c.drawLine(46,57,59,57,p);c.drawLine(80,57,93,57,p);
  color(0xffbd856c);c.drawOval(66,64,78,78,p);color(0xfff1e9df);c.drawOval(48,76,75,90,p);c.drawOval(71,76,96,90,p);color(0xff62384c);c.drawArc(62,81,82,99,0,180,true,p);
  if(style==4){color(0xffedf0ff);c.drawRoundRect(91,82,118,109,5,5,p);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(4);c.drawOval(109,87,127,102,p);p.setStyle(Paint.Style.FILL);}else if(style==7){MotionEmoji.heart(c,109,87,12,p);}else if(style==6){color(0xffffea99);p.setTextSize(27);c.drawText("!",110,95,p);}
  color(0xff101b30);c.drawRoundRect(3,109,137,136,9,9,p);color(0xffeffaff);p.setTextAlign(Paint.Align.CENTER);p.setTypeface(Typeface.create("sans-serif-condensed",1));String caption=I18n.t(CAPTIONS[style]);p.setTextSize(13);if(p.measureText(caption)>124)p.setTextSize(13*124/p.measureText(caption));c.drawText(caption,70,127,p);p.setTextAlign(Paint.Align.LEFT);c.restore();if(isShown()&&Notices.prefs(getContext()).getBoolean("animations",true))postInvalidateDelayed(100);
 }
}
