package chat.oldy;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** Small vector controls remain legible with every system font and emoji set. */
final class UiIcon extends View {
 final Paint paint=new Paint(3);String symbol;int color;float scale=.50f;
 UiIcon(Context c,String symbol,int color){super(c);this.symbol=symbol;this.color=color;setFocusable(true);}
 void symbol(String value){symbol=value;invalidate();}
 protected void onDraw(Canvas c){super.onDraw(c);float size=Math.min(getWidth(),getHeight())*scale;
  c.save();c.translate((getWidth()-size)/2,(getHeight()-size)/2);c.scale(size/24,size/24);
  paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2.2f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);paint.setColor(color);
  Path p=new Path();
  switch(symbol){
   case "profile":c.drawCircle(12,7,4,paint);p.moveTo(3,22);p.cubicTo(3,12,21,12,21,22);c.drawPath(p,paint);break;
   case "contacts":c.drawCircle(8,7,3,paint);c.drawCircle(17,8,2.5f,paint);p.moveTo(1,21);p.cubicTo(1,12,15,12,15,21);p.moveTo(17,14);p.cubicTo(21,14,23,17,23,21);c.drawPath(p,paint);break;
   case "settings":c.drawCircle(12,12,6,paint);c.drawCircle(12,12,2,paint);for(int i=0;i<8;i++){double angle=i*Math.PI/4;c.drawLine(12+6*(float)Math.cos(angle),12+6*(float)Math.sin(angle),12+9*(float)Math.cos(angle),12+9*(float)Math.sin(angle),paint);}break;
   case "shield":p.moveTo(12,2);p.lineTo(21,6);p.lineTo(20,14);p.quadTo(18,20,12,23);p.quadTo(6,20,4,14);p.lineTo(3,6);p.close();c.drawPath(p,paint);p.reset();p.moveTo(8,12);p.lineTo(11,15);p.lineTo(16,9);c.drawPath(p,paint);break;
   case "back":c.drawLine(20,12,4,12,paint);p.moveTo(11,5);p.lineTo(4,12);p.lineTo(11,19);c.drawPath(p,paint);break;
   case "reply":p.moveTo(10,5);p.lineTo(3,11);p.lineTo(10,17);c.drawPath(p,paint);p.reset();p.moveTo(4,11);p.lineTo(13,11);p.quadTo(21,11,21,20);c.drawPath(p,paint);break;
   case "copy":c.drawRoundRect(7,6,21,22,2,2,paint);p.moveTo(16,3);p.lineTo(3,3);p.lineTo(3,17);c.drawPath(p,paint);break;
   case "more":paint.setStyle(Paint.Style.FILL);for(int i=0;i<3;i++)c.drawCircle(5+i*7,12,1.6f,paint);break;
   case "delete":c.drawLine(3,6,21,6,paint);p.moveTo(8,5);p.lineTo(8,2);p.lineTo(16,2);p.lineTo(16,5);c.drawPath(p,paint);p.reset();p.moveTo(5,7);p.lineTo(7,22);p.lineTo(17,22);p.lineTo(19,7);c.drawPath(p,paint);c.drawLine(10,10,10,18,paint);c.drawLine(14,10,14,18,paint);break;
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
