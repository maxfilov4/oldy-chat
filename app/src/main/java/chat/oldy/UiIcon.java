package chat.oldy;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** Small vector controls remain legible with every system font and emoji set. */
final class UiIcon extends View {
 final Paint paint=new Paint(3);String symbol;int color;
 UiIcon(Context c,String symbol,int color){super(c);this.symbol=symbol;this.color=color;setFocusable(true);}
 void symbol(String value){symbol=value;invalidate();}
 protected void onDraw(Canvas c){super.onDraw(c);float size=Math.min(getWidth(),getHeight())*.50f;
  c.save();c.translate((getWidth()-size)/2,(getHeight()-size)/2);c.scale(size/24,size/24);
  paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2.2f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);paint.setColor(color);
  Path p=new Path();
  switch(symbol){
   case "back":c.drawLine(20,12,4,12,paint);p.moveTo(11,5);p.lineTo(4,12);p.lineTo(11,19);c.drawPath(p,paint);break;
   case "close":c.drawLine(6,6,18,18,paint);c.drawLine(18,6,6,18,paint);break;
   case "mic":c.drawRoundRect(8,2,16,15,4,4,paint);c.drawArc(4,5,20,20,0,180,false,paint);c.drawLine(12,20,12,23,paint);break;
   case "pause":c.drawLine(8,5,8,19,paint);c.drawLine(16,5,16,19,paint);break;
   case "play":p.moveTo(8,4);p.lineTo(20,12);p.lineTo(8,20);p.close();paint.setStyle(Paint.Style.FILL);c.drawPath(p,paint);break;
   case "send":p.moveTo(3,5);p.lineTo(21,12);p.lineTo(3,19);p.lineTo(7,12);p.close();c.drawPath(p,paint);c.drawLine(7,12,15,12,paint);break;
   case "call":p.moveTo(5,3);p.lineTo(8,3);p.lineTo(10,8);p.lineTo(7,10);p.cubicTo(8,14,10,16,14,17);p.lineTo(16,14);p.lineTo(21,16);p.lineTo(21,19);p.cubicTo(20,24,2,18,3,5);p.close();c.drawPath(p,paint);break;
   case "bell":case "muted":p.moveTo(5,17);p.lineTo(7,14);p.lineTo(7,9);p.cubicTo(7,2,17,2,17,9);p.lineTo(17,14);p.lineTo(19,17);p.close();c.drawPath(p,paint);c.drawArc(9,17,15,22,0,180,false,paint);if(symbol.equals("muted"))c.drawLine(3,3,21,21,paint);break;
   case "block":c.drawCircle(12,12,9,paint);c.drawLine(6,6,18,18,paint);break;
   case "chat":p.moveTo(5,3);p.lineTo(19,3);p.quadTo(22,3,22,6);p.lineTo(22,15);p.quadTo(22,18,19,18);p.lineTo(9,18);p.lineTo(3,22);p.lineTo(3,6);p.quadTo(3,3,5,3);c.drawPath(p,paint);c.drawLine(8,8,17,8,paint);c.drawLine(8,12,14,12,paint);break;
   case "flip":c.drawArc(3,3,21,21,210,145,false,paint);c.drawArc(3,3,21,21,30,145,false,paint);p.moveTo(17,5);p.lineTo(21,11);p.lineTo(23,5);c.drawPath(p,paint);p.reset();p.moveTo(7,19);p.lineTo(3,13);p.lineTo(1,19);c.drawPath(p,paint);break;
   case "share":p.moveTo(8,14);p.lineTo(17,5);p.moveTo(10,5);p.lineTo(17,5);p.lineTo(17,12);c.drawPath(p,paint);p.reset();p.moveTo(5,9);p.lineTo(3,9);p.lineTo(3,21);p.lineTo(16,21);p.lineTo(16,17);c.drawPath(p,paint);break;
   default:c.drawRoundRect(3,3,21,21,5,5,paint);c.drawLine(7,9,17,9,paint);c.drawLine(7,15,14,15,paint);
  }
  c.restore();
 }
}
