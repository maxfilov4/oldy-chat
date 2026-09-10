package chat.oldy;
import android.graphics.*;import android.graphics.drawable.Drawable;
/** Distinct color palettes and original device-resolution art for each wallpaper. */
final class Wallpaper extends Drawable {
 static final String[] NAMES={"Аркада · синий","Созвездия · фиолетовый","Мягкий песок · бежевый","Киберпанк · неон","Гараж · графит и красный","Лес · зелёный","Закат на трассе · терракота","Горы · бирюзовый"};
 static final int[][] LIGHT={{0xffc7e1fa,0xff94bfd9},{0xffd6c1ef,0xffb29bd9},{0xffeddbbc,0xffcfad86},{0xffc0b9e6,0xff91c7d2},{0xffc2c7d2,0xffb29cae},{0xffc3dec1,0xff91bba7},{0xfff2ceaa,0xffd59f94},{0xffbde1e2,0xff94bdd6}};
 static final int[][] DARK={{0xff142943,0xff183b4d},{0xff392848,0xff282245},{0xff564234,0xff352e29},{0xff30163f,0xff082f40},{0xff28313d,0xff412431},{0xff183b31,0xff123733},{0xff654333,0xff432632},{0xff173c47,0xff1e314b}};
 final boolean light;final int style;final Paint p=new Paint(3);int alpha=255;
 Wallpaper(boolean l,int s){light=l;style=Math.floorMod(s,NAMES.length);}
 public void draw(Canvas canvas){Rect bounds=getBounds();if(bounds.isEmpty())return;Canvas c=canvas;c.save();c.translate(bounds.left,bounds.top);float w=bounds.width(),h=bounds.height();int[] colors=(light?LIGHT:DARK)[style];p.setStyle(Paint.Style.FILL);p.setAlpha(alpha);p.setShader(new LinearGradient(0,0,w,h,colors[0],colors[1],Shader.TileMode.CLAMP));c.drawRect(0,0,w,h,p);p.setShader(null);
  float density=android.content.res.Resources.getSystem().getDisplayMetrics().density;float cell=(style==4||style==6?112:92)*density;p.setStrokeWidth(1.2f*density);p.setColor(style==3?(light?0x38664e98:0x4057e9ec):light?0x28613e53:0x2a8adbc7);p.setStyle(Paint.Style.STROKE);
  if(style==3){for(float x=0;x<w;x+=cell/2)c.drawLine(x,0,x,h,p);for(float y=0;y<h;y+=cell/2)c.drawLine(0,y,w,y,p);}
  for(int y=0;y<h/cell+1;y++)for(int x=0;x<w/cell+1;x++){c.save();c.translate(x*cell+cell/2+(y%2)*cell/2,y*cell+cell/2);c.scale(density,density);p.setStrokeWidth(1.2f);int n=(x+3*y)%3;
   if(style==0){c.rotate((x+y)%2==0?-14:16);game(c,n);}
   else if(style==1){star(c,0,0,17);c.drawLine(14,8,36,20,p);c.drawCircle(38,21,3,p);c.drawLine(-7,-14,-24,-30,p);c.drawCircle(-26,-32,2,p);}
   else if(style==2){c.drawArc(-26,-20,26,20,15,140,false,p);c.drawArc(-26,-12,26,28,15,140,false,p);c.drawCircle(16,-20,3,p);}
   else if(style==3){c.drawLine(-24,-16,12,-16,p);c.drawLine(12,-16,26,-2,p);c.drawLine(26,-2,26,20,p);c.drawCircle(-26,-16,3,p);c.drawCircle(26,22,3,p);c.drawRect(-14,-6,8,14,p);c.drawLine(-21,26,7,26,p);}
   else if(style==4||style==6){if(n<2)car(c);else{c.drawCircle(0,0,23,p);c.drawCircle(0,0,8,p);for(int j=0;j<5;j++){c.save();c.rotate(j*72);c.drawLine(0,8,0,23,p);c.restore();}}}
   else if(style==5){if(n<2){Path leaf=new Path();leaf.moveTo(0,24);leaf.cubicTo(-37,-2,-12,-30,18,-31);leaf.cubicTo(34,-2,18,18,0,24);c.drawPath(leaf,p);c.drawLine(-5,30,16,-23,p);c.drawLine(2,12,-12,-2,p);c.drawLine(8,-3,22,-6,p);}else{Path tree=new Path();tree.moveTo(0,-29);tree.lineTo(-22,13);tree.lineTo(22,13);tree.close();c.drawPath(tree,p);c.drawLine(0,13,0,28,p);}}
   else{Path mountains=new Path();mountains.moveTo(-36,22);mountains.lineTo(-8,-22);mountains.lineTo(20,22);mountains.moveTo(7,2);mountains.lineTo(24,-17);mountains.lineTo(45,22);c.drawPath(mountains,p);c.drawCircle(27,-32,7,p);c.drawLine(-30,30,40,30,p);}
   c.restore();}
  p.setStyle(Paint.Style.FILL);c.restore();
 }
 void game(Canvas c,int n){if(n==0){c.drawRoundRect(-23,-14,23,14,9,9,p);c.drawLine(-15,0,-3,0,p);c.drawLine(-9,-6,-9,6,p);c.drawCircle(10,-4,2,p);c.drawCircle(16,4,2,p);}else if(n==1){c.drawRoundRect(-17,-24,17,24,4,4,p);c.drawRect(-12,-17,12,1,p);c.drawLine(-10,12,0,12,p);c.drawLine(-5,7,-5,17,p);c.drawCircle(10,12,2,p);}else star(c,0,0,20);}
 void car(Canvas c){Path body=new Path();body.moveTo(-37,10);body.lineTo(-31,-5);body.lineTo(-18,-7);body.lineTo(-7,-22);body.lineTo(15,-22);body.lineTo(28,-7);body.lineTo(37,-1);body.lineTo(38,13);body.lineTo(-37,13);body.close();c.drawPath(body,p);c.drawCircle(-21,14,7,p);c.drawCircle(24,14,7,p);c.drawLine(-5,-18,-12,-7,p);c.drawLine(-12,-7,22,-7,p);c.drawLine(6,-19,6,-7,p);c.drawLine(-35,0,-28,0,p);c.drawLine(-26,28,32,28,p);}
 void star(Canvas c,float x,float y,float radius){Path star=new Path();for(int j=0;j<10;j++){double angle=j*Math.PI/5-Math.PI/2;float r=j%2==0?radius:radius*.43f;float a=x+(float)Math.cos(angle)*r,b=y+(float)Math.sin(angle)*r;if(j==0)star.moveTo(a,b);else star.lineTo(a,b);}star.close();c.drawPath(star,p);}
 public void setAlpha(int value){alpha=value;invalidateSelf();}public void setColorFilter(ColorFilter filter){p.setColorFilter(filter);invalidateSelf();}public int getOpacity(){return PixelFormat.OPAQUE;}
}
