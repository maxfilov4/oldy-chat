package chat.oldy;
import android.graphics.*;import android.graphics.drawable.Drawable;
/** Distinct color palettes and original device-resolution art for each wallpaper. */
final class Wallpaper extends Drawable {
 static final String[] NAMES={"Аркада · синий","Созвездия · фиолетовый","Мягкий песок · бежевый","Киберпанк · неон","Гараж · графит и красный","Лес · зелёный","Закат на трассе · терракота","Горы · бирюзовый","Океан · лазурный","Сакура · розовый","Космос · индиго","Мята · свежий","Пиксели · янтарный","Лаванда · сиреневый","Граффити · малиновый","Луна · серебристый","Сияние · изумруд","Неоновый дождь · фуксия","Вишня · бордовый","Дюны · персиковый","Ночь · золото"};
 static final int[][] LIGHT={{0xffc7e1fa,0xff94bfd9},{0xffd6c1ef,0xffb29bd9},{0xffeddbbc,0xffcfad86},{0xffc0b9e6,0xff91c7d2},{0xffc2c7d2,0xffb29cae},{0xffc3dec1,0xff91bba7},{0xfff2ceaa,0xffd59f94},{0xffbde1e2,0xff94bdd6},{0xffb7e5f2,0xff7bb5d5},{0xfff4cfe1,0xffdfa6c7},{0xffc6c5ed,0xff939ed4},{0xffc3efe0,0xff94cbbc},{0xfff6dfb2,0xffdeba87},{0xffe2d5f4,0xffb6a3d8},{0xfff2bed4,0xffcb93b1},{0xffe0e5ed,0xffa6b3c6},{0xffb8f0dc,0xff9ec4ed},{0xffecb5f4,0xffa9bcf8},{0xfff3c5ca,0xffcf91a6},{0xfff6d9b8,0xffdfa399},{0xffecd9ac,0xffadbed0}};
 static final int[][] DARK={{0xff142943,0xff183b4d},{0xff392848,0xff282245},{0xff564234,0xff352e29},{0xff30163f,0xff082f40},{0xff28313d,0xff412431},{0xff183b31,0xff123733},{0xff654333,0xff432632},{0xff173c47,0xff1e314b},{0xff0e3552,0xff143a48},{0xff4c2540,0xff2d233a},{0xff171b43,0xff29214d},{0xff163a34,0xff12302d},{0xff503824,0xff332936},{0xff342846,0xff24283d},{0xff50253f,0xff242c48},{0xff293644,0xff182331},{0xff103e36,0xff192850},{0xff41114e,0xff102445},{0xff4d1c30,0xff281c39},{0xff5b372b,0xff3a2440},{0xff322d1d,0xff101d30}};
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
   else if(style==8){for(int k=0;k<3;k++){Path wave=new Path();wave.moveTo(-34,k*11-12);wave.cubicTo(-12,k*11-34,10,k*11+10,34,k*11-12);c.drawPath(wave,p);}}
   else if(style==9){for(int k=0;k<5;k++){c.save();c.rotate(k*72);c.drawOval(-7,-25,7,-3,p);c.restore();}c.drawCircle(0,0,4,p);}
   else if(style==10){c.drawCircle(0,0,18,p);c.save();c.rotate(-25);c.drawOval(-35,-8,35,8,p);c.restore();star(c,29,-25,6);}
   else if(style==11){c.drawOval(-26,-24,0,7,p);c.drawOval(0,-12,26,20,p);c.drawLine(-17,-13,16,23,p);}
   else if(style==12){for(int k=0;k<5;k++){int xx=(k%3)*11-18,yy=(k/3)*11-9;c.drawRect(xx,yy,xx+10,yy+10,p);}game(c,n);}
   else if(style==13){c.drawLine(0,29,0,-29,p);for(int k=0;k<4;k++){c.drawOval(-13,-26+k*12,0,-17+k*12,p);c.drawOval(0,-22+k*12,13,-13+k*12,p);}}
   else if(style==14){c.rotate(-15);c.drawRoundRect(-28,-15,28,15,7,7,p);c.drawLine(-18,5,-6,-6,p);c.drawLine(-6,-6,6,5,p);c.drawLine(6,5,19,-7,p);star(c,24,-24,8);}
   else if(style==15){c.drawArc(-21,-24,21,24,60,270,false,p);c.drawArc(-10,-24,32,22,80,210,false,p);c.drawCircle(28,-22,2,p);}
   else if(style==16){for(int k=0;k<3;k++){Path ribbon=new Path();ribbon.moveTo(-36,-24+k*15);ribbon.cubicTo(-14,-42+k*15,10,5+k*15,36,-18+k*15);c.drawPath(ribbon,p);}star(c,20,28,5);}
   else if(style==17){for(int k=0;k<4;k++)c.drawLine(-24+k*15,-30,-34+k*15,25-k*5,p);c.drawCircle(25,24,7,p);}
   else if(style==18){c.drawCircle(-10,10,11,p);c.drawCircle(15,15,11,p);Path stem=new Path();stem.moveTo(-10,-1);stem.quadTo(-1,-30,15,-26);stem.lineTo(15,4);c.drawPath(stem,p);c.drawOval(14,-30,32,-20,p);}
   else if(style==19){for(int k=0;k<3;k++){Path dune=new Path();dune.moveTo(-40,5+k*12);dune.cubicTo(-3,-24+k*12,12,26+k*12,40,0+k*12);c.drawPath(dune,p);}c.drawCircle(19,-29,8,p);}
   else if(style==20){star(c,0,0,16);c.drawCircle(28,-28,3,p);c.drawCircle(-30,23,2,p);c.drawArc(-32,-32,32,32,210,75,false,p);}
   else{Path mountains=new Path();mountains.moveTo(-36,22);mountains.lineTo(-8,-22);mountains.lineTo(20,22);mountains.moveTo(7,2);mountains.lineTo(24,-17);mountains.lineTo(45,22);c.drawPath(mountains,p);c.drawCircle(27,-32,7,p);c.drawLine(-30,30,40,30,p);}
   c.restore();}
  p.setStyle(Paint.Style.FILL);c.restore();
 }
 void game(Canvas c,int n){if(n==0){c.drawRoundRect(-23,-14,23,14,9,9,p);c.drawLine(-15,0,-3,0,p);c.drawLine(-9,-6,-9,6,p);c.drawCircle(10,-4,2,p);c.drawCircle(16,4,2,p);}else if(n==1){c.drawRoundRect(-17,-24,17,24,4,4,p);c.drawRect(-12,-17,12,1,p);c.drawLine(-10,12,0,12,p);c.drawLine(-5,7,-5,17,p);c.drawCircle(10,12,2,p);}else star(c,0,0,20);}
 void car(Canvas c){Path body=new Path();body.moveTo(-37,10);body.lineTo(-31,-5);body.lineTo(-18,-7);body.lineTo(-7,-22);body.lineTo(15,-22);body.lineTo(28,-7);body.lineTo(37,-1);body.lineTo(38,13);body.lineTo(-37,13);body.close();c.drawPath(body,p);c.drawCircle(-21,14,7,p);c.drawCircle(24,14,7,p);c.drawLine(-5,-18,-12,-7,p);c.drawLine(-12,-7,22,-7,p);c.drawLine(6,-19,6,-7,p);c.drawLine(-35,0,-28,0,p);c.drawLine(-26,28,32,28,p);}
 void star(Canvas c,float x,float y,float radius){Path star=new Path();for(int j=0;j<10;j++){double angle=j*Math.PI/5-Math.PI/2;float r=j%2==0?radius:radius*.43f;float a=x+(float)Math.cos(angle)*r,b=y+(float)Math.sin(angle)*r;if(j==0)star.moveTo(a,b);else star.lineTo(a,b);}star.close();c.drawPath(star,p);}
 public void setAlpha(int value){alpha=value;invalidateSelf();}public void setColorFilter(ColorFilter filter){p.setColorFilter(filter);invalidateSelf();}public int getOpacity(){return PixelFormat.OPAQUE;}
}
